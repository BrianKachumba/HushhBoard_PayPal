package dev.patrickgold.florisboard.money

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

private val PayPalBlue = Color(0xFF0070E0)
private const val KES_PER_USD = 129.96

/** What the user chose to do with a product. */
sealed class ShopAction {
    /** Pay someone (e.g. the friend who is buying it) this amount with PayPal. */
    data class PayWithPayPal(val product: ShopProduct) : ShopAction()
}

/**
 * Agentic shopping: Channel3 finds the product and live prices; the user picks one and HushhBoard prepares
 * a PayPal payment, a PayPal request ("chip in for this"), or opens the store. Nothing is paid without a tap.
 */
@Composable
fun ShopDialog(query: String, onDismiss: () -> Unit, onAction: (ShopAction) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember(query) { mutableStateOf<ApiResult<List<ShopProduct>>?>(null) }
    LaunchedEffect(query) { state = MoneyApi.products(ctx, query) }
    val hasPayPal = MoneyStore.accounts.collectAsState().value.paypal != null

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.ShoppingBag, null, tint = PayPalBlue) },
        title = { Text("\"$query\"", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            when (val s = state) {
                null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = PayPalBlue)
                    Spacer(Modifier.width(12.dp)); Text("Finding live prices…")
                }
                is ApiResult.Err -> Text(s.message)
                is ApiResult.Ok -> if (s.value.isEmpty()) Text("No products found. Try a shorter name.") else
                    Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            if (hasPayPal) "Live prices from Channel3 · pay or request with PayPal"
                            else "Live prices from Channel3 · connect PayPal on Home to pay or request",
                            fontSize = 11.sp, color = Color.Gray,
                        )
                        s.value.forEach { p ->
                            ProductCard(
                                p, hasPayPal,
                                onPay = { onAction(ShopAction.PayWithPayPal(p)) },
                                onRequest = { scope.launch { requestFor(ctx, p) } },
                                onOpen = { openStore(ctx, p) },
                            )
                        }
                    }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun ProductCard(p: ShopProduct, hasPayPal: Boolean, onPay: () -> Unit, onRequest: () -> Unit, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFFF4F6F8)).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = p.image, contentDescription = p.title, contentScale = ContentScale.Fit,
                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(Color.White),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(p.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${p.currency} ${"%,.2f".format(p.price)}" + if (p.currency == "USD") " · ≈ KSh ${"%,.0f".format(p.price * KES_PER_USD)}" else "",
                    fontSize = 13.sp, color = PayPalBlue, fontWeight = FontWeight.Bold,
                )
                Text(
                    p.store + (if (p.inStock) " · in stock" else " · check stock") + (if (p.offerCount > 1) " · ${p.offerCount} stores" else ""),
                    fontSize = 11.sp, color = Color.Gray, maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (hasPayPal && p.currency in setOf("USD", "EUR", "GBP")) {
                Button(onClick = onPay, contentPadding = PaddingValues(horizontal = 10.dp), colors = ButtonDefaults.buttonColors(containerColor = PayPalBlue), modifier = Modifier.height(34.dp)) {
                    Text("Pay", fontSize = 12.sp)
                }
                OutlinedButton(onClick = onRequest, contentPadding = PaddingValues(horizontal = 10.dp), modifier = Modifier.height(34.dp)) {
                    Text("Request", fontSize = 12.sp, color = PayPalBlue)
                }
            }
            TextButton(onClick = onOpen, contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.height(34.dp)) {
                Text("Open store", fontSize = 12.sp)
            }
        }
    }
}

private fun openStore(ctx: Context, p: ShopProduct) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** "Chip in for this": a PayPal request for the product's price, shared to any chat. */
private suspend fun requestFor(ctx: Context, p: ShopProduct) {
    when (val r = MoneyApi.request(ctx, p.price, p.currency, p.title.take(90), "")) {
        is ApiResult.Ok -> ctx.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, r.value.shareText + "\n" + p.url),
                "Send PayPal request",
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        is ApiResult.Err -> Toast.makeText(ctx, r.message, Toast.LENGTH_LONG).show()
    }
}
