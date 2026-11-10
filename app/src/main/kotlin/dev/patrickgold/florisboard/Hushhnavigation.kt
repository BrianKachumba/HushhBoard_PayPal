package dev.patrickgold.florisboard

import android.app.Activity
import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.VerticalPager
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.abs

// ================================================================
// 1. PAGE GRAPH  (the exact swipe sequence you specified)
//
//   HOME → STATS → REELS → CREATOR → ME → THEMES      (swipe left = forward)
//   Right swipe: THEMES→ME, ME→REELS (skips creator), CREATOR→REELS,
//                REELS→STATS, STATS→HOME, HOME = no-op
// ================================================================
enum class AppPage {
    HOME, STATS, REELS, CREATOR, ME, THEMES;

    // PayPal hackathon build: only two tabs - Home (money accounts) and Themes.
    val swipeLeftTarget: AppPage?
        get() = if (this == HOME) THEMES else null

    val swipeRightTarget: AppPage?
        get() = if (this == THEMES) HOME else null

    val navIndex: Int
        get() = if (this == THEMES) 1 else 0

    val showsBottomBar: Boolean get() = true
}

private fun pageForNavIndex(index: Int) = if (index == 1) AppPage.THEMES else AppPage.HOME

// ================================================================
// 2. SWIPE MODIFIER
//    - pointer detector: works anywhere that has no horizontal scroller
//    - nested-scroll hook: catches leftover swipes when an inner horizontal
//      scroller (e.g. the Home card pager) is already at its edge
//    A 450ms guard stops the two paths from double-firing.
// ================================================================
@Composable
fun Modifier.tabSwipeNavigation(onSwipeLeft: () -> Unit, onSwipeRight: () -> Unit): Modifier {
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    val currentLeft by rememberUpdatedState(onSwipeLeft)
    val currentRight by rememberUpdatedState(onSwipeRight)
    val lastFire = remember { longArrayOf(0L) }

    val fire = remember {
        { dx: Float ->
            val now = System.currentTimeMillis()
            if (now - lastFire[0] > 450L) {
                lastFire[0] = now
                if (dx < 0) currentLeft() else currentRight()
            }
        }
    }

    val connection = remember {
        object : NestedScrollConnection {
            var acc = 0f
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.x != 0f) {
                    acc += available.x
                    if (abs(acc) > threshold) { fire(acc); acc = 0f }
                }
                return Offset.Zero
            }
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                acc = 0f
                return Velocity.Zero
            }
        }
    }

    return this
        .nestedScroll(connection)
        .pointerInput(Unit) {
            var total = 0f
            detectHorizontalDragGestures(
                onDragStart = { total = 0f },
                onDragCancel = { total = 0f },
                onDragEnd = { if (abs(total) > threshold) fire(total); total = 0f },
                onHorizontalDrag = { _, delta -> total += delta }
            )
        }
}

// ================================================================
// 3. MAIN APP ENGINE
// ================================================================
@Composable
fun MainAppEngine(viewModel: DashboardViewModel) {
    var page by remember { mutableStateOf(AppPage.HOME) }
    var currentReelIndex by remember { mutableIntStateOf(0) }

    // "Tap to label" jump from Stats → Home
    val pendingJumpId by viewModel.pendingUnlabeledJumpId.collectAsState()
    LaunchedEffect(pendingJumpId) { if (pendingJumpId != null) page = AppPage.HOME }

    var showWrapViewer by remember { mutableStateOf(false) }
    val dummyWraps = listOf(
        WrapCollection("wrap_1", listOf(
            WrapSegment("🎉", "You Spent KSh 5,400", "Top spending: Food & Transport"),
            WrapSegment("🍔", "KSh 2,100 on Food", "You love Irene Motors!"),
            WrapSegment("📉", "23% Drop!", "You spent less than last week.")
        )),
        WrapCollection("wrap_2", listOf(
            WrapSegment("🏆", "Top 10%", "You are saving better than most users."),
            WrapSegment("💸", "KSh 390 in Fees", "Watch out for transfer costs.")
        ))
    )
    if (showWrapViewer) {
        Dialog(
            onDismissRequest = { showWrapViewer = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
        ) { WrapViewerScreen(collections = dummyWraps, onClose = { showWrapViewer = false }) }
    }

    Scaffold(
        containerColor = Bg,
        bottomBar = {
            AnimatedVisibility(
                visible = page.showsBottomBar,
                enter = slideInVertically { it },
                exit = slideOutVertically { it }
            ) {
                BottomNav(selected = page.navIndex) { page = pageForNavIndex(it) }
            }
        }
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .tabSwipeNavigation(
                    onSwipeLeft = { page.swipeLeftTarget?.let { page = it } },
                    onSwipeRight = { page.swipeRightTarget?.let { page = it } }
                )
        ) {
            AnimatedContent(
                targetState = page,
                label = "PageSlide",
                transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal
                    (slideInHorizontally { if (forward) it else -it } + fadeIn()) togetherWith
                        (slideOutHorizontally { if (forward) -it else it } + fadeOut()) using
                        SizeTransform(clip = false)
                }
            ) { p ->
                when (p) {
                    AppPage.THEMES -> ThemesScreen()
                    else -> dev.patrickgold.florisboard.money.MoneyHomeScreen()
                }
            }
        }
    }
}

// ================================================================
// 4. BOTTOM NAV  (Home · Stats · Reals · Me · Themes)
// ================================================================
val ActiveGreen = HushhTheme.Accent
val BottomBarColor = HushhTheme.Surface
val InactiveColor = Color.Gray

private data class BottomNavItemData(
    val title: String,
    val icon: ImageVector?,   // null = hollow ring (Me, until profile pic exists)
    val index: Int
)

@Composable
fun BottomNav(selected: Int, onSelect: (Int) -> Unit) {
    val items = remember {
        listOf(
            BottomNavItemData("HOME", Icons.Rounded.Home, 0),
            BottomNavItemData("THEMES", Icons.Rounded.ColorLens, 1)
        )
    }
    NavigationBar(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, HushhTheme.BorderSubtle, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)),
        containerColor = BottomBarColor,
        contentColor = InactiveColor,
        tonalElevation = 0.dp,
        windowInsets = NavigationBarDefaults.windowInsets
    ) {
        items.forEach { item ->
            val isSelected = selected == item.index
            NavigationBarItem(
                selected = isSelected,
                onClick = { onSelect(item.index) },
                icon = {
                    if (item.icon != null) {
                        Icon(item.icon, contentDescription = item.title, modifier = Modifier.size(24.dp))
                    } else {
                        Box(
                            Modifier.size(22.dp).border(
                                2.dp, if (isSelected) ActiveGreen else InactiveColor, CircleShape
                            )
                        )
                    }
                },
                label = {
                    Text(item.title, fontSize = 10.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium)
                },
                alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = ActiveGreen,
                    selectedTextColor = ActiveGreen,
                    indicatorColor = ActiveGreen.copy(alpha = 0.15f),
                    unselectedIconColor = InactiveColor,
                    unselectedTextColor = InactiveColor
                )
            )
        }
    }
}

// ================================================================
// 5. REALS — full-screen vertical video player
// ================================================================
data class ReelItem(val id: Int, val creator: String, val handle: String, val tint: Color)

val sampleReels = listOf(
    ReelItem(0, "Xander", "xander_OG", Color(0xFF1B3A2A)),
    ReelItem(1, "Amina", "amina.cooks", Color(0xFF2A1B3A)),
    ReelItem(2, "Kip", "kip_fixes", Color(0xFF3A2A1B)),
    ReelItem(3, "Wanjiru", "wanjiru.fit", Color(0xFF1B2A3A)),
)

@Composable
fun ReelsScreen(initialReel: Int, onReelChanged: (Int) -> Unit, onOpenCreator: () -> Unit) {
    val pagerState = rememberPagerState(initialPage = initialReel) { sampleReels.size }
    LaunchedEffect(pagerState.currentPage) { onReelChanged(pagerState.currentPage) }

    // Vertical swipe = next/previous video. Horizontal swipes bubble up to tabSwipeNavigation.
    VerticalPager(state = pagerState, modifier = Modifier.fillMaxSize().background(Color.Black)) { index ->
        ReelPage(sampleReels[index], onOpenCreator)
    }
}

@Composable
private fun ReelPage(reel: ReelItem, onOpenCreator: () -> Unit) {
    var liked by remember { mutableStateOf(false) }
    var disliked by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(reel.tint, Color.Black)))
    ) {
        // TODO: put your video surface here (Media3 ExoPlayer / PlayerView via AndroidView)

        // Search (top-left)
        Row(
            Modifier.align(Alignment.TopStart).statusBarsPadding().padding(16.dp).clickable { },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Search, "Search", tint = Color.White, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(6.dp))
            Text("Search", color = Color.White, fontWeight = FontWeight.SemiBold)
        }

        // Action rail (right)
        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            ReelAction(Icons.Rounded.ChatBubbleOutline, "100") { }
            ReelAction(Icons.Rounded.Repeat, "Repost") { }
            ReelAction(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (liked) "51" else "50",
                if (liked) Color(0xFFFF5252) else Color.White) { liked = !liked; if (liked) disliked = false }
            ReelAction(Icons.Rounded.ThumbDown, if (disliked) "3" else "2",
                if (disliked) HushhTheme.Accent else Color.White) { disliked = !disliked; if (disliked) liked = false }
            ReelAction(Icons.Rounded.ShoppingCart, "Shop") { }
            ReelAction(if (saved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, if (saved) "21" else "20",
                if (saved) HushhTheme.Accent else Color.White) { saved = !saved }
        }

        // Creator (bottom-left) — tap opens the creator page, same as swiping left
        Row(
            Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(44.dp).border(2.dp, Color.White, CircleShape).clickable { onOpenCreator() })
            Spacer(Modifier.width(10.dp))
            Column {
                Text(reel.creator, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("See more", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ReelAction(icon: ImageVector, label: String, tint: Color = Color.White, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onClick() }) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(30.dp))
        Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

// ================================================================
// 6. CREATOR PROFILE (bottom nav stays visible)
// ================================================================
@Composable
fun CreatorProfileScreen(reel: ReelItem) {
    var following by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf("Hottest") }
    val thumbs = remember(reel.id) {
        listOf("5K", "2K", "500", "700", "12K", "20", "15K", "800", "3K", "90", "1.2K", "40")
    }

    Column(Modifier.fillMaxSize().background(HushhTheme.Bg)) {
        // Preview of the video that was playing
        Box(
            Modifier.fillMaxWidth().weight(0.35f).background(Brush.verticalGradient(listOf(reel.tint, HushhTheme.Bg))),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.PlayArrow, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(48.dp))
        }

        Column(
            Modifier.fillMaxWidth().weight(0.65f)
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(HushhTheme.Surface).padding(16.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("148 Following", color = Color.White, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(28.dp).border(1.5.dp, Color.White, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text("@${reel.handle}", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { following = !following },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (following) HushhTheme.SurfaceAlt else HushhTheme.Accent,
                        contentColor = if (following) Color.White else Color.Black
                    )
                ) { Text(if (following) "Following" else "Follow", fontWeight = FontWeight.Bold) }
                OutlinedButton(
                    onClick = { }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, HushhTheme.BorderStrong)
                ) { Text("Comment", color = Color.White, fontWeight = FontWeight.Bold) }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Hottest", "Popular", "Old").forEach { s ->
                    Text(
                        s, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        color = if (sort == s) HushhTheme.Accent else HushhTheme.TextSecondary,
                        modifier = Modifier.clickable { sort = s }
                    )
                }
                Spacer(Modifier.weight(1f))
                Text("Sort", color = HushhTheme.TextSecondary, fontSize = 12.sp)
                Text("Recently ▾", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = HushhTheme.BorderSubtle)

            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(thumbs) { views ->
                    Box(
                        Modifier.aspectRatio(0.62f).clip(RoundedCornerShape(8.dp)).background(HushhTheme.SurfaceAlt)
                            .clickable { /* TODO: open this video in Reals */ },
                        contentAlignment = Alignment.BottomEnd
                    ) { Text(views, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(4.dp)) }
                }
            }
        }
    }
}

// ================================================================
// 7. THEMES — settings gear top-right opens the existing Settings
// ================================================================
@Composable
fun ThemesScreen() {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Themes", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
            IconButton(onClick = {
                val intent = Intent(context, dev.patrickgold.florisboard.app.FlorisAppActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                context.startActivity(intent)
                (context as? Activity)?.overridePendingTransition(0, 0)
            }) { Icon(Icons.Rounded.Settings, "Settings", tint = Color.White, modifier = Modifier.size(28.dp)) }
        }
        // Appearance + customization content goes here
        PlaceholderScreen("Themes", Icons.Rounded.ColorLens)
    }
}
