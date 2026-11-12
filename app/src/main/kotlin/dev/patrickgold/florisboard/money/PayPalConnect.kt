package dev.patrickgold.florisboard.money

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.size
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

/**
 * Receives hushhboard://paypal?result=... from our server after PayPal:
 *  - connected: saves the sealed PayPal link and returns to the app,
 *  - paid / pay_cancelled / pay_failed: shows the outcome over whatever app you were in, then gets out of the way.
 */
class PayPalReturnActivity : androidx.activity.ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        val result = uri?.getQueryParameter("result")
        MoneyStore.reload(this)
        when (result) {
            "paid", "pay_cancelled", "pay_failed" -> showPayment(result, uri)
            else -> handleConnect(result, uri)
        }
    }

    private fun handleConnect(result: String?, uri: Uri?) {
        val token = uri?.getQueryParameter("link").orEmpty()
        lifecycleScope.launch {
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

    private fun showPayment(result: String, uri: Uri?) {
        val order = uri?.getQueryParameter("order").orEmpty()
        val status = uri?.getQueryParameter("status").orEmpty()
        val paid = result == "paid" && status == "COMPLETED"
        if (order.isNotBlank()) MoneyStore.updateActivityStatus(this, order, if (paid) "COMPLETED" else if (result == "paid") status else "VOIDED", null)
        val amount = uri?.getQueryParameter("amount").orEmpty()
        val currency = uri?.getQueryParameter("currency").orEmpty()
        val to = uri?.getQueryParameter("to").orEmpty()
        setContent {
            PaymentResult(
                ok = paid,
                title = when { paid -> "Paid"; result == "pay_cancelled" -> "Payment cancelled"; result == "paid" -> "Payment ${status.lowercase()}"; else -> "Payment not completed" },
                body = when {
                    paid -> "$currency $amount sent to $to with PayPal."
                    result == "pay_cancelled" -> "Nothing was charged."
                    else -> "PayPal didn't complete it. Nothing was charged."
                },
                onDone = { finish() },
            )
        }
    }
}

@androidx.compose.runtime.Composable
private fun PaymentResult(ok: Boolean, title: String, body: String, onDone: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDone,
        icon = {
            androidx.compose.material3.Icon(
                if (ok) androidx.compose.material.icons.Icons.Rounded.CheckCircle else androidx.compose.material.icons.Icons.Rounded.Info,
                null, tint = if (ok) androidx.compose.ui.graphics.Color(0xFF07C160) else androidx.compose.ui.graphics.Color(0xFFFFA000),
                modifier = androidx.compose.ui.Modifier.size(androidx.compose.ui.unit.Dp(44f)),
            )
        },
        title = { androidx.compose.material3.Text(title) },
        text = { androidx.compose.material3.Text(body) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDone) { androidx.compose.material3.Text("Done") } },
    )
}
