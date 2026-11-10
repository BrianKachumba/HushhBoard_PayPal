package dev.patrickgold.florisboard.ussd

object UssdCodeGenerator {
    fun generateAirtelCode(type: TransactionType, data: Map<String, String>): String {
        val amount = data["amount"] ?: return ""

        return when (type) {
            TransactionType.SEND_MONEY -> {
                val phone = data["phone"] ?: return ""
                "*334*4*1*$phone*$amount#"
            }
            TransactionType.PAYBILL -> {
                val paybill = data["paybill"] ?: return ""
                val acc = data["account"] ?: return ""
                "*334*6*1*1*$paybill*$acc*$amount#"
            }
            TransactionType.TILL -> {
                val till = data["till"] ?: return ""
                "*334*6*2*1*$till*$amount#"
            }
            else -> ""
        }
    }
}
