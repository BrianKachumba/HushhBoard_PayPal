/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.nlp

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.ui.graphics.vector.ImageVector
import dev.hushhboard.nlp.entities.Entity
import dev.hushhboard.nlp.entities.EntityExtractor
import dev.hushhboard.nlp.entities.EntityKind
import dev.hushhboard.nlp.entities.Otp
import dev.patrickgold.florisboard.ime.nlp.hushh.HushhCodeCandidate
import dev.patrickgold.florisboard.ime.nlp.hushh.SmsCodeWatcher
import android.content.Context
import android.os.SystemClock
import android.util.LruCache
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.clipboardManager
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.editor.EditorRange
import dev.patrickgold.florisboard.ime.media.emoji.EmojiSuggestionProvider
import dev.patrickgold.florisboard.ime.nlp.han.HanShapeBasedLanguageProvider
import dev.patrickgold.florisboard.ime.nlp.hushh.HushhLanguageProvider
import dev.patrickgold.florisboard.ime.nlp.hushh.HushhWordCandidate
import dev.patrickgold.florisboard.ime.nlp.latin.LatinLanguageProvider
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.lib.util.NetworkUtils
import dev.patrickgold.florisboard.subtypeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.florisboard.lib.kotlin.guardedByLock
import org.florisboard.lib.kotlin.collectLatestIn
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.properties.Delegates

private const val BLANK_STR_PATTERN = "^\\s*$"

class NlpManager(context: Context) {
    private val blankStrRegex = Regex(BLANK_STR_PATTERN)

    private val prefs by FlorisPreferenceStore
    private val clipboardManager by context.clipboardManager()
    private val editorInstance by context.editorInstance()
    private val keyboardManager by context.keyboardManager()
    private val subtypeManager by context.subtypeManager()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val clipboardSuggestionProvider = ClipboardSuggestionProvider(context)
    // 🚀 HUSHHBOARD: verification codes from SMS that just arrived.
    val smsCodes = SmsCodeWatcher(context)
    private val emojiSuggestionProvider = EmojiSuggestionProvider(context)
    // HushhBoard word engine (lib/nlp). Kept as a direct reference so the typing path can reach it
    // without taking the providers lock on the UI thread.
    private val hushhLanguageProvider = HushhLanguageProvider(context)
    private val providers = guardedByLock {
        mapOf(
            // To swap engines, change only this line, e.g. back to
            // ProviderInstanceWrapper(LatinLanguageProvider(context)).
            LatinLanguageProvider.ProviderId to ProviderInstanceWrapper(hushhLanguageProvider),
            HanShapeBasedLanguageProvider.ProviderId to ProviderInstanceWrapper(HanShapeBasedLanguageProvider(context)),
        )
    }
    // lock unnecessary because values constant
    private val providersForceSuggestionOn = mutableMapOf<String, Boolean>()

    private val internalSuggestionsGuard = Mutex()
    private var internalSuggestions by Delegates.observable(SystemClock.uptimeMillis() to listOf<SuggestionCandidate>()) { _, _, _ ->
        scope.launch { assembleCandidates() }
    }

    private val _activeCandidatesFlow = MutableStateFlow(listOf<SuggestionCandidate>())
    val activeCandidatesFlow = _activeCandidatesFlow.asStateFlow()
    inline var activeCandidates
        get() = activeCandidatesFlow.value
        private set(v) {
            _activeCandidatesFlow.value = v
        }

    val debugOverlaySuggestionsInfos = LruCache<Long, Pair<String, SpellingResult>>(10)
    var debugOverlayVersion = MutableStateFlow(0)

    init {
        clipboardManager.primaryClipFlow.collectLatestIn(scope) {
            assembleCandidates()
        }
        prefs.suggestion.enabled.asFlow().collectLatestIn(scope) {
            assembleCandidates()
        }
        prefs.clipboard.suggestionEnabled.asFlow().collectLatestIn(scope) {
            assembleCandidates()
        }
        prefs.emoji.suggestionEnabled.asFlow().collectLatestIn(scope) {
            assembleCandidates()
        }
        smsCodes.code.collectLatestIn(scope) {
            assembleCandidates()
        }
        subtypeManager.activeSubtypeFlow.collectLatestIn(scope) { subtype ->
            preload(subtype)
        }
    }

    /**
     * Gets the punctuation rule from the currently active subtype and returns it. Falls back to a default one if the
     * subtype does not exist or defines an invalid punctuation rule.
     *
     * @return The punctuation rule or a fallback.
     */
    fun getActivePunctuationRule(): PunctuationRule {
        return getPunctuationRule(subtypeManager.activeSubtype)
    }

    /**
     * Gets the punctuation rule from the given subtype and returns it. Falls back to a default one if the subtype does
     * not exist or defines an invalid punctuation rule.
     *
     * @return The punctuation rule or a fallback.
     */
    fun getPunctuationRule(subtype: Subtype): PunctuationRule {
        return keyboardManager.resources.punctuationRules.value[subtype.punctuationRule] ?: PunctuationRule.Fallback
    }

    private suspend fun getSpellingProvider(subtype: Subtype): SpellingProvider {
        return providers.withLock { it[subtype.nlpProviders.spelling] }?.provider as? SpellingProvider
            ?: FallbackNlpProvider
    }

    private suspend fun getSuggestionProvider(subtype: Subtype): SuggestionProvider {
        return providers.withLock { it[subtype.nlpProviders.suggestion] }?.provider as? SuggestionProvider
            ?: FallbackNlpProvider
    }

    fun preload(subtype: Subtype) {
        scope.launch {
            emojiSuggestionProvider.preload(subtype)
            providers.withLock { providers ->
                subtype.nlpProviders.forEach { _, providerId ->
                    providers[providerId]?.let { provider ->
                        provider.createIfNecessary()
                        provider.preload(subtype)
                    }
                }
            }
        }
    }

    /**
     * Spell wrapper helper which calls the spelling provider and returns the result. Coroutine management must be done
     * by the source spell checker service.
     */
    suspend fun spell(
        subtype: Subtype,
        word: String,
        precedingWords: List<String>,
        followingWords: List<String>,
        maxSuggestionCount: Int,
    ): SpellingResult {
        return getSpellingProvider(subtype).spell(
            subtype = subtype,
            word = word,
            precedingWords = precedingWords,
            followingWords = followingWords,
            maxSuggestionCount = maxSuggestionCount,
            allowPossiblyOffensive = !prefs.suggestion.blockPossiblyOffensive.get(),
            isPrivateSession = keyboardManager.activeState.isIncognitoMode,
        )
    }

    suspend fun determineLocalComposing(
        textBeforeSelection: CharSequence, breakIterators: BreakIteratorGroup, localLastCommitPosition: Int
    ): EditorRange {
        return getSuggestionProvider(subtypeManager.activeSubtype).determineLocalComposing(
            subtypeManager.activeSubtype, textBeforeSelection, breakIterators, localLastCommitPosition
        )
    }

    fun providerForcesSuggestionOn(subtype: Subtype): Boolean {
        // Using a cache because I have no idea how fast the runBlocking is
        return providersForceSuggestionOn.getOrPut(subtype.nlpProviders.suggestion) {
            runBlocking {
                getSuggestionProvider(subtype).forcesSuggestionOn
            }
        }
    }

    fun isSuggestionOn(): Boolean =
        prefs.suggestion.enabled.get()
            || prefs.emoji.suggestionEnabled.get()
            || providerForcesSuggestionOn(subtypeManager.activeSubtype)

    @Volatile
    private var suggestJob: Job? = null

    fun suggest(subtype: Subtype, content: EditorContent) {
        val reqTime = SystemClock.uptimeMillis()
        // Only the newest request matters: a result for text the user has already typed past is
        // thrown away anyway, so don't spend CPU on it while they type.
        // Learning and typing state must see every change in order, so it runs here, not in the
        // (cancellable) job below.
        hushhProvider()?.observe(subtype, content, keyboardManager.activeState.isIncognitoMode)
        suggestJob?.cancel()
        suggestJob = scope.launch {
            val emojiSuggestions = when {
                prefs.emoji.suggestionEnabled.get() -> {
                    emojiSuggestionProvider.suggest(
                        subtype = subtype,
                        content = content,
                        maxCandidateCount = prefs.emoji.suggestionCandidateMaxCount.get(),
                        allowPossiblyOffensive = !prefs.suggestion.blockPossiblyOffensive.get(),
                        isPrivateSession = keyboardManager.activeState.isIncognitoMode,
                    )
                }
                else -> emptyList()
            }
            val suggestions = when {
                emojiSuggestions.isNotEmpty() && prefs.emoji.suggestionType.get().prefix.isNotEmpty() -> {
                    emptyList()
                }
                else -> {
                    getSuggestionProvider(subtype).suggest(
                        subtype = subtype,
                        content = content,
                        maxCandidateCount = 8,
                        allowPossiblyOffensive = !prefs.suggestion.blockPossiblyOffensive.get(),
                        isPrivateSession = keyboardManager.activeState.isIncognitoMode,
                    )
                }
            }
            ensureActive()
            internalSuggestionsGuard.withLock {
                if (internalSuggestions.first < reqTime) {
                    internalSuggestions = reqTime to buildList {
                        addAll(emojiSuggestions)
                        addAll(suggestions)
                    }
                }
            }
        }
    }

    fun suggestDirectly(suggestions: List<SuggestionCandidate>) {
        val reqTime = SystemClock.uptimeMillis()
        runBlocking {
            internalSuggestions = reqTime to suggestions
        }
    }

    fun clearSuggestions() {
        val reqTime = SystemClock.uptimeMillis()
        runBlocking {
            internalSuggestions = reqTime to emptyList()
        }
    }

    fun getAutoCommitCandidate(): SuggestionCandidate? {
        return activeCandidates.firstOrNull { it.isEligibleForAutoCommit }
    }

    /**
     * 🚀 HUSHHBOARD: the automatic correction for the word being finished right now.
     *
     * Suggestions are computed in the background, so the cached list can be one keystroke behind
     * when the user types fast. A correction is only ever taken from the cache if it was computed
     * for exactly the current word; otherwise it is computed synchronously (1-5 ms).
     */
    fun getAutoCommitCandidate(content: EditorContent): SuggestionCandidate? {
        val composing = content.composingText
        val hushh = hushhProvider()
        if (hushh == null || composing.isEmpty()) return getAutoCommitCandidate()
        val snapshot = activeCandidates
        val ours = snapshot.firstOrNull { it is HushhWordCandidate } as HushhWordCandidate?
        if (ours != null && ours.typed == composing) {
            return snapshot.firstOrNull { it is HushhWordCandidate && it.isEligibleForAutoCommit }
        }
        return runBlocking {
            hushh.freshAutoCorrection(
                subtype = subtypeManager.activeSubtype,
                content = content,
                allowPossiblyOffensive = !prefs.suggestion.blockPossiblyOffensive.get(),
            )
        }
    }

    /** 🚀 HUSHHBOARD: the user undid an automatic correction of [original] with backspace. */
    fun onAutoCorrectionUndone(original: String) {
        hushhProvider()?.onAutoCorrectionUndone(original)
    }

    private fun hushhProvider(): HushhLanguageProvider? =
        hushhLanguageProvider.takeIf { subtypeManager.activeSubtype.nlpProviders.suggestion == LatinLanguageProvider.ProviderId }

    fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate): Boolean {
        if (candidate is HushhCodeCandidate) {
            if (candidate.source == HushhCodeCandidate.Source.SMS) smsCodes.consume(null)
            else runBlocking { clipboardSuggestionProvider.removeSuggestion(subtype, candidate) }
            scope.launch { assembleCandidates() }
            return true
        }
        return runBlocking { candidate.sourceProvider?.removeSuggestion(subtype, candidate) == true }.also { result ->
            if (result) {
                scope.launch {
                    // Need to re-trigger the suggestions algorithm
                    if (candidate is ClipboardSuggestionCandidate) {
                        assembleCandidates()
                    } else {
                        suggest(subtypeManager.activeSubtype, editorInstance.activeContent)
                    }
                }
            }
        }
    }

    /** Erases everything the word engine has learned on this device. */
    fun clearLearnedData() {
        runBlocking {
            providers.withLock { map -> map.values.forEach { (it.provider as? HushhLanguageProvider)?.clearLearnedData() } }
        }
        scope.launch { assembleCandidates() }
    }

    fun getListOfWords(subtype: Subtype): List<String> {
        return runBlocking { getSuggestionProvider(subtype).getListOfWords(subtype) }
    }

    fun getFrequencyForWord(subtype: Subtype, word: String): Double {
        return runBlocking { getSuggestionProvider(subtype).getFrequencyForWord(subtype, word) }
    }

    private fun assembleCandidates() {
        runBlocking {
            val candidates = when {
                isSuggestionOn() -> {
                    clipboardSuggestionProvider.suggest(
                        subtype = Subtype.DEFAULT,
                        content = editorInstance.activeContent,
                        maxCandidateCount = 8,
                        allowPossiblyOffensive = !prefs.suggestion.blockPossiblyOffensive.get(),
                        isPrivateSession = keyboardManager.activeState.isIncognitoMode,
                    ).ifEmpty {
                        buildList {
                            internalSuggestionsGuard.withLock {
                                addAll(internalSuggestions.second)
                            }
                        }
                    }
                }
                else -> emptyList()
            }
            val withCode = smsCodeCandidate()?.let { code ->
                listOf(code) + candidates.filterNot { it is HushhCodeCandidate && it.code == code.code }
            } ?: candidates
            activeCandidates = withCode
            autoExpandCollapseSmartbarActions(withCode, NlpInlineAutofill.suggestions.value)
        }
    }

    /** A fresh SMS code, offered while the field is empty or is asking for a code. */
    private fun smsCodeCandidate(): HushhCodeCandidate? {
        if (!prefs.hushh.smsCodes.get()) return null
        val c = smsCodes.fresh() ?: return null
        val content = editorInstance.activeContent
        val fieldText = content.text
        if (fieldText.contains(c.code)) return null
        val wanted = fieldText.isBlank() || (SmsCodeWatcher.looksLikeCodeField(editorInstance.activeInfo) && fieldText.length < 12)
        return if (wanted) HushhCodeCandidate(c.code, c.sender, HushhCodeCandidate.Source.SMS) else null
    }

    /** 🚀 HUSHHBOARD: a code chip was used: never offer it again, and wipe a copied code if wanted. */
    fun onCodeUsed(candidate: HushhCodeCandidate) {
        when (candidate.source) {
            HushhCodeCandidate.Source.SMS -> smsCodes.consume(null)
            HushhCodeCandidate.Source.CLIPBOARD -> {
                val item = candidate.clipboardItem ?: return
                clipboardSuggestionProvider.markUsed(item)
                if (prefs.hushh.clearCopiedCodes.get()) {
                    clipboardManager.updatePrimaryClip(null)
                    clipboardManager.deleteClip(item, onlyIfUnpinned = true)
                }
            }
        }
        scope.launch { assembleCandidates() }
    }

    fun autoExpandCollapseSmartbarActions(list1: List<*>?, list2: List<*>?) {
        if (!prefs.smartbar.enabled.get()) {// || !prefs.smartbar.sharedActionsAutoExpandCollapse.get()) {
            return
        }
        // TODO: this is a mess and needs to be cleaned up in v0.5 with the NLP development
        /*if (keyboardManager.inputEventDispatcher.isRepeatableCodeLastDown()
            && !keyboardManager.inputEventDispatcher.isPressed(KeyCode.DELETE)
            && !keyboardManager.inputEventDispatcher.isPressed(KeyCode.FORWARD_DELETE)
            || keyboardManager.activeState.isActionsOverflowVisible
        ) {
            return // We do not auto switch if a repeatable action key was last pressed or if the actions overflow
                   // menu is visible to prevent annoying UI changes
        }*/
        val isSelection = editorInstance.activeContent.selection.isSelectionMode
        val isExpanded = list1.isNullOrEmpty() && list2.isNullOrEmpty() || isSelection
        // Called after every keystroke: only touch the preferences (and recompose the Smartbar)
        // when the toolbar actually has to switch.
        if (prefs.smartbar.sharedActionsExpanded.get() == isExpanded) return
        scope.launch {
            prefs.smartbar.sharedActionsExpandWithAnimation.set(false)
            prefs.smartbar.sharedActionsExpanded.set(isExpanded)
        }
    }

    fun addToDebugOverlay(word: String, info: SpellingResult) {
        debugOverlaySuggestionsInfos.put(System.currentTimeMillis(), word to info)
        debugOverlayVersion.update { it + 1 }
    }

    fun clearDebugOverlay() {
        debugOverlaySuggestionsInfos.evictAll()
        debugOverlayVersion.update { it + 1 }
    }

    private class ProviderInstanceWrapper(val provider: NlpProvider) {
        private var isInstanceAlive = AtomicBoolean(false)

        suspend fun createIfNecessary() {
            if (!isInstanceAlive.getAndSet(true)) provider.create()
        }

        suspend fun preload(subtype: Subtype) {
            provider.preload(subtype)
        }

        suspend fun destroyIfNecessary() {
            if (isInstanceAlive.getAndSet(true)) provider.destroy()
        }
    }

    inner class ClipboardSuggestionProvider internal constructor(private val context: Context) : SuggestionProvider {
        private var lastClipboardItemId: Long = -1

        override val providerId = "org.florisboard.nlp.providers.clipboard"

        override suspend fun create() {
            // Do nothing
        }

        override suspend fun preload(subtype: Subtype) {
            // Do nothing
        }

        override suspend fun suggest(
            subtype: Subtype,
            content: EditorContent,
            maxCandidateCount: Int,
            allowPossiblyOffensive: Boolean,
            isPrivateSession: Boolean,
        ): List<SuggestionCandidate> {
            // Check if enabled
            if (!prefs.clipboard.suggestionEnabled.get()) return emptyList()

            val currentItem = validateClipboardItem(clipboardManager.primaryClip, lastClipboardItemId, content.text)
                ?: return emptyList()

            return buildList {
                val now = System.currentTimeMillis()
                if ((now - currentItem.creationTimestampMs) < prefs.clipboard.suggestionTimeout.get() * 1000) {
                    val text = if (currentItem.type == ItemType.TEXT) currentItem.stringRepresentation() else null
                    val smart = prefs.hushh.clipboardEntities.get()
                    // 🚀 HUSHHBOARD: a copied verification code gets its own highlighted chip.
                    if (text != null && smart && text.length <= 400) {
                        val otp = Otp.findAll(text).firstOrNull()
                        if (otp != null) {
                            add(HushhCodeCandidate(otp.value, null, HushhCodeCandidate.Source.CLIPBOARD, currentItem))
                            if (Otp.isBareCode(text)) return@buildList
                        }
                    }
                    add(ClipboardSuggestionCandidate(currentItem, sourceProvider = this@ClipboardSuggestionProvider, context = context))
                    if (currentItem.isSensitive || text == null) {
                        return@buildList
                    }
                    if (smart) {
                        // 🚀 HUSHHBOARD: numbers, paybills, links, names and places as separate chips.
                        val whole = text.trim()
                        val entities = EntityExtractor.rank(EntityExtractor.extract(text), 5)
                            .filter { it.kind != EntityKind.OTP && it.value != whole }
                        for (e in entities) {
                            add(ClipboardSuggestionCandidate(
                                clipboardItem = currentItem.copy(text = e.value),
                                sourceProvider = this@ClipboardSuggestionProvider,
                                context = context,
                                label = entityLabel(e),
                                entityIcon = entityIcon(e.kind),
                            ))
                        }
                        return@buildList
                    }
                    run {
                        val matches = buildList {
                            addAll(NetworkUtils.getEmailAddresses(text))
                            addAll(NetworkUtils.getUrls(text))
                            addAll(NetworkUtils.getPhoneNumbers(text))
                        }
                        matches.forEachIndexed { i, match ->
                            val isUniqueMatch = matches.subList(0, i).all { prevMatch ->
                                prevMatch.value != match.value && prevMatch.range.intersect(match.range).isEmpty()
                            }
                            if (match.value != text && isUniqueMatch) {
                                add(ClipboardSuggestionCandidate(
                                    clipboardItem = currentItem.copy(
                                        // TODO: adjust regex of phone number so we don't need to manually strip the
                                        //  parentheses from the match results
                                        text = if (match.value.startsWith("(") && match.value.endsWith(")")) {
                                            match.value.substring(1, match.value.length - 1)
                                        } else {
                                            match.value
                                        }
                                    ),
                                    sourceProvider = this@ClipboardSuggestionProvider,
                                    context = context,
                                ))
                            }
                        }
                    }
                }
            }
        }

        private fun entityLabel(e: Entity): String = when (e.kind) {
            EntityKind.PAYBILL -> "Paybill" + (e.extra["account"]?.let { " · Acc $it" } ?: "")
            EntityKind.PHONE -> "Phone" + (e.detail?.let { " · $it" } ?: "")
            EntityKind.URL -> "Link" + (e.detail?.let { " · $it" } ?: "")
            EntityKind.AMOUNT -> e.detail ?: "Amount"
            else -> e.kind.label
        }

        private fun entityIcon(kind: EntityKind): ImageVector = when (kind) {
            EntityKind.PHONE -> Icons.Rounded.Phone
            EntityKind.EMAIL -> Icons.Rounded.Email
            EntityKind.URL -> Icons.Rounded.Link
            EntityKind.PAYBILL, EntityKind.TILL, EntityKind.AMOUNT -> Icons.Rounded.Payments
            EntityKind.MPESA_CODE -> Icons.Rounded.ReceiptLong
            EntityKind.PERSON -> Icons.Rounded.Person
            EntityKind.BUSINESS -> Icons.Rounded.Storefront
            EntityKind.PLACE -> Icons.Rounded.Place
            EntityKind.KRA_PIN -> Icons.Rounded.Badge
            EntityKind.OTP -> Icons.Rounded.Password
        }

        override suspend fun notifySuggestionAccepted(subtype: Subtype, candidate: SuggestionCandidate) {
            if (candidate is ClipboardSuggestionCandidate) {
                lastClipboardItemId = candidate.clipboardItem.id
            }
        }

        override suspend fun notifySuggestionReverted(subtype: Subtype, candidate: SuggestionCandidate) {
            // Do nothing
        }

        override suspend fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate): Boolean {
            if (candidate is ClipboardSuggestionCandidate) {
                lastClipboardItemId = candidate.clipboardItem.id
                return true
            }
            if (candidate is HushhCodeCandidate && candidate.clipboardItem != null) {
                lastClipboardItemId = candidate.clipboardItem.id
                return true
            }
            return false
        }

        /** The clip a pasted code came from is done: don't offer it again. */
        fun markUsed(item: ClipboardItem) {
            lastClipboardItemId = item.id
        }

        override suspend fun getListOfWords(subtype: Subtype): List<String> {
            return emptyList()
        }

        override suspend fun getFrequencyForWord(subtype: Subtype, word: String): Double {
            return 0.0
        }

        override suspend fun destroy() {
            // Do nothing
        }

        private fun validateClipboardItem(currentItem: ClipboardItem?, lastItemId: Long, contentText: String) =
            currentItem?.takeIf {
                // Check if already used
                it.id != lastItemId
                    // Check if content is empty
                    && contentText.isBlank()
                    // Check if clipboard content has any valid characters
                    && !currentItem.text.isNullOrBlank()
                    && !blankStrRegex.matches(currentItem.text)
            }
    }
}
