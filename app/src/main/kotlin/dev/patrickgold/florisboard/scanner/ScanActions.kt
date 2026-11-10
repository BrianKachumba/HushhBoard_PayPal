/*
 * Copyright (C) 2026 HushhBoard
 * Licensed under the Apache License, Version 2.0.
 */

package dev.patrickgold.florisboard.scanner

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.provider.ContactsContract
import android.provider.Settings
import android.widget.Toast
import dev.hushhboard.nlp.entities.PaymentParser
import dev.hushhboard.nlp.entities.ScanPayload
import dev.patrickgold.florisboard.ussd.UssdConfirmationActivity

/** The things a scan result can be turned into. Every action is explicit: nothing opens by itself. */
object ScanActions {

    fun copy(context: Context, text: String, label: String = "HushhBoard scan") {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        // Android 13+ shows its own "copied" confirmation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
    }

    /** Opens a link in the user's default browser (or the app that owns the link). */
    fun openLink(context: Context, url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return start(context, intent)
    }

    fun dial(context: Context, number: String) =
        start(context, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    fun sms(context: Context, number: String, body: String?) = start(
        context,
        Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)))
            .apply { if (!body.isNullOrEmpty()) putExtra("sms_body", body) }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    fun email(context: Context, address: String, subject: String?, body: String?) = start(
        context,
        Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(address)))
            .apply {
                putExtra(Intent.EXTRA_EMAIL, arrayOf(address))
                subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
                body?.let { putExtra(Intent.EXTRA_TEXT, it) }
            }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    fun map(context: Context, lat: Double?, lng: Double?, query: String?): Boolean {
        val uri = when {
            lat != null && lng != null && query != null -> "geo:$lat,$lng?q=" + Uri.encode("$lat,$lng($query)")
            lat != null && lng != null -> "geo:$lat,$lng?q=$lat,$lng"
            else -> "geo:0,0?q=" + Uri.encode(query.orEmpty())
        }
        return start(context, Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun saveContact(context: Context, name: String?, phones: List<String>, emails: List<String>, org: String?): Boolean {
        val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
            type = ContactsContract.RawContacts.CONTENT_TYPE
            name?.let { putExtra(ContactsContract.Intents.Insert.NAME, it) }
            phones.getOrNull(0)?.let { putExtra(ContactsContract.Intents.Insert.PHONE, it) }
            phones.getOrNull(1)?.let { putExtra(ContactsContract.Intents.Insert.SECONDARY_PHONE, it) }
            emails.getOrNull(0)?.let { putExtra(ContactsContract.Intents.Insert.EMAIL, it) }
            org?.let { putExtra(ContactsContract.Intents.Insert.COMPANY, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return start(context, intent)
    }

    fun share(context: Context, text: String) = start(
        context,
        Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    fun webSearch(context: Context, text: String): Boolean {
        val search = Intent(Intent.ACTION_WEB_SEARCH).putExtra("query", text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (start(context, search)) return true
        return openLink(context, "https://www.google.com/search?q=" + Uri.encode(text))
    }

    /**
     * Joins a Wi-Fi network. Android 11+ shows its own "Save this network?" sheet; older versions
     * open Wi-Fi settings (the password is copied first so it can be pasted).
     */
    fun joinWifi(context: Context, wifi: ScanPayload.Wifi): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val builder = WifiNetworkSuggestion.Builder().setSsid(wifi.ssid)
            if (wifi.hidden) builder.setIsHiddenSsid(true)
            val password = wifi.password
            try {
                when {
                    password == null || wifi.security.equals("nopass", ignoreCase = true) -> Unit
                    wifi.security.equals("SAE", ignoreCase = true) || wifi.security.equals("WPA3", ignoreCase = true) ->
                        builder.setWpa3Passphrase(password)
                    wifi.security.equals("WEP", ignoreCase = true) -> {
                        copy(context, password, "Wi-Fi password")
                        return start(context, Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    else -> builder.setWpa2Passphrase(password)
                }
                val intent = Intent(Settings.ACTION_WIFI_ADD_NETWORKS)
                    .putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(builder.build()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (start(context, intent)) return true
            } catch (_: IllegalArgumentException) {
                // e.g. a passphrase the platform rejects; fall through to settings.
            }
        }
        wifi.password?.let { copy(context, it, "Wi-Fi password") }
        return start(context, Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Opens the HushhBoard payment confirmation with the scanned details filled in. */
    fun pay(context: Context, payment: PaymentParser.Payment, rawText: String) {
        val intent = Intent(context, UssdConfirmationActivity::class.java).apply {
            putExtra("RAW_OCR_TEXT", rawText)
            putExtra(UssdConfirmationActivity.EXTRA_PAY_TYPE, payment.type.name)
            putExtra(UssdConfirmationActivity.EXTRA_PAY_FIELDS, HashMap(payment.toFields()))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        start(context, intent)
    }

    /** "Paybill 247247 Account AB12 KSh 500": what edit mode hands back to the payment screen. */
    fun describe(payment: PaymentParser.Payment): String = buildString {
        when (payment.type) {
            PaymentParser.Type.TILL -> append("Till ").append(payment.till)
            PaymentParser.Type.PAYBILL -> {
                append("Paybill ").append(payment.paybill)
                payment.account?.let { append(" Account ").append(it) }
            }
            PaymentParser.Type.SEND_MONEY -> append("Send to ").append(payment.phone)
            PaymentParser.Type.UNKNOWN -> Unit
        }
        payment.amount?.let { append(" Ksh ").append(it) }
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app on this phone can open that", Toast.LENGTH_SHORT).show()
        false
    } catch (_: SecurityException) {
        false
    }
}
