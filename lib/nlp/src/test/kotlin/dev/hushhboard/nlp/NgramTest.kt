/*
 * Copyright (C) 2026 HushhBoard
 * Licensed under the Apache License, Version 2.0.
 */

package dev.hushhboard.nlp

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File

fun fullNgrams(lexicon: Lexicon): NgramModel {
    val path = System.getProperty("hushh.ngrams") ?: error("hushh.ngrams system property not set")
    return NgramModel.mmap(File(path), lexicon)
}

private fun goldenLines(name: String): List<List<String>> =
    NgramTest::class.java.getResourceAsStream(name)!!.bufferedReader().readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { it.split('\t') }

private fun unquote(s: String): String {
    require(s.length >= 2 && s.first() == '"' && s.last() == '"') { "expected a quoted string: $s" }
    return s.substring(1, s.length - 1)
}

/**
 * Next-word prediction must match the reference implementation (tools/nlp/ngrams.py) exactly.
 * Regenerate the golden files with `python3 tools/nlp/build_assets.py`.
 */
class NgramTest : FunSpec({
    val lexicon = fullLexicon()
    val model = fullNgrams(lexicon)

    val predictions = goldenLines("/golden_predictions.tsv")
    val contexts = goldenLines("/golden_context.tsv")

    test("golden files are not empty") {
        (predictions.size > 10) shouldBe true
        (contexts.size > 10) shouldBe true
    }

    for (row in predictions) {
        val before = unquote(row[0])
        val expected = row.drop(1).filter { it.isNotEmpty() }
        test("predictions after '$before'") {
            val ctx = model.context(lexicon, before)
            ctx.predictions(3, lexicon).map { (node, _) -> lexicon.display(node) ?: ctx.keyOf(node) } shouldBe expected
        }
    }

    for (row in contexts) {
        val before = unquote(row[0])
        val typed = row[1]
        val expected = row.drop(2)
        test("context completions for '$before' + '$typed'") {
            val engine = WordEngine(lexicon, UserModel(), ngrams = model)
            engine.suggest(typed, before, 3).suggestions.map { it.text } shouldBe expected
        }
    }

    test("sentence chunks are split like the reference tokenizer") {
        NgramModel.lastChunkWords("") shouldBe emptyList()
        NgramModel.lastChunkWords("Hi there. How are ") shouldBe listOf("How", "are")
        NgramModel.lastChunkWords("I don’t know, 2 of my friends") shouldBe listOf("I", "don't", "know", "of", "my", "friends")
        NgramModel.lastChunkWords("well-known; M-Pesa ") shouldBe listOf("M-Pesa")
        NgramModel.lastChunkWords("Done.") shouldBe listOf("Done")
    }

    test("context nodes: sentence start, single word, unknown word") {
        NgramModel.contextNodes(lexicon, "") shouldBe (null to NgramModel.START)
        NgramModel.contextNodes(lexicon, "Done. ") shouldBe (null to NgramModel.START)
        NgramModel.contextNodes(lexicon, "Okay!\n") shouldBe (null to NgramModel.START)
        val thank = lexicon.lookup("thank")!!.node
        NgramModel.contextNodes(lexicon, "Thank ") shouldBe (NgramModel.START to thank)
        NgramModel.contextNodes(lexicon, "xyzzyq ") shouldBe (null to null)
        val (p2, p1) = NgramModel.contextNodes(lexicon, "How are ")
        p2 shouldBe lexicon.lookup("how")!!.node
        p1 shouldBe lexicon.lookup("are")!!.node
    }

    test("rejects a model built for a different dictionary") {
        val bytes = File(System.getProperty("hushh.ngrams")).readBytes()
        shouldThrow<IllegalArgumentException> { NgramModel.fromBytes(bytes, miniLexicon()) }
        shouldThrow<IllegalArgumentException> { NgramModel.fromBytes(ByteArray(8), lexicon) }
    }

    test("context boosts are bounded and never negative") {
        val ctx = model.context(lexicon, "Thank ")
        val you = lexicon.lookup("you")!!
        val b = ctx.boost(you.node, you.freq)
        (b > 0.0) shouldBe true
        (b <= ContextModel.CTX_MAX_BOOST) shouldBe true
        for ((node, _) in ctx.logp) (ctx.boost(node, lexicon.freq(node)) >= 0.0) shouldBe true
    }

    test("engine predicts after a space for a brand-new user") {
        val engine = WordEngine(lexicon, UserModel(), ngrams = model)
        val result = engine.suggest("", "Thank ", 3)
        result.suggestions.first().text shouldBe "you"
        result.suggestions.first().kind shouldBe WordEngine.Kind.PREDICTION
        result.autoCorrection shouldBe null
    }

    test("predictions are capitalised at a sentence start") {
        val engine = WordEngine(lexicon, UserModel(), ngrams = model)
        val words = engine.suggest("", "", 3).suggestions.map { it.text }
        words.isEmpty() shouldBe false
        for (w in words) w[0].isUpperCase() shouldBe true
        val afterStop = engine.suggest("", "Done. ", 3).suggestions.map { it.text }
        for (w in afterStop) w[0].isUpperCase() shouldBe true
        // Mid-sentence predictions keep their natural case.
        engine.suggest("", "Thank ", 1).suggestions.first().text shouldBe "you"
    }

    test("unknown context gives no model predictions and no crash") {
        val engine = WordEngine(lexicon, UserModel(), ngrams = model)
        engine.suggest("", "xyzzyq ", 3).suggestions shouldBe emptyList()
    }

    test("personal follow-ups are merged with model predictions") {
        val engine = WordEngine(lexicon, UserModel(), ngrams = model)
        repeat(4) {
            engine.learn("Thank", null)
            engine.learn("habibi", "Thank")
        }
        val words = engine.suggest("", "Thank ", 8).suggestions.map { it.text }
        words shouldContain "you"
        words shouldContain "habibi"
    }

    test("removed words are never predicted") {
        val engine = WordEngine(lexicon, UserModel(), ngrams = model)
        engine.suggest("", "Thank ", 3).suggestions.map { it.text } shouldContain "you"
        engine.removeSuggestion("you")
        engine.suggest("", "Thank ", 3).suggestions.map { it.text } shouldNotContain "you"
    }

    test("offensive words are filtered from predictions") {
        val ctx = model.context(lexicon, "")
        val offensive = ctx.logp.keys.filter { lexicon.flags(it) and Lexicon.FLAG_OFFENSIVE != 0 }
        val shown = ctx.predictions(1000, lexicon).map { it.first }.toSet()
        for (node in offensive) (node in shown) shouldBe false
    }

    test("context ranks the expected word first while typing") {
        val withModel = WordEngine(lexicon, UserModel(), ngrams = model)
        withModel.suggest("m", "Good ", 3).suggestions.first().text shouldBe "morning"
        val plain = WordEngine(lexicon, UserModel())
        plain.suggest("m", "Good ", 3).suggestions.first().text shouldNotBe null
    }
})
