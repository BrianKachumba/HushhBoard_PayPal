package dev.patrickgold.florisboard.ime.smartbar

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.PointerEventPass
import kotlin.math.abs
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardVoice
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.TransparentCameraActivity
import dev.patrickgold.florisboard.ears.SongCaptureActivity
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.keyboardManager

/** One mode of a toolbar switch: its icon, its spoken name and what tapping it launches. */
class HushMode(val name: String, val icon: ImageVector, val launch: (Context) -> Unit)

/** Remembers the chosen mode per switch while the keyboard process lives (survives keyboard re-creation). */
private object HushModeMemory {
    private val prefs = mutableMapOf<String, Int>()
    fun get(ctx: Context, key: String): Int = prefs.getOrPut(key) {
        ctx.getSharedPreferences("hushh_modes", Context.MODE_PRIVATE).getInt(key, 0)
    }
    fun set(ctx: Context, key: String, index: Int) {
        prefs[key] = index
        ctx.getSharedPreferences("hushh_modes", Context.MODE_PRIVATE).edit().putInt(key, index).apply()
    }
}

/**
 * Toolbar mode switch:
 *  - **swipe in any direction** on it (up, down, left or right) to flip to the other mode,
 *  - **tap** to launch the current mode.
 * One gesture = at most one switch, decided as soon as the finger has clearly moved, so it never
 * depends on which way you swiped. Looks like the emoji row's "all emojis" key: an icon centred
 * in a soft rounded square.
 */
@Composable
fun HushModeSwitch(key: String, modes: List<HushMode>) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var index by remember { mutableStateOf(HushModeMemory.get(context, key).coerceIn(0, modes.lastIndex)) }
    val current by rememberUpdatedState(index)

    fun flip() {
        index = (index + 1) % modes.size
        HushModeMemory.set(context, key, index)
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(46.dp)
            .padding(horizontal = 3.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Gray.copy(alpha = 0.12f))
            .semantics { contentDescription = "${modes[index].name}. Tap to open, swipe to switch." }
            .pointerInput(modes.size) {
                val slop = 10.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var switched = false
                    var moved = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        val d = change.position - down.position
                        if (!moved && (abs(d.x) > slop || abs(d.y) > slop)) {
                            moved = true
                            if (!switched) { switched = true; flip() }
                        }
                        if (!change.pressed) {
                            if (!moved) modes[current].launch(context)
                            break
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = index,
            transitionSpec = {
                (fadeIn(tween(140)) + scaleIn(tween(160), initialScale = 0.6f)) togetherWith
                    (fadeOut(tween(100)) + scaleOut(tween(120), targetScale = 0.6f))
            },
            label = "hush-mode-$key",
        ) { i ->
            Icon(modes[i].icon, null, tint = Color.Gray, modifier = Modifier.size(22.dp))
        }
    }
}

/** The keyboard's "eye": swipe to flip between text scan and QR scan, tap to open the scanner. */
@Composable
fun HushEyeSwitch() = HushModeSwitch(
    key = "eye",
    modes = listOf(
        HushMode("Scan text", Icons.Rounded.DocumentScanner) { ctx -> openScanner(ctx, qr = false) },
        HushMode("Scan QR code", Icons.Rounded.QrCodeScanner) { ctx -> openScanner(ctx, qr = true) },
    ),
)

/** The keyboard's "ears": swipe to flip between voice typing and song search, tap to start. */
@Composable
fun HushEarsSwitch() {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    HushModeSwitch(
        key = "ears",
        modes = listOf(
            HushMode("Voice typing", Icons.Rounded.KeyboardVoice) {
                keyboardManager.inputEventDispatcher.sendDownUp(TextKeyData(code = KeyCode.VOICE_INPUT))
            },
            HushMode("Song search", Icons.Rounded.GraphicEq) { ctx -> SongCaptureActivity.launch(ctx, fromKeyboard = true) },
        ),
    )
}

private fun openScanner(ctx: Context, qr: Boolean) {
    ctx.startActivity(
        Intent(ctx, TransparentCameraActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("isFront", false)
            .putExtra("IS_CODE_SCAN", qr)
    )
}
