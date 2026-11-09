package com.kaurisystems.speakeasy

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

const val DEFAULT_SERVER = "https://speakeasy.kauri-systems.com"

/** App categories for per-context styles, like Wispr Flow. */
enum class Category(val key: String, val label: String) {
    EMAIL("email", "Email"),
    WORK("work", "Work messages"),
    PERSONAL("personal", "Personal messages"),
    OTHER("other", "Everything else");

    companion object {
        private val EMAIL_APPS = listOf(
            "com.microsoft.office.outlook", "com.google.android.gm",
            "com.samsung.android.email", "ch.protonmail", "com.yahoo.mobile.client.android.mail",
        )
        private val WORK_APPS = listOf(
            "com.microsoft.teams", "com.Slack", "com.pipedrive", "com.linkedin.android",
            "com.google.android.apps.dynamite", "com.asana", "com.trello", "com.notion",
        )
        private val PERSONAL_APPS = listOf(
            "com.whatsapp", "com.facebook.orca", "org.telegram", "com.google.android.apps.messaging",
            "com.samsung.android.messaging", "com.instagram.android", "org.thoughtcrime.securesms",
            "com.snapchat.android", "com.discord",
        )

        fun of(pkg: String): Category = when {
            EMAIL_APPS.any { pkg.startsWith(it) } -> EMAIL
            WORK_APPS.any { pkg.startsWith(it) } -> WORK
            PERSONAL_APPS.any { pkg.startsWith(it) } -> PERSONAL
            else -> OTHER
        }
    }
}

val STYLE_KEYS = listOf("formal", "casual", "very_casual")
val STYLE_LABELS = listOf("Formal", "Casual", "Very casual")
val STYLE_EXAMPLES = listOf(
    "Hi Mark, I will be there at 6:30. Cheers, Ed",
    "Hey Mark, I'll be there at half 6.",
    "hey mark ill be there half 6",
)

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

    /** Send the text already in the field so names and sentence continuations come out right. */
    var sendContext: Boolean
        get() = sp.getBoolean("send_context", true)
        set(v) = sp.edit().putBoolean("send_context", v).apply()

    fun style(c: Category): String = sp.getString("style_" + c.key, defaultStyle(c)) ?: defaultStyle(c)
    fun setStyle(c: Category, s: String) = sp.edit().putString("style_" + c.key, s).apply()

    private fun defaultStyle(c: Category) = when (c) {
        Category.EMAIL -> "formal"
        Category.PERSONAL -> "very_casual"
        else -> "casual"
    }
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

data class DictationRequest(
    val audio: File,
    val durationMs: Long,
    val app: String,
    val category: Category,
    val style: String,
    val mode: String,          // "dictate", "braindump" or "edit"
    val context: String,
    val hint: String,
    val selection: String = "",   // for mode "edit": the text being rewritten
)

data class DictationResult(val text: String, val raw: String)

class Api(private val prefs: Prefs) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    private val json = "application/json".toMediaType()

    private fun request(path: String) = Request.Builder()
        .url(prefs.server + path)
        .header("Authorization", "Bearer " + prefs.token)

    private fun call(req: Request): JSONObject {
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (resp.code == 401) throw IOException("Server rejected the access token - check Settings")
            if (!resp.isSuccessful) throw IOException("Server error ${resp.code}")
            return JSONObject(body)
        }
    }

    /** Blocking; call off the main thread. */
    @Throws(IOException::class)
    fun dictate(r: DictationRequest): DictationResult {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("audio", "dictation.m4a", r.audio.asRequestBody("audio/mp4".toMediaType()))
            .addFormDataPart("app", r.app)
            .addFormDataPart("category", r.category.key)
            .addFormDataPart("style", r.style)
            .addFormDataPart("mode", r.mode)
            .addFormDataPart("context", r.context)
            .addFormDataPart("hint", r.hint)
            .addFormDataPart("selection", r.selection)
            .addFormDataPart("duration_ms", r.durationMs.toString())
            .build()
        val o = call(request("/v1/dictate").post(body).build())
        return DictationResult(o.optString("text", ""), o.optString("raw", ""))
    }

    /** Checks the server is reachable and accepts the token. Returns a message for the user. */
    @Throws(IOException::class)
    fun check(): String {
        client.newCall(request("/v1/check").build()).execute().use {
            return when {
                it.isSuccessful -> "Connected ✓ token accepted"
                it.code == 401 -> "Server reachable, but the token is wrong"
                else -> "Server error ${it.code}"
            }
        }
    }

    @Throws(IOException::class)
    fun getText(path: String): String = call(request(path).build()).optString("text", "")

    @Throws(IOException::class)
    fun putText(path: String, text: String) {
        val body = JSONObject().put("text", text).toString().toRequestBody(json)
        call(request(path).put(body).build())
    }

    /** Send a hand-corrected output. Returns the dictionary entries the server learned. */
    @Throws(IOException::class)
    fun correction(raw: String, text: String, corrected: String, app: String): List<String> {
        val body = JSONObject().put("raw", raw).put("text", text)
            .put("corrected", corrected).put("app", app).toString().toRequestBody(json)
        val arr = call(request("/v1/correction").post(body).build()).optJSONArray("learned") ?: JSONArray()
        return (0 until arr.length()).map { arr.getString(it) }
    }

    /** Send a bug report or idea. Returns the report id. */
    @Throws(IOException::class)
    fun sendFeedback(kind: String, text: String, entry: HistoryEntry?, appVersion: String): String {
        val o = JSONObject().put("kind", kind).put("text", text).put("app_version", appVersion)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")
        if (entry != null) {
            o.put("entry", JSONObject().put("raw", entry.raw).put("text", entry.text)
                .put("app", entry.app).put("mode", entry.mode).put("at", entry.at))
        }
        return call(request("/v1/feedback").post(o.toString().toRequestBody(json)).build()).optString("id")
    }

    data class Report(val at: String, val kind: String, val text: String, val status: String,
                      val note: String, val version: String)

    @Throws(IOException::class)
    fun reports(): List<Report> {
        val arr = call(request("/v1/feedback").build()).optJSONArray("reports") ?: JSONArray()
        return (0 until arr.length()).map {
            val r = arr.getJSONObject(it)
            Report(r.optString("at"), r.optString("kind"), r.optString("text"), r.optString("status"),
                r.optString("note"), r.optString("version"))
        }
    }
}

data class HistoryEntry(
    val at: Long,
    val app: String,
    val mode: String,
    val raw: String,
    val text: String,
    val error: String = "",
    val audioPath: String = "",   // kept only for failed dictations so they can be retried
    val durationMs: Long = 0,
) {
    fun toJson(): JSONObject = JSONObject().put("at", at).put("app", app).put("mode", mode)
        .put("raw", raw).put("text", text).put("error", error).put("audio", audioPath)
        .put("duration_ms", durationMs)

    companion object {
        fun fromJson(o: JSONObject) = HistoryEntry(
            o.optLong("at"), o.optString("app"), o.optString("mode", "dictate"),
            o.optString("raw"), o.optString("text"), o.optString("error"),
            o.optString("audio"), o.optLong("duration_ms"),
        )
    }
}

/** On-phone history of dictations (newest first), capped. */
class History(context: Context) {
    private val file = File(context.filesDir, "history.json")
    val audioDir = File(context.filesDir, "failed-audio").apply { mkdirs() }

    @Synchronized
    fun all(): List<HistoryEntry> = try {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { HistoryEntry.fromJson(arr.getJSONObject(it)) }
    } catch (e: Exception) { emptyList() }

    @Synchronized
    fun add(e: HistoryEntry) = save(listOf(e) + all())

    @Synchronized
    fun replace(old: HistoryEntry, new: HistoryEntry) = save(all().map { if (it == old) new else it })

    @Synchronized
    fun clear() {
        save(emptyList())
        audioDir.listFiles()?.forEach { it.delete() }
    }

    private fun save(list: List<HistoryEntry>) {
        val kept = list.take(500)
        // Drop retry audio for entries that fell off the end.
        val keepAudio = kept.map { it.audioPath }.toSet()
        audioDir.listFiles()?.filter { it.absolutePath !in keepAudio }?.forEach { it.delete() }
        val arr = JSONArray()
        kept.forEach { arr.put(it.toJson()) }
        val tmp = File(file.parentFile, "history.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }
}
