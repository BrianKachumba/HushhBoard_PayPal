/*
 * Copyright (C) 2026 HushhBoard
 * Licensed under the Apache License, Version 2.0.
 */

package dev.hushhboard.nlp.entities

/** What a scanned QR code (or a block of scanned text) actually is, so it can be acted on. */
sealed class ScanPayload {
    abstract val raw: String

    data class Link(override val raw: String, val url: String, val report: UrlSafety.Report) : ScanPayload()
    data class Wifi(override val raw: String, val ssid: String, val password: String?, val security: String, val hidden: Boolean) : ScanPayload()
    data class Phone(override val raw: String, val number: String) : ScanPayload()
    data class Sms(override val raw: String, val number: String, val body: String?) : ScanPayload()
    data class Email(override val raw: String, val address: String, val subject: String?, val body: String?) : ScanPayload()
    data class Geo(override val raw: String, val lat: Double?, val lng: Double?, val query: String?) : ScanPayload()
    data class Contact(
        override val raw: String,
        val name: String?,
        val phones: List<String>,
        val emails: List<String>,
        val organization: String?,
        val url: String?,
    ) : ScanPayload()
    data class Payment(override val raw: String, val payment: PaymentParser.Payment, val merchant: String?, val verified: Boolean) : ScanPayload()
    data class Text(override val raw: String) : ScanPayload()

    companion object {
        /** Classifies the raw value of a QR code. */
        fun parse(raw: String): ScanPayload {
            val s = raw.trim()
            val lower = s.lowercase()
            return when {
                lower.startsWith("wifi:") -> parseWifi(s) ?: Text(s)
                lower.startsWith("tel:") -> Phone(s, s.substring(4).trim())
                lower.startsWith("smsto:") || lower.startsWith("sms:") || lower.startsWith("mmsto:") -> parseSms(s)
                lower.startsWith("mailto:") -> parseMailto(s)
                lower.startsWith("matmsg:") -> parseMatmsg(s)
                lower.startsWith("geo:") -> parseGeo(s)
                lower.startsWith("begin:vcard") -> parseVcard(s)
                lower.startsWith("mecard:") -> parseMecard(s)
                s.startsWith("000201") && "6304" in s -> parseEmv(s) ?: Text(s)
                lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("www.") -> {
                    val url = if (lower.startsWith("www.")) "https://$s" else s
                    Link(s, url, UrlSafety.check(url))
                }
                else -> classifyText(s)
            }
        }

        /** For plain text: a lone link, phone number or payment instruction gets its own type. */
        fun classifyText(s: String): ScanPayload {
            val entities = EntityExtractor.extract(s)
            val single = entities.singleOrNull()?.takeIf { it.end - it.start >= s.trim().length - 2 }
            when (single?.kind) {
                EntityKind.URL -> return Link(s, single.value, UrlSafety.check(single.value))
                EntityKind.PHONE -> return Phone(s, single.value)
                EntityKind.EMAIL -> return Email(s, single.value, null, null)
                else -> Unit
            }
            val payment = PaymentParser.parse(s)
            if (payment.isValid && (payment.type != PaymentParser.Type.SEND_MONEY || s.length < 40)) {
                return Payment(s, payment, payment.payee, verified = false)
            }
            return Text(s)
        }

        // ── Wi-Fi: WIFI:T:WPA;S:My Net;P:secret;H:false;; ────────────────────────────────────

        private fun parseWifi(s: String): Wifi? {
            val fields = splitEscaped(s.substring(5))
            val ssid = fields["S"] ?: return null
            val type = fields["T"]?.uppercase()?.ifEmpty { null } ?: if (fields["P"].isNullOrEmpty()) "nopass" else "WPA"
            return Wifi(s, ssid, fields["P"]?.ifEmpty { null }, type, fields["H"].equals("true", ignoreCase = true))
        }

        /** Splits "K:v;K2:v\;x;;" honouring backslash escapes. */
        private fun splitEscaped(body: String): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            val cur = StringBuilder()
            var key: String? = null
            var i = 0
            while (i < body.length) {
                val c = body[i]
                when {
                    c == '\\' && i + 1 < body.length -> { cur.append(body[i + 1]); i++ }
                    c == ':' && key == null -> { key = cur.toString().uppercase(); cur.clear() }
                    c == ';' -> {
                        if (key != null) out.putIfAbsent(key, cur.toString())
                        key = null
                        cur.clear()
                    }
                    else -> cur.append(c)
                }
                i++
            }
            if (key != null) out.putIfAbsent(key, cur.toString())
            return out
        }

        private fun parseSms(s: String): ScanPayload {
            val body = s.substringAfter(':')
            return if (s.lowercase().startsWith("sms:")) {
                val number = body.substringBefore('?').substringBefore(':')
                val text = queryParam(body, "body") ?: body.substringAfter(':', "").ifEmpty { null }
                Sms(s, number, text)
            } else {
                Sms(s, body.substringBefore(':'), body.substringAfter(':', "").ifEmpty { null })
            }
        }

        private fun parseMailto(s: String): ScanPayload {
            val body = s.substring(7)
            return Email(s, decode(body.substringBefore('?')), queryParam(body, "subject"), queryParam(body, "body"))
        }

        private fun parseMatmsg(s: String): ScanPayload {
            val f = splitEscaped(s.substring(7))
            return Email(s, f["TO"].orEmpty(), f["SUB"]?.ifEmpty { null }, f["BODY"]?.ifEmpty { null })
        }

        private fun parseGeo(s: String): ScanPayload {
            val body = s.substring(4)
            val coords = body.substringBefore('?').split(',')
            val lat = coords.getOrNull(0)?.toDoubleOrNull()
            val lng = coords.getOrNull(1)?.toDoubleOrNull()
            return Geo(s, lat, lng, queryParam(body, "q"))
        }

        private fun parseVcard(s: String): ScanPayload {
            var name: String? = null
            var org: String? = null
            var url: String? = null
            val phones = ArrayList<String>()
            val emails = ArrayList<String>()
            // Unfold continuation lines first (RFC 6350 §3.2).
            val lines = s.replace("\r\n ", "").replace("\n ", "").lines()
            for (line in lines) {
                val key = line.substringBefore(':').substringBefore(';').uppercase()
                val value = line.substringAfter(':', "").trim()
                when (key) {
                    "FN" -> name = value
                    "N" -> if (name == null) name = value.split(';').filter { it.isNotBlank() }.reversed().joinToString(" ")
                    "TEL" -> if (value.isNotEmpty()) phones += value
                    "EMAIL" -> if (value.isNotEmpty()) emails += value
                    "ORG" -> org = value.replace(';', ' ').trim()
                    "URL" -> url = value
                }
            }
            return Contact(s, name, phones, emails, org, url)
        }

        private fun parseMecard(s: String): ScanPayload {
            val f = splitEscaped(s.substring(7))
            val name = f["N"]?.split(',')?.reversed()?.joinToString(" ")?.trim()
            return Contact(s, name, listOfNotNull(f["TEL"]?.ifEmpty { null }), listOfNotNull(f["EMAIL"]?.ifEmpty { null }), f["ORG"], f["URL"])
        }

        private fun queryParam(s: String, name: String): String? {
            val q = s.substringAfter('?', "")
            for (part in q.split('&')) {
                if (part.substringBefore('=').equals(name, ignoreCase = true)) return decode(part.substringAfter('=', ""))
            }
            return null
        }

        private fun decode(s: String): String {
            val out = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 < s.length) {
                    val b = s.substring(i + 1, i + 3).toIntOrNull(16)
                    if (b != null) { out.write(b); i += 3; continue }
                }
                if (c == '+') out.write(' '.code) else out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
            return out.toString(Charsets.UTF_8.name())
        }

        // ── EMVCo merchant-presented QR (Kenya's KE-QR standard) ─────────────────────────────

        /** Parses tag-length-value data: "0002" + "01" … -> map of tag to value. */
        internal fun tlv(s: String): Map<String, String>? {
            val out = LinkedHashMap<String, String>()
            var i = 0
            while (i < s.length) {
                if (i + 4 > s.length) return null
                val tag = s.substring(i, i + 2)
                val len = s.substring(i + 2, i + 4).toIntOrNull() ?: return null
                if (i + 4 + len > s.length) return null
                out[tag] = s.substring(i + 4, i + 4 + len)
                i += 4 + len
            }
            return out
        }

        /** CRC-16/CCITT-FALSE, as used for the EMVCo "63" checksum. */
        internal fun crc16(data: String): String {
            var crc = 0xFFFF
            for (b in data.toByteArray(Charsets.UTF_8)) {
                crc = crc xor ((b.toInt() and 0xFF) shl 8)
                repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
                crc = crc and 0xFFFF
            }
            return "%04X".format(crc)
        }

        private fun parseEmv(s: String): ScanPayload? {
            val fields = tlv(s) ?: return null
            val crcIndex = s.lastIndexOf("6304")
            val verified = crcIndex >= 0 && crcIndex + 8 == s.length &&
                crc16(s.substring(0, crcIndex + 4)).equals(s.substring(crcIndex + 4), ignoreCase = true)
            val merchant = fields["59"]?.trim()?.ifEmpty { null }
            val amount = fields["54"]?.trim()?.ifEmpty { null }?.let { a ->
                val d = a.toDoubleOrNull() ?: return@let null
                if (d == Math.floor(d)) d.toLong().toString() else "%.2f".format(d)
            }
            val extra = fields["62"]?.let { tlv(it) }.orEmpty()
            val reference = extra["01"] ?: extra["05"] ?: extra["02"]
            // Merchant account templates (tags 26-51) carry the till / paybill number.
            var number: String? = null
            var isPaybill = false
            for (tag in 26..51) {
                val tpl = fields["%02d".format(tag)] ?: continue
                val sub = tlv(tpl) ?: continue
                val guid = sub["00"].orEmpty().lowercase()
                if ("paybill" in guid || "pb" == guid) isPaybill = true
                for ((k, v) in sub) {
                    if (k == "00") continue
                    if (v.length in 5..7 && v.all { it.isDigit() }) { number = v; break }
                }
                if (number != null) break
            }
            if (number == null) return Text(s)
            val payment = if (isPaybill || reference != null) {
                PaymentParser.Payment(PaymentParser.Type.PAYBILL, paybill = number, account = reference, amount = amount, payee = merchant)
            } else {
                PaymentParser.Payment(PaymentParser.Type.TILL, till = number, amount = amount, payee = merchant)
            }
            return Payment(s, payment, merchant, verified)
        }
    }
}
