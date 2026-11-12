package dev.patrickgold.florisboard.money

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.patrickgold.florisboard.R
import kotlinx.coroutines.launch

// ───────────────────────── Brands ─────────────────────────

/**
 * How a provider is drawn. Only PayPal (the sponsor) uses its real logo; every bank and mobile-money provider is a
 * coloured tile with its initials, so the app ships no third-party trademarks. [icon] tiles are the two categories.
 */
data class Mark(val text: String, val color: Color, @DrawableRes val res: Int? = null, val icon: ImageVector? = null)

object Brands {
    private val palette = mapOf(
        "equity" to Color(0xFF8E3B46), "kcb" to Color(0xFF2E7D32), "coop" to Color(0xFF1565C0), "absa" to Color(0xFFAD1457),
        "ncba" to Color(0xFF37474F), "stanbic" to Color(0xFF283593), "scb" to Color(0xFF00897B), "im" to Color(0xFF6A1B9A),
        "dtb" to Color(0xFFEF6C00), "family" to Color(0xFF4E342E),
    )
    private val initials = mapOf(
        "equity" to "EQ", "kcb" to "KC", "coop" to "CO", "absa" to "AB", "ncba" to "NC", "stanbic" to "SB",
        "scb" to "SC", "im" to "IM", "dtb" to "DT", "family" to "FB",
    )

    fun bank(code: String): Mark = Mark(initials[code] ?: code.take(2).uppercase(), palette[code] ?: Color(0xFF546E7A))

    fun network(n: Network): Mark = when (n) {
        Network.SAFARICOM -> Mark("MP", Color(0xFF388E3C))
        Network.AIRTEL -> Mark("AM", Color(0xFFC62828))
        Network.TELKOM -> Mark("TK", Color(0xFF0277BD))
        Network.OTHER -> Mark("MM", Color(0xFF607D8B))
    }

    val PAYPAL = Mark("PP", Color(0xFF003087), res = R.drawable.logo_paypal)
    val MOBILE = Mark("", Color(0xFF2E7D5B), icon = Icons.Rounded.Phone)
    val BANK = Mark("", Color(0xFF455A64), icon = Icons.Rounded.AccountBalance)
}

/** Category icon for the right-hand side of a bar: phone = mobile money, building = bank. */
fun kindIcon(kind: SourceKind): ImageVector? = when (kind) {
    SourceKind.MPESA, SourceKind.AIRTEL -> Icons.Rounded.Phone
    SourceKind.BANK -> Icons.Rounded.AccountBalance
    else -> null
}

/** A provider tile in a rounded square: PayPal's logo, a category icon, or coloured initials. */
@Composable
fun BrandLogo(mark: Mark, size: Dp = 36.dp) {
    val shape = RoundedCornerShape(size * 0.28f)
    when {
        mark.res != null -> Image(
            painterResource(mark.res), null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(shape).border(0.5.dp, Color.Black.copy(alpha = 0.08f), shape),
        )
        else -> Box(Modifier.size(size).clip(shape).background(mark.color), contentAlignment = Alignment.Center) {
            if (mark.icon != null) Icon(mark.icon, null, tint = Color.White, modifier = Modifier.size(size * 0.55f))
            else Text(mark.text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * (if (mark.text.length > 2) 0.30f else 0.36f)).sp, maxLines = 1)
        }
    }
}

// ───────────────────────── Payee groups (one bar per provider) ─────────────────────────

/** One account inside a payee bar. [value] is the raw number used to pay; [label] is what we show. */
data class PayeeOption(val id: String, val value: String, val label: String, val holder: String = "")

/** One bar in the payee list: a provider and its accounts, with the primary first. */
data class PayeeGroup(
    val key: String,
    val kind: SourceKind,
    val name: String,
    val logo: Mark,
    val options: List<PayeeOption>,
    val primaryId: String,
) {
    val primary: PayeeOption get() = options.first { it.id == primaryId }
}

object Payees {
    /** Builds the bars from the saved accounts: one per network, one per bank, plus PayPal. */
    fun groups(a: MoneyAccounts): List<PayeeGroup> = buildList {
        val byNet = a.phones.groupBy { it.network }
        listOf(Network.SAFARICOM, Network.AIRTEL, Network.TELKOM, Network.OTHER).forEach { net ->
            val phones = byNet[net] ?: return@forEach
            val group = phones.first().group
            val primary = a.primaryId(group) ?: phones.first().id
            add(
                PayeeGroup(
                    key = group,
                    kind = if (net == Network.AIRTEL) SourceKind.AIRTEL else SourceKind.MPESA,
                    name = when (net) { Network.SAFARICOM -> "M-PESA"; Network.AIRTEL -> "Airtel Money"; Network.TELKOM -> "T-Kash"; Network.OTHER -> "Mobile money" },
                    logo = Brands.network(net),
                    options = phones.sortedByDescending { it.id == primary }.map { PayeeOption(it.id, it.number, formatPhone(it.number)) },
                    primaryId = primary,
                )
            )
        }
        a.banks.groupBy { it.bankCode }.forEach { (code, accts) ->
            val group = accts.first().group
            val primary = a.primaryId(group) ?: accts.first().id
            add(
                PayeeGroup(
                    key = group, kind = SourceKind.BANK, name = accts.first().bank?.name ?: "Bank",
                    logo = Brands.bank(code),
                    options = accts.sortedByDescending { it.id == primary }.map { PayeeOption(it.id, it.accountNumber, maskAccount(it.accountNumber), it.holder) },
                    primaryId = primary,
                )
            )
        }
        a.paypal?.let { add(PayeeGroup("paypal", SourceKind.PAYPAL, "PayPal", Brands.PAYPAL, listOf(PayeeOption("paypal", it.email, it.email)), "paypal")) }
    }

    /**
     * PayPal only appears where it can actually be used: never for Till or Paybill, and in the Phone tab
     * only for M-PESA numbers (PayPal pays out to M-PESA only). An unfinished number keeps it visible.
     */
    fun payPalAllowed(tab: PayTab, phone: String): Boolean = when (tab) {
        PayTab.TILL, PayTab.PAYBILL -> false
        PayTab.BANK -> true
        PayTab.PHONE -> {
            val digits = phone.filter { it.isDigit() }
            digits.length < 4 || Network.of(phone) == Network.SAFARICOM
        }
    }

    fun visible(groups: List<PayeeGroup>, tab: PayTab, phone: String): List<PayeeGroup> =
        if (payPalAllowed(tab, phone)) groups else groups.filterNot { it.kind == SourceKind.PAYPAL }
}

fun maskAccount(n: String) = if (n.length > 4) "•••• " + n.takeLast(4) else n

// ───────────────────────── Add an account (same flow in Home and on the Pay screen) ─────────────────────────

/** Colours for [AddAccountSheet], so it matches the dark app and the light pay screen. */
data class SheetPalette(val bg: Color, val surface: Color, val text: Color, val muted: Color, val accent: Color, val border: Color) {
    companion object {
        val Dark = SheetPalette(Color(0xFF121416), Color(0xFF1E2124), Color.White, Color(0xFFA0A0A0), Color(0xFF00E676), Color.White.copy(alpha = 0.14f))
        val Light = SheetPalette(Color.White, Color(0xFFF3F4F6), Color(0xFF111111), Color(0xFF8A8A8A), Color(0xFF07C160), Color(0xFFE4E4E4))
    }
}

private enum class AddKind { PHONE, BANK }

/**
 * Bottom sheet to add a phone number, a bank account or PayPal. Saves to [MoneyStore], so whatever is added here
 * shows up everywhere (Home and the keyboard's Pay screen) and is remembered.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAccountSheet(palette: SheetPalette, onDismiss: () -> Unit, onAdded: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val accounts by MoneyStore.accounts.collectAsState()
    var kind by remember { mutableStateOf<AddKind?>(null) }
    var bank by remember { mutableStateOf<Bank?>(null) }
    var number by remember { mutableStateOf("") }
    var holder by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focus = remember { FocusRequester() }

    fun reset() { kind = null; bank = null; number = ""; holder = ""; error = null }
    fun close() { scope.launch { sheet.hide(); onDismiss() } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = palette.bg, dragHandle = { BottomSheetDefaults.DragHandle(color = palette.muted.copy(alpha = 0.5f)) }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding().imePadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (kind != null) {
                    IconButton(onClick = { reset() }, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.ArrowBackIosNew, "Back", tint = palette.text, modifier = Modifier.size(16.dp)) }
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    when { kind == null -> "Add an account"; kind == AddKind.PHONE -> "Mobile money number"; bank == null -> "Choose your bank"; else -> bank!!.name },
                    color = palette.text, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(14.dp))
            AnimatedContent(targetState = Triple(kind, bank, 0), label = "add-account") { (k, b, _) ->
                Column {
                    when {
                        k == null -> {
                            ChoiceRow(palette, Brands.MOBILE, "Mobile money", "M-PESA, Airtel Money or T-Kash number") { kind = AddKind.PHONE }
                            Spacer(Modifier.height(10.dp))
                            ChoiceRow(palette, Brands.BANK, "Bank account", "Equity, KCB, Co-op, Absa and more") { kind = AddKind.BANK }
                            Spacer(Modifier.height(10.dp))
                            if (accounts.paypal == null) {
                                ChoiceRow(palette, Brands.PAYPAL, "PayPal", "Log in on PayPal's own page - we never see your password") {
                                    scope.launch { val e = PayPalConnect.start(ctx); if (e != null) error = e else close() }
                                }
                            } else {
                                ChoiceRow(palette, Brands.PAYPAL, "PayPal connected", accounts.paypal!!.email, enabled = false) {}
                            }
                        }
                        k == AddKind.PHONE -> {
                            val net = number.filter { it.isDigit() }.takeIf { it.length >= 4 }?.let { Network.of(it.let { d -> if (d.startsWith("254")) "0" + d.drop(3) else d }) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                BrandLogo(net?.let { Brands.network(it) } ?: Brands.MOBILE, 44.dp)
                                Spacer(Modifier.width(12.dp))
                                SheetField(palette, number, { number = it.filter { c -> c.isDigit() || c == '+' }.take(13); error = null }, "07XX XXX XXX", KeyboardType.Phone, focus, Modifier.weight(1f)) {
                                    error = MoneyStore.addPhone(ctx, number); if (error == null) { onAdded("Number added"); close() }
                                }
                            }
                            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                            Text(
                                error ?: net?.let { if (it == Network.OTHER) "We don't recognise this network" else "${it.label} · ${it.wallet}" } ?: "Safaricom, Airtel or Telkom",
                                color = if (error != null) Color(0xFFE53935) else palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, start = 56.dp),
                            )
                            SheetButton(palette, "Add number", number.filter { it.isDigit() }.length >= 9) {
                                error = MoneyStore.addPhone(ctx, number); if (error == null) { onAdded("Number added"); close() }
                            }
                        }
                        b == null -> {
                            // Bank grid with logos
                            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                                Banks.all.chunked(3).forEach { row ->
                                    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        row.forEach { bk ->
                                            Column(
                                                Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(palette.surface).clickable { bank = bk }.padding(vertical = 12.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                            ) {
                                                BrandLogo(Brands.bank(bk.code), 40.dp)
                                                Spacer(Modifier.height(6.dp))
                                                Text(bk.name.removeSuffix(" Bank").removeSuffix(" Kenya"), color = palette.text, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                            }
                        }
                        else -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                BrandLogo(Brands.bank(b.code), 44.dp)
                                Spacer(Modifier.width(12.dp))
                                SheetField(palette, number, { number = it.filter { c -> c.isLetterOrDigit() }.take(20); error = null }, "Account number", KeyboardType.Number, focus, Modifier.weight(1f), ImeAction.Next) {}
                            }
                            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                            Spacer(Modifier.height(10.dp))
                            SheetField(palette, holder, { holder = it.take(40) }, "Name on the account (optional)", KeyboardType.Text, null, Modifier.fillMaxWidth().padding(start = 56.dp)) {
                                error = MoneyStore.addBank(ctx, b.code, number, holder); if (error == null) { onAdded("${b.name} added"); close() }
                            }
                            error?.let { Text(it, color = Color(0xFFE53935), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, start = 56.dp)) }
                            SheetButton(palette, "Add account", number.length >= 6) {
                                error = MoneyStore.addBank(ctx, b.code, number, holder); if (error == null) { onAdded("${b.name} added"); close() }
                            }
                        }
                    }
                    if (k == null) error?.let { Text(it, color = Color(0xFFE53935), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ChoiceRow(p: SheetPalette, logo: Mark, title: String, sub: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.surface).clickable(enabled = enabled, onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandLogo(logo, 40.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = p.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(sub, color = p.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (enabled) Icon(Icons.Rounded.ChevronRight, null, tint = p.muted)
    }
}

@Composable
private fun SheetField(
    p: SheetPalette, value: String, onChange: (String) -> Unit, hint: String, type: KeyboardType,
    focus: FocusRequester?, modifier: Modifier, ime: ImeAction = ImeAction.Done, onDone: () -> Unit,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, placeholder = { Text(hint, color = p.muted) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = ime),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.let { if (focus != null) it.focusRequester(focus) else it },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = p.text, unfocusedTextColor = p.text, cursorColor = p.accent,
            focusedBorderColor = p.accent, unfocusedBorderColor = p.border,
            focusedContainerColor = p.surface, unfocusedContainerColor = p.surface,
        ),
    )
}

@Composable
private fun SheetButton(p: SheetPalette, label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 18.dp).height(52.dp),
        colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = if (p == SheetPalette.Dark) Color.Black else Color.White, disabledContainerColor = p.accent.copy(alpha = 0.35f)),
    ) { Text(label, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
}
