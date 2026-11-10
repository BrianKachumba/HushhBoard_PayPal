/*
 * Copyright (C) 2026 HushhBoard
 * Licensed under the Apache License, Version 2.0.
 */

package dev.hushhboard.nlp.entities

/** Kinds of useful things found in copied or scanned text, in the order they are offered. */
enum class EntityKind(val label: String) {
    OTP("Code"),
    PAYBILL("Paybill"),
    TILL("Till"),
    PHONE("Phone"),
    EMAIL("Email"),
    URL("Link"),
    MPESA_CODE("M-Pesa code"),
    AMOUNT("Amount"),
    KRA_PIN("KRA PIN"),
    PERSON("Name"),
    BUSINESS("Business"),
    PLACE("Place"),
}

/**
 * One entity. [value] is what gets pasted (normalised: "0712345678", "1200", "https://…"),
 * [start]..[end] the span in the source text, [detail] a short description for the chip
 * ("Acc 12345", "Safaricom"), [extra] structured parts (e.g. "account" for a paybill).
 */
data class Entity(
    val kind: EntityKind,
    val value: String,
    val start: Int,
    val end: Int,
    val detail: String? = null,
    val extra: Map<String, String> = emptyMap(),
) {
    val range: IntRange get() = start until end
    fun overlaps(other: Entity): Boolean = start < other.end && other.start < end
}
