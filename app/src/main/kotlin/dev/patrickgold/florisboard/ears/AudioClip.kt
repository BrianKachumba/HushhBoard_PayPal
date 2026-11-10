/*
 * Copyright (C) 2026 HushhBoard
 * Licensed under the Apache License, Version 2.0.
 */

package dev.patrickgold.florisboard.ears

import android.media.AudioFormat
import android.media.AudioRecord
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/** Records a short mono clip and packs it as a small WAV for recognition. */
object AudioClip {
    const val CAPTURE_RATE = 44_100
    /** Sent at half rate: plenty for fingerprinting, half the upload. */
    const val SEND_RATE = 22_050
    const val SECONDS = 8

    val format: AudioFormat = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(CAPTURE_RATE)
        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
        .build()

    fun bufferSize(): Int = maxOf(
        AudioRecord.getMinBufferSize(CAPTURE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
        CAPTURE_RATE / 5 * 2,
    )

    /**
     * Reads [SECONDS] of audio from a started [record]. [onLevel] gets 0..1 loudness about 20
     * times a second, [onProgress] 0..1 of the clip. Stops early when [cancelled] returns true.
     */
    fun record(
        record: AudioRecord,
        cancelled: () -> Boolean,
        onLevel: (Float) -> Unit,
        onProgress: (Float) -> Unit,
    ): ShortArray {
        val total = CAPTURE_RATE * SECONDS
        val out = ShortArray(total)
        val chunk = ShortArray(CAPTURE_RATE / 20)
        var filled = 0
        while (filled < total && !cancelled()) {
            val n = record.read(chunk, 0, minOf(chunk.size, total - filled))
            if (n <= 0) {
                if (n < 0) break else continue
            }
            System.arraycopy(chunk, 0, out, filled, n)
            filled += n
            onLevel((rms(chunk, n) / 6000f).coerceIn(0f, 1f))
            onProgress(filled / total.toFloat())
        }
        return if (filled == total) out else out.copyOf(filled)
    }

    fun rms(samples: ShortArray, n: Int = samples.size): Float {
        if (n == 0) return 0f
        var sum = 0.0
        for (i in 0 until n) {
            val s = samples[i].toDouble()
            sum += s * s
        }
        return sqrt(sum / n).toFloat()
    }

    /** True if the clip is (near) silent: nothing playing, or the app blocks recording. */
    fun isSilent(samples: ShortArray): Boolean = samples.isEmpty() || rms(samples) < 40f

    /** Halves the sample rate (simple averaging is a fine low-pass for fingerprinting) and wraps as WAV. */
    fun toWav(samples: ShortArray): ByteArray {
        val half = ShortArray(samples.size / 2) { i -> ((samples[2 * i] + samples[2 * i + 1]) / 2).toShort() }
        val dataBytes = half.size * 2
        val out = ByteArrayOutputStream(44 + dataBytes)
        fun int(v: Int) { out.write(v and 0xFF); out.write(v shr 8 and 0xFF); out.write(v shr 16 and 0xFF); out.write(v shr 24 and 0xFF) }
        fun short(v: Int) { out.write(v and 0xFF); out.write(v shr 8 and 0xFF) }
        out.write("RIFF".toByteArray()); int(36 + dataBytes); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); int(16); short(1); short(1); int(SEND_RATE); int(SEND_RATE * 2); short(2); short(16)
        out.write("data".toByteArray()); int(dataBytes)
        val buf = ByteArray(dataBytes)
        for (i in half.indices) {
            val v = half[i].toInt()
            buf[2 * i] = (v and 0xFF).toByte()
            buf[2 * i + 1] = (v shr 8 and 0xFF).toByte()
        }
        out.write(buf)
        return out.toByteArray()
    }
}
