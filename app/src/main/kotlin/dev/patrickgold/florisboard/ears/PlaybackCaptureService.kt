/*
 * Copyright (C) 2026 HushhBoard
 * Licensed under the Apache License, Version 2.0.
 */

package dev.patrickgold.florisboard.ears

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.patrickgold.florisboard.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Records a few seconds of the audio other apps are playing (Android 10+ playback capture) and
 * identifies it. Android requires playback capture to run in a foreground service of type
 * "mediaProjection", started after the user agreed in the system dialog; the service stops
 * itself (and the capture) as soon as the clip is recorded.
 */
class PlaybackCaptureService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        val recognizer = SongRecognizers.available()
        if (data == null || recognizer == null) {
            SongSession.set(SongSession.State.Failed("Couldn't start listening"))
            stopSelf()
            return START_NOT_STICKY
        }
        scope.launch {
            try {
                val samples = capture(resultCode, data)
                release()
                if (samples != null) SongSession.identify(samples, ListenSource.PHONE, recognizer)
            } catch (e: Exception) {
                SongSession.set(SongSession.State.Failed("Couldn't listen to the phone's audio"))
            } finally {
                release()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission", "NewApi")
    private fun capture(resultCode: Int, data: Intent): ShortArray? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            SongSession.set(SongSession.State.Failed("Microphone permission is needed to listen"))
            return null
        }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = manager.getMediaProjection(resultCode, data) ?: return null
        projection = mp
        // Android 14+ requires a callback before the projection is used.
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                SongSession.cancelled = true
            }
        }, Handler(Looper.getMainLooper()))

        val config = AudioPlaybackCaptureConfiguration.Builder(mp)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val rec = AudioRecord.Builder()
            .setAudioFormat(AudioClip.format)
            .setBufferSizeInBytes(AudioClip.bufferSize())
            .setAudioPlaybackCaptureConfig(config)
            .build()
        record = rec
        rec.startRecording()
        SongSession.set(SongSession.State.Listening(ListenSource.PHONE, 0f, 0f))
        var lastPublish = 0L
        var level = 0f
        return AudioClip.record(
            rec,
            cancelled = { SongSession.cancelled },
            onLevel = { level = it },
            onProgress = { p ->
                val now = System.currentTimeMillis()
                if (now - lastPublish > 50) {
                    lastPublish = now
                    SongSession.set(SongSession.State.Listening(ListenSource.PHONE, level, p))
                }
            },
        )
    }

    private fun release() {
        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        record = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
    }

    override fun onDestroy() {
        release()
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Song search", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown for a few seconds while HushhBoard listens to identify a song"
                    setShowBadge(false)
                },
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_app_icon_monochrome)
            .setContentTitle("Listening for the song…")
            .setContentText("HushhBoard stops after a few seconds")
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL = "hushh_song_search"
        private const val NOTIFICATION_ID = 4471
    }
}
