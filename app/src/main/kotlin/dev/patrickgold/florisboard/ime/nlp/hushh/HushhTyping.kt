/*
 * Copyright (C) 2026 HushhBoard
 * Licensed under the Apache License, Version 2.0.
 */

package dev.patrickgold.florisboard.ime.nlp.hushh

import androidx.compose.ui.graphics.vector.ImageVector
import dev.hushhboard.nlp.KeyProximity
import dev.hushhboard.nlp.TouchPoint
import dev.hushhboard.nlp.WordEngine
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionProvider
import dev.patrickgold.florisboard.ime.text.key.KeyType
import dev.patrickgold.florisboard.ime.text.keyboard.TextKey
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyboard

/**
 * Glue between the on-screen keyboard and the HushhBoard word engine for touch-level features:
 *  - records where each letter was actually tapped (for fat-finger correction and offset learning),
 *  - keeps the engine's key geometry in sync with the real layout,
 *  - smart key targets: resolves taps near key borders using the dictionary.
 *
 * Touch callbacks run on the main thread; the word engine reads taps from background threads,
 * so [TapTracker] is synchronised.
 */
object HushhTyping {
    @Volatile
    var engine: WordEngine? = null
        internal set

    /** Smart key targets on/off (user preference, kept in sync by the provider). */
    @Volatile
    var smartTargetsEnabled: Boolean = true

    val taps = TapTracker()

    @Volatile
    private var layoutKeyboard: TextKeyboard? = null
    @Volatile
    private var layoutSignature: Int = 0
    @Volatile
    var proximity: KeyProximity = KeyProximity.QWERTY
        private set

    /** Rebuilds the key geometry when the keyboard or its size changed. Cheap when nothing changed. */
    fun syncLayout(keyboard: TextKeyboard) {
        val signature = layoutSignatureOf(keyboard)
        if (keyboard === layoutKeyboard && signature == layoutSignature) return
        val keys = ArrayList<KeyProximity.KeyGeometry>()
        for (key in keyboard.keys()) {
            val ch = letterOf(key) ?: continue
            val b = key.touchBounds
            keys.add(KeyProximity.KeyGeometry(ch, b.center.x, b.center.y, b.width))
        }
        val prox = KeyProximity.fromLayout(keys)
        proximity = prox
        layoutKeyboard = keyboard
        layoutSignature = signature
        engine?.setProximity(prox)
        taps.clear()
    }

    private fun layoutSignatureOf(keyboard: TextKeyboard): Int {
        var h = 17
        for (key in keyboard.keys()) {
            val b = key.touchBounds
            h = 31 * h + b.left.toInt()
            h = 31 * h + b.top.toInt()
            h = 31 * h + b.width.toInt()
        }
        return h
    }

    /** Lower-case letter produced by [key], or null for non-letter keys. */
    fun letterOf(key: TextKey): Char? {
        val data = key.computedData
        if (data.type != KeyType.CHARACTER) return null
        val code = data.code
        if (code <= 0 || code > 0xFFFF || !Character.isLetter(code)) return null
        return Character.toLowerCase(code.toChar())
    }

    /**
     * Smart key targets. [default] is the key whose rectangle contains the tap. Returns the key
     * that should register: [default] unless the tap is near a border and a neighbouring letter
     * clearly continues a real word better.
     */
    fun resolveKey(keyboard: TextKeyboard, default: TextKey?, xPx: Float, yPx: Float, composing: String): TextKey? {
        if (default == null || !smartTargetsEnabled) return default
        val e = engine ?: return default
        val defaultLetter = letterOf(default) ?: return default
        syncLayout(keyboard)
        if (!hasRealLayout()) return default
        val prefix = taps.currentPrefix(composing)
        val chosen = e.resolveTap(prefix, proximity.toKeyUnits(xPx, yPx), defaultLetter)
        if (chosen == defaultLetter) return default
        for (key in keyboard.keys()) {
            if (letterOf(key) == chosen && key.isEnabled) return key
        }
        return default
    }

    /** A letter key was committed by a tap at ([xPx], [yPx]). */
    fun onLetterTapped(keyboard: TextKeyboard, letter: Char, xPx: Float, yPx: Float) {
        syncLayout(keyboard)
        if (!hasRealLayout()) {
            taps.clear() // no geometry yet: better no tap data than wrong tap data
            return
        }
        taps.record(letter, proximity.toKeyUnits(xPx, yPx))
    }

    /** True once the key geometry comes from the measured on-screen layout (pixels), not the QWERTY fallback. */
    private fun hasRealLayout(): Boolean = proximity !== KeyProximity.QWERTY && proximity.unit > 1.0
}

/**
 * The taps of the word being typed (and the previous word), aligned with the letters they produced.
 * When the text changes in ways we did not see (cursor moved, paste, glide typing), the letters no
 * longer match the editor's word and the taps are simply not used.
 */
class TapTracker {
    private val current = ArrayList<Pair<Char, TouchPoint>>()
    private val previous = ArrayList<Pair<Char, TouchPoint>>()

    @Synchronized
    fun record(letter: Char, point: TouchPoint) {
        current.add(letter to point)
        if (current.size > MAX_WORD) current.removeAt(0)
    }

    /** A space, punctuation or enter ended the word. */
    @Synchronized
    fun onSeparator() {
        if (current.isEmpty()) return
        previous.clear()
        previous.addAll(current)
        current.clear()
    }

    /** Backspace: drop the last tap; deleting the separator brings the previous word back. */
    @Synchronized
    fun onDelete() {
        if (current.isNotEmpty()) {
            current.removeAt(current.size - 1)
        } else if (previous.isNotEmpty()) {
            current.addAll(previous)
            previous.clear()
        }
    }

    @Synchronized
    fun clear() {
        current.clear()
        previous.clear()
    }

    /** Taps aligned with [word] (the current or the just-finished word), or null if they do not match. */
    @Synchronized
    fun tapsFor(word: String): List<TouchPoint?>? {
        if (word.isEmpty()) return null
        return match(current, word) ?: match(previous, word)
    }

    /** The word typed so far for smart targets: our own record if it agrees with the editor. */
    @Synchronized
    fun currentPrefix(composing: String): String {
        val mine = String(CharArray(current.size) { current[it].first })
        val c = composing.lowercase()
        return if (mine.startsWith(c)) mine else c
    }

    private fun match(list: List<Pair<Char, TouchPoint>>, word: String): List<TouchPoint?>? {
        val n = word.length
        if (list.size < n) return null
        val start = list.size - n
        for (i in 0 until n) {
            if (list[start + i].first != word[i].lowercaseChar()) return null
        }
        return List(n) { list[start + it].second }
    }

    companion object {
        private const val MAX_WORD = 48
    }
}

/** A word suggestion from the HushhBoard engine that remembers which input it was computed for. */
data class HushhWordCandidate(
    override val text: CharSequence,
    /** The composing text this suggestion was computed for. */
    val typed: String,
    override val confidence: Double,
    override val isEligibleForAutoCommit: Boolean,
    override val sourceProvider: SuggestionProvider?,
    /** For next-word predictions: the word before the cursor and whether a sentence starts here. */
    val previousWord: String? = null,
    val sentenceStart: Boolean = false,
) : SuggestionCandidate {
    override val secondaryText: CharSequence? = null
    override val isEligibleForUserRemoval: Boolean = true
    override val icon: ImageVector? = null
}
