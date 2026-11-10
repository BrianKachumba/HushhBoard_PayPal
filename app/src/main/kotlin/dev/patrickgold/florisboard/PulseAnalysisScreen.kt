package dev.patrickgold.florisboard

import dev.patrickgold.florisboard.ussd.CategoryRegistry
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.patrickgold.florisboard.ussd.TransactionEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ================================================================
// 1. ANALYSIS MODELS
// ================================================================
enum class AnalysisPeriod { DAY, WEEK, MONTH, YEAR }
enum class TrendMode { AMOUNT, COUNT }

data class AnalysisBar(
    val label: String,
    val selectionTitle: String,
    val contextTitle: String?,
    val amount: Double,
    val transactions: List<TransactionEntity>,
    val startMillis: Long,
    val endMillis: Long
)

data class AnalysisCategory(
    val name: String,
    val amount: Double,
    val count: Int,
    val icon: ImageVector,
    val isUnlabeled: Boolean = false
)

data class TrendPoint(
    val label: String,
    val amount: Double,
    val count: Int,
    val startMillis: Long,
    val endMillis: Long
)

private fun Double.roundToTwo(): String {
    return String.format(Locale.getDefault(), "%.2f", this)
}

// ================================================================
// TREND GRAPH FORMATTERS
// ================================================================
private fun trendPointTitle(point: TrendPoint, period: AnalysisPeriod): String {
    val date = Date(point.startMillis)
    return when (period) {
        AnalysisPeriod.DAY -> SimpleDateFormat("h a", Locale.getDefault()).format(date)
        AnalysisPeriod.WEEK -> SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(date)
        AnalysisPeriod.MONTH -> SimpleDateFormat("d MMMM", Locale.getDefault()).format(date)
        AnalysisPeriod.YEAR -> SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(date)
    }
}

private fun trendXAxisLabel(point: TrendPoint, period: AnalysisPeriod): String {
    val date = Date(point.startMillis)
    return when (period) {
        AnalysisPeriod.DAY -> {
            when (Calendar.getInstance().apply { time = date }.get(Calendar.HOUR_OF_DAY)) {
                1 -> "1 AM"
                6 -> "6 AM"
                12 -> "12 Noon"
                18 -> "6 PM"
                23 -> "11 PM"
                else -> SimpleDateFormat("ha", Locale.getDefault()).format(date)
            }
        }
        AnalysisPeriod.WEEK -> SimpleDateFormat("EEE", Locale.getDefault()).format(date)
        AnalysisPeriod.MONTH -> SimpleDateFormat("d", Locale.getDefault()).format(date)
        AnalysisPeriod.YEAR -> SimpleDateFormat("MMM", Locale.getDefault()).format(date)
    }
}

// ================================================================
// 2. SAFE AMOUNT HELPERS
// ================================================================
private fun TransactionEntity.analysisAmount(): Double =
    amount.replace(",", "").trim().toDoubleOrNull() ?: 0.0

// ================================================================
// 3. ANALYSIS MAIN ROUTER
// ================================================================
@Composable
fun PulseAnalysisScreen(viewModel: DashboardViewModel, onTabSelect: (String) -> Unit = {}) {
    var selectedCategory by remember { mutableStateOf<String?>(null) }

    Crossfade(
        targetState = selectedCategory,
        label = "AnalysisRouter"
    ) { category ->
        if (category == null) {
            AnalysisMainView(viewModel = viewModel, onCategoryClick = { selectedCategory = it }, onTabSelect = onTabSelect)
        } else {
            CategoryDetailView(categoryName = category, viewModel = viewModel, onBack = { selectedCategory = null })
        }
    }
}

// ================================================================
// 4. MAIN ANALYSIS SCREEN (Flawless Overlay Scroll Engine)
// ================================================================
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AnalysisMainView(viewModel: DashboardViewModel, onCategoryClick: (String) -> Unit, onTabSelect: (String) -> Unit) {
    val transactions by viewModel.transactions.collectAsState()
    var selectedPeriod by remember { mutableStateOf(AnalysisPeriod.DAY) }
    var selectedBarIndex by remember { mutableIntStateOf(0) }
    var trendMode by remember { mutableStateOf(TrendMode.AMOUNT) }

    // ----- Scroll state and density -----
    val scrollState = rememberScrollState()
    val density = LocalDensity.current

    // ----- Measurements -----
    var greetingHeightPx by remember { mutableIntStateOf(0) }
    val expandedCardHeightDp = 340.dp
    val collapsedCardHeightDp = expandedCardHeightDp * 0.65f   // 🚀 hard floor — never shrinks past 65% of full height

    // ----- Data Compilation -----
    val bars = remember(transactions, selectedPeriod) {
        buildAnalysisBars(transactions = transactions, period = selectedPeriod)
    }

    LaunchedEffect(bars, selectedPeriod) {
        selectedBarIndex = bars.lastIndex.coerceAtLeast(0)
    }

    val safeSelectedIndex = selectedBarIndex.coerceIn(0, bars.lastIndex.coerceAtLeast(0))
    val selectedBar = bars.getOrNull(safeSelectedIndex)
    val selectedTransactions = selectedBar?.transactions ?: emptyList()

    // 🚀 Powered entirely by the Canonical Category Registry
    val categories = remember(selectedTransactions) { buildAnalysisCategories(selectedTransactions) }

    val trendPoints = remember(selectedTransactions, selectedBar, selectedPeriod, trendMode) {
        buildTrendPoints(transactions = selectedTransactions, selectedBar = selectedBar, period = selectedPeriod)
    }

    // ============================================================
    // COLLAPSE & SCROLL MATH ENGINE
    // ============================================================
    val scrollOffset = scrollState.value.toFloat()

    val greetingY = -scrollOffset
    val heroBaseY = greetingHeightPx.toFloat()
    val pinnedHeroY = max(0f, heroBaseY - scrollOffset)

    val collapseStart = greetingHeightPx.toFloat()
    val collapseDistance = with(density) { 75.dp.toPx() }
    val collapseProgress = if (scrollOffset > collapseStart) {
        ((scrollOffset - collapseStart) / collapseDistance).coerceIn(0f, 1f)
    } else {
        0f
    }

    val heightDifference = expandedCardHeightDp - collapsedCardHeightDp
    val currentCardHeightDp = expandedCardHeightDp - (heightDifference * collapseProgress)
    val currentCardPadding = 20.dp - (8.dp * collapseProgress)

    val expandedCardHeightPx = with(density) { expandedCardHeightDp.toPx() }
    val contentTopPaddingPx = greetingHeightPx + expandedCardHeightPx

    Column(modifier = Modifier.fillMaxSize().background(HushhTheme.Bg)) {

        // ========================================================
        // EXACT COPY OF GLOBAL HEADER & TOP NAV (Fixed Position)
        // ========================================================
        Column(modifier = Modifier.fillMaxWidth().background(Color.Black)) {
            Spacer(Modifier.height(24.dp))
            HushhGlobalHeader()
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "For you", color = HushhTheme.TextSecondary, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.clickable { onTabSelect("For you") })
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "Analysis", color = HushhTheme.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    Box(modifier = Modifier.size(width = 40.dp, height = 3.dp).background(HushhTheme.Accent, RoundedCornerShape(50)))
                }
                Text(text = "Safe Pay", color = HushhTheme.TextSecondary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            HorizontalDivider(color = HushhTheme.BorderSubtle, thickness = 1.dp)
        }

        // ========================================================
        // Z-INDEXED SCROLLABLE AREA + OVERLAYS
        // ========================================================
        Box(modifier = Modifier.fillMaxSize().clipToBounds()) {

            // --- LAYER 1: SCROLLABLE CONTENT ---
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(top = with(density) { contentTopPaddingPx.toDp() })
                    .padding(start = 16.dp, end = 16.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                AnalysisInsightsSection(selectedBar = selectedBar)

                AnalysisCategoriesSection(categories = categories, onCategoryClick = onCategoryClick)

                AnalysisTrendSection(
                    selectedBar = selectedBar,
                    trendMode = trendMode,
                    trendPoints = trendPoints,
                    period = selectedPeriod,
                    onTrendModeChange = { trendMode = it }
                )
            }

            // --- LAYER 2: OVERLAY GREETING ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(0, greetingY.roundToInt()) }
                    .onGloballyPositioned { greetingHeightPx = it.size.height }
                    .padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 12.dp)
            ) {
                Text(
                    text = "Hello, Brian",
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // --- LAYER 3: OVERLAY HERO GRAPH CARD ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(0, pinnedHeroY.roundToInt()) }
                    .padding(horizontal = 16.dp)
            ) {
                AnalysisHeroCard(
                    period = selectedPeriod,
                    bars = bars,
                    selectedIndex = safeSelectedIndex,
                    selectedBar = selectedBar,
                    cardHeight = currentCardHeightDp,
                    cardPadding = currentCardPadding,
                    collapseProgress = collapseProgress,
                    onPeriodSelect = { selectedPeriod = it },
                    onBarSelect = { selectedBarIndex = it }
                )
            }
        }
    }
}

// ================================================================
// 5. HERO GRAPH CARD
// ================================================================
@Composable
private fun AnalysisHeroCard(
    period: AnalysisPeriod, bars: List<AnalysisBar>, selectedIndex: Int, selectedBar: AnalysisBar?,
    cardHeight: Dp, cardPadding: Dp, collapseProgress: Float,
    onPeriodSelect: (AnalysisPeriod) -> Unit, onBarSelect: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().height(cardHeight),
        colors = CardDefaults.cardColors(containerColor = HushhTheme.SurfaceAlt),
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, HushhTheme.BorderSubtle),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(cardPadding)) {
            AnalysisPeriodSelector(selected = period, onSelected = onPeriodSelect)

            Spacer(Modifier.height(16.dp))

            if (period != AnalysisPeriod.YEAR) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            val shrunkHeight = (placeable.height * (1f - collapseProgress))
                                .roundToInt()
                                .coerceAtLeast(0)
                            layout(placeable.width, shrunkHeight) {
                                placeable.placeRelative(0, 0)
                            }
                        }
                        .clipToBounds()
                ) {
                    AnimatedContent(targetState = selectedBar?.contextTitle, label = "GraphContext") { context ->
                        if (context != null) {
                            Text(
                                text = context,
                                color = HushhTheme.TextSecondary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .padding(bottom = 12.dp)
                                    .graphicsLayer { alpha = (1f - (collapseProgress * 2f)).coerceIn(0f, 1f) }
                            )
                        }
                    }
                }
            }

            Box(
                modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp))
                    .background(HushhTheme.Bg.copy(alpha = 0.45f))
                    .border(1.dp, HushhTheme.BorderSubtle.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 12.dp, vertical = 12.dp)
            ) {
                if (bars.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(text = "No transaction data", color = HushhTheme.TextSecondary, fontSize = 13.sp)
                    }
                } else {
                    AnalysisBarGraph(bars = bars, selectedIndex = selectedIndex, onBarSelect = onBarSelect)
                }
            }
        }
    }
}

// ================================================================
// 6. PERIOD SELECTOR
// ================================================================
@Composable
private fun AnalysisPeriodSelector(selected: AnalysisPeriod, onSelected: (AnalysisPeriod) -> Unit) {
    val options = AnalysisPeriod.entries
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(50))
            .background(HushhTheme.Bg.copy(alpha = 0.75f)).padding(4.dp)
    ) {
        val width = maxWidth / options.size
        val selectedIndex = options.indexOf(selected)
        val indicatorOffset by animateDpAsState(targetValue = width * selectedIndex, animationSpec = spring(dampingRatio = 0.85f, stiffness = 300f), label = "indicator")

        Box(modifier = Modifier.offset(x = indicatorOffset).width(width).fillMaxHeight().background(HushhTheme.Accent, RoundedCornerShape(50)))
        Row(modifier = Modifier.fillMaxSize()) {
            options.forEach { option ->
                val active = option == selected
                Box(
                    modifier = Modifier.weight(1f).fillMaxHeight().clickable(interactionSource = null, indication = null) { onSelected(option) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = option.displayName(), color = if (active) Color.Black else HushhTheme.TextSecondary, fontSize = 12.sp, fontWeight = if (active) FontWeight.Black else FontWeight.Medium)
                }
            }
        }
    }
}

private fun AnalysisPeriod.displayName(): String = when (this) {
    AnalysisPeriod.DAY -> "Day"
    AnalysisPeriod.WEEK -> "Week"
    AnalysisPeriod.MONTH -> "Month"
    AnalysisPeriod.YEAR -> "Year"
}

// ================================================================
// 7. BAR GRAPH
// ================================================================
@Composable
private fun AnalysisBarGraph(bars: List<AnalysisBar>, selectedIndex: Int, onBarSelect: (Int) -> Unit) {
    val maxAmount = bars.maxOfOrNull { it.amount }?.coerceAtLeast(1.0) ?: 1.0
    val density = LocalDensity.current

    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val top = 24.dp.toPx()
            val bottom = size.height - 24.dp.toPx()
            val usableHeight = bottom - top

            for (i in 0..2) {
                val y = top + usableHeight * (i / 2f)
                drawLine(
                    color = Color.White.copy(alpha = 0.055f), start = Offset(0f, y), end = Offset(size.width, y),
                    strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 12f))
                )
            }
            drawLine(color = Color.White.copy(alpha = 0.2f), start = Offset(0f, bottom), end = Offset(size.width, bottom), strokeWidth = 2.dp.toPx())
        }

        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom
        ) {
            bars.forEachIndexed { index, bar ->
                val selected = index == selectedIndex
                val isZero = bar.amount <= 0.0
                val targetFraction = if (maxAmount <= 0) 0f else (bar.amount / maxAmount).toFloat().coerceIn(0f, 1f)

                val visualTargetFraction = if (isZero) 0f else maxOf(0.08f, targetFraction)

                val fraction by animateFloatAsState(targetValue = visualTargetFraction, animationSpec = spring(dampingRatio = 0.82f, stiffness = 320f), label = "fraction")
                val barColor by animateColorAsState(targetValue = if (selected) HushhTheme.Accent else Color.White, animationSpec = tween(220), label = "color")
                val barWidth = if (selected) 20.dp else 12.dp

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .zIndex(if (selected) 10f else 1f)
                        .clickable(interactionSource = null, indication = null) { onBarSelect(index) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom
                ) {
                    BoxWithConstraints(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        val containerHeightPx = with(density) { maxHeight.toPx() }
                        val barStartYPx = containerHeightPx - with(density) { 24.dp.toPx() }
                        val barMaxDrawHeightPx = barStartYPx - with(density) { 40.dp.toPx() }

                        val renderedBarHeightPx = if (isZero) {
                            0f
                        } else {
                            (barMaxDrawHeightPx * fraction).coerceAtLeast(with(density) { 4.dp.toPx() })
                        }

                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val startY = size.height - 24.dp.toPx()
                            val maxDrawHeight = startY - 40.dp.toPx()
                            val barHeightPx = maxDrawHeight * fraction
                            val endY = startY - barHeightPx.coerceAtLeast(4.dp.toPx())
                            val barWidthPx = barWidth.toPx()

                            if (isZero) {
                                drawRoundRect(
                                    color = Color.DarkGray.copy(alpha = 0.4f),
                                    topLeft = Offset((size.width - barWidthPx) / 2, startY - 4f),
                                    size = Size(barWidthPx, 4f),
                                    cornerRadius = CornerRadius(2f, 2f)
                                )
                            } else {
                                if (selected) {
                                    drawRoundRect(
                                        color = HushhTheme.Accent.copy(alpha = 0.15f),
                                        topLeft = Offset((size.width - barWidthPx) / 2 - 6f, endY - 6f),
                                        size = Size(barWidthPx + 12f, startY - endY + 12f),
                                        cornerRadius = CornerRadius((barWidthPx + 12f) / 2, (barWidthPx + 12f) / 2)
                                    )
                                }
                                drawRoundRect(
                                    color = barColor,
                                    topLeft = Offset((size.width - barWidthPx) / 2, endY),
                                    size = Size(barWidthPx, startY - endY),
                                    cornerRadius = CornerRadius(barWidthPx / 2, barWidthPx / 2)
                                )
                            }
                        }

                        if (selected) {
                            Box(
                                modifier = Modifier
                                    .padding(bottom = 24.dp)
                                    .graphicsLayer {
                                        translationY = -renderedBarHeightPx - 8.dp.toPx()
                                    }
                            ) {
                                Box(
                                    modifier = Modifier
                                        .border(1.dp, HushhTheme.BorderStrong, RoundedCornerShape(8.dp))
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF1E2126))
                                        .padding(horizontal = 6.dp, vertical = 4.dp)
                                ) {
                                    Text(text = "KSh ${bar.amount.toInt()}", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Black)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    Text(text = bar.label, color = if (selected) Color.White else Color.White.copy(alpha = 0.5f), fontSize = 10.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }
}

// ================================================================
// 8. INSIGHTS
// ================================================================
@Composable
private fun AnalysisInsightsSection(selectedBar: AnalysisBar?) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Text(text = selectedBar?.selectionTitle ?: "Insights", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(8.dp))

        val txs = selectedBar?.transactions ?: emptyList()
        val total = selectedBar?.amount ?: 0.0
        val topMerchant = txs.groupBy { it.recipient }.maxByOrNull { it.value.size }?.key ?: "Unknown"

        val insightText = if (total > 0) {
            "You've spent KSh ${total.toInt()} over ${txs.size} transactions this period. Your most frequent expense destination was $topMerchant."
        } else {
            "No spending recorded for this period yet. When you make transactions, a summary of your activity will appear here."
        }

        Text(text = insightText, color = HushhTheme.TextSecondary, fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
    }
}

// ================================================================
// 9. CATEGORIES — MAX 3 COLUMNS
// ================================================================
@Composable
private fun AnalysisCategoriesSection(categories: List<AnalysisCategory>, onCategoryClick: (String) -> Unit) {
    var showAll by remember { mutableStateOf(false) }
    val visibleCategories = if (showAll) categories else categories.take(6)
    val hasMore = categories.size > 6

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Categories", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)

            if (hasMore) {
                Text(
                    text = if (showAll) "See less" else "See more",
                    color = HushhTheme.Accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { showAll = !showAll }
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        if (categories.isEmpty()) {
            Text(text = "No categories for this period", color = HushhTheme.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp))
        } else {
            visibleCategories.chunked(3).forEach { rowCategories ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    rowCategories.forEach { category ->
                        PremiumAnalysisCategoryCard(category = category, modifier = Modifier.weight(1f), onClick = { onCategoryClick(category.name) })
                    }
                    repeat(3 - rowCategories.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun PremiumAnalysisCategoryCard(category: AnalysisCategory, modifier: Modifier, onClick: () -> Unit) {
    val hasActivity = category.amount > 0.0

    Card(
        modifier = modifier.height(112.dp).clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = HushhTheme.Surface),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, if (category.isUnlabeled) HushhTheme.Accent.copy(alpha = 0.35f) else HushhTheme.BorderSubtle)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (hasActivity || category.isUnlabeled) Color(0xFF2A1644) else Color(0xFF2A1644).copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center
            ) {
                val tint = if (hasActivity || category.isUnlabeled) HushhTheme.Accent else HushhTheme.Accent.copy(alpha = 0.55f)
                Icon(
                    imageVector = category.icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column {
                Text(text = category.name, color = HushhTheme.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = "KSh ${category.amount.toInt()}",
                    color = if (hasActivity) Color.White else HushhTheme.TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }
    }
}

// ================================================================
// 10. SPENDING TREND — INTERACTIVE DRILL-DOWN GRAPH
// ================================================================
@Composable
private fun AnalysisTrendSection(
    selectedBar: AnalysisBar?,
    trendMode: TrendMode,
    trendPoints: List<TrendPoint>,
    period: AnalysisPeriod,
    onTrendModeChange: (TrendMode) -> Unit
) {
    var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            currentTime = System.currentTimeMillis()
            kotlinx.coroutines.delay(30_000L)
        }
    }

    var selectedPointIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(selectedBar, period, trendPoints) {
        val now = System.currentTimeMillis()
        val isCurrentBar = selectedBar != null && now in selectedBar.startMillis..selectedBar.endMillis

        selectedPointIndex = if (isCurrentBar) {
            trendPoints.indexOfLast { now >= it.startMillis }.coerceAtLeast(0)
        } else {
            trendPoints.indexOfLast { it.amount > 0 || it.count > 0 }.coerceAtLeast(0)
        }
    }

    val safeSelectedIndex = selectedPointIndex.coerceIn(0, trendPoints.lastIndex.coerceAtLeast(0))
    val selectedPoint = trendPoints.getOrNull(safeSelectedIndex)

    val totalAmount = selectedBar?.amount ?: 0.0
    val totalCount = selectedBar?.transactions?.size ?: 0
    val selectedPointCount = selectedPoint?.count ?: 0
    val selectedPointAmount = selectedPoint?.amount ?: 0.0

    val metricPercentage = if (trendMode == TrendMode.AMOUNT) {
        if (totalAmount > 0) (selectedPointAmount / totalAmount) * 100.0 else 0.0
    } else {
        if (totalCount > 0) (selectedPointCount.toDouble() / totalCount) * 100.0 else 0.0
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Spending Trend", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
            TrendModeSelector(selected = trendMode, onSelected = onTrendModeChange)
        }
        Spacer(Modifier.height(14.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = HushhTheme.Surface),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, HushhTheme.BorderSubtle)
        ) {
            Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp)) {

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                    Column(modifier = Modifier.weight(1f)) {
                        val leftTitle = selectedBar?.contextTitle ?: selectedBar?.selectionTitle ?: "SELECTED PERIOD"
                        Text(
                            text = leftTitle.uppercase(),
                            color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Black
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(text = "KSh ${totalAmount.toInt()}", color = HushhTheme.Accent, fontSize = 14.sp, fontWeight = FontWeight.Black)
                        Text(text = "$totalCount transactions", color = HushhTheme.TextSecondary, fontSize = 11.sp)
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = (selectedPoint?.let { trendPointTitle(it, period) } ?: "--").uppercase(),
                            color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = if (trendMode == TrendMode.AMOUNT) "KSh ${selectedPointAmount.toInt()}" else "$selectedPointCount tx",
                            color = HushhTheme.Accent, fontSize = 14.sp, fontWeight = FontWeight.Black
                        )
                        if (totalAmount > 0 || totalCount > 0) {
                            val labelStr = if (trendMode == TrendMode.AMOUNT) "spending" else "transactions"
                            Text(text = "${metricPercentage.roundToTwo()}% of $labelStr", color = HushhTheme.TextSecondary, fontSize = 10.sp)
                        }
                    }
                }

                Spacer(Modifier.height(22.dp))

                if (trendPoints.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                        Text(text = "No activity in this period", color = HushhTheme.TextSecondary, fontSize = 13.sp)
                    }
                } else {
                    InteractiveTrendGraph(
                        points = trendPoints, mode = trendMode,
                        selectedIndex = safeSelectedIndex, period = period,
                        currentTime = currentTime,
                        onPointSelected = { selectedPointIndex = it }
                    )
                }
            }
        }
    }
}

// ================================================================
// 11. AMOUNT / COUNT SWITCH
// ================================================================
@Composable
private fun TrendModeSelector(selected: TrendMode, onSelected: (TrendMode) -> Unit) {
    Row(modifier = Modifier.clip(RoundedCornerShape(50)).background(HushhTheme.SurfaceAlt).padding(3.dp)) {
        TrendMode.entries.forEach { mode ->
            val active = selected == mode
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (active) HushhTheme.Accent else Color.Transparent)
                    .clickable { onSelected(mode) }
                    .padding(horizontal = 11.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when (mode) { TrendMode.AMOUNT -> "Amount"; TrendMode.COUNT -> "Count" },
                    color = if (active) Color.Black else HushhTheme.TextSecondary,
                    fontSize = 10.sp, fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ================================================================
// 12. REAL LINE GRAPH (Clamped Splines, Unified Gestures, Accurate Align)
// ================================================================
@Composable
private fun InteractiveTrendGraph(points: List<TrendPoint>, mode: TrendMode, selectedIndex: Int, period: AnalysisPeriod, currentTime: Long, onPointSelected: (Int) -> Unit) {
    val values = points.map { if (mode == TrendMode.AMOUNT) it.amount else it.count.toDouble() }
    val maxValue = values.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0

    BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(205.dp)) {
        val density = LocalDensity.current
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()

        val left = with(density) { 12.dp.toPx() }
        val right = widthPx - with(density) { 12.dp.toPx() }
        val top = with(density) { 12.dp.toPx() }
        val bottom = heightPx - with(density) { 32.dp.toPx() }
        val graphWidth = right - left
        val graphHeight = bottom - top

        Canvas(
            modifier = Modifier.fillMaxSize()
                .pointerInput(points, mode) {
                    awaitEachGesture {
                        val downEvent = awaitFirstDown()
                        val step = if (points.size > 1) { graphWidth / (points.size - 1) } else { 0f }
                        val nearestIndex = if (points.size == 1) 0 else {
                            ((downEvent.position.x - left) / step).roundToInt().coerceIn(0, points.lastIndex)
                        }
                        onPointSelected(nearestIndex)

                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull()
                            if (change != null && change.pressed) {
                                val nIdx = if (points.size == 1) 0 else {
                                    ((change.position.x - left) / step).roundToInt().coerceIn(0, points.lastIndex)
                                }
                                onPointSelected(nIdx)
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
        ) {
            for (i in 0..2) {
                val y = top + graphHeight * (i / 2f)
                drawLine(
                    color = Color.White.copy(alpha = 0.045f), start = Offset(left, y), end = Offset(right, y),
                    strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                )
            }

            if (points.isEmpty()) return@Canvas

            val dx = if (points.size > 1) { graphWidth / (points.size - 1) } else { 0f }
            val graphPoints = values.mapIndexed { index, value ->
                val normalized = (value / maxValue).toFloat().coerceIn(0f, 1f)
                Offset(x = if (points.size == 1) (left + right) / 2f else left + dx * index, y = bottom - graphHeight * normalized)
            }

            val safeIndex = selectedIndex.coerceIn(0, graphPoints.lastIndex.coerceAtLeast(0))
            val selectedX = graphPoints[safeIndex].x
            drawLine(
                color = Color.White.copy(alpha = 0.1f), start = Offset(selectedX, top), end = Offset(selectedX, bottom), strokeWidth = 1.5.dp.toPx()
            )

            if (graphPoints.size > 1) {
                for (i in 0 until graphPoints.lastIndex) {
                    val p0 = graphPoints[(i - 1).coerceAtLeast(0)]
                    val p1 = graphPoints[i]
                    val p2 = graphPoints[i + 1]
                    val p3 = graphPoints[(i + 2).coerceAtMost(graphPoints.lastIndex)]

                    val isCompleted = points[i + 1].endMillis <= currentTime

                    val tension = 0.2f
                    val cp1x = p1.x + (p2.x - p0.x) * tension
                    val cp1y = p1.y + (p2.y - p0.y) * tension

                    val cp2x = p2.x - (p3.x - p1.x) * tension
                    val cp2y = p2.y - (p3.y - p1.y) * tension

                    val minY = min(p1.y, p2.y)
                    val maxY = max(p1.y, p2.y)
                    val safeCp1y = cp1y.coerceIn(minY, maxY)
                    val safeCp2y = cp2y.coerceIn(minY, maxY)

                    val path = Path().apply {
                        moveTo(p1.x, p1.y)
                        cubicTo(cp1x, safeCp1y, cp2x, safeCp2y, p2.x, p2.y)
                    }

                    drawPath(
                        path = path,
                        color = if (isCompleted) HushhTheme.Accent else Color.White.copy(alpha = 0.20f),
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }

            graphPoints.forEachIndexed { index, point ->
                val isCompleted = points[index].endMillis <= currentTime
                val isSelected = index == safeIndex
                val pointColor = if (isCompleted) HushhTheme.Accent else Color.White.copy(alpha = 0.25f)

                if (isSelected) {
                    drawCircle(color = HushhTheme.Accent.copy(alpha = 0.18f), radius = 11.dp.toPx(), center = point)
                }
                drawCircle(color = Color.Black, radius = if (isSelected) 6.dp.toPx() else 4.dp.toPx(), center = point)
                drawCircle(color = pointColor, radius = if (isSelected) 4.dp.toPx() else 2.5.dp.toPx(), center = point)
            }

            drawLine(color = Color.White.copy(alpha = 0.12f), start = Offset(left, bottom), end = Offset(right, bottom), strokeWidth = 1.dp.toPx())
        }

        val indexes = when (period) {
            AnalysisPeriod.DAY -> listOf(1, 6, 12, 18, 23).filter { it < points.size }
            AnalysisPeriod.WEEK -> listOf(0, 2, 4).filter { it < points.size }
            AnalysisPeriod.MONTH -> listOf(0, points.lastIndex / 2, points.lastIndex).distinct()
            AnalysisPeriod.YEAR -> listOf(0, 6, 11).filter { it < points.size }
        }

        val dx = if (points.size > 1) { graphWidth / (points.size - 1) } else { 0f }

        indexes.forEach { index ->
            val pointX = if (points.size == 1) (left + right) / 2f else left + dx * index
            Text(
                text = trendXAxisLabel(points[index], period),
                color = HushhTheme.TextSecondary,
                fontSize = 9.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.offset {
                    IntOffset((pointX - with(density) { 14.dp.toPx() }).roundToInt(), (bottom + with(density) { 8.dp.toPx() }).roundToInt())
                }
            )
        }
    }
}

// ================================================================
// 13. HUSHH CATEGORY REGISTRY
// The UI-owned universe of categories. This forms our Single Source of Truth
// ensuring the Home, Analysis, and Labeling components all stay in perfect sync.
// ================================================================


// ================================================================
// 13B. RANKING ENGINE
// UI owns the category universe (CategoryRegistry.masterList + uncategorized).
// User data only determines each card's amount/count and its rank —
// it never determines whether a card exists.
// ================================================================

private fun buildAnalysisCategories(transactions: List<TransactionEntity>): List<AnalysisCategory> {
    // Bucket this period's transactions by resolved category id (stable key, not displayName)
    val grouped = transactions.groupBy { CategoryRegistry.getCategoryByName(it.category).id }

    // One card per real registry category, always — even with zero transactions
    val realCategoryResults = CategoryRegistry.masterList.map { def ->
        val txs = grouped[def.id] ?: emptyList()
        AnalysisCategory(
            name = def.displayName,
            amount = txs.sumOf { it.analysisAmount() },
            count = txs.size,
            icon = def.icon,
            isUnlabeled = false
        )
    }

    // Uncategorized is its own permanent bucket — never folded into the ranking pool
    val uncategorizedTxs = grouped[CategoryRegistry.uncategorized.id] ?: emptyList()
    val uncategorizedResult = AnalysisCategory(
        name = CategoryRegistry.uncategorized.displayName,
        amount = uncategorizedTxs.sumOf { it.analysisAmount() },
        count = uncategorizedTxs.size,
        icon = CategoryRegistry.uncategorized.icon,
        isUnlabeled = true
    )

    // Ranking hierarchy: spending desc → Uncategorized → zero-spend categories
    val withSpending = realCategoryResults.filter { it.amount > 0.0 }.sortedByDescending { it.amount }
    val zeroSpend = realCategoryResults.filter { it.amount <= 0.0 }

    return withSpending + uncategorizedResult + zeroSpend
}

// ================================================================
// 14. ANALYSIS BAR AGGREGATION ENGINE
// ================================================================
private fun buildAnalysisBars(transactions: List<TransactionEntity>, period: AnalysisPeriod): List<AnalysisBar> {
    if (transactions.isEmpty()) return emptyList()

    return when (period) {
        AnalysisPeriod.DAY -> {
            (6 downTo 0).map { offset ->
                val start = startOfDayOffset(offset)
                val end = endOfDayOffset(offset)
                val txs = transactions.filter { it.timestamp in start..end }
                val date = Date(start)
                val dayName = SimpleDateFormat("EEE", Locale.getDefault()).format(date)

                val selection = when (offset) {
                    0 -> "Today"
                    1 -> "Yesterday"
                    else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(date)
                }

                val label = when (offset) {
                    0 -> "Today"
                    1 -> "Yest"
                    else -> dayName
                }

                val contextTitle = if (offset <= 6) "This week" else "Last week"
                AnalysisBar(label = label, selectionTitle = selection, contextTitle = contextTitle, amount = txs.sumOf { it.analysisAmount() }, transactions = txs, startMillis = start, endMillis = end)
            }
        }
        AnalysisPeriod.WEEK -> {
            (4 downTo 0).map { offset ->
                val start = startOfWeekOffset(offset)
                val end = endOfWeekOffset(offset)
                val txs = transactions.filter { it.timestamp in start..end }

                val label = when (offset) {
                    0 -> "This week"
                    1 -> "Last week"
                    else -> "${SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(start))}–${SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(end))}"
                }

                val contextTitle = if (offset <= 3) "This month" else "Last month"
                AnalysisBar(label = label, selectionTitle = label, contextTitle = contextTitle, amount = txs.sumOf { it.analysisAmount() }, transactions = txs, startMillis = start, endMillis = end)
            }
        }
        AnalysisPeriod.MONTH -> {
            val currentYear = Calendar.getInstance().get(Calendar.YEAR)
            val currentMonth = Calendar.getInstance().get(Calendar.MONTH)

            (0..11).mapNotNull { month ->
                val startCal = Calendar.getInstance().apply { set(currentYear, month, 1, 0, 0, 0); set(Calendar.MILLISECOND, 0) }
                val endCal = Calendar.getInstance().apply {
                    timeInMillis = startCal.timeInMillis
                    set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
                    set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
                }
                if (startCal.timeInMillis > System.currentTimeMillis()) null else {
                    val txs = transactions.filter { it.timestamp in startCal.timeInMillis..endCal.timeInMillis }

                    val label = when (month) {
                        currentMonth -> "This month"
                        currentMonth - 1 -> "Last month"
                        else -> SimpleDateFormat("MMM", Locale.getDefault()).format(startCal.time)
                    }

                    val selectionTitle = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(startCal.time)
                    AnalysisBar(label = label, selectionTitle = selectionTitle, contextTitle = "This year", amount = txs.sumOf { it.analysisAmount() }, transactions = txs, startMillis = startCal.timeInMillis, endMillis = endCal.timeInMillis)
                }
            }
        }
        AnalysisPeriod.YEAR -> {
            val currentYear = Calendar.getInstance().get(Calendar.YEAR)
            (currentYear - 3..currentYear).map { year ->
                val start = Calendar.getInstance().apply { set(year, Calendar.JANUARY, 1, 0, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
                val end = Calendar.getInstance().apply { set(year, Calendar.DECEMBER, 31, 23, 59, 59); set(Calendar.MILLISECOND, 999) }.timeInMillis
                val txs = transactions.filter { it.timestamp in start..end }

                val label = when (year) {
                    currentYear -> "This yr"
                    currentYear - 1 -> "Last yr"
                    else -> year.toString()
                }

                AnalysisBar(label = label, selectionTitle = year.toString(), contextTitle = null, amount = txs.sumOf { it.analysisAmount() }, transactions = txs, startMillis = start, endMillis = end)
            }
        }
    }
}

// ================================================================
// 15. DATE HELPERS
// ================================================================
private fun startOfDayOffset(offset: Int): Long = Calendar.getInstance().apply {
    add(Calendar.DAY_OF_YEAR, -offset); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun endOfDayOffset(offset: Int): Long = Calendar.getInstance().apply {
    add(Calendar.DAY_OF_YEAR, -offset); set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
}.timeInMillis

private fun startOfWeekOffset(offset: Int): Long = Calendar.getInstance().apply {
    add(Calendar.WEEK_OF_YEAR, -offset); set(Calendar.DAY_OF_WEEK, firstDayOfWeek); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun endOfWeekOffset(offset: Int): Long = Calendar.getInstance().apply {
    add(Calendar.WEEK_OF_YEAR, -offset); set(Calendar.DAY_OF_WEEK, firstDayOfWeek); add(Calendar.DAY_OF_YEAR, 6)
    set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
}.timeInMillis

// ================================================================
// 16. REAL TREND ENGINE (Time-Aware Boundaries)
// ================================================================
private fun buildTrendPoints(transactions: List<TransactionEntity>, selectedBar: AnalysisBar?, period: AnalysisPeriod): List<TrendPoint> {
    if (transactions.isEmpty() || selectedBar == null) return emptyList()

    return when (period) {
        AnalysisPeriod.DAY -> {
            (0..23).map { hour ->
                val hourStart = Calendar.getInstance().apply {
                    timeInMillis = selectedBar.startMillis
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis

                val hourEnd = Calendar.getInstance().apply {
                    timeInMillis = hourStart
                    set(Calendar.MINUTE, 59)
                    set(Calendar.SECOND, 59)
                    set(Calendar.MILLISECOND, 999)
                }.timeInMillis

                val txs = transactions.filter { it.timestamp in hourStart..hourEnd }
                TrendPoint(
                    label = when { hour == 0 -> "12a"; hour == 12 -> "12p"; hour < 12 -> "${hour}a"; else -> "${hour - 12}p" },
                    amount = txs.sumOf { it.analysisAmount() },
                    count = txs.size,
                    startMillis = hourStart,
                    endMillis = hourEnd
                )
            }
        }
        AnalysisPeriod.WEEK -> {
            (0..6).map { dayOffset ->
                val startCal = Calendar.getInstance().apply {
                    timeInMillis = selectedBar.startMillis; add(Calendar.DAY_OF_YEAR, dayOffset)
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                val endCal = (startCal.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999) }
                val txs = transactions.filter { it.timestamp in startCal.timeInMillis..endCal.timeInMillis }
                TrendPoint(
                    label = SimpleDateFormat("EEE", Locale.getDefault()).format(startCal.time),
                    amount = txs.sumOf { it.analysisAmount() },
                    count = txs.size,
                    startMillis = startCal.timeInMillis,
                    endMillis = endCal.timeInMillis
                )
            }
        }
        AnalysisPeriod.MONTH -> {
            val start = Calendar.getInstance().apply { timeInMillis = selectedBar.startMillis }
            val days = start.getActualMaximum(Calendar.DAY_OF_MONTH)
            (1..days).map { day ->
                val dayStart = Calendar.getInstance().apply {
                    timeInMillis = selectedBar.startMillis; set(Calendar.DAY_OF_MONTH, day)
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                val dayEnd = (dayStart.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999) }
                val txs = transactions.filter { it.timestamp in dayStart.timeInMillis..dayEnd.timeInMillis }
                TrendPoint(
                    label = day.toString(),
                    amount = txs.sumOf { it.analysisAmount() },
                    count = txs.size,
                    startMillis = dayStart.timeInMillis,
                    endMillis = dayEnd.timeInMillis
                )
            }
        }
        AnalysisPeriod.YEAR -> {
            (0..11).map { month ->
                val monthStart = Calendar.getInstance().apply {
                    timeInMillis = selectedBar.startMillis; set(Calendar.MONTH, month); set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                val monthEnd = (monthStart.clone() as Calendar).apply {
                    set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
                    set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
                }
                val txs = transactions.filter { it.timestamp in monthStart.timeInMillis..monthEnd.timeInMillis }
                TrendPoint(
                    label = SimpleDateFormat("MMM", Locale.getDefault()).format(monthStart.time),
                    amount = txs.sumOf { it.analysisAmount() },
                    count = txs.size,
                    startMillis = monthStart.timeInMillis,
                    endMillis = monthEnd.timeInMillis
                )
            }
        }
    }
}

// ================================================================
// 17. CATEGORY DETAIL VIEW
// ================================================================
@Composable
fun CategoryDetailView(categoryName: String, viewModel: DashboardViewModel, onBack: () -> Unit) {
    val transactions by viewModel.transactions.collectAsState()
    var selectedFilter by remember { mutableStateOf("This Week") }

    val isUncategorized = categoryName == CategoryRegistry.uncategorized.displayName

    val categoryTxs = remember(transactions, categoryName) {
        if (isUncategorized) {
            // 🚀 Matches Home's unlabeled-activity filter exactly, so every row here
            // has a matching card to jump to on the Home pager.
            transactions.filter { !it.isLabeled && !it.isDiscarded }
        } else {
            transactions.filter { CategoryRegistry.getCategoryByName(it.category).displayName == categoryName }
        }
    }

    val merchants = remember(categoryTxs, isUncategorized) {
        if (isUncategorized) emptyList() else categoryTxs.groupBy { it.recipient }.map { (name, txs) ->
            val count = "${txs.size} times"
            val total = txs.sumOf { it.analysisAmount() }.toFloat()
            Triple(name, count, total)
        }.sortedByDescending { it.third }
    }

    Column(modifier = Modifier.fillMaxSize().background(HushhTheme.Bg).padding(top = 24.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Color.White) }
            Spacer(Modifier.width(8.dp))
            Text("$categoryName Analytics", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(24.dp))
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("This Week", "Last Week", "Month").forEach { filter ->
                val isSelected = filter == selectedFilter
                Surface(
                    color = if (isSelected) HushhTheme.SurfaceAlt else Color.Transparent,
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, if (isSelected) HushhTheme.BorderSubtle else Color.DarkGray),
                    modifier = Modifier.clickable { selectedFilter = filter }.padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(filter, color = if (isSelected) Color.White else Color.Gray, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(32.dp))
        Text(
            text = if (isUncategorized) "Tap to label" else "Merchant Ranking",
            color = Color.Gray, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Spacer(Modifier.height(16.dp))

        LazyColumn(contentPadding = PaddingValues(start = 20.dp, top = 0.dp, end = 20.dp, bottom = 100.dp)) {
            if (isUncategorized) {
                items(categoryTxs) { tx ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.requestJumpToUnlabeled(tx.transactionId)
                                onBack()   // return to Analysis root; MainAppEngine flips to Home in response to the pending jump
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.size(36.dp).background(HushhTheme.Surface, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Label, null, tint = HushhTheme.Accent, modifier = Modifier.size(16.dp))
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(tx.recipient, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text(tx.dateString, color = Color.Gray, fontSize = 12.sp)
                        }
                        Icon(Icons.Rounded.ChevronRight, null, tint = Color.Gray, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("KSh ${tx.analysisAmount().toInt()}", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Black)
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                }
            } else {
                items(merchants) { (name, count, amt) ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(36.dp).background(HushhTheme.Surface, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Storefront, null, tint = HushhTheme.Accent, modifier = Modifier.size(16.dp))
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text(count, color = Color.Gray, fontSize = 12.sp)
                        }
                        Text("KSh ${amt.toInt()}", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Black)
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                }
            }
        }
    }
}
