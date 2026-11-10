/*
 * Copyright (C) 2026 HushhBoard
 * Licensed under the Apache License, Version 2.0.
 */

package dev.hushhboard.nlp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.random.Random

/**
 * Latency guard. CI machines are faster than phones, so the budget here is strict: a regression
 * that makes typing feel slow on a phone should fail this test first.
 */
class PerformanceTest : FunSpec({
    test("suggest() latency on the production dictionary") {
        val lexicon = fullLexicon()
        val engine = WordEngine(lexicon, ngrams = fullNgrams(lexicon))
        repeat(3) { engine.learn("Kiprotich", null) }
        repeat(3) { engine.learn("yako", "habari") }

        val rng = Random(42)
        val words = engine.lexicon.topWords(5000).map { it.first.lowercase() }.filter { it.length >= 3 }
        val queries = ArrayList<String>()
        repeat(1000) {
            val w = words[rng.nextInt(words.size)]
            val q = when (rng.nextInt(4)) {
                0 -> w.substring(0, 1 + rng.nextInt(w.length))            // prefix
                1 -> w.replaceRange(rng.nextInt(w.length).let { it..it }, "q") // substitution
                2 -> w.removeRange(rng.nextInt(w.length).let { it..it })      // missed tap
                else -> w + w.last()                                          // double tap
            }
            if (q.isNotEmpty()) queries.add(q)
        }

        // Warm up the JIT.
        for (q in queries.take(300)) engine.suggest(q, "habari ")

        val times = LongArray(queries.size)
        for ((i, q) in queries.withIndex()) {
            val t0 = System.nanoTime()
            engine.suggest(q, "habari ")
            times[i] = System.nanoTime() - t0
        }
        times.sort()
        val p50 = times[times.size / 2] / 1e6
        val p95 = times[(times.size * 95) / 100] / 1e6
        val max = times.last() / 1e6
        println("HUSHH_PERF suggest(): p50=%.2fms p95=%.2fms max=%.2fms over %d queries".format(p50, p95, max, times.size))
        (p95 < 15.0) shouldBe true

        // Next-word predictions run after every space, so they must be even cheaper.
        val contexts = listOf("", "I ", "Thank ", "How are ", "I want to ", "Habari ", "Mimi ni ", "See you ", "Done. ")
        repeat(200) { for (c in contexts) engine.suggest("", c) }
        val pt = LongArray(contexts.size * 100)
        var k = 0
        repeat(100) {
            for (c in contexts) {
                val t0 = System.nanoTime()
                engine.suggest("", c)
                pt[k++] = System.nanoTime() - t0
            }
        }
        pt.sort()
        val pp95 = pt[(pt.size * 95) / 100] / 1e6
        println("HUSHH_PERF predict(): p50=%.2fms p95=%.2fms over %d calls".format(pt[pt.size / 2] / 1e6, pp95, pt.size))
        (pp95 < 5.0) shouldBe true
    }
})
