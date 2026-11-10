/*
 * Copyright (C) 2026 HushhBoard
 * Licensed under the Apache License, Version 2.0.
 */

package dev.hushhboard.nlp

/**
 * Decides whether a finished word should be corrected automatically (on space or punctuation).
 *
 * Precision first: a wrong automatic change is far worse than a missed one, because the user's
 * correct text gets damaged. Every rule below exists because of a measured failure case; the
 * thresholds were tuned with tools/nlp/eval_ac.py against typos that must be fixed and valid
 * unknown words (names, Sheng, rare words, Swahili word forms) that must be left alone.
 * Mirrors `autocorrect_choice` in tools/nlp/hdict.py; GoldenAutocorrectTest enforces parity.
 */
object AutoCorrector {
    const val MIN_SCORE = -10.0
    const val SCORE_SLOPE = 1.0
    const val MIN_GAP = 2.5
    const val COST_SCALE = 1.0
    const val JOINER_ONLY_COST = 0.15
    const val APOSTROPHE_MARGIN = 80
    const val JOINER_GAP = 1.0

    private val EN_SUFFIXES = setOf(
        "s", "es", "ed", "d", "r", "st", "ing", "er", "ers", "est", "ly", "ies", "ied", "ier", "ment", "ness", "'s",
    )
    private val SW_INFIXES = listOf("m", "ni", "ku", "wa", "ki", "vi", "li", "zi", "tu", "mw", "ji")
    private val SW_DERIVATIONS = setOf("wa", "ia", "ea", "iwa", "ewa", "ika", "eka", "isha", "esha", "ana")

    fun minScore(n: Int): Double = MIN_SCORE - SCORE_SLOPE * maxOf(0, n - 4)

    fun maxCost(n: Int): Double = when {
        n <= 2 -> JOINER_ONLY_COST
        n == 3 -> 0.6 * COST_SCALE
        n == 4 -> 0.9 * COST_SCALE
        n <= 6 -> 1.2 * COST_SCALE
        else -> 1.5 * COST_SCALE
    }

    /**
     * @param typed what the user typed (original casing)
     * @param ranked scored candidates, best first (as returned by [Decoder.suggest] / the engine)
     * @param typedValid the typed word is a dictionary or trusted personal word
     * @param typedFreq dictionary frequency of the typed word (0 if not in the dictionary)
     * @param sentenceStart the word begins a sentence (so a capital letter is not evidence of a name)
     * @param isRejected true for words whose correction the user has undone before
     * @return the candidate to apply, or null to leave the word alone
     */
    fun choose(
        typed: String,
        ranked: List<Candidate>,
        typedValid: Boolean,
        typedFreq: Int,
        sentenceStart: Boolean,
        proximity: KeyProximity = KeyProximity.QWERTY,
        isRejected: (String) -> Boolean = { false },
    ): Candidate? {
        if (!isPlainWord(typed)) return null
        val tl = Text.normalizeKey(typed)
        if (isRejected(tl)) return null
        var letters = 0
        var allUpper = true
        for (c in typed) if (c.isLetter()) {
            letters++
            if (!c.isUpperCase()) allUpper = false
        }
        if (letters >= 2 && allUpper) return null // acronyms, shouting

        if (typedValid) {
            for (c in ranked) {
                // "cant" -> "can't", "wont" -> "won't" (but never "well" -> "we'll", "ill" -> "I'll").
                if (c.completionChars == 0 && c.key != tl && c.key.replace("'", "") == tl &&
                    c.freq - typedFreq >= APOSTROPHE_MARGIN
                ) return c
            }
            for (c in ranked) {
                // Words that only exist capitalised: "i" -> "I", "nairobi" -> "Nairobi".
                if (c.key == tl && c.cost == 0.0 && c.completionChars == 0) {
                    val d = c.display
                    return if (d != null && d != typed && typed == tl && d.lowercase() == tl) c else null
                }
            }
            return null
        }

        // After space the word is finished, so only whole-word readings compete.
        val full = ranked.filter { it.completionChars == 0 && it.key != tl }
        if (full.isEmpty()) return null
        val best = full[0]
        val n = tl.length
        var maxCost = maxCost(n)
        val probableName = typed[0].isUpperCase() && !sentenceStart
        if (probableName) maxCost = minOf(maxCost, JOINER_ONLY_COST)
        if (best.cost > maxCost || best.score < minScore(n)) return null
        if (' ' in best.key) return null // never auto-expand multi-word shortcuts ("omw")
        if (best.isShortcut && stripJoiners(best.key) != tl) return null // "ur" -> "your" stays a suggestion
        if (isInflectionPair(tl, best.key)) return null
        val swOrKe = best.flags and (Lexicon.FLAG_LANG_SW or Lexicon.FLAG_LANG_KE) != 0
        if (swOrKe && best.flags and Lexicon.FLAG_LANG_EN == 0) {
            // Swahili builds words from prefixes and suffixes; unknown forms are usually valid.
            if (sharesSwahiliAffix(tl, best.key) || isSwahiliMorphVariant(tl, best.key)) return null
        }
        if (swOrKe && tl.length == best.key.length + 1 && tl.startsWith(best.key)) return null // "wangui" vs "wangu"
        if (farFirstLetter(proximity, tl, best.key)) return null
        val gap = if (stripJoiners(best.key) == tl) JOINER_GAP else MIN_GAP
        if (full.size > 1 && best.score - full[1].score < gap) return null
        return best
    }

    fun isPlainWord(typed: String): Boolean {
        if (typed.isEmpty() || !typed[0].isLetter()) return false
        return typed.all { it.isLetter() || Text.isJoiner(it) }
    }

    private fun stripJoiners(s: String) = s.replace("'", "").replace("-", "")

    private fun commonPrefixLength(a: String, b: String): Int {
        var n = 0
        while (n < a.length && n < b.length && a[n] == b[n]) n++
        return n
    }

    /** Both words share a 4+ letter stem and differ only by common English endings ("dramatises"/"dramatised"). */
    fun isInflectionPair(a: String, b: String): Boolean {
        val p = commonPrefixLength(a, b)
        if (p < 4) return false
        val ra = a.substring(p)
        val rb = b.substring(p)
        if (ra == rb) return false
        return (ra.isEmpty() || ra in EN_SUFFIXES) && (rb.isEmpty() || rb in EN_SUFFIXES)
    }

    /** Valid Swahili derivations of each other: passive (-wa), applicative (-ia/-ea), object infix (-m-). */
    fun isSwahiliMorphVariant(a: String, b: String): Boolean {
        if (a.length == b.length) return false
        val short = if (a.length < b.length) a else b
        val long = if (a.length < b.length) b else a
        if (short.length < 4) return false
        if (short.endsWith("a") && long.substring(0, short.length - 1) == short.substring(0, short.length - 1)) {
            if (long.substring(short.length - 1) in SW_DERIVATIONS) return true
        }
        for (infix in SW_INFIXES) {
            if (long.length - short.length != infix.length) continue
            for (i in 2 until short.length - 2) {
                if (long.regionMatches(0, short, 0, i) &&
                    long.regionMatches(i, infix, 0, infix.length) &&
                    long.regionMatches(i + infix.length, short, i, short.length - i)
                ) return true
            }
        }
        return false
    }

    /** Words that share a 4+ letter ending and differ only in a short grammatical prefix. */
    fun sharesSwahiliAffix(a: String, b: String): Boolean {
        if (a.length < 5 || b.length < 5) return false
        var k = 0
        val m = minOf(a.length, b.length)
        while (k < m && a[a.length - 1 - k] == b[b.length - 1 - k]) k++
        if (k < 4) return false
        val pa = a.substring(0, a.length - k)
        val pb = b.substring(0, b.length - k)
        return pa != pb && pa.length <= 5 && pb.length <= 5
    }

    /** First letters differ and are not neighbouring keys (and it is not a swap of the first two letters). */
    fun farFirstLetter(proximity: KeyProximity, a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty() || Text.baseLetter(a[0]) == Text.baseLetter(b[0])) return false
        if (a.length > 1 && b.length > 1 && a[0] == b[1] && a[1] == b[0]) return false
        if (Text.isJoiner(a[0]) || Text.isJoiner(b[0])) return false
        return proximity.distance(a[0], b[0]) > DecoderConfig.NEAR_DIST
    }
}
