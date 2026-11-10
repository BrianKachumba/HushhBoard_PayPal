@file:OptIn(ExperimentalPermissionsApi::class)

package dev.patrickgold.florisboard

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.ImageDecoder
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeoSize
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dev.hushhboard.nlp.entities.PaymentParser
import dev.hushhboard.nlp.entities.ScanPayload
import dev.patrickgold.florisboard.scanner.HushhInsert
import dev.patrickgold.florisboard.scanner.Hint
import dev.patrickgold.florisboard.scanner.ScanActions
import dev.patrickgold.florisboard.scanner.ScanAnalyzer
import dev.patrickgold.florisboard.scanner.ScanEvent
import dev.patrickgold.florisboard.scanner.ScanLine
import dev.patrickgold.florisboard.scanner.ScanMode
import dev.patrickgold.florisboard.scanner.ScanResultSheet
import dev.patrickgold.florisboard.scanner.TextPicker
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * The HushhBoard scanner ("the eye"): reads QR codes, text and M-Pesa payment details over the
 * app the user is in, and sends the result where it belongs (browser, Wi-Fi, dialer, payment,
 * or straight into the field they were typing in).
 */
class TransparentCameraActivity : ComponentActivity() {

    private var fromDashboard = false
    private var isForEdit = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        window.decorView.setBackgroundColor(AndroidColor.TRANSPARENT)

        val startFront = intent.getBooleanExtra("isFront", false)
        fromDashboard = intent.getBooleanExtra("FROM_DASHBOARD", false)
        val isCodeScanInitial = intent.getBooleanExtra("IS_CODE_SCAN", false)
        isForEdit = intent.getBooleanExtra("IS_FOR_EDIT", false)

        val initialMode = when {
            isForEdit -> ScanMode.OCR_MONEY
            fromDashboard -> ScanMode.OCR_TEXT
            isCodeScanInitial -> ScanMode.QR_CODE
            else -> ScanMode.OCR_TEXT
        }
        // Which modes the shutter-side toggle switches between, per entry point.
        val alternate: (ScanMode) -> ScanMode = { m ->
            when {
                isForEdit -> if (m == ScanMode.OCR_MONEY) ScanMode.QR_MONEY else ScanMode.OCR_MONEY
                fromDashboard -> if (m == ScanMode.OCR_TEXT) ScanMode.QR_CODE else ScanMode.OCR_TEXT
                isCodeScanInitial -> if (m == ScanMode.QR_CODE) ScanMode.QR_MONEY else ScanMode.QR_CODE
                else -> if (m == ScanMode.OCR_TEXT) ScanMode.OCR_MONEY else ScanMode.OCR_TEXT
            }
        }

        setContent {
            ScannerScreen(
                startFront = startFront,
                initialMode = initialMode,
                alternateMode = alternate,
                canInsert = !fromDashboard && !isForEdit,
                onInsert = { text ->
                    HushhInsert.send(this, text)
                    closeAndShowKeyboard()
                },
                onPay = { payment, raw -> pay(payment, raw) },
                onClose = { closeAndShowKeyboard() },
            )
        }
    }

    private fun pay(payment: PaymentParser.Payment, raw: String) {
        if (isForEdit) {
            setResult(Activity.RESULT_OK, Intent().putExtra("SCANNED_RESULT", ScanActions.describe(payment)))
            finish()
            return
        }
        ScanActions.pay(this, payment, raw)
        finish()
    }

    private fun closeAndShowKeyboard() {
        finish()
        if (fromDashboard || isForEdit) return
        val appContext = applicationContext
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val imm = appContext.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                @Suppress("DEPRECATION")
                imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
            } catch (e: Exception) {
                Log.e("HushhScanner", "Could not restore the keyboard: ${e.message}")
            }
        }, 300)
    }
}

private sealed class Phase {
    object Live : Phase()
    data class Picking(val bitmap: Bitmap, val lines: List<ScanLine>?, val preselected: Set<Int>) : Phase()
    data class Result(val payload: ScanPayload, val source: String) : Phase()
}

private fun ScanMode.isCode() = this == ScanMode.QR_CODE || this == ScanMode.QR_MONEY
private fun ScanMode.isMoney() = this == ScanMode.QR_MONEY || this == ScanMode.OCR_MONEY
private fun ScanMode.accent() = when {
    isMoney() -> Color(0xFF00D47E)
    isCode() -> Color(0xFF8E7CFF)
    else -> Color.White
}

/** The scan window: square for codes, a wide band for text. */
private fun frameFor(mode: ScanMode, w: Float, h: Float): RectF {
    return if (mode.isCode()) {
        val side = w * 0.70f
        val top = h * 0.40f - side / 2f
        RectF((w - side) / 2f, top, (w + side) / 2f, top + side)
    } else {
        val fw = w * 0.88f
        val fh = minOf(w * 0.62f, h * 0.40f)
        val top = h * 0.40f - fh / 2f
        RectF((w - fw) / 2f, top, (w + fw) / 2f, top + fh)
    }
}

private class GeometryHolder {
    @Volatile var value = ScanAnalyzer.Geometry(RectF(), 0, 0, false)
}

@Composable
private fun ScannerScreen(
    startFront: Boolean,
    initialMode: ScanMode,
    alternateMode: (ScanMode) -> ScanMode,
    canInsert: Boolean,
    onInsert: (String) -> Unit,
    onPay: (PaymentParser.Payment, String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)
    val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val motion = remember { DeviceMotionTracker(sensorManager) }
    DisposableEffect(Unit) {
        motion.start()
        onDispose { motion.stop() }
    }

    var mode by remember { mutableStateOf(initialMode) }
    var isFront by remember { mutableStateOf(startFront) }
    var torch by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf<Phase>(Phase.Live) }
    var session by remember { mutableStateOf(0) }

    var lines by remember { mutableStateOf<List<ScanLine>>(emptyList()) }
    var hint by remember { mutableStateOf(Hint.LOOKING) }
    var lowLight by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var spotted by remember { mutableStateOf<String?>(null) }
    var focusAt by remember { mutableStateOf<Offset?>(null) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var camera by remember { mutableStateOf<Camera?>(null) }

    val geometry = remember { GeometryHolder() }
    val previewView = remember {
        PreviewView(context).apply {
            setBackgroundColor(AndroidColor.TRANSPARENT)
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    val stillScanner = remember { BarcodeScanning.getClient() }
    DisposableEffect(Unit) {
        onDispose {
            recognizer.close()
            stillScanner.close()
        }
    }

    fun resetLive() {
        lines = emptyList(); progress = 0f; spotted = null; hint = Hint.LOOKING
        phase = Phase.Live
        session++
    }

    fun showResult(payload: ScanPayload, source: String) {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY,
        )
        phase = Phase.Result(payload, source)
    }

    /** Freezes the current frame and lets the user pick lines on it. */
    fun freezeForPicking(bitmap: Bitmap? = previewView.bitmap) {
        val bmp = bitmap ?: return
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        phase = Phase.Picking(bmp, null, emptySet())
        val frame = frameFor(mode, bmp.width.toFloat(), bmp.height.toFloat())
        recognizer.process(InputImage.fromBitmap(bmp, 0)).addOnSuccessListener { result ->
            val picked = ArrayList<ScanLine>()
            for (b in result.textBlocks) for (l in b.lines) {
                val box = l.boundingBox ?: continue
                picked += ScanLine(l.text, RectF(box))
            }
            picked.sortWith(compareBy({ it.box.top }, { it.box.left }))
            val inside = picked.indices.filter { frame.contains(picked[it].box.centerX(), picked[it].box.centerY()) }.toSet()
            if (phase is Phase.Picking) phase = Phase.Picking(bmp, picked, inside.ifEmpty { picked.indices.toSet() })
        }.addOnFailureListener {
            if (phase is Phase.Picking) phase = Phase.Picking(bmp, emptyList(), emptySet())
        }
    }

    /** Shutter for code modes: decode the current frame directly. */
    fun captureCodeNow(bitmap: Bitmap? = previewView.bitmap, fromGallery: Boolean = false) {
        val bmp = bitmap ?: return
        stillScanner.process(InputImage.fromBitmap(bmp, 0)).addOnSuccessListener { codes ->
            val raw = codes.firstOrNull { !it.rawValue.isNullOrEmpty() }?.rawValue
            if (raw == null) {
                if (fromGallery && !mode.isMoney()) freezeForPicking(bmp)
                else Toast.makeText(context, if (fromGallery) "No code in that picture" else "No code in view yet. Move a little closer.", Toast.LENGTH_SHORT).show()
                return@addOnSuccessListener
            }
            val payload = ScanPayload.parse(raw)
            if (mode.isMoney() && payload !is ScanPayload.Payment) {
                Toast.makeText(context, "That code isn't a payment code", Toast.LENGTH_SHORT).show()
            } else {
                showResult(payload, raw)
            }
        }
    }

    /** Shutter for money text mode: accept what is readable right now (user asked for it). */
    fun captureMoneyTextNow(text: String) {
        val payment = PaymentParser.parse(text)
        if (payment.isValid) showResult(ScanPayload.Payment(text, payment, payment.payee, verified = false), text)
        else Toast.makeText(context, "No till, paybill or number found yet", Toast.LENGTH_SHORT).show()
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bmp = decodeForScan(context, uri)
        if (bmp == null) {
            Toast.makeText(context, "Couldn't open that picture", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        when {
            mode.isCode() -> captureCodeNow(bmp, fromGallery = true)
            mode.isMoney() -> recognizer.process(InputImage.fromBitmap(bmp, 0)).addOnSuccessListener { captureMoneyTextNow(it.text) }
            else -> {
                // A picture may hold a QR code: offer it alongside the text.
                stillScanner.process(InputImage.fromBitmap(bmp, 0)).addOnSuccessListener { codes ->
                    spotted = codes.firstOrNull { !it.rawValue.isNullOrEmpty() }?.rawValue
                }
                freezeForPicking(bmp)
            }
        }
    }

    BackHandler(enabled = phase !is Phase.Live) { resetLive() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { size ->
                viewSize = size
                geometry.value = ScanAnalyzer.Geometry(frameFor(mode, size.width.toFloat(), size.height.toFloat()), size.width, size.height, isFront)
            },
    ) {
        if (cameraPermission.status.isGranted) {
            val analyzer = remember(mode, isFront, session) {
                ScanAnalyzer(
                    mode = mode,
                    geometry = { geometry.value },
                    isMoving = { motion.isMoving },
                    onZoom = { ratio -> camera?.cameraControl?.setZoomRatio(ratio); camera != null },
                    maxZoom = { camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 4f },
                    onEvent = { event ->
                        Handler(Looper.getMainLooper()).post {
                            if (phase !is Phase.Live) return@post
                            when (event) {
                                is ScanEvent.Status -> {
                                    lines = event.lines; lowLight = event.lowLight; progress = event.progress; hint = event.hint
                                    if (mode == ScanMode.OCR_TEXT && event.progress >= 1f && event.lines.isNotEmpty()) freezeForPicking()
                                }
                                is ScanEvent.CodeSpotted -> spotted = event.raw
                                is ScanEvent.Captured -> showResult(event.payload, event.sourceText)
                                ScanEvent.Idle -> onClose()
                            }
                        }
                    },
                )
            }
            DisposableEffect(analyzer) { onDispose { analyzer.close() } }
            // Keep the geometry in step with mode / camera changes.
            LaunchedEffect(mode, isFront, viewSize) {
                geometry.value = ScanAnalyzer.Geometry(
                    frameFor(mode, viewSize.width.toFloat(), viewSize.height.toFloat()), viewSize.width, viewSize.height, isFront,
                )
            }
            CameraLayer(
                previewView = previewView,
                isFront = isFront,
                highRes = !mode.isCode(),
                torch = torch,
                analyzer = if (phase is Phase.Live) analyzer else null,
                onCamera = { camera = it },
            )
        } else {
            LaunchedEffect(Unit) { cameraPermission.launchPermissionRequest() }
        }

        // Scrim with the scan window cut out, corners, live line boxes, scan sweep.
        ScanOverlay(
            mode = mode,
            lines = lines,
            progress = progress,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { o ->
                        val cam = camera ?: return@detectTapGestures
                        val point = previewView.meteringPointFactory.createPoint(o.x, o.y)
                        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                            .setAutoCancelDuration(3, TimeUnit.SECONDS).build()
                        cam.cameraControl.startFocusAndMetering(action)
                        focusAt = o
                    }
                }
                .pointerInput(Unit) {
                    var total = 0f
                    detectVerticalDragGestures(
                        onDragStart = { total = 0f },
                        onVerticalDrag = { _, d -> total += d },
                        onDragEnd = {
                            if (abs(total) > 160f) { isFront = !isFront; torch = false; resetLive() }
                            total = 0f
                        },
                    )
                },
        )
        focusAt?.let { FocusRing(it) { focusAt = null } }

        // Top bar: close · mode · torch
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIcon(Icons.Rounded.Close, "Close") { onClose() }
            Spacer(Modifier.weight(1f))
            ModeTitle(mode)
            Spacer(Modifier.weight(1f))
            if (!isFront) {
                GlassIcon(if (torch) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff, "Torch", active = torch) { torch = !torch }
            } else {
                Spacer(Modifier.size(44.dp))
            }
        }

        // Hint under the window, and the low-light suggestion.
        val density = LocalDensity.current
        val frame = frameFor(mode, viewSize.width.toFloat(), viewSize.height.toFloat())
        Column(
            Modifier.fillMaxWidth().offset { IntOffset(0, (frame.bottom + with(density) { 18.dp.toPx() }).toInt()) },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HintPill(hintText(mode, hint, progress), warn = hint == Hint.HOLD_STEADY || hint == Hint.NOT_A_PAYMENT)
            AnimatedVisibility(lowLight && !torch && !isFront, enter = fadeIn(), exit = fadeOut()) {
                Row(
                    Modifier.padding(top = 10.dp).clip(CircleShape).background(Color(0xFFFFD54F)).clickable { torch = true }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.FlashOn, null, tint = Color(0xFF1A1A1A), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Too dark · Turn on torch", color = Color(0xFF1A1A1A), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
            AnimatedVisibility(spotted != null && !mode.isCode(), enter = fadeIn(), exit = fadeOut()) {
                Row(
                    Modifier.padding(top = 10.dp).clip(CircleShape).background(Color(0xFF8E7CFF))
                        .clickable { spotted?.let { showResult(ScanPayload.parse(it), it) } }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.QrCode2, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("QR code found · Open", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
        }

        // Bottom controls: gallery · shutter · mode switch
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(start = 28.dp, end = 28.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LabeledGlassIcon(Icons.Rounded.PhotoLibrary, "Upload") { galleryLauncher.launch("image/*") }
            Shutter(progress = progress, accent = mode.accent()) {
                when (mode) {
                    ScanMode.OCR_TEXT -> freezeForPicking()
                    ScanMode.OCR_MONEY -> captureMoneyTextNow(lines.joinToString("\n") { it.text })
                    ScanMode.QR_CODE, ScanMode.QR_MONEY -> captureCodeNow()
                }
            }
            val next = alternateMode(mode)
            LabeledGlassIcon(modeIcon(next), modeLabel(next)) {
                mode = next; torch = torch && !isFront; resetLive()
            }
        }

        // Frozen frame: pick lines.
        val picking = phase as? Phase.Picking
        if (picking != null) {
            val pickedLines = picking.lines
            if (pickedLines == null) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    androidx.compose.foundation.Image(
                        picking.bitmap.asImageBitmap(), null,
                        modifier = Modifier.fillMaxSize(),
                    )
                    HintPill("Reading text…", warn = false, modifier = Modifier.align(Alignment.Center))
                }
            } else {
                TextPicker(
                    bitmap = picking.bitmap,
                    lines = pickedLines,
                    preselected = picking.preselected,
                    canInsert = canInsert,
                    onInsert = onInsert,
                    onMore = { text -> phase = Phase.Result(ScanPayload.classifyText(text), text) },
                    onClose = { resetLive() },
                )
            }
        }

        // Result card over a dimmed camera.
        val result = phase as? Phase.Result
        AnimatedVisibility(
            visible = result != null,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(260, easing = FastOutSlowInEasing)) { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            val r = result ?: return@AnimatedVisibility
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable(enabled = false) {}) {
                ScanResultSheet(
                    result = r.payload,
                    canInsert = canInsert,
                    onInsert = onInsert,
                    onPay = onPay,
                    onScanAgain = { resetLive() },
                    onDone = onClose,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }

}

private fun hintText(mode: ScanMode, hint: Hint, progress: Float): String = when (hint) {
    Hint.LOOKING -> when (mode) {
        ScanMode.QR_CODE -> "Point at a QR code"
        ScanMode.QR_MONEY -> "Point at the payment QR code"
        ScanMode.OCR_MONEY -> "Point at the till or paybill details"
        ScanMode.OCR_TEXT -> "Point at the text"
    }
    Hint.HOLD_STEADY -> "Hold steady…"
    Hint.MOVE_CLOSER -> "Move a little closer"
    Hint.READING -> if (mode == ScanMode.OCR_MONEY) "Checking the details ${(progress * 3).toInt().coerceIn(1, 3)}/3 · hold still" else "Reading… hold still"
    Hint.NOT_A_PAYMENT -> if (mode.isCode()) "That isn't a payment code" else "No till or paybill in view yet"
    Hint.LOW_LIGHT -> "It's too dark to read"
}

private fun modeIcon(mode: ScanMode): ImageVector = when (mode) {
    ScanMode.OCR_TEXT -> Icons.Rounded.DocumentScanner
    ScanMode.QR_CODE -> Icons.Rounded.QrCodeScanner
    ScanMode.OCR_MONEY, ScanMode.QR_MONEY -> Icons.Rounded.Payments
}

private fun modeLabel(mode: ScanMode): String = when (mode) {
    ScanMode.OCR_TEXT -> "Text"
    ScanMode.QR_CODE -> "Code"
    ScanMode.OCR_MONEY -> "Pay · text"
    ScanMode.QR_MONEY -> "Pay · code"
}

@Composable
private fun ModeTitle(mode: ScanMode) {
    val title = when (mode) {
        ScanMode.OCR_TEXT -> "Scan text"
        ScanMode.QR_CODE -> "Scan code"
        ScanMode.OCR_MONEY -> "Scan to pay"
        ScanMode.QR_MONEY -> "Scan payment QR"
    }
    Row(
        Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(mode.accent()))
        Spacer(Modifier.width(8.dp))
        Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
private fun HintPill(text: String, warn: Boolean, modifier: Modifier = Modifier) {
    Text(
        text,
        color = if (warn) Color(0xFFFFD54F) else Color.White,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 16.dp, vertical = 9.dp),
    )
}

@Composable
private fun GlassIcon(icon: ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(if (active) Color(0xFFFFD54F) else Color.Black.copy(alpha = 0.5f)).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (active) Color(0xFF1A1A1A) else Color.White, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun LabeledGlassIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f))
                .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape).clickable { onClick() },
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(24.dp)) }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Shutter(progress: Float, accent: Color, onClick: () -> Unit) {
    val animated by animateFloatAsState(progress, tween(250), label = "shutter")
    val view = LocalView.current
    Box(
        Modifier.size(84.dp).clip(CircleShape).clickable {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onClick()
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 5.dp.toPx()
            drawCircle(Color.White.copy(alpha = 0.35f), radius = size.minDimension / 2f - stroke / 2f, style = Stroke(stroke))
            if (animated > 0f) {
                drawArc(
                    color = accent, startAngle = -90f, sweepAngle = 360f * animated, useCenter = false,
                    topLeft = Offset(stroke / 2f, stroke / 2f), size = GeoSize(size.width - stroke, size.height - stroke),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
            drawCircle(Color.White, radius = size.minDimension / 2f - stroke * 2.2f)
        }
    }
}

@Composable
private fun FocusRing(at: Offset, onDone: () -> Unit) {
    val anim by animateFloatAsState(1f, tween(450), label = "focus")
    LaunchedEffect(at) {
        kotlinx.coroutines.delay(900)
        onDone()
    }
    Canvas(Modifier.fillMaxSize()) {
        val r = (46 - 12 * anim).dp.toPx()
        drawCircle(Color.White.copy(alpha = 0.9f), radius = r, center = at, style = Stroke(1.6.dp.toPx()))
    }
}

@Composable
private fun ScanOverlay(mode: ScanMode, lines: List<ScanLine>, progress: Float, modifier: Modifier) {
    val accent = mode.accent()
    val sweep = rememberInfiniteTransition(label = "sweep")
    val sweepPos by sweep.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Reverse), label = "pos")
    val breathe by sweep.animateFloat(0.92f, 1f, infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")

    Canvas(modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
        val f = frameFor(mode, size.width, size.height)
        val radius = 26.dp.toPx()
        // Dim outside the window.
        drawRect(Color.Black.copy(alpha = 0.52f))
        drawRoundRect(
            Color.Transparent, topLeft = Offset(f.left, f.top), size = GeoSize(f.width(), f.height()),
            cornerRadius = CornerRadius(radius), blendMode = BlendMode.Clear,
        )
        // Live text lines (Lens style).
        if (!mode.isCode()) {
            for (l in lines) {
                val b = l.box
                drawRoundRect(
                    Color.White.copy(alpha = 0.16f), topLeft = Offset(b.left - 3f, b.top - 3f),
                    size = GeoSize(b.width() + 6f, b.height() + 6f), cornerRadius = CornerRadius(6.dp.toPx()),
                )
            }
        } else {
            // Gentle sweep line inside the code window.
            val y = f.top + f.height() * (0.12f + 0.76f * sweepPos)
            drawLine(
                Brush.horizontalGradient(listOf(Color.Transparent, accent.copy(alpha = 0.85f), Color.Transparent), f.left, f.right),
                Offset(f.left + 18.dp.toPx(), y), Offset(f.right - 18.dp.toPx(), y), strokeWidth = 2.dp.toPx(),
            )
        }
        // Corner brackets, breathing slightly; filled with the accent as reading progresses.
        val len = minOf(f.width(), f.height()) * 0.16f * breathe
        val stroke = 4.dp.toPx()
        val color = if (progress > 0f) accent else Color.White
        val path = Path().apply {
            // top-left
            moveTo(f.left, f.top + len); lineTo(f.left, f.top + radius / 2); quadraticTo(f.left, f.top, f.left + radius / 2, f.top); lineTo(f.left + len, f.top)
            // top-right
            moveTo(f.right - len, f.top); lineTo(f.right - radius / 2, f.top); quadraticTo(f.right, f.top, f.right, f.top + radius / 2); lineTo(f.right, f.top + len)
            // bottom-right
            moveTo(f.right, f.bottom - len); lineTo(f.right, f.bottom - radius / 2); quadraticTo(f.right, f.bottom, f.right - radius / 2, f.bottom); lineTo(f.right - len, f.bottom)
            // bottom-left
            moveTo(f.left + len, f.bottom); lineTo(f.left + radius / 2, f.bottom); quadraticTo(f.left, f.bottom, f.left, f.bottom - radius / 2); lineTo(f.left, f.bottom - len)
        }
        drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/**
 * Binds preview + analysis with the same 16:9 aspect ratio so boxes map exactly between the
 * analysis image and the screen. Text modes use 1080p for small print; code modes 720p (ML Kit's
 * auto-zoom handles distance) for speed.
 */
@Composable
private fun CameraLayer(
    previewView: PreviewView,
    isFront: Boolean,
    highRes: Boolean,
    torch: Boolean,
    analyzer: ScanAnalyzer?,
    onCamera: (Camera) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }
    var analysis by remember { mutableStateOf<ImageAnalysis?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }

    LaunchedEffect(isFront, highRes) {
        val provider = cameraProvider(context)
        val target = if (highRes) Size(1920, 1080) else Size(1280, 720)
        val selector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(target, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
            .build()
        val preview = Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY).build(),
            )
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val imageAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(selector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        val cameraSelector = if (isFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        try {
            provider.unbindAll()
            val cam = provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
            camera = cam
            analysis = imageAnalysis
            onCamera(cam)
        } catch (e: Exception) {
            Log.e("HushhScanner", "Camera binding failed", e)
        }
    }

    LaunchedEffect(analysis, analyzer) {
        val a = analysis ?: return@LaunchedEffect
        if (analyzer != null) a.setAnalyzer(executor, analyzer) else a.clearAnalyzer()
    }
    LaunchedEffect(camera, torch) {
        val cam = camera ?: return@LaunchedEffect
        if (cam.cameraInfo.hasFlashUnit()) cam.cameraControl.enableTorch(torch && !isFront)
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

private suspend fun cameraProvider(context: Context): ProcessCameraProvider = suspendCancellableCoroutine { cont ->
    val future = ProcessCameraProvider.getInstance(context)
    future.addListener({ cont.resume(future.get()) }, ContextCompat.getMainExecutor(context))
}

/** Decodes a picked image, upright and at most ~2000 px, for scanning and display. */
private fun decodeForScan(context: Context, uri: Uri): Bitmap? = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > 2000) {
                val s = 2000f / longest
                decoder.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt())
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2000) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }
} catch (e: Exception) {
    null
}

/** Accelerometer: "hold steady" hints and a steadiness gate for capture. */
class DeviceMotionTracker(private val sensorManager: SensorManager) : SensorEventListener {
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    @Volatile
    var isMoving: Boolean = false
        private set

    private val last = FloatArray(3)
    private var hasLast = false

    fun start() {
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() = sensorManager.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val v = event.values
        if (hasLast) {
            val delta = abs(last[0] - v[0]) + abs(last[1] - v[1]) + abs(last[2] - v[2])
            isMoving = delta > MOVEMENT_THRESHOLD
        }
        System.arraycopy(v, 0, last, 0, 3)
        hasLast = true
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val MOVEMENT_THRESHOLD = 1.6f
    }
}
