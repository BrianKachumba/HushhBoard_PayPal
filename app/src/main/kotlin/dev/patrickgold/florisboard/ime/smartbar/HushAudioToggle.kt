package dev.patrickgold.florisboard.ime.smartbar

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
import dev.patrickgold.florisboard.ears.SongCaptureActivity
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.keyboardManager

enum class AudioMode { SONG_SEARCH, VOICE_INPUT }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HushAudioToggle() {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()

    // Default to Voice Input
    var currentMode by remember { mutableStateOf(AudioMode.VOICE_INPUT) }

    val ringColor = if (currentMode == AudioMode.VOICE_INPUT) {
        Color(0xFF4CAF50) // Material Green
    } else {
        Color(0xFFFFC107) // Material Yellow
    }

    Box(
        modifier = Modifier
            .size(40.dp)
            .padding(4.dp)
            .combinedClickable(
                onClick = {
                    // SINGLE TAP: Toggle the mode
                    currentMode = if (currentMode == AudioMode.VOICE_INPUT) {
                        AudioMode.SONG_SEARCH
                    } else {
                        AudioMode.VOICE_INPUT
                    }
                },
                onLongClick = {
                    // LONG PRESS: Execute the chosen action!
                    if (currentMode == AudioMode.VOICE_INPUT) {
                        keyboardManager.inputEventDispatcher.sendDownUp(
                            TextKeyData(code = KeyCode.VOICE_INPUT)
                        )
                    } else {
                        // Floating song search over the current app: phone audio or around me.
                        // (Falls back to Google's free song search when no recognizer is configured.)
                        SongCaptureActivity.launch(context, fromKeyboard = true)
                    }
                }
            )
            .border(width = 2.dp, color = ringColor, shape = CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(text = "🎵", fontSize = 16.sp)
    }
}
