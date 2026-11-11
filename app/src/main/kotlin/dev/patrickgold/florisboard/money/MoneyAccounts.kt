package dev.patrickgold.florisboard.money

import android.content.Context
import dev.hushhboard.nlp.entities.EntityExtractor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class Network(val label: String, val wallet: String) {
    SAFARICOM("Safaricom", "M-PESA"),
    AIRTEL("Airtel", "Airtel Money"),
    TELKOM("Telkom", "T-Kash"),
    OTHER("Other", "Mobile money");

    companion object {
        fun of(phone: String): Network = when (EntityExtractor.carrierOf(phone)) {
            "Safaricom" -> SAFARICOM
            "Airtel" -> AIRTEL
            "Telkom" -> TELKOM
            else -> OTHER
        }
    }
}

/** A Kenyan bank. [paybill] is the bank's M-PESA paybill: pay it with the account number as the reference. */
data class Bank(val code: String, val name: String, val paybill: String)

object Banks {
    val all = listOf(
        Bank("equity", "Equity Bank", "247247"),
        Bank("kcb", "KCB Bank", "522522"),
        Bank("coop", "Co-operative Bank", "400200"),
        Bank("absa", "Absa Bank Kenya", "303030"),
        Bank("ncba", "NCBA Bank", "880100"),
        Bank("stanbic", "Stanbic Bank", "600100"),
        Bank("scb", "Standard Chartered", "329329"),
        Bank("im", "I&M Bank", "542542"),
        Bank("dtb", "Diamond Trust Bank", "516600"),
        Bank("family", "Family Bank", "222111"),
    )

    fun byCode(code: String) = all.firstOrNull { it.code == code }

    /** Loose match for a typed bank name ("equity", "KCB", "coop bank"...). */
    fun match(name: String): Bank? {
        val n = name.trim().lowercase()
        if (n.isEmpty()) return null
        return all.firstOrNull { b ->
            val bn = b.name.lowercase()
            bn.startsWith(n) || n.startsWith(bn.substringBefore(' ')) || n.contains(b.code)
        }
    }
}

data class PhoneLink(val id: String, val number: String) {
    val network: Network get() = Network.of(number)
    /** All numbers on one network share one payee bar (one "Safaricom", one "Airtel"). */
    val group: String get() = "net:" + network.name
}

data class BankLink(val id: String, val bankCode: String, val accountNumber: String, val holder: String) {
    val bank: Bank? get() = Banks.byCode(bankCode)
    /** All accounts at one bank share one payee bar (one "KCB"). */
    val group: String get() = "bank:" + bankCode
}

/** [token] is the server-sealed PayPal Vault reference. Useless without this phone's device token. */
data class PayPalLink(val email: String, val name: String, val token: String)

/** A PayPal payment or request made from this phone (kept locally; the server stores nothing). */
data class MoneyActivity(
    val id: String, val kind: String, val status: String, val amount: Double,
    val currency: String, val counterparty: String, val note: String, val at: Long,
)

data class MoneyAccounts(
    val phones: List<PhoneLink> = emptyList(),
    val banks: List<BankLink> = emptyList(),
    val paypal: PayPalLink? = null,
    val activity: List<MoneyActivity> = emptyList(),
    /** Payee group ("net:SAFARICOM", "bank:kcb") -> id of its primary account. */
    val primaries: Map<String, String> = emptyMap(),
) {
    /** The primary of a group: the one the user chose, else the first one they added. */
    fun primaryId(group: String): String? {
        val ids = phones.filter { it.group == group }.map { it.id } + banks.filter { it.group == group }.map { it.id }
        return primaries[group]?.takeIf { it in ids } ?: ids.firstOrNull()
    }
    fun isPrimary(id: String): Boolean {
        val g = phones.firstOrNull { it.id == id }?.group ?: banks.firstOrNull { it.id == id }?.group ?: return false
        return primaryId(g) == id
    }

    val isEmpty: Boolean get() = phones.isEmpty() && banks.isEmpty() && paypal == null

    fun isOwnPhone(number: String): Boolean {
        val n = EntityExtractor.normalizeKenyanPhone(number) ?: number
        return phones.any { it.number == n }
    }

    fun ownBank(bankCode: String?, account: String): BankLink? =
        banks.firstOrNull { it.accountNumber == account && (bankCode == null || it.bankCode == bankCode) }
}

/**
 * Local store for the user's linked money accounts, kept in one small JSON file in app storage.
 * A file (not SharedPreferences) because the pay screen runs in a separate process and must always
 * see what the app just saved. Phone and bank numbers stay on the phone; for PayPal we keep the
 * email plus a server-sealed link. No passwords are stored anywhere.
 */
object MoneyStore {
    private const val FILE = "hushh_money.json"
    private const val DEVICE_FILE = "hushh_device_id"

    private val _accounts = MutableStateFlow(MoneyAccounts())
    val accounts: StateFlow<MoneyAccounts> = _accounts.asStateFlow()
    private var loaded = false
    private val lock = Any()

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, FILE)

    fun load(ctx: Context): MoneyAccounts = synchronized(lock) {
        if (!loaded) {
            _accounts.value = decode(runCatching { file(ctx).readText() }.getOrNull())
            loaded = true
        }
        _accounts.value
    }

    /** Re-read from disk (other process may have written). */
    fun reload(ctx: Context): MoneyAccounts {
        synchronized(lock) { loaded = false }
        return load(ctx)
    }

    fun deviceToken(ctx: Context): String = synchronized(lock) {
        val f = File(ctx.applicationContext.filesDir, DEVICE_FILE)
        runCatching { f.readText().trim() }.getOrNull()?.takeIf { it.length >= 16 }
            ?: UUID.randomUUID().toString().also { f.writeText(it) }
    }

    private fun setupFlag(ctx: Context) = File(ctx.applicationContext.filesDir, "hushh_accounts_setup_done")
    fun isSetupDone(ctx: Context) = setupFlag(ctx).exists()
    fun markSetupDone(ctx: Context) { runCatching { setupFlag(ctx).writeText("1") } }

    private fun update(ctx: Context, f: (MoneyAccounts) -> MoneyAccounts) = synchronized(lock) {
        loaded = false
        load(ctx)
        val next = f(_accounts.value)
        _accounts.value = next
        val target = file(ctx)
        val tmp = File(target.parentFile, "$FILE.tmp")
        tmp.writeText(encode(next))
        tmp.renameTo(target)
    }

    /** Returns an error message, or null when added. */
    fun addPhone(ctx: Context, raw: String): String? {
        val n = EntityExtractor.normalizeKenyanPhone(raw) ?: return "Enter a Kenyan number like 0712 345 678"
        if (load(ctx).phones.any { it.number == n }) return "That number is already added"
        update(ctx) { it.copy(phones = it.phones + PhoneLink(UUID.randomUUID().toString(), n)) }
        return null
    }

    fun addBank(ctx: Context, bankCode: String, account: String, holder: String): String? {
        val acc = account.filter { it.isLetterOrDigit() }
        if (Banks.byCode(bankCode) == null) return "Pick a bank"
        if (acc.length < 6) return "Enter the full account number"
        if (load(ctx).banks.any { it.bankCode == bankCode && it.accountNumber == acc }) return "That account is already added"
        update(ctx) { it.copy(banks = it.banks + BankLink(UUID.randomUUID().toString(), bankCode, acc, holder.trim())) }
        return null
    }

    fun removePhone(ctx: Context, id: String) = update(ctx) { a -> a.copy(phones = a.phones.filterNot { it.id == id }, primaries = a.primaries.filterValues { it != id }) }
    fun removeBank(ctx: Context, id: String) = update(ctx) { a -> a.copy(banks = a.banks.filterNot { it.id == id }, primaries = a.primaries.filterValues { it != id }) }

    /** Makes this phone number / bank account the default of its payee bar. */
    fun setPrimary(ctx: Context, id: String) = update(ctx) { a ->
        val g = a.phones.firstOrNull { it.id == id }?.group ?: a.banks.firstOrNull { it.id == id }?.group
        if (g == null) a else a.copy(primaries = a.primaries + (g to id))
    }
    fun setPayPal(ctx: Context, link: PayPalLink?) = update(ctx) { it.copy(paypal = link) }

    fun recordActivity(ctx: Context, item: MoneyActivity) = update(ctx) { a ->
        a.copy(activity = (listOf(item) + a.activity.filterNot { it.id == item.id }).take(50))
    }

    fun updateActivityStatus(ctx: Context, id: String, status: String, counterparty: String?) = update(ctx) { a ->
        a.copy(activity = a.activity.map {
            if (it.id == id) it.copy(status = status, counterparty = counterparty?.takeIf { c -> c.isNotBlank() } ?: it.counterparty) else it
        })
    }

    private fun encode(a: MoneyAccounts): String = JSONObject().apply {
        put("phones", JSONArray(a.phones.map { JSONObject().put("id", it.id).put("number", it.number) }))
        put("banks", JSONArray(a.banks.map {
            JSONObject().put("id", it.id).put("bank", it.bankCode).put("account", it.accountNumber).put("holder", it.holder)
        }))
        a.paypal?.let { put("paypal", JSONObject().put("email", it.email).put("name", it.name).put("token", it.token)) }
        put("primaries", JSONObject().apply { a.primaries.forEach { (k, v) -> put(k, v) } })
        put("activity", JSONArray(a.activity.map {
            JSONObject().put("id", it.id).put("kind", it.kind).put("status", it.status).put("amount", it.amount)
                .put("currency", it.currency).put("counterparty", it.counterparty).put("note", it.note).put("at", it.at)
        }))
    }.toString()

    private fun decode(json: String?): MoneyAccounts {
        if (json.isNullOrBlank()) return MoneyAccounts()
        return runCatching {
            val o = JSONObject(json)
            fun objs(key: String) = o.optJSONArray(key)?.let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } } ?: emptyList()
            MoneyAccounts(
                phones = objs("phones").map { PhoneLink(it.getString("id"), it.getString("number")) },
                banks = objs("banks").map { BankLink(it.getString("id"), it.getString("bank"), it.getString("account"), it.optString("holder")) },
                paypal = o.optJSONObject("paypal")?.let { PayPalLink(it.optString("email"), it.optString("name"), it.optString("token")) }
                    ?.takeIf { it.token.isNotBlank() },
                primaries = o.optJSONObject("primaries")?.let { p -> p.keys().asSequence().associateWith { p.getString(it) } } ?: emptyMap(),
                activity = objs("activity").map {
                    MoneyActivity(
                        it.optString("id"), it.optString("kind"), it.optString("status"), it.optDouble("amount", 0.0),
                        it.optString("currency", "USD"), it.optString("counterparty"), it.optString("note"), it.optLong("at"),
                    )
                },
            )
        }.getOrDefault(MoneyAccounts())
    }
}
