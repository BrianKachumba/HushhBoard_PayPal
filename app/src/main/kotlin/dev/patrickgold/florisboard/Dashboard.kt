package dev.patrickgold.florisboard

import dev.patrickgold.florisboard.ussd.CategoryRegistry
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.gestures.Orientation
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.patrickgold.florisboard.ussd.AppDatabase
import dev.patrickgold.florisboard.ussd.TransactionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// ================================

// 🧠 STATE & VIEWMODEL

// ================================


data class GatekeeperState(

    val isEnabled: Boolean = false,

    val isSelected: Boolean = false,

    val hasAccountsStep: Boolean = false,

    val hasSmsPerm: Boolean = false

) {

    val keyboardReady: Boolean get() = isEnabled && isSelected && hasSmsPerm

    val isFullySetup: Boolean get() = keyboardReady && hasAccountsStep

}


class DashboardViewModelFactory(private val context: Context) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {

        return DashboardViewModel(context) as T

    }

}


class DashboardViewModel(context: Context) : ViewModel() {
    private val _pendingUnlabeledJumpId = MutableStateFlow<String?>(null)
    val pendingUnlabeledJumpId: StateFlow<String?> = _pendingUnlabeledJumpId.asStateFlow()

    fun requestJumpToUnlabeled(transactionId: String) {
        _pendingUnlabeledJumpId.value = transactionId
    }

    fun consumeUnlabeledJumpRequest() {
        _pendingUnlabeledJumpId.value = null
    }

    // Add these functions to handle the new Inbox Zero logic without hard-deleting data
    fun markAsLabeled(tx: TransactionEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            db.transactionDao().updateTransaction(tx.copy(isLabeled = true))
        }
    }

    fun markAsDiscarded(tx: TransactionEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            db.transactionDao().updateTransaction(tx.copy(isDiscarded = true))
        }
    }

    private val _gatekeeperState = MutableStateFlow(GatekeeperState())

    val gatekeeperState = _gatekeeperState.asStateFlow()


    private val _isEvaluating = MutableStateFlow(true)

    val isEvaluating = _isEvaluating.asStateFlow()


    private val db = AppDatabase.getDatabase(context)

    val transactions: StateFlow<List<TransactionEntity>> = db.transactionDao().getAllTransactions()

        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())


    fun evaluateSetupState(context: Context) {

        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

        val isEnabled = imm.enabledInputMethodList.any { it.packageName == context.packageName }

        val isSelected = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.contains(context.packageName) == true


        // Hackathon build: the crash-report step is gone; the last step is "Connect your money".
        val hasAccountsStep = dev.patrickgold.florisboard.money.MoneyStore.isSetupDone(context)

        val hasSmsPerm = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECEIVE_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED


        _gatekeeperState.value = GatekeeperState(isEnabled, isSelected, hasAccountsStep, hasSmsPerm)

        _isEvaluating.value = false

    }


    fun finishAccountsStep(context: Context) {

        dev.patrickgold.florisboard.money.MoneyStore.markSetupDone(context)

        evaluateSetupState(context)

    }


    // Database Actions

    fun updateTransactionCategory(tx: TransactionEntity, newCategory: String) {

        viewModelScope.launch(Dispatchers.IO) { db.transactionDao().updateTransaction(tx.copy(category = newCategory)) }

    }

    fun updateTransactionDescription(tx: TransactionEntity, newDesc: String) {

        viewModelScope.launch(Dispatchers.IO) { db.transactionDao().updateTransaction(tx.copy(description = newDesc)) }

    }

    fun deleteTransaction(tx: TransactionEntity) {

        viewModelScope.launch(Dispatchers.IO) { db.transactionDao().deleteTransaction(tx) }

    }

}


// ================================

// 🎨 THEME

// ================================

val Bg = Color(0xFF0A0A0A)

val Surface = Color(0xFF161616)

val Accent = Color(0xFF00E676)


// ================================

// 🚀 APP ROOT (THE ROUTER)

// ================================

@Composable

fun HushhCompanionApp(viewModel: DashboardViewModel) { // 🚀 FIXED: Added parameter back

    val gatekeeper by viewModel.gatekeeperState.collectAsState()

    val isEvaluating by viewModel.isEvaluating.collectAsState()


    if (isEvaluating) return


    val context = LocalContext.current

    val stage = when {

        !gatekeeper.keyboardReady -> 0

        !gatekeeper.hasAccountsStep -> 1

        else -> 2

    }

    AnimatedContent(targetState = stage, label = "AppRouter") { s ->

        when (s) {

            0 -> SetupWizardScreen(viewModel, gatekeeper, onComplete = { })

            1 -> dev.patrickgold.florisboard.money.ConnectAccountsSetupScreen(onDone = { viewModel.finishAccountsStep(context) })

            else -> MainAppEngine(viewModel)

        }

    }

}


// ================================

// 🚧 PROPER SETUP WIZARD

// ================================

@OptIn(com.google.accompanist.permissions.ExperimentalPermissionsApi::class)

@Composable

fun SetupWizardScreen(viewModel: DashboardViewModel, gatekeeper: GatekeeperState, onComplete: () -> Unit) {

    val context = LocalContext.current

    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    val lifecycleOwner = LocalLifecycleOwner.current

    val view = LocalView.current

    val smsPermissionState = com.google.accompanist.permissions.rememberPermissionState(android.Manifest.permission.RECEIVE_SMS)


    var isEnabled by remember { mutableStateOf(gatekeeper.isEnabled) }

    var isSelected by remember { mutableStateOf(gatekeeper.isSelected) }


    fun checkKeyboardStatus() {

        isEnabled = imm.enabledInputMethodList.any { it.packageName == context.packageName }

        isSelected = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.contains(context.packageName) == true

        viewModel.evaluateSetupState(context)

    }


    DisposableEffect(lifecycleOwner, view) {

        val lifecycleObserver = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) checkKeyboardStatus() }

        val focusListener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { hasFocus -> if (hasFocus) checkKeyboardStatus() }

        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)

        view.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)

        onDispose {

            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)

            view.viewTreeObserver.removeOnWindowFocusChangeListener(focusListener)

        }

    }


    LaunchedEffect(Unit) { checkKeyboardStatus() }


    val currentStep = when {

        !isEnabled -> 1

        !isSelected -> 2

        !gatekeeper.hasSmsPerm -> 3

        else -> 4

    }


    val progress = currentStep / 5f


    Column(modifier = Modifier.fillMaxSize().background(Bg).padding(horizontal = 20.dp)) {

        Spacer(Modifier.height(32.dp))

        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)), color = Accent, trackColor = Surface)

        Spacer(Modifier.height(32.dp))

        Text("Set up HushhBoard", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)

        Text("Just a few quick steps to get you started", fontSize = 14.sp, color = Color.Gray, modifier = Modifier.padding(top = 6.dp, bottom = 24.dp))


        SetupStepItem(1, "Enable Keyboard", "Turn on HushhBoard in system settings", currentStep == 1, currentStep > 1) { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }

        SetupStepItem(2, "Select as Default", "Switch to HushhBoard", currentStep == 2, currentStep > 2) { imm.showInputMethodPicker() }

        SetupStepItem(3, "Financial Insights", "Allow secure local payment tracking", currentStep == 3, currentStep > 3) { smsPermissionState.launchPermissionRequest() }

        SetupStepItem(4, "Connect your money", "M-PESA, Airtel Money, banks and PayPal", false, false) { }


        Spacer(Modifier.weight(1f))


        AnimatedVisibility(visible = currentStep == 4, enter = fadeIn() + slideInVertically(), exit = fadeOut()) {

            Button(onClick = onComplete, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent)) {

                Text("Continue", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black)

            }

        }

        Spacer(Modifier.height(24.dp))

    }

}


@Composable

fun SetupStepItem(stepNumber: Int, title: String, description: String, isActive: Boolean, isCompleted: Boolean, onClick: () -> Unit) {

    val alpha by animateFloatAsState(if (isActive || isCompleted) 1f else 0.5f, label = "")

    Card(

        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).alpha(alpha).clickable(enabled = isActive) { onClick() },

        shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = if (isActive) Surface else Color.Transparent),

        border = if (isActive) BorderStroke(1.dp, Accent) else if (isCompleted) BorderStroke(1.dp, Color.DarkGray) else null

    ) {

        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {

            Box(modifier = Modifier.size(42.dp).clip(CircleShape).background(if (isCompleted) Accent else Surface), contentAlignment = Alignment.Center) {

                if (isCompleted) Icon(Icons.Rounded.Check, null, tint = Color.Black) else Text("$stepNumber", color = if (isActive) Accent else Color.Gray, fontWeight = FontWeight.Bold)

            }

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {

                Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)

                Text(description, color = Color.Gray, fontSize = 12.sp)

            }

        }

    }

}




// ================================

// 📊 DASHBOARD HOME (STICKY HEADER)

// ================================

@OptIn(ExperimentalFoundationApi::class)

@Composable

fun DashboardHomeScreen(viewModel: DashboardViewModel, onOpenWrap: () -> Unit = {}) {

    val transactions by viewModel.transactions.collectAsState()


    Column(modifier = Modifier.fillMaxSize().padding(top = 24.dp)) {

        // ✅ FIXED HEADER (Never scrolls)

        HushhGlobalHeader()


        Spacer(Modifier.height(20.dp))


        // ✅ ONLY THIS PART SCROLLS

        Column(

            modifier = Modifier

                .fillMaxSize()

                .bouncyScroll(Orientation.Vertical) // 🚀 ADDED ELASTIC PHYSICS HERE

                .verticalScroll(rememberScrollState())

        ) {

            OutlinedTextField(

                value = "", onValueChange = {}, placeholder = { Text("Search Themes", color = Color.Gray, fontSize = 14.sp) },

                trailingIcon = { Icon(Icons.Rounded.Search, "Search", tint = Color.Gray) },

                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(52.dp), shape = RoundedCornerShape(26.dp),

                colors = TextFieldDefaults.colors(focusedContainerColor = Surface, unfocusedContainerColor = Surface, focusedIndicatorColor = Accent, unfocusedIndicatorColor = Color.Transparent)

            )


            Spacer(Modifier.height(24.dp))

            LazyRow(

                modifier = Modifier.bouncyScroll(Orientation.Horizontal),

                contentPadding = PaddingValues(horizontal = 16.dp),

                horizontalArrangement = Arrangement.spacedBy(16.dp)

            ) {

                items(2) {

                    Box(

                        modifier = Modifier

                            .width(260.dp)

                            .height(160.dp)

                            .clip(RoundedCornerShape(16.dp)) // Added clip for ripple boundary

                            .border(1.dp, Color.DarkGray, RoundedCornerShape(16.dp))

                            .background(Surface.copy(alpha = 0.5f), RoundedCornerShape(16.dp))

                            .clickable { onOpenWrap() } // 🚀 MAKES THE CARD TRIGGER THE STORY

                    )

                }

            }


            Spacer(Modifier.height(24.dp))

            DashboardActionButtons()


            Spacer(Modifier.height(32.dp))


            // 🚀 PULSE ACTIVITY HEADER
            val unlabeledTransactions = transactions.filter { !it.isLabeled && !it.isDiscarded }

            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Text("UNLABELED ACTIVITY", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                Text("(${unlabeledTransactions.size} pending)", color = Color.Gray, fontSize = 14.sp)
            }

            HorizontalDivider(color = Color.DarkGray, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp, start = 16.dp, end = 16.dp))

            if (unlabeledTransactions.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = "Done", tint = Accent, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("You're all caught up! 🎉", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text("No unlabeled transactions.", color = Color.Gray, fontSize = 14.sp)
                    }
                }
            } else {
                val pagerState = rememberPagerState(pageCount = { unlabeledTransactions.size })

                val pendingJumpId by viewModel.pendingUnlabeledJumpId.collectAsState()
                LaunchedEffect(pendingJumpId, unlabeledTransactions) {
                    val targetId = pendingJumpId ?: return@LaunchedEffect
                    val targetIndex = unlabeledTransactions.indexOfFirst { it.transactionId == targetId }
                    if (targetIndex >= 0) {
                        pagerState.animateScrollToPage(targetIndex)
                    }
                    viewModel.consumeUnlabeledJumpRequest()   // 🚀 always consumed — even a miss (e.g. the tx got labeled elsewhere in the meantime)
                }

                HorizontalPager(
                    state = pagerState,
                    contentPadding = PaddingValues(horizontal = 32.dp),
                    pageSpacing = 16.dp,
                    modifier = Modifier.fillMaxWidth().height(390.dp)
                ) { page ->
                    val tx = unlabeledTransactions[page]
                    // Calculate position based on the original full list for accurate daily transaction counting
                    val txTodayCount = transactions.count { it.dateString == tx.dateString }
                    val positionToday = txTodayCount - transactions.filter { it.dateString == tx.dateString }.indexOf(tx)

                    val pageOffset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                    val scale = lerp(start = 0.88f, stop = 1f, fraction = 1f - pageOffset.absoluteValue.coerceIn(0f, 1f))
                    val alpha = lerp(start = 0.5f, stop = 1f, fraction = 1f - pageOffset.absoluteValue.coerceIn(0f, 1f))

                    Box(modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }) {
                        InteractiveActivityCard(
                            transaction = tx,
                            dailyPosition = positionToday,
                            onCategorySelect = { viewModel.updateTransactionCategory(tx, it) },
                            onDescChange = { viewModel.updateTransactionDescription(tx, it) },
                            onDiscard = { viewModel.markAsDiscarded(tx) }, // 🚀 Hides it, doesn't delete it!
                            onSaveLabel = { viewModel.markAsLabeled(it) }  // 🚀 Saves relation and hides it!
                        )
                    }
                }
            }

            Spacer(Modifier.height(32.dp))

        }

    }

}

// 🚀 DYNAMIC, GAMIFIED INTERACTIVE CARD (3-Tier Input & Community Engine)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InteractiveActivityCard(
    transaction: TransactionEntity,
    dailyPosition: Int,
    onCategorySelect: (String) -> Unit,
    onDescChange: (String) -> Unit,
    onDiscard: () -> Unit,
    onSaveLabel: (TransactionEntity) -> Unit
) {
    // 🧠 Dynamic Scoring Logic
    val completionScore = remember(transaction.category, transaction.description) {
        var score = 0.40f // 40% base for automatic extraction
        if (transaction.category != "Uncategorized") score += 0.30f
        if (transaction.description.isNotBlank()) score += 0.30f
        score
    }

    val animatedProgress by animateFloatAsState(
        targetValue = completionScore,
        animationSpec = tween(1000, easing = FastOutSlowInEasing),
        label = "health"
    )

    // Dynamic Date Logic
    val displayDate = remember(transaction.dateString) {
        val format = java.text.SimpleDateFormat("d/M/yy", java.util.Locale.getDefault())
        val todayStr = format.format(java.util.Date())
        val yesterdayStr = format.format(java.util.Date(System.currentTimeMillis() - 86400000))

        when (transaction.dateString) {
            todayStr -> "Today"
            yesterdayStr -> "Yesterday"
            else -> transaction.dateString
        }
    }

    // 🚀 Predefined Categories & Subcategories
    val categoriesMap = remember {
        mapOf(
            "🍔 Food" to listOf("Fruits", "Vegetables", "Lunch", "Breakfast", "Other"),
            "🚕 Transport" to listOf("Taxi", "Bus", "Train", "Fuel", "Other"),
            "🛍️ Shopping" to listOf("Clothing", "Electronics", "Groceries", "Other"),
            "💡 Bills" to listOf("Electricity", "Water", "Internet", "Rent", "Other"),
            "💸 Transfers" to listOf("Family", "Friends", "Business", "Other")
        )
    }

    var expandedCategory by remember { mutableStateOf(false) }
    var expandedSubcategory by remember { mutableStateOf(false) }
    var selectedSubcategory by remember { mutableStateOf("") }
    var descriptionText by remember { mutableStateOf(transaction.description) }

    // 🚀 Community Learning State
    var showCommunityBubble by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Box(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
        Card(
            modifier = Modifier.fillMaxSize(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF16181C)),
            shape = RoundedCornerShape(28.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
        ) {
            Column(Modifier.padding(20.dp).fillMaxSize()) {

                // 🔝 HEADER
                Text(
                    text = "${dailyPosition}${getOrdinal(dailyPosition)} Transaction $displayDate",
                    color = Accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    text = transaction.recipient.uppercase(),
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(20.dp))

                // 🧠 MAIN SPLIT CONTENT
                Row(Modifier.fillMaxWidth().weight(1f)) {

                    // LEFT BLOCK
                    Column(
                        modifier = Modifier
                            .weight(0.85f)
                            .fillMaxHeight()
                            .background(Color(0xFF23262B), RoundedCornerShape(24.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.04f), RoundedCornerShape(24.dp))
                            .padding(horizontal = 16.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("KSH", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            transaction.amount,
                            color = Color.White,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Black
                        )

                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Fee: ${transaction.fee}",
                            color = Color(0xFFFF5252),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )

                        Spacer(Modifier.weight(1f))

                        HorizontalDivider(color = Color.DarkGray.copy(alpha = 0.5f))
                        Spacer(Modifier.height(16.dp))

                        val parts = transaction.dateString.split("/")
                        val day = parts.getOrNull(0) ?: ""

                        Text(day, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(transaction.timeString, color = Color.Gray, fontSize = 11.sp)
                    }

                    Spacer(Modifier.width(12.dp))

                    // RIGHT BLOCK (Intelligence & Input Area)
                    Column(
                        modifier = Modifier
                            .weight(1.15f)
                            .fillMaxHeight()
                            .background(Color(0xFF1E2126), RoundedCornerShape(24.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.04f), RoundedCornerShape(24.dp))
                            .padding(12.dp)
                    ) {
                        // Scan Receipt & Data Health Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Surface(
                                color = Color(0xFF23262B),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.clickable { }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Rounded.DocumentScanner,
                                        contentDescription = "Scan",
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("Receipt", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                }
                            }

                            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(56.dp)) {
                                CircularProgressIndicator(
                                    progress = { animatedProgress },
                                    modifier = Modifier.fillMaxSize(),
                                    color = Accent,
                                    trackColor = Color.DarkGray,
                                    strokeWidth = 6.dp
                                )
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Progress", color = Color.Gray, fontSize = 8.sp)
                                    Text(
                                        "${(animatedProgress * 100).toInt()}%",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        // 🚀 1. CATEGORY SELECTION
                        Box {
                            Surface(
                                color = Color(0xFF23262B),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .clickable { expandedCategory = true }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (transaction.category == "Uncategorized") "Select Category" else transaction.category,
                                        color = if (transaction.category == "Uncategorized") Color.Gray else Accent,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.weight(1f))
                                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Drop", tint = Color.Gray, modifier = Modifier.size(16.dp))
                                }
                            }

                            DropdownMenu(
                                expanded = expandedCategory,
                                onDismissRequest = { expandedCategory = false },
                                modifier = Modifier.background(Color(0xFF23262B))
                            ) {
                                CategoryRegistry.masterList.forEach { cat ->
                                    DropdownMenuItem(
                                        text = { Text("${cat.emoji} ${cat.displayName}", color = Color.White) },
                                        onClick = {
                                            onCategorySelect(cat.displayName)   // 🚀 stores "Food & Dining", not "🍔 Food"
                                            selectedSubcategory = ""
                                            expandedCategory = false
                                        }
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // 🚀 2. SUBCATEGORY SELECTION
                        Box {
                            Surface(
                                color = Color(0xFF23262B),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .clickable(enabled = transaction.category != "Uncategorized") { expandedSubcategory = true }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (selectedSubcategory.isEmpty()) "Select Subcategory" else selectedSubcategory,
                                        color = if (selectedSubcategory.isEmpty() || transaction.category == "Uncategorized") Color.Gray else Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.weight(1f))
                                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Drop", tint = Color.Gray, modifier = Modifier.size(16.dp))
                                }
                            }

                            DropdownMenu(
                                expanded = expandedSubcategory,
                                onDismissRequest = { expandedSubcategory = false },
                                modifier = Modifier.background(Color(0xFF23262B))
                            ) {
                                val availableSubs = CategoryRegistry.getCategoryByName(transaction.category).subCategories
                                availableSubs.forEach { sub ->
                                    DropdownMenuItem(
                                        text = { Text(sub, color = Color.White) },
                                        onClick = {
                                            selectedSubcategory = sub
                                            expandedSubcategory = false
                                            val newDesc = if (descriptionText.isBlank()) sub else "$sub - $descriptionText"
                                            onDescChange(newDesc)
                                            descriptionText = newDesc
                                        }
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // 🚀 3. DESCRIPTION (User Input)
                        Surface(
                            color = Color(0xFF23262B),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth().height(44.dp)
                        ) {
                            Box(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (descriptionText.isEmpty()) {
                                    Text("Description...", color = Color.Gray, fontSize = 13.sp)
                                }

                                androidx.compose.foundation.text.BasicTextField(
                                    value = descriptionText,
                                    onValueChange = {
                                        descriptionText = it
                                        onDescChange(it)
                                    },
                                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = Color.White),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))

                // 🔻 FOOTER
                Text(
                    "Transaction ID: \n${transaction.transactionId}",
                    color = Color.DarkGray,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )

                Spacer(Modifier.height(16.dp))

                // ACTION BUTTONS
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    OutlinedButton(
                        onClick = onDiscard, // 🚀 Just hides it from the inbox, keeps it in DB!
                        border = BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.3f)),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.width(130.dp).height(48.dp)
                    ) {
                        Text("Discard", color = Color(0xFFFF5252), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(Modifier.width(16.dp))

                    Button(
                        onClick = { showCommunityBubble = true }, // 🚀 Open the Bubble!
                        colors = ButtonDefaults.buttonColors(containerColor = Accent),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.width(130.dp).height(48.dp)
                    ) {
                        Text("Done", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Black)
                    }
                }
            }
        }

        // 🚀 THE COMMUNITY CONSENSUS BUBBLE (Bottom Sheet)
        if (showCommunityBubble) {
            ModalBottomSheet(
                onDismissRequest = {
                    showCommunityBubble = false // 🚀 Just closes the sheet, does NOT delete the card
                },
                sheetState = sheetState,
                containerColor = Color(0xFF16181C),
                dragHandle = { BottomSheetDefaults.DragHandle(color = Color.DarkGray) }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Success Icon
                    Box(
                        modifier = Modifier.size(64.dp).background(Accent.copy(alpha = 0.1f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = "Success", tint = Accent, modifier = Modifier.size(32.dp))
                    }

                    Spacer(Modifier.height(16.dp))

                    Text(
                        text = "Did you label this transaction correctly?",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )

                    Spacer(Modifier.height(12.dp))

                    Text(
                        text = "Save this receiver-label pair so HushhBoard remembers it next time. You will not need to label the same receiver again, and the saved label can also help anonymously improve Community Intelligence.",
                        color = Color.Gray,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 22.sp
                    )

                    Spacer(Modifier.height(32.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                showCommunityBubble = false // 🚀 Just closes the bubble. Leaves the card intact.
                            },
                            modifier = Modifier.weight(1f).height(52.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB0B0B0))
                        ) {
                            Text("No, Return", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                onSaveLabel(transaction) // 🚀 Submits data, marks as labeled, removes from Inbox
                                showCommunityBubble = false
                            },
                            modifier = Modifier.weight(1f).height(52.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black)
                        ) {
                            Text("Save", fontSize = 16.sp, fontWeight = FontWeight.Black)
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

// Math Utility for Interpolation in Pager

fun lerp(start: Float, stop: Float, fraction: Float): Float {

    return (1 - fraction) * start + fraction * stop

}


fun getOrdinal(n: Int): String {

    if (n in 11..13) return "th"

    return when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }

}


@Composable

fun ActionSquricle(text: String, icon: ImageVector? = null, onClick: () -> Unit = {}) {

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(70.dp).clickable { onClick() }) {

        Box(modifier = Modifier.size(64.dp).border(2.dp, Color(0xFF2A2A2A), RoundedCornerShape(18.dp)).background(Surface, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {

            if (icon != null) Icon(icon, text, tint = Color.White, modifier = Modifier.size(32.dp))

            else Text(text.split("\n")[0], color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)

        }

        Spacer(Modifier.height(8.dp))

        Text(if (text.contains("\n")) text.split("\n")[1] else text, color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.Medium)

    }

}


@Composable

fun DashboardActionButtons() {

    val context = LocalContext.current

    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {

        ActionSquricle("72\nWPM", null)

        ActionSquricle("SCAN", Icons.Rounded.DocumentScanner) { context.startActivity(Intent(context, dev.patrickgold.florisboard.TransparentCameraActivity::class.java).putExtra("FROM_DASHBOARD", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

        ActionSquricle("Song\nsearch", Icons.Rounded.MusicNote) { try { context.startActivity(Intent("com.google.android.googlequicksearchbox.MUSIC_SEARCH").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { } }

        ActionSquricle("PAY", Icons.Rounded.Payments) { context.startActivity(Intent(context, dev.patrickgold.florisboard.ussd.UssdConfirmationActivity::class.java).putExtra("TYPED_COMMAND", "##send ").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    }

}


@Composable

fun PlaceholderScreen(title: String, icon: ImageVector) {

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {

        Icon(icon, null, tint = Color.Gray, modifier = Modifier.size(64.dp))

        Spacer(Modifier.height(16.dp))

        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)

        Text("Coming Soon...", color = Color.Gray, fontSize = 14.sp)

    }

}
