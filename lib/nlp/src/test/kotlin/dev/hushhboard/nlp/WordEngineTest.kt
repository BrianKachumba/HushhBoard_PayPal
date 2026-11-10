/*
 * Copyright (C) 2026 HushhBoard
 * Licensed under the Apache License, Version 2.0.
 */

package dev.hushhboard.nlp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

class WordEngineTest : FunSpec({
    val lexicon = fullLexicon()
    fun engine() = WordEngine(lexicon)
    fun WordEngine.top(typed: String, before: String = "", n: Int = 3) =
        suggest(typed, before, n).suggestions.map { it.text }

    test("completes common words") {
        engine().top("tomo") shouldContain "tomorrow"
        engine().top("hel") shouldContain "hello"
        engine().top("asubu") shouldContain "asubuhi"
    }

    test("corrects fat-finger typos") {
        engine().top("recieve") shouldContain "receive"
        engine().top("sawq").first() shouldBe "sawa"
        engine().top("goign").first() shouldBe "going"
        engine().top("thier").first() shouldBe "their"
    }

    test("expands shortcuts and inserts apostrophes") {
        engine().top("im").first() shouldBe "I'm"
        engine().top("dont").first() shouldBe "don't"
        engine().top("pls") shouldContain "please"
    }

    test("keeps the user's casing") {
        engine().top("Hel").first()[0].isUpperCase() shouldBe true
        engine().top("HEL").first() shouldBe engine().top("hel").first().uppercase()
        engine().top("nairob").first() shouldBe "Nairobi"
    }

    test("never suggests offensive words unless allowed or typed in full") {
        val e = engine()
        val withOffensive = e.suggest("dam", "", 8, allowOffensive = true).suggestions.map { it.text }
        val without = e.suggest("dam", "", 8, allowOffensive = false).suggestions.map { it.text }
        if ("damn" in withOffensive) without shouldNotContain "damn"
    }

    test("learns personal words and suggests them") {
        val e = engine()
        e.top("kiprot") shouldNotContain "Kiprotich"
        e.learn("Kiprotich", null)
        e.learn("Kiprotich", null)
        e.top("kiprot") shouldContain "Kiprotich"
        e.top("kiprotih") shouldContain "Kiprotich"
        e.isValidWord("Kiprotich") shouldBe true
    }

    test("context from personal history re-ranks suggestions") {
        val e = engine()
        repeat(6) { e.learn("yako", "habari") }
        e.top("y", "habari ").first() shouldBe "yako"
    }

    test("predicts the next word after a space from personal history") {
        val e = engine()
        e.suggest("", "habari ").suggestions shouldBe emptyList()
        repeat(3) { e.learn("yako", "habari") }
        val preds = e.suggest("", "Habari ").suggestions
        preds.first().text shouldBe "yako"
        preds.first().kind shouldBe WordEngine.Kind.PREDICTION
    }

    test("removed suggestions stay removed") {
        val e = engine()
        e.top("tomo") shouldContain "tomorrow"
        e.removeSuggestion("tomorrow")
        e.top("tomo") shouldNotContain "tomorrow"
    }

    test("spell check") {
        val e = engine()
        e.spell("hello").isValid shouldBe true
        e.spell("KCB").isValid shouldBe true
        e.spell("2024").isValid shouldBe true
        val bad = e.spell("recieve")
        bad.isValid shouldBe false
        bad.suggestions shouldContain "receive"
    }

    test("glide word list includes personal words") {
        val e = engine()
        repeat(2) { e.learn("Kiprotich", null) }
        val words = e.glideWords(1000).map { it.first }
        words shouldContain "the"
        words shouldContain "Kiprotich"
        (e.frequencyOf("the") > e.frequencyOf("xylophone")) shouldBe true
    }
})

class CommitDetectorTest : FunSpec({
    test("detects a word finished with a space") {
        val d = CommitDetector()
        d.observe("hel", 3, "hel") shouldBe null
        d.observe("hello", 5, "hello") shouldBe null
        d.observe("hello ", 6, "") shouldBe CommitDetector.Committed("hello", null, sentenceStart = true)
    }

    test("reports the previous word in the same sentence") {
        val d = CommitDetector()
        d.observe("habari yak", 10, "yak")
        d.observe("habari yako ", 12, "") shouldBe CommitDetector.Committed("yako", "habari")
    }

    test("detects punctuation and accepted suggestions") {
        val d = CommitDetector()
        d.observe("Thanks", 6, "Thanks")
        d.observe("Thanks!", 7, "") shouldBe CommitDetector.Committed("Thanks", null, sentenceStart = true)
        val d2 = CommitDetector()
        d2.observe("tomo", 4, "tomo")
        d2.observe("tomorrow ", 9, "") shouldBe CommitDetector.Committed("tomorrow", null, sentenceStart = true)
    }

    test("ignores cursor jumps, deletions and repeats") {
        val d = CommitDetector()
        d.observe("hello", 5, "hello")
        d.observe("hello ", 6, "")
        d.observe("hello", 5, "hello") shouldBe null // backspace
        d.observe("hello ", 6, "") shouldBe null      // same word at same place: not counted twice
        val d2 = CommitDetector()
        d2.observe("some text", 9, "text")
        d2.observe("a much longer document text that was pasted here ", 500, "") shouldBe null
    }

    test("works when the app does not support composing text") {
        val d = CommitDetector()
        d.observe("sawa", 4, "")
        d.observe("sawa ", 5, "") shouldBe CommitDetector.Committed("sawa", null, sentenceStart = true)
    }

    test("does not learn numbers") {
        val d = CommitDetector()
        d.observe("2500", 4, "")
        d.observe("2500 ", 5, "") shouldBe null
    }
})

class TextTest : FunSpec({
    test("previous words stop at sentence boundaries") {
        Text.previousWords("I am going to ", 2) shouldBe listOf("to", "going")
        Text.previousWords("Done. Next ", 2) shouldBe listOf("Next")
        Text.previousWords("", 2) shouldBe emptyList()
        Text.previousWords("don’t ", 1) shouldBe listOf("don’t")
    }

    test("case application") {
        Text.applyCase("hel", "hello") shouldBe "hello"
        Text.applyCase("Hel", "hello") shouldBe "Hello"
        Text.applyCase("HEL", "hello") shouldBe "HELLO"
        Text.applyCase("H", "hello") shouldBe "Hello"
        Text.applyCase("nai", "Nairobi") shouldBe "Nairobi"
    }

    test("base letters") {
        Text.baseLetter('é') shouldBe 'e'
        Text.baseLetter('a') shouldBe 'a'
    }
})
