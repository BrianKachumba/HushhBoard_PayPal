/*
 * Copyright (C) 2026 HushhBoard
 * Licensed under the Apache License, Version 2.0.
 */

package dev.hushhboard.nlp.entities

/**
 * M-Pesa payment details from a scanned sign, receipt or QR payload: "Lipa na M-PESA · Buy Goods
 * Till 123456", "Paybill 247247 Account 0712345678", "Send KSh 500 to 0712 345 678".
 *
 * [fingerprint] is what the camera compares across frames: a payment is only accepted when the
 * same type, numbers and amount come out of several consecutive frames.
 */
object PaymentParser {

    enum class Type { SEND_MONEY, TILL, PAYBILL, UNKNOWN }

    data class Payment(
        val type: Type,
        val phone: String? = null,
        val till: String? = null,
        val paybill: String? = null,
        val account: String? = null,
        val amount: String? = null,
        val payee: String? = null,
    ) {
        val isValid: Boolean
            get() = when (type) {
                Type.SEND_MONEY -> phone != null
                Type.TILL -> till != null
                Type.PAYBILL -> paybill != null
                Type.UNKNOWN -> false
            }

        val fingerprint: String get() = listOf(type, phone, till, paybill, account?.uppercase(), amount).joinToString("|")

        /** The keys the payment confirmation screen uses. */
        fun toFields(): Map<String, String> = buildMap {
            phone?.let { put("phone", it) }
            till?.let { put("till", it) }
            paybill?.let { put("paybill", it) }
            account?.let { put("account", it) }
            put("amount", amount ?: "")
        }
    }

    private val SEND_CUE = Regex("""(?i)\b(?:send|tuma|transfer)\b""")
    private val LIPA = Regex("""(?i)lipa\s*na\s*m-?\s?pesa""")
    private val STANDALONE_NUMBER = Regex("""(?<![\d,.])\d{5,7}(?![\d,.])""")
    private val AMOUNT_CUE = Regex("""(?i)\b(?:amount|amt|kiasi|total)\s*[:\-]?\s*(?:kshs?|kes)?\.?\s*(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d{1,2}))?""")

    fun parse(text: String): Payment {
        val clean = text.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return Payment(Type.UNKNOWN)
        val entities = EntityExtractor.extract(clean)
        val amount = amountOf(clean, entities)
        val payee = entities.firstOrNull { it.kind == EntityKind.BUSINESS || it.kind == EntityKind.PERSON }?.value

        entities.firstOrNull { it.kind == EntityKind.PAYBILL }?.let {
            return Payment(Type.PAYBILL, paybill = it.extra["paybill"], account = it.extra["account"], amount = amount, payee = payee)
        }
        entities.firstOrNull { it.kind == EntityKind.TILL }?.let {
            return Payment(Type.TILL, till = it.value, amount = amount, payee = payee)
        }
        val phone = entities.firstOrNull { it.kind == EntityKind.PHONE && it.value.startsWith("0") }?.value
        // A "Lipa na M-PESA" sign with one bare number on it is a till.
        if (LIPA.containsMatchIn(clean) && phone == null) {
            val numbers = STANDALONE_NUMBER.findAll(clean).map { it.value }
                .filter { n -> amount == null || n != amount }.distinct().toList()
            if (numbers.size == 1) return Payment(Type.TILL, till = numbers.first(), amount = amount, payee = payee)
        }
        if (phone != null) return Payment(Type.SEND_MONEY, phone = phone, amount = amount, payee = payee)
        return Payment(Type.UNKNOWN, amount = amount)
    }

    private fun amountOf(text: String, entities: List<Entity>): String? {
        entities.firstOrNull { it.kind == EntityKind.AMOUNT }?.let { return it.value }
        val m = AMOUNT_CUE.find(text) ?: return null
        val whole = m.groupValues[1].replace(",", "")
        val cents = m.groupValues[2].takeIf { it.isNotEmpty() && it.trimEnd('0').isNotEmpty() }
        return if (cents != null) "$whole.${cents.padEnd(2, '0')}" else whole
    }

    /**
     * Accepts a payment once the same details were read in [needed] of the last [window] frames.
     * Not thread-safe: feed it from the camera's single analysis thread.
     */
    class Consensus(private val needed: Int = 3, private val window: Int = 5) {
        private val recent = ArrayDeque<String>()
        private val byPrint = HashMap<String, Payment>()

        /** Adds one frame's result; returns the payment once it is confirmed, else null. */
        fun offer(payment: Payment): Payment? {
            val print = if (payment.isValid) payment.fingerprint else ""
            recent.addLast(print)
            if (payment.isValid) byPrint[print] = payment
            while (recent.size > window) recent.removeFirst()
            if (!payment.isValid) return null
            return if (recent.count { it == print } >= needed) payment else null
        }

        /** 0..1: how close the current reading is to being confirmed (for the progress ring). */
        fun progress(): Float {
            val best = recent.filter { it.isNotEmpty() }.groupingBy { it }.eachCount().values.maxOrNull() ?: 0
            return (best.toFloat() / needed).coerceAtMost(1f)
        }

        fun reset() {
            recent.clear()
            byPrint.clear()
        }
    }
}
