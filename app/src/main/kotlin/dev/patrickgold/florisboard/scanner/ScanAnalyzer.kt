/*
 * Copyright (C) 2026 HushhBoard
 * Licensed under the Apache License, Version 2.0.
 */

package dev.patrickgold.florisboard.scanner

import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.ZoomSuggestionOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dev.hushhboard.nlp.entities.PaymentParser
import dev.hushhboard.nlp.entities.ScanPayload
import java.util.concurrent.TimeUnit
import kotlin.math.hypot

/** A recognised line of text, its box in view coordinates (for drawing and tapping). */
data class ScanLine(val text: String, val box: RectF)

/** What the analyzer reports to the UI, at most a few times a second. */
sealed class ScanEvent {
    /** Live status: lines currently inside the frame, how bright it is, how close to capture. */
    data class Status(
        val lines: List<ScanLine>,
        val lowLight: Boolean,
        val progress: Float,
        val hint: Hint,
    ) : ScanEvent()

    /** A QR code was read in a text mode: offer it like Google Lens does. */
    data class CodeSpotted(val raw: String) : ScanEvent()

    /** Done: a code was read (QR modes) or a payment was confirmed across frames (money modes). */
    data class Captured(val payload: ScanPayload, val sourceText: String) : ScanEvent()

    /** Nothing has been in view for a while. */
    object Idle : ScanEvent()
}

enum class Hint { LOOKING, HOLD_STEADY, MOVE_CLOSER, READING, NOT_A_PAYMENT, LOW_LIGHT }

/**
 * Camera frame analysis for the scanner.
 *
 * - QR modes decode only QR codes, accept the first successful read (a QR code carries its own
 *   error correction, so a decoded value is exact) and let ML Kit zoom in on distant codes.
 * - Text modes recognise text but only keep lines inside the on-screen scan frame; every few
 *   frames they also look for a QR code so one can be offered without switching modes.
 * - Money modes accept a payment only when the same till / paybill / account / amount is read in
 *   3 of the last 5 frames.
 * - No per-frame bitmap conversion: brightness is sampled straight from the Y plane.
 * - When nothing is in view the analysis rate drops to about one frame a second.
 */
class ScanAnalyzer(
    private val mode: ScanMode,
    /** Frame and view geometry; read on every frame because the UI lays out after binding. */
    private val geometry: () -> Geometry,
    private val isMoving: () -> Boolean,
    private val onZoom: (Float) -> Boolean,
    private val maxZoom: () -> Float,
    private val onEvent: (ScanEvent) -> Unit,
) : ImageAnalysis.Analyzer {

    /** The scan frame and the preview view, in view pixels. [mirrored] for the front camera. */
    data class Geometry(val frame: RectF, val viewWidth: Int, val viewHeight: Int, val mirrored: Boolean)

    private val isQrMode = mode == ScanMode.QR_CODE || mode == ScanMode.QR_MONEY
    private val isMoneyMode = mode == ScanMode.QR_MONEY || mode == ScanMode.OCR_MONEY

    private val barcodeScanner: BarcodeScanner by lazy {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_AZTEC, Barcode.FORMAT_DATA_MATRIX)
            .apply {
                if (isQrMode) {
                    setZoomSuggestionOptions(
                        ZoomSuggestionOptions.Builder { ratio -> onZoom(ratio) }
                            .setMaxSupportedZoomRatio(maxZoom())
                            .build(),
                    )
                }
            }
            .build()
        BarcodeScanning.getClient(options)
    }
    private val textRecognizer: TextRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private val consensus = PaymentParser.Consensus(needed = 3, window = 5)
    private var lastTextAt = 0L
    private var lastActivityAt = SystemClock.elapsedRealtime()
    private var lastFrameAt = 0L
    private var frameCount = 0
    private var darkFrames = 0
    private var stableSince = 0L
    private var lastLinesKey = ""
    @Volatile private var done = false

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        try {
            if (done) return
            val now = SystemClock.elapsedRealtime()
            val idle = now - lastActivityAt > IDLE_AFTER_MS
            val minGap = when {
                idle -> 900L
                isQrMode -> 0L
                else -> 220L
            }
            if (now - lastFrameAt < minGap) return
            lastFrameAt = now
            frameCount++

            val media = image.image ?: return
            val rotation = image.imageInfo.rotationDegrees
            val input = InputImage.fromMediaImage(media, rotation)
            val upright = if (rotation == 90 || rotation == 270) image.height to image.width else image.width to image.height
            val geo = geometry()
            val roi = viewToImage(geo, geo.frame, upright.first, upright.second)

            val lowLight = updateLowLight(image, rotation, roi)

            if (isQrMode) {
                analyzeCode(input, geo, upright, roi, lowLight, now)
            } else {
                analyzeText(input, geo, upright, roi, lowLight, now)
            }
        } catch (_: Exception) {
            // A failed frame is simply skipped.
        } finally {
            image.close()
        }
    }

    private fun analyzeCode(input: InputImage, geo: Geometry, upright: Pair<Int, Int>, roi: RectF, lowLight: Boolean, now: Long) {
        val barcodes = Tasks.await(barcodeScanner.process(input), TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val best = nearestToCentre(barcodes.filter { !it.rawValue.isNullOrEmpty() }, roi)
        if (best == null) {
            val hint = when {
                lowLight -> Hint.LOW_LIGHT
                barcodes.isNotEmpty() -> Hint.MOVE_CLOSER // seen but not decodable yet
                else -> Hint.LOOKING
            }
            if (barcodes.isNotEmpty()) lastActivityAt = now
            onEvent(ScanEvent.Status(emptyList(), lowLight, 0f, hint))
            maybeIdle(now)
            return
        }
        lastActivityAt = now
        val raw = best.rawValue!!
        val payload = ScanPayload.parse(raw)
        if (isMoneyMode && payload !is ScanPayload.Payment) {
            onEvent(ScanEvent.Status(emptyList(), lowLight, 0f, Hint.NOT_A_PAYMENT))
            return
        }
        done = true
        onEvent(ScanEvent.Captured(payload, raw))
    }

    private fun analyzeText(input: InputImage, geo: Geometry, upright: Pair<Int, Int>, roi: RectF, lowLight: Boolean, now: Long) {
        // Every 4th frame, look for a QR code too (Lens-style "code spotted").
        if (frameCount % 4 == 0) {
            val codes = Tasks.await(barcodeScanner.process(input), TIMEOUT_MS, TimeUnit.MILLISECONDS)
            nearestToCentre(codes.filter { !it.rawValue.isNullOrEmpty() }, null)?.let { onEvent(ScanEvent.CodeSpotted(it.rawValue!!)) }
        }
        val result: Text = Tasks.await(textRecognizer.process(input), TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val lines = ArrayList<ScanLine>()
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                if (!roi.contains(box.exactCenterX(), box.exactCenterY())) continue
                lines += ScanLine(line.text, imageToView(geo, box, upright.first, upright.second))
            }
        }
        lines.sortWith(compareBy({ it.box.top }, { it.box.left }))
        if (lines.isNotEmpty()) lastActivityAt = now

        val moving = isMoving()
        val text = lines.joinToString("\n") { it.text }

        if (isMoneyMode) {
            val payment = PaymentParser.parse(text)
            val confirmed = if (!moving) consensus.offer(payment) else null
            if (confirmed != null) {
                done = true
                onEvent(ScanEvent.Captured(ScanPayload.Payment(text, confirmed, confirmed.payee, verified = false), text))
                return
            }
            val hint = when {
                lowLight && lines.isEmpty() -> Hint.LOW_LIGHT
                lines.isEmpty() -> Hint.LOOKING
                moving -> Hint.HOLD_STEADY
                !payment.isValid -> Hint.NOT_A_PAYMENT
                else -> Hint.READING
            }
            onEvent(ScanEvent.Status(lines, lowLight, consensus.progress(), hint))
            maybeIdle(now)
            return
        }

        // Plain text: report progress while the same lines stay steady; the UI freezes the frame
        // for line picking when progress reaches 1.
        val key = text.lowercase().filter { it.isLetterOrDigit() }
        val similar = key.isNotEmpty() && similarity(key, lastLinesKey) > 0.8f
        lastLinesKey = key
        if (lines.isEmpty() || moving || !similar) stableSince = 0L else if (stableSince == 0L) stableSince = now
        val progress = if (stableSince == 0L) 0f else ((now - stableSince) / STABLE_MS.toFloat()).coerceIn(0f, 1f)
        val hint = when {
            lowLight && lines.isEmpty() -> Hint.LOW_LIGHT
            lines.isEmpty() -> Hint.LOOKING
            moving -> Hint.HOLD_STEADY
            else -> Hint.READING
        }
        onEvent(ScanEvent.Status(lines, lowLight, progress, hint))
        maybeIdle(now)
    }

    private fun maybeIdle(now: Long) {
        if (now - lastActivityAt > IDLE_TIMEOUT_MS) {
            done = true
            onEvent(ScanEvent.Idle)
        }
    }

    /** Restart after the UI dismissed a result (e.g. the user tapped "Scan again"). */
    fun resume() {
        consensus.reset()
        stableSince = 0L
        lastActivityAt = SystemClock.elapsedRealtime()
        done = false
    }

    fun close() {
        done = true
        try { barcodeScanner.close() } catch (_: Exception) {}
        try { textRecognizer.close() } catch (_: Exception) {}
    }

    /** Average brightness of the scan area from the Y (luma) plane, sampled sparsely. */
    private fun updateLowLight(image: ImageProxy, rotation: Int, roiUpright: RectF): Boolean {
        val plane = image.planes.firstOrNull() ?: return false
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val w = image.width
        val h = image.height
        // Map the upright ROI back to sensor orientation (only its bounds matter for sampling).
        val r = when (rotation) {
            90 -> RectF(roiUpright.top, h - roiUpright.right, roiUpright.bottom, h - roiUpright.left)
            180 -> RectF(w - roiUpright.right, h - roiUpright.bottom, w - roiUpright.left, h - roiUpright.top)
            270 -> RectF(w - roiUpright.bottom, roiUpright.left, w - roiUpright.top, roiUpright.right)
            else -> RectF(roiUpright)
        }
        val x0 = r.left.toInt().coerceIn(0, w - 1)
        val x1 = r.right.toInt().coerceIn(x0 + 1, w)
        val y0 = r.top.toInt().coerceIn(0, h - 1)
        val y1 = r.bottom.toInt().coerceIn(y0 + 1, h)
        var sum = 0L
        var n = 0
        val step = 16
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val idx = y * rowStride + x * pixelStride
                if (idx < buf.limit()) {
                    sum += buf.get(idx).toInt() and 0xFF
                    n++
                }
                x += step
            }
            y += step
        }
        val avg = if (n == 0) 128 else (sum / n).toInt()
        darkFrames = if (avg < DARK_LUMA) darkFrames + 1 else 0
        return darkFrames >= DARK_FRAMES
    }

    private fun nearestToCentre(barcodes: List<Barcode>, roi: RectF?): Barcode? {
        if (barcodes.isEmpty()) return null
        if (barcodes.size == 1 || roi == null) return barcodes.first()
        return barcodes.minByOrNull { b ->
            val box = b.boundingBox ?: return@minByOrNull Double.MAX_VALUE
            hypot((box.exactCenterX() - roi.centerX()).toDouble(), (box.exactCenterY() - roi.centerY()).toDouble())
        }
    }

    private fun similarity(a: String, b: String): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        if (a == b) return 1f
        val longer = if (a.length >= b.length) a else b
        val shorter = if (a.length >= b.length) b else a
        if (longer.length > 400) return if (longer.startsWith(shorter.take(200))) 0.9f else 0f
        val prev = IntArray(shorter.length + 1) { it }
        val cur = IntArray(shorter.length + 1)
        for (i in 1..longer.length) {
            cur[0] = i
            for (j in 1..shorter.length) {
                val cost = if (longer[i - 1] == shorter[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return (longer.length - prev[shorter.length]) / longer.length.toFloat()
    }

    companion object {
        const val IDLE_AFTER_MS = 3_000L
        const val IDLE_TIMEOUT_MS = 20_000L
        const val STABLE_MS = 1_200L
        private const val TIMEOUT_MS = 2_000L
        private const val DARK_LUMA = 45
        private const val DARK_FRAMES = 6

        /**
         * View -> upright image coordinates for a PreviewView in FILL_CENTER mode with the same
         * aspect ratio as the analysis stream (both are bound with a 16:9 selector).
         */
        fun viewToImage(geo: Geometry, r: RectF, imageW: Int, imageH: Int): RectF {
            if (geo.viewWidth <= 0 || geo.viewHeight <= 0 || r.isEmpty) return RectF(0f, 0f, imageW.toFloat(), imageH.toFloat())
            val scale = maxOf(geo.viewWidth / imageW.toFloat(), geo.viewHeight / imageH.toFloat())
            val offX = (imageW * scale - geo.viewWidth) / 2f
            val offY = (imageH * scale - geo.viewHeight) / 2f
            var left = (r.left + offX) / scale
            var right = (r.right + offX) / scale
            if (geo.mirrored) {
                val l = imageW - right
                right = imageW - left
                left = l
            }
            return RectF(left, (r.top + offY) / scale, right, (r.bottom + offY) / scale)
        }

        fun imageToView(geo: Geometry, box: Rect, imageW: Int, imageH: Int): RectF {
            if (geo.viewWidth <= 0 || geo.viewHeight <= 0) return RectF(box)
            val scale = maxOf(geo.viewWidth / imageW.toFloat(), geo.viewHeight / imageH.toFloat())
            val offX = (imageW * scale - geo.viewWidth) / 2f
            val offY = (imageH * scale - geo.viewHeight) / 2f
            var left = box.left.toFloat()
            var right = box.right.toFloat()
            if (geo.mirrored) {
                val l = imageW - right
                right = imageW - left
                left = l
            }
            return RectF(left * scale - offX, box.top * scale - offY, right * scale - offX, box.bottom * scale - offY)
        }
    }
}
