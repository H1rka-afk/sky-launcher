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
    private var installAfterDownload = false

    fun apkFile(ctx: Context) = File(ctx.cacheDir, "update.apk")

    /** Обновление уже скачано в фоне — установка начнётся мгновенно. */
    fun ready(ctx: Context): Boolean {
        val a = available ?: return false
        val f = apkFile(ctx)
        return f.exists() && f.length() > 0 &&
            ctx.getSharedPreferences("skyos_settings", Context.MODE_PRIVATE).getInt("update_ready_code", 0) == a.code
    }

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
                    // заранее скачиваем в фоне, чтобы установка была мгновенной
                    if (available != null && Prefs.autoUpdate && !ready(app)) download(app, install = false)
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
        if (ready(ctx)) { install(ctx.applicationContext, apkFile(ctx)); return }
        if (progress >= 0) { installAfterDownload = true; return }   // уже качается в фоне — установим сразу после
        download(ctx, install = true)
    }

    private fun download(ctx: Context, install: Boolean) {
        val info = available ?: return
        if (progress >= 0) return
        installAfterDownload = install
        progress = 0; onChange?.invoke()
        val app = ctx.applicationContext
        Island.setLive("update", Island.Content(icon = R.drawable.ic_refresh, title = "Обновление", ring = 0f, right = "0%"))
        Thread {
            try {
                val file = apkFile(app)
                val part = File(app.cacheDir, "update.part")
                val c = open(info.url)
                val total = c.contentLength.toLong()
                c.inputStream.use { inp ->
                    java.io.BufferedOutputStream(part.outputStream(), 256 * 1024).use { out ->
                        val buf = ByteArray(256 * 1024)
                        var done = 0L; var last = -1
                        while (true) {
                            val n = inp.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            val pct = if (total > 0) (done * 100 / total).toInt() else 50
                            if (pct >= last + 5 || pct == 100) {
                                last = pct
                                main.post { progress = pct; onChange?.invoke() }
                                Island.setLive("update", Island.Content(icon = R.drawable.ic_refresh, title = "Обновление", ring = pct / 100f, right = "$pct%"))
                            }
                        }
                    }
                }
                c.disconnect()
                file.delete(); part.renameTo(file)
                app.getSharedPreferences("skyos_settings", Context.MODE_PRIVATE).edit().putInt("update_ready_code", info.code).apply()
                Island.setLive("update", null)
                main.post {
                    progress = -1
                    onChange?.invoke()
                    if (installAfterDownload) install(app, file)
                    else Notify.post(app, "Обновление Sky OS ${info.name} готово", "Настройки → Обновление ПО → «Установить»", keep = false)
                    installAfterDownload = false
                }
            } catch (e: Exception) {
                Island.setLive("update", null)
                main.post {
                    progress = -1; lastError = e.message ?: e.javaClass.simpleName
                    if (installAfterDownload) Toast.makeText(app, "Ошибка загрузки: $lastError", Toast.LENGTH_LONG).show()
                    installAfterDownload = false
                    onChange?.invoke()
                }
            }
        }.start()
    }

    /**
     * Мгновенно открывает окно установки Android (файл отдаётся через свой ContentProvider).
     * Если это не сработало — старый способ через PackageInstaller.
     */
    private fun install(ctx: Context, file: File) {
        try {
            val uri = android.net.Uri.parse("content://${ctx.packageName}.update/update.apk")
            @Suppress("DEPRECATION")
            val i = Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            return
        } catch (e: Exception) {}
        sessionInstall(ctx, file)
    }

    private fun sessionInstall(ctx: Context, file: File) {
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

/** Отдаёт скачанный APK системному установщику. */
class UpdateProvider : android.content.ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: android.net.Uri) = "application/vnd.android.package-archive"
    override fun openFile(uri: android.net.Uri, mode: String): android.os.ParcelFileDescriptor =
        android.os.ParcelFileDescriptor.open(Updater.apkFile(context!!), android.os.ParcelFileDescriptor.MODE_READ_ONLY)
    override fun query(uri: android.net.Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): android.database.Cursor? {
        val f = Updater.apkFile(context!!)
        return android.database.MatrixCursor(arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE))
            .apply { addRow(arrayOf<Any>("SkyOS.apk", f.length())) }
    }
    override fun insert(uri: android.net.Uri, v: android.content.ContentValues?): android.net.Uri? = null
    override fun delete(uri: android.net.Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: android.net.Uri, v: android.content.ContentValues?, s: String?, a: Array<out String>?) = 0
}
