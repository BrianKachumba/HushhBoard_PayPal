/*
 * Copyright (C) 2026 HushhBoard
 * Licensed under the Apache License, Version 2.0.
 */

package dev.hushhboard.nlp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class UserModelTest : FunSpec({
    val en = Lexicon.FLAG_LANG_EN
    val sw = Lexicon.FLAG_LANG_SW

    test("unknown words are trusted only after being typed twice") {
        val m = UserModel()
        m.learnWord("Kiprotich", null, 0)
        m.isKnown("kiprotich") shouldBe false
        m.learnWord("Kiprotich", null, 0)
        m.isKnown("kiprotich") shouldBe true
        m.isKnown("KIPROTICH") shouldBe true
    }

    test("confirmed words are trusted immediately") {
        val m = UserModel()
        m.confirmWord("Wanjiku")
        m.isKnown("wanjiku") shouldBe true
    }

    test("words with digits, emails or URLs are never learned") {
        val m = UserModel()
        repeat(3) {
            m.learnWord("abc123", null, 0)
            m.learnWord("me@x.com", null, 0)
            m.learnWord("http://x", null, 0)
            m.learnWord("a", null, 0)
        }
        m.wordCount shouldBe 0
    }

    test("blocking removes a word and its pairs, and stops relearning") {
        val m = UserModel()
        repeat(3) { m.learnWord("yako", "habari", sw) }
        m.predictNext("habari", 3).map { it.first } shouldBe listOf("yako")
        m.blockWord("yako")
        m.isKnown("yako") shouldBe false
        m.predictNext("habari", 3) shouldBe emptyList()
        m.learnWord("yako", "habari", sw)
        m.isKnown("yako") shouldBe false
    }

    test("next-word prediction needs repeated evidence and ranks by frequency") {
        val m = UserModel()
        m.learnWord("morning", "good", en)
        m.predictNext("good", 3) shouldBe emptyList()
        repeat(3) { m.learnWord("morning", "good", en) }
        repeat(2) { m.learnWord("night", "good", en) }
        m.predictNext("Good", 3).map { it.first } shouldBe listOf("morning", "night")
    }

    test("boosts grow with use and are capped") {
        val m = UserModel()
        m.wordBoost("sawa") shouldBe 0.0
        m.learnWord("sawa", null, sw)
        val one = m.wordBoost("sawa")
        repeat(500) { m.learnWord("sawa", null, sw) }
        m.wordBoost("sawa") shouldBeGreaterThan one
        (m.wordBoost("sawa") <= UserModel.MAX_WORD_BOOST) shouldBe true
    }

    test("language priors start at the defaults and adapt to Swahili use") {
        val m = UserModel()
        m.languagePriors() shouldBe LanguagePriors(0.0, DecoderConfig.PRIOR_SW, DecoderConfig.PRIOR_KE)
        repeat(200) { m.learnWord("habari", null, sw) }
        val p = m.languagePriors()
        p.sw shouldBeGreaterThan DecoderConfig.PRIOR_SW
        p.en shouldBeLessThan 0.0
    }

    test("old activity decays") {
        var now = 0L
        val m = UserModel(clock = { now })
        repeat(5) { m.learnWord("chama", null, 0) }
        val fresh = m.wordBoost("chama")
        now += 180L * 86_400_000L
        m.wordBoost("chama") shouldBeLessThan fresh
    }

    test("eviction keeps the model bounded and protects confirmed words") {
        val m = UserModel(maxWords = 100)
        m.confirmWord("keepme")
        for (i in 0 until 300) m.learnWord("word" + ('a' + i % 26) + ('a' + i / 26), null, 0)
        (m.wordCount <= 100) shouldBe true
        m.isKnown("keepme") shouldBe true
    }

    test("round-trips through its file format") {
        val m = UserModel()
        repeat(3) { m.learnWord("Wanjiku", null, 0) }
        repeat(2) { m.learnWord("yako", "habari", sw) }
        m.blockWord("spam")
        val bytes = ByteArrayOutputStream().also { m.writeTo(it) }.toByteArray()
        val r = UserModel()
        r.readFrom(ByteArrayInputStream(bytes))
        r.isKnown("wanjiku") shouldBe true
        r.predictNext("habari", 1).map { it.first } shouldBe listOf("yako")
        r.isBlocked("spam") shouldBe true
        r.languagePriors() shouldBe m.languagePriors()
    }

    test("atomic save/load, and corrupt files are discarded") {
        val dir = Files.createTempDirectory("hushh").toFile()
        val f = File(dir, "user.bin")
        val m = UserModel()
        repeat(2) { m.learnWord("Odhiambo", null, 0) }
        m.saveTo(f)
        val r = UserModel()
        r.loadFrom(f) shouldBe true
        r.isKnown("odhiambo") shouldBe true
        f.writeBytes(byteArrayOf(1, 2, 3))
        UserModel().loadFrom(f) shouldBe false
        f.exists() shouldBe false
        dir.deleteRecursively()
    }
})
