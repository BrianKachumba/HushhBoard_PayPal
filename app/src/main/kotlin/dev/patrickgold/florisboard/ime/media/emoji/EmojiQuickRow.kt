/*
 * Copyright (C) 2026 HushhBoard
 * Licensed under the Apache License, Version 2.0.
 */

package dev.patrickgold.florisboard.ime.media.emoji

import android.content.Context
import android.graphics.Paint
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.view.HapticFeedbackConstants
import dev.patrickgold.florisboard.app.FlorisPreferenceModel
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.ime.editor.FlorisEditorInfo
import dev.patrickgold.florisboard.ime.editor.InputAttributes
import dev.patrickgold.florisboard.ime.keyboard.KeyboardMode
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.lib.FlorisLocale
import dev.patrickgold.florisboard.subtypeManager
import dev.patrickgold.jetpref.datastore.model.collectAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Gboard-style quick emoji row above the keys in chat apps: emojis that match the word being
 * typed first, then the user's pinned and recent ones, then everyday favourites.
 *
 * Cheap by design: one map lookup per keystroke; the keyword index is built once in the background.
 */
object EmojiQuickRow {
    const val SLOTS = 8

    /** Chat apps where an emoji row is expected (Gboard shows its row in apps like these). */
    private val CHAT_APPS = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.telegram.messenger.web",
        "org.thunderdog.challegram", "com.google.android.apps.messaging", "com.samsung.android.messaging",
        "com.android.mms", "com.instagram.android", "com.instagram.barcelona", "com.facebook.orca",
        "com.facebook.mlite", "com.facebook.katana", "com.snapchat.android", "com.twitter.android",
        "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.discord", "com.viber.voip",
        "org.thoughtcrime.securesms", "com.google.android.apps.dynamite", "com.microsoft.teams",
        "com.Slack", "jp.naver.line.android", "com.imo.android.imoim", "com.truecaller", "com.linkedin.android",
        "com.reddit.frontpage", "com.tinder", "com.bumble.app", "com.skype.raider", "com.kakao.talk",
        "com.tencent.mm", "com.google.android.gm", "com.microsoft.office.outlook",
    )

    private val DEFAULTS = listOf("😂", "❤️", "🙏", "👍", "😭", "🔥", "😍", "🥰", "😊", "🎉", "💯", "🤣", "😅", "🙌", "👏", "💪")

    /** Swahili and Sheng words people type in chats. */
    private val LOCAL = mapOf(
        "asante" to listOf("🙏", "😊", "❤️"), "shukran" to listOf("🙏", "😊"), "ahsante" to listOf("🙏"),
        "pole" to listOf("😔", "🤗", "🙏"), "sawa" to listOf("👍", "👌", "✅"), "poa" to listOf("😎", "👌", "🔥"),
        "fiti" to listOf("💪", "😎"), "nakupenda" to listOf("❤️", "😍", "🥰"), "upendo" to listOf("❤️", "💕"),
        "mpenzi" to listOf("❤️", "😘"), "haha" to listOf("😂", "🤣", "😆"), "hehe" to listOf("😅", "😁"),
        "lol" to listOf("😂", "🤣"), "chakula" to listOf("🍲", "🍛", "😋"), "pesa" to listOf("💰", "💵", "🤑"),
        "doh" to listOf("💰", "💸"), "mpesa" to listOf("📲", "💸"), "pombe" to listOf("🍺", "🍻"),
        "sherehe" to listOf("🎉", "🥳", "💃"), "birthday" to listOf("🎂", "🥳", "🎉"), "hongera" to listOf("🎉", "👏", "🥳"),
        "kazi" to listOf("💼", "💪"), "lala" to listOf("😴", "🌙", "🛌"), "usiku" to listOf("🌙", "😴"),
        "asubuhi" to listOf("🌅", "☀️", "☕"), "mvua" to listOf("🌧️", "☔"), "jua" to listOf("☀️", "😎"),
        "mungu" to listOf("🙏", "✝️"), "amen" to listOf("🙏", "✨"), "sasa" to listOf("👋", "😎"),
        "niaje" to listOf("👋", "😎"), "mambo" to listOf("👋", "😄"), "habari" to listOf("👋", "😊"),
        "safari" to listOf("✈️", "🚗", "🧳"), "nyumbani" to listOf("🏠", "🏡"), "shule" to listOf("🏫", "📚"),
        "mpira" to listOf("⚽", "🏟️"), "maji" to listOf("💧", "🚰"), "chai" to listOf("☕", "🫖"),
        "kahawa" to listOf("☕"), "nyama" to listOf("🥩", "🍖"), "ugali" to listOf("🍽️", "😋"),
        "simu" to listOf("📱", "📞"), "gari" to listOf("🚗", "🚙"), "mat" to listOf("🚐"), "matatu" to listOf("🚐"),
        "boda" to listOf("🏍️"), "sorry" to listOf("🙏", "😔", "🥺"), "love" to listOf("❤️", "😍", "🥰"),
        "congrats" to listOf("🎉", "👏", "🥳"), "thanks" to listOf("🙏", "😊", "❤️"), "ok" to listOf("👍", "👌"),
        "okay" to listOf("👍", "👌"), "yes" to listOf("👍", "✅"), "no" to listOf("🙅", "❌"), "wow" to listOf("😮", "🤩", "🔥"),
        "sad" to listOf("😢", "😭", "💔"), "happy" to listOf("😊", "😁", "🥳"), "lit" to listOf("🔥", "💯"),
        "sleep" to listOf("😴", "🛌"), "hungry" to listOf("😋", "🍔"), "food" to listOf("🍔", "🍕", "😋"),
        "money" to listOf("💰", "💵", "🤑"), "party" to listOf("🎉", "🥳", "💃"), "kiss" to listOf("😘", "💋"),
        "miss" to listOf("🥺", "💕"), "goodnight" to listOf("🌙", "😴", "💤"), "morning" to listOf("🌅", "☀️", "☕"),
    )

    private val index = ConcurrentHashMap<String, List<String>>()
    @Volatile private var indexedLocale: String? = null
    private val glyphCache = ConcurrentHashMap<String, Boolean>()
    private val paint = Paint()

    /** True when the row belongs in this field. */
    fun isWanted(prefs: FlorisPreferenceModel, info: FlorisEditorInfo, mode: KeyboardMode): Boolean {
        if (!prefs.hushh.emojiRow.get() || mode != KeyboardMode.CHARACTERS || info.isRawInputEditor) return false
        val attrs = info.inputAttributes
        if (attrs.type != InputAttributes.Type.TEXT) return false
        when (attrs.variation) {
            InputAttributes.Variation.PASSWORD, InputAttributes.Variation.VISIBLE_PASSWORD, InputAttributes.Variation.WEB_PASSWORD,
            InputAttributes.Variation.EMAIL_ADDRESS, InputAttributes.Variation.WEB_EMAIL_ADDRESS, InputAttributes.Variation.URI,
            InputAttributes.Variation.FILTER, InputAttributes.Variation.PERSON_NAME, InputAttributes.Variation.POSTAL_ADDRESS,
            InputAttributes.Variation.PHONETIC -> return false
            InputAttributes.Variation.SHORT_MESSAGE, InputAttributes.Variation.LONG_MESSAGE -> return true
            else -> Unit
        }
        return info.packageName in CHAT_APPS
    }

    /** Builds the word -> emoji index for [locale] once, off the main thread. */
    suspend fun ensureIndex(context: Context, locale: FlorisLocale, skinTone: EmojiSkinTone) {
        val key = locale.languageTag() + "/" + skinTone.name
        if (indexedLocale == key) return
        withContext(Dispatchers.Default) {
            val emojis = EmojiData.get(context, locale).bySkinTone[skinTone].orEmpty()
            val built = HashMap<String, MutableList<String>>()
            for (e in emojis) {
                val words = (e.keywords + e.name.split(' ', '-', ':', '_')).map { it.lowercase().trim() }.filter { it.length >= 2 }
                for (w in words.distinct()) {
                    val list = built.getOrPut(w) { ArrayList(4) }
                    if (list.size < 4) list.add(e.value)
                }
            }
            index.clear()
            for ((k, v) in built) index[k] = v
            indexedLocale = key
        }
    }

    /** The emojis for the row given the word under the cursor and the user's history. */
    fun pick(word: String, history: EmojiHistory): List<String> {
        val out = LinkedHashSet<String>()
        val w = word.lowercase().trim().trimEnd('!', '?', '.', ',')
        if (w.length >= 2) {
            LOCAL[w]?.let { out.addAll(it) }
            index[w]?.let { out.addAll(it.take(3)) }
            if (w.endsWith("s")) index[w.dropLast(1)]?.let { out.addAll(it.take(2)) }
        }
        history.pinned.forEach { out.add(it.value) }
        history.recent.forEach { out.add(it.value) }
        out.addAll(DEFAULTS)
        return out.filter { canRender(it) }.take(SLOTS)
    }

    private fun canRender(emoji: String): Boolean = glyphCache.getOrPut(emoji) {
        try { paint.hasGlyph(emoji) } catch (_: Exception) { true }
    }

    /** The word being typed, or the last word before the cursor. */
    fun currentWord(textBefore: String, composing: String): String {
        if (composing.isNotEmpty()) return composing
        val trimmed = textBefore.trimEnd()
        if (trimmed.length != textBefore.length && textBefore.length - trimmed.length > 1) return ""
        return trimmed.takeLastWhile { it.isLetter() || it == '\'' }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EmojiQuickRowView() {
    val context = LocalContext.current
    val view = LocalView.current
    val prefs by FlorisPreferenceStore
    val keyboardManager by context.keyboardManager()
    val editorInstance by context.editorInstance()
    val subtypeManager by context.subtypeManager()
    val scope = rememberCoroutineScope()

    val content by editorInstance.activeContentFlow.collectAsState()
    val history by prefs.emoji.historyData.collectAsState()
    val skinTone by prefs.emoji.preferredSkinTone.collectAsState()
    val subtype by subtypeManager.activeSubtypeFlow.collectAsState()
    var indexVersion by remember { mutableStateOf(0) }

    LaunchedEffect(subtype.primaryLocale, skinTone) {
        EmojiQuickRow.ensureIndex(context, subtype.primaryLocale, skinTone)
        indexVersion++
    }

    val word = EmojiQuickRow.currentWord(content.textBeforeSelection, content.composingText)
    val emojis = remember(word, history, indexVersion) { EmojiQuickRow.pick(word, history) }
    var confirmHide by remember { mutableStateOf(false) }

    if (confirmHide) {
        // Long-press asks first, so a slow tap never makes the row disappear.
        Row(
            Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Hide the emoji row?", fontSize = 14.sp, color = Color.Gray, modifier = Modifier.weight(1f).padding(start = 8.dp))
            Box(
                Modifier.padding(horizontal = 4.dp).clip(RoundedCornerShape(16.dp)).background(Color.Gray.copy(alpha = 0.18f))
                    .clickable {
                        confirmHide = false
                        scope.launch { prefs.hushh.emojiRow.set(false) }
                        Toast.makeText(context, "Emoji row hidden. Turn it back on in Settings › Typing.", Toast.LENGTH_LONG).show()
                    }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) { Text("Hide", fontSize = 14.sp, color = Color.Gray) }
            Box(
                Modifier.padding(horizontal = 4.dp).clip(RoundedCornerShape(16.dp)).clickable { confirmHide = false }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) { Text("Keep", fontSize = 14.sp, color = Color.Gray) }
        }
        return
    }

    Row(
        Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (e in emojis) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = 2.dp, vertical = 3.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .combinedClickable(
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            val emoji = Emoji(e, "", emptyList())
                            keyboardManager.inputEventDispatcher.sendDownUp(emoji)
                            scope.launch { EmojiHistoryHelper.markEmojiUsed(prefs, emoji) }
                        },
                        onLongClick = { confirmHide = true },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(e, fontSize = 22.sp)
            }
        }
        // The full emoji keyboard.
        Box(
            Modifier.weight(1f).fillMaxHeight().padding(horizontal = 2.dp, vertical = 3.dp).clip(RoundedCornerShape(10.dp))
                .background(Color.Gray.copy(alpha = 0.12f))
                .clickable { keyboardManager.inputEventDispatcher.sendDownUp(TextKeyData.IME_UI_MODE_MEDIA) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.EmojiEmotions, "All emojis", tint = Color.Gray, modifier = Modifier.size(22.dp))
        }
    }
}
