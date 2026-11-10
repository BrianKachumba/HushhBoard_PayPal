/*
 * Copyright (C) 2026 HushhBoard
 * Licensed under the Apache License, Version 2.0.
 */

package dev.hushhboard.nlp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Casing, typing state and the "keyboard-first" guarantees added after the first phone tests. */
class TypingPolishTest : FunSpec({
    val lexicon = fullLexicon()
    val ngrams = fullNgrams(lexicon)

    test("a capital at a sentence start or in caps lock is not learned as the word's spelling") {
        Text.learnableCasing("Hello", sentenceStart = true) shouldBe null
        Text.learnableCasing("Hello", sentenceStart = false) shouldBe "Hello"
        Text.learnableCasing("hello", sentenceStart = true) shouldBe "hello"
        Text.learnableCasing("HELLO", sentenceStart = false) shouldBe null
        Text.learnableCasing("KCB", sentenceStart = false) shouldBe "KCB"
        Text.learnableCasing("iPhone", sentenceStart = true) shouldBe "iPhone"
    }

    test("words typed at the start of sentences are suggested in lower case mid-sentence") {
        val e = WordEngine(lexicon, UserModel(), ngrams = ngrams)
        repeat(3) { e.learn("Habibi", null, sentenceStart = true) }
        val words = e.suggest("habib", "you are my ", 5).suggestions.map { it.text }
        words shouldContain "habibi"
        words shouldNotContain "Habibi"
    }

    test("names typed mid-sentence keep their capital, and lower-case use wins back") {
        val e = WordEngine(lexicon, UserModel(), ngrams = ngrams)
        repeat(2) { e.learn("Kiprotich", "with", sentenceStart = false) }
        e.suggest("kiprot", "I was with ", 3).suggestions.map { it.text } shouldContain "Kiprotich"
        val m = UserModel()
        m.learnWord("Sawa", null, 0, sentenceStart = false)
        m.learnWord("sawa", "ni", 0, sentenceStart = false)
        m.learnWord("sawa", "ni", 0, sentenceStart = false)
        m.predictNext("ni", 1).first().first shouldBe "sawa"
    }

    test("dictionary words are predicted with dictionary casing, not the user's") {
        val e = WordEngine(lexicon, UserModel(), ngrams = ngrams)
        repeat(4) { e.learn("Morning", "good", sentenceStart = false) }
        val preds = e.suggest("", "it was a good ", 5, nextWordCase = WordEngine.NextWordCase.NONE).suggestions.map { it.text }
        preds shouldContain "morning"
        preds shouldNotContain "Morning"
    }

    test("prediction casing follows the keyboard's shift state") {
        val e = WordEngine(lexicon, UserModel(), ngrams = ngrams)
        fun first(case: WordEngine.NextWordCase, before: String = "Thank ") =
            e.suggest("", before, 1, nextWordCase = case).suggestions.first().text
        first(WordEngine.NextWordCase.NONE) shouldBe "you"
        first(WordEngine.NextWordCase.CAPITALIZE) shouldBe "You"
        first(WordEngine.NextWordCase.UPPER) shouldBe "YOU"
        first(WordEngine.NextWordCase.NONE, "Done. ")[0].isLowerCase() shouldBe false // "I" stays "I"
    }

    test("a user's habits outweigh the shipped model once they have some history") {
        val e = WordEngine(lexicon, UserModel(), ngrams = ngrams)
        e.suggest("", "Thank ", 1).suggestions.first().text shouldBe "you"
        repeat(6) { e.learn("bro", "Thank") }
        e.suggest("", "Thank ", 3).suggestions.first().text shouldBe "bro"
    }

    test("chat-style predictions for a brand-new user") {
        val e = WordEngine(lexicon, UserModel(), ngrams = ngrams)
        fun top(before: String) = e.suggest("", before, 3, nextWordCase = WordEngine.NextWordCase.NONE).suggestions.map { it.text }
        top("I ") shouldContain "am"
        top("Good ") shouldContain "morning"
        top("Habari ya ") shouldContain "asubuhi"
        top("Niko ") shouldContain "njiani"
        top("See you ") shouldContain "tomorrow"
    }

    test("typing state: a new field or a cursor jump is not typing") {
        val d = CommitDetector()
        d.observe("", 0, "")
        d.isTyping shouldBe false
        d.observe("h", 1, "h")
        d.isTyping shouldBe true
        d.observe("hello ", 6, "")
        d.isTyping shouldBe true
        d.observe("hello you ", 10, "") // tapped a prediction
        d.isTyping shouldBe true
        d.observe("a long text that was already in the chat when it was opened ", 400, "")
        d.isTyping shouldBe false
        d.reset()
        d.observe("Draft message ", 14, "")
        d.isTyping shouldBe false
    }

    test("tap offsets are read without the model lock and survive a save") {
        val m = UserModel()
        m.touchOffsets() shouldBe null
        val prox = KeyProximity.QWERTY
        repeat(40) { m.learnTouches("hello", "hello".map { ch -> prox.center(ch)?.let { c -> TouchPoint(c.x + 0.1, c.y) } }, prox) }
        val offsets = m.touchOffsets()
        offsets shouldNotBe null
        val out = ByteArrayOutputStream()
        m.writeTo(out)
        val copy = UserModel()
        copy.readFrom(ByteArrayInputStream(out.toByteArray()))
        (copy.touchOffsets() != null) shouldBe true
    }

    test("glide words: the dictionary part is built once") {
        val e = WordEngine(lexicon, UserModel())
        val a = e.glideWords(500)
        repeat(2) { e.learn("Kiprotich", null) }
        val b = e.glideWords(500)
        b.size shouldBe a.size + 1
        b.map { it.first } shouldContain "Kiprotich"
    }
})
