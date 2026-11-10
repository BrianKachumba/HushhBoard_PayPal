package dev.patrickgold.florisboard

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 🚀 DATA MODELS
data class WrapSegment(
    val emoji: String,
    val value: String,
    val subtitle: String
)

data class WrapCollection(
    val id: String,
    val segments: List<WrapSegment>
)

// 🚀 THE FULL-SCREEN CONTAINER (Handles Entrance/Exit Animations)
@Composable
fun WrapViewerScreen(
    collections: List<WrapCollection>,
    initialCollectionIndex: Int = 0,
    onClose: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { visible = true }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(300)) + scaleIn(initialScale = 0.92f, animationSpec = tween(300, easing = FastOutSlowInEasing)),
        exit = fadeOut(tween(200)) + scaleOut(targetScale = 0.92f, animationSpec = tween(200))
    ) {
        WrapViewerContent(
            collections = collections,
            initialCollectionIndex = initialCollectionIndex,
            onClose = {
                visible = false
                // Small delay to allow exit animation to finish before destroying the composable
                kotlinx.coroutines.GlobalScope.launch { delay(200); onClose() }
            }
        )
    }
}

// 🚀 THE ACTUAL VIEWER LOGIC (Pager + Drag to Dismiss)
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WrapViewerContent(
    collections: List<WrapCollection>,
    initialCollectionIndex: Int,
    onClose: () -> Unit
) {
    val pagerState = rememberPagerState(
        initialPage = initialCollectionIndex,
        pageCount = { collections.size }
    )
    val coroutineScope = rememberCoroutineScope()

    // 🔥 Added Drag-To-Dismiss State
    val offsetY = remember { Animatable(0f) }

    // 🔥 Wraps the entire screen with Gradient Depth and Swipe-to-close
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colors = listOf(Color.Black, Color(0xFF0A0A0A), Color.Black)))
            .offset { IntOffset(0, offsetY.value.toInt()) }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        coroutineScope.launch {
                            // Only allow dragging down
                            val newOffset = (offsetY.value + dragAmount.y).coerceAtLeast(0f)
                            offsetY.snapTo(newOffset)
                        }
                    },
                    onDragEnd = {
                        if (offsetY.value > 300f) {
                            onClose()
                        } else {
                            coroutineScope.launch { offsetY.animateTo(0f, spring()) }
                        }
                    }
                )
            }
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { pageIndex ->
            val collection = collections[pageIndex]

            // Render the individual story engine for this specific page
            StoryPlayer(
                collection = collection,
                isCurrentPage = pagerState.currentPage == pageIndex,
                onNextCollection = {
                    if (pageIndex < collections.lastIndex) {
                        coroutineScope.launch { pagerState.animateScrollToPage(pageIndex + 1) }
                    } else {
                        onClose()
                    }
                },
                onPrevCollection = {
                    if (pageIndex > 0) {
                        coroutineScope.launch { pagerState.animateScrollToPage(pageIndex - 1) }
                    }
                },
                onClose = onClose
            )
        }
    }
}

// 🚀 THE INDIVIDUAL STORY ENGINE (Physics & Auto-Play)
@Composable
fun StoryPlayer(
    collection: WrapCollection,
    isCurrentPage: Boolean,
    onNextCollection: () -> Unit,
    onPrevCollection: () -> Unit,
    onClose: () -> Unit
) {
    var currentSegmentIndex by remember { mutableStateOf(0) }
    var isPaused by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()

    // ⏱️ Auto-Play Timer Logic
    LaunchedEffect(currentSegmentIndex, isPaused, isCurrentPage) {
        if (isCurrentPage) {
            if (!isPaused) {
                // Calculate remaining time for smooth resume
                val duration = 5000 // 5 seconds per story
                val remainingTime = ((1f - progress.value) * duration).toInt()

                progress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = remainingTime, easing = LinearEasing)
                )

                // When finished, move to next
                if (currentSegmentIndex < collection.segments.lastIndex) {
                    currentSegmentIndex++
                    progress.snapTo(0f)
                } else {
                    onNextCollection()
                }
            } else {
                progress.stop()
            }
        } else {
            // Reset if user swiped away
            progress.snapTo(0f)
            currentSegmentIndex = 0
        }
    }

    val currentWrap = collection.segments[currentSegmentIndex]

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 👆 ADVANCED TAP PHYSICS (Pause on Hold, Navigate on Edge Tap)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPaused = true
                        tryAwaitRelease()
                        isPaused = false
                    },
                    onTap = { tapOffset ->
                        val width = size.width
                        val isRight = tapOffset.x > width * 0.6f // 40% Right
                        val isLeft = tapOffset.x < width * 0.4f  // 40% Left

                        coroutineScope.launch {
                            if (isRight) {
                                if (currentSegmentIndex < collection.segments.lastIndex) {
                                    currentSegmentIndex++
                                    progress.snapTo(0f)
                                } else {
                                    onNextCollection()
                                }
                            } else if (isLeft) {
                                if (currentSegmentIndex > 0) {
                                    currentSegmentIndex--
                                    progress.snapTo(0f)
                                } else {
                                    onPrevCollection()
                                }
                            }
                        }
                    }
                )
            }
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(vertical = 16.dp)) {

            // 🔝 1. PROGRESS BARS
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                collection.segments.forEachIndexed { index, _ ->
                    val segmentProgress = when {
                        index < currentSegmentIndex -> 1f
                        index == currentSegmentIndex -> progress.value
                        else -> 0f
                    }
                    LinearProgressIndicator(
                        progress = { segmentProgress },
                        modifier = Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(50)),
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.3f)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // 🔝 2. PROFILE HEADER & ICONS
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // HushhBoard Avatar
                Box(
                    modifier = Modifier.size(36.dp).background(Color(0xFF00E676), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("H", color = Color.Black, fontWeight = FontWeight.Black, fontSize = 16.sp)
                }
                Spacer(Modifier.width(10.dp))
                Text("HushhBoard", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)

                Spacer(Modifier.weight(1f))

                // Star & More Options
                Icon(Icons.Rounded.StarBorder, contentDescription = "Favorite", tint = Color.White, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Icon(Icons.Rounded.MoreVert, contentDescription = "More", tint = Color.White, modifier = Modifier.size(24.dp).clickable { onClose() })
            }

            // 🧠 3. MAIN CONTENT (Centered + Animated)
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = currentSegmentIndex,
                    transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
                    label = "segment"
                ) { index ->
                    val wrap = collection.segments[index]
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(wrap.emoji, fontSize = 64.sp)
                        Spacer(Modifier.height(24.dp))
                        Text(wrap.value, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black)
                        Spacer(Modifier.height(12.dp))
                        Text(wrap.subtitle, color = Color.Gray, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        // 🔻 4. RIGHT SIDE ACTIONS (Share / Save)
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ActionPill(icon = Icons.Rounded.Share, text = "SHARE")
            ActionPill(icon = Icons.Rounded.BookmarkBorder, text = "SAVE")
        }
    }
}

@Composable
fun ActionPill(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.DarkGray.copy(alpha = 0.5f))
            .clickable { /* Handle action */ }
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = text, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}
