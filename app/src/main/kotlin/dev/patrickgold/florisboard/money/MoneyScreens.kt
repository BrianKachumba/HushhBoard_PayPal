package dev.patrickgold.florisboard.money

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.patrickgold.florisboard.HushhTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val PayPalBlue = Color(0xFF0070E0)
private val Warn = Color(0xFFFFB300)
private val Card = Color(0xFF15171A)
private val CardLine = Color.White.copy(alpha = 0.07f)
private val Muted = Color(0xFF9AA0A6)

/** Re-check PayPal with the server whenever the screen comes back (e.g. after PayPal's pages or the Pay screen). */
@Composable
private fun SyncPayPalOnResume() {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) { MoneyStore.reload(ctx); scope.launch { MoneyApi.syncPayPal(ctx); MoneyApi.refreshActivity(ctx) } } }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
}

// ───────────────────────── Setup step 4: connect your money ─────────────────────────

@Composable
fun ConnectAccountsSetupScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val accounts by MoneyStore.accounts.collectAsState()
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { MoneyStore.load(ctx) }
    SyncPayPalOnResume()
    val groups = remember(accounts) { Payees.groups(accounts) }

    Column(Modifier.fillMaxSize().background(HushhTheme.Bg).statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(4) { Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(50)).background(HushhTheme.Accent)) }
        }
        Spacer(Modifier.height(10.dp))
        Text("Step 4 of 4", color = Color.Gray, fontSize = 12.sp)
        Spacer(Modifier.height(28.dp))
        Text("Connect your money", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(
            "Add what you pay with. On the keyboard's Pay screen they're one tap away - no typing, no logging in again.",
            fontSize = 15.sp, color = Color.Gray, lineHeight = 21.sp, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (groups.isEmpty()) {
                // Three big one-tap tiles
                listOf(
                    Triple(Brands.networkLogo(Network.SAFARICOM), "M-PESA or Airtel Money", "Your phone number"),
                    Triple(Brands.bankLogo("equity"), "Bank account", "Equity, KCB, Co-op, Absa and more"),
                    Triple(Brands.PAYPAL, "PayPal", "Log in on PayPal's page, once"),
                ).forEach { (logo, t, sub) ->
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(18.dp)).background(Card)
                            .border(1.dp, CardLine, RoundedCornerShape(18.dp)).clickable { adding = true }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BrandLogo(logo, 44.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text(sub, color = Muted, fontSize = 12.sp)
                        }
                        Icon(Icons.Rounded.Add, null, tint = HushhTheme.Accent)
                    }
                }
            } else {
                AccountGroups(groups)
                AddAccountTile { adding = true }
            }
        }
        Button(
            onClick = { MoneyStore.markSetupDone(ctx); onDone() },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(56.dp), shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = HushhTheme.Accent),
        ) {
            Text(if (groups.isEmpty()) "Skip for now" else "Start using HushhBoard", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black)
        }
        Spacer(Modifier.height(16.dp))
    }
    if (adding) AddAccountSheet(SheetPalette.Dark, onDismiss = { adding = false }) { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }
}

// ───────────────────────── Home tab ─────────────────────────

@Composable
fun MoneyHomeScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val accounts by MoneyStore.accounts.collectAsState()
    var showRequest by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var allActivity by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { MoneyStore.load(ctx) }
    SyncPayPalOnResume()
    LaunchedEffect(accounts.paypal) { if (accounts.paypal != null) MoneyApi.refreshActivity(ctx) }
    val groups = remember(accounts) { Payees.groups(accounts) }

    Column(Modifier.fillMaxSize().background(HushhTheme.Bg).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Wallet", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(Card).border(1.dp, CardLine, CircleShape).clickable { adding = true },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Add, "Add account", tint = Color.White) }
        }
        Spacer(Modifier.height(18.dp))

        HeroCard(groups)
        Spacer(Modifier.height(22.dp))

        // Quick actions
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            QuickAction("Pay", Icons.Rounded.NorthEast, HushhTheme.Accent) {
                ctx.startActivity(Intent(ctx, dev.patrickgold.florisboard.ussd.UssdConfirmationActivity::class.java))
            }
            QuickAction("Request", Icons.Rounded.CallReceived, PayPalBlue) {
                if (accounts.paypal != null) showRequest = true
                else Toast.makeText(ctx, "Connect PayPal first", Toast.LENGTH_SHORT).show().also { adding = true }
            }
            QuickAction("Shop", Icons.Rounded.ShoppingBag, Color(0xFFFFB74D)) { ShopActivity.launch(ctx, fromKeyboard = false) }
        }
        Spacer(Modifier.height(28.dp))

        SectionHeader("Accounts", if (groups.isNotEmpty()) "${groups.sumOf { it.options.size }}" else null)
        if (groups.isEmpty()) {
            EmptyCard("Add your M-PESA number, a bank or PayPal to pay from the keyboard.", "Add account") { adding = true }
        } else {
            AccountGroups(groups)
        }

        if (accounts.activity.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            SectionHeader("Recent", null, action = if (accounts.activity.size > 3) (if (allActivity) "Less" else "See all") else null) { allActivity = !allActivity }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card).border(1.dp, CardLine, RoundedCornerShape(20.dp)).animateContentSize()) {
                accounts.activity.take(if (allActivity) 15 else 3).forEachIndexed { i, a ->
                    if (i > 0) HorizontalDivider(color = CardLine, modifier = Modifier.padding(start = 64.dp))
                    ActivityRow(a)
                }
            }
        }
        Spacer(Modifier.height(36.dp))
    }

    if (adding) AddAccountSheet(SheetPalette.Dark, onDismiss = { adding = false }) { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }
    if (showRequest) RequestPayPalDialog(onDismiss = { showRequest = false }) { amount, note ->
        showRequest = false
        scope.launch {
            when (val r = MoneyApi.request(ctx, amount, "USD", note, "")) {
                is ApiResult.Ok -> ctx.startActivity(
                    Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, r.value.shareText), "Send PayPal request")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                is ApiResult.Err -> Toast.makeText(ctx, r.message, Toast.LENGTH_LONG).show()
            }
        }
    }
}

/** The "card" at the top: what's ready on the keyboard, with the logos of everything connected. */
@Composable
private fun HeroCard(groups: List<PayeeGroup>) {
    Box(
        Modifier.fillMaxWidth().height(156.dp).clip(RoundedCornerShape(26.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF0E3B2A), Color(0xFF0A1F1A), Color(0xFF0B1220))))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(26.dp))
            .padding(20.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Keyboard, null, tint = HushhTheme.Accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("ON YOUR KEYBOARD", color = HushhTheme.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (groups.isEmpty()) "Nothing connected yet" else "Pay from any chat",
                color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            )
            Text(
                if (groups.isEmpty()) "Add an account to start" else "Tap Pay on the toolbar - ${groups.joinToString(", ") { it.name }}",
                color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            // Overlapping logos
            Row {
                groups.take(6).forEachIndexed { i, g ->
                    Box(Modifier.offset(x = (-10 * i).dp).border(2.dp, Color(0xFF0B1A16), RoundedCornerShape(12.dp))) { BrandLogo(g.logo, 34.dp) }
                }
                if (groups.size > 6) Text("+${groups.size - 6}", color = Color.White, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterVertically))
            }
        }
    }
}

@Composable
private fun QuickAction(label: String, icon: ImageVector, tint: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(8.dp)) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SectionHeader(title: String, count: String?, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        count?.let {
            Spacer(Modifier.width(8.dp))
            Text(it, color = Muted, fontSize = 13.sp)
        }
        Spacer(Modifier.weight(1f))
        action?.let { Text(it, color = HushhTheme.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable(onClick = onAction)) }
    }
}

@Composable
private fun EmptyCard(text: String, button: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card).border(1.dp, CardLine, RoundedCornerShape(20.dp)).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(14.dp))
        Button(onClick = onClick, shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = HushhTheme.Accent)) {
            Text(button, color = Color.Black, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun AddAccountTile(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(18.dp)).border(1.dp, CardLine, RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(16.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Add, null, tint = HushhTheme.Accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("Add another account", color = HushhTheme.Accent, fontWeight = FontWeight.SemiBold)
    }
}

// ───────────────────────── Accounts: one row per provider, expand to manage ─────────────────────────

@Composable
fun AccountGroups(groups: List<PayeeGroup>) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card).border(1.dp, CardLine, RoundedCornerShape(20.dp))) {
        groups.forEachIndexed { i, g ->
            if (i > 0) HorizontalDivider(color = CardLine, modifier = Modifier.padding(start = 70.dp))
            AccountGroupRow(g)
        }
    }
}

@Composable
private fun AccountGroupRow(g: PayeeGroup) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<PayeeOption?>(null) }
    val arrow by animateFloatAsState(if (open) 180f else 0f, label = "arrow")
    Column(Modifier.animateContentSize()) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            BrandLogo(g.logo, 40.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(g.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(g.primary.label + if (g.options.size > 1) "  ·  +${g.options.size - 1} more" else "", color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (g.kind == SourceKind.PAYPAL) {
                Text("Connected", color = PayPalBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 6.dp))
            }
            Icon(Icons.Rounded.ExpandMore, null, tint = Muted, modifier = Modifier.rotate(arrow))
        }
        AnimatedVisibility(open) {
            Column(Modifier.fillMaxWidth().padding(start = 70.dp, end = 12.dp, bottom = 10.dp)) {
                g.options.forEach { o ->
                    val isPrimary = o.id == g.primaryId
                    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(o.label, color = Color.White, fontSize = 14.sp)
                            if (o.holder.isNotBlank()) Text(o.holder, color = Muted, fontSize = 11.sp)
                        }
                        if (g.kind != SourceKind.PAYPAL) {
                            if (isPrimary) Text(
                                "Primary", color = HushhTheme.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(HushhTheme.Accent.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 3.dp),
                            ) else Text(
                                "Make primary", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { MoneyStore.setPrimary(ctx, o.id) }.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                        IconButton(onClick = { confirm = o }) { Icon(Icons.Rounded.DeleteOutline, "Remove", tint = Muted, modifier = Modifier.size(20.dp)) }
                    }
                }
                if (g.kind == SourceKind.BANK) Text(
                    "Pay into this bank today through M-PESA. Direct bank payments switch on when the bank opens its API.",
                    color = Muted.copy(alpha = 0.8f), fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp, end = 8.dp),
                )
            }
        }
    }
    confirm?.let { o ->
        val isPayPal = g.kind == SourceKind.PAYPAL
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (isPayPal) "Disconnect PayPal?" else "Remove ${g.name}?") },
            text = { Text(o.label) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    when (g.kind) {
                        SourceKind.PAYPAL -> scope.launch { MoneyApi.disconnect(ctx); MoneyStore.setPayPal(ctx, null) }
                        SourceKind.BANK -> MoneyStore.removeBank(ctx, o.id)
                        else -> MoneyStore.removePhone(ctx, o.id)
                    }
                }) { Text(if (isPayPal) "Disconnect" else "Remove", color = Color(0xFFE53935)) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ActivityRow(a: MoneyActivity) {
    val incoming = a.kind == "request"
    val handoff = a.kind == "handoff"
    val done = a.status == "COMPLETED"
    val tint = if (handoff) Color(0xFF3AA935) else PayPalBlue
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(
                when { handoff -> Icons.Rounded.OpenInNew; incoming -> Icons.Rounded.CallReceived; else -> Icons.Rounded.CallMade },
                null, tint = tint, modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                when {
                    handoff -> a.note.ifBlank { "Finished in ${a.counterparty}" }
                    incoming -> "Request" + if (a.counterparty.isNotBlank()) " · ${a.counterparty}" else ""
                    else -> "Sent to ${a.counterparty}"
                },
                color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                (if (!handoff && a.note.isNotBlank()) a.note + " · " else if (handoff) "In ${a.counterparty} · " else "") +
                    SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(a.at)),
                color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            if (a.amount > 0) Text(
                (if (incoming) "+" else if (handoff) "" else "-") + "${a.currency} ${if (a.currency == "KES") "%,.0f".format(a.amount) else "%.2f".format(a.amount)}",
                color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
            )
            Text(
                when { handoff -> "Handed off"; done -> "Paid"; a.status == "WAITING" -> "Waiting"; else -> a.status.lowercase().replaceFirstChar { it.uppercase() } },
                color = when { done -> HushhTheme.Accent; handoff -> Muted; else -> Warn }, fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun RequestPayPalDialog(onDismiss: () -> Unit, onRequest: (Double, String) -> Unit) {
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val value = amount.toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { BrandLogo(Brands.PAYPAL, 40.dp) },
        title = { Text("Request with PayPal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("We'll make a PayPal pay link you can send in any chat. The money lands in your PayPal.", fontSize = 13.sp)
                OutlinedTextField(amount, { amount = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Amount (USD)") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(note, { note = it.take(100) }, label = { Text("What it's for (optional)") }, singleLine = true)
                value?.let { Text("≈ KSh ${"%,.0f".format(it * 129.96)} at today's indicative rate", fontSize = 12.sp, color = Color.Gray) }
            }
        },
        confirmButton = { TextButton(enabled = (value ?: 0.0) >= 1.0, onClick = { onRequest(value!!, note) }) { Text("Create link") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

fun formatPhone(n: String) = if (n.length == 10) "${n.substring(0, 4)} ${n.substring(4, 7)} ${n.substring(7)}" else n

