package dev.patrickgold.florisboard.scanner

import dev.hushhboard.nlp.entities.PaymentParser
import dev.patrickgold.florisboard.ussd.TransactionType

// Added QR_MONEY to handle Mode 3 toggling
enum class ScanMode { OCR_TEXT, OCR_MONEY, QR_CODE, QR_MONEY }

data class ParsedResult(
    val type: TransactionType,
    val data: Map<String, String>
)

/**
 * Payment details from scanned or selected text. Delegates to the tested [PaymentParser]
 * (lib/nlp), which reads tills, paybills with accounts, phone numbers and amounts from their
 * labels instead of guessing from any 5–7 digit number.
 */
object MobileMoneyParser {
    fun parse(text: String): ParsedResult = fromPayment(PaymentParser.parse(text))

    fun fromPayment(p: PaymentParser.Payment): ParsedResult {
        val type = when (p.type) {
            PaymentParser.Type.SEND_MONEY -> TransactionType.SEND_MONEY
            PaymentParser.Type.TILL -> TransactionType.TILL
            PaymentParser.Type.PAYBILL -> TransactionType.PAYBILL
            PaymentParser.Type.UNKNOWN -> TransactionType.UNKNOWN
        }
        return if (type == TransactionType.UNKNOWN) ParsedResult(type, emptyMap()) else ParsedResult(type, p.toFields())
    }
}
