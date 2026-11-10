/*
 * Copyright (C) 2026 HushhBoard
 * Licensed under the Apache License, Version 2.0.
 */

package dev.patrickgold.florisboard.scanner

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hushhboard.nlp.entities.Entity
import dev.hushhboard.nlp.entities.EntityExtractor
import dev.hushhboard.nlp.entities.EntityKind
import dev.hushhboard.nlp.entities.PaymentParser
import dev.hushhboard.nlp.entities.ScanPayload
import dev.hushhboard.nlp.entities.UrlSafety

/** Scanner palette: dark glass with one accent per kind of result. */
internal object ScanColors {
    val Sheet = Color(0xF2121418)
    val Card = Color(0xFF1B1E24)
    val Line = Color(0x1FFFFFFF)
    val Text = Color(0xFFF4F5F7)
    val Muted = Color(0xFF9AA0AA)
    val Money = Color(0xFF00D47E)
    val Link = Color(0xFF5AA9FF)
    val Code = Color(0xFF8E7CFF)
    val Warn = Color(0xFFFFB020)
    val Danger = Color(0xFFFF5A5F)
}

/**
 * The card shown after a scan. One primary action that fits the kind of result, a few secondary
 * ones, and nothing ever opens by itself.
 */
@Composable
fun ScanResultSheet(
    result: ScanPayload,
    canInsert: Boolean,
    onInsert: (String) -> Unit,
    onPay: (PaymentParser.Payment, String) -> Unit,
    onScanAgain: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var shown by remember(result) { mutableStateOf(result) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(ScanColors.Sheet)
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .animateContentSize(),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).size(width = 36.dp, height = 4.dp).clip(CircleShape).background(ScanColors.Line))
        Spacer(Modifier.height(14.dp))

        when (val p = shown) {
            is ScanPayload.Link -> LinkResult(p, canInsert, onInsert, onDone)
            is ScanPayload.Wifi -> WifiResult(p, onDone)
            is ScanPayload.Phone -> PhoneResult(p.number, canInsert, onInsert, onPay, onDone)
            is ScanPayload.Sms -> {
                Header(Icons.Rounded.Sms, ScanColors.Link, "Text message", p.number)
                p.body?.let { Body(it) }
                Actions(
                    primary = Action("Send SMS", Icons.AutoMirrored.Rounded.Send) { ScanActions.sms(context, p.number, p.body); onDone() },
                    Action("Copy number", Icons.Rounded.ContentCopy) { ScanActions.copy(context, p.number) },
                )
            }
            is ScanPayload.Email -> {
                Header(Icons.Rounded.Email, ScanColors.Link, "Email", p.address)
                p.subject?.let { Body(it) }
                Actions(
                    primary = Action("Write email", Icons.Rounded.Email) { ScanActions.email(context, p.address, p.subject, p.body); onDone() },
                    Action("Copy", Icons.Rounded.ContentCopy) { ScanActions.copy(context, p.address) },
                    if (canInsert) Action("Insert", Icons.Rounded.Keyboard) { onInsert(p.address) } else null,
                )
            }
            is ScanPayload.Geo -> {
                val where = p.query ?: listOfNotNull(p.lat, p.lng).joinToString(", ")
                Header(Icons.Rounded.Map, ScanColors.Link, "Location", where)
                Actions(
                    primary = Action("Open in Maps", Icons.Rounded.Map) { ScanActions.map(context, p.lat, p.lng, p.query); onDone() },
                    Action("Copy", Icons.Rounded.ContentCopy) { ScanActions.copy(context, where) },
                    if (canInsert) Action("Insert", Icons.Rounded.Keyboard) { onInsert(where) } else null,
                )
            }
            is ScanPayload.Contact -> {
                Header(Icons.Rounded.Person, ScanColors.Code, p.name ?: "Contact", p.organization ?: p.phones.firstOrNull() ?: p.emails.firstOrNull() ?: "")
                val lines = p.phones + p.emails + listOfNotNull(p.url)
                if (lines.isNotEmpty()) Body(lines.joinToString("\n"))
                Actions(
                    primary = Action("Save contact", Icons.Rounded.PersonAdd) {
                        ScanActions.saveContact(context, p.name, p.phones, p.emails, p.organization); onDone()
                    },
                    p.phones.firstOrNull()?.let { n -> Action("Call", Icons.Rounded.Call) { ScanActions.dial(context, n); onDone() } },
                    Action("Copy", Icons.Rounded.ContentCopy) { ScanActions.copy(context, listOfNotNull(p.name, *lines.toTypedArray()).joinToString("\n")) },
                )
            }
            is ScanPayload.Payment -> PaymentResult(p, onPay, onDone)
            is ScanPayload.Text -> TextResult(p.raw, canInsert, onInsert, onShow = { shown = it }, onPay = onPay, onDone = onDone)
        }

        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Row(
                Modifier.clip(CircleShape).clickable { onScanAgain() }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.QrCodeScanner, null, tint = ScanColors.Muted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Scan again", color = ScanColors.Muted, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun LinkResult(p: ScanPayload.Link, canInsert: Boolean, onInsert: (String) -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val report = p.report
    var confirm by remember(p) { mutableStateOf(false) }
    val site = report.site.ifEmpty { report.host.ifEmpty { "Link" } }
    Header(Icons.Rounded.Link, ScanColors.Link, site, p.url, monoSubtitle = true)
    SafetyBanner(report)
    val danger = report.level == UrlSafety.Level.DANGER
    Actions(
        primary = Action(
            label = when {
                danger && !confirm -> "Open anyway…"
                danger -> "Yes, open it"
                else -> "Open in browser"
            },
            icon = if (danger) Icons.Rounded.Warning else Icons.Rounded.OpenInBrowser,
            tint = if (danger) ScanColors.Danger else ScanColors.Link,
        ) {
            if (danger && !confirm) {
                confirm = true
            } else {
                ScanActions.openLink(context, p.url)
                onDone()
            }
        },
        Action("Copy", Icons.Rounded.ContentCopy) { ScanActions.copy(context, p.url) },
        if (canInsert) Action("Insert", Icons.Rounded.Keyboard) { onInsert(p.url) } else null,
    )
}

@Composable
private fun SafetyBanner(report: UrlSafety.Report) {
    val (color, icon, title) = when (report.level) {
        UrlSafety.Level.OK -> Triple(ScanColors.Money, Icons.Rounded.CheckCircle, "Secure link to ${report.site}")
        UrlSafety.Level.CAUTION -> Triple(ScanColors.Warn, Icons.Rounded.GppMaybe, "Check before you open")
        UrlSafety.Level.DANGER -> Triple(
            ScanColors.Danger, Icons.Rounded.Warning,
            report.imitates?.let { "This is not ${brandName(it)}'s website" } ?: "This link looks unsafe",
        )
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, color = ScanColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        for (w in report.warnings) {
            Text("•  " + w.message, color = ScanColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 28.dp, top = 4.dp))
        }
        if (report.level != UrlSafety.Level.OK) {
            Text(
                "QR codes on posters and stickers are a common scam. Only open it if you trust where it came from.",
                color = ScanColors.Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 28.dp, top = 6.dp),
            )
        }
    }
}

private fun brandName(key: String): String = when (key) {
    "mpesa" -> "M-Pesa"
    "kcb", "kcbgroup" -> "KCB"
    "ncba" -> "NCBA"
    "kra" -> "KRA"
    "helb" -> "HELB"
    "nhif" -> "NHIF"
    "equitybank" -> "Equity"
    "coopbank" -> "Co-op Bank"
    "ecitizen" -> "eCitizen"
    "hustlerfund" -> "Hustler Fund"
    else -> key.replaceFirstChar { it.uppercase() }
}

@Composable
private fun WifiResult(p: ScanPayload.Wifi, onDone: () -> Unit) {
    val context = LocalContext.current
    var reveal by remember { mutableStateOf(false) }
    Header(Icons.Rounded.Wifi, ScanColors.Link, p.ssid, when {
        p.security.equals("nopass", ignoreCase = true) -> "Open network"
        else -> p.security.uppercase() + " network"
    })
    p.password?.let { pw ->
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(14.dp)).background(ScanColors.Card).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (reveal) pw else "•".repeat(minOf(pw.length, 14)),
                color = ScanColors.Text, fontFamily = FontFamily.Monospace, fontSize = 15.sp, modifier = Modifier.weight(1f),
            )
            Icon(
                if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "Show password",
                tint = ScanColors.Muted, modifier = Modifier.size(22.dp).clickable { reveal = !reveal },
            )
        }
    }
    Actions(
        primary = Action("Join network", Icons.Rounded.Wifi) { ScanActions.joinWifi(context, p); onDone() },
        p.password?.let { pw -> Action("Copy password", Icons.Rounded.ContentCopy) { ScanActions.copy(context, pw, "Wi-Fi password") } },
    )
}

@Composable
private fun PhoneResult(
    number: String,
    canInsert: Boolean,
    onInsert: (String) -> Unit,
    onPay: (PaymentParser.Payment, String) -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val local = EntityExtractor.normalizeKenyanPhone(number)
    Header(Icons.Rounded.Phone, ScanColors.Link, local ?: number, local?.let { EntityExtractor.carrierOf(it) } ?: "Phone number")
    Actions(
        primary = Action("Call", Icons.Rounded.Call) { ScanActions.dial(context, number); onDone() },
        local?.let { l -> Action("Send money", Icons.Rounded.Payments, tint = ScanColors.Money) {
            onPay(PaymentParser.Payment(PaymentParser.Type.SEND_MONEY, phone = l), l)
        } },
        Action("Save", Icons.Rounded.PersonAdd) { ScanActions.saveContact(context, null, listOf(local ?: number), emptyList(), null) },
        Action("Copy", Icons.Rounded.ContentCopy) { ScanActions.copy(context, local ?: number) },
        if (canInsert) Action("Insert", Icons.Rounded.Keyboard) { onInsert(local ?: number) } else null,
    )
}

@Composable
private fun PaymentResult(p: ScanPayload.Payment, onPay: (PaymentParser.Payment, String) -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val pay = p.payment
    val title = when (pay.type) {
        PaymentParser.Type.TILL -> "Buy Goods · Till ${pay.till}"
        PaymentParser.Type.PAYBILL -> "Paybill ${pay.paybill}"
        PaymentParser.Type.SEND_MONEY -> "Send to ${pay.phone}"
        PaymentParser.Type.UNKNOWN -> "Payment"
    }
    Header(Icons.Rounded.Payments, ScanColors.Money, title, p.merchant ?: pay.payee ?: "M-Pesa")
    Column(
        Modifier.fillMaxWidth().padding(vertical = 10.dp).clip(RoundedCornerShape(18.dp)).background(ScanColors.Card).padding(16.dp),
    ) {
        pay.amount?.let {
            Text("KSh " + EntityExtractor.groupThousands(it.substringBefore('.')) + (it.substringAfter('.', "").let { c -> if (c.isEmpty()) "" else ".$c" }),
                color = ScanColors.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
        }
        pay.account?.let { Field("Account", it) }
        pay.till?.let { Field("Till number", it) }
        pay.paybill?.let { Field("Business number", it) }
        pay.phone?.let { Field("Phone", it) }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (p.verified) Icons.Rounded.CheckCircle else Icons.Rounded.GppMaybe, null,
                tint = if (p.verified) ScanColors.Money else ScanColors.Warn, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                if (p.verified) "Official payment QR · checksum OK" else "Read 3 times to be sure. Confirm the details before paying.",
                color = ScanColors.Muted, fontSize = 12.sp,
            )
        }
    }
    val number = pay.till ?: pay.paybill ?: pay.phone
    Actions(
        primary = Action("Pay with M-Pesa", Icons.Rounded.Payments, tint = ScanColors.Money) { onPay(pay, p.raw) },
        number?.let { n -> Action("Copy number", Icons.Rounded.ContentCopy) { ScanActions.copy(context, n) } },
    )
}

@Composable
private fun Field(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = ScanColors.Muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = ScanColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun TextResult(
    text: String,
    canInsert: Boolean,
    onInsert: (String) -> Unit,
    onShow: (ScanPayload) -> Unit,
    onPay: (PaymentParser.Payment, String) -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val entities = remember(text) { EntityExtractor.rank(EntityExtractor.extract(text), 8) }
    Header(Icons.Rounded.TextFields, ScanColors.Code, "Text", "${text.length} characters")
    Box(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).heightIn(max = 200.dp).clip(RoundedCornerShape(16.dp))
            .background(ScanColors.Card).verticalScroll(rememberScrollState()).padding(14.dp),
    ) {
        Text(text, color = ScanColors.Text, fontSize = 15.sp, lineHeight = 21.sp)
    }
    if (entities.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
            for (e in entities) {
                EntityChip(e) {
                    when (e.kind) {
                        EntityKind.URL -> onShow(ScanPayload.Link(e.value, e.value, UrlSafety.check(e.value)))
                        EntityKind.PHONE -> onShow(ScanPayload.Phone(e.value, e.value))
                        EntityKind.EMAIL -> onShow(ScanPayload.Email(e.value, e.value, null, null))
                        EntityKind.TILL -> onPay(PaymentParser.Payment(PaymentParser.Type.TILL, till = e.value), text)
                        EntityKind.PAYBILL -> onPay(
                            PaymentParser.Payment(PaymentParser.Type.PAYBILL, paybill = e.value, account = e.extra["account"]), text,
                        )
                        EntityKind.PLACE -> ScanActions.map(context, e.extra["lat"]?.toDoubleOrNull(), e.extra["lng"]?.toDoubleOrNull(), e.value)
                        else -> ScanActions.copy(context, e.value)
                    }
                }
                Spacer(Modifier.width(8.dp))
            }
        }
    }
    Actions(
        primary = if (canInsert) Action("Insert", Icons.Rounded.Keyboard) { onInsert(text) }
        else Action("Copy", Icons.Rounded.ContentCopy) { ScanActions.copy(context, text); onDone() },
        if (canInsert) Action("Copy", Icons.Rounded.ContentCopy) { ScanActions.copy(context, text) } else null,
        Action("Share", Icons.Rounded.Share) { ScanActions.share(context, text) },
        Action("Search", Icons.Rounded.Search) { ScanActions.webSearch(context, text.take(200)); onDone() },
    )
}

@Composable
internal fun EntityChip(e: Entity, onClick: () -> Unit) {
    val accent = when (e.kind) {
        EntityKind.TILL, EntityKind.PAYBILL, EntityKind.AMOUNT, EntityKind.MPESA_CODE -> ScanColors.Money
        EntityKind.URL, EntityKind.EMAIL, EntityKind.PHONE -> ScanColors.Link
        EntityKind.OTP -> ScanColors.Code
        else -> ScanColors.Muted
    }
    Column(
        Modifier.clip(RoundedCornerShape(14.dp)).background(accent.copy(alpha = 0.14f))
            .border(1.dp, accent.copy(alpha = 0.3f), RoundedCornerShape(14.dp)).clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(e.kind.label.uppercase(), color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        Text(
            if (e.kind == EntityKind.URL) e.detail ?: e.value else e.value,
            color = ScanColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        e.detail?.takeIf { e.kind != EntityKind.URL }?.let { Text(it, color = ScanColors.Muted, fontSize = 11.sp, maxLines = 1) }
    }
}

// ── Building blocks ─────────────────────────────────────────────────────────────────────────

@Composable
private fun Header(icon: ImageVector, accent: Color, title: String, subtitle: String, monoSubtitle: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = ScanColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle, color = ScanColors.Muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    fontFamily = if (monoSubtitle) FontFamily.Monospace else FontFamily.Default,
                )
            }
        }
    }
}

@Composable
private fun Body(text: String) {
    Text(text, color = ScanColors.Text, fontSize = 15.sp, modifier = Modifier.padding(vertical = 10.dp), maxLines = 6, overflow = TextOverflow.Ellipsis)
}

internal class Action(val label: String, val icon: ImageVector, val tint: Color = ScanColors.Link, val onClick: () -> Unit)

@Composable
private fun Actions(primary: Action, vararg secondary: Action?) {
    Spacer(Modifier.height(10.dp))
    Row(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp)).background(primary.tint).clickable { primary.onClick() },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(primary.icon, null, tint = Color(0xFF0B0D10), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(primary.label, color = Color(0xFF0B0D10), fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
    val rest = secondary.filterNotNull()
    if (rest.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (a in rest) {
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(ScanColors.Card).clickable { a.onClick() }
                        .padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(a.icon, null, tint = ScanColors.Text, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.height(4.dp))
                    Text(a.label, color = ScanColors.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
