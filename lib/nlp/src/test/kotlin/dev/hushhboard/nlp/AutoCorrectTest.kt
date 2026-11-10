/*
 * Copyright (C) 2026 HushhBoard
 * Licensed under the Apache License, Version 2.0.
 */

package dev.hushhboard.nlp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

private fun resourceLines(name: String): List<List<String>> =
    AutoCorrectTest::class.java.getResourceAsStream(name)!!.bufferedReader().readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { it.split('\t') }

/** Kotlin must make exactly the same autocorrect decisions as the tuned reference (tools/nlp/hdict.py). */
class GoldenAutocorrectTest : FunSpec({
    val lexicon = fullLexicon()
    val decoder = Decoder(lexicon)
    for (row in resourceLines("/golden_autocorrect.tsv")) {
        val (typed, start, expected) = row
        test("autocorrect '$typed' (sentenceStart=$start)") {
            val ranked = decoder.suggest(typed, 8)
            val info = lexicon.lookup(typed)
            val choice = AutoCorrector.choose(typed, ranked, info != null, info?.freq ?: 0, start == "1")
            val actual = choice?.let { Text.applyCase(typed, it.text) } ?: "-"
            actual shouldBe expected
        }
    }
})

class GoldenTouchTest : FunSpec({
    val decoder = Decoder(fullLexicon())
    for (row in resourceLines("/golden_touch.tsv")) {
        val typed = row[0]
        val taps = row[1].split(';').map { p -> p.split(',').let { TouchPoint(it[0].toDouble(), it[1].toDouble()) } }
        val expected = row.drop(2)
        test("touch-aware top-3 for '$typed'") {
            decoder.suggest(typed, 3, touches = taps).map { Text.applyCase(typed, it.text) } shouldBe expected
        }
    }
})

class GoldenSmartTargetsTest : FunSpec({
    val lexicon = fullLexicon()
    val prox = KeyProximity.QWERTY
    for (row in resourceLines("/golden_smart_targets.tsv")) {
        val (prefix, default, x, y) = row
        val expected = row[4]
        test("smart target after '$prefix' on '$default' at ($x,$y)") {
            val d = default[0]
            SmartTargets.choose(lexicon, prox, prefix, TouchPoint(x.toDouble(), y.toDouble()), d, prox.neighbours(d))
                .toString() shouldBe expected
        }
    }
})

class AutoCorrectTest : FunSpec({
    val lexicon = fullLexicon()
    fun engine() = WordEngine(lexicon)
    fun WordEngine.auto(typed: String, before: String = "") = suggest(typed, before).autoCorrection?.text

    test("fixes clear typos when the word is finished") {
        engine().auto("teh") shouldBe "the"
        engine().auto("recieve") shouldBe "receive"
        engine().auto("definately") shouldBe "definitely"
        engine().auto("habri") shouldBe "habari"
        engine().auto("im") shouldBe "I'm"
        engine().auto("dont") shouldBe "don't"
        engine().auto("cant") shouldBe "can't"
        engine().auto("i") shouldBe "I"
    }

    test("keeps sentence-start capitals") {
        engine().auto("Teh", "") shouldBe "The"
        engine().auto("Teh", "Done. ") shouldBe "The"
    }

    test("leaves valid words, names, slang and acronyms alone") {
        val e = engine()
        for (w in listOf("hello", "sawa", "msee", "lakini", "well", "were", "its", "ill", "KCB", "TEH", "abc123")) {
            e.auto(w) shouldBe null
        }
        e.auto("Kibet", "I met ") shouldBe null       // capitalised mid-sentence: a name
        e.auto("Teh", "I met ") shouldBe null
        e.auto("wangui") shouldBe null
        e.auto("kuandikishwa") shouldBe null
        e.auto("omw") shouldBe null                   // shortcuts are suggestions, not automatic
        e.auto("ur") shouldBe null
    }

    test("never repeats a correction the user undid, and trusts the word") {
        val e = engine()
        e.auto("teh") shouldBe "the"
        e.onAutoCorrectionUndone("teh")
        e.auto("teh") shouldBe null
        e.isValidWord("teh") shouldBe true
    }

    test("words the user types repeatedly are never corrected") {
        val e = engine()
        e.auto("habri") shouldBe "habari"
        repeat(2) { e.learn("habri", null) }
        e.auto("habri") shouldBe null
    }

    test("a correction is also offered in the suggestion bar") {
        val r = engine().suggest("teh", "")
        r.autoCorrection shouldNotBe null
        r.suggestions.first().text shouldBe r.autoCorrection!!.text
    }
})

class TouchModelTest : FunSpec({
    val prox = KeyProximity.QWERTY

    test("needs enough evidence before offsets are used") {
        val m = TouchModel()
        m.learn("the", listOf(TouchPoint(4.6, 0.7), TouchPoint(6.1, 1.7), TouchPoint(2.6, 0.7)), prox)
        m.snapshot() shouldBe null
    }

    test("learns a consistent thumb bias and applies it to unseen keys") {
        val m = TouchModel()
        val bias = TouchPoint(0.1, 0.2)
        repeat(60) {
            val word = "hello"
            val taps = word.map { c -> prox.center(c)!!.let { TouchPoint(it.x + bias.x, it.y + bias.y) } }
            m.learn(word, taps, prox)
        }
        val off = m.snapshot()!!
        off.offsetFor('h')!!.x shouldBe (0.1 plusOrMinus 0.02)
        off.offsetFor('h')!!.y shouldBe (0.2 plusOrMinus 0.03)
        off.offsetFor('z')!!.y shouldBe (0.2 plusOrMinus 0.05) // falls back to the global bias
    }

    test("offsets are clamped and wild taps ignored") {
        val m = TouchModel()
        repeat(100) {
            m.learn("a", listOf(prox.center('a')!!.let { TouchPoint(it.x + 0.8, it.y + 0.8) }), prox)
            m.learn("s", listOf(prox.center('s')!!.let { TouchPoint(it.x + 5.0, it.y) }), prox)
        }
        val off = m.snapshot()!!
        (off.offsetFor('a')!!.x <= TouchModel.MAX_OFFSET) shouldBe true
        m.sampleCount shouldBe 100
    }

    test("taps near a key border are resolved with the dictionary") {
        val e = WordEngine(fullLexicon())
        val y = prox.center('y')!!
        e.resolveTap("matat", TouchPoint(y.x + 0.47, y.y), 'y') shouldBe 'u'
        val h = prox.center('h')!!
        e.resolveTap("", TouchPoint(h.x, h.y), 'h') shouldBe 'h'            // centred tap: never changed
        e.resolveTap("matat", TouchPoint(y.x + 0.47, y.y), 'Y') shouldBe 'U' // keeps shift state
    }

    test("tap positions sharpen corrections") {
        val e = WordEngine(fullLexicon())
        val taps = "tge".mapIndexed { i, c ->
            val p = prox.center(c)!!
            if (i == 1) TouchPoint(p.x - 0.05, p.y - 0.42) else TouchPoint(p.x, p.y)
        }
        e.suggest("tge", "", touches = taps).suggestions.first().text shouldBe "the"
    }

    test("user model file keeps rejected corrections and tap offsets, and still reads version 1") {
        val m = UserModel()
        m.rejectCorrection("teh")
        repeat(50) { m.learnTouches("hello", "hello".map { c -> prox.center(c)!!.let { TouchPoint(it.x, it.y + 0.2) } }, prox) }
        val bytes = ByteArrayOutputStream().also { m.writeTo(it) }.toByteArray()
        val r = UserModel()
        r.readFrom(ByteArrayInputStream(bytes))
        r.isCorrectionRejected("teh") shouldBe true
        r.touchOffsets()!!.offsetFor('e')!!.y shouldBe (0.2 plusOrMinus 0.05)

        val v1 = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).apply {
                writeInt(0x48555352); writeInt(1); writeDouble(0.0); writeDouble(0.0)
                writeInt(1); writeUTF("kibet"); writeUTF("Kibet"); writeInt(3); writeInt(0); writeByte(0)
                writeInt(0); flush()
            }
        }.toByteArray()
        val old = UserModel()
        old.readFrom(ByteArrayInputStream(v1))
        old.isKnown("kibet") shouldBe true
        old.touchOffsets() shouldBe null
    }
})
