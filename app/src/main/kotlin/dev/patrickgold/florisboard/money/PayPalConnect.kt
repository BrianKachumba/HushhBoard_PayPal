package dev.patrickgold.florisboard.money

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.lifecycle.lifecycleScope
import dev.patrickgold.florisboard.HushDashboardActivity
import kotlinx.coroutines.launch

/**
 * Opens PayPal's own approval pages (Custom Tab). PayPal handles the email, password and 2FA;
 * HushhBoard never sees them. When the user approves, PayPal sends them to our server, which turns the
 * approval into a Vault payment token and bounces back to hushhboard://paypal.
 */
object PayPalConnect {
    suspend fun start(ctx: Context): String? = when (val r = MoneyApi.connect(ctx)) {
        is ApiResult.Ok -> { openPayPalPage(ctx, r.value); null }
        is ApiResult.Err -> r.message
    }

    fun openPayPalPage(ctx: Context, url: String) {
        val tab = CustomTabsIntent.Builder().setShowTitle(true).build()
        tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { tab.launchUrl(ctx, Uri.parse(url)) }
            .onFailure { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/** Receives hushhboard://paypal?result=... after PayPal, syncs the link, and returns to the app. */
class PayPalReturnActivity : androidx.activity.ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        val result = uri?.getQueryParameter("result")
        val token = uri?.getQueryParameter("link").orEmpty()
        lifecycleScope.launch {
            MoneyStore.reload(this@PayPalReturnActivity)
            val msg = when {
                result == "connected" && token.isNotBlank() -> {
                    val email = uri?.getQueryParameter("email").orEmpty()
                    MoneyStore.setPayPal(this@PayPalReturnActivity, PayPalLink(email, uri?.getQueryParameter("name").orEmpty(), token))
                    "PayPal connected: $email"
                }
                result == "cancelled" -> "PayPal not connected (cancelled)"
                else -> "PayPal didn't connect. Please try again."
            }
            Toast.makeText(this@PayPalReturnActivity, msg, Toast.LENGTH_LONG).show()
            startActivity(
                Intent(this@PayPalReturnActivity, HushDashboardActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            setResult(Activity.RESULT_OK)
            finish()
        }
    }
}
