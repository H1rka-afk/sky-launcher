package com.sky.launcher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Обновление ПО Sky OS: сверяет version.json из релиза на GitHub,
 * скачивает новый APK и открывает системное окно установки.
 */
object Updater {
    private const val BASE = "https://github.com/H1rka-afk/sky-launcher/releases/download/latest/"
    const val VERSION_URL = BASE + "version.json"

    class Info(val code: Int, val name: String, val notes: String, val url: String)

    var available: Info? = null
        private set
    var lastError: String? = null
    var onChange: (() -> Unit)? = null
    var checking = false
        private set
    var progress = -1   // -1 — не скачивается, 0..100 — процент
        private set

    private val main = Handler(Looper.getMainLooper())

    fun currentCode(ctx: Context): Int = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode
    } catch (e: Exception) { 0 }

    fun currentName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (e: Exception) { "?" }

    private fun open(url: String): HttpURLConnection {
        var u = url
        repeat(5) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "SkyOS-Updater")
            val code = c.responseCode
            if (code in 300..399) {
                u = c.getHeaderField("Location") ?: throw java.io.IOException("Пустой редирект")
                c.disconnect()
            } else if (code in 200..299) return c
            else { c.disconnect(); throw java.io.IOException("HTTP $code") }
        }
        throw java.io.IOException("Слишком много перенаправлений")
    }

    /** Проверка обновлений. manual = true — показать результат даже если обновлений нет. */
    fun check(ctx: Context, manual: Boolean = false) {
        if (checking) return
        checking = true; onChange?.invoke()
        val app = ctx.applicationContext
        Thread {
            try {
                val c = open(VERSION_URL)
                val o = JSONObject(c.inputStream.bufferedReader().readText()); c.disconnect()
                val info = Info(o.getInt("code"), o.optString("name", "?"), o.optString("notes", ""),
                    o.optString("url", BASE + "SkyLauncher.apk"))
                main.post {
                    checking = false; lastError = null
                    Prefs.lastUpdateCheck = System.currentTimeMillis()
                    available = if (info.code > currentCode(app)) info else null
                    if (manual && available == null) Toast.makeText(app, "Установлена последняя версия Sky OS", Toast.LENGTH_SHORT).show()
                    onChange?.invoke()
                }
            } catch (e: Exception) {
                main.post {
                    checking = false; lastError = e.message ?: e.javaClass.simpleName
                    if (manual) Toast.makeText(app, "Не удалось проверить обновления: $lastError", Toast.LENGTH_LONG).show()
                    onChange?.invoke()
                }
            }
        }.start()
    }

    /** Автопроверка не чаще раза в 6 часов. */
    fun autoCheck(ctx: Context) {
        if (!Prefs.autoUpdate) return
        if (System.currentTimeMillis() - Prefs.lastUpdateCheck < 6 * 3600_000L && available == null) return
        check(ctx)
    }

    fun downloadAndInstall(ctx: Context) {
        val info = available ?: return
        if (progress >= 0) return
        progress = 0; onChange?.invoke()
        val app = ctx.applicationContext
        Thread {
            try {
                val file = File(app.cacheDir, "update.apk")
                val c = open(info.url)
                val total = c.contentLength.toLong()
                c.inputStream.use { inp ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L; var last = -1
                        while (true) {
                            val n = inp.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            val pct = if (total > 0) (done * 100 / total).toInt() else 50
                            if (pct >= last + 5 || pct == 100) { last = pct; main.post { progress = pct; onChange?.invoke() } }
                        }
                    }
                }
                c.disconnect()
                install(app, file)
                main.post { progress = -1; onChange?.invoke() }
            } catch (e: Exception) {
                main.post {
                    progress = -1; lastError = e.message ?: e.javaClass.simpleName
                    Toast.makeText(app, "Ошибка загрузки: $lastError", Toast.LENGTH_LONG).show()
                    onChange?.invoke()
                }
            }
        }.start()
    }

    private fun install(ctx: Context, file: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("sky-os.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                s.fsync(out)
            }
            val intent = Intent(ctx, InstallReceiver::class.java)
            val pending = PendingIntent.getBroadcast(ctx, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or 0x02000000 /* MUTABLE */)
            s.commit(pending.intentSender)
        }
    }
}

/** Получает статус установки: показывает системное окно подтверждения. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { ctx.startActivity(confirm) } catch (e: Exception) {}
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "неизвестная ошибка"
                Toast.makeText(ctx, "Обновление не установлено: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
