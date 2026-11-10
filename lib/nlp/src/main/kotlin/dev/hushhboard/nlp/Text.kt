/*
 * Copyright (C) 2026 HushhBoard
 * Licensed under the Apache License, Version 2.0.
 */

package dev.hushhboard.nlp

import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/** Text helpers shared by the engine. Behaviour mirrors tools/nlp/hdict.py. */
object Text {
    const val MAX_WORD_LENGTH = 32
    private const val JOINERS = "'-"

    /** Dictionary key form: locale-independent lower case (matches Python `str.lower`). */
    fun normalizeKey(word: CharSequence): String = word.toString().lowercase()

    fun isJoiner(c: Char): Boolean = c == '\'' || c == '-'

    /** Normalises typographic apostrophes so "don’t" and "don't" are the same word. */
    fun normalizeApostrophes(s: String): String =
        if (s.indexOf('’') < 0 && s.indexOf('ʼ') < 0) s else s.replace('’', '\'').replace('ʼ', '\'')

    /** Characters that can be part of a word for the purposes of the engine. */
    fun isWordChar(c: Char): Boolean = c.isLetter() || c == '\'' || c == '’' || c == '-'

    private val baseLetterCache = ConcurrentHashMap<Char, Char>()

    /** Base letter without accents: 'é' -> 'e'. Matches Python `unicodedata.normalize("NFD", c)[0]`. */
    fun baseLetter(c: Char): Char {
        if (c.code < 0x80) return c
        return baseLetterCache.getOrPut(c) {
            val d = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
            if (d.isEmpty()) c else d[0]
        }
    }

    /**
     * Applies the casing the user typed to a suggestion:
     *  - "HEL" (2+ letters, all upper) -> "HELLO"
     *  - "Hel" -> "Hello"
     *  - otherwise the dictionary's display form ("nairobi" -> "Nairobi", "im" -> "I'm").
     */
    /**
     * The casing of [word] worth remembering, or null if its casing is only explained by where it
     * was typed: a capital at the start of a sentence, or a long word in all caps (caps lock).
     */
    fun learnableCasing(word: String, sentenceStart: Boolean): String? {
        var letters = 0
        var upper = 0
        var upperAfterFirst = false
        for ((i, c) in word.withIndex()) {
            if (!c.isLetter()) continue
            letters++
            if (c.isUpperCase()) {
                upper++
                if (letters > 1) upperAfterFirst = true
            }
        }
        if (letters == 0) return word
        if (upper == letters && letters > 4) return null // SHOUTING / caps lock, not an acronym
        if (sentenceStart && upper == 1 && !upperAfterFirst && word.firstOrNull()?.isUpperCase() == true) return null
        return word
    }

    fun applyCase(typed: String, display: String): String {
        var letters = 0
        var allUpper = true
        for (c in typed) {
            if (c.isLetter()) {
                letters++
                if (!c.isUpperCase()) allUpper = false
            }
        }
        if (letters >= 2 && allUpper) return display.uppercase()
        if (typed.isNotEmpty() && typed[0].isUpperCase() && display.isNotEmpty()) {
            return display.substring(0, 1).uppercase() + display.substring(1)
        }
        return display
    }

    /**
     * Returns up to [max] words immediately before the end of [text], nearest first, stopping at
     * sentence boundaries. Example: previousWords("I am going to ", 2) == ["to", "going"].
     */
    fun previousWords(text: CharSequence, max: Int = 2): List<String> {
        val out = ArrayList<String>(max)
        var i = text.length - 1
        while (out.size < max && i >= 0) {
            // Skip separators; stop at sentence punctuation or a line break.
            while (i >= 0 && !isWordChar(text[i])) {
                val c = text[i]
                if (c == '.' || c == '!' || c == '?' || c == '\n') return out
                i--
            }
            if (i < 0) break
            val end = i + 1
            while (i >= 0 && isWordChar(text[i])) i--
            val word = text.subSequence(i + 1, end).toString().trim('\'', '-', '’')
            if (word.isEmpty()) break
            out.add(word)
        }
        return out
    }

    /** True if a word starting right after [textBefore] begins a sentence (start of text, after . ! ? or a new line). */
    fun isSentenceStart(textBefore: CharSequence): Boolean {
        var i = textBefore.length - 1
        while (i >= 0 && textBefore[i] == ' ') i--
        if (i < 0) return true
        val c = textBefore[i]
        return c == '.' || c == '!' || c == '?' || c == '\n'
    }

    /** True if [word] looks like something the engine should learn or correct (no digits, URLs, emails, …). */
    fun isLearnable(word: String): Boolean {
        if (word.length < 2 || word.length > MAX_WORD_LENGTH) return false
        if (!word[0].isLetter()) return false
        var letters = 0
        for (c in word) {
            if (c.isLetter()) letters++ else if (!isJoiner(c) && c != '’') return false
        }
        return letters >= 2
    }
}
