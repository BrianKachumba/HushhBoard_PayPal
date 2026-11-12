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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.animation.togetherWith
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
                    Triple(Brands.MOBILE, "Mobile money", "M-PESA, Airtel Money or T-Kash number"),
                    Triple(Brands.BANK, "Bank account", "Equity, KCB, Co-op, Absa and more"),
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

        WalletCard(groups, accounts)
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

/** One account on the Wallet card's stack. */
private data class StackItem(val group: PayeeGroup, val option: PayeeOption)

/**
 * The Wallet card: the account on top of the stack (bottom-left) is the one shown. Swipe the card left or right,
 * or tap a tile, to bring another account to the top. The eye hides or shows private details.
 * Balances are only shown where we can truly know them: PayPal shows what moved through HushhBoard; banks and
 * mobile money say their balance arrives with the provider's API.
 */
@Composable
private fun WalletCard(groups: List<PayeeGroup>, accounts: MoneyAccounts) {
    val ctx = LocalContext.current
    val items = remember(groups) {
        val paypalFirst = groups.sortedBy { if (it.kind == SourceKind.PAYPAL) 0 else 1 }
        paypalFirst.flatMap { g -> g.options.sortedByDescending { it.id == g.primaryId }.map { StackItem(g, it) } }
    }
    var top by remember(items.size) { mutableStateOf(0) }
    var hidden by remember { mutableStateOf(true) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val shape = RoundedCornerShape(26.dp)

    if (items.isEmpty()) {
        Box(
            Modifier.fillMaxWidth().height(190.dp).clip(shape)
                .background(Brush.linearGradient(listOf(Color(0xFF1B1F24), Color(0xFF0E1013)))).border(1.dp, CardLine, shape).padding(22.dp),
        ) {
            Column {
                Text("ON YOUR KEYBOARD", color = HushhTheme.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Spacer(Modifier.height(8.dp))
                Text("Nothing connected yet", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Add M-PESA, a bank or PayPal to pay from any chat.", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
            }
        }
        return
    }

    val cur = items[top.coerceIn(0, items.lastIndex)]
    val isPayPal = cur.group.kind == SourceKind.PAYPAL
    fun step(d: Int) { top = (top + d + items.size) % items.size; haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove) }

    Box(
        Modifier.fillMaxWidth().height(210.dp).clip(shape)
            .background(
                if (isPayPal) Brush.linearGradient(listOf(Color(0xFF0B1F4D), Color(0xFF081430), Color(0xFF0B0F1A)))
                else Brush.linearGradient(listOf(Color(0xFF1B1F24), Color(0xFF111418), Color(0xFF0B0D10)))
            )
            .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
            .pointerInput(items.size) {
                var dx = 0f
                detectHorizontalDragGestures(onDragStart = { dx = 0f }, onDragEnd = {
                    if (kotlin.math.abs(dx) > 48.dp.toPx()) step(if (dx < 0) 1 else -1)
                }) { _, d -> dx += d }
            }
            .padding(20.dp),
    ) {
        androidx.compose.animation.AnimatedContent(
            targetState = cur, label = "wallet-card",
            transitionSpec = { (androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInHorizontally { it / 6 }) togetherWith androidx.compose.animation.fadeOut() },
        ) { it2 ->
            val g = it2.group; val o = it2.option
            Column(Modifier.fillMaxSize()) {
                // Header: provider + eye
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (g.kind == SourceKind.PAYPAL) BrandLogo(Brands.PAYPAL, 44.dp)
                    else BrandLogo(if (g.kind == SourceKind.BANK) Brands.BANK else Brands.MOBILE, 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(g.name, color = Color.White, fontSize = if (g.kind == SourceKind.PAYPAL) 22.sp else 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(
                            when (g.kind) { SourceKind.PAYPAL -> "Connected · sandbox"; SourceKind.BANK -> "Bank account"; else -> "Mobile money" } +
                                if (o.id == g.primaryId && g.options.size > 1) " · Primary" else "",
                            color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp,
                        )
                    }
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f)).clickable { hidden = !hidden },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(if (hidden) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (hidden) "Show details" else "Hide details", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.height(14.dp))
                // Account line, like a card number
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (hidden) maskForCard(g.kind, o.value) else if (g.kind == SourceKind.BANK) o.value.chunked(4).joinToString(" ") else o.label,
                        color = Color.White, fontSize = if (g.kind == SourceKind.PAYPAL) 16.sp else 20.sp, fontWeight = FontWeight.SemiBold,
                        letterSpacing = if (g.kind == SourceKind.PAYPAL) 0.sp else 1.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    if (!hidden) {
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Rounded.ContentCopy, "Copy", tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(16.dp).clickable {
                            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("account", o.value))
                            Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                        })
                    }
                }
                val holder = when (g.kind) { SourceKind.PAYPAL -> accounts.paypal?.name.orEmpty(); else -> o.holder }
                if (holder.isNotBlank()) Text(if (hidden) holder.take(1) + "•••" else holder, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.weight(1f))
                // Bottom: stack (left) + balance (right)
                Row(verticalAlignment = Alignment.Bottom) {
                    Row(Modifier.weight(1f)) {
                        val shown = (0 until minOf(items.size, 5)).map { (top + it) % items.size }.reversed()
                        shown.forEachIndexed { i, idx ->
                            val isTop = idx == top
                            Box(
                                Modifier.offset(x = (-12 * i).dp)
                                    .border(if (isTop) 2.dp else 1.5.dp, if (isTop) Color.White else Color(0xFF0B0F1A), RoundedCornerShape(11.dp))
                                    .clickable { top = idx }
                            ) { BrandLogo(items[idx].group.logo, if (isTop) 34.dp else 28.dp) }
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        if (g.kind == SourceKind.PAYPAL) {
                            val (sent, got) = paypalTotals(accounts.activity)
                            Text("Through HushhBoard", color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp)
                            Text(if (hidden) "USD ••••" else "↑ $${"%,.2f".format(sent)}  ↓ $${"%,.2f".format(got)}", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        } else {
                            Text("Balance", color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp)
                            Text(if (hidden) "KSh ••••" else "With ${g.name}'s API", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
    if (items.size > 1) Text(
        "Swipe the card to switch accounts", color = Muted.copy(alpha = 0.7f), fontSize = 11.sp,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

private fun maskForCard(kind: SourceKind, v: String): String = when (kind) {
    SourceKind.PAYPAL -> v.substringBefore('@').take(1) + "•••@" + v.substringAfter('@', "")
    SourceKind.BANK -> "•••• •••• " + v.takeLast(4)
    else -> if (v.length >= 10) v.take(2) + "•• ••• " + v.takeLast(3) else "••••"
}

/** USD sent and received through HushhBoard this month (completed only) - real numbers from PayPal orders. */
private fun paypalTotals(activity: List<MoneyActivity>): Pair<Double, Double> {
    val cal = java.util.Calendar.getInstance().apply { set(java.util.Calendar.DAY_OF_MONTH, 1); set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0) }
    val month = activity.filter { it.at >= cal.timeInMillis && it.status == "COMPLETED" && it.currency == "USD" }
    return month.filter { it.kind == "send" }.sumOf { it.amount } to month.filter { it.kind == "request" }.sumOf { it.amount }
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
            kindIcon(g.kind)?.let { Icon(it, null, tint = Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
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

