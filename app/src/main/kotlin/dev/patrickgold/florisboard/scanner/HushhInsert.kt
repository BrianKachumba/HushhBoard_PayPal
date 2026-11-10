/*
 * Copyright (C) 2026 HushhBoard
 * Licensed under the Apache License, Version 2.0.
 */

package dev.patrickgold.florisboard.scanner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.core.content.ContextCompat

/**
 * Hands text from the scanner / song screens (which run in the ":hushh" process) to the keyboard,
 * which types it into the field the user was in once that field is active again.
 */
object HushhInsert {
    private const val ACTION = "dev.hushhboard.action.INSERT_TEXT"
    private const val EXTRA_TEXT = "text"
    /** A pending insert older than this is dropped: the user has moved on. */
    private const val MAX_AGE_MS = 60_000L

    /** Called from the scanner/song screens. */
    fun send(context: Context, text: String) {
        if (text.isEmpty()) return
        context.sendBroadcast(Intent(ACTION).setPackage(context.packageName).putExtra(EXTRA_TEXT, text))
    }

    /** Keyboard side: receives inserts and delivers them when a field is ready. */
    class Receiver(private val commit: (String) -> Boolean, private val isFieldActive: () -> Boolean) : BroadcastReceiver() {
        private var pending: String? = null
        private var pendingAt = 0L

        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION) return
            val text = intent.getStringExtra(EXTRA_TEXT) ?: return
            pending = text
            pendingAt = SystemClock.elapsedRealtime()
            deliverIfReady()
        }

        /** Call when an input view starts: types the pending text into the new field. */
        fun deliverIfReady() {
            val text = pending ?: return
            if (SystemClock.elapsedRealtime() - pendingAt > MAX_AGE_MS) {
                pending = null
                return
            }
            if (isFieldActive() && commit(text)) pending = null
        }

        fun register(context: Context) {
            ContextCompat.registerReceiver(context, this, IntentFilter(ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        }

        fun unregister(context: Context) {
            try { context.unregisterReceiver(this) } catch (_: Exception) {}
        }
    }
}
