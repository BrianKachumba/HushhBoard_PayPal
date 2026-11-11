package dev.patrickgold.florisboard.money

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.patrickgold.florisboard.scanner.HushhInsert
import dev.patrickgold.florisboard.ussd.UssdConfirmationActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private val PayPalBlue = Color(0xFF0070E0)
private val PayPalNavy = Color(0xFF003087)
private val Ink = Color(0xFF111418)
private val Muted = Color(0xFF7A808A)
private val Line = Color(0xFFE8EAED)
private val Soft = Color(0xFFF4F5F7)
private const val KES_PER_USD = 129.96

/**
 * HushhBoard Shop: a small sheet that opens over whatever app you are in (one tap on the keyboard's bag key or the
 * shopping suggestion). Channel3 finds the product and live prices in 25,000+ stores; from a result you can pay with
 * PayPal, drop a PayPal request into the chat ("chip in for this"), share the deal, or open the store.
 * Nothing is paid without an explicit tap on the next screen.
 */
class ShopActivity : ComponentActivity() {
    companion object {
        const val EXTRA_QUERY = "query"
        const val EXTRA_FROM_KEYBOARD = "from_keyboard"

        fun launch(ctx: Context, query: String = "", fromKeyboard: Boolean) {
            ctx.startActivity(
                Intent(ctx, ShopActivity::class.java)
                    .putExtra(EXTRA_QUERY, query)
                    .putExtra(EXTRA_FROM_KEYBOARD, fromKeyboard)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        MoneyStore.reload(this)
        val fromKeyboard = intent.getBooleanExtra(EXTRA_FROM_KEYBOARD, false)
        val initial = intent.getStringExtra(EXTRA_QUERY).orEmpty()
        setContent {
            ShopPanel(
                initialQuery = initial,
                onClose = { finish() },
                onInsert = { text ->
                    if (fromKeyboard) {
                        HushhInsert.send(this, text)
                        finish()
                    } else {
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Send"))
                    }
                },
                onPay = { p ->
                    startActivity(
                        Intent(this, UssdConfirmationActivity::class.java)
                            .putExtra(UssdConfirmationActivity.EXTRA_PAY_TYPE, "BANK_PAYPAL")
                            .putExtra(UssdConfirmationActivity.EXTRA_PAY_FIELDS, hashMapOf("amount" to "%.2f".format(Locale.US, p.price), "note" to p.title.take(100)))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    finish()
                },
            )
        }
    }
}

private object ShopRecents {
    private const val PREFS = "hushh_shop"
    fun get(ctx: Context): List<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("recent", "").orEmpty().split('\n').filter { it.isNotBlank() }
    fun add(ctx: Context, q: String) {
        val list = (listOf(q) + get(ctx).filterNot { it.equals(q, true) }).take(6)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("recent", list.joinToString("\n")).apply()
    }
}

private val SUGGESTIONS = listOf("Sony WH-1000XM5", "AirPods Pro", "Kindle Paperwhite", "Nike Air Force 1", "PS5 controller")

@Composable
fun ShopPanel(initialQuery: String, onClose: () -> Unit, onInsert: (String) -> Unit, onPay: (ShopProduct) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val hasPayPal = MoneyStore.accounts.collectAsState().value.paypal != null

    var query by remember { mutableStateOf(initialQuery) }
    var searched by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var slow by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<ShopProduct>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var recents by remember { mutableStateOf(ShopRecents.get(ctx)) }
    var shown by remember { mutableStateOf(false) }

    fun search(q: String = query) {
        val text = q.trim()
        if (text.length < 2) return
        query = text
        focusManager.clearFocus()
        searched = text; loading = true; error = null; slow = false
        ShopRecents.add(ctx, text); recents = ShopRecents.get(ctx)
        scope.launch {
            val slowJob = launch { delay(4000); if (loading) slow = true }
            when (val r = MoneyApi.products(ctx, text)) {
                is ApiResult.Ok -> { results = r.value; error = if (r.value.isEmpty()) "No products found. Try a shorter name." else null }
                is ApiResult.Err -> { results = emptyList(); error = r.message }
            }
            loading = false; slowJob.cancel()
        }
    }

    LaunchedEffect(Unit) {
        shown = true
        if (initialQuery.isNotBlank()) search(initialQuery) else runCatching { delay(250); focus.requestFocus() }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.40f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(shown, enter = slideInVertically(tween(260, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(200))) {
            Column(
                Modifier.fillMaxWidth().fillMaxHeight(0.78f)
                    .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)).background(Color.White)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .navigationBarsPadding().imePadding(),
            ) {
                Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(36.dp, 4.dp).clip(CircleShape).background(Line))
                }
                // Header
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(PayPalBlue.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.ShoppingBag, null, tint = PayPalBlue, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Shop", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("Live prices from 25,000+ stores", color = Muted, fontSize = 11.sp)
                    }
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close", tint = Muted) }
                }
                // Search field
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).height(48.dp)
                        .clip(RoundedCornerShape(14.dp)).background(Soft).padding(start = 14.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Search, null, tint = Muted, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text("Search any product", color = Muted, fontSize = 15.sp)
                        BasicTextField(
                            value = query, onValueChange = { query = it.take(80) }, singleLine = true,
                            textStyle = TextStyle(color = Ink, fontSize = 15.sp), cursorBrush = SolidColor(PayPalBlue),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { search() }),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                    }
                    if (query.isNotEmpty()) IconButton(onClick = { query = ""; runCatching { focus.requestFocus() } }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Rounded.Cancel, "Clear", tint = Muted.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
                    }
                    Box(
                        Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(if (query.trim().length >= 2) PayPalBlue else PayPalBlue.copy(alpha = 0.3f))
                            .clickable { search() },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.ArrowForward, "Search", tint = Color.White, modifier = Modifier.size(18.dp)) }
                }

                when {
                    loading -> Column(Modifier.padding(horizontal = 16.dp)) {
                        if (slow) Text("Waking up the price server, one moment…", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp, start = 4.dp))
                        repeat(3) { SkeletonCard(); Spacer(Modifier.height(10.dp)) }
                    }
                    searched == null -> StartState(recents, onPick = { search(it) })
                    error != null && results.isEmpty() -> Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.SearchOff, null, tint = Muted, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(error!!, color = Ink, fontSize = 14.sp)
                        TextButton(onClick = { search(searched!!) }) { Text("Try again", color = PayPalBlue, fontWeight = FontWeight.SemiBold) }
                    }
                    else -> {
                        Row(Modifier.padding(horizontal = 20.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${results.size} results", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            if (!hasPayPal) Text("Connect PayPal in HushhBoard to pay", color = PayPalBlue, fontSize = 11.sp)
                        }
                        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(results, key = { it.id + it.url }) { p ->
                                ProductCard(
                                    p, canPayPal = hasPayPal && p.currency in setOf("USD", "EUR", "GBP"), busy = busyId == p.id,
                                    onPay = { onPay(p) },
                                    onRequest = {
                                        busyId = p.id
                                        scope.launch {
                                            when (val r = MoneyApi.request(ctx, p.price, p.currency, p.title.take(90), "")) {
                                                is ApiResult.Ok -> {
                                                    Toast.makeText(ctx, "PayPal request added to your chat", Toast.LENGTH_SHORT).show()
                                                    onInsert(r.value.shareText + "\n" + p.url)
                                                }
                                                is ApiResult.Err -> Toast.makeText(ctx, r.message, Toast.LENGTH_LONG).show()
                                            }
                                            busyId = null
                                        }
                                    },
                                    onShare = { onInsert("${p.title} - ${money(p)} at ${p.store}\n${p.url}") },
                                    onOpen = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun money(p: ShopProduct) = "${if (p.currency == "USD") "$" else p.currency + " "}${"%,.2f".format(p.price)}"

@Composable
private fun StartState(recents: List<String>, onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        if (recents.isNotEmpty()) {
            Text("Recent", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 20.dp, bottom = 8.dp))
            ChipRow(recents, Icons.Rounded.History, onPick)
            Spacer(Modifier.height(16.dp))
        }
        Text("Popular", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 20.dp, bottom = 8.dp))
        ChipRow(SUGGESTIONS, Icons.Rounded.TrendingUp, onPick)
        Spacer(Modifier.height(24.dp))
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            BrandLogo(Brands.PAYPAL, 28.dp)
            Spacer(Modifier.width(10.dp))
            Text("Pay with PayPal, or send a PayPal request so a friend can chip in.", color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ChipRow(items: List<String>, icon: androidx.compose.ui.graphics.vector.ImageVector, onPick: (String) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items) { q ->
            Row(
                Modifier.clip(RoundedCornerShape(50)).border(1.dp, Line, RoundedCornerShape(50)).clickable { onPick(q) }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, tint = Muted, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(q, color = Ink, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun SkeletonCard() {
    val t = rememberInfiniteTransition(label = "skeleton")
    val a by t.animateFloat(0.45f, 0.9f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).border(1.dp, Line, RoundedCornerShape(18.dp)).padding(12.dp).alpha(a)) {
        Box(Modifier.size(84.dp).clip(RoundedCornerShape(12.dp)).background(Soft))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Box(Modifier.fillMaxWidth(0.9f).height(14.dp).clip(RoundedCornerShape(4.dp)).background(Soft))
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth(0.5f).height(14.dp).clip(RoundedCornerShape(4.dp)).background(Soft))
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth(0.7f).height(28.dp).clip(RoundedCornerShape(8.dp)).background(Soft))
        }
    }
}

@Composable
private fun ProductCard(
    p: ShopProduct, canPayPal: Boolean, busy: Boolean,
    onPay: () -> Unit, onRequest: () -> Unit, onShare: () -> Unit, onOpen: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).border(1.dp, Line, RoundedCornerShape(18.dp)).clickable(onClick = onOpen).padding(12.dp)) {
        Row {
            AsyncImage(
                model = p.image, contentDescription = p.title, contentScale = ContentScale.Fit,
                modifier = Modifier.size(84.dp).clip(RoundedCornerShape(12.dp)).background(Soft).padding(6.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.title, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(money(p), color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    if (p.currency == "USD") {
                        Spacer(Modifier.width(6.dp))
                        Text("≈ KSh ${"%,.0f".format(p.price * KES_PER_USD)}", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 2.dp))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(if (p.inStock) Color(0xFF1E9E5A) else Color(0xFFE0A100)))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        p.store + (if (p.offerCount > 1) " · best of ${p.offerCount} stores" else ""),
                        color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canPayPal) {
                Row(
                    Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(50)).background(PayPalNavy).clickable(onClick = onPay),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    BrandLogo(Brands.PAYPAL, 18.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("Pay", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Row(
                    Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(50)).border(1.dp, PayPalBlue, RoundedCornerShape(50)).clickable(enabled = !busy, onClick = onRequest),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(16.dp), color = PayPalBlue, strokeWidth = 2.dp)
                    else Text("Request", color = PayPalBlue, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            } else Spacer(Modifier.weight(1f))
            RoundIcon(Icons.Rounded.Share, "Share in chat", onShare)
            RoundIcon(Icons.Rounded.OpenInNew, "Open store", onOpen)
        }
    }
}

@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(38.dp).clip(CircleShape).background(Soft).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Ink, modifier = Modifier.size(18.dp))
    }
}
