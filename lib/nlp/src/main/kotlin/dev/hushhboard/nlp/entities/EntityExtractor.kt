/*
 * Copyright (C) 2026 HushhBoard
 * Licensed under the Apache License, Version 2.0.
 */

package dev.hushhboard.nlp.entities

/**
 * Finds the entities people copy and scan in Kenya: verification codes, M-Pesa paybills, tills,
 * phone numbers, emails, links, M-Pesa transaction codes, amounts, KRA PINs, names and places.
 *
 * Rule-based on purpose: it runs on every clipboard change and camera frame, needs no model
 * download, and its rules are tuned to local formats (07xx/01xx/+254 numbers, "Paybill 247247
 * Acc 123", M-Pesa SMS wording) that general-purpose extractors get wrong.
 */
object EntityExtractor {

    /** Everything found in [text], overlaps resolved (the more specific kind wins), in text order. */
    fun extract(text: String): List<Entity> {
        if (text.isBlank()) return emptyList()
        val found = ArrayList<Entity>()
        found += urls(text)
        found += emails(text)
        found += Otp.findAll(text)
        found += paybills(text)
        found += tills(text)
        found += kraPins(text)
        found += phones(text)
        found += mpesaCodes(text)
        found += amounts(text)
        found += people(text)
        found += places(text)
        return resolve(found)
    }

    /** Entities in the order they should be offered (most useful first), at most [limit]. */
    fun rank(entities: List<Entity>, limit: Int = 6): List<Entity> =
        entities.withIndex()
            .sortedWith(compareBy({ it.value.kind.ordinal }, { it.index }))
            .map { it.value }
            .distinctBy { it.kind to it.value }
            .take(limit)

    // ── Phones ──────────────────────────────────────────────────────────────────────────────

    private val KE_PHONE = Regex("""(?<![\d+])(?:\+?254[\s-]?|0)([17]\d{2})[\s-]?(\d{3})[\s-]?(\d{3})(?!\d)""")
    private val INTL_PHONE = Regex("""(?<![\w+])\+(?!254)(\d{1,3})[\s-]?\(?\d{1,4}\)?(?:[\s-]?\d{2,4}){2,4}(?!\d)""")

    /** "0712 345 678", "+254712345678", "254 112 345678" -> "0712345678". */
    fun normalizeKenyanPhone(raw: String): String? {
        val m = KE_PHONE.matchEntire(raw.trim()) ?: return null
        return "0" + m.groupValues[1] + m.groupValues[2] + m.groupValues[3]
    }

    private fun phones(text: String): List<Entity> {
        val out = ArrayList<Entity>()
        for (m in KE_PHONE.findAll(text)) {
            val local = "0" + m.groupValues[1] + m.groupValues[2] + m.groupValues[3]
            out += Entity(EntityKind.PHONE, local, m.range.first, m.range.last + 1, detail = carrierOf(local))
        }
        for (m in INTL_PHONE.findAll(text)) {
            val digits = m.value.filter { it.isDigit() }
            if (digits.length in 8..15) {
                out += Entity(EntityKind.PHONE, "+$digits", m.range.first, m.range.last + 1)
            }
        }
        return out
    }

    /** Best-effort network from the prefix (number portability makes this a hint, not a fact). */
    fun carrierOf(localPhone: String): String? {
        if (localPhone.length != 10) return null
        val p = localPhone.substring(1, 4).toIntOrNull() ?: return null
        return when (p) {
            in 700..729, in 740..743, 745, 746, 748, in 757..759, in 768..769, in 790..799, in 110..115 -> "Safaricom"
            in 730..739, in 750..756, in 780..789, in 100..102 -> "Airtel"
            in 770..779 -> "Telkom"
            else -> null
        }
    }

    // ── Email & links ───────────────────────────────────────────────────────────────────────

    private val EMAIL = Regex("""(?<![\w.+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,24}(?![\w-])""")

    private fun emails(text: String): List<Entity> =
        EMAIL.findAll(text).map { Entity(EntityKind.EMAIL, it.value, it.range.first, it.range.last + 1) }.toList()

    private val URL_SCHEME = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"']+""")
    private val BARE_DOMAIN = Regex(
        """(?i)(?<![\w@./-])(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+(?:com|net|org|info|io|app|dev|me|ke|co\.ke|or\.ke|go\.ke|ac\.ke|africa|co|tv|ly|gl|gy|link|site|online|store|shop)(?:/[^\s<>"']*)?(?![\w@])""",
    )

    private fun trimUrl(raw: String): String {
        var s = raw
        while (s.isNotEmpty() && s.last() in ".,;:!?'\"") s = s.dropLast(1)
        // Drop an unbalanced closing bracket: "(see https://x.com/a)" -> "https://x.com/a"
        while (s.endsWith(")") && s.count { it == '(' } < s.count { it == ')' }) s = s.dropLast(1)
        return s
    }

    private fun urls(text: String): List<Entity> {
        val out = ArrayList<Entity>()
        for (m in URL_SCHEME.findAll(text)) {
            val v = trimUrl(m.value)
            if (v.length < 5) continue
            val full = if (v.startsWith("www.", ignoreCase = true)) "https://$v" else v
            out += Entity(EntityKind.URL, full, m.range.first, m.range.first + v.length, detail = UrlSafety.hostOf(full))
        }
        for (m in BARE_DOMAIN.findAll(text)) {
            if (out.any { it.start <= m.range.first && m.range.last < it.end }) continue
            val v = trimUrl(m.value)
            // "0712.345.678" or version numbers are not domains; require a letter in the name.
            if (v.substringBefore('.').none { it.isLetter() }) continue
            out += Entity(EntityKind.URL, "https://$v", m.range.first, m.range.first + v.length, detail = UrlSafety.hostOf("https://$v"))
        }
        return out
    }

    // ── M-Pesa payments ─────────────────────────────────────────────────────────────────────

    private val PAYBILL = Regex(
        """(?i)\b(?:pay\s*bill|paybill|business\s*(?:no|number|#)|biz\s*no)\.?\s*(?:no\.?|number|#)?\s*[:#.\-]?\s*(\d{5,7})\b""",
    )
    private val ACCOUNT = Regex(
        """(?i)\b(?:acc(?:ount)?|a/c)\.?\s*(?:no\.?|number|#|name)?\s*[:#.\-]?\s*([A-Za-z0-9][A-Za-z0-9\-/]{0,24})""",
    )
    private val TILL = Regex(
        """(?i)\b(?:till|buy\s*goods(?:\s*(?:and|&)\s*services)?)\s*(?:no\.?|number|#)?\s*[:#.\-]?\s*(\d{5,7})\b""",
    )
    private val ACCOUNT_STOPWORDS = setOf("no", "number", "amount", "ksh", "kes", "and", "for", "name")

    private fun paybills(text: String): List<Entity> {
        val out = ArrayList<Entity>()
        for (m in PAYBILL.findAll(text)) {
            val number = m.groupValues[1]
            // The account usually follows the paybill within a short distance.
            val tail = text.substring(m.range.last + 1, minOf(text.length, m.range.last + 1 + 80))
            val acc = ACCOUNT.find(tail)?.groupValues?.get(1)?.takeIf { it.lowercase() !in ACCOUNT_STOPWORDS }
            val end = if (acc != null) m.range.last + 1 + ACCOUNT.find(tail)!!.range.last + 1 else m.range.last + 1
            out += Entity(
                EntityKind.PAYBILL, number, m.range.first, end,
                detail = acc?.let { "Acc $it" },
                extra = buildMap { put("paybill", number); if (acc != null) put("account", acc) },
            )
        }
        return out
    }

    private fun tills(text: String): List<Entity> =
        TILL.findAll(text).map {
            Entity(EntityKind.TILL, it.groupValues[1], it.range.first, it.range.last + 1, extra = mapOf("till" to it.groupValues[1]))
        }.toList()

    /** M-Pesa receipt numbers: 10 upper-case letters and digits, e.g. "SFK7ABC12D". */
    private val MPESA_CODE = Regex("""(?<![A-Za-z0-9])[A-Z][A-Z0-9]{9}(?![A-Za-z0-9])""")

    private fun mpesaCodes(text: String): List<Entity> =
        MPESA_CODE.findAll(text).filter { m ->
            val digits = m.value.count { it.isDigit() }
            val letters = m.value.count { it.isLetter() }
            digits in 1..7 && letters >= 3
        }.map { Entity(EntityKind.MPESA_CODE, it.value, it.range.first, it.range.last + 1) }.toList()

    private val AMOUNT_PREFIX = Regex("""(?i)\b(?:kshs?|kes|sh)\.?\s?(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d{1,2}))?""")
    private val AMOUNT_SUFFIX = Regex("""(?i)(?<![\d.,])(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d{1,2}))?\s?(?:/=|bob|kshs?\b|shillings?\b)""")

    private fun amounts(text: String): List<Entity> {
        val out = ArrayList<Entity>()
        for (re in listOf(AMOUNT_PREFIX, AMOUNT_SUFFIX)) {
            for (m in re.findAll(text)) {
                val whole = m.groupValues[1].replace(",", "")
                val cents = m.groupValues[2].takeIf { it.isNotEmpty() && it.trimEnd('0').isNotEmpty() }
                val value = if (cents != null) "$whole.${cents.padEnd(2, '0')}" else whole
                out += Entity(EntityKind.AMOUNT, value, m.range.first, m.range.last + 1, detail = "KSh " + groupThousands(whole) + (cents?.let { ".${it.padEnd(2, '0')}" } ?: ""))
            }
        }
        return out
    }

    fun groupThousands(digits: String): String {
        val s = digits.trimStart('0').ifEmpty { "0" }
        val sb = StringBuilder()
        for ((i, c) in s.withIndex()) {
            if (i > 0 && (s.length - i) % 3 == 0) sb.append(',')
            sb.append(c)
        }
        return sb.toString()
    }

    // ── KRA PIN ─────────────────────────────────────────────────────────────────────────────

    private val KRA_PIN = Regex("""(?<![A-Za-z0-9])[APap]\d{9}[A-Za-z](?![A-Za-z0-9])""")

    private fun kraPins(text: String): List<Entity> =
        KRA_PIN.findAll(text).map { Entity(EntityKind.KRA_PIN, it.value.uppercase(), it.range.first, it.range.last + 1) }.toList()

    // ── Names ───────────────────────────────────────────────────────────────────────────────

    private const val NAME_WORD = """[A-Z][A-Za-z'’.\-]{1,24}"""
    private const val UPPER_WORD = """[A-Z][A-Z'’.\-]{1,24}"""

    /** M-Pesa style: "sent to JOHN KAMAU 0712…", "from JANE W. 07…", "paid to NAIVAS LTD." */
    private val MPESA_PERSON = Regex("""(?i:sent to|received [^\n]{0,30}? from|from)\s+($UPPER_WORD(?:[ \t]+$UPPER_WORD){0,3})(?=\s+(?:\+?254|0)[17]\d|\s+on\s|\.)""")
    private val MPESA_BUSINESS = Regex("""(?i:paid to)\s+($NAME_WORD(?:[ \t]+[A-Za-z0-9&'’.\-]{1,24}){0,4}?)(?=\s*\.|\s+on\s|\s+for\s)""")
    private val NAME_CUE = Regex(
        """(?:(?i:name|jina|contact|attn|attention|beneficiary|recipient)\s*[:\-]\s*|(?:Mr|Mrs|Ms|Miss|Dr|Eng|Hon|Prof|Rev|Mama|Baba)\.?\s+)($NAME_WORD(?:[ \t]+$NAME_WORD){0,3})""",
    )
    private val SIGN_OFF = Regex("""(?i:regards|thanks|thank you|sincerely|cheers|asante|wako)\s*,?\s*\n\s*($NAME_WORD(?:[ \t]+$NAME_WORD){0,2})\s*$""", RegexOption.MULTILINE)
    private val NOT_NAMES = setOf("THE", "YOUR", "NEW", "M-PESA", "MPESA", "BALANCE", "ACCOUNT", "TRANSACTION", "CONFIRMED", "KSH")

    private fun people(text: String): List<Entity> {
        val out = ArrayList<Entity>()
        fun add(kind: EntityKind, m: MatchResult) {
            val g = m.groups[1] ?: return
            val name = g.value.trim().trimEnd('.', ',')
            if (name.length < 3 || name.uppercase() in NOT_NAMES) return
            val pretty = if (name == name.uppercase()) titleCase(name) else name
            out += Entity(kind, pretty, g.range.first, g.range.first + name.length)
        }
        MPESA_PERSON.findAll(text).forEach { add(EntityKind.PERSON, it) }
        MPESA_BUSINESS.findAll(text).forEach { add(EntityKind.BUSINESS, it) }
        NAME_CUE.findAll(text).forEach { add(EntityKind.PERSON, it) }
        SIGN_OFF.findAll(text).forEach { add(EntityKind.PERSON, it) }
        return out
    }

    fun titleCase(s: String): String = s.lowercase().split(' ').joinToString(" ") { w ->
        w.split('-').joinToString("-") { part -> part.replaceFirstChar { it.uppercase() } }
    }

    // ── Places ──────────────────────────────────────────────────────────────────────────────

    private val PLACE_SUFFIX = Regex(
        """\b($NAME_WORD(?:[ \t]+$NAME_WORD){0,3}[ \t]+(?:Road|Rd|Street|St|Avenue|Ave|Lane|Drive|Way|Highway|Hwy|Close|Crescent|Mall|Centre|Center|Plaza|House|Towers?|Estate|Stage|Hospital|Clinic|School|Academy|University|College|Church|Mosque|Hotel|Market|Park|Stadium|Airport))\b""",
    )
    private val PLACE_CUE = Regex("""(?i:location|venue|address|mahali|place|meet(?:ing)? (?:at|point))\s*[:\-]\s*([^\n;]{3,60})""")
    private val GEO = Regex("""(?<![\d.])(-?\d{1,2}\.\d{3,8})\s*,\s*(-?\d{1,3}\.\d{3,8})(?![\d.])""")

    private fun places(text: String): List<Entity> {
        val out = ArrayList<Entity>()
        for (m in PLACE_SUFFIX.findAll(text)) {
            val g = m.groups[1]!!
            out += Entity(EntityKind.PLACE, g.value, g.range.first, g.range.last + 1)
        }
        for (m in PLACE_CUE.findAll(text)) {
            val g = m.groups[1]!!
            val v = g.value.trim().trimEnd('.', ',')
            out += Entity(EntityKind.PLACE, v, g.range.first, g.range.first + v.length)
        }
        for (m in GEO.findAll(text)) {
            val lat = m.groupValues[1].toDouble()
            val lng = m.groupValues[2].toDouble()
            if (lat in -90.0..90.0 && lng in -180.0..180.0) {
                out += Entity(EntityKind.PLACE, "${m.groupValues[1]},${m.groupValues[2]}", m.range.first, m.range.last + 1,
                    detail = "Map pin", extra = mapOf("lat" to m.groupValues[1], "lng" to m.groupValues[2]))
            }
        }
        val lower = text.lowercase()
        for (place in KenyaPlaces.NAMES) {
            var from = 0
            while (true) {
                val i = lower.indexOf(place, from)
                if (i < 0) break
                from = i + place.length
                val before = if (i > 0) lower[i - 1] else ' '
                val after = if (from < lower.length) lower[from] else ' '
                if (before.isLetterOrDigit() || after.isLetterOrDigit()) continue
                // Mid-sentence lower-case "karen"/"meru" in chat is ambiguous; require a capital.
                if (!text[i].isUpperCase()) continue
                out += Entity(EntityKind.PLACE, text.substring(i, from), i, from)
            }
        }
        return out
    }

    // ── Overlaps ────────────────────────────────────────────────────────────────────────────

    /** Which kind wins when spans overlap (lower wins). */
    private fun priority(k: EntityKind): Int = when (k) {
        EntityKind.URL -> 0
        EntityKind.EMAIL -> 1
        EntityKind.OTP -> 2
        EntityKind.PAYBILL -> 3
        EntityKind.TILL -> 4
        EntityKind.KRA_PIN -> 5
        EntityKind.PHONE -> 6
        EntityKind.MPESA_CODE -> 7
        EntityKind.AMOUNT -> 8
        EntityKind.BUSINESS -> 9
        EntityKind.PERSON -> 10
        EntityKind.PLACE -> 11
    }

    private fun resolve(found: List<Entity>): List<Entity> {
        val sorted = found.sortedWith(compareBy({ priority(it.kind) }, { -(it.end - it.start) }, { it.start }))
        val kept = ArrayList<Entity>()
        for (e in sorted) {
            // A paybill span includes its account; a phone used as the account is still worth its own chip.
            val clash = kept.any { k ->
                k.overlaps(e) && !(k.kind == EntityKind.PAYBILL && e.kind == EntityKind.PHONE && k.extra["account"] != null)
            }
            if (!clash) kept += e
        }
        return kept.sortedBy { it.start }
    }
}
