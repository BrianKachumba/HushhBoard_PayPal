/*
 * Copyright (C) 2026 HushhBoard
 * Licensed under the Apache License, Version 2.0.
 */

package dev.patrickgold.florisboard.ime.nlp.hushh

import android.content.Context
import dev.hushhboard.nlp.CommitDetector
import dev.hushhboard.nlp.Lexicon
import dev.hushhboard.nlp.NgramModel
import dev.hushhboard.nlp.Text
import dev.hushhboard.nlp.UserModel
import dev.hushhboard.nlp.WordEngine
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.editor.FlorisEditorInfo
import dev.patrickgold.florisboard.ime.editor.InputAttributes
import dev.patrickgold.florisboard.ime.input.InputShiftState
import dev.patrickgold.florisboard.ime.nlp.SpellingProvider
import dev.patrickgold.florisboard.ime.nlp.SpellingResult
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionProvider
import dev.patrickgold.florisboard.lib.devtools.flogError
import dev.patrickgold.florisboard.lib.devtools.flogInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Plugs the HushhBoard word engine (lib/nlp) into FlorisBoard's NLP provider slot.
 *
 * It is registered under the Latin provider id, so every existing English/Swahili subtype uses it
 * without migration. To swap engines (e.g. for a future upstream FlorisBoard engine), change the
 * single line in NlpManager that instantiates this class.
 *
 * Privacy: everything stays on the device. Nothing is learned in incognito mode, in password
 * fields (FlorisBoard disables suggestions there) or in apps that request no personalised learning.
 */
class HushhLanguageProvider(context: Context) : SpellingProvider, SuggestionProvider {
    companion object {
        const val ProviderId = "org.florisboard.nlp.providers.latin"
        private const val DICT_ASSET = "ime/dict/hushh/en_ke_sw.hdict"
        private const val NGRAM_ASSET = "ime/dict/hushh/en_ke_sw.hngm"
        private const val USER_MODEL_FILE = "hushh-nlp/user-model.bin"
        private const val SAVE_DEBOUNCE_MS = 15_000L
        private val SUPPORTED_LANGUAGES = setOf("en", "sw")
    }

    override val providerId = ProviderId

    private val appContext = context.applicationContext
    private val prefs by FlorisPreferenceStore
    private val editorInstance by appContext.editorInstance()
    private val keyboardManager by appContext.keyboardManager()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val loadLock = Mutex()
    private val detectorLock = Any()
    private val commitDetector = CommitDetector()

    @Volatile private var engine: WordEngine? = null
    @Volatile private var glideWords: List<String>? = null
    @Volatile private var lastSessionPrivate = false
    @Volatile private var savedRevision = -1L
    private var lastEditorInfo: Any? = null
    private var saveJob: Job? = null

    private val userModelFile: File get() = File(appContext.noBackupFilesDir, USER_MODEL_FILE)

    override suspend fun create() {
        // Nothing language-specific here; dictionaries are mapped lazily in preload().
    }

    override suspend fun preload(subtype: Subtype) {
        HushhTyping.smartTargetsEnabled = prefs.correction.smartTouch.get()
        if (!isSupported(subtype)) {
            HushhTyping.engine = null
            return
        }
        HushhTyping.engine = ensureEngine()
    }

    private suspend fun ensureEngine(): WordEngine? {
        engine?.let { return it }
        return loadLock.withLock {
            engine ?: try {
                val started = System.nanoTime()
                val lexicon = openLexicon()
                val model = UserModel()
                model.loadFrom(userModelFile)
                savedRevision = model.revision
                val ngrams = openNgrams(lexicon)
                val e = WordEngine(lexicon, model, HushhTyping.proximity, ngrams)
                engine = e
                HushhTyping.engine = e
                glideWords = e.glideWords().map { it.first }
                flogInfo {
                    "HushhBoard engine ready: ${lexicon.wordCount} words, ${model.wordCount} personal, " +
                        "next-word model ${if (ngrams != null) "on" else "off"}, " +
                        "${(System.nanoTime() - started) / 1_000_000} ms"
                }
                e
            } catch (e: Exception) {
                flogError { "HushhBoard engine failed to load: $e" }
                null
            }
        }
    }

    /** Memory-maps the dictionary straight out of the APK (it is stored uncompressed). */
    private fun openLexicon(): Lexicon = Lexicon.fromByteBuffer(mapAsset(DICT_ASSET))

    /**
     * The shipped next-word model. Optional: if it is missing or was built for a different
     * dictionary, typing still works with personal predictions only.
     */
    private fun openNgrams(lexicon: Lexicon): NgramModel? = try {
        NgramModel.fromByteBuffer(mapAsset(NGRAM_ASSET), lexicon)
    } catch (e: Exception) {
        flogError { "HushhBoard next-word model unavailable: $e" }
        null
    }

    private fun mapAsset(asset: String): ByteBuffer {
        return try {
            appContext.assets.openFd(asset).use { afd ->
                // The AssetFileDescriptor owns the fd; a mapping stays valid after it is closed.
                val channel = FileInputStream(afd.fileDescriptor).channel
                channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.length)
            }
        } catch (e: java.io.FileNotFoundException) {
            // Asset was compressed (should not happen with noCompress): copy once per app update, then map the copy.
            val copy = File(appContext.noBackupFilesDir, "hushh-nlp/" + asset.substringAfterLast('/'))
            val installed = try {
                appContext.packageManager.getPackageInfo(appContext.packageName, 0).lastUpdateTime
            } catch (_: Exception) {
                Long.MAX_VALUE
            }
            if (!copy.exists() || copy.lastModified() < installed) {
                copy.parentFile?.mkdirs()
                val tmp = File(copy.path + ".tmp")
                appContext.assets.open(asset).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                if (!tmp.renameTo(copy)) error("could not install $asset")
            }
            FileInputStream(copy).channel.use { it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()) }
        }
    }

    private fun isSupported(subtype: Subtype): Boolean =
        subtype.primaryLocale.language.lowercase() in SUPPORTED_LANGUAGES

    override suspend fun spell(
        subtype: Subtype,
        word: String,
        precedingWords: List<String>,
        followingWords: List<String>,
        maxSuggestionCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): SpellingResult {
        if (!isSupported(subtype)) return SpellingResult.unspecified()
        val e = ensureEngine() ?: return SpellingResult.unspecified()
        val result = e.spell(word, maxSuggestionCount, allowPossiblyOffensive)
        return if (result.isValid) {
            SpellingResult.validWord()
        } else {
            SpellingResult.typo(result.suggestions.toTypedArray())
        }
    }

    override suspend fun suggest(
        subtype: Subtype,
        content: EditorContent,
        maxCandidateCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): List<SuggestionCandidate> {
        if (!isSupported(subtype)) return emptyList()
        val e = ensureEngine() ?: return emptyList()
        lastSessionPrivate = isPrivateSession
        if (content.selection.isSelectionMode) return emptyList()

        val textBefore = content.textBeforeSelection
        val composing = content.composingText
        HushhTyping.smartTargetsEnabled = prefs.correction.smartTouch.get()

        val before = if (composing.isNotEmpty() && textBefore.endsWith(composing)) {
            textBefore.substring(0, textBefore.length - composing.length)
        } else {
            textBefore
        }
        if (composing.isEmpty() && !shouldPredict(before)) return emptyList()
        val previousWord = if (composing.isEmpty()) Text.previousWords(before, 1).firstOrNull() else null
        val sentenceStart = composing.isEmpty() && Text.isSentenceStart(before)
        val touches = HushhTyping.taps.tapsFor(composing)
        val result = e.suggest(composing, before, maxCandidateCount, allowPossiblyOffensive, touches, nextWordCase(subtype))
        val auto = result.autoCorrection?.takeIf { autoCorrectAllowed() }
        val out = ArrayList<SuggestionCandidate>(result.suggestions.size + 1)
        if (auto != null) {
            out.add(HushhWordCandidate(auto.text, composing, 1.0, isEligibleForAutoCommit = true, sourceProvider = this))
        }
        for (s in result.suggestions) {
            if (auto != null && s.text == auto.text) continue
            out.add(
                HushhWordCandidate(
                    s.text, composing, s.confidence, isEligibleForAutoCommit = false, sourceProvider = this,
                    previousWord = previousWord, sentenceStart = sentenceStart,
                ),
            )
        }
        return out
    }

    /**
     * Sees every change of the text in order (called before the suggestions are computed, which
     * may be skipped when the user types faster than they are computed): learns the words the user
     * finishes and tracks whether they are typing right now.
     */
    fun observe(subtype: Subtype, content: EditorContent, isPrivateSession: Boolean) {
        if (!isSupported(subtype)) return
        val info = editorInstance.activeInfo
        val committed = synchronized(detectorLock) {
            if (info !== lastEditorInfo) {
                // A new text field: nothing typed here yet, so show the toolbar, not predictions.
                lastEditorInfo = info
                commitDetector.reset()
            }
            if (content.selection.isSelectionMode || content.selection.isNotValid) {
                commitDetector.reset()
                return
            }
            commitDetector.observe(content.textBeforeSelection, content.selection.start, content.composingText)
        }
        if (committed == null || isPrivateSession) return
        val e = engine ?: return
        e.learn(committed.word, committed.previousWord, committed.sentenceStart)
        HushhTyping.taps.tapsFor(committed.word)?.let { e.learnTouches(committed.word, it) }
        scheduleSave(e)
    }

    /**
     * Next-word predictions only while the user is typing and has just finished a word, like
     * Gboard: an empty field, a freshly opened chat or a cursor moved by hand shows the toolbar.
     */
    private fun shouldPredict(before: CharSequence): Boolean {
        if (!prefs.suggestion.nextWordPrediction.get()) return false
        if (before.isBlank() || before.last() != ' ') return false
        return synchronized(detectorLock) { commitDetector.isTyping }
    }

    /** Predictions are cased the way the keys are: capitalised when the keyboard shifts itself. */
    private fun nextWordCase(subtype: Subtype): WordEngine.NextWordCase {
        if (keyboardManager.activeState.inputShiftState == InputShiftState.CAPS_LOCK) return WordEngine.NextWordCase.UPPER
        if (!prefs.correction.autoCapitalization.get() || !subtype.primaryLocale.supportsCapitalization) {
            return WordEngine.NextWordCase.NONE
        }
        return when (editorInstance.activeCursorCapsMode) {
            InputAttributes.CapsMode.ALL -> WordEngine.NextWordCase.UPPER
            InputAttributes.CapsMode.WORDS, InputAttributes.CapsMode.SENTENCES -> WordEngine.NextWordCase.CAPITALIZE
            else -> WordEngine.NextWordCase.NONE
        }
    }

    /**
     * The automatic correction for the word being finished now, computed synchronously (used when
     * the cached suggestions are behind the user's typing). Null if nothing should change.
     */
    suspend fun freshAutoCorrection(
        subtype: Subtype,
        content: EditorContent,
        allowPossiblyOffensive: Boolean,
    ): HushhWordCandidate? {
        if (!isSupported(subtype) || !autoCorrectAllowed()) return null
        val e = engine ?: return null
        val composing = content.composingText
        if (composing.isEmpty() || content.selection.isSelectionMode) return null
        val textBefore = content.textBeforeSelection
        val before = if (textBefore.endsWith(composing)) textBefore.substring(0, textBefore.length - composing.length) else textBefore
        val auto = e.suggest(composing, before, 8, allowPossiblyOffensive, HushhTyping.taps.tapsFor(composing)).autoCorrection
            ?: return null
        return HushhWordCandidate(auto.text, composing, 1.0, isEligibleForAutoCommit = true, sourceProvider = this)
    }

    /** The user pressed backspace right after an automatic correction of [original]. */
    fun onAutoCorrectionUndone(original: String) {
        val e = engine ?: return
        e.onAutoCorrectionUndone(original)
        scheduleSave(e)
    }

    /**
     * Autocorrect only where it helps: normal text fields, never for passwords, emails, web
     * addresses, names, filters, or fields that ask for no suggestions.
     */
    private fun autoCorrectAllowed(): Boolean {
        if (!prefs.correction.autoCorrect.get()) return false
        // Never touch the words of a HushhBoard "##send / ##pay" command while it is being typed.
        if (keyboardManager.activeState.isHushhCommandActive) return false
        return isAutoCorrectField(editorInstance.activeInfo)
    }

    override suspend fun notifySuggestionAccepted(subtype: Subtype, candidate: SuggestionCandidate) {
        val e = engine ?: return
        if (lastSessionPrivate) return
        e.onSuggestionAccepted(candidate.text.toString())
        // A tapped next-word prediction is never seen being typed, so learn it here.
        if (candidate is HushhWordCandidate && candidate.typed.isEmpty()) {
            e.learn(candidate.text.toString(), candidate.previousWord, candidate.sentenceStart)
        }
        scheduleSave(e)
    }

    override suspend fun notifySuggestionReverted(subtype: Subtype, candidate: SuggestionCandidate) {
        // Used by autocorrect (step 3): reverting will teach the engine to stop that correction.
    }

    override suspend fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate): Boolean {
        val e = engine ?: return false
        e.removeSuggestion(candidate.text.toString())
        scheduleSave(e)
        return true
    }

    override suspend fun getListOfWords(subtype: Subtype): List<String> {
        if (!isSupported(subtype)) return emptyList()
        glideWords?.let { return it }
        val e = ensureEngine() ?: return emptyList()
        return glideWords ?: e.glideWords().map { it.first }.also { glideWords = it }
    }

    override suspend fun getFrequencyForWord(subtype: Subtype, word: String): Double {
        return engine?.frequencyOf(word) ?: 0.0
    }

    /** Erases everything the keyboard has learned (for a "Clear learned words" setting). */
    fun clearLearnedData() {
        val e = engine
        e?.userModel?.clear()
        scope.launch { runCatching { userModelFile.delete() } }
        savedRevision = e?.userModel?.revision ?: -1L
    }

    private fun scheduleSave(e: WordEngine) {
        synchronized(this) {
            if (saveJob?.isActive == true) return
            saveJob = scope.launch {
                delay(SAVE_DEBOUNCE_MS)
                saveNow(e)
            }
        }
    }

    private fun saveNow(e: WordEngine) {
        val rev = e.userModel.revision
        if (rev == savedRevision) return
        try {
            e.userModel.saveTo(userModelFile)
            savedRevision = rev
        } catch (ex: Exception) {
            flogError { "Could not save HushhBoard user model: $ex" }
        }
    }

    override suspend fun destroy() {
        engine?.let { saveNow(it) }
    }
}

/** Field types where automatic corrections are welcome. */
internal fun isAutoCorrectField(info: FlorisEditorInfo): Boolean {
    if (info.isRawInputEditor) return false
    val attrs = info.inputAttributes
    if (attrs.type != InputAttributes.Type.TEXT) return false
    if (attrs.flagTextNoSuggestions) return false
    return when (attrs.variation) {
        InputAttributes.Variation.EMAIL_ADDRESS,
        InputAttributes.Variation.WEB_EMAIL_ADDRESS,
        InputAttributes.Variation.URI,
        InputAttributes.Variation.PASSWORD,
        InputAttributes.Variation.VISIBLE_PASSWORD,
        InputAttributes.Variation.WEB_PASSWORD,
        InputAttributes.Variation.FILTER,
        InputAttributes.Variation.PHONETIC,
        InputAttributes.Variation.PERSON_NAME,
        InputAttributes.Variation.POSTAL_ADDRESS -> false
        else -> true
    }
}
