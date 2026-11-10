/*
 * Copyright (C) 2026 HushhBoard
 * Licensed under the Apache License, Version 2.0.
 */

package dev.hushhboard.nlp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The Kotlin decoder must produce exactly the same top-3 suggestions as the tuned reference
 * implementation (tools/nlp/hdict.py). Regenerate with `python3 tools/nlp/build_assets.py`.
 */
class GoldenTest : FunSpec({
    val decoder = Decoder(fullLexicon())
    val golden = GoldenTest::class.java.getResourceAsStream("/golden_suggestions.tsv")!!
        .bufferedReader().readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { line -> line.split('\t').let { it[0] to it.drop(1) } }

    test("golden file is not empty") {
        (golden.size > 30) shouldBe true
    }

    for ((query, expected) in golden) {
        test("top-3 for '$query'") {
            val actual = decoder.suggest(query, 3).map { Text.applyCase(query, it.text) }
            actual shouldBe expected
        }
    }
})
