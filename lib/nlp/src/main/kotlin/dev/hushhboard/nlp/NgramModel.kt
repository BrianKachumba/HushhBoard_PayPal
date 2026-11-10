/*
 * Copyright (C) 2026 HushhBoard
 * Licensed under the Apache License, Version 2.0.
 */

package dev.hushhboard.nlp

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Read-only next-word model (HNGM v1, see tools/nlp/ngrams.py): which words tend to follow one
 * or two previous words. Word ids are node indices of the [Lexicon] it was built with; node 0
 * (the trie root) stands for "start of sentence". Memory-mapped and thread-safe.
 */
class NgramModel private constructor(private val buf: ByteBuffer, lexicon: Lexicon) {
    private val bigramCount: Int
    private val bigramOffset: Int
    private val trigramCount: Int
    private val trigramOffset: Int
    private val followerOffset: Int
    private val uniSlope: Double
    private val uniIntercept: Double
    private val wordCount: Int
    private val wordTableOffset: Int
    private val poolOffset: Int

    init {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        require(buf.capacity() >= HEADER_SIZE) { "Model too small" }
        val magic = ByteArray(4) { buf.get(it) }
        require(magic.contentEquals(MAGIC)) { "Not an HNGM model" }
        require((buf.getShort(4).toInt() and 0xFFFF) == VERSION) { "Unsupported model version" }
        val nodes = buf.getInt(8)
        val words = buf.getInt(12)
        require(nodes == lexicon.nodeCount && words == lexicon.wordCount) {
            "Next-word model was built for a different dictionary"
        }
        bigramCount = buf.getInt(16)
        bigramOffset = buf.getInt(20)
        trigramCount = buf.getInt(24)
        trigramOffset = buf.getInt(28)
        followerOffset = buf.getInt(36)
        uniSlope = buf.getFloat(40).toDouble()
        uniIntercept = buf.getFloat(44).toDouble()
        wordCount = buf.getInt(48)
        wordTableOffset = buf.getInt(52)
        poolOffset = buf.getInt(56)
    }

    /** Followers of a one-word context: (node, ln P), most likely first. */
    fun bigram(prev1: Int): List<Pair<Int, Double>> {
        var lo = 0
        var hi = bigramCount - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val at = bigramOffset + mid * 12
            val node = buf.getInt(at)
            when {
                node < prev1 -> lo = mid + 1
                node > prev1 -> hi = mid - 1
                else -> return followers(buf.getInt(at + 4), buf.getShort(at + 8).toInt() and 0xFFFF)
            }
        }
        return emptyList()
    }

    /** Followers of a two-word context (prev2, prev1). */
    fun trigram(prev2: Int, prev1: Int): List<Pair<Int, Double>> {
        var lo = 0
        var hi = trigramCount - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val at = trigramOffset + mid * 16
            val n1 = buf.getInt(at)
            val n2 = buf.getInt(at + 4)
            val cmp = if (n1 != prev2) n1.compareTo(prev2) else n2.compareTo(prev1)
            when {
                cmp < 0 -> lo = mid + 1
                cmp > 0 -> hi = mid - 1
                else -> return followers(buf.getInt(at + 8), buf.getShort(at + 12).toInt() and 0xFFFF)
            }
        }
        return emptyList()
    }

    private fun followers(first: Int, count: Int): List<Pair<Int, Double>> {
        val out = ArrayList<Pair<Int, Double>>(count)
        for (i in first until first + count) {
            val packed = buf.getInt(followerOffset + i * 4)
            out.add((packed and 0xFFFFFF) to -((packed ushr 24) / Q_SCALE))
        }
        return out
    }

    /** Dictionary key of a predictable word, or null. */
    fun keyOf(node: Int): String? {
        var lo = 0
        var hi = wordCount - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val at = wordTableOffset + mid * 8
            val n = buf.getInt(at)
            when {
                n < node -> lo = mid + 1
                n > node -> hi = mid - 1
                else -> {
                    val off = poolOffset + buf.getInt(at + 4)
                    val len = buf.getShort(off).toInt() and 0xFFFF
                    val bytes = ByteArray(len) { buf.get(off + 2 + it) }
                    return String(bytes, Charsets.UTF_8)
                }
            }
        }
        return null
    }

    /** Approximate unconditional ln P(word) from its dictionary frequency. */
    fun uniLogp(freq: Int): Double = uniSlope * freq + uniIntercept

    /** The next-word distribution for the text before the cursor. */
    fun context(lexicon: Lexicon, textBefore: CharSequence): ContextModel {
        val (p2, p1) = contextNodes(lexicon, textBefore)
        return ContextModel(this, p2, p1)
    }

    companion object {
        val MAGIC = byteArrayOf('H'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 'M'.code.toByte())
        const val VERSION = 1
        const val HEADER_SIZE = 64
        const val Q_SCALE = 20.0
        const val START = 0

        fun fromByteBuffer(buffer: ByteBuffer, lexicon: Lexicon) = NgramModel(buffer.duplicate(), lexicon)
        fun fromBytes(bytes: ByteArray, lexicon: Lexicon) = NgramModel(ByteBuffer.wrap(bytes), lexicon)
        fun mmap(file: File, lexicon: Lexicon): NgramModel {
            RandomAccessFile(file, "r").use { raf ->
                return NgramModel(raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length()), lexicon)
            }
        }

        private fun isSentenceSplit(c: Char) = c == '.' || c == '!' || c == '?' || c == ';' || c == ':' || c == '\n'
        private fun isJoinerChar(c: Char) = c == '\'' || c == '’' || c == '-'

        /** Words of the last sentence chunk in [text]. Mirrors `tokenize` in tools/nlp/ngrams.py. */
        fun lastChunkWords(text: CharSequence): List<String> {
            var last: List<String> = emptyList()
            var current = ArrayList<String>()
            var i = 0
            val n = text.length
            while (i < n) {
                val c = text[i]
                when {
                    isSentenceSplit(c) -> {
                        if (current.isNotEmpty()) last = current
                        current = ArrayList()
                        i++
                    }
                    c.isLetter() -> {
                        val sb = StringBuilder()
                        while (i < n) {
                            while (i < n && text[i].isLetter()) sb.append(text[i++])
                            if (i + 1 < n && isJoinerChar(text[i]) && text[i + 1].isLetter()) {
                                sb.append(if (text[i] == '’') '\'' else text[i])
                                i++
                            } else {
                                break
                            }
                        }
                        current.add(sb.toString())
                    }
                    else -> i++
                }
            }
            if (current.isNotEmpty()) last = current
            return last
        }

        /** (prev2, prev1) word ids for [textBefore]; null = unknown. Mirrors `context_nodes`. */
        fun contextNodes(lexicon: Lexicon, textBefore: CharSequence): Pair<Int?, Int?> {
            val words = lastChunkWords(textBefore)
            var end = textBefore.length
            while (end > 0 && textBefore[end - 1] == ' ') end--
            if (words.isEmpty() || (end > 0 && textBefore[end - 1].let { it == '.' || it == '!' || it == '?' || it == '\n' })) {
                return null to START
            }
            val p1 = lexicon.lookup(words.last())?.node ?: return null to null
            if (words.size == 1) return START to p1
            val p2 = lexicon.lookup(words[words.size - 2])?.node
            return p2 to p1
        }
    }
}

/** Next-word log-probabilities for one context. Mirrors `Context` in tools/nlp/ngrams.py. */
class ContextModel(private val model: NgramModel, prev2: Int?, prev1: Int?) {
    val logp: Map<Int, Double>

    init {
        val map = HashMap<Int, Double>()
        if (prev1 != null) {
            for ((node, lp) in model.bigram(prev1)) map[node] = lp + BACKOFF
            if (prev2 != null) {
                for ((node, lp) in model.trigram(prev2, prev1)) {
                    if (lp > (map[node] ?: -1e9)) map[node] = lp
                }
            }
        }
        logp = map
    }

    val isEmpty: Boolean get() = logp.isEmpty()

    /** Log-space boost for a word that is more likely here than usual (never negative). */
    fun boost(node: Int, freq: Int): Double {
        val lp = logp[node] ?: return 0.0
        val gain = lp - model.uniLogp(freq)
        if (gain <= 0.0) return 0.0
        return CTX_WEIGHT * minOf(gain, CTX_MAX_BOOST / CTX_WEIGHT)
    }

    /** Most likely next words (node, ln P), best first. */
    fun predictions(limit: Int, lexicon: Lexicon, allowOffensive: Boolean = false): List<Pair<Int, Double>> {
        val items = logp.entries.filter { it.value >= PREDICT_MIN_LOGP }
            .sortedWith(compareByDescending<Map.Entry<Int, Double>> { it.value }.thenBy { it.key })
        val out = ArrayList<Pair<Int, Double>>(limit)
        for (e in items) {
            if (!allowOffensive && lexicon.flags(e.key) and Lexicon.FLAG_OFFENSIVE != 0) continue
            out.add(e.key to e.value)
            if (out.size >= limit) break
        }
        return out
    }

    fun keyOf(node: Int): String? = model.keyOf(node)

    companion object {
        /** ln(0.4): stupid-backoff weight from a two-word to a one-word context. */
        const val BACKOFF = -0.916290731874155
        const val CTX_WEIGHT = 0.8
        const val CTX_MAX_BOOST = 6.0
        const val PREDICT_MIN_LOGP = -7.0
    }
}
