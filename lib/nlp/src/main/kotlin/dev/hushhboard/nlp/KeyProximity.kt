/*
 * Copyright (C) 2026 HushhBoard
 * Licensed under the Apache License, Version 2.0.
 */

package dev.hushhboard.nlp

import kotlin.math.hypot

/**
 * Key geometry used to price typos: hitting a neighbouring key is a cheap mistake, a key on the
 * other side of the keyboard is an expensive one.
 *
 * Coordinates are in "key widths" so the model is independent of screen size. The default is a
 * standard QWERTY layout; [fromLayout] builds one from the real on-screen keys (groundwork for
 * per-tap fat-finger modelling).
 */
class KeyProximity private constructor(
    private val centers: Map<Char, Point>,
    /** Pixels per key width (1.0 for the abstract QWERTY model). */
    val unit: Double = 1.0,
) {
    data class Point(val x: Double, val y: Double)

    /** One on-screen key, in pixels. */
    data class KeyGeometry(val label: Char, val centerX: Float, val centerY: Float, val width: Float)

    // Fast path for ASCII lower-case letters.
    private val asciiTable: Array<DoubleArray> = Array(26) { a ->
        DoubleArray(26) { b -> computeDistance('a' + a, 'a' + b) }
    }

    /** Euclidean distance between two keys in key widths, or [FAR] if either key is unknown. */
    fun distance(a: Char, b: Char): Double {
        val ba = Text.baseLetter(a)
        val bb = Text.baseLetter(b)
        if (ba in 'a'..'z' && bb in 'a'..'z') return asciiTable[ba - 'a'][bb - 'a']
        return computeDistance(ba, bb)
    }

    private fun computeDistance(a: Char, b: Char): Double {
        val pa = centers[Text.baseLetter(a)] ?: return FAR
        val pb = centers[Text.baseLetter(b)] ?: return FAR
        return hypot(pa.x - pb.x, pa.y - pb.y)
    }

    /** Converts an on-screen position in pixels to this model's key-width units. */
    fun toKeyUnits(xPx: Float, yPx: Float): TouchPoint = TouchPoint(xPx / unit, yPx / unit)

    /** Centre of a key, if known. */
    fun center(c: Char): Point? = centers[Text.baseLetter(c)]

    private val neighbourCache = java.util.concurrent.ConcurrentHashMap<Char, List<Char>>()

    /** Keys whose centre is within [radius] key widths of [c]'s centre (excluding [c]), sorted by label. */
    fun neighbours(c: Char, radius: Double = SmartTargets.NEIGHBOUR_RADIUS): List<Char> {
        val base = Text.baseLetter(c)
        if (radius == SmartTargets.NEIGHBOUR_RADIUS) neighbourCache[base]?.let { return it }
        val p = centers[base] ?: return emptyList()
        val list = centers.entries
            .filter { (k, q) -> k != base && hypot(q.x - p.x, q.y - p.y) <= radius }
            .map { it.key }
            .sorted()
        if (radius == SmartTargets.NEIGHBOUR_RADIUS) neighbourCache[base] = list
        return list
    }

    companion object {
        const val FAR = 99.0

        private val QWERTY_ROWS = listOf("qwertyuiop" to 0.0, "asdfghjkl" to 0.5, "zxcvbnm" to 1.5)

        /** Standard QWERTY. Mirrors `default_key_centers()` in tools/nlp/hdict.py. */
        val QWERTY: KeyProximity by lazy {
            val map = HashMap<Char, Point>()
            QWERTY_ROWS.forEachIndexed { row, (letters, offset) ->
                letters.forEachIndexed { col, ch -> map[ch] = Point(offset + col + 0.5, row + 0.5) }
            }
            KeyProximity(map)
        }

        /**
         * Builds a model from the keys actually on screen. Positions are converted to key widths
         * using the median letter-key width, so tablets, landscape and custom layouts all work.
         */
        fun fromLayout(keys: List<KeyGeometry>): KeyProximity {
            val letters = keys.filter { it.label.isLetter() && it.width > 0f }
            if (letters.size < 10) return QWERTY
            val unit = letters.map { it.width }.sorted()[letters.size / 2].toDouble()
            val map = HashMap<Char, Point>()
            for (k in letters) {
                map[k.label.lowercaseChar()] = Point(k.centerX / unit, k.centerY / unit)
            }
            return KeyProximity(map, unit)
        }
    }
}
