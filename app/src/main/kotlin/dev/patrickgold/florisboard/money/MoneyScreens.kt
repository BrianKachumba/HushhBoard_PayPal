package dev.patrickgold.florisboard.money

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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

/** Re-check PayPal with the server whenever the screen comes back (e.g. after PayPal's pages). */
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

// ───────────────────────── Setup step: connect your money accounts ─────────────────────────

@Composable
fun ConnectAccountsSetupScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val accounts by MoneyStore.accounts.collectAsState()
    LaunchedEffect(Unit) { MoneyStore.load(ctx) }
    SyncPayPalOnResume()

    Column(Modifier.fillMaxSize().background(HushhTheme.Bg).statusBarsPadding().navigationBarsPadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(28.dp))
            LinearProgressIndicator(
                progress = { 0.9f }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
                color = HushhTheme.Accent, trackColor = HushhTheme.Surface,
            )
            Spacer(Modifier.height(28.dp))
            Text("Connect your money", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(
                "Add the accounts you pay with. Next time you tap Pay on the keyboard, they're one tap away - no re-typing, no logging in again.",
                fontSize = 14.sp, color = HushhTheme.TextSecondary, modifier = Modifier.padding(top = 6.dp, bottom = 20.dp),
            )
            AccountsEditor(accounts)
            Spacer(Modifier.height(16.dp))
        }
        Button(
            onClick = { MoneyStore.markSetupDone(ctx); onDone() },
            modifier = Modifier.fillMaxWidth().padding(20.dp).height(56.dp), shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = HushhTheme.Accent),
        ) {
            Text(if (accounts.isEmpty) "Skip for now" else "Start using HushhBoard", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black)
        }
    }
}

// ───────────────────────── Home tab: manage accounts + PayPal activity ─────────────────────────

@Composable
fun MoneyHomeScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val accounts by MoneyStore.accounts.collectAsState()
    val activity = accounts.activity
    var showRequest by remember { mutableStateOf(false) }
    var shopQuery by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { MoneyStore.load(ctx) }
    SyncPayPalOnResume()
    shopQuery?.let { q ->
        ShopDialog(q, onDismiss = { shopQuery = null }) { action ->
            if (action is ShopAction.PayWithPayPal) {
                val p = action.product
                ctx.startActivity(
                    Intent(ctx, dev.patrickgold.florisboard.ussd.UssdConfirmationActivity::class.java)
                        .putExtra(dev.patrickgold.florisboard.ussd.UssdConfirmationActivity.EXTRA_PAY_TYPE, "BANK_PAYPAL")
                        .putExtra(
                            dev.patrickgold.florisboard.ussd.UssdConfirmationActivity.EXTRA_PAY_FIELDS,
                            hashMapOf("amount" to "%.2f".format(Locale.US, p.price), "note" to p.title.take(100)),
                        )
                )
            }
            shopQuery = null
        }
    }
    LaunchedEffect(accounts.paypal) { if (accounts.paypal != null) MoneyApi.refreshActivity(ctx) }

    Column(Modifier.fillMaxSize().background(HushhTheme.Bg).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(24.dp))
        Text("Money", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(
            "Tap Pay on the keyboard toolbar in any app to use these.",
            fontSize = 13.sp, color = HushhTheme.TextSecondary, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )
        if (accounts.paypal != null) {
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickButton("Request via PayPal", Icons.Rounded.CallReceived, PayPalBlue, Modifier.weight(1f)) { showRequest = true }
                QuickButton("Open Pay", Icons.Rounded.Payments, HushhTheme.Accent, Modifier.weight(1f)) {
                    ctx.startActivity(Intent(ctx, dev.patrickgold.florisboard.ussd.UssdConfirmationActivity::class.java))
                }
            }
        }
        if (accounts.paypal != null) ShopSearchCard { shopQuery = it }
        AccountsEditor(accounts)
        if (accounts.paypal != null) {
            SectionTitle("PayPal activity", Icons.Rounded.History)
            if (activity.isEmpty()) {
                Text("No PayPal payments yet.", color = HushhTheme.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
            } else Card(colors = CardDefaults.cardColors(containerColor = HushhTheme.Surface), shape = RoundedCornerShape(16.dp)) {
                activity.take(10).forEachIndexed { i, a ->
                    if (i > 0) HorizontalDivider(color = HushhTheme.BorderSubtle)
                    ActivityRow(a)
                }
            }
            TextButton(onClick = { scope.launch { MoneyApi.refreshActivity(ctx) } }) {
                Text("Refresh", color = HushhTheme.Accent)
            }
        }
        Spacer(Modifier.height(32.dp))
    }

    if (showRequest) RequestPayPalDialog(onDismiss = { showRequest = false }) { amount, note ->
        showRequest = false
        scope.launch {
            when (val r = MoneyApi.request(ctx, amount, "USD", note, "")) {
                is ApiResult.Ok -> {
                    ctx.startActivity(
                        Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, r.value.shareText), "Send PayPal request")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                is ApiResult.Err -> Toast.makeText(ctx, r.message, Toast.LENGTH_LONG).show()
            }
        }
    }
}

@Composable
private fun ShopSearchCard(onSearch: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    Card(
        colors = CardDefaults.cardColors(containerColor = HushhTheme.Surface), shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, HushhTheme.BorderSubtle), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.ShoppingBag, null, tint = PayPalBlue, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Shop abroad with PayPal", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
            Text("Live prices from 25,000+ stores (Channel3). Pay or ask someone to chip in with PayPal.", color = HushhTheme.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { DarkField(q, { q = it.take(80) }, "e.g. Sony WH-1000XM5", KeyboardType.Text) }
                Spacer(Modifier.width(8.dp))
                FilledIconButton(onClick = { if (q.trim().length >= 2) onSearch(q.trim()) }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = PayPalBlue)) {
                    Icon(Icons.Rounded.Search, "Search", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun QuickButton(label: String, icon: ImageVector, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick, modifier = modifier.height(52.dp), shape = RoundedCornerShape(14.dp),
        color = tint.copy(alpha = 0.14f), border = BorderStroke(1.dp, tint.copy(alpha = 0.5f)),
    ) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun ActivityRow(a: MoneyActivity) {
    val incoming = a.kind == "request"
    val done = a.status == "COMPLETED"
    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(PayPalBlue.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Icon(if (incoming) Icons.Rounded.CallReceived else Icons.Rounded.CallMade, null, tint = PayPalBlue, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (incoming) "Request ${if (a.counterparty.isNotBlank()) "paid by ${a.counterparty}" else ""}".trim() else "Sent to ${a.counterparty}",
                color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                (if (a.note.isNotBlank()) a.note + " · " else "") + SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(a.at)),
                color = HushhTheme.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text((if (incoming) "+" else "-") + "${a.currency} ${"%.2f".format(a.amount)}", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(
                when { done -> "Paid ✓"; a.status == "WAITING" -> "Waiting"; else -> a.status.lowercase().replaceFirstChar { it.uppercase() } },
                color = if (done) HushhTheme.Accent else Warn, fontSize = 12.sp,
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
        title = { Text("Request money via PayPal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("We'll create a PayPal pay link you can send in any chat. The money lands in your PayPal.", fontSize = 13.sp)
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

// ───────────────────────── Shared editor (setup + home) ─────────────────────────

@Composable
fun AccountsEditor(accounts: MoneyAccounts) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // Phones
    SectionTitle("Mobile money", Icons.Rounded.PhoneAndroid)
    AccountCard {
        accounts.phones.forEachIndexed { i, p ->
            if (i > 0) HorizontalDivider(color = HushhTheme.BorderSubtle)
            AccountRow(
                title = p.network.wallet, subtitle = formatPhone(p.number),
                badge = p.network.label, badgeColor = if (p.network == Network.SAFARICOM) HushhTheme.Accent else Color(0xFFE53935),
            ) { MoneyStore.removePhone(ctx, p.id) }
        }
        AddPhoneRow(first = accounts.phones.isEmpty())
    }

    // Banks
    SectionTitle("Banks", Icons.Rounded.AccountBalance)
    AccountCard {
        accounts.banks.forEachIndexed { i, b ->
            if (i > 0) HorizontalDivider(color = HushhTheme.BorderSubtle)
            AccountRow(
                title = b.bank?.name ?: "Bank", subtitle = "•••• " + b.accountNumber.takeLast(4) + (if (b.holder.isNotBlank()) " · ${b.holder}" else ""),
                badge = "Pending API", badgeColor = Warn,
            ) { MoneyStore.removeBank(ctx, b.id) }
        }
        AddBankRow(first = accounts.banks.isEmpty())
    }
    Text(
        "Banks are saved on this phone. Pay into them today through M-PESA. \"Pending API\" means one-tap direct connect goes live when the bank opens its API to HushhBoard.",
        color = HushhTheme.TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp),
    )

    // PayPal
    SectionTitle("PayPal", Icons.Rounded.Public)
    AccountCard {
        val pp = accounts.paypal
        if (pp != null) {
            AccountRow(title = "PayPal", subtitle = pp.email, badge = "Connected", badgeColor = PayPalBlue, removeLabel = "Disconnect") {
                scope.launch {
                    MoneyApi.disconnect(ctx) // revoke the PayPal vault token; forget it locally either way
                    MoneyStore.setPayPal(ctx, null)
                }
            }
        } else {
            var busy by remember { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(
                    "PayPal opens its own secure login. HushhBoard never sees your password - PayPal gives us a secure link so you can pay later without logging in again.",
                    color = HushhTheme.TextSecondary, fontSize = 12.sp,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        busy = true
                        scope.launch {
                            PayPalConnect.start(ctx)?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() }
                            busy = false
                        }
                    },
                    enabled = !busy, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PayPalBlue),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    else Text("Connect PayPal", fontWeight = FontWeight.SemiBold, color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, icon: ImageVector) {
    Row(Modifier.padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = HushhTheme.Accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(text.uppercase(), color = HushhTheme.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
    }
}

@Composable
private fun AccountCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = HushhTheme.Surface), shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, HushhTheme.BorderSubtle), modifier = Modifier.fillMaxWidth(),
    ) { Column(content = content) }
}

@Composable
private fun AccountRow(title: String, subtitle: String, badge: String, badgeColor: Color, removeLabel: String = "Remove", onRemove: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    badge, color = badgeColor, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.border(1.dp, badgeColor.copy(alpha = 0.6f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
            Text(subtitle, color = HushhTheme.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = { confirm = true }) { Icon(Icons.Rounded.DeleteOutline, removeLabel, tint = HushhTheme.TextSecondary) }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("$removeLabel $title?") },
        text = { Text(subtitle) },
        confirmButton = { TextButton(onClick = { confirm = false; onRemove() }) { Text(removeLabel, color = Color(0xFFE53935)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
}

@Composable
private fun AddPhoneRow(first: Boolean) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(first) }
    var number by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    if (!first) HorizontalDivider(color = HushhTheme.BorderSubtle)
    AnimatedVisibility(!open) { AddRow("Add phone number") { open = true } }
    AnimatedVisibility(open) {
        Column(Modifier.padding(16.dp)) {
            val network = if (number.length >= 4) Network.of(number.filter { it.isDigit() }.let { if (it.startsWith("254")) "0" + it.drop(3) else it }) else null
            DarkField(number, { number = it.filter { c -> c.isDigit() || c == '+' }.take(13); error = null }, "Safaricom or Airtel number", KeyboardType.Phone)
            Text(
                error ?: network?.let { if (it == Network.OTHER) "Unknown network" else "${it.label} · ${it.wallet}" } ?: "e.g. 0712 345 678",
                color = if (error != null) Color(0xFFE53935) else HushhTheme.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                if (!first) TextButton(onClick = { open = false; number = "" }) { Text("Cancel", color = HushhTheme.TextSecondary) }
                TextButton(onClick = {
                    error = MoneyStore.addPhone(ctx, number)
                    if (error == null) { number = ""; open = false }
                }) { Text("Add number", color = HushhTheme.Accent, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun AddBankRow(first: Boolean) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var bank by remember { mutableStateOf<Bank?>(null) }
    var account by remember { mutableStateOf("") }
    var holder by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    if (!first) HorizontalDivider(color = HushhTheme.BorderSubtle)
    AnimatedVisibility(!open) { AddRow("Add a bank") { open = true } }
    AnimatedVisibility(open) {
        Column(Modifier.padding(16.dp)) {
            Text("Choose your bank", color = HushhTheme.TextSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            // Simple wrapping chip grid
            Banks.all.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { b ->
                        val sel = bank == b
                        Text(
                            b.name, fontSize = 12.sp, color = if (sel) Color.Black else Color.White, maxLines = 1,
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                                .background(if (sel) HushhTheme.Accent else HushhTheme.SurfaceAlt)
                                .clickable { bank = b; error = null }.padding(horizontal = 10.dp, vertical = 9.dp),
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            AnimatedVisibility(bank != null) {
                Column {
                    Spacer(Modifier.height(6.dp))
                    DarkField(account, { account = it.filter { c -> c.isLetterOrDigit() }.take(20); error = null }, "Account number", KeyboardType.Number)
                    Spacer(Modifier.height(8.dp))
                    DarkField(holder, { holder = it.take(40) }, "Account name (optional)", KeyboardType.Text)
                }
            }
            error?.let { Text(it, color = Color(0xFFE53935), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { open = false; bank = null; account = ""; holder = "" }) { Text("Cancel", color = HushhTheme.TextSecondary) }
                TextButton(enabled = bank != null, onClick = {
                    error = MoneyStore.addBank(ctx, bank!!.code, account, holder)
                    if (error == null) { open = false; bank = null; account = ""; holder = "" }
                }) { Text("Add bank", color = HushhTheme.Accent, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun AddRow(label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.AddCircleOutline, null, tint = HushhTheme.Accent)
        Spacer(Modifier.width(10.dp))
        Text(label, color = HushhTheme.Accent, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DarkField(value: String, onChange: (String) -> Unit, label: String, type: KeyboardType) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = type), modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = HushhTheme.Accent,
            focusedBorderColor = HushhTheme.Accent, unfocusedBorderColor = HushhTheme.BorderStrong,
            focusedLabelColor = HushhTheme.Accent, unfocusedLabelColor = HushhTheme.TextSecondary,
        ),
    )
}

fun formatPhone(n: String) = if (n.length == 10) "${n.substring(0, 4)} ${n.substring(4, 7)} ${n.substring(7)}" else n
