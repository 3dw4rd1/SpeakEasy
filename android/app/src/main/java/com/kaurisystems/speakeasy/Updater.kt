package com.kaurisystems.speakeasy

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Self-update from GitHub releases. Each CI build publishes tag v1.0.<run number> with
 * SpeakEasy.apk attached, and the app's versionCode is that same run number.
 */
class Updater(private val context: Context) {

    data class Release(val code: Long, val name: String, val notes: String, val apkUrl: String,
                       val apiUrl: String = "")

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val currentCode: Long
        get() {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
                   else @Suppress("DEPRECATION") info.versionCode.toLong()
        }

    val currentName: String
        get() = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"

    /** Newest release, or null if none / no APK attached. Blocking. */
    @Throws(IOException::class)
    fun latest(): Release? {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "SpeakEasy-app")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("GitHub ${resp.code}")
            val o = JSONObject(resp.body?.string() ?: "{}")
            val tag = o.optString("tag_name")                    // v1.0.12
            val code = tag.substringAfterLast('.').toLongOrNull() ?: return null
            val assets = o.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    return Release(code, tag.removePrefix("v"), o.optString("body"),
                        a.optString("browser_download_url"), a.optString("url"))
                }
            }
            return null
        }
    }

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Downloads the APK and hands it to Android's installer, which asks the user to confirm. */
    @Throws(IOException::class)
    fun install(r: Release, progress: (Int) -> Unit) {
        val apk = File(context.cacheDir, "update.apk")
        // GitHub's API asset route first (its plain download links have had 504 outages),
        // then the plain link. Each one is tried twice before giving up.
        val sources = listOfNotNull(
            r.apiUrl.takeIf { it.isNotEmpty() }?.let {
                Request.Builder().url(it).header("Accept", "application/octet-stream")
            },
            Request.Builder().url(r.apkUrl),
        ).flatMap { listOf(it, it) }.map { it.header("User-Agent", "SpeakEasy-app").build() }

        var lastError = "no download source"
        val ok = sources.any { req ->
            try {
                download(req, apk, progress); true
            } catch (e: IOException) {
                lastError = e.message ?: "network error"; false
            }
        }
        if (!ok) throw IOException("Download failed ($lastError). Try again in a few minutes")
        commitInstall(apk)
    }

    @Throws(IOException::class)
    private fun download(req: Request, apk: File, progress: (Int) -> Unit) {
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Empty download")
            val total = body.contentLength()
            body.byteStream().use { input ->
                apk.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) progress((done * 100 / total).toInt())
                    }
                }
            }
        }
    }

    private fun commitInstall(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("SpeakEasy.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                        else PendingIntent.FLAG_UPDATE_CURRENT
            val pending = PendingIntent.getBroadcast(
                context, sessionId, Intent(context, UpdateReceiver::class.java), flags)
            session.commit(pending.intentSender)
        }
        apk.delete()
    }

    companion object {
        const val REPO = "3dw4rd1/SpeakEasy"
    }
}

/** Receives the installer's result: shows the confirm screen, or reports a failure. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { context.startActivity(it) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit  // the app restarts on the new version
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                Toast.makeText(context, "Update didn't install: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
