/*
 * Copyright (C) 2026 HushhBoard
 * Licensed under the Apache License, Version 2.0.
 */

package dev.hushhboard.nlp

import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.abs
import kotlin.math.min

/** A tap position in key-width units (the coordinate space of [KeyProximity]). */
data class TouchPoint(val x: Double, val y: Double)

/** The user's systematic tap offset per key (where they actually hit, relative to the key centre). */
fun interface TouchOffsets {
    /** Offset for the key labelled [c] (base letter), or null if unknown. */
    fun offsetFor(c: Char): TouchPoint?
}

/** Spatial likelihood of taps. Mirrors `touch_nats` in tools/nlp/hdict.py. */
object TouchMath {
    /**
     * Negative log-likelihood (nats, up to a constant) that [tap] was aimed at a key centred at
     * [center], using an anisotropic Gaussian and the key's learned offset.
     */
    fun nats(tap: TouchPoint, center: KeyProximity.Point, offsets: TouchOffsets?, key: Char): Double {
        var cx = center.x
        var cy = center.y
        if (offsets != null) {
            val o = offsets.offsetFor(Text.baseLetter(key))
            if (o != null) {
                cx += o.x
                cy += o.y
            }
        }
        val dx = (tap.x - cx) / DecoderConfig.TOUCH_SIGMA_X
        val dy = (tap.y - cy) / DecoderConfig.TOUCH_SIGMA_Y
        return 0.5 * (dx * dx + dy * dy)
    }
}

/**
 * Learns where this user actually taps each key. Most people hit slightly below and to one side
 * of the key centre; correcting for that makes both key detection and correction sharper.
 *
 * Per-key means are shrunk towards a global mean (and the global mean towards zero) until enough
 * evidence exists, and every offset is clamped, so a few odd taps can never skew the keyboard.
 * Not thread-safe on its own; [UserModel] guards it.
 */
class TouchModel {
    private class Stat(var n: Int = 0, var dx: Double = 0.0, var dy: Double = 0.0) {
        fun add(x: Double, y: Double) {
            n++
            val w = 1.0 / min(n, EMA_WINDOW)
            dx += (x - dx) * w
            dy += (y - dy) * w
        }
    }

    private val perKey = HashMap<Char, Stat>()
    private val global = Stat()

    val sampleCount: Int get() = global.n

    /**
     * Learn from a word the user typed exactly as intended. [taps] are aligned with [word]'s
     * characters; null entries (e.g. taps we did not see) are skipped.
     */
    fun learn(word: String, taps: List<TouchPoint?>, proximity: KeyProximity) {
        if (taps.size != word.length) return
        for (i in word.indices) {
            val tap = taps[i] ?: continue
            val c = Text.baseLetter(word[i].lowercaseChar())
            val center = proximity.center(c) ?: continue
            val dx = tap.x - center.x
            val dy = tap.y - center.y
            // Ignore taps that are clearly not aimed at this key (e.g. alignment glitches).
            if (abs(dx) > MAX_SAMPLE_OFFSET || abs(dy) > MAX_SAMPLE_OFFSET) continue
            perKey.getOrPut(c) { Stat() }.add(dx, dy)
            global.add(dx, dy)
        }
    }

    /** Current offsets, or null until enough taps have been seen to trust them. */
    fun snapshot(): TouchOffsets? {
        if (global.n < MIN_SAMPLES) return null
        val gShrink = global.n.toDouble() / (global.n + GLOBAL_PRIOR)
        val gx = global.dx * gShrink
        val gy = global.dy * gShrink
        val table = HashMap<Char, TouchPoint>()
        for ((c, s) in perKey) {
            val w = s.n.toDouble() / (s.n + KEY_PRIOR)
            table[c] = TouchPoint(clamp(s.dx * w + gx * (1 - w)), clamp(s.dy * w + gy * (1 - w)))
        }
        val fallback = TouchPoint(clamp(gx), clamp(gy))
        return TouchOffsets { c -> table[c] ?: fallback }
    }

    fun clear() {
        perKey.clear()
        global.n = 0; global.dx = 0.0; global.dy = 0.0
    }

    fun write(d: DataOutputStream) {
        d.writeInt(global.n); d.writeDouble(global.dx); d.writeDouble(global.dy)
        d.writeInt(perKey.size)
        for ((c, s) in perKey) {
            d.writeChar(c.code); d.writeInt(s.n); d.writeDouble(s.dx); d.writeDouble(s.dy)
        }
    }

    fun read(d: DataInputStream) {
        clear()
        global.n = d.readInt(); global.dx = d.readDouble(); global.dy = d.readDouble()
        repeat(d.readInt()) {
            val c = d.readChar()
            perKey[c] = Stat(d.readInt(), d.readDouble(), d.readDouble())
        }
    }

    private fun clamp(v: Double) = v.coerceIn(-MAX_OFFSET, MAX_OFFSET)

    companion object {
        const val MIN_SAMPLES = 40
        const val EMA_WINDOW = 200
        const val KEY_PRIOR = 15.0
        const val GLOBAL_PRIOR = 30.0
        const val MAX_OFFSET = 0.35
        const val MAX_SAMPLE_OFFSET = 0.9
    }
}

/**
 * Smart key targets: when a tap lands near the border between keys, pick the letter that best
 * continues a real word, weighing how far the tap was from each key against how likely each letter
 * is next. Taps clearly inside a key are never changed. Mirrors `smart_target` in hdict.py.
 */
object SmartTargets {
    const val AMBIGUITY_NATS = 1.5
    const val LM_WEIGHT = 0.5
    const val SWITCH_MARGIN = 0.4
    const val NEIGHBOUR_RADIUS = 1.6
    val MISSING_LOGP = DecoderConfig.freqLogp(0) - 2.0

    /** Log-score of [ch] following [prefix] in the dictionary, or null if the prefix is unknown. */
    fun nextCharLogp(lexicon: Lexicon, prefix: String, ch: Char): Double? {
        val node = lexicon.find(prefix)
        if (node < 0) return null
        val child = lexicon.child(node, ch)
        if (child < 0) return MISSING_LOGP
        return DecoderConfig.freqLogp(lexicon.maxFreq(child))
    }

    /**
     * @param prefix the current word so far, lower-case
     * @param default the key whose rectangle contains the tap
     * @param keys candidate keys (neighbours of [default]), in a stable order
     */
    fun choose(
        lexicon: Lexicon,
        proximity: KeyProximity,
        prefix: String,
        tap: TouchPoint,
        default: Char,
        keys: List<Char>,
        offsets: TouchOffsets? = null,
    ): Char {
        val defaultCenter = proximity.center(default) ?: return default
        val defaultLm = nextCharLogp(lexicon, prefix, default) ?: return default
        val baseNats = TouchMath.nats(tap, defaultCenter, offsets, default)
        var best = default
        var bestTotal = -baseNats + LM_WEIGHT * defaultLm
        for (k in keys) {
            if (k == default) continue
            val center = proximity.center(k) ?: continue
            val nats = TouchMath.nats(tap, center, offsets, k)
            if (nats - baseNats > AMBIGUITY_NATS) continue
            val lm = nextCharLogp(lexicon, prefix, k) ?: continue
            val total = -nats + LM_WEIGHT * lm
            if (total > bestTotal + SWITCH_MARGIN) {
                best = k
                bestTotal = total
            }
        }
        return best
    }
}
