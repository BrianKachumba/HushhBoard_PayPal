/*
 * Copyright (C) 2026 HushhBoard
 * Licensed under the Apache License, Version 2.0.
 */

package dev.patrickgold.florisboard.ears

import android.net.Uri
import dev.patrickgold.florisboard.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** An identified song and where to listen to it. */
data class Song(
    val title: String,
    val artist: String,
    val album: String? = null,
    val year: String? = null,
    val songLink: String? = null,
    val spotifyUrl: String? = null,
    val appleMusicUrl: String? = null,
    val artworkUrl: String? = null,
) {
    val youtubeUrl: String get() = "https://music.youtube.com/search?q=" + Uri.encode("$title $artist")
    val shareText: String get() = "🎵 $title — $artist" + (songLink?.let { "\n$it" } ?: "")
}

sealed class Recognition {
    data class Match(val song: Song) : Recognition()
    object NoMatch : Recognition()
    data class Failed(val message: String) : Recognition()
}

/** Identifies music in a short WAV clip. Pluggable: swap the service without touching the UI. */
interface SongRecognizer {
    suspend fun recognize(wav: ByteArray): Recognition
}

object SongRecognizers {
    /**
     * The configured recognizer, or null when this build has none (then only Google's free
     * "around me" search is offered). Prefer [BuildConfig.HUSHH_SONG_ENDPOINT]: your own server
     * that forwards to the service, so no secret ships inside the APK.
     */
    fun available(): SongRecognizer? {
        val endpoint = BuildConfig.HUSHH_SONG_ENDPOINT
        val token = BuildConfig.HUSHH_AUDD_TOKEN
        return when {
            endpoint.isNotBlank() -> AudDRecognizer(endpoint, token.ifBlank { null })
            token.isNotBlank() -> AudDRecognizer("https://api.audd.io/", token)
            else -> null
        }
    }
}

/**
 * AudD's music recognition API (https://docs.audd.io): POST multipart with `file`, `api_token`
 * and `return=apple_music,spotify`. Also used for a self-hosted proxy speaking the same protocol.
 */
class AudDRecognizer(private val endpoint: String, private val token: String?) : SongRecognizer {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun recognize(wav: ByteArray): Recognition = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            if (token != null) addFormDataPart("api_token", token)
            addFormDataPart("return", "apple_music,spotify")
            addFormDataPart("file", "clip.wav", wav.toRequestBody("audio/wav".toMediaType()))
        }.build()
        val request = Request.Builder().url(endpoint).post(body).build()
        try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) return@withContext Recognition.Failed("Song search is unavailable (${response.code})")
                parse(text)
            }
        } catch (e: IOException) {
            Recognition.Failed("No internet connection")
        } catch (e: Exception) {
            Recognition.Failed("Song search failed")
        }
    }

    internal fun parse(json: String): Recognition {
        val root = JSONObject(json)
        if (root.optString("status") != "success") {
            val msg = root.optJSONObject("error")?.optString("error_message")
            return Recognition.Failed(msg?.takeIf { it.isNotBlank() } ?: "Song search failed")
        }
        val r = root.optJSONObject("result") ?: return Recognition.NoMatch
        val title = r.optString("title").ifBlank { return Recognition.NoMatch }
        val apple = r.optJSONObject("apple_music")
        val spotify = r.optJSONObject("spotify")
        val artwork = apple?.optJSONObject("artwork")?.optString("url")?.takeIf { it.isNotBlank() }
            ?.replace("{w}", "600")?.replace("{h}", "600")
            ?: spotify?.optJSONObject("album")?.optJSONArray("images")?.optJSONObject(0)?.optString("url")
        return Recognition.Match(
            Song(
                title = title,
                artist = r.optString("artist"),
                album = r.optString("album").ifBlank { null },
                year = r.optString("release_date").take(4).ifBlank { null },
                songLink = r.optString("song_link").ifBlank { null },
                spotifyUrl = spotify?.optJSONObject("external_urls")?.optString("spotify")?.ifBlank { null },
                appleMusicUrl = apple?.optString("url")?.ifBlank { null },
                artworkUrl = artwork,
            ),
        )
    }
}
