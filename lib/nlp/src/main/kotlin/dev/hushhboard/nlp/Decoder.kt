/*
 * Copyright (C) 2026 HushhBoard
 * Licensed under the Apache License, Version 2.0.
 */

package dev.hushhboard.nlp

import java.util.PriorityQueue

/**
 * Scoring and search constants. Tuned offline with tools/nlp/eval.py and kept identical to the
 * reference implementation in tools/nlp/hdict.py (the golden tests enforce this).
 */
object DecoderConfig {
    // Edit costs (1.0 = an unrelated keystroke).
    const val SUB_ACCENT = 0.10
    const val SUB_NEAR = 0.40
    const val SUB_MID = 0.70
    const val SUB_FAR = 1.00
    const val FIRST_CHAR_EXTRA = 0.25
    const val JOINER_COST = 0.12
    const val DEL_DOUBLE = 0.35
    const val DEL_NEAR = 0.55
    const val DEL_FAR = 0.85
    const val INS_DOUBLE = 0.40
    const val INS_FAR = 0.85
    const val TRANSPOSE = 0.55
    const val NEAR_DIST = 1.25
    const val MID_DIST = 2.1

    // Scoring (natural-log units).
    const val COST_WEIGHT = 8.0
    const val FREQ_SPAN = 14.0
    const val COMPLETION_BASE = 0.8
    const val COMPLETION_PER_CHAR = 0.2
    const val VALID_TYPED_BONUS = 0.5
    const val SHORTCUT_COST = 0.1
    const val SHORTCUT_RANK_STEP = 0.5
    const val SHORTCUT_MIN_FREQ = 185
    const val COMPLETIONS_PER_ROOT = 6
    const val MAX_COMPLETION_ROOTS = 12

    // Touch model (key-width units).
    const val TOUCH_SIGMA_X = 0.33
    const val TOUCH_SIGMA_Y = 0.40
    const val TOUCH_SUB_BASE = 0.15

    const val PRIOR_EN = 0.0
    const val PRIOR_SW = -1.2
    const val PRIOR_KE = -0.6

    fun maxCostFor(n: Int): Double = when {
        n <= 1 -> 0.0
        n == 2 -> 0.45
        n == 3 -> 0.9
        n == 4 -> 1.1
        n <= 6 -> 1.5
        n <= 9 -> 1.9
        else -> 2.3
    }

    fun completionMaxCostFor(n: Int): Double = when {
        n <= 2 -> 0.1
        n == 3 -> 0.45
        else -> 0.9
    }

    fun penalty(cost: Double, completionChars: Int): Double {
        var p = COST_WEIGHT * cost
        if (completionChars > 0) p += COMPLETION_BASE + COMPLETION_PER_CHAR * completionChars
        return p
    }

    fun freqLogp(freq: Int): Double = (freq - 255) / 255.0 * FREQ_SPAN
}

/** Language priors (log-space bonus per language). Adapted per user by [UserModel]. */
data class LanguagePriors(
    val en: Double = DecoderConfig.PRIOR_EN,
    val sw: Double = DecoderConfig.PRIOR_SW,
    val ke: Double = DecoderConfig.PRIOR_KE,
) {
    fun forFlags(flags: Int): Double {
        var best = Double.NaN
        if (flags and Lexicon.FLAG_LANG_EN != 0) best = en
        if (flags and Lexicon.FLAG_LANG_SW != 0) best = if (best.isNaN()) sw else maxOf(best, sw)
        if (flags and Lexicon.FLAG_LANG_KE != 0) best = if (best.isNaN()) ke else maxOf(best, ke)
        return if (best.isNaN()) 0.0 else best
    }

    companion object {
        val DEFAULT = LanguagePriors()
    }
}

/** A dictionary word that could be what the user meant. */
class Candidate(
    val key: String,
    /** Canonical display form if it differs from [key] (e.g. "Nairobi", "I'm"). */
    val display: String?,
    val freq: Int,
    val flags: Int,
    /** Edit cost between what was typed and this word (or its prefix, for completions). */
    val cost: Double,
    /** Characters added beyond what was typed (0 = full-word match). */
    val completionChars: Int,
    /** Came from the texting-shortcut table ("ur" -> "your") rather than from spelling. */
    val isShortcut: Boolean = false,
) {
    var score: Double = 0.0
    val text: String get() = display ?: key
    val isCompletion: Boolean get() = completionChars > 0
    val isOffensive: Boolean get() = flags and Lexicon.FLAG_OFFENSIVE != 0
    override fun toString() = "Candidate($text, cost=$cost, +$completionChars, f=$freq, score=$score)"
}

/**
 * Finds likely intended words for a typed string in one pass over the dictionary trie:
 *  - full-word corrections via a keyboard-aware weighted Damerau-Levenshtein distance,
 *    computed incrementally along the trie and pruned as soon as it exceeds the budget;
 *  - completions, found best-first by frequency below every prefix that matches the input;
 *  - texting shortcuts ("im" -> "I'm").
 *
 * Thread-safe: holds no mutable state between calls.
 */
class Decoder(
    private val lexicon: Lexicon,
    private val proximity: KeyProximity = KeyProximity.QWERTY,
) {
    private fun subCost(t: Char, c: Char, tap: TouchPoint? = null, offsets: TouchOffsets? = null): Double {
        if (t == c) return 0.0
        if (Text.isJoiner(c) || Text.isJoiner(t)) return DecoderConfig.SUB_FAR
        if (Text.baseLetter(t) == Text.baseLetter(c)) return DecoderConfig.SUB_ACCENT
        if (tap != null) {
            val ct = proximity.center(t)
            val cc = proximity.center(c)
            if (ct != null && cc != null) {
                var nats = TouchMath.nats(tap, cc, offsets, c) - TouchMath.nats(tap, ct, offsets, t)
                if (nats < 0.0) nats = 0.0
                val cost = DecoderConfig.TOUCH_SUB_BASE + nats / DecoderConfig.COST_WEIGHT
                return if (cost > DecoderConfig.SUB_FAR) DecoderConfig.SUB_FAR else cost
            }
        }
        val d = proximity.distance(t, c)
        if (d <= DecoderConfig.NEAR_DIST) return DecoderConfig.SUB_NEAR
        if (d <= DecoderConfig.MID_DIST) return DecoderConfig.SUB_MID
        return DecoderConfig.SUB_FAR
    }

    private fun delCost(typed: String, i: Int): Double {
        val t = typed[i]
        if (Text.isJoiner(t)) return DecoderConfig.JOINER_COST
        if (i >= 1) {
            val p = typed[i - 1]
            if (p == t) return DecoderConfig.DEL_DOUBLE
            if (proximity.distance(p, t) <= DecoderConfig.NEAR_DIST) return DecoderConfig.DEL_NEAR
        }
        return DecoderConfig.DEL_FAR
    }

    private fun insCost(c: Char, prev: Char?): Double {
        if (Text.isJoiner(c)) return DecoderConfig.JOINER_COST
        if (prev != null && prev == c) return DecoderConfig.INS_DOUBLE
        return DecoderConfig.INS_FAR
    }

    /**
     * All candidates for [typed] (unscored, unordered).
     *
     * @param touches optional tap position (key-width units, same space as [proximity]) for each typed
     *   character; when present, substitutions are priced by how close the tap really was to each key.
     * @param offsets the user's learned per-key tap offsets, if any.
     */
    fun candidates(
        typed: String,
        allowOffensive: Boolean = false,
        touches: List<TouchPoint?>? = null,
        offsets: TouchOffsets? = null,
    ): List<Candidate> {
        val t = Text.normalizeKey(Text.normalizeApostrophes(typed))
        val n = t.length
        if (n == 0 || n > Text.MAX_WORD_LENGTH) return emptyList()
        return Search(t, allowOffensive, touches, offsets).run()
    }

    /** Scores and ranks candidates; best first. */
    fun suggest(
        typed: String,
        maxCount: Int = 8,
        allowOffensive: Boolean = false,
        priors: LanguagePriors = LanguagePriors.DEFAULT,
        touches: List<TouchPoint?>? = null,
        offsets: TouchOffsets? = null,
    ): List<Candidate> {
        val cands = candidates(typed, allowOffensive, touches, offsets)
        val tl = Text.normalizeKey(Text.normalizeApostrophes(typed))
        for (c in cands) c.score = score(c, tl, priors)
        return cands.sortedWith(RANKING).take(maxCount)
    }

    fun score(c: Candidate, typedLower: String, priors: LanguagePriors = LanguagePriors.DEFAULT): Double {
        var s = DecoderConfig.freqLogp(c.freq) + priors.forFlags(c.flags) -
            DecoderConfig.penalty(c.cost, c.completionChars)
        if (c.cost == 0.0 && c.completionChars == 0 && c.key == typedLower) s += DecoderConfig.VALID_TYPED_BONUS
        return s
    }

    /**
     * Weighted alignment of [typed] against an arbitrary [word] outside the trie (used for the
     * user's personal words). Returns (fullWordCost, bestPrefixCost, prefixLength).
     */
    fun align(typed: String, word: String): Triple<Double, Double, Int> {
        val t = Text.normalizeKey(Text.normalizeApostrophes(typed))
        val w = Text.normalizeKey(word)
        val n = t.length
        val m = w.length
        val del = DoubleArray(n) { delCost(t, it) }
        val rows = Array(m + 1) { DoubleArray(n + 1) }
        for (i in 1..n) rows[0][i] = rows[0][i - 1] + del[i - 1]
        var bestPrefix = if (n == 0) 0.0 else Double.MAX_VALUE
        var bestPrefixLen = 0
        for (j in 1..m) {
            val c = w[j - 1]
            val prevC = if (j >= 2) w[j - 2] else null
            val row = rows[j - 1]
            val new = rows[j]
            new[0] = row[0] + insCost(c, prevC)
            for (i in 1..n) {
                var sub = subCost(t[i - 1], c)
                if (i == 1 && j == 1 && sub > 0.0) sub += DecoderConfig.FIRST_CHAR_EXTRA
                var v = row[i - 1] + sub
                val v2 = new[i - 1] + del[i - 1]
                if (v2 < v) v = v2
                val v3 = row[i] + insCost(c, prevC)
                if (v3 < v) v = v3
                if (j >= 2 && i >= 2 && prevC != null && t[i - 1] == prevC && t[i - 2] == c && t[i - 1] != t[i - 2]) {
                    val v4 = rows[j - 2][i - 2] + DecoderConfig.TRANSPOSE
                    if (v4 < v) v = v4
                }
                new[i] = v
            }
            if (new[n] < bestPrefix - 1e-9) {
                bestPrefix = new[n]
                bestPrefixLen = j
            }
        }
        return Triple(rows[m][n], bestPrefix, bestPrefixLen)
    }

    private inner class Search(
        private val t: String,
        private val allowOffensive: Boolean,
        private val touches: List<TouchPoint?>?,
        private val offsets: TouchOffsets?,
    ) {
        private val n = t.length
        private val maxCost = DecoderConfig.maxCostFor(n)
        private val compMax = DecoderConfig.completionMaxCostFor(n)
        private val pruneAt = maxOf(maxCost, compMax)
        private val maxDepth = n + 3
        private val rows = Array(maxDepth + 1) { DoubleArray(n + 1) }
        private val path = CharArray(maxDepth + 1)
        private val del = DoubleArray(n) { delCost(t, it) }
        private fun tapAt(i: Int): TouchPoint? = touches?.let { if (i < it.size) it[i] else null }
        private val subAscii = Array(n) { i ->
            val tap = tapAt(i)
            DoubleArray(128) { c -> subCost(t[i], c.toChar(), tap, offsets) }
        }
        private val found = HashMap<String, Candidate>()
        private val roots = ArrayList<Root>()

        private inner class Root(val cost: Double, val maxFreq: Int, val node: Int, val path: String)

        fun run(): List<Candidate> {
            for (i in 1..n) rows[0][i] = rows[0][i - 1] + del[i - 1]
            dfs(Lexicon.ROOT, 0, Double.POSITIVE_INFINITY)
            completions()
            shortcuts()
            found.remove("")
            return found.values.toList()
        }

        private fun sub(i: Int, c: Char): Double =
            if (c.code < 128) subAscii[i][c.code] else subCost(t[i], c, tapAt(i), offsets)

        private fun consider(key: String, node: Int, freq: Int, flags: Int, cost: Double, compChars: Int) {
            if (flags and Lexicon.FLAG_OFFENSIVE != 0 && !allowOffensive && !(key == t && cost == 0.0)) return
            val prev = found[key]
            if (prev == null ||
                DecoderConfig.penalty(cost, compChars) < DecoderConfig.penalty(prev.cost, prev.completionChars)
            ) {
                found[key] = Candidate(key, lexicon.display(node), freq, flags, cost, compChars)
            }
        }

        private fun dfs(node: Int, depth: Int, rootCostIn: Double) {
            var rootCost = rootCostIn
            val row = rows[depth]
            if (depth > 0) {
                val flags = lexicon.flags(node)
                if (flags and Lexicon.FLAG_TERMINAL != 0 && row[n] <= maxCost) {
                    consider(String(path, 0, depth), node, lexicon.freq(node), flags, row[n], 0)
                }
                if (row[n] <= compMax && row[n] < rootCost - 1e-9) {
                    roots.add(Root(row[n], lexicon.maxFreq(node), node, String(path, 0, depth)))
                    rootCost = row[n]
                }
            }
            val count = lexicon.childCount(node)
            if (depth >= maxDepth || count == 0) return
            val first = lexicon.firstChild(node)
            val prevC: Char? = if (depth > 0) path[depth - 1] else null
            val prow: DoubleArray? = if (depth > 0) rows[depth - 1] else null
            val new = rows[depth + 1]
            for (k in first until first + count) {
                val c = lexicon.nodeChar(k)
                val ins = insCost(c, prevC)
                new[0] = row[0] + ins
                var best = new[0]
                for (i in 1..n) {
                    var s = sub(i - 1, c)
                    if (i == 1 && depth == 0 && s > 0.0) s += DecoderConfig.FIRST_CHAR_EXTRA
                    var v = row[i - 1] + s
                    val v2 = new[i - 1] + del[i - 1]
                    if (v2 < v) v = v2
                    val v3 = row[i] + ins
                    if (v3 < v) v = v3
                    if (prow != null && i >= 2 && prevC != null &&
                        t[i - 1] == prevC && t[i - 2] == c && t[i - 1] != t[i - 2]
                    ) {
                        val v4 = prow[i - 2] + DecoderConfig.TRANSPOSE
                        if (v4 < v) v = v4
                    }
                    new[i] = v
                    if (v < best) best = v
                }
                if (best > pruneAt) continue
                path[depth] = c
                dfs(k, depth + 1, rootCost)
            }
        }

        private fun completions() {
            roots.sortWith(compareBy<Root> { it.cost }.thenByDescending { it.maxFreq }.thenBy { it.node })
            val heap = PriorityQueue<HeapItem>(64, HEAP_ORDER)
            for (root in roots.take(DecoderConfig.MAX_COMPLETION_ROOTS)) {
                heap.clear()
                heap.add(HeapItem(lexicon.maxFreq(root.node), KIND_EXPAND, root.node, root.path))
                var emitted = 0
                while (heap.isNotEmpty() && emitted < DecoderConfig.COMPLETIONS_PER_ROOT) {
                    val item = heap.poll()
                    if (item.kind == KIND_EMIT) {
                        consider(
                            item.path, item.node, lexicon.freq(item.node), lexicon.flags(item.node),
                            root.cost, item.path.length - root.path.length,
                        )
                        emitted++
                        continue
                    }
                    val ni = item.node
                    if (lexicon.isTerminal(ni) && ni != root.node) {
                        heap.add(HeapItem(lexicon.freq(ni), KIND_EMIT, ni, item.path))
                    }
                    val first = lexicon.firstChild(ni)
                    for (k in first until first + lexicon.childCount(ni)) {
                        heap.add(HeapItem(lexicon.maxFreq(k), KIND_EXPAND, k, item.path + lexicon.nodeChar(k)))
                    }
                }
            }
        }

        private fun shortcuts() {
            lexicon.shortcut(t).forEachIndexed { rank, expansion ->
                val key = Text.normalizeKey(expansion)
                val hit = if (' ' !in expansion) lexicon.lookup(expansion) else null
                var freq = hit?.freq ?: 0
                val flags = (hit?.flags ?: Lexicon.FLAG_LANG_EN) and Lexicon.FLAG_OFFENSIVE.inv()
                freq = maxOf(freq, DecoderConfig.SHORTCUT_MIN_FREQ)
                val cost = DecoderConfig.SHORTCUT_COST + DecoderConfig.SHORTCUT_RANK_STEP / DecoderConfig.COST_WEIGHT * rank
                val prev = found[key]
                if (prev == null ||
                    DecoderConfig.freqLogp(freq) - DecoderConfig.penalty(cost, 0) >
                    DecoderConfig.freqLogp(prev.freq) - DecoderConfig.penalty(prev.cost, prev.completionChars)
                ) {
                    found[key] = Candidate(key, if (expansion != key) expansion else null, freq, flags, cost, 0, isShortcut = true)
                }
            }
        }
    }

    private class HeapItem(val priority: Int, val kind: Int, val node: Int, val path: String)

    companion object {
        private const val KIND_EMIT = 0
        private const val KIND_EXPAND = 1

        private val HEAP_ORDER = compareByDescending<HeapItem> { it.priority }
            .thenBy { it.kind }
            .thenBy { it.node }

        /** Best score first; ties broken alphabetically for determinism. */
        val RANKING: Comparator<Candidate> = compareByDescending<Candidate> { it.score }.thenBy { it.key }
    }
}
