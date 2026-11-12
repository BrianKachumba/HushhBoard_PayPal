package dev.patrickgold.florisboard.money

import android.content.Context
import dev.patrickgold.florisboard.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Result of a server call: either data or a human-readable error. */
sealed class ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>()
    data class Err(val code: String, val message: String) : ApiResult<Nothing>()
}

/** Live USD->KES rate from our server: PayPal's own quote when available ("paypal"), else today's market rate. */
data class FxRate(val rate: Double, val source: String) {
    val label: String get() = if (source == "paypal") "PayPal rate" else "today's rate"
}

data class PayPalStatus(val connected: Boolean, val email: String?, val name: String?)
data class PayPalPayment(val id: String, val status: String, val amount: String?, val currency: String?, val kesEstimate: Long?, val approveUrl: String = "")
data class PayPalRequest(val id: String, val approveUrl: String, val shareText: String)

/** A product found by Channel3, with its best live offer. */
data class ShopProduct(
    val id: String, val title: String, val brand: String, val image: String,
    val price: Double, val currency: String, val store: String, val url: String, val inStock: Boolean, val offerCount: Int,
)

/**
 * Talks to the HushhBoard money server (see /server). The PayPal client secret lives only there;
 * the app identifies itself with a random per-install device token.
 */
object MoneyApi {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS) // free hosting may need a cold start
        .readTimeout(45, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json".toMediaType()

    val baseUrl: String get() = BuildConfig.HUSHH_MONEY_API.trimEnd('/')

    private suspend fun call(ctx: Context, method: String, path: String, body: JSONObject? = null, idempotencyKey: String? = null): ApiResult<JSONObject> =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(baseUrl + path)
                    .header("X-Hushh-Device", MoneyStore.deviceToken(ctx))
                    .apply { MoneyStore.accounts.value.paypal?.token?.let { header("X-Hushh-Link", it) } }
                    .apply { idempotencyKey?.let { header("Idempotency-Key", it) } }
                    .method(method, body?.toString()?.toRequestBody(JSON) ?: if (method == "POST") "{}".toRequestBody(JSON) else null)
                    .build()
                http.newCall(req).execute().use { res ->
                    val text = res.body?.string().orEmpty()
                    val json = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
                    if (res.isSuccessful) ApiResult.Ok(json)
                    else {
                        val code = json.optString("error", "HTTP_${res.code}")
                        // The saved PayPal link is gone at PayPal: forget it so "Connect PayPal" shows again.
                        if (code == "PAYPAL_NOT_CONNECTED") MoneyStore.setPayPal(ctx, null)
                        ApiResult.Err(code, friendly(code, json.optString("message")))
                    }
                }
            } catch (e: Exception) {
                ApiResult.Err("NETWORK", "Can't reach HushhBoard right now. Check your internet and try again.")
            }
        }

    private fun friendly(code: String, message: String): String = when {
        code == "PAYPAL_NOT_CONNECTED" -> "Connect your PayPal first (Wallet > Add account > PayPal)."
        code.contains("PAYEE_ACCOUNT", true) -> "That PayPal email can't receive payments. Check it and try again."
        code.contains("INSTRUMENT_DECLINED", true) -> "PayPal declined this payment. Try another funding source in PayPal."
        code.contains("PAYER_CANNOT_PAY", true) -> "PayPal can't complete this payment from your account."
        message.isNotBlank() -> message
        else -> "Something went wrong. Please try again."
    }

    suspend fun status(ctx: Context): ApiResult<PayPalStatus> = when (val r = call(ctx, "GET", "/api/paypal/status")) {
        is ApiResult.Ok -> ApiResult.Ok(PayPalStatus(r.value.optBoolean("connected"), r.value.optString("email").ifBlank { null }.takeIf { it != "null" }, r.value.optString("name").takeIf { it.isNotBlank() && it != "null" }))
        is ApiResult.Err -> r
    }

    /** Returns PayPal's approval URL; the user approves on PayPal's own pages. */
    suspend fun connect(ctx: Context): ApiResult<String> = when (val r = call(ctx, "POST", "/api/paypal/connect")) {
        is ApiResult.Ok -> r.value.optString("approveUrl").takeIf { it.isNotBlank() }?.let { ApiResult.Ok(it) } ?: ApiResult.Err("NO_URL", "PayPal didn't return a link. Try again.")
        is ApiResult.Err -> r
    }

    suspend fun disconnect(ctx: Context): ApiResult<Unit> = when (val r = call(ctx, "POST", "/api/paypal/disconnect")) {
        is ApiResult.Ok -> ApiResult.Ok(Unit)
        is ApiResult.Err -> r
    }

    suspend fun send(ctx: Context, toEmail: String, amount: Double, currency: String, note: String): ApiResult<PayPalPayment> {
        val body = JSONObject().put("to", toEmail).put("amount", amount).put("currency", currency).put("note", note)
        return when (val r = call(ctx, "POST", "/api/paypal/send", body, idempotencyKey = UUID.randomUUID().toString())) {
            is ApiResult.Ok -> {
                val p = PayPalPayment(
                    r.value.optString("id"), r.value.optString("status"), r.value.optString("amount"),
                    r.value.optString("currency"), r.value.optLong("kesEstimate").takeIf { it > 0 },
                    r.value.optString("approveUrl"),
                )
                MoneyStore.recordActivity(ctx, MoneyActivity(p.id, "send", p.status, amount, currency, toEmail, note, System.currentTimeMillis()))
                ApiResult.Ok(p)
            }
            is ApiResult.Err -> r
        }
    }

    suspend fun request(ctx: Context, amount: Double, currency: String, note: String, from: String): ApiResult<PayPalRequest> {
        val body = JSONObject().put("amount", amount).put("currency", currency).put("note", note).put("from", from)
        return when (val r = call(ctx, "POST", "/api/paypal/request", body)) {
            is ApiResult.Ok -> {
                val req = PayPalRequest(r.value.optString("id"), r.value.optString("approveUrl"), r.value.optString("shareText"))
                MoneyStore.recordActivity(ctx, MoneyActivity(req.id, "request", "WAITING", amount, currency, from, note, System.currentTimeMillis()))
                ApiResult.Ok(req)
            }
            is ApiResult.Err -> r
        }
    }

    /** Channel3 product search (through our server, so the API key stays off the phone). */
    /** Last rate we got (shared by the Pay screen and Shop in this process). */
    @Volatile var lastFx: FxRate? = null

    suspend fun fx(ctx: Context): ApiResult<FxRate> = when (val r = call(ctx, "GET", "/api/fx")) {
        is ApiResult.Ok -> {
            val rate = r.value.optDouble("rate", 0.0)
            if (rate > 0) FxRate(rate, r.value.optString("source", "market")).also { lastFx = it }.let { ApiResult.Ok(it) }
            else ApiResult.Err("FX_UNAVAILABLE", "Can't get today's exchange rate right now.")
        }
        is ApiResult.Err -> r
    }

    suspend fun products(ctx: Context, query: String): ApiResult<List<ShopProduct>> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        return when (val r = call(ctx, "GET", "/api/products?q=$q")) {
            is ApiResult.Ok -> {
                val arr = r.value.optJSONArray("items")
                ApiResult.Ok((0 until (arr?.length() ?: 0)).mapNotNull { i ->
                    val p = arr!!.getJSONObject(i)
                    val o = p.optJSONObject("offer") ?: return@mapNotNull null
                    ShopProduct(
                        p.optString("id"), p.optString("title"), p.optString("brand"), p.optString("image"),
                        o.optDouble("price", 0.0), o.optString("currency", "USD"), o.optString("store"), o.optString("url"),
                        o.optBoolean("inStock"), p.optInt("offerCount", 1),
                    )
                })
            }
            is ApiResult.Err -> r
        }
    }

    /** Re-check any PayPal requests still waiting for the payer. */
    suspend fun refreshActivity(ctx: Context) {
        val waiting = MoneyStore.accounts.value.activity.filter { it.status != "COMPLETED" && it.status != "VOIDED" }.take(10)
        for (a in waiting) {
            val r = call(ctx, "GET", "/api/paypal/orders/${a.id}")
            if (r is ApiResult.Ok) {
                val status = r.value.optString("status")
                val shown = when (status) { "COMPLETED", "VOIDED" -> status; "APPROVED" -> "APPROVED"; else -> a.status }
                if (shown != a.status) MoneyStore.updateActivityStatus(ctx, a.id, shown, if (a.kind == "request") r.value.optString("payer") else null)
            }
        }
    }

    /**
     * Check the saved PayPal link is still valid. Only clears it when the server answers that it isn't;
     * a network problem keeps the link.
     */
    suspend fun syncPayPal(ctx: Context): PayPalStatus? {
        if (MoneyStore.accounts.value.paypal == null) return null
        val r = status(ctx)
        if (r is ApiResult.Ok && !r.value.connected) MoneyStore.setPayPal(ctx, null)
        return (r as? ApiResult.Ok)?.value
    }
}
