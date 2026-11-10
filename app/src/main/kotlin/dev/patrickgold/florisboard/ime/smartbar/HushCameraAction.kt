package dev.patrickgold.florisboard.ime.smartbar

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.patrickgold.florisboard.TransparentCameraActivity

enum class VisionMode { TEXT, QR }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HushCameraAction() {
    val context = LocalContext.current
    var currentMode by remember { mutableStateOf(VisionMode.TEXT) }

    val ringColor = when (currentMode) {
        VisionMode.TEXT -> Color(0xFFFFC107) // Yellow
        VisionMode.QR -> Color(0xFF00E676)   // Green
    }

    Box(
        modifier = Modifier
            .size(40.dp)
            .padding(4.dp)
            .combinedClickable(
                onClick = {
                    // SINGLE TAP: toggle Yellow <-> Green
                    currentMode = if (currentMode == VisionMode.TEXT) VisionMode.QR else VisionMode.TEXT
                },
                onLongClick = {
                    // LONG PRESS: Launch UI (camera flipping now happens inside the camera screen)
                    val intent = Intent(context, TransparentCameraActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    intent.putExtra("isFront", false)
                    intent.putExtra("IS_CODE_SCAN", currentMode == VisionMode.QR)
                    context.startActivity(intent)
                }
            )
            .border(width = 2.dp, color = ringColor, shape = CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(text = "🤫", fontSize = 16.sp)
    }
}
