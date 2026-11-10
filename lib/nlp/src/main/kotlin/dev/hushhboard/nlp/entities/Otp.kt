/*
 * Copyright (C) 2026 HushhBoard
 * Licensed under the Apache License, Version 2.0.
 */

package dev.hushhboard.nlp.entities

/**
 * One-time / verification codes in SMS bodies and copied text ("Your code is 482913",
 * "G-482913 is your Google verification code", "Nambari yako ya uthibitisho ni 4829").
 *
 * Precision first: a wrong "code" pasted into a login box is worse than none, so a number only
 * counts when a cue word is close by, or when the whole text is just the code (a "Copy code"
 * button). Amounts, phone numbers, years, times and paybill/till/account numbers never count.
 */
object Otp {
    private val CUE = Regex(
        """(?i)\b(?:codes?|otp|verification|verify|confirmation|confirm|pin|passcode|password|one[- ]?time|2fa|token|login|sign[- ]?in|security|authenti\w*|nambari|namba|msimbo|uthibitisho|kuthibitisha|tarakimu)\b""",
    )
    private val CANDIDATE = Regex("""(?<![\w.,:/\-])(?:G-)?(\d{3}[- ]\d{3}|\d{4,8})(?![\w,:/]|[.-]\d)""")
    private val BARE = Regex("""^\s*(?:G-)?(\d{4,8}|\d{3}[- ]\d{3})\s*$""")
    private val MONEY_BEFORE = Regex("""(?i)(?:kshs?|kes|sh|usd|\$|€|£)\.?\s?$""")
    private val NUMBER_CONTEXT_BEFORE = Regex("""(?i)(?:till|paybill|pay\s*bill|business\s*no|acc(?:ount)?|a/c|ref|id|no\.?|number|ticket|order|invoice|box|p\.?o\.?)\s*[:#.\-]?\s*$""")
    private const val MAX_CUE_DISTANCE = 60

    /** The single best code in [text], as an entity, or nothing. */
    fun findAll(text: String): List<Entity> {
        BARE.matchEntire(text)?.let { m ->
            val g = m.groups[1]!!
            return listOf(Entity(EntityKind.OTP, g.value.filter { it.isDigit() }, g.range.first, g.range.last + 1, detail = "Verification code"))
        }
        val cues = CUE.findAll(text).map { it.range }.toList()
        if (cues.isEmpty()) return emptyList()
        var best: Entity? = null
        var bestDistance = Int.MAX_VALUE
        for (m in CANDIDATE.findAll(text)) {
            val g = m.groups[1]!!
            val digits = g.value.filter { it.isDigit() }
            if (!isPlausible(text, g.range.first, digits)) continue
            val distance = cues.minOf { cue ->
                when {
                    cue.last < g.range.first -> g.range.first - cue.last
                    cue.first > g.range.last -> cue.first - g.range.last
                    else -> 0
                }
            }
            if (distance > MAX_CUE_DISTANCE) continue
            if (distance < bestDistance) {
                bestDistance = distance
                best = Entity(EntityKind.OTP, digits, g.range.first, g.range.last + 1, detail = "Verification code")
            }
        }
        return listOfNotNull(best)
    }

    /** The verification code in an SMS [body], or null. */
    fun fromMessage(body: String): String? {
        // M-Pesa transaction confirmations are full of numbers and are never codes.
        val lower = body.lowercase()
        if ("confirmed" in lower && ("m-pesa" in lower || "mpesa" in lower) && "transaction cost" in lower) return null
        return findAll(body).firstOrNull()?.value
    }

    /** True if [text] is just a code (what a "Copy code" button puts on the clipboard). */
    fun isBareCode(text: String): Boolean = BARE.matches(text)

    private fun isPlausible(text: String, start: Int, digits: String): Boolean {
        if (digits.length !in 4..8) return false
        val before = text.substring(maxOf(0, start - 14), start)
        if (MONEY_BEFORE.containsMatchIn(before)) return false
        if (NUMBER_CONTEXT_BEFORE.containsMatchIn(before) && !before.lowercase().contains("code")) return false
        // Years and all-same digits ("0000") are almost never codes in a message with other text.
        if (digits.length == 4 && (digits.startsWith("19") || digits.startsWith("20"))) {
            val after = text.substring(minOf(text.length, start + 4), minOf(text.length, start + 6))
            if (!before.trimEnd().endsWith(":") && !before.lowercase().trimEnd().endsWith("is")) return false
            if (after.startsWith("-") || after.startsWith("/")) return false
        }
        return true
    }
}
