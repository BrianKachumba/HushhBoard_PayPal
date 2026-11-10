/*
 * Copyright (C) 2026 HushhBoard
 * Licensed under the Apache License, Version 2.0.
 */

package dev.hushhboard.nlp

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The user's personal language model, kept only on the device.
 *
 *  - Words the user types (counts + recency). Unknown words become suggestable only after being
 *    typed [PROMOTE_AFTER] times or explicitly accepted, so one-off typos are not learned.
 *  - Word pairs ("habari" -> "yako") for context boosts and next-word prediction.
 *  - The user's English/Swahili mix, used to adapt the language priors.
 *  - Words the user removed from suggestions are blocked.
 *
 * Thread-safe. Callers decide *whether* to learn (e.g. never in incognito or password fields).
 */
class UserModel(
    private val maxWords: Int = 20_000,
    private val maxBigrams: Int = 50_000,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    class WordStat(var display: String, var count: Int, var lastDay: Int, var confirmed: Boolean, var blocked: Boolean)
    class BigramStat(var count: Int, var lastDay: Int)

    private val lock = ReentrantReadWriteLock()
    private val words = HashMap<String, WordStat>()
    private val bigrams = HashMap<String, HashMap<String, BigramStat>>()
    private var bigramTotal = 0
    private var enUse = 0.0
    private var swUse = 0.0
    private val rejectedCorrections = LinkedHashSet<String>()
    private var touchModel = TouchModel()

    /**
     * Snapshot of the learned tap offsets, rebuilt only when they change. Read lock-free on the
     * UI thread for every key press, so a background save or learn can never stall a tap.
     */
    @Volatile
    private var touchSnapshot: TouchOffsets? = null

    @Volatile
    var revision: Long = 0
        private set

    private fun today(): Int = (clock() / 86_400_000L).toInt()

    private fun touch() {
        revision++
    }

    /** Changes only when the set of trusted words (or their spelling) changes. */
    @Volatile
    var vocabularyRevision: Long = 0
        private set

    private fun vocabularyChanged() {
        vocabularyRevision++
    }

    // ---------------------------------------------------------------- learning

    /**
     * Records that the user finished typing [word] (followed by a space or punctuation).
     * [previousWord] is the word before it in the same sentence, if any.
     * [lexiconFlags] are the word's dictionary flags (0 if unknown) for language-mix tracking.
     */
    fun learnWord(word: String, previousWord: String?, lexiconFlags: Int, sentenceStart: Boolean = false) = lock.write {
        val clean = Text.normalizeApostrophes(word)
        if (!Text.isLearnable(clean)) return@write
        val key = Text.normalizeKey(clean)
        val day = today()
        val stat = words[key]
        // Only learn how a word is spelled when its casing says something about the word itself:
        // "Hello" at the start of a sentence or "HELLO" in caps lock does not mean "hello" is a name.
        val casing = Text.learnableCasing(clean, sentenceStart)
        if (stat != null) {
            if (stat.blocked) return@write
            stat.count++
            stat.lastDay = day
            if (stat.count == PROMOTE_AFTER) vocabularyChanged()
            if (casing != null && casing != stat.display) {
                stat.display = casing
                vocabularyChanged()
            }
        } else {
            words[key] = WordStat(casing ?: key, 1, day, confirmed = false, blocked = false)
            if (words.size > maxWords) evictWords(day)
        }
        if (previousWord != null) {
            val prev = Text.normalizeKey(Text.normalizeApostrophes(previousWord))
            if (Text.isLearnable(prev)) {
                val followers = bigrams.getOrPut(prev) { HashMap() }
                val b = followers[key]
                if (b != null) {
                    b.count++
                    b.lastDay = day
                } else {
                    followers[key] = BigramStat(1, day)
                    bigramTotal++
                    if (bigramTotal > maxBigrams) evictBigrams(day)
                }
            }
        }
        trackLanguage(lexiconFlags)
        touch()
    }

    /** The user explicitly picked [word] from the suggestion bar: it is now trusted. */
    fun confirmWord(word: String) = lock.write {
        val clean = Text.normalizeApostrophes(word)
        if (!Text.isLearnable(clean)) return@write
        val key = Text.normalizeKey(clean)
        val stat = words.getOrPut(key) { WordStat(clean, 0, today(), confirmed = false, blocked = false) }
        stat.confirmed = true
        stat.blocked = false
        touch()
        vocabularyChanged()
    }

    /** The user removed [word] from suggestions: never suggest it from personal data again. */
    fun blockWord(word: String) = lock.write {
        val key = Text.normalizeKey(Text.normalizeApostrophes(word))
        val stat = words.getOrPut(key) { WordStat(word, 0, today(), confirmed = false, blocked = true) }
        stat.blocked = true
        stat.confirmed = false
        bigrams.values.forEach { it.remove(key) }
        bigrams.remove(key)
        recountBigrams()
        touch()
        vocabularyChanged()
    }

    /** Forgets everything (Settings -> clear learned words). */
    fun clear() = lock.write {
        words.clear()
        bigrams.clear()
        bigramTotal = 0
        enUse = 0.0
        swUse = 0.0
        rejectedCorrections.clear()
        touchModel.clear()
        touchSnapshot = null
        touch()
        vocabularyChanged()
    }

    // ---------------------------------------------------------------- autocorrect & touch

    /**
     * The user undid an automatic correction of [typed]: never autocorrect it again, and trust it
     * as a real word from now on.
     */
    fun rejectCorrection(typed: String) = lock.write {
        val clean = Text.normalizeApostrophes(typed)
        val key = Text.normalizeKey(clean)
        if (key.isEmpty()) return@write
        rejectedCorrections.add(key)
        if (rejectedCorrections.size > MAX_REJECTED) rejectedCorrections.remove(rejectedCorrections.first())
        if (Text.isLearnable(clean)) {
            val stat = words.getOrPut(key) { WordStat(clean, 0, today(), confirmed = false, blocked = false) }
            stat.confirmed = true
            stat.blocked = false
        }
        touch()
        vocabularyChanged()
    }

    fun isCorrectionRejected(key: String): Boolean = lock.read { key in rejectedCorrections }

    /** Learn this user's tap offsets from a word they typed exactly as intended. */
    fun learnTouches(word: String, taps: List<TouchPoint?>, proximity: KeyProximity) = lock.write {
        touchModel.learn(word, taps, proximity)
        touchSnapshot = touchModel.snapshot()
        touch()
    }

    /** The user's learned per-key tap offsets, or null until there is enough evidence. Lock-free. */
    fun touchOffsets(): TouchOffsets? = touchSnapshot

    val touchSampleCount: Int get() = lock.read { touchModel.sampleCount }

    // ---------------------------------------------------------------- queries

    fun isBlocked(key: String): Boolean = lock.read { words[key]?.blocked == true }

    /** True if [word] is a trusted personal word (typed enough times or confirmed). */
    fun isKnown(word: String): Boolean = lock.read {
        val s = words[Text.normalizeKey(Text.normalizeApostrophes(word))] ?: return@read false
        !s.blocked && (s.confirmed || s.count >= PROMOTE_AFTER)
    }

    /** Log-space boost for a word the user types often. */
    fun wordBoost(key: String): Double = lock.read {
        val s = words[key] ?: return@read 0.0
        if (s.blocked || s.count <= 0) return@read 0.0
        min(MAX_WORD_BOOST, WORD_BOOST_SCALE * ln(1.0 + s.count) * recency(s.lastDay))
    }

    /** Log-space boost when [key] often follows [previousKey] for this user. */
    fun contextBoost(previousKey: String?, key: String): Double = lock.read {
        if (previousKey == null) return@read 0.0
        val b = bigrams[previousKey]?.get(key) ?: return@read 0.0
        min(MAX_CONTEXT_BOOST, CONTEXT_BOOST_SCALE * ln(1.0 + b.count) * recency(b.lastDay))
    }

    /** Personal words not necessarily in the dictionary, for fuzzy matching. */
    fun trustedWords(): List<Pair<String, WordStat>> = lock.read {
        words.entries.filter { (_, s) -> !s.blocked && (s.confirmed || s.count >= PROMOTE_AFTER) }
            .map { it.key to WordStat(it.value.display, it.value.count, it.value.lastDay, it.value.confirmed, false) }
    }

    /**
     * What this user typed after [previousWord]: (total weight of all followers, followers seen at
     * least [MIN_PREDICTION_COUNT] times as (display, weight), best first). Weights decay with age.
     */
    fun followers(previousWord: String, limit: Int): Pair<Double, List<Pair<String, Double>>> = lock.read {
        val prev = Text.normalizeKey(Text.normalizeApostrophes(previousWord))
        val map = bigrams[prev] ?: return@read 0.0 to emptyList()
        var total = 0.0
        val out = ArrayList<Pair<String, Double>>()
        for ((k, b) in map) {
            val w = b.count * recency(b.lastDay)
            total += w
            if (b.count >= MIN_PREDICTION_COUNT && words[k]?.blocked != true) out.add((words[k]?.display ?: k) to w)
        }
        out.sortWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
        total to if (out.size > limit) out.subList(0, limit).toList() else out
    }

    /** Most likely next words after [previousWord], best first (needs [MIN_PREDICTION_COUNT] sightings). */
    fun predictNext(previousWord: String, limit: Int): List<Pair<String, Double>> = lock.read {
        val prev = Text.normalizeKey(Text.normalizeApostrophes(previousWord))
        val followers = bigrams[prev] ?: return@read emptyList()
        followers.entries
            .filter { (k, b) -> b.count >= MIN_PREDICTION_COUNT && words[k]?.blocked != true }
            .map { (k, b) -> (words[k]?.display ?: k) to b.count * recency(b.lastDay) }
            .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
            .take(limit)
    }

    /**
     * Language priors adapted to how much this user writes in Swahili vs English. Starts at the
     * defaults (English-leaning, with Kenyan words close behind) and moves with real usage.
     */
    fun languagePriors(): LanguagePriors = lock.read {
        val mixSw = (swUse + PRIOR_SW_PSEUDO) / (swUse + enUse + PRIOR_PSEUDO_TOTAL)
        val sw = (DecoderConfig.PRIOR_SW + PRIOR_ADAPT * ln(mixSw / BASE_MIX_SW)).coerceIn(-2.5, 0.0)
        val en = (PRIOR_ADAPT * ln((1.0 - mixSw) / (1.0 - BASE_MIX_SW))).coerceIn(-2.5, 0.0)
        val ke = (max(en, sw) + DecoderConfig.PRIOR_KE).coerceIn(-1.2, 0.0)
        LanguagePriors(en = en, sw = sw, ke = ke)
    }

    val wordCount: Int get() = lock.read { words.size }

    // ---------------------------------------------------------------- persistence

    fun writeTo(out: OutputStream) {
        out.write(toBytes())
        out.flush()
    }

    /** Serialises everything in memory (fast, under the read lock); file IO happens outside the lock. */
    fun toBytes(): ByteArray = lock.read {
        val bytes = java.io.ByteArrayOutputStream(64 * 1024)
        val d = DataOutputStream(bytes)
        d.writeInt(FILE_MAGIC)
        d.writeInt(FILE_VERSION)
        d.writeDouble(enUse)
        d.writeDouble(swUse)
        d.writeInt(words.size)
        for ((key, s) in words) {
            d.writeUTF(key)
            d.writeUTF(s.display)
            d.writeInt(s.count)
            d.writeInt(s.lastDay)
            d.writeByte((if (s.confirmed) 1 else 0) or (if (s.blocked) 2 else 0))
        }
        d.writeInt(bigrams.size)
        for ((prev, followers) in bigrams) {
            d.writeUTF(prev)
            d.writeInt(followers.size)
            for ((w, b) in followers) {
                d.writeUTF(w)
                d.writeInt(b.count)
                d.writeInt(b.lastDay)
            }
        }
        d.writeInt(rejectedCorrections.size)
        for (k in rejectedCorrections) d.writeUTF(k)
        touchModel.write(d)
        d.flush()
        bytes.toByteArray()
    }

    /** Replaces the current contents with data previously written by [writeTo]. */
    @Throws(IOException::class)
    fun readFrom(input: InputStream) {
        val d = DataInputStream(input.buffered())
        if (d.readInt() != FILE_MAGIC) throw IOException("Not a HushhBoard user model")
        val version = d.readInt()
        if (version < 1 || version > FILE_VERSION) throw IOException("Unsupported user model version $version")
        val newWords = HashMap<String, WordStat>()
        val newBigrams = HashMap<String, HashMap<String, BigramStat>>()
        val en = d.readDouble()
        val sw = d.readDouble()
        repeat(d.readInt()) {
            val key = d.readUTF()
            val display = d.readUTF()
            val count = d.readInt()
            val lastDay = d.readInt()
            val flags = d.readByte().toInt()
            newWords[key] = WordStat(display, count, lastDay, flags and 1 != 0, flags and 2 != 0)
        }
        var total = 0
        repeat(d.readInt()) {
            val prev = d.readUTF()
            val followers = HashMap<String, BigramStat>()
            repeat(d.readInt()) {
                val w = d.readUTF()
                followers[w] = BigramStat(d.readInt(), d.readInt())
            }
            total += followers.size
            newBigrams[prev] = followers
        }
        val newRejected = HashSet<String>()
        val newTouch = TouchModel()
        if (version >= 2) {
            repeat(d.readInt()) { newRejected.add(d.readUTF()) }
            newTouch.read(d)
        }
        lock.write {
            words.clear(); words.putAll(newWords)
            bigrams.clear(); bigrams.putAll(newBigrams)
            bigramTotal = total
            enUse = en
            swUse = sw
            rejectedCorrections.clear(); rejectedCorrections.addAll(newRejected)
            touchModel = newTouch
            touchSnapshot = newTouch.snapshot()
            touch()
            vocabularyChanged()
        }
    }

    /** Atomically saves to [file] (write to a temp file, then rename). */
    @Throws(IOException::class)
    fun saveTo(file: File) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        val bytes = toBytes()
        tmp.outputStream().use { it.write(bytes) }
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("Could not replace $file")
        }
    }

    /** Loads from [file] if present. A corrupt file is discarded rather than crashing the keyboard. */
    fun loadFrom(file: File): Boolean {
        if (!file.exists()) return false
        return try {
            file.inputStream().use { readFrom(it) }
            true
        } catch (e: IOException) {
            file.delete()
            false
        }
    }

    // ---------------------------------------------------------------- internals

    private fun recency(lastDay: Int): Double {
        val age = (today() - lastDay).coerceAtLeast(0)
        return 0.5.pow(age / RECENCY_HALF_LIFE_DAYS)
    }

    private fun trackLanguage(flags: Int) {
        val en = flags and Lexicon.FLAG_LANG_EN != 0
        val sw = flags and (Lexicon.FLAG_LANG_SW or Lexicon.FLAG_LANG_KE) != 0
        if (en == sw) return // unknown or shared word: no evidence either way
        enUse *= LANGUAGE_DECAY
        swUse *= LANGUAGE_DECAY
        if (en) enUse += 1.0 else swUse += 1.0
    }

    private fun evictWords(day: Int) {
        vocabularyChanged()
        val target = (maxWords * 0.9).toInt()
        val victims = words.entries
            .filter { !it.value.confirmed && !it.value.blocked }
            .sortedBy { it.value.count * 0.5.pow((day - it.value.lastDay).coerceAtLeast(0) / RECENCY_HALF_LIFE_DAYS) }
            .take((words.size - target).coerceAtLeast(0))
            .map { it.key }
        victims.forEach { words.remove(it) }
    }

    private fun evictBigrams(day: Int) {
        val all = ArrayList<Triple<String, String, Double>>(bigramTotal)
        for ((p, f) in bigrams) for ((w, b) in f) {
            all.add(Triple(p, w, b.count * 0.5.pow((day - b.lastDay).coerceAtLeast(0) / RECENCY_HALF_LIFE_DAYS)))
        }
        all.sortBy { it.third }
        val remove = (bigramTotal - (maxBigrams * 0.9).toInt()).coerceAtLeast(0)
        for (i in 0 until remove) {
            val (p, w, _) = all[i]
            val f = bigrams[p] ?: continue
            f.remove(w)
            if (f.isEmpty()) bigrams.remove(p)
        }
        recountBigrams()
    }

    private fun recountBigrams() {
        bigramTotal = bigrams.values.sumOf { it.size }
    }

    companion object {
        const val PROMOTE_AFTER = 2
        const val MIN_PREDICTION_COUNT = 2
        const val MAX_WORD_BOOST = 3.0
        const val WORD_BOOST_SCALE = 0.9
        const val MAX_CONTEXT_BOOST = 3.0
        const val CONTEXT_BOOST_SCALE = 1.4
        const val RECENCY_HALF_LIFE_DAYS = 90.0
        const val LANGUAGE_DECAY = 0.995
        const val BASE_MIX_SW = 0.25
        const val PRIOR_SW_PSEUDO = 5.0
        const val PRIOR_PSEUDO_TOTAL = 20.0
        const val PRIOR_ADAPT = 1.5
        private const val FILE_MAGIC = 0x48555352 // "HUSR"
        private const val FILE_VERSION = 2
        const val MAX_REJECTED = 2_000
    }
}
