/*
 * Copyright (C) 2026 HushhBoard
 * Licensed under the Apache License, Version 2.0.
 */

package dev.hushhboard.nlp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File

fun miniLexicon(): Lexicon =
    Lexicon.fromBytes(LexiconTest::class.java.getResourceAsStream("/mini.hdict")!!.readBytes())

fun fullLexicon(): Lexicon {
    val path = System.getProperty("hushh.dict") ?: error("hushh.dict system property not set")
    return Lexicon.mmap(File(path))
}

class LexiconTest : FunSpec({
    val lex = miniLexicon()

    test("header and meta are parsed") {
        lex.meta["name"] shouldBe "mini"
        lex.wordCount shouldBe 11
    }

    test("lookup is case-insensitive and returns display forms") {
        lex.lookup("the")!!.display shouldBe "the"
        lex.lookup("THE")!!.key shouldBe "the"
        lex.lookup("i")!!.display shouldBe "I"
        lex.lookup("nairobi")!!.display shouldBe "Nairobi"
        lex.lookup("m-pesa")!!.display shouldBe "M-Pesa"
        lex.lookup("don't")!!.freq shouldBe 185
    }

    test("prefixes are not words") {
        lex.lookup("th") shouldBe null
        lex.lookup("ther") shouldBe null
        lex.lookup("xyz") shouldBe null
    }

    test("language and offensive flags") {
        val sawa = lex.lookup("sawa")!!
        (sawa.flags and Lexicon.FLAG_LANG_SW != 0) shouldBe true
        (sawa.flags and Lexicon.FLAG_LANG_KE != 0) shouldBe true
        lex.lookup("damn")!!.isOffensive shouldBe true
        lex.lookup("the")!!.isOffensive shouldBe false
    }

    test("shortcuts") {
        lex.shortcut("im") shouldContainExactly listOf("I'm")
        lex.shortcut("ur") shouldContainExactly listOf("your", "you're")
        lex.shortcut("zz") shouldBe emptyList()
    }

    test("forEachWord visits every word once, in key order") {
        val keys = ArrayList<String>()
        lex.forEachWord { key, _, _, _ -> keys.add(key) }
        keys.size shouldBe lex.wordCount
        keys shouldBe keys.sorted()
    }

    test("maxFreq is the subtree maximum") {
        val th = lex.find("th")
        th shouldNotBe -1
        lex.maxFreq(th) shouldBe 222
    }

    test("topWords excludes offensive words by default") {
        val top = lex.topWords(100).map { it.first }
        top.first() shouldBe "the"
        top.contains("damn") shouldBe false
    }

    test("production dictionary loads and contains the core vocabulary") {
        val full = fullLexicon()
        (full.wordCount > 100_000) shouldBe true
        for (w in listOf("the", "hello", "sawa", "asante", "habari", "matatu", "Nairobi", "M-Pesa", "Fuliza", "I'm")) {
            (full.lookup(w) != null) shouldBe true
        }
        full.lookup("mpesa")!!.display shouldBe "Mpesa"
    }
})
