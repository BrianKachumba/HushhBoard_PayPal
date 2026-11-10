/*
 * Copyright (C) 2026 HushhBoard
 * Licensed under the Apache License, Version 2.0.
 */

package dev.hushhboard.nlp

import kotlin.math.exp

/**
 * Public entry point of the HushhBoard word engine.
 *
 * Combines the static dictionary ([Lexicon] + [Decoder]) with the user's personal model
 * ([UserModel]). Pure Kotlin, no Android dependencies: the keyboard adapts it through a thin
 * provider, which keeps it swappable.
 *
 * Thread-safe; [suggest] typically takes 1-5 ms on a mid-range phone.
 */
class WordEngine(
    val lexicon: Lexicon,
    val userModel: UserModel = UserModel(),
    proximity: KeyProximity = KeyProximity.QWERTY,
    /** Shipped next-word model (optional): predictions after a space and context-aware ranking. */
    val ngrams: NgramModel? = null,
) {
    @Volatile
    private var decoder = Decoder(lexicon, proximity)

    @Volatile
    var proximity: KeyProximity = proximity
        private set

    /** Replace the key geometry (e.g. when the on-screen layout changes). */
    fun setProximity(proximity: KeyProximity) {
        this.proximity = proximity
        decoder = Decoder(lexicon, proximity)
    }

    enum class Kind { TYPED_VALID, COMPLETION, CORRECTION, SHORTCUT, PERSONAL, PREDICTION }

    /** How next-word predictions are cased: follow the keyboard's shift state like the keys do. */
    enum class NextWordCase {
        /** Capitalise at a sentence start (when the keyboard state is not known). */
        AUTO,
        /** Natural casing ("you", "I", "Nairobi"). */
        NONE,
        /** First letter upper case (the keyboard is shifted). */
        CAPITALIZE,
        /** All upper case (caps lock). */
        UPPER,
    }

    data class Suggestion(
        /** Text to insert, with the user's casing applied. */
        val text: String,
        /** Relative confidence in (0, 1]; the best suggestion has 1.0. */
        val confidence: Double,
        val kind: Kind,
        /** Raw ranking score (log-space). */
        val score: Double,
    )

    data class Result(
        val suggestions: List<Suggestion>,
        /** True if what was typed is itself a dictionary or personal word. */
        val typedWordIsValid: Boolean,
        /** The correction to apply automatically if the word is finished now, or null to leave it alone. */
        val autoCorrection: Suggestion? = null,
    )

    /**
     * Suggestions for the word being typed.
     *
     * @param composing the partial word at the cursor (may be empty: then next-word predictions are returned)
     * @param textBefore the text before the composing word (used for context)
     * @param touches tap position for each character of [composing] (key-width units), if known
     */
    fun suggest(
        composing: String,
        textBefore: CharSequence,
        maxCount: Int = 8,
        allowOffensive: Boolean = false,
        touches: List<TouchPoint?>? = null,
        nextWordCase: NextWordCase = NextWordCase.AUTO,
    ): Result {
        val typed = Text.normalizeApostrophes(composing.trim())
        val previous = Text.previousWords(textBefore, 1).firstOrNull()
        val previousKey = previous?.let { Text.normalizeKey(Text.normalizeApostrophes(it)) }

        val context = ngrams?.context(lexicon, textBefore)

        if (typed.isEmpty()) {
            return Result(predict(textBefore, previous, context, maxCount, allowOffensive, nextWordCase), false)
        }

        val typedLower = Text.normalizeKey(typed)
        val priors = userModel.languagePriors()
        val dec = decoder
        val offsets = if (touches != null) userModel.touchOffsets() else null
        val usableTouches = touches?.takeIf { it.size == typed.length }
        val pool = HashMap<String, Candidate>()

        for (c in dec.candidates(typed, allowOffensive, usableTouches, offsets)) {
            if (userModel.isBlocked(c.key)) continue
            c.score = dec.score(c, typedLower, priors) +
                userModel.wordBoost(c.key) +
                userModel.contextBoost(previousKey, c.key) +
                contextBoost(context, c.key, c.freq)
            put(pool, c)
        }
        if (context != null) addModelFollowers(dec, typedLower, context, priors, previousKey, allowOffensive, pool)
        addPersonalWords(dec, typed, typedLower, previousKey, pool)
        if (previous != null) addContextFollowers(dec, typed, typedLower, previous, previousKey!!, priors, pool)

        val ranked = pool.values.sortedWith(Decoder.RANKING)
        val typedInfo = lexicon.lookup(typed)
        val typedValid = typedInfo != null || userModel.isKnown(typed)

        val out = ArrayList<Suggestion>(maxCount)
        val seen = HashSet<String>()
        val bestScore = ranked.firstOrNull()?.score ?: 0.0
        for (c in ranked) {
            val text = Text.applyCase(typed, c.text)
            if (!seen.add(text)) continue
            out.add(Suggestion(text, exp(c.score - bestScore), kindOf(c, typedLower), c.score))
            if (out.size >= maxCount) break
        }

        val choice = AutoCorrector.choose(
            typed = typed,
            ranked = ranked.take(AUTOCORRECT_POOL),
            typedValid = typedValid,
            typedFreq = typedInfo?.freq ?: 0,
            sentenceStart = Text.isSentenceStart(textBefore),
            proximity = proximity,
            isRejected = { userModel.isCorrectionRejected(it) },
        )
        val auto = choice?.let { Suggestion(Text.applyCase(typed, it.text), 1.0, kindOf(it, typedLower), it.score) }
            ?.takeIf { it.text != typed }
        return Result(out, typedValid, auto)
    }

    private fun contextBoost(context: ContextModel?, key: String, freq: Int): Double {
        if (context == null || context.isEmpty || ' ' in key) return 0.0
        val node = lexicon.find(key)
        return if (node > 0) context.boost(node, freq) else 0.0
    }

    /**
     * Words the shipped model expects here that complete what was typed ("I want to b" -> "book"),
     * even when the dictionary search did not rank them. Mirrors `suggest_with_context` in ngrams.py.
     */
    private fun addModelFollowers(
        dec: Decoder,
        typedLower: String,
        context: ContextModel,
        priors: LanguagePriors,
        previousKey: String?,
        allowOffensive: Boolean,
        pool: HashMap<String, Candidate>,
    ) {
        for (node in context.logp.keys.sorted()) {
            val key = context.keyOf(node) ?: continue
            if (key in pool || !key.startsWith(typedLower) || userModel.isBlocked(key)) continue
            val flags = lexicon.flags(node)
            if (flags and Lexicon.FLAG_OFFENSIVE != 0 && !allowOffensive) continue
            val freq = lexicon.freq(node)
            val c = Candidate(key, lexicon.display(node), freq, flags, 0.0, key.length - typedLower.length)
            c.score = dec.score(c, typedLower, priors) + context.boost(node, freq) +
                userModel.wordBoost(key) + userModel.contextBoost(previousKey, key)
            pool[key] = c
        }
    }

    /**
     * Next-word predictions after a space. The shipped model's probabilities are interpolated
     * with this user's own follow-ups: the more often they have written after this word, the more
     * their habits count (up to [MAX_PERSONAL_SHARE]).
     */
    private fun predict(
        textBefore: CharSequence,
        previous: String?,
        context: ContextModel?,
        maxCount: Int,
        allowOffensive: Boolean,
        nextWordCase: NextWordCase,
    ): List<Suggestion> {
        val (total, mine) = if (previous != null) userModel.followers(previous, PREDICTION_POOL) else 0.0 to emptyList()
        val share = if (total > 0.0) minOf(MAX_PERSONAL_SHARE, total / (total + PERSONAL_PRIOR)) else 0.0
        val probs = HashMap<String, Double>()
        val displays = HashMap<String, String>()

        fun modelP(key: String): Double {
            if (context == null || ' ' in key) return 0.0
            val node = lexicon.find(key)
            if (node <= 0) return 0.0
            return context.logp[node]?.let { kotlin.math.exp(it) } ?: 0.0
        }

        if (context != null) {
            for ((node, lp) in context.predictions(PREDICTION_POOL, lexicon, allowOffensive)) {
                val key = context.keyOf(node) ?: continue
                if (userModel.isBlocked(key)) continue
                probs[key] = (1.0 - share) * kotlin.math.exp(lp)
                displays[key] = lexicon.display(node) ?: key
            }
        }
        for ((display, w) in mine) {
            val key = Text.normalizeKey(display)
            if (userModel.isBlocked(key)) continue
            val info = lexicon.lookup(key)
            if (info != null && !allowOffensive && info.flags and Lexicon.FLAG_OFFENSIVE != 0) continue
            val base = probs[key] ?: ((1.0 - share) * modelP(key))
            probs[key] = base + share * (w / total)
            // Dictionary words keep the dictionary's casing; the user's casing is for their own words.
            displays[key] = info?.display ?: display
        }
        if (probs.isEmpty()) return emptyList()

        val case = when (nextWordCase) {
            NextWordCase.AUTO -> if (Text.isSentenceStart(textBefore)) NextWordCase.CAPITALIZE else NextWordCase.NONE
            else -> nextWordCase
        }
        val ranked = probs.entries.sortedWith(
            compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key },
        ).take(maxCount)
        val best = ranked.first().value
        return ranked.map { (key, p) ->
            val d = displays[key] ?: key
            val text = when (case) {
                NextWordCase.UPPER -> d.uppercase()
                NextWordCase.CAPITALIZE -> if (d.isNotEmpty()) d.substring(0, 1).uppercase() + d.substring(1) else d
                else -> d
            }
            Suggestion(text, p / best, Kind.PREDICTION, kotlin.math.ln(p))
        }
    }

    private fun kindOf(c: Candidate, typedLower: String): Kind = when {
        c.cost == 0.0 && c.completionChars == 0 && c.key == typedLower -> Kind.TYPED_VALID
        c.isShortcut -> Kind.SHORTCUT
        c.flags == PERSONAL_FLAGS && c.freq == PERSONAL_BASE_FREQ -> Kind.PERSONAL
        c.completionChars > 0 -> Kind.COMPLETION
        else -> Kind.CORRECTION
    }

    private fun put(pool: HashMap<String, Candidate>, c: Candidate) {
        val prev = pool[c.key]
        if (prev == null || c.score > prev.score) pool[c.key] = c
    }

    @Volatile
    private var personalCache: Pair<Long, List<Pair<String, UserModel.WordStat>>>? = null

    @Volatile
    private var glideBase: Triple<Int, List<Pair<String, Int>>, Set<String>>? = null

    /** Trusted personal words that are not in the dictionary (names, slang, ...). */
    private fun personalWords(): List<Pair<String, UserModel.WordStat>> {
        // Rebuilt only when the set of trusted words changes, not after every word typed.
        val rev = userModel.vocabularyRevision
        personalCache?.let { if (it.first == rev) return it.second }
        val list = userModel.trustedWords().filter { (key, _) -> lexicon.find(key).let { it < 0 || !lexicon.isTerminal(it) } }
        personalCache = rev to list
        return list
    }

    /** Aligns [typed] with an out-of-trie word; returns (cost, completionChars) or null if too far. */
    private fun alignForPool(dec: Decoder, typed: String, key: String, n: Int): Pair<Double, Int>? {
        val maxCost = DecoderConfig.maxCostFor(n)
        val compMax = DecoderConfig.completionMaxCostFor(n)
        val (full, prefix, prefixLen) = dec.align(typed, key)
        return when {
            full <= maxCost && (full <= prefix || prefixLen >= key.length) -> full to 0
            prefix <= compMax && prefixLen < key.length -> prefix to (key.length - prefixLen)
            full <= maxCost -> full to 0
            else -> null
        }
    }

    private fun addPersonalWords(
        dec: Decoder,
        typed: String,
        typedLower: String,
        previousKey: String?,
        pool: HashMap<String, Candidate>,
    ) {
        val n = typedLower.length
        val first = typedLower[0]
        for ((key, stat) in personalWords()) {
            if (key.isEmpty()) continue
            // Cheap pre-filter: first letter equal or adjacent, length plausible.
            if (key[0] != first && KeyProximity.QWERTY.distance(key[0], first) > DecoderConfig.NEAR_DIST) continue
            if (key.length < n - 3) continue
            val (cost, compChars) = alignForPool(dec, typed, key, n) ?: continue
            val c = Candidate(key, stat.display.takeIf { it != key }, PERSONAL_BASE_FREQ, PERSONAL_FLAGS, cost, compChars)
            var score = DecoderConfig.freqLogp(PERSONAL_BASE_FREQ) -
                DecoderConfig.penalty(cost, compChars) +
                userModel.wordBoost(key) +
                userModel.contextBoost(previousKey, key)
            if (cost == 0.0 && compChars == 0 && key == typedLower) score += DecoderConfig.VALID_TYPED_BONUS
            c.score = score
            put(pool, c)
        }
    }

    /**
     * Words this user often types after [previous] are considered even when the dictionary search
     * did not surface them (e.g. "habari y" -> "yako" after one letter).
     */
    private fun addContextFollowers(
        dec: Decoder,
        typed: String,
        typedLower: String,
        previous: String,
        previousKey: String,
        priors: LanguagePriors,
        pool: HashMap<String, Candidate>,
    ) {
        val n = typedLower.length
        for ((display, _) in userModel.predictNext(previous, MAX_CONTEXT_FOLLOWERS)) {
            val key = Text.normalizeKey(display)
            if (key in pool || userModel.isBlocked(key)) continue
            val (cost, compChars) = alignForPool(dec, typed, key, n) ?: continue
            val info = lexicon.lookup(key)
            val c = if (info != null) {
                Candidate(key, info.display.takeIf { it != key }, info.freq, info.flags, cost, compChars)
            } else {
                Candidate(key, display.takeIf { it != key }, PERSONAL_BASE_FREQ, PERSONAL_FLAGS, cost, compChars)
            }
            val base = DecoderConfig.freqLogp(c.freq) + (if (info != null) priors.forFlags(info.flags) else 0.0)
            var score = base - DecoderConfig.penalty(cost, compChars) +
                userModel.wordBoost(key) + userModel.contextBoost(previousKey, key)
            if (cost == 0.0 && compChars == 0 && key == typedLower) score += DecoderConfig.VALID_TYPED_BONUS
            c.score = score
            put(pool, c)
        }
    }

    // ---------------------------------------------------------------- autocorrect & touch API

    /**
     * The user undid an automatic correction (backspace right after it): remember not to make
     * that correction again, and trust [original] as a real word.
     */
    fun onAutoCorrectionUndone(original: String) = userModel.rejectCorrection(original)

    /** Learn the user's tap offsets from a word typed exactly as intended (a valid word, no correction). */
    fun learnTouches(word: String, taps: List<TouchPoint?>) {
        if (taps.size != word.length || !lexicon.contains(word)) return
        userModel.learnTouches(word, taps, proximity)
    }

    /**
     * Smart key targets: the key that should register for a tap at [tap] whose rectangle hit
     * [default], given the word typed so far ([prefix]).
     */
    fun resolveTap(prefix: String, tap: TouchPoint, default: Char): Char {
        val d = default.lowercaseChar()
        if (!d.isLetter()) return default
        val prox = proximity
        val chosen = SmartTargets.choose(
            lexicon, prox, Text.normalizeKey(prefix), tap, d, prox.neighbours(d), userModel.touchOffsets(),
        )
        return if (chosen == d) default else if (default.isUpperCase()) chosen.uppercaseChar() else chosen
    }

    /** True if [word] is a dictionary word, a trusted personal word, or not checkable (digits, URLs…). */
    fun isValidWord(word: String): Boolean {
        val w = Text.normalizeApostrophes(word.trim())
        if (w.isEmpty() || !Text.isLearnable(w)) return true
        if (w.length <= 5 && w.all { !it.isLetter() || it.isUpperCase() }) return true // acronyms: KCB, SGR
        if (lexicon.contains(w)) return true
        return userModel.isKnown(w)
    }

    data class SpellCheck(val isValid: Boolean, val suggestions: List<String>)

    /** Spell-check a single word (for the system spell-checker service). */
    fun spell(word: String, maxSuggestions: Int = 5, allowOffensive: Boolean = false): SpellCheck {
        if (isValidWord(word)) return SpellCheck(true, emptyList())
        val typed = Text.normalizeApostrophes(word.trim())
        val priors = userModel.languagePriors()
        val dec = decoder
        val typedLower = Text.normalizeKey(typed)
        val fixes = dec.candidates(typed, allowOffensive)
            .filter { it.completionChars == 0 && !userModel.isBlocked(it.key) }
            .onEach { it.score = dec.score(it, typedLower, priors) + userModel.wordBoost(it.key) }
            .sortedWith(Decoder.RANKING)
            .map { Text.applyCase(typed, it.text) }
            .distinct()
            .take(maxSuggestions)
        return SpellCheck(false, fixes)
    }

    // ---------------------------------------------------------------- learning API

    /**
     * The user finished typing [word]; [previousWord] precedes it in the same sentence.
     * [sentenceStart]: the word began a sentence, so a leading capital says nothing about the word.
     */
    fun learn(word: String, previousWord: String?, sentenceStart: Boolean = false) {
        val flags = lexicon.lookup(Text.normalizeApostrophes(word))?.flags ?: 0
        userModel.learnWord(word, previousWord, flags, sentenceStart)
    }

    /** The user tapped [word] in the suggestion bar. */
    fun onSuggestionAccepted(word: String) {
        if (!lexicon.contains(Text.normalizeApostrophes(word))) userModel.confirmWord(word)
    }

    /** The user long-pressed and removed [word] from suggestions. */
    fun removeSuggestion(word: String) = userModel.blockWord(word)

    /** Words for glide typing: the most frequent dictionary words plus trusted personal words. */
    fun glideWords(limit: Int = GLIDE_WORD_LIMIT): List<Pair<String, Int>> {
        // The dictionary part never changes: build it once (it walks the whole dictionary).
        val cached = glideBase
        val (base, have) = if (cached != null && cached.first == limit) {
            cached.second to cached.third
        } else {
            val list = lexicon.topWords(limit)
            val keys = list.mapTo(HashSet()) { Text.normalizeKey(it.first) }
            glideBase = Triple(limit, list, keys)
            list to keys
        }
        val personal = personalWords().filter { it.first !in have }.map { it.second.display to PERSONAL_BASE_FREQ }
        return base + personal
    }

    /** Frequency in 0..1 for glide-typing ranking. */
    fun frequencyOf(word: String): Double {
        val info = lexicon.lookup(Text.normalizeApostrophes(word))
        if (info != null) return info.freq / 255.0
        return if (userModel.isKnown(word)) PERSONAL_BASE_FREQ / 255.0 else 0.0
    }

    companion object {
        /** Frequency assigned to personal words that are not in the dictionary. */
        const val PERSONAL_BASE_FREQ = 150
        const val GLIDE_WORD_LIMIT = 60_000
        const val MAX_CONTEXT_FOLLOWERS = 20
        /** Candidate predictions considered before ranking. */
        const val PREDICTION_POOL = 12
        /** Most a user's own habits can weigh against the shipped model in predictions. */
        const val MAX_PERSONAL_SHARE = 0.85
        /** Pseudo-count: after this many follow-ups of a word, the user's habits weigh half. */
        const val PERSONAL_PRIOR = 3.0
        /** Candidates considered by autocorrect: matches the reference implementation (top 8). */
        const val AUTOCORRECT_POOL = 8
        /** Personal words carry no dictionary flags. */
        const val PERSONAL_FLAGS = 0
    }
}

/**
 * Detects when the user has just finished a word (typed a space or punctuation after it) by
 * watching successive editor states, so learning needs no hooks inside the keyboard core.
 * Not thread-safe: feed it from one place.
 */
class CommitDetector {
    data class Committed(val word: String, val previousWord: String?, val sentenceStart: Boolean = false)

    /**
     * True while the user is typing here (letters, spaces, picking suggestions), false after the
     * field was just opened or the cursor jumped. Next-word predictions are only shown while
     * typing; otherwise the toolbar stays visible.
     */
    var isTyping: Boolean = false
        private set

    private var lastCursor = -1
    private var lastComposing = ""
    private var lastEndedInWord = false
    private var lastLearnedStart = -1
    private var lastLearnedWord = ""

    fun reset() {
        lastCursor = -1
        lastComposing = ""
        lastEndedInWord = false
        isTyping = false
    }

    /**
     * @param textBeforeCursor editor text before the cursor (including any composing text)
     * @param cursor absolute cursor position in the editor
     * @param composing the current composing word (empty if none)
     */
    fun observe(textBeforeCursor: CharSequence, cursor: Int, composing: String): Committed? {
        var result: Committed? = null
        val advanced = cursor - lastCursor
        val endsInWord = textBeforeCursor.isNotEmpty() && Text.isWordChar(textBeforeCursor.last())
        isTyping = when {
            lastCursor < 0 || textBeforeCursor.isEmpty() -> false
            advanced == 0 -> isTyping || composing.isNotEmpty()
            kotlin.math.abs(advanced) <= Text.MAX_WORD_LENGTH + 3 ->
                isTyping || composing.isNotEmpty() || lastComposing.isNotEmpty() || lastEndedInWord
            else -> false
        }
        if (composing.isEmpty() && lastCursor >= 0 && advanced in 1..(Text.MAX_WORD_LENGTH + 3) &&
            (lastComposing.isNotEmpty() || lastEndedInWord) && !endsInWord && textBeforeCursor.isNotEmpty()
        ) {
            result = extract(textBeforeCursor, cursor)
        }
        lastCursor = cursor
        lastComposing = composing
        lastEndedInWord = endsInWord
        return result
    }

    private fun extract(text: CharSequence, cursor: Int): Committed? {
        var i = text.length - 1
        var separators = 0
        while (i >= 0 && !Text.isWordChar(text[i])) {
            separators++
            if (separators > 3) return null
            i--
        }
        if (i < 0) return null
        val end = i + 1
        while (i >= 0 && Text.isWordChar(text[i])) i--
        val start = i + 1
        val word = text.subSequence(start, end).toString().trim('\'', '-', '’')
        if (!Text.isLearnable(Text.normalizeApostrophes(word))) return null
        val absStart = cursor - (text.length - start)
        if (absStart == lastLearnedStart && word == lastLearnedWord) return null
        lastLearnedStart = absStart
        lastLearnedWord = word
        val head = text.subSequence(0, start)
        val previous = Text.previousWords(head, 1).firstOrNull()
        return Committed(word, previous, Text.isSentenceStart(head))
    }
}
