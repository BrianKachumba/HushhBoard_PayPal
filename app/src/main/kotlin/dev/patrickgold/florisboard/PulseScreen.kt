package dev.patrickgold.florisboard

import androidx.compose.animation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.patrickgold.florisboard.ussd.TransactionEntity
import dev.patrickgold.florisboard.ussd.TransactionType
import java.util.*
import kotlin.math.absoluteValue

// ================================================================
// 🎨 DESIGN SYSTEM (Unified Theme - Only declared here!)
// ================================================================
object HushhTheme {
    val Bg = Color(0xFF000000)
    val Surface = Color(0xFF1A1C1E)
    val SurfaceAlt = Color(0xFF23262B)
    val Accent = Color(0xFF00E676)

    val TextPrimary = Color.White
    val TextSecondary = Color(0xFFA0A0A0)

    val BorderSubtle = Color.White.copy(alpha = 0.12f)
    val BorderStrong = Color.White.copy(alpha = 0.25f)

    // 📊 Monochromatic Donut Chart Accents
    val TillColor = Accent
    val SendColor = Color(0xFF2979FF) // Soft Blue for Phone
    val PaybillColor = Color(0xFFD500F9) // Purple for Paybill
}

// 🚀 REUSABLE GLOBAL HEADER (WhatsApp Style)
@Composable
fun HushhGlobalHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            "HushhBoard",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Black
        )

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.NotificationsNone, contentDescription = "Alerts", tint = Color.White, modifier = Modifier.size(28.dp))
            Box(modifier = Modifier.size(36.dp).border(1.dp, Color.Gray, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Person, contentDescription = "Profile", tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }
    }
}

// 🚀 DATA BREAKDOWN ENGINE
data class PulseStats(
    val totalSpent: Float,
    val previousSpent: Float,
    val totalFees: Float,
    val transactionCount: Int,
    val tillPercent: Float,
    val sendPercent: Float,
    val paybillPercent: Float,
    val favoriteMerchant: String,
    val favCount: Int,
    val tillTotal: Float,
    val sendTotal: Float,
    val paybillTotal: Float
)

fun calculatePulseStats(filteredTx: List<TransactionEntity>, previousTx: List<TransactionEntity>): PulseStats {
    val totalSpent = filteredTx.sumOf { it.amount.replace(",", "").toDoubleOrNull() ?: 0.0 }.toFloat()
    val previousSpent = previousTx.sumOf { it.amount.replace(",", "").toDoubleOrNull() ?: 0.0 }.toFloat()
    val totalFees = filteredTx.sumOf { it.fee.replace(",", "").toDoubleOrNull() ?: 0.0 }.toFloat()

    val tillTotal = filteredTx.filter { it.type == TransactionType.TILL.name }.sumOf { it.amount.replace(",", "").toDoubleOrNull() ?: 0.0 }.toFloat()
    val sendTotal = filteredTx.filter { it.type == TransactionType.SEND_MONEY.name }.sumOf { it.amount.replace(",", "").toDoubleOrNull() ?: 0.0 }.toFloat()
    val paybillTotal = filteredTx.filter { it.type == TransactionType.PAYBILL.name }.sumOf { it.amount.replace(",", "").toDoubleOrNull() ?: 0.0 }.toFloat()

    val favGroup = filteredTx.groupBy { it.recipient }.maxByOrNull { it.value.size }

    return PulseStats(
        totalSpent = totalSpent,
        previousSpent = previousSpent,
        totalFees = totalFees,
        transactionCount = filteredTx.size,
        tillPercent = if (totalSpent > 0) (tillTotal / totalSpent) * 100f else 0f,
        sendPercent = if (totalSpent > 0) (sendTotal / totalSpent) * 100f else 0f,
        paybillPercent = if (totalSpent > 0) (paybillTotal / totalSpent) * 100f else 0f,
        favoriteMerchant = favGroup?.key ?: "No Data",
        favCount = favGroup?.value?.size ?: 0,
        tillTotal = tillTotal,
        sendTotal = sendTotal,
        paybillTotal = paybillTotal
    )
}

@Composable
fun PulseDashboardScreen(viewModel: DashboardViewModel, onOpenWrap: () -> Unit = {}) {
    var activeSubTab by remember { mutableStateOf("For you") }

    Crossfade(targetState = activeSubTab, label = "PaySubTab") { tab ->
        when (tab) {
            "For you" -> PulseForYouScreen(
                viewModel = viewModel,
                onOpenWrap = onOpenWrap,
                onTabSelect = { activeSubTab = it }
            )
            "Analysis" -> PulseAnalysisScreen(
                viewModel = viewModel,
                onTabSelect = { activeSubTab = it }
            )
        }
    }
}

@Composable
fun PulseForYouScreen(viewModel: DashboardViewModel, onOpenWrap: () -> Unit = {}, onTabSelect: (String) -> Unit = {}) {
    val transactions by viewModel.transactions.collectAsState()
    var selectedTimeframe by remember { mutableStateOf("Today") }

    val stats = remember(transactions, selectedTimeframe) {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val startOfToday = cal.timeInMillis
        val startOfYesterday = startOfToday - (24 * 60 * 60 * 1000L)

        val calWeek = Calendar.getInstance()
        calWeek.set(Calendar.DAY_OF_WEEK, calWeek.firstDayOfWeek)
        calWeek.set(Calendar.HOUR_OF_DAY, 0); calWeek.set(Calendar.MINUTE, 0); calWeek.set(Calendar.SECOND, 0); calWeek.set(Calendar.MILLISECOND, 0)
        val startOfWeek = calWeek.timeInMillis

        val calMonth = Calendar.getInstance()
        calMonth.set(Calendar.DAY_OF_MONTH, 1)
        calMonth.set(Calendar.HOUR_OF_DAY, 0); calMonth.set(Calendar.MINUTE, 0); calMonth.set(Calendar.SECOND, 0); calMonth.set(Calendar.MILLISECOND, 0)
        val startOfMonth = calMonth.timeInMillis

        val filteredTx = when (selectedTimeframe) {
            "Today" -> transactions.filter { it.timestamp >= startOfToday }
            "Yesterday" -> transactions.filter { it.timestamp in startOfYesterday until startOfToday }
            "Week" -> transactions.filter { it.timestamp >= startOfWeek }
            "Month" -> transactions.filter { it.timestamp >= startOfMonth }
            else -> transactions
        }

        val previousTx = when (selectedTimeframe) {
            "Today" -> transactions.filter { it.timestamp in startOfYesterday until startOfToday }
            "Yesterday" -> transactions.filter { it.timestamp in (startOfYesterday - (24 * 60 * 60 * 1000L)) until startOfYesterday }
            "Week" -> transactions.filter { it.timestamp in (startOfWeek - (7 * 24 * 60 * 60 * 1000L)) until startOfWeek }
            "Month" -> transactions.filter { it.timestamp in (startOfMonth - (30 * 24 * 60 * 60 * 1000L)) until startOfMonth }
            else -> emptyList()
        }

        calculatePulseStats(filteredTx, previousTx)
    }

    Column(modifier = Modifier.fillMaxSize().padding(top = 24.dp)) {
        HushhGlobalHeader()
        Spacer(Modifier.height(16.dp))

        // Tab Navigation
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("For you", color = HushhTheme.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Box(Modifier.size(width = 40.dp, height = 3.dp).background(HushhTheme.Accent, RoundedCornerShape(50)))
            }
            Text("Analysis", color = HushhTheme.TextSecondary, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.clickable { onTabSelect("Analysis") })
            Text("Safe Pay", color = HushhTheme.TextSecondary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }

        HorizontalDivider(color = HushhTheme.BorderSubtle, thickness = 1.dp)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .bouncyScroll(Orientation.Vertical)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(24.dp))

            LazyRow(
                modifier = Modifier.bouncyScroll(Orientation.Horizontal),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(6) { index ->
                    Box(
                        modifier = Modifier
                            .width(105.dp)
                            .height(190.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .border(1.dp, HushhTheme.BorderSubtle.copy(alpha = 0.3f), RoundedCornerShape(24.dp))
                            .background(HushhTheme.Surface, RoundedCornerShape(24.dp))
                            .clickable { onOpenWrap() },
                        contentAlignment = Alignment.Center
                    ) {
                        if (index == 0) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Rounded.Add, "Add", tint = HushhTheme.TextSecondary, modifier = Modifier.size(24.dp))
                                Spacer(Modifier.height(4.dp))
                                Text("Your Wrap", color = HushhTheme.TextSecondary, fontSize = 11.sp)
                            }
                        } else {
                            Icon(Icons.Rounded.Insights, null, tint = HushhTheme.BorderSubtle, modifier = Modifier.size(28.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = HushhTheme.SurfaceAlt),
                shape = RoundedCornerShape(28.dp),
                border = BorderStroke(1.dp, HushhTheme.BorderSubtle)
            ) {
                Column(Modifier.padding(24.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Today", "Yesterday", "Week", "Month").forEach { tf ->
                            val isSelected = selectedTimeframe == tf
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(if (isSelected) HushhTheme.Accent else HushhTheme.Bg)
                                    .border(1.dp, if (isSelected) HushhTheme.Accent else HushhTheme.BorderStrong, RoundedCornerShape(20.dp))
                                    .clickable { selectedTimeframe = tf }
                                    .padding(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = tf,
                                    color = if (isSelected) Color.Black else HushhTheme.TextSecondary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(32.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        InteractiveDonutChartBox(
                            stats = stats,
                            modifier = Modifier.weight(1.2f)
                        )

                        Spacer(Modifier.width(20.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            val diff = stats.totalSpent - stats.previousSpent
                            val arrow = if (diff <= 0) "↓" else "↑"
                            val containerColor = if (diff <= 0) HushhTheme.Accent else Color(0xFFFF5252)
                            val perc = if (stats.previousSpent > 0) ((diff.absoluteValue / stats.previousSpent) * 100).toInt() else 0

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(text = "$arrow $perc% ", color = containerColor, fontSize = 16.sp, fontWeight = FontWeight.Black)
                                Text(text = "vs Last", color = HushhTheme.TextSecondary, fontSize = 13.sp)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("${stats.transactionCount} transactions.", color = HushhTheme.TextSecondary, fontSize = 12.sp)

                            Spacer(Modifier.height(16.dp))
                            DonutLegendItem("Till", stats.tillPercent, HushhTheme.TillColor)
                            Spacer(Modifier.height(8.dp))
                            DonutLegendItem("Phone", stats.sendPercent, HushhTheme.SendColor)
                            Spacer(Modifier.height(8.dp))
                            DonutLegendItem("Paybill", stats.paybillPercent, HushhTheme.PaybillColor)
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Card(
                    modifier = Modifier.weight(1f).height(135.dp),
                    colors = CardDefaults.cardColors(containerColor = HushhTheme.Surface),
                    shape = RoundedCornerShape(22.dp),
                    border = BorderStroke(1.dp, HushhTheme.BorderSubtle)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Box(modifier = Modifier.size(32.dp).border(1.dp, HushhTheme.BorderStrong, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Receipt, null, tint = HushhTheme.TextPrimary, modifier = Modifier.size(16.dp))
                        }
                        Spacer(Modifier.weight(1f))
                        Text("KSh ${stats.totalFees}", color = HushhTheme.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Black)
                        Text("Total Transaction Cost", color = HushhTheme.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Text("${stats.transactionCount} transactions", color = HushhTheme.TextSecondary, fontSize = 11.sp)
                    }
                }

                Card(
                    modifier = Modifier.weight(1f).height(135.dp),
                    colors = CardDefaults.cardColors(containerColor = HushhTheme.Surface),
                    shape = RoundedCornerShape(22.dp),
                    border = BorderStroke(1.dp, HushhTheme.BorderSubtle)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Box(modifier = Modifier.size(32.dp).border(1.dp, HushhTheme.BorderStrong, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Favorite, tint = Color(0xFFFF5252), contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                        Spacer(Modifier.weight(1f))
                        Text("Favorite Recipient", color = HushhTheme.TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(text = stats.favoriteMerchant.uppercase(), color = HushhTheme.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("used ${stats.favCount} times today", color = HushhTheme.TextSecondary, fontSize = 11.sp)
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

enum class DonutSegment { TILL, SEND, PAYBILL, NONE }

@Composable
fun InteractiveDonutChartBox(stats: PulseStats, modifier: Modifier = Modifier) {
    var selectedSegment by remember { mutableStateOf(DonutSegment.NONE) }
    var isPressed by remember { mutableStateOf(false) }

    val springSpec = spring<Float>(dampingRatio = 0.65f, stiffness = 180f)

    val tillSelected = selectedSegment == DonutSegment.TILL
    val tillPop by animateFloatAsState(if (tillSelected) 20f else 0f, springSpec, label = "tillPop")
    val tillScale by animateFloatAsState(if (tillSelected) 1.05f else 1f, spring(), label = "tillScale")
    val tillAlpha by animateFloatAsState(if (selectedSegment == DonutSegment.NONE || tillSelected) 0.95f else 0.3f, tween(300), label = "tillAlpha")

    val sendSelected = selectedSegment == DonutSegment.SEND
    val sendPop by animateFloatAsState(if (sendSelected) 20f else 0f, springSpec, label = "sendPop")
    val sendScale by animateFloatAsState(if (sendSelected) 1.05f else 1f, spring(), label = "sendScale")
    val sendAlpha by animateFloatAsState(if (selectedSegment == DonutSegment.NONE || sendSelected) 0.95f else 0.3f, tween(300), label = "sendAlpha")

    val paybillSelected = selectedSegment == DonutSegment.PAYBILL
    val paybillPop by animateFloatAsState(if (paybillSelected) 20f else 0f, springSpec, label = "paybillPop")
    val paybillScale by animateFloatAsState(if (paybillSelected) 1.05f else 1f, spring(), label = "paybillScale")
    val paybillAlpha by animateFloatAsState(if (selectedSegment == DonutSegment.NONE || paybillSelected) 0.95f else 0.3f, tween(300), label = "paybillAlpha")

    val transition = rememberTransition(transitionState = remember { MutableTransitionState(0f).apply { targetState = 1f } }, label = "donut")
    val animationProgress by transition.animateFloat(transitionSpec = { tween(durationMillis = 1200, easing = FastOutSlowInEasing) }, label = "progress") { it }

    val pressScale by animateFloatAsState(if (isPressed) 0.97f else 1f, tween(120), label = "pressScale")

    Box(modifier = modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            isPressed = true
                            tryAwaitRelease()
                            isPressed = false
                        },
                        onTap = { tapOffset ->
                            val cx = size.width / 2
                            val cy = size.height / 2
                            val tapAngle = Math.toDegrees(kotlin.math.atan2((tapOffset.y - cy).toDouble(), (tapOffset.x - cx).toDouble())).toFloat()
                            val normalizedTapAngle = (tapAngle + 90f + 360f) % 360f

                            var currentStart = 0f
                            var clickedSegment = DonutSegment.NONE
                            val segmentList = listOf(
                                DonutSegment.TILL to stats.tillPercent,
                                DonutSegment.SEND to stats.sendPercent,
                                DonutSegment.PAYBILL to stats.paybillPercent
                            )

                            for ((type, percent) in segmentList) {
                                val sweep = (percent / 100f) * 360f
                                if (normalizedTapAngle in currentStart..(currentStart + sweep)) {
                                    clickedSegment = type
                                    break
                                }
                                currentStart += sweep
                            }
                            selectedSegment = if (selectedSegment == clickedSegment) DonutSegment.NONE else clickedSegment
                        })
                }
        ) {
            val strokeWidth = 32f
            val gap = 4f
            val padding = strokeWidth / 2f + 20f
            val arcSize = Size(size.width - padding * 2, size.height - padding * 2)
            val arcTopLeft = Offset(padding, padding)
            val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)

            drawArc(
                color = Color.White.copy(alpha = 0.05f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = stroke,
                topLeft = arcTopLeft,
                size = arcSize
            )

            var startAngle = -90f

            val tillSweep = ((stats.tillPercent / 100f) * 360f * animationProgress - gap).coerceAtLeast(0f)
            if (tillSweep > 0) {
                val midAngle = Math.toRadians((startAngle + tillSweep / 2).toDouble())
                val offsetX = (kotlin.math.cos(midAngle) * tillPop).toFloat()
                val offsetY = (kotlin.math.sin(midAngle) * tillPop).toFloat()

                withTransform({
                    translate(offsetX, offsetY)
                    scale(tillScale, tillScale, pivot = Offset(size.width/2, size.height/2))
                }) {
                    drawArc(color = HushhTheme.TillColor.copy(alpha = tillAlpha), startAngle = startAngle, sweepAngle = tillSweep, useCenter = false, style = stroke, topLeft = arcTopLeft, size = arcSize)
                }
                startAngle += tillSweep + gap
            }

            val sendSweep = ((stats.sendPercent / 100f) * 360f * animationProgress - gap).coerceAtLeast(0f)
            if (sendSweep > 0) {
                val midAngle = Math.toRadians((startAngle + sendSweep / 2).toDouble())
                val offsetX = (kotlin.math.cos(midAngle) * sendPop).toFloat()
                val offsetY = (kotlin.math.sin(midAngle) * sendPop).toFloat()

                withTransform({
                    translate(offsetX, offsetY)
                    scale(sendScale, sendScale, pivot = Offset(size.width/2, size.height/2))
                }) {
                    drawArc(color = HushhTheme.SendColor.copy(alpha = sendAlpha), startAngle = startAngle, sweepAngle = sendSweep, useCenter = false, style = stroke, topLeft = arcTopLeft, size = arcSize)
                }
                startAngle += sendSweep + gap
            }

            val paybillSweep = ((stats.paybillPercent / 100f) * 360f * animationProgress - gap).coerceAtLeast(0f)
            if (paybillSweep > 0) {
                val midAngle = Math.toRadians((startAngle + paybillSweep / 2).toDouble())
                val offsetX = (kotlin.math.cos(midAngle) * paybillPop).toFloat()
                val offsetY = (kotlin.math.sin(midAngle) * paybillPop).toFloat()

                withTransform({
                    translate(offsetX, offsetY)
                    scale(paybillScale, paybillScale, pivot = Offset(size.width/2, size.height/2))
                }) {
                    drawArc(color = HushhTheme.PaybillColor.copy(alpha = paybillAlpha), startAngle = startAngle, sweepAngle = paybillSweep, useCenter = false, style = stroke, topLeft = arcTopLeft, size = arcSize)
                }
            }
        }

        AnimatedContent(
            targetState = selectedSegment,
            label = "CenterText",
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) }
        ) { segment ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                when (segment) {
                    DonutSegment.NONE -> {
                        Text("You Spent", color = HushhTheme.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        val animatedAmount by animateFloatAsState(targetValue = stats.totalSpent, animationSpec = tween(1200, easing = FastOutSlowInEasing), label = "amount")
                        Text("KSh ${animatedAmount.toInt()}", color = HushhTheme.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Black)
                    }
                    DonutSegment.TILL -> {
                        Text("TILL", color = HushhTheme.TillColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("KSh ${stats.tillTotal.toInt()}", color = HushhTheme.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Black)
                        Text("${stats.tillPercent.toInt()}%", color = HushhTheme.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                    DonutSegment.SEND -> {
                        Text("PHONE", color = HushhTheme.SendColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("KSh ${stats.sendTotal.toInt()}", color = HushhTheme.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Black)
                        Text("${stats.sendPercent.toInt()}%", color = HushhTheme.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                    DonutSegment.PAYBILL -> {
                        Text("PAYBILL", color = HushhTheme.PaybillColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("KSh ${stats.paybillTotal.toInt()}", color = HushhTheme.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Black)
                        Text("${stats.paybillPercent.toInt()}%", color = HushhTheme.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
fun DonutLegendItem(label: String, percent: Float, color: Color) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(10.dp).background(color, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(label, color = HushhTheme.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        Text("${percent.toInt()}%", color = HushhTheme.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun Modifier.bouncyScroll(orientation: Orientation = Orientation.Vertical): Modifier {
    val overscrollOffset = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()

    val connection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = if (orientation == Orientation.Vertical) available.y else available.x
                if (overscrollOffset.value != 0f && sign(overscrollOffset.value) != sign(delta)) {
                    val consumed = delta * 0.4f
                    coroutineScope.launch {
                        overscrollOffset.snapTo(overscrollOffset.value + consumed)
                    }
                    return if (orientation == Orientation.Vertical) Offset(0f, consumed) else Offset(consumed, 0f)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                val delta = if (orientation == Orientation.Vertical) available.y else available.x
                if (delta != 0f) {
                    coroutineScope.launch {
                        overscrollOffset.snapTo(overscrollOffset.value + delta * 0.2f)
                    }
                    return available
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (overscrollOffset.value != 0f) {
                    coroutineScope.launch {
                        overscrollOffset.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 300f))
                    }
                    return available
                }
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                coroutineScope.launch {
                    overscrollOffset.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 300f))
                }
                return Velocity.Zero
            }
        }
    }

    return this
        .nestedScroll(connection)
        .graphicsLayer {
            val stretch = abs(overscrollOffset.value) * 0.0015f
            if (orientation == Orientation.Vertical) {
                translationY = overscrollOffset.value
                scaleY = 1f + stretch
            } else {
                translationX = overscrollOffset.value
                scaleX = 1f + stretch
            }
        }
}
