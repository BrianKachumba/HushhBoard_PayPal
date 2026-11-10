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
 * Read-only view of an HDIC v1 dictionary (see tools/nlp/hdict.py for the format spec).
 *
 * The backing [ByteBuffer] is normally memory-mapped, so the dictionary costs almost no Java heap
 * and loads instantly. All accessors use absolute reads, which makes a [Lexicon] safe to use from
 * any number of threads at once.
 */
class Lexicon private constructor(private val buf: ByteBuffer) {
    val nodeCount: Int
    val wordCount: Int
    val meta: Map<String, String>
    private val nodesOffset: Int
    private val displayCount: Int
    private val displayIndexOffset: Int
    private val poolOffset: Int
    private val shortcutCount: Int
    private val shortcutIndexOffset: Int

    init {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        require(buf.capacity() >= HEADER_SIZE) { "Dictionary too small" }
        val magic = ByteArray(4) { buf.get(it) }
        require(magic.contentEquals(MAGIC)) { "Not an HDIC dictionary" }
        val version = buf.getShort(4).toInt() and 0xFFFF
        require(version == VERSION) { "Unsupported dictionary version $version" }
        nodeCount = buf.getInt(8)
        nodesOffset = buf.getInt(12)
        displayCount = buf.getInt(16)
        displayIndexOffset = buf.getInt(20)
        poolOffset = buf.getInt(24)
        wordCount = buf.getInt(32)
        val metaOffset = buf.getInt(36)
        val metaSize = buf.getInt(40)
        shortcutCount = buf.getInt(44)
        shortcutIndexOffset = buf.getInt(48)
        require(nodesOffset.toLong() + nodeCount.toLong() * NODE_SIZE <= buf.capacity()) { "Truncated dictionary" }
        meta = decodeUtf8(metaOffset, metaSize)
            .lineSequence()
            .filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
    }

    // ---- Node accessors (index -> field) ----------------------------------------------------

    fun nodeChar(node: Int): Char = buf.getChar(nodesOffset + node * NODE_SIZE)
    fun childCount(node: Int): Int = buf.get(nodesOffset + node * NODE_SIZE + 2).toInt() and 0xFF
    fun flags(node: Int): Int = buf.get(nodesOffset + node * NODE_SIZE + 3).toInt() and 0xFF
    fun firstChild(node: Int): Int = buf.getInt(nodesOffset + node * NODE_SIZE + 4)
    fun freq(node: Int): Int = buf.get(nodesOffset + node * NODE_SIZE + 8).toInt() and 0xFF
    fun maxFreq(node: Int): Int = buf.get(nodesOffset + node * NODE_SIZE + 9).toInt() and 0xFF
    fun isTerminal(node: Int): Boolean = flags(node) and FLAG_TERMINAL != 0

    /** Index of the child of [node] labelled [ch], or -1. Children are sorted, so this is a binary search. */
    fun child(node: Int, ch: Char): Int {
        var lo = firstChild(node)
        var hi = lo + childCount(node) - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = nodeChar(mid)
            when {
                c < ch -> lo = mid + 1
                c > ch -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    /** Node index for an exact dictionary key (already normalised), or -1. */
    fun find(key: CharSequence): Int {
        var node = ROOT
        for (ch in key) {
            node = child(node, ch)
            if (node < 0) return -1
        }
        return node
    }

    /** Looks up a word in any casing. Returns null if it is not a complete dictionary word. */
    fun lookup(word: CharSequence): WordInfo? {
        val key = Text.normalizeKey(word)
        val node = find(key)
        if (node <= ROOT || !isTerminal(node)) return null
        return WordInfo(key, displayOf(node) ?: key, freq(node), flags(node), node)
    }

    fun contains(word: CharSequence): Boolean = lookup(word) != null

    /** Canonical display form (e.g. "Nairobi", "M-Pesa", "I'm") if it differs from the key. */
    fun display(node: Int): String? {
        if (flags(node) and FLAG_HAS_DISPLAY == 0) return null
        return displayOf(node)
    }

    private fun displayOf(node: Int): String? {
        var lo = 0
        var hi = displayCount - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val entry = displayIndexOffset + mid * 8
            val nodeIndex = buf.getInt(entry)
            when {
                nodeIndex < node -> lo = mid + 1
                nodeIndex > node -> hi = mid - 1
                else -> return poolString(buf.getInt(entry + 4))
            }
        }
        return null
    }

    /** Expansions for a texting shortcut key ("im" -> ["I'm"]), or an empty list. */
    fun shortcut(key: String): List<String> {
        var lo = 0
        var hi = shortcutCount - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val entry = shortcutIndexOffset + mid * 8
            val k = poolString(buf.getInt(entry))
            val cmp = k.compareTo(key)
            when {
                cmp < 0 -> lo = mid + 1
                cmp > 0 -> hi = mid - 1
                else -> return poolString(buf.getInt(entry + 4)).split('\t')
            }
        }
        return emptyList()
    }

    /**
     * Visits every word in key order. [visitor] receives (key, freq, flags, nodeIndex).
     * Intended for one-off work such as building the glide-typing word list, not per keystroke.
     */
    fun forEachWord(visitor: (String, Int, Int, Int) -> Unit) {
        val path = StringBuilder()
        fun walk(node: Int) {
            if (node != ROOT && isTerminal(node)) visitor(path.toString(), freq(node), flags(node), node)
            val first = firstChild(node)
            for (k in first until first + childCount(node)) {
                path.append(nodeChar(k))
                walk(k)
                path.setLength(path.length - 1)
            }
        }
        walk(ROOT)
    }

    /** The [limit] most frequent words (display forms), most frequent first. */
    fun topWords(limit: Int, includeOffensive: Boolean = false): List<Pair<String, Int>> {
        val all = ArrayList<Triple<String, Int, Int>>(wordCount)
        forEachWord { key, freq, flags, node ->
            if (includeOffensive || flags and FLAG_OFFENSIVE == 0) {
                all.add(Triple(display(node) ?: key, freq, node))
            }
        }
        all.sortWith(compareByDescending<Triple<String, Int, Int>> { it.second }.thenBy { it.first })
        return all.asSequence().take(limit).map { it.first to it.second }.toList()
    }

    private fun poolString(offset: Int): String {
        val at = poolOffset + offset
        val len = buf.getShort(at).toInt() and 0xFFFF
        return decodeUtf8(at + 2, len)
    }

    private fun decodeUtf8(offset: Int, length: Int): String {
        val bytes = ByteArray(length)
        for (i in 0 until length) bytes[i] = buf.get(offset + i)
        return String(bytes, Charsets.UTF_8)
    }

    data class WordInfo(
        val key: String,
        val display: String,
        val freq: Int,
        val flags: Int,
        val node: Int,
    ) {
        val isOffensive: Boolean get() = flags and FLAG_OFFENSIVE != 0
    }

    companion object {
        val MAGIC = byteArrayOf('H'.code.toByte(), 'D'.code.toByte(), 'I'.code.toByte(), 'C'.code.toByte())
        const val VERSION = 1
        const val HEADER_SIZE = 64
        const val NODE_SIZE = 12
        const val ROOT = 0

        const val FLAG_TERMINAL = 0x01
        const val FLAG_OFFENSIVE = 0x02
        const val FLAG_HAS_DISPLAY = 0x04
        const val FLAG_LANG_EN = 0x08
        const val FLAG_LANG_SW = 0x10
        const val FLAG_LANG_KE = 0x20

        /** Wraps an existing buffer (e.g. a mapped region of an APK asset). */
        fun fromByteBuffer(buffer: ByteBuffer): Lexicon = Lexicon(buffer.duplicate())

        fun fromBytes(bytes: ByteArray): Lexicon = Lexicon(ByteBuffer.wrap(bytes))

        /** Memory-maps [file] read-only. */
        fun mmap(file: File): Lexicon {
            RandomAccessFile(file, "r").use { raf ->
                val map = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
                return Lexicon(map)
            }
        }

        /** Memory-maps a region of a file, e.g. an uncompressed asset inside an APK. */
        fun mmap(channel: FileChannel, offset: Long, length: Long): Lexicon {
            return Lexicon(channel.map(FileChannel.MapMode.READ_ONLY, offset, length))
        }
    }
}
