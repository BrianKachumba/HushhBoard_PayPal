/*
 * Copyright (C) 2026 HushhBoard
 * Licensed under the Apache License, Version 2.0.
 */

package dev.patrickgold.florisboard.ime.nlp.hushh

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Telephony
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import dev.hushhboard.nlp.entities.Otp
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import dev.patrickgold.florisboard.ime.editor.FlorisEditorInfo
import dev.patrickgold.florisboard.ime.editor.InputAttributes
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A verification code offered in the Smartbar, from an SMS that just arrived or from the clipboard.
 * Tapping it types the code into the focused field; nothing is ever submitted.
 */
data class HushhCodeCandidate(
    val code: String,
    val from: String?,
    val source: Source,
    /** For clipboard codes: the clip, so it can be cleared after pasting. */
    val clipboardItem: ClipboardItem? = null,
) : SuggestionCandidate {
    enum class Source { SMS, CLIPBOARD }

    override val text: CharSequence = code
    override val secondaryText: CharSequence = when (source) {
        Source.SMS -> from?.let { "Code from $it" } ?: "Code from SMS"
        Source.CLIPBOARD -> "Copied code"
    }
    override val confidence: Double = 1.0
    override val isEligibleForAutoCommit: Boolean = false
    override val isEligibleForUserRemoval: Boolean = true
    override val icon: ImageVector = if (source == Source.SMS) Icons.Rounded.Sms else Icons.Rounded.Password
    override val sourceProvider: SuggestionProvider? = null
}

/**
 * Finds verification codes in SMS that arrived in the last few minutes, without any background
 * receiver: the inbox is read only when a text field opens (and watched while the keyboard is up).
 * Uses the SMS permission the app already holds for M-Pesa tracking; can be turned off in Settings.
 */
class SmsCodeWatcher(private val context: Context) {
    data class Code(val code: String, val sender: String?, val receivedAt: Long, val messageId: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _code = MutableStateFlow<Code?>(null)
    /** The freshest unused code, or null. */
    val code: StateFlow<Code?> = _code.asStateFlow()

    private val dismissed = HashSet<Long>()
    private var observer: ContentObserver? = null
    @Volatile private var enabled = false

    fun onInputStarted(isEnabled: Boolean) {
        enabled = isEnabled && hasPermission()
        if (!enabled) {
            _code.value = null
            stopWatching()
            return
        }
        refresh()
        startWatching()
    }

    fun onInputFinished() = stopWatching()

    /** The code was used or dismissed: never offer it again. */
    fun consume(code: Code?) {
        val c = code ?: _code.value ?: return
        synchronized(dismissed) { dismissed += c.messageId }
        _code.value = null
    }

    /** The current code if it is still fresh. */
    fun fresh(): Code? {
        val c = _code.value ?: return null
        return if (System.currentTimeMillis() - c.receivedAt <= SHOW_FOR_MS) c else null
    }

    private fun hasPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    private fun refresh() {
        scope.launch {
            _code.value = try { query() } catch (_: Exception) { null }
        }
    }

    private fun query(): Code? {
        val since = System.currentTimeMillis() - SHOW_FOR_MS
        val projection = arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI, projection, "${Telephony.Sms.DATE} > ?", arrayOf(since.toString()),
            "${Telephony.Sms.DATE} DESC",
        )?.use { c ->
            var rows = 0
            while (c.moveToNext() && rows < 8) {
                rows++
                val id = c.getLong(0)
                if (synchronized(dismissed) { id in dismissed }) continue
                val sender = c.getString(1)
                if (sender.equals("MPESA", ignoreCase = true)) continue
                val body = c.getString(2) ?: continue
                val code = Otp.fromMessage(body) ?: continue
                return Code(code, prettySender(sender), c.getLong(3), id)
            }
        }
        return null
    }

    private fun prettySender(s: String?): String? {
        if (s.isNullOrBlank()) return null
        // Short codes and long numbers say nothing to the user; brand senders ("Safaricom") do.
        return if (s.any { it.isLetter() }) s else null
    }

    private fun startWatching() {
        if (observer != null) return
        val o = object : ContentObserver(Handler(Looper.getMainLooper())) {
            private var last = 0L
            override fun onChange(selfChange: Boolean) {
                val now = SystemClock.elapsedRealtime()
                if (now - last < 500) return
                last = now
                if (enabled) refresh()
            }
        }
        try {
            context.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, o)
            observer = o
        } catch (_: Exception) {
        }
    }

    private fun stopWatching() {
        observer?.let { try { context.contentResolver.unregisterContentObserver(it) } catch (_: Exception) {} }
        observer = null
    }

    companion object {
        /** Codes are offered for 5 minutes after they arrive (most expire sooner). */
        const val SHOW_FOR_MS = 5 * 60_000L

        private val CODE_HINT = Regex("(?i)\\b(code|otp|pin|verif|token|passcode|msimbo|nambari)")

        /** A field that is asking for a code: numeric, or labelled like one. */
        fun looksLikeCodeField(info: FlorisEditorInfo): Boolean {
            val attrs = info.inputAttributes
            if (attrs.type == InputAttributes.Type.NUMBER) return true
            val hint = info.base.hintText?.toString().orEmpty() + " " + (info.base.label?.toString().orEmpty())
            return CODE_HINT.containsMatchIn(hint)
        }
    }
}
