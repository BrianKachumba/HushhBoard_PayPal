package dev.patrickgold.florisboard.ussd

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.patrickgold.florisboard.TransparentCameraActivity
import dev.patrickgold.florisboard.money.*
import dev.patrickgold.florisboard.scanner.MobileMoneyParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class TransactionType { SEND_MONEY, TILL, PAYBILL, BANK, UNKNOWN }

/** A payment source / payee row in the list (M-Pesa, Airtel Money, Equity, KCB, PayPal...). */
data class PayAccount(
    val id: String,
    val provider: String,
    val number: String,
    val kind: SourceKind = when {
        provider.contains("PayPal", true) -> SourceKind.PAYPAL
        provider.contains("Airtel", true) -> SourceKind.AIRTEL
        provider.contains("M-Pesa", true) || provider.contains("M-PESA", true) -> SourceKind.MPESA
        else -> SourceKind.BANK
    },
)

/** The account actually used to pay: a payee bar plus the account picked in it (its primary by default). */
private fun payAccountOf(g: PayeeGroup, o: PayeeOption): PayAccount = PayAccount(
    id = o.id,
    provider = when (g.kind) { SourceKind.MPESA -> if (g.name == "M-PESA") "M-Pesa" else g.name; SourceKind.AIRTEL -> "Airtel Money"; else -> g.name },
    number = o.value,
    kind = g.kind,
)

private fun TransactionType.toTab() = when (this) {
    TransactionType.TILL -> PayTab.TILL
    TransactionType.PAYBILL -> PayTab.PAYBILL
    TransactionType.BANK -> PayTab.BANK
    else -> PayTab.PHONE
}

private fun PayTab.toType() = when (this) {
    PayTab.TILL -> TransactionType.TILL
    PayTab.PAYBILL -> TransactionType.PAYBILL
    PayTab.BANK -> TransactionType.BANK
    PayTab.PHONE -> TransactionType.SEND_MONEY
}

/** What the pay screen shows after Confirm when the route isn't a plain M-PESA/Airtel payment. */
sealed class PayOutcome {
    data class Route(val route: PayRoute) : PayOutcome()
    data class ConfirmPayPal(val email: String, val amount: Double) : PayOutcome()
    data object Sending : PayOutcome()
    data class Sent(val payment: PayPalPayment, val email: String) : PayOutcome()
    data class Failed(val message: String) : PayOutcome()
}

class UssdConfirmationActivity : ComponentActivity() {
    companion object {
        /** Already-parsed payment from the scanner (e.g. a KE-QR code): type name + field map. */
        const val EXTRA_PAY_TYPE = "PAY_TYPE"
        const val EXTRA_PAY_FIELDS = "PAY_FIELDS"
        /** Open with Channel3 results for this product (from the keyboard's shopping chip). */
        const val EXTRA_SHOP_QUERY = "SHOP_QUERY"
        private const val HANDOFF_CLIP = "hushh-handoff"
    }

    @Suppress("UNCHECKED_CAST", "DEPRECATION")
    private fun structuredPayment(): Pair<TransactionType, Map<String, String>>? {
        val typeName = intent.getStringExtra(EXTRA_PAY_TYPE) ?: return null
        val type = when (typeName) {
            "SEND_MONEY" -> TransactionType.SEND_MONEY
            "TILL" -> TransactionType.TILL
            "PAYBILL" -> TransactionType.PAYBILL
            "BANK_PAYPAL" -> TransactionType.BANK
            else -> return null
        }
        if (typeName == "BANK_PAYPAL") {
            bankDestinationState.value = BankDestination.PAYPAL
            preferPayPalState.value = true
        }
        val fields = (intent.getSerializableExtra(EXTRA_PAY_FIELDS) as? HashMap<String, String>) ?: return null
        return type to fields
    }

    private var pendingRawText: String = ""
    private var pendingCommand: String = ""

    private val transactionTypeState = MutableStateFlow(TransactionType.SEND_MONEY)
    private val transactionDataState = MutableStateFlow<Map<String, String>>(emptyMap())

    private val bankDestinationState = MutableStateFlow(BankDestination.BANK_ACCOUNT)
    private val outcomeState = MutableStateFlow<PayOutcome?>(null)
    private val smartState = MutableStateFlow<SmartFill?>(null)
    private val preferPayPalState = MutableStateFlow(false)
    /** The amount's currency on screen. KSh by default; any vertical swipe on the amount switches to USD and back. */
    private val currencyState = MutableStateFlow("KES")
    private val fxState = MutableStateFlow(MoneyApi.lastFx)

    private val cameraEditLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val scannedText = result.data?.getStringExtra("SCANNED_RESULT") ?: ""
                val parsed = MobileMoneyParser.parse(scannedText)
                if (parsed.type != TransactionType.UNKNOWN) {
                    transactionTypeState.value = parsed.type
                    val merged = transactionDataState.value.toMutableMap()
                    parsed.data.forEach { (k, v) -> if (v.isNotBlank()) merged[k] = v }
                    transactionDataState.value = merged
                    Toast.makeText(this, "Fields Auto-Updated!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Could not detect details from scan.", Toast.LENGTH_SHORT).show()
                }
            }
        }

    /** Phone permissions are only needed for USSD (Airtel / *334#); the screen itself always opens. */
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pendingRawText = intent.getStringExtra("RAW_OCR_TEXT") ?: ""
        pendingCommand = intent.getStringExtra("TYPED_COMMAND") ?: ""

        val hasCall = ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val hasState = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

        launchUi()
        if (!(hasCall && hasState)) requestPermissionLauncher.launch(arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE))
    }

    private fun launchUi() {
        val (initType, initData) = structuredPayment() ?: parseMobileMoneyIntent(pendingRawText, pendingCommand)
        if (initType != TransactionType.UNKNOWN) {
            transactionTypeState.value = initType
            transactionDataState.value = initData
        }

        MoneyStore.reload(this)
        lifecycleScope.launch { MoneyApi.syncPayPal(this@UssdConfirmationActivity) }
        lifecycleScope.launch { runCatching { MoneyAi.warmUp(this@UssdConfirmationActivity) } }
        lifecycleScope.launch { (MoneyApi.fx(this@UssdConfirmationActivity) as? ApiResult.Ok)?.let { fxState.value = it.value } }

        setContent {
            val type by transactionTypeState.collectAsState()
            val data by transactionDataState.collectAsState()
            val money by MoneyStore.accounts.collectAsState()
            val groups = remember(money) { Payees.groups(money) }
            val destination by bankDestinationState.collectAsState()
            val outcome by outcomeState.collectAsState()
            val smart by smartState.collectAsState()
            val preferPayPal by preferPayPalState.collectAsState()
            val currency by currencyState.collectAsState()
            val fx by fxState.collectAsState()

            UssdTransactionScreen(
                type = type,
                data = data,
                groups = groups,
                money = money,
                destination = destination,
                smart = smart,
                preferPayPal = preferPayPal,
                currency = currency,
                fx = fx,
                onToggleCurrency = { toggleCurrency() },
                onDestinationChange = { bankDestinationState.value = it },
                onSmartFill = { },
                onApplySmart = { markClipOffered(); applySmart(it) },
                onDismissSmart = { markClipOffered(); smartState.value = null },
                onConnectPayPal = {
                    lifecycleScope.launch {
                        PayPalConnect.start(this@UssdConfirmationActivity)?.let {
                            Toast.makeText(this@UssdConfirmationActivity, it, Toast.LENGTH_LONG).show()
                        }
                    }
                },
                onTypeChange = { transactionTypeState.value = it },
                onDataChange = { key, value ->
                    transactionDataState.value = transactionDataState.value.toMutableMap().apply { put(key, value) }
                },
                onCameraEdit = {
                    cameraEditLauncher.launch(
                        Intent(this, TransparentCameraActivity::class.java).putExtra("IS_FOR_EDIT", true)
                    )
                },
                onBack = { finish() },
                onConfirm = { account -> handlePay(type, data.withAmountFor(account.kind), account) }
            )

            outcome?.let { o ->
                PayOutcomeDialog(
                    outcome = o,
                    onDismiss = { if (o !is PayOutcome.Sending) outcomeState.value = null },
                    onOpenApp = { app -> handOff(app, (o as? PayOutcome.Route)?.route, lastPayData ?: data); outcomeState.value = null },
                    onSendPayPal = { email, amount -> sendPayPal(email, amount, data["note"].orEmpty()) },
                    onDone = { outcomeState.value = null; finish() },
                )
            }
        }

        // Silently look at a message the user *just* copied (never an old one), unless the page came prefilled.
        if (structuredPayment() == null) window.decorView.postDelayed({ freshClip()?.let { runSmartFill(it) } }, 350)
    }

    /** A clip copied in the last 3 minutes that we haven't offered before - or nothing. */
    private var offeredClipKey: String? = null
    private fun freshClip(): String? = runCatching {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val desc = cm.primaryClipDescription ?: return null
        if (desc.label == HANDOFF_CLIP) return null // the amount we copied for a hand-off, not a message
        val ts = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) desc.timestamp else 0L
        if (ts == 0L || System.currentTimeMillis() - ts > 3 * 60_000) return null
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (text.isBlank()) return null
        val key = "$ts:${text.hashCode()}"
        val seen = getSharedPreferences("hushh_smartfill", MODE_PRIVATE)
        if (seen.getString("offered", null) == key) return null
        offeredClipKey = key
        text
    }.getOrNull()

    /**
     * Hand-off to the official app: the amount goes on the clipboard (neither M-PESA nor PayPal accept a
     * pre-filled amount from other apps), and the step is logged in Activity so it doesn't just vanish.
     */
    private fun handOff(app: HandoffApp, route: PayRoute?, data: Map<String, String>) {
        val amount = data["amount"].orEmpty()
        if (amount.isNotBlank()) {
            runCatching {
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText(HANDOFF_CLIP, amount))
            }
            Toast.makeText(this, "Amount $amount copied. Paste it in ${app.label.removePrefix("Open ")}", Toast.LENGTH_LONG).show()
        }
        val h = route as? PayRoute.Handoff
        MoneyStore.recordActivity(
            this,
            MoneyActivity(
                id = "handoff-" + System.currentTimeMillis(), kind = "handoff", status = "HANDED_OFF",
                amount = amount.toDoubleOrNull() ?: 0.0,
                currency = if (lastSource == SourceKind.PAYPAL) "USD" else "KES",
                counterparty = app.label.removePrefix("Open "), note = h?.title.orEmpty(), at = System.currentTimeMillis(),
            ),
        )
        Handoffs.open(this, app)
    }

    /** Remember the clip so it is never offered again (after it was used or dismissed). */
    private fun markClipOffered() {
        offeredClipKey?.let { getSharedPreferences("hushh_smartfill", MODE_PRIVATE).edit().putString("offered", it).apply() }
    }

    private fun runSmartFill(text: String) {
        lifecycleScope.launch {
            val fill = runCatching { MoneyAi.understand(this@UssdConfirmationActivity, text) }.getOrNull()
            if (fill != null) smartState.value = fill
        }
    }

    private fun applySmart(fill: SmartFill) {
        transactionTypeState.value = fill.tab.toType()
        bankDestinationState.value = fill.destination
        transactionDataState.value = transactionDataState.value.toMutableMap().apply { putAll(fill.fields) }
        preferPayPalState.value = fill.preferPayPal && MoneyStore.accounts.value.paypal != null
        currencyState.value = if (fill.currency.equals("USD", true)) "USD" else "KES"
        smartState.value = null
        fill.shopQuery?.let { ShopActivity.launch(this, it, fromKeyboard = true); finish(); return }
    }

    private fun sendPayPal(email: String, amount: Double, note: String) {
        outcomeState.value = PayOutcome.Sending
        lifecycleScope.launch {
            when (val r = MoneyApi.send(this@UssdConfirmationActivity, email, amount, "USD", note.ifBlank { "Sent with HushhBoard" })) {
                is ApiResult.Ok -> {
                    // PayPal's own sheet confirms it (the user is normally still signed in from Connect),
                    // then our server captures it and brings them back with "Paid".
                    outcomeState.value = null
                    PayPalConnect.openPayPalPage(this@UssdConfirmationActivity, r.value.approveUrl)
                    finish()
                }
                is ApiResult.Err -> outcomeState.value = PayOutcome.Failed(r.message)
            }
        }
    }

    /**
     * Payment dispatch. SIM picking is gone from the UI, so we resolve a SIM from the
     * chosen provider here. Replace this body with your new payment handler.
     */
    private var lastSource: SourceKind? = null

    private var lastPayData: Map<String, String>? = null

    /** Swipe on the amount: KSh <-> USD, converting what's typed with the live rate (never a guessed one). */
    private fun toggleCurrency() {
        val fx = fxState.value ?: run {
            Toast.makeText(this, "Getting today's rate…", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch { (MoneyApi.fx(this@UssdConfirmationActivity) as? ApiResult.Ok)?.let { fxState.value = it.value } }
            return
        }
        val next = if (currencyState.value == "KES") "USD" else "KES"
        val amt = transactionDataState.value["amount"]?.toDoubleOrNull()
        if (amt != null && amt > 0) {
            val converted = if (next == "USD") amt / fx.rate else amt * fx.rate
            val text = if (next == "USD") "%.2f".format(java.util.Locale.US, converted) else "%.0f".format(java.util.Locale.US, converted)
            transactionDataState.value = transactionDataState.value + ("amount" to text)
        }
        currencyState.value = next
        window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    /** The amount in the currency the chosen account pays in: PayPal pays USD, M-PESA / Airtel / banks pay KSh. */
    private fun Map<String, String>.withAmountFor(kind: SourceKind): Map<String, String> {
        val amt = this["amount"]?.toDoubleOrNull() ?: return this
        val want = if (kind == SourceKind.PAYPAL) "USD" else "KES"
        val have = currencyState.value
        val rate = fxState.value?.rate
        if (want == have || rate == null) return this
        val v = if (want == "USD") amt / rate else amt * rate
        return this + ("amount" to if (want == "USD") "%.2f".format(java.util.Locale.US, v) else "%.0f".format(java.util.Locale.US, v))
    }

    private fun handlePay(type: TransactionType, data: Map<String, String>, account: PayAccount) {
        lastSource = account.kind
        lastPayData = data
        val route = PayRouter.route(account.kind, type.toTab(), data, bankDestinationState.value, MoneyStore.accounts.value)
        when (route) {
            is PayRoute.Local -> handleLocalPay(route.tab.toType(), route.fields, account)
            is PayRoute.PayPalSend -> outcomeState.value = PayOutcome.ConfirmPayPal(route.toEmail, data["amount"]?.toDoubleOrNull() ?: 0.0)
            else -> outcomeState.value = PayOutcome.Route(route)
        }
    }

    /** The original M-PESA / Airtel flow (USSD or STK push). */
    private fun handleLocalPay(type: TransactionType, data: Map<String, String>, account: PayAccount) {

        val sims = runCatching { SimManager.getActiveSims(this) }.getOrDefault(emptyList())
        val sim = sims.find { it.carrierName.contains(account.provider.substringBefore(" "), ignoreCase = true) }
            ?: sims.firstOrNull()

        when {
            account.provider.contains("Airtel", ignoreCase = true) && sim != null -> {
                val ussd = UssdCodeGenerator.generateAirtelCode(type, data)
                if (ussd.isNotEmpty()) {
                    SimManager.executeUssd(this, ussd, sim)
                    finish()
                } else Toast.makeText(this, "Invalid Airtel details", Toast.LENGTH_SHORT).show()
            }

            account.provider.contains("M-Pesa", ignoreCase = true) &&
                (type == TransactionType.TILL || type == TransactionType.PAYBILL) -> {
                Toast.makeText(this, "Requesting M-PESA prompt...", Toast.LENGTH_SHORT).show()
                val amount = data["amount"] ?: "0"
                val ref = if (type == TransactionType.PAYBILL) data["account"] ?: "Paybill" else "Buy Goods"
                val userPhone = account.number
                lifecycleScope.launch(Dispatchers.IO) {
                    val ok = DarajaApiClient.initiateStkPush(userPhone, amount, ref, "HushhBoard Payment")
                    withContext(Dispatchers.Main) {
                        if (!ok) Toast.makeText(this@UssdConfirmationActivity, "M-Pesa Request Failed", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            }

            sim != null -> {
                Toast.makeText(this, "Opening " + account.provider + " menu...", Toast.LENGTH_SHORT).show()
                SimManager.executeUssd(this, "*334#", sim)
                finish()
            }

            else -> Toast.makeText(this, account.provider + " isn't supported yet", Toast.LENGTH_SHORT).show()
        }
    }

    private fun parseMobileMoneyIntent(rawText: String, typedCommand: String): Pair<TransactionType, Map<String, String>> {
        if (typedCommand.startsWith("##send") || typedCommand.startsWith("##pay")) {
            val clean = typedCommand.lowercase()
            val amount = Regex("\\d+").find(clean)?.value ?: ""
            val phone = Regex("(07\\d{8}|01\\d{8})").find(clean)?.value
            val till = Regex("till\\s(\\d{5,7})").find(clean)?.groupValues?.get(1)
            val paybill = Regex("paybil[l]?\\s(\\d{5,6})").find(clean)?.groupValues?.get(1)
            val acc = Regex("acc\\s(\\w+)").find(clean)?.groupValues?.get(1)

            return when {
                phone != null -> TransactionType.SEND_MONEY to mapOf("phone" to phone, "amount" to amount)
                paybill != null && acc != null -> TransactionType.PAYBILL to mapOf("paybill" to paybill, "account" to acc, "amount" to amount)
                till != null -> TransactionType.TILL to mapOf("till" to till, "amount" to amount)
                else -> TransactionType.UNKNOWN to emptyMap()
            }
        }
        if (rawText.isBlank()) return TransactionType.UNKNOWN to emptyMap()
        val parsed = MobileMoneyParser.parse(rawText)
        return parsed.type to parsed.data
    }
}

// ───────────────────────── Fields & key logic ─────────────────────────

/** Every input on the page. `numeric` fields use the in-page number pad; the others use the normal keyboard. */
private enum class Field(val key: String, val numeric: Boolean, val maxLen: Int) {
    AMOUNT("amount", true, 9),
    PHONE("phone", true, 10),
    TILL("till", true, 7),
    PAYBILL("paybill", true, 6),
    ACCOUNT("account", false, 30),
    BANK_NAME("bankName", false, 30),
    BANK_ACCOUNT("bankAccount", true, 20),
    PP_EMAIL("ppEmail", false, 64),
}

private fun fieldsFor(type: TransactionType, dest: BankDestination): List<Field> = when (type) {
    TransactionType.SEND_MONEY -> listOf(Field.AMOUNT, Field.PHONE)
    TransactionType.TILL -> listOf(Field.AMOUNT, Field.TILL)
    TransactionType.PAYBILL -> listOf(Field.AMOUNT, Field.PAYBILL, Field.ACCOUNT)
    TransactionType.BANK ->
        if (dest == BankDestination.PAYPAL) listOf(Field.AMOUNT, Field.PP_EMAIL)
        else listOf(Field.AMOUNT, Field.BANK_NAME, Field.BANK_ACCOUNT)
    else -> listOf(Field.AMOUNT)
}

private val EMAIL_RE = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$")

private const val KEY_BACKSPACE = "BACKSPACE"

private fun applyKey(field: Field, current: String, key: String): String = when (key) {
    KEY_BACKSPACE -> current.dropLast(1)
    ".", "," ->
        // Only the amount accepts a decimal point, and only one.
        if (field == Field.AMOUNT && !current.contains('.')) current.ifEmpty { "0" } + "." else current
    else -> when {
        current.length >= field.maxLen -> current
        field == Field.AMOUNT && current == "0" -> key
        field == Field.AMOUNT && current.contains('.') && current.substringAfter('.').length >= 2 -> current
        else -> current + key
    }
}

// ───────────────────────── UI ─────────────────────────

private val PageBg = Color(0xFFF2F2F2)
private val CardBg = Color.White
private val Brand = Color(0xFF07C160)       // WeChat-style green
private val TextMain = Color(0xFF111111)
private val TextMuted = Color(0xFF8A8A8A)
private val Divider = Color(0xFFE4E4E4)
private val BrandPressed = Color(0xFF05A050)
private val PadBg = Color(0xFFE9EAEC)
private val KeyBg = Color.White
private val KeyFn = Color(0xFFD3D5D9)
private val KeyPressed = Color(0xFFC8CACE)
private val PayPalBlue = Color(0xFF0070E0)
private val AmberText = Color(0xFFB26A00)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun UssdTransactionScreen(
    type: TransactionType,
    data: Map<String, String>,
    groups: List<PayeeGroup>,
    money: MoneyAccounts,
    destination: BankDestination,
    smart: SmartFill?,
    preferPayPal: Boolean,
    currency: String,
    fx: FxRate?,
    onToggleCurrency: () -> Unit,
    onDestinationChange: (BankDestination) -> Unit,
    onSmartFill: (String?) -> Unit,
    onApplySmart: (SmartFill) -> Unit,
    onDismissSmart: () -> Unit,
    onConnectPayPal: () -> Unit,
    onTypeChange: (TransactionType) -> Unit,
    onDataChange: (String, String) -> Unit,
    onCameraEdit: () -> Unit,
    onBack: () -> Unit,
    onConfirm: (PayAccount) -> Unit,
) {
    val view = LocalView.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // Which payee bar is selected, and (only for this payment) which of its accounts if not the primary.
    var selectedKey by remember { mutableStateOf<String?>(null) }
    var pickedOption by remember { mutableStateOf<String?>(null) }
    var showAddSheet by remember { mutableStateOf(false) }
    var active by remember { mutableStateOf<Field?>(null) }
    val latestData by rememberUpdatedState(data)

    // PayPal is only listed where it works (never Till/Paybill; Phone only for M-PESA numbers).
    val visibleGroups = remember(groups, type, data["phone"]) { Payees.visible(groups, type.toTab(), data["phone"].orEmpty()) }
    val selectedGroup = visibleGroups.firstOrNull { it.key == selectedKey }
    val selectedOption = selectedGroup?.let { g -> g.options.firstOrNull { it.id == pickedOption } ?: g.primary }
    val selected = if (selectedGroup != null && selectedOption != null) payAccountOf(selectedGroup, selectedOption) else null

    // AI asked for PayPal, or nothing picked yet / picked bar no longer listed: preselect sensibly.
    LaunchedEffect(preferPayPal, visibleGroups) {
        if (preferPayPal && visibleGroups.any { it.kind == SourceKind.PAYPAL }) { selectedKey = "paypal"; pickedOption = null }
        else if (selectedKey == null || visibleGroups.none { it.key == selectedKey }) { selectedKey = visibleGroups.firstOrNull()?.key; pickedOption = null }
    }

    val fields = fieldsFor(type, destination)
    val textRequesters = remember { mapOf(Field.ACCOUNT to FocusRequester(), Field.BANK_NAME to FocusRequester(), Field.PP_EMAIL to FocusRequester()) }
    val payingWithPayPal = selected?.kind == SourceKind.PAYPAL
    val isUsd = currency == "USD"
    val currencyLabel = if (isUsd) "USD" else "KSh"

    val formValid = (data["amount"]?.toDoubleOrNull() ?: 0.0) > 0 && when (type) {
        TransactionType.SEND_MONEY -> (data["phone"] ?: "").length >= 10
        TransactionType.TILL -> (data["till"] ?: "").length >= 5
        TransactionType.PAYBILL -> (data["paybill"] ?: "").length >= 5 && !data["account"].isNullOrBlank()
        TransactionType.BANK ->
            if (destination == BankDestination.PAYPAL) {
                val email = data["ppEmail"].orEmpty().trim()
                EMAIL_RE.matches(email) || (email.isEmpty() && !payingWithPayPal && money.paypal != null)
            } else !data["bankName"].isNullOrBlank() && (data["bankAccount"] ?: "").length >= 6
        else -> false
    }
    val canPay = formValid && selected != null
    val route = if (canPay) PayRouter.route(selected!!.kind, type.toTab(), data, destination, money) else null
    val flow = if (canPay) PayRouter.flowFor(selected!!.kind, type.toTab(), data, destination, money) else null
    val padOpen = active?.numeric == true

    // One keyboard at a time: our number pad for numbers, the system keyboard for text - never both.
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(active) {
        val a = active ?: return@LaunchedEffect
        if (a.numeric) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
            (view.context as? android.app.Activity)?.window?.let { w ->
                androidx.core.view.WindowCompat.getInsetsController(w, view).hide(androidx.core.view.WindowInsetsCompat.Type.ime())
            }
        } else {
            runCatching { textRequesters[a]?.requestFocus() }
        }
    }
    // The system keyboard opened (a text field got focus) -> the pad must not be the active input.
    LaunchedEffect(imeVisible) { if (imeVisible && active?.numeric == true) active = null }

    BackHandler(enabled = padOpen) { active = null }

    fun closeInput() {
        active = null
        focusManager.clearFocus()
        keyboardController?.hide()
    }

    fun advance(allowPay: Boolean) {
        val idx = fields.indexOf(active)
        when {
            idx in 0 until fields.lastIndex -> active = fields[idx + 1]
            allowPay && canPay -> {
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                selected?.let(onConfirm)
            }
            else -> closeInput()
        }
    }

    fun onPadKey(key: String) {
        val f = active ?: return
        val current = latestData[f.key] ?: ""
        val updated = applyKey(f, current, key)
        if (updated != current) onDataChange(f.key, updated)
    }

    val isLastField = active != null && active == fields.lastOrNull()
    val enterLabel = when {
        !isLastField -> "NEXT"
        canPay -> "PAY"
        else -> "DONE"
    }

    Column(
        Modifier.fillMaxSize().background(PageBg).statusBarsPadding().navigationBarsPadding().imePadding()
    ) {
        // Top bar (compact)
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Rounded.ArrowBackIosNew, "Back", tint = TextMain, modifier = Modifier.size(14.dp))
            }
            Text("Make a Transaction", color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        }

        // AI suggestion from the copied chat message
        AnimatedVisibility(visible = smart != null) {
            smart?.let { s -> SmartBanner(s, onApply = { onApplySmart(s); active = null }, onDismiss = onDismissSmart) }
        }

        // Content sits at the top of the page. Scrolling only switches on if the content genuinely
        // doesn't fit (tiny screen / system keyboard open), so normally the page never scrolls.
        val contentScroll = rememberScrollState()
        Column(Modifier.weight(1f).verticalScroll(contentScroll, enabled = contentScroll.maxValue > 0)) {
            // Phone | Till | Paybill | Bank
            TypeTabs(type) { onTypeChange(it); active = null }

            // Amount - big, tap to edit with the number pad (no label needed)
            Row(
                Modifier.fillMaxWidth().pointerInput(Unit) {
                    val slop = 14.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var switched = false
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!switched && kotlin.math.abs(c.position.y - down.position.y) > slop) { switched = true; onToggleCurrency() }
                            if (switched) c.consume()
                            if (!c.pressed) { if (!switched) active = Field.AMOUNT; break }
                        }
                    }
                }.padding(top = 24.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.UnfoldMore, "Swipe to switch KSh and USD", tint = TextMuted.copy(alpha = 0.6f), modifier = Modifier.size(14.dp))
                    AnimatedContent(targetState = currencyLabel, label = "currency") { c ->
                        Text(c, color = if (c == "USD") PayPalBlue else TextMain, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                    }
                }
                Spacer(Modifier.width(8.dp))
                val amt = data["amount"] ?: ""
                // Cursor sits where the next digit goes: before the grey "0" when empty, after the digits otherwise.
                if (active == Field.AMOUNT && amt.isEmpty()) BlinkingCursor(42.dp, amt)
                Text(
                    if (amt.isEmpty()) "0" else amt,
                    fontSize = 48.sp, fontWeight = FontWeight.Bold,
                    color = if (amt.isEmpty()) Color(0xFFCCCCCC) else TextMain
                )
                if (active == Field.AMOUNT && amt.isNotEmpty()) BlinkingCursor(42.dp, amt)
            }
            val typed = data["amount"]?.toDoubleOrNull()
            if (typed != null && typed > 0 && fx != null) {
                val other = if (isUsd) "≈ KSh ${"%,.0f".format(typed * fx.rate)}" else "≈ USD ${"%,.2f".format(typed / fx.rate)}"
                Text(
                    "$other · ${fx.label}", color = TextMuted, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp), textAlign = TextAlign.Center
                )
            }
            if (flow != null && route != null) RouteHint(flow, route)

            // Recipient details: same plain background as the amount, no card
            Column(Modifier.fillMaxWidth()) {
                when (type) {
                    TransactionType.SEND_MONEY -> {
                        NumericFieldRow("Phone number", data["phone"] ?: "", "07XXXXXXXX",
                            active == Field.PHONE, { active = Field.PHONE }, onCameraEdit)
                    }
                    TransactionType.TILL -> {
                        NumericFieldRow("Till number", data["till"] ?: "", "123456",
                            active == Field.TILL, { active = Field.TILL }, onCameraEdit)
                    }
                    TransactionType.PAYBILL -> {
                        NumericFieldRow("Paybill number", data["paybill"] ?: "", "123456",
                            active == Field.PAYBILL, { active = Field.PAYBILL }, onCameraEdit)
                        HorizontalDivider(color = Divider, modifier = Modifier.padding(horizontal = 16.dp))
                        TextFieldRow(
                            label = "Account number", value = data["account"] ?: "", placeholder = "Account number",
                            capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done,
                            focusRequester = textRequesters.getValue(Field.ACCOUNT),
                            onValueChange = { onDataChange(Field.ACCOUNT.key, it.take(Field.ACCOUNT.maxLen)) },
                            onFocused = { active = Field.ACCOUNT },
                            onImeAction = { advance(allowPay = false) }
                        )
                    }
                    TransactionType.BANK -> {
                        DestinationToggle(destination) { onDestinationChange(it); active = null }
                    }
                    else -> {}
                }
                if (type == TransactionType.BANK && destination == BankDestination.PAYPAL) {
                    TextFieldRow(
                        label = "PayPal email", value = data["ppEmail"] ?: "",
                        placeholder = if (!payingWithPayPal && money.paypal != null) "Blank = your PayPal" else "name@example.com",
                        capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Done,
                        focusRequester = textRequesters.getValue(Field.PP_EMAIL),
                        onValueChange = { onDataChange(Field.PP_EMAIL.key, it.trim().take(Field.PP_EMAIL.maxLen)) },
                        onFocused = { active = Field.PP_EMAIL },
                        onImeAction = { advance(allowPay = false) },
                        keyboardType = KeyboardType.Email,
                    )
                } else when (type) {
                    TransactionType.BANK -> {
                        TextFieldRow(
                            label = "Bank name", value = data["bankName"] ?: "", placeholder = "e.g. Equity",
                            capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next,
                            focusRequester = textRequesters.getValue(Field.BANK_NAME),
                            onValueChange = { onDataChange(Field.BANK_NAME.key, it.take(Field.BANK_NAME.maxLen)) },
                            onFocused = { active = Field.BANK_NAME },
                            onImeAction = { advance(allowPay = false) }
                        )
                        HorizontalDivider(color = Divider, modifier = Modifier.padding(horizontal = 16.dp))
                        NumericFieldRow("Account number", data["bankAccount"] ?: "", "Account number",
                            active == Field.BANK_ACCOUNT, { active = Field.BANK_ACCOUNT }, null)
                    }
                    else -> {}
                }
            }

            // Payee list: one bar per provider; tap = use its primary, the chevron picks another account.
            Text("Pay from", color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp))
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (visibleGroups.isEmpty()) {
                    Text(
                        "No accounts yet. Add your M-PESA, bank or PayPal below.",
                        color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                visibleGroups.forEach { g ->
                    val isSel = g.key == selectedKey
                    PayeeBar(
                        group = g, selected = isSel,
                        current = if (isSel) selectedOption ?: g.primary else g.primary,
                        onSelect = {
                            if (!isSel) { selectedKey = g.key; pickedOption = null }
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        },
                        onPick = { o ->
                            selectedKey = g.key; pickedOption = o.id
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        },
                    )
                }
                if (money.paypal == null && Payees.payPalAllowed(type.toTab(), data["phone"].orEmpty())) {
                    ConnectPayPalBar(onConnectPayPal)
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { active = null; showAddSheet = true }.padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Add, null, tint = Brand, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add account", color = Brand, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // Confirm and Pay (the pad's big key takes over while the pad is open)
        if (!padOpen || imeVisible) {
            Button(
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    selected?.let(onConfirm)
                },
                enabled = canPay,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).height(46.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Brand, contentColor = Color.White,
                    disabledContainerColor = Brand.copy(alpha = 0.4f), disabledContentColor = Color.White
                )
            ) {
                Text(
                    when (route) {
                        is PayRoute.Handoff -> "Continue"
                        is PayRoute.NeedsApi, is PayRoute.NotSupported -> "Review"
                        is PayRoute.PayPalSend -> "Pay with PayPal"
                        else -> "Confirm and Pay"
                    },
                    fontSize = 15.sp, fontWeight = FontWeight.Medium
                )
            }
        }

        // In-page number pad (only exists on this screen)
        AnimatedVisibility(
            visible = padOpen && !imeVisible,
            enter = slideInVertically { it },
            exit = slideOutVertically { it }
        ) {
            NumberPad(
                enterLabel = enterLabel,
                decimalEnabled = active == Field.AMOUNT,
                onKey = ::onPadKey,
                onEnter = { advance(allowPay = true) }
            )
        }
    }

    if (showAddSheet) AddAccountSheet(SheetPalette.Light, onDismiss = { showAddSheet = false }) { msg ->
        android.widget.Toast.makeText(view.context, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}

/**
 * Layout (4 columns x 4 rows, 13 equal keys + 1 tall key):
 *   1  2  3  [back]
 *   4  5  6  [    ]
 *   7  8  9  [ENTER - fills rows 2 to 4 exactly]
 *   ,  0  .  [    ]
 */
@Composable
private fun NumberPad(
    enterLabel: String,
    decimalEnabled: Boolean,
    onKey: (String) -> Unit,
    onEnter: () -> Unit,
) {
    val keyH = 56.dp
    val gap = 6.dp
    val pad = 6.dp
    val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf(",", "0", "."))

    // Height is set BEFORE padding so the inner area is exactly 4 keys + 3 gaps.
    Row(
        Modifier.fillMaxWidth().height(keyH * 4 + gap * 3 + pad * 2).background(PadBg).padding(pad),
        horizontalArrangement = Arrangement.spacedBy(gap)
    ) {
        Column(Modifier.weight(3f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
            rows.forEach { row ->
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { k ->
                        val isSeparator = k == "." || k == ","
                        val enabled = !isSeparator || decimalEnabled
                        KeyBox(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            enabled = enabled,
                            onPress = { onKey(k) }
                        ) {
                            Text(
                                k, fontSize = 24.sp, fontWeight = FontWeight.Medium,
                                color = if (enabled) TextMain else Color(0xFFB5B5B5)
                            )
                        }
                    }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
            KeyBox(
                modifier = Modifier.fillMaxWidth().height(keyH), bg = KeyFn, repeat = true,
                onPress = { onKey(KEY_BACKSPACE) }
            ) { Icon(Icons.Rounded.Backspace, "Backspace", tint = TextMain) }

            // Takes everything left in the column: top lines up with "6", bottom with "."
            KeyBox(
                modifier = Modifier.fillMaxWidth().weight(1f),
                bg = Brand, pressedBg = BrandPressed,
                onPress = onEnter
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(enterLabel, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = Color.White)
                    Icon(Icons.Rounded.KeyboardReturn, null, tint = Color.White, modifier = Modifier.size(26.dp))
                }
            }
        }
    }
}

@Composable
private fun KeyBox(
    modifier: Modifier,
    bg: Color = KeyBg,
    pressedBg: Color = KeyPressed,
    enabled: Boolean = true,
    repeat: Boolean = false,
    onPress: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val view = LocalView.current
    val current by rememberUpdatedState(onPress)
    var pressed by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (pressed) pressedBg else bg)
            .then(
                if (enabled) Modifier.pointerInput(repeat) {
                    detectTapGestures(onPress = {
                        pressed = true
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        current()
                        if (repeat) {
                            // Hold to keep deleting
                            coroutineScope {
                                val job = launch {
                                    delay(400)
                                    while (true) { current(); delay(60) }
                                }
                                tryAwaitRelease()
                                job.cancel()
                            }
                        } else {
                            tryAwaitRelease()
                        }
                        pressed = false
                    })
                } else Modifier
            ),
        contentAlignment = Alignment.Center,
        content = content
    )
}

/** A text-editor style caret: solid while typing, then a crisp on/off blink (no fading). */
@Composable
private fun BlinkingCursor(height: Dp, value: String = "") {
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(value) {
        visible = true
        delay(600)
        while (true) { visible = !visible; delay(530) }
    }
    Box(Modifier.padding(horizontal = 2.dp).width(2.dp).height(height).background(if (visible) Brand else Color.Transparent))
}

@Composable
private fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier =
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)

@Composable
private fun TypeTabs(type: TransactionType, onChange: (TransactionType) -> Unit) {
    val tabs = listOf(
        "Phone" to TransactionType.SEND_MONEY,
        "Till" to TransactionType.TILL,
        "Paybill" to TransactionType.PAYBILL,
        "Bank" to TransactionType.BANK,
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(18.dp)).background(Color(0xFFE4E4E4)).padding(2.dp)
    ) {
        tabs.forEach { (label, t) ->
            val isActive = t == type
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                    .background(if (isActive) Brand else Color.Transparent)
                    .clickable { onChange(t) }.padding(vertical = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label, color = if (isActive) Color.White else TextMain, fontSize = 12.sp,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

/** A row that looks like an input but is driven by the in-page number pad (never opens the system keyboard). */
@Composable
private fun NumericFieldRow(
    label: String, value: String, placeholder: String,
    isActive: Boolean, onClick: () -> Unit, onScan: (() -> Unit)?
) {
    Row(
        Modifier.fillMaxWidth().noRippleClickable(onClick).padding(horizontal = 16.dp).heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = if (isActive) Brand else TextMuted, fontSize = 12.sp, modifier = Modifier.width(104.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (isActive && value.isEmpty()) BlinkingCursor(16.dp, value)
            if (value.isEmpty()) Text(placeholder, color = Color(0xFFBBBBBB), fontSize = 14.sp)
            else Text(value, color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (isActive && value.isNotEmpty()) BlinkingCursor(16.dp, value)
        }
        if (onScan != null) {
            IconButton(onClick = onScan, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.DocumentScanner, "Scan", tint = Brand, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Normal text input (account number, bank name) - uses the regular keyboard. */
@Composable
private fun TextFieldRow(
    label: String, value: String, placeholder: String,
    capitalization: KeyboardCapitalization, imeAction: ImeAction,
    focusRequester: FocusRequester,
    onValueChange: (String) -> Unit, onFocused: () -> Unit, onImeAction: () -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextMuted, fontSize = 12.sp, modifier = Modifier.width(104.dp))
        BasicTextField(
            value = value, onValueChange = onValueChange, singleLine = true,
            textStyle = TextStyle(fontSize = 14.sp, color = TextMain, textAlign = TextAlign.End, fontWeight = FontWeight.Medium),
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType, capitalization = capitalization, imeAction = imeAction
            ),
            keyboardActions = KeyboardActions(onNext = { onImeAction() }, onDone = { onImeAction() }),
            cursorBrush = SolidColor(Brand),
            modifier = Modifier.weight(1f).focusRequester(focusRequester).onFocusChanged { if (it.isFocused) onFocused() },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterEnd) {
                    if (value.isEmpty()) Text(placeholder, color = Color(0xFFBBBBBB), fontSize = 14.sp, textAlign = TextAlign.End)
                    inner()
                }
            }
        )
    }
}

/** One payee bar: logo, provider, the account in use, and a chevron to pick another account (this payment only). */
@Composable
private fun PayeeBar(group: PayeeGroup, selected: Boolean, current: PayeeOption, onSelect: () -> Unit, onPick: (PayeeOption) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().height(60.dp).clip(shape)
            .background(if (selected) Brand.copy(alpha = 0.10f) else CardBg)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) Brand else Divider, shape)
            .clickable(onClick = onSelect).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BrandLogo(group.logo, 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(group.name, color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(current.label, color = if (selected) TextMain.copy(alpha = 0.75f) else TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (group.options.size > 1 && current.id == group.primaryId) {
                    Spacer(Modifier.width(6.dp))
                    Text("Primary", color = Brand, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        // Right side: what kind of account this is (phone = mobile money, building = bank), then the dropdown.
        kindIcon(group.kind)?.let { Icon(it, if (group.kind == SourceKind.BANK) "Bank" else "Mobile money", tint = TextMuted, modifier = Modifier.size(18.dp)) }
        if (group.options.size > 1) {
            Box {
                Row(
                    Modifier.clip(RoundedCornerShape(10.dp)).clickable { menu = true }.padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${group.options.size}", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Icon(Icons.Rounded.ExpandMore, "Other accounts", tint = TextMuted, modifier = Modifier.size(20.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.background(CardBg)) {
                    group.options.forEach { o ->
                        DropdownMenuItem(
                            leadingIcon = { BrandLogo(group.logo, 24.dp) },
                            text = {
                                Column {
                                    Text(o.label, color = TextMain, fontSize = 14.sp, fontWeight = if (o.id == current.id) FontWeight.Bold else FontWeight.Normal)
                                    val sub = listOfNotNull(o.holder.takeIf { it.isNotBlank() }, "Primary".takeIf { o.id == group.primaryId }).joinToString(" · ")
                                    if (sub.isNotEmpty()) Text(sub, color = TextMuted, fontSize = 11.sp)
                                }
                            },
                            onClick = { menu = false; onPick(o) },
                        )
                    }
                }
            }
        } else Spacer(Modifier.width(8.dp))
    }
}

@Composable
private fun ConnectPayPalBar(onConnect: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().height(60.dp).clip(shape).background(CardBg).border(1.dp, Divider, shape)
            .clickable(onClick = onConnect).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BrandLogo(Brands.PAYPAL, 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("PayPal", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("Log in once on PayPal's page", color = TextMuted, fontSize = 12.sp)
        }
        Text("Connect", color = PayPalBlue, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

// ───────────────────────── Money: AI banner, route hint, Bank-tab receiver, outcome dialogs ─────────────────────────

@Composable
private fun SmartBanner(fill: SmartFill, onApply: () -> Unit, onDismiss: () -> Unit) {
    // Slim, quiet suggestion from a message copied moments ago. Tap to fill, x to ignore.
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(20.dp)).background(Brand.copy(alpha = 0.08f))
            .clickable(onClick = onApply)
            .padding(start = 12.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.ContentPaste, null, tint = Brand, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        Text(fill.summary, color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Rounded.Close, "Ignore", tint = TextMuted, modifier = Modifier.size(14.dp))
        }
    }
}

/** One line telling the user exactly what Confirm will do, before they tap it. */
@Composable
private fun RouteHint(flow: Flow, route: PayRoute) {
    val (text, color) = when (route) {
        is PayRoute.Local -> "${flow.label} · pay with your PIN on the M-PESA/Airtel screen" to Brand
        is PayRoute.PayPalSend -> "${flow.label} · confirm on PayPal, then done" to PayPalBlue
        is PayRoute.Handoff -> "${route.flow.label} · finish in ${route.app.label.removePrefix("Open ")}" to PayPalBlue
        is PayRoute.NeedsApi -> "${route.flow.label} · ready, waiting for ${route.partner} API" to AmberText
        is PayRoute.NotSupported -> "Not supported for this payment method" to Color(0xFFD32F2F)
    }
    Text(
        text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 10.dp)
    )
}

@Composable
private fun DestinationToggle(dest: BankDestination, onChange: (BankDestination) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Send to", color = TextMuted, fontSize = 12.sp, modifier = Modifier.width(104.dp))
        listOf(BankDestination.BANK_ACCOUNT to "Bank account", BankDestination.PAYPAL to "PayPal").forEach { (d, label) ->
            val on = d == dest
            Text(
                label, fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                color = if (on) Color.White else TextMain,
                modifier = Modifier.padding(end = 8.dp).clip(RoundedCornerShape(14.dp))
                    .background(if (on) (if (d == BankDestination.PAYPAL) PayPalBlue else Brand) else Color(0xFFE4E4E4))
                    .clickable { onChange(d) }.padding(horizontal = 12.dp, vertical = 5.dp)
            )
        }
    }
}

@Composable
private fun PayOutcomeDialog(
    outcome: PayOutcome,
    onDismiss: () -> Unit,
    onOpenApp: (HandoffApp) -> Unit,
    onSendPayPal: (String, Double) -> Unit,
    onDone: () -> Unit,
) {
    when (outcome) {
        is PayOutcome.ConfirmPayPal -> AlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(Icons.Rounded.Public, null, tint = PayPalBlue) },
            title = { Text("Pay with PayPal") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("USD ${"%.2f".format(outcome.amount)} to ${outcome.email}", fontWeight = FontWeight.SemiBold)
                    Text("${MoneyApi.lastFx?.let { "≈ KSh ${"%,.0f".format(outcome.amount * it.rate)} (${it.label}). " } ?: ""}PayPal opens to confirm - you stay signed in from when you connected. Your password never touches HushhBoard.", fontSize = 13.sp, color = TextMuted)
                }
            },
            confirmButton = {
                Button(onClick = { onSendPayPal(outcome.email, outcome.amount) }, colors = ButtonDefaults.buttonColors(containerColor = PayPalBlue)) { Text("Continue to PayPal") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextMuted) } }
        )
        PayOutcome.Sending -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Paying with PayPal…") },
            text = { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(22.dp), color = PayPalBlue, strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)); Text("Talking to PayPal") } },
            confirmButton = {}
        )
        is PayOutcome.Sent -> AlertDialog(
            onDismissRequest = onDone,
            icon = { Icon(Icons.Rounded.CheckCircle, null, tint = Brand, modifier = Modifier.size(40.dp)) },
            title = { Text(if (outcome.payment.status == "COMPLETED") "Paid ✓" else "Payment ${outcome.payment.status.lowercase()}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${outcome.payment.currency ?: "USD"} ${outcome.payment.amount} to ${outcome.email}", fontWeight = FontWeight.SemiBold)
                    outcome.payment.kesEstimate?.let { Text("≈ KSh ${"%,d".format(it)}", fontSize = 13.sp, color = TextMuted) }
                    Text("PayPal ref ${outcome.payment.id}", fontSize = 12.sp, color = TextMuted)
                }
            },
            confirmButton = { TextButton(onClick = onDone) { Text("Done", color = Brand, fontWeight = FontWeight.Bold) } }
        )
        is PayOutcome.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(Icons.Rounded.ErrorOutline, null, tint = Color(0xFFD32F2F)) },
            title = { Text("PayPal didn't complete it") },
            text = { Text(outcome.message) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
        )
        is PayOutcome.Route -> when (val r = outcome.route) {
            is PayRoute.Handoff -> AlertDialog(
                onDismissRequest = onDismiss,
                icon = { Icon(Icons.Rounded.OpenInNew, null, tint = PayPalBlue) },
                title = { Text(r.title) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(r.flow.label.uppercase(), color = PayPalBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        r.steps.forEachIndexed { i, step -> Text("${i + 1}. $step", fontSize = 13.sp) }
                        Text("This step happens in the official app, so your PIN and password stay there.", fontSize = 12.sp, color = TextMuted)
                    }
                },
                confirmButton = { Button(onClick = { onOpenApp(r.app) }, colors = ButtonDefaults.buttonColors(containerColor = PayPalBlue)) { Text(r.app.label) } },
                dismissButton = { TextButton(onClick = onDismiss) { Text("Not now", color = TextMuted) } }
            )
            is PayRoute.NeedsApi -> AlertDialog(
                onDismissRequest = onDismiss,
                icon = { Icon(Icons.Rounded.Hub, null, tint = AmberText) },
                title = { Text(r.title) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${r.flow.label.uppercase()} · READY FOR ${r.partner.uppercase()}", color = AmberText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(r.message, fontSize = 13.sp)
                        Text("No money moved. This flow goes live when the partner API is enabled for HushhBoard.", fontSize = 12.sp, color = TextMuted)
                    }
                },
                confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
            )
            is PayRoute.NotSupported -> AlertDialog(
                onDismissRequest = onDismiss,
                icon = { Icon(Icons.Rounded.Block, null, tint = Color(0xFFD32F2F)) },
                title = { Text("Not supported") },
                text = { Text(r.message) },
                confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
            )
            else -> LaunchedEffect(r) { onDismiss() }
        }
    }
}
