package dev.patrickgold.florisboard.money

import com.google.mlkit.nl.entityextraction.Entity
import com.google.mlkit.nl.entityextraction.EntityExtraction
import com.google.mlkit.nl.entityextraction.EntityExtractionParams
import com.google.mlkit.nl.entityextraction.EntityExtractorOptions
import com.google.android.gms.tasks.Task
import dev.hushhboard.nlp.entities.EntityExtractor
import dev.hushhboard.nlp.entities.EntityKind
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * What the AI understood from a chat message, ready to fill the pay screen.
 * [tab] / [destination] / [preferPayPal] tell the screen which tab and payment method to open.
 */
data class SmartFill(
    val tab: PayTab,
    val destination: BankDestination,
    val preferPayPal: Boolean,
    val fields: Map<String, String>,
    val currency: String,
    val summary: String,
    val engine: String,
)

/**
 * HushhBoard money AI. Two layers:
 *  1. Google ML Kit Entity Extraction - an on-device ML model that finds money (with currency), emails,
 *     phone numbers and IBANs in free text. Runs offline; nothing leaves the phone.
 *  2. HushhBoard's Kenyan rules (tills, paybills + account, M-PESA formats, Sheng-style amounts) on top.
 * Then it decides intent: who to pay, which rail (PayPal vs M-PESA vs bank) and the amount.
 */
object MoneyAi {
    private val extractor by lazy {
        EntityExtraction.getClient(EntityExtractorOptions.Builder(EntityExtractorOptions.ENGLISH).build())
    }
    @Volatile private var modelReady = false

    private suspend fun <T> Task<T>.awaitOrNull(): T? = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resume(null) }
        addOnCanceledListener { cont.resume(null) }
    }

    /** Start the (small, one-time) model download early so the first Smart fill is instant. */
    suspend fun warmUp() {
        if (!modelReady) modelReady = withTimeoutOrNull(20_000) { extractor.downloadModelIfNeeded().awaitOrNull() } != null
    }

    private data class Found(
        var amount: String? = null, var currency: String? = null, var email: String? = null,
        var phone: String? = null, var iban: String? = null,
    )

    private suspend fun mlKit(text: String): Found? {
        warmUp()
        if (!modelReady) return null
        val annotations = withTimeoutOrNull(5_000) {
            extractor.annotate(EntityExtractionParams.Builder(text).build()).awaitOrNull()
        } ?: return null
        val f = Found()
        for (a in annotations) for (e in a.entities) when (e.type) {
            Entity.TYPE_MONEY -> if (f.amount == null) {
                val m = e.asMoneyEntity() ?: continue
                f.amount = if (m.fractionalPart > 0) "${m.integerPart}.${m.fractionalPart.toString().padStart(2, '0')}" else "${m.integerPart}"
                f.currency = normalizeCurrency(m.unnormalizedCurrency)
            }
            Entity.TYPE_EMAIL -> if (f.email == null) f.email = a.annotatedText.trim()
            Entity.TYPE_PHONE -> if (f.phone == null) f.phone = EntityExtractor.normalizeKenyanPhone(a.annotatedText)
            Entity.TYPE_IBAN -> if (f.iban == null) f.iban = a.annotatedText.filter { it.isLetterOrDigit() }
        }
        return f
    }

    private fun normalizeCurrency(raw: String?): String = when (raw?.trim()?.lowercase()) {
        "$", "usd", "us$", "dollar", "dollars", "bucks" -> "USD"
        "€", "eur", "euro", "euros" -> "EUR"
        "£", "gbp", "pound", "pounds" -> "GBP"
        else -> "KES"
    }

    private val USD_RULE = Regex("""(?i)(?:\$|usd|us\$)\s?(\d+(?:[.,]\d{1,2})?)|(\d+(?:[.,]\d{1,2})?)\s?(?:usd|dollars?|bucks)\b""")
    private val PAYPAL_WORD = Regex("""(?i)\bpay\s?pal\b|\bpaypal\.me/""")
    private val BANK_WORD = Regex("""(?i)\b(equity|kcb|co-?op(?:erative)?|absa|ncba|stanbic|standard chartered|i&m|dtb|diamond trust|family bank)\b""")
    private val BANK_ACC = Regex("""(?i)(?:a/?c|acc(?:ount)?(?:\s*(?:no|number))?)\s*[:.#-]?\s*(\d{6,20})""")

    suspend fun understand(text: String): SmartFill? {
        val clean = text.take(2_000)
        if (clean.isBlank()) return null
        val ml = mlKit(clean)
        val rules = EntityExtractor.extract(clean)

        val usd = USD_RULE.find(clean)
        val currency = when {
            usd != null -> "USD"
            ml?.currency != null && ml.currency != "KES" -> ml.currency!!
            else -> "KES"
        }
        val amount = usd?.let { (it.groupValues[1].ifBlank { it.groupValues[2] }).replace(',', '.') }
            ?: ml?.amount
            ?: rules.firstOrNull { it.kind == EntityKind.AMOUNT }?.value
        val email = ml?.email ?: rules.firstOrNull { it.kind == EntityKind.EMAIL }?.value
        val phone = ml?.phone ?: rules.firstOrNull { it.kind == EntityKind.PHONE && it.value.startsWith("0") }?.value
        val till = rules.firstOrNull { it.kind == EntityKind.TILL }?.value
        val paybill = rules.firstOrNull { it.kind == EntityKind.PAYBILL }
        val bankName = BANK_WORD.find(clean)?.value?.let { Banks.match(it)?.name }
        val bankAcc = BANK_ACC.find(clean)?.groupValues?.get(1)
        val mentionsPayPal = PAYPAL_WORD.containsMatchIn(clean)
        val engine = if (ml != null) "ML Kit on-device model + HushhBoard rules" else "HushhBoard rules"

        val amt = amount?.let { mapOf("amount" to it) } ?: emptyMap()
        return when {
            // "Send me $50 on PayPal: jane@x.com" -> pay a PayPal account (Bank tab, PayPal receiver)
            email != null && (mentionsPayPal || currency != "KES") -> SmartFill(
                PayTab.BANK, BankDestination.PAYPAL, preferPayPal = true, fields = amt + ("ppEmail" to email),
                currency = if (currency == "KES") "USD" else currency,
                summary = "Pay ${amountText(amount, currency)} to $email on PayPal", engine = engine,
            )
            paybill != null && bankName == null -> SmartFill(
                PayTab.PAYBILL, BankDestination.BANK_ACCOUNT, false,
                amt + ("paybill" to paybill.value) + (paybill.extra["account"]?.let { mapOf("account" to it) } ?: emptyMap()),
                "KES", "Pay paybill ${paybill.value}${paybill.extra["account"]?.let { " acc $it" } ?: ""}", engine,
            )
            bankName != null && bankAcc != null -> SmartFill(
                PayTab.BANK, BankDestination.BANK_ACCOUNT, mentionsPayPal, amt + ("bankName" to bankName) + ("bankAccount" to bankAcc),
                currency, "Send to $bankName account $bankAcc", engine,
            )
            till != null -> SmartFill(PayTab.TILL, BankDestination.BANK_ACCOUNT, false, amt + ("till" to till), "KES", "Pay till $till", engine)
            phone != null -> SmartFill(
                PayTab.PHONE, BankDestination.BANK_ACCOUNT, mentionsPayPal, amt + ("phone" to phone), currency,
                "Send ${amountText(amount, currency)} to $phone", engine,
            )
            email != null -> SmartFill(
                PayTab.BANK, BankDestination.PAYPAL, true, amt + ("ppEmail" to email), "USD",
                "Pay $email on PayPal", engine,
            )
            amount != null -> SmartFill(PayTab.PHONE, BankDestination.BANK_ACCOUNT, mentionsPayPal, amt, currency, "Amount ${amountText(amount, currency)}", engine)
            else -> null
        }
    }

    private fun amountText(amount: String?, currency: String) = when {
        amount == null -> "money"
        currency == "KES" -> "KSh $amount"
        else -> "$currency $amount"
    }
}
