package com.kaurisystems.speakeasy

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

const val DEFAULT_SERVER = "https://speakeasy.kauri-systems.com"

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("speakeasy", Context.MODE_PRIVATE)

    var server: String
        get() = sp.getString("server", DEFAULT_SERVER) ?: DEFAULT_SERVER
        set(v) = sp.edit().putString("server", v.trim().trimEnd('/')).apply()

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit().putString("token", v.trim()).apply()

    /** Stop automatically after a pause, so one tap is enough. */
    var autoStop: Boolean
        get() = sp.getBoolean("auto_stop", true)
        set(v) = sp.edit().putBoolean("auto_stop", v).apply()
}

/** AAC/M4A recorder: small uploads, and Whisper reads it directly. */
class Recorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var startedAt = 0L
    val file: File = File(context.cacheDir, "dictation.m4a")

    val isRecording get() = recorder != null
    val elapsedMs get() = if (startedAt == 0L) 0L else System.currentTimeMillis() - startedAt

    fun start() {
        file.delete()
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioChannels(1)
        r.setAudioSamplingRate(16000)
        r.setAudioEncodingBitRate(32000)
        r.setOutputFile(file.absolutePath)
        r.prepare()
        r.start()
        recorder = r
        startedAt = System.currentTimeMillis()
    }

    /** Peak amplitude since the last call (0..32767). */
    fun amplitude(): Int = try { recorder?.maxAmplitude ?: 0 } catch (e: Exception) { 0 }

    /** Returns the recording length in ms, or 0 if nothing usable was captured. */
    fun stop(): Long {
        val r = recorder ?: return 0
        val ms = elapsedMs
        recorder = null
        startedAt = 0L
        return try {
            r.stop(); r.release(); ms
        } catch (e: RuntimeException) {
            // stop() throws if no audio frames were written (tap-tap too fast).
            r.release(); file.delete(); 0
        }
    }

    fun cancel() { stop(); file.delete() }
}

class Api(private val prefs: Prefs) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    /** Blocking; call off the main thread. Returns cleaned text (may be empty). */
    @Throws(IOException::class)
    fun dictate(audio: File, durationMs: Long, appPackage: String): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("audio", "dictation.m4a", audio.asRequestBody("audio/mp4".toMediaType()))
            .addFormDataPart("app", appPackage)
            .addFormDataPart("duration_ms", durationMs.toString())
            .build()
        val req = Request.Builder()
            .url(prefs.server + "/v1/dictate")
            .header("Authorization", "Bearer " + prefs.token)
            .post(body)
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (resp.code == 401) throw IOException("Server rejected the access token - check Settings")
            if (!resp.isSuccessful) throw IOException("Server error ${resp.code}")
            return JSONObject(text).optString("text", "")
        }
    }

    /** Checks the server is reachable and accepts the token. Returns a message for the user. */
    @Throws(IOException::class)
    fun check(): String {
        val req = Request.Builder().url(prefs.server + "/v1/check")
            .header("Authorization", "Bearer " + prefs.token).build()
        client.newCall(req).execute().use {
            return when {
                it.isSuccessful -> "Connected ✓ token accepted"
                it.code == 401 -> "Server reachable, but the token is wrong"
                else -> "Server error ${it.code}"
            }
        }
    }
}
