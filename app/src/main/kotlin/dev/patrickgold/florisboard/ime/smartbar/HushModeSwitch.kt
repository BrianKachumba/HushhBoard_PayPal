package dev.patrickgold.florisboard.ime.smartbar

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.alpha
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
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.keyboardManager
import org.florisboard.lib.snygg.ui.SnyggBox
import org.florisboard.lib.snygg.ui.SnyggIcon

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
 * Toolbar mode switch, Google-account-switcher style:
 *  - **swipe up or down** on it to change mode (the icon slides to the next one, with a light haptic),
 *  - **tap** to launch the current mode.
 * Two dots show which mode is active. No rings, no long-press.
 */
@Composable
fun HushModeSwitch(key: String, modes: List<HushMode>) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var index by androidx.compose.runtime.remember { mutableStateOf(HushModeMemory.get(context, key).coerceIn(0, modes.lastIndex)) }
    var direction by androidx.compose.runtime.remember { mutableStateOf(1) }

    fun step(delta: Int) {
        direction = delta
        index = (index + delta + modes.size) % modes.size
        HushModeMemory.set(context, key, index)
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(44.dp)
            .semantics { contentDescription = "${modes[index].name}. Tap to open, swipe up or down to switch." }
            .pointerInput(modes.size) {
                var dragged = 0f
                detectVerticalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = { dragged = 0f },
                    onDragCancel = { dragged = 0f },
                ) { change, dy ->
                    change.consume()
                    dragged += dy
                    val threshold = 18.dp.toPx()
                    if (dragged <= -threshold) { step(1); dragged = 0f }        // swipe up -> next
                    else if (dragged >= threshold) { step(-1); dragged = 0f }   // swipe down -> previous
                }
            }
            .pointerInput(index) {
                detectTapGestures(onTap = { modes[index].launch(context) })
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(
                targetState = index,
                transitionSpec = {
                    val d = direction
                    (slideInVertically(tween(160)) { h -> h * d } + fadeIn(tween(160))) togetherWith
                        (slideOutVertically(tween(160)) { h -> -h * d } + fadeOut(tween(120)))
                },
                label = "hush-mode-$key",
            ) { i ->
                SnyggBox(elementName = FlorisImeUi.SmartbarActionKey.elementName, modifier = Modifier.size(32.dp)) {
                    SnyggIcon(imageVector = modes[i].icon)
                }
            }
            // Position dots: which of the modes is active.
            Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(start = 1.dp)) {
                modes.indices.forEach { i ->
                    Box(
                        Modifier.size(4.dp).clip(CircleShape)
                            .alpha(if (i == index) 0.95f else 0.3f)
                            .background(Color(0xFFB0B0B0))
                    )
                }
            }
        }
    }
}

/** The keyboard's "eye": swipe to pick text scan or QR scan, tap to open the scanner. */
@Composable
fun HushEyeSwitch() = HushModeSwitch(
    key = "eye",
    modes = listOf(
        HushMode("Scan text", Icons.Rounded.DocumentScanner) { ctx -> openScanner(ctx, qr = false) },
        HushMode("Scan QR code", Icons.Rounded.QrCodeScanner) { ctx -> openScanner(ctx, qr = true) },
    ),
)

/** The keyboard's "ears": swipe to pick voice typing or song search, tap to start. */
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
