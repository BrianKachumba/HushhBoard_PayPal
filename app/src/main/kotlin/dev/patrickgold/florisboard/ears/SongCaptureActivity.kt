/*
 * Copyright (C) 2026 HushhBoard
 * Licensed under the Apache License, Version 2.0.
 */

package dev.patrickgold.florisboard.ears

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.patrickgold.florisboard.scanner.HushhInsert
import dev.patrickgold.florisboard.scanner.ScanActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.math.sin

/**
 * "The ears": a small floating card over whatever app is open. Identifies the song the phone is
 * playing (TikTok, Instagram, YouTube…) via playback capture, or music around the user via the
 * microphone. Without a configured recognizer it hands "around me" to Google's free song search.
 */
class SongCaptureActivity : ComponentActivity() {

    private var fromKeyboard = true
    private val recognizer by lazy { SongRecognizers.available() }
    private var pendingSource: ListenSource? = null

    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val source = pendingSource ?: return@registerForActivityResult
        if (granted) begin(source) else {
            Toast.makeText(this, "HushhBoard needs the microphone permission to listen", Toast.LENGTH_LONG).show()
            SongSession.reset()
        }
    }

    private val projectionConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            SongSession.cancelled = false
            SongSession.set(SongSession.State.Listening(ListenSource.PHONE, 0f, 0f))
            val service = Intent(this, PlaybackCaptureService::class.java)
                .putExtra(PlaybackCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                .putExtra(PlaybackCaptureService.EXTRA_RESULT_DATA, data)
            ContextCompat.startForegroundService(this, service)
        } else {
            SongSession.reset()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        window.decorView.setBackgroundColor(AndroidColor.TRANSPARENT)
        fromKeyboard = intent.getBooleanExtra(EXTRA_FROM_KEYBOARD, true)
        SongSession.reset()

        // No recognizer in this build: Google's free search is the only option.
        if (recognizer == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            launchGoogleSongSearch()
            finish()
            return
        }

        setContent {
            val state by SongSession.state.collectAsState()
            SongOverlay(
                state = state,
                canInsert = fromKeyboard,
                onChoose = { source -> start(source) },
                onInsert = { song ->
                    HushhInsert.send(this, song.shareText)
                    close(restoreKeyboard = true)
                },
                onAroundMeWithGoogle = { launchGoogleSongSearch(); finish() },
                onRetry = { source -> start(source) },
                onClose = { close(restoreKeyboard = fromKeyboard) },
            )
        }
    }

    private fun start(source: ListenSource) {
        pendingSource = source
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            begin(source)
        }
    }

    private fun begin(source: ListenSource) {
        SongSession.reset()
        when (source) {
            ListenSource.PHONE -> {
                val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                // Android 14+: ask for the whole display directly (audio capture needs it).
                val consent = if (Build.VERSION.SDK_INT >= 34) {
                    manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                } else {
                    manager.createScreenCaptureIntent()
                }
                projectionConsent.launch(consent)
            }
            ListenSource.AROUND -> listenAround()
        }
    }

    @SuppressLint("MissingPermission")
    private fun listenAround() {
        val rec = recognizer ?: return
        SongSession.set(SongSession.State.Listening(ListenSource.AROUND, 0f, 0f))
        lifecycleScope.launch(Dispatchers.IO) {
            val record = try {
                AudioRecord(MediaRecorder.AudioSource.MIC, AudioClip.CAPTURE_RATE, android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT, AudioClip.bufferSize())
            } catch (e: Exception) {
                SongSession.set(SongSession.State.Failed("The microphone is busy"))
                return@launch
            }
            try {
                record.startRecording()
                var level = 0f
                var last = 0L
                val samples = AudioClip.record(
                    record,
                    cancelled = { SongSession.cancelled },
                    onLevel = { level = it },
                    onProgress = { p ->
                        val now = System.currentTimeMillis()
                        if (now - last > 50) {
                            last = now
                            SongSession.set(SongSession.State.Listening(ListenSource.AROUND, level, p))
                        }
                    },
                )
                record.stop()
                SongSession.identify(samples, ListenSource.AROUND, rec)
            } catch (e: Exception) {
                SongSession.set(SongSession.State.Failed("Couldn't use the microphone"))
            } finally {
                record.release()
            }
        }
    }

    private fun launchGoogleSongSearch() {
        val intent = Intent("com.google.android.googlequicksearchbox.MUSIC_SEARCH").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Song search needs the Google app", Toast.LENGTH_SHORT).show()
        }
    }

    private fun close(restoreKeyboard: Boolean) {
        SongSession.cancelled = true
        finish()
        if (!restoreKeyboard) return
        val appContext = applicationContext
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val imm = appContext.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                @Suppress("DEPRECATION")
                imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
            } catch (_: Exception) {}
        }, 300)
    }

    override fun onDestroy() {
        if (isFinishing) SongSession.cancelled = true
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FROM_KEYBOARD = "from_keyboard"

        fun launch(context: Context, fromKeyboard: Boolean) {
            context.startActivity(
                Intent(context, SongCaptureActivity::class.java)
                    .putExtra(EXTRA_FROM_KEYBOARD, fromKeyboard)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
        }
    }
}

// ── UI ──────────────────────────────────────────────────────────────────────────────────────

private val Glass = Color(0xEB15171C)
private val Card = Color(0xFF20232A)
private val TextMain = Color(0xFFF4F5F7)
private val TextMuted = Color(0xFF9AA0AA)
private val Accent = Color(0xFF5AA9FF)
private val Pink = Color(0xFFFF5C93)

@Composable
private fun SongOverlay(
    state: SongSession.State,
    canInsert: Boolean,
    onChoose: (ListenSource) -> Unit,
    onInsert: (Song) -> Unit,
    onAroundMeWithGoogle: () -> Unit,
    onRetry: (ListenSource) -> Unit,
    onClose: () -> Unit,
) {
    // A light veil so the floating card reads well; the app underneath stays visible.
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.18f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClose() },
    ) {
        val listening = state is SongSession.State.Listening || state is SongSession.State.Identifying
        AnimatedContent(
            targetState = state::class,
            transitionSpec = { (fadeIn(tween(220)) + slideInVertically(tween(260, easing = FastOutSlowInEasing)) { it / 3 }) togetherWith fadeOut(tween(120)) },
            modifier = Modifier
                .align(if (listening) Alignment.TopCenter else Alignment.BottomCenter)
                .then(if (listening) Modifier.statusBarsPadding() else Modifier.navigationBarsPadding())
                .padding(16.dp),
            label = "song",
        ) { _ ->
            Box(Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }) {
                when (state) {
                    SongSession.State.Idle -> Chooser(onChoose, onClose)
                    is SongSession.State.Listening -> ListeningPill(state, onClose)
                    SongSession.State.Identifying -> ListeningPill(null, onClose)
                    is SongSession.State.Found -> FoundCard(state.song, canInsert, onInsert, onRetry, onClose)
                    is SongSession.State.NotFound -> MessageCard(
                        title = "No match",
                        body = if (state.source == ListenSource.PHONE) "Let it play a little longer, then try again." else "Hold the phone closer to the music and try again.",
                        primary = "Try again" to { onRetry(state.source) },
                        secondary = if (state.source == ListenSource.PHONE) "Listen around me" to { onRetry(ListenSource.AROUND) } else null,
                        onClose = onClose,
                    )
                    SongSession.State.Silent -> MessageCard(
                        title = "Couldn't hear anything",
                        body = "Nothing was playing, or this app doesn't allow its sound to be recorded. Play it on the speaker and use \"Around me\" instead.",
                        primary = "Listen around me" to { onRetry(ListenSource.AROUND) },
                        secondary = "Use Google" to onAroundMeWithGoogle,
                        onClose = onClose,
                    )
                    is SongSession.State.Failed -> MessageCard(
                        title = "Song search didn't work",
                        body = state.message,
                        primary = "Try again" to { onRetry(ListenSource.PHONE) },
                        secondary = "Use Google" to onAroundMeWithGoogle,
                        onClose = onClose,
                    )
                }
            }
        }
    }
}

@Composable
private fun Chooser(onChoose: (ListenSource) -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Glass).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.MusicNote, null, tint = Pink, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text("What's this song?", color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.Close, "Close", tint = TextMuted, modifier = Modifier.size(22.dp).clickable { onClose() })
        }
        Spacer(Modifier.height(14.dp))
        Option(Icons.Rounded.PhoneAndroid, "Playing on this phone", "TikTok, Instagram, YouTube, a video…") { onChoose(ListenSource.PHONE) }
        Spacer(Modifier.height(10.dp))
        Option(Icons.Rounded.SurroundSound, "Playing around me", "A radio, a club, a matatu…") { onChoose(ListenSource.AROUND) }
    }
}

@Composable
private fun Option(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card).clickable { onClick() }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(Accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Accent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = TextMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ListeningPill(state: SongSession.State.Listening?, onCancel: () -> Unit) {
    val level by animateFloatAsState(state?.level ?: 0.35f, tween(90), label = "level")
    var phase by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(33)
            phase += 0.35f
        }
    }
    Row(
        Modifier.clip(CircleShape).background(Glass).padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(width = 44.dp, height = 26.dp)) {
            val bars = 5
            val w = size.width / (bars * 2 - 1)
            for (i in 0 until bars) {
                val wave = (sin(phase + i * 0.9f) + 1f) / 2f
                val h = size.height * (0.18f + 0.82f * (if (state == null) wave * 0.6f else level * (0.45f + 0.55f * wave)))
                drawRoundRect(
                    Brush.verticalGradient(listOf(Pink, Accent)),
                    topLeft = Offset(i * 2 * w, (size.height - h) / 2f),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(w / 2f),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.width(190.dp)) {
            Text(
                when {
                    state == null -> "Finding the song…"
                    state.source == ListenSource.PHONE -> "Listening to your phone"
                    else -> "Listening around you"
                },
                color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            )
            Text(if (state == null) "Almost there" else "Keep the music playing", color = TextMuted, fontSize = 12.sp)
            if (state != null) {
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f))) {
                    Box(Modifier.fillMaxWidth(state.progress.coerceIn(0f, 1f)).height(3.dp).clip(CircleShape).background(Accent))
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(32.dp).clip(CircleShape).clickable { onCancel() }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Close, "Stop", tint = TextMuted, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun FoundCard(song: Song, canInsert: Boolean, onInsert: (Song) -> Unit, onRetry: (ListenSource) -> Unit, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var art by remember(song.artworkUrl) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(song.artworkUrl) { art = song.artworkUrl?.let { loadArtwork(it) } }

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Glass).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)).background(Card), contentAlignment = Alignment.Center) {
                val bmp = art
                if (bmp != null) {
                    Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(Icons.Rounded.GraphicEq, null, tint = Pink, modifier = Modifier.size(30.dp))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, color = TextMain, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(song.artist, color = TextMain.copy(alpha = 0.85f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val meta = listOfNotNull(song.album, song.year).joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, color = TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Rounded.Close, "Close", tint = TextMuted, modifier = Modifier.size(22.dp).clickable { onClose() })
        }
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(16.dp)).background(Accent).clickable {
                ScanActions.openLink(context, song.songLink ?: song.spotifyUrl ?: song.appleMusicUrl ?: song.youtubeUrl)
                onClose()
            },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.PlayCircle, null, tint = Color(0xFF0B0D10), modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text("Listen", color = Color(0xFF0B0D10), fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            song.spotifyUrl?.let { url -> Small("Spotify", Icons.Rounded.OpenInNew, Modifier.weight(1f)) { ScanActions.openLink(context, url); onClose() } }
            Small("YouTube", Icons.Rounded.OpenInNew, Modifier.weight(1f)) { ScanActions.openLink(context, song.youtubeUrl); onClose() }
            Small("Copy", Icons.Rounded.ContentCopy, Modifier.weight(1f)) { ScanActions.copy(context, "${song.title} — ${song.artist}", "Song") }
            if (canInsert) Small("Send", Icons.Rounded.Keyboard, Modifier.weight(1f)) { onInsert(song) }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Row(
                Modifier.clip(CircleShape).clickable { onRetry(ListenSource.PHONE) }.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Refresh, null, tint = TextMuted, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Search again", color = TextMuted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun Small(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(Card).clickable { onClick() }.padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = TextMain, modifier = Modifier.size(18.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = TextMuted, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun MessageCard(
    title: String,
    body: String,
    primary: Pair<String, () -> Unit>,
    secondary: Pair<String, () -> Unit>?,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Glass).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.Close, "Close", tint = TextMuted, modifier = Modifier.size(22.dp).clickable { onClose() })
        }
        Spacer(Modifier.height(6.dp))
        Text(body, color = TextMuted, fontSize = 14.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(16.dp)).background(Accent).clickable { primary.second() },
                contentAlignment = Alignment.Center,
            ) { Text(primary.first, color = Color(0xFF0B0D10), fontWeight = FontWeight.Bold) }
            secondary?.let { (label, action) ->
                Box(
                    Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(16.dp)).background(Card).clickable { action() },
                    contentAlignment = Alignment.Center,
                ) { Text(label, color = TextMain, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

private val artClient by lazy { OkHttpClient() }

private suspend fun loadArtwork(url: String): Bitmap? = withContext(Dispatchers.IO) {
    try {
        artClient.newCall(Request.Builder().url(url).build()).execute().use { r ->
            val bytes = r.body?.bytes() ?: return@use null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    } catch (_: Exception) {
        null
    }
}
