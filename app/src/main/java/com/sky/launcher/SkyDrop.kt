package com.sky.launcher

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Sky Drop — «AirDrop» для Sky OS.
 * Телевизор запускает маленький веб-сервер в домашней сети; айфон отправляет на него
 * фото, видео, ссылки и текст (страница в Safari или Быстрая команда в меню «Поделиться»).
 */
object SkyDrop {
    const val PORT = 8765

    class Item(val kind: String, val name: String, val file: File?, val text: String?, val time: Long = System.currentTimeMillis())
    class Batch(val id: Int, val items: List<Item>, val from: String)

    /** История полученного (новые первыми). */
    val received = ArrayList<Item>()
    val pending = HashMap<Int, Batch>()
    private var nextId = 1
    private var server: Server? = null
    private val main = Handler(Looper.getMainLooper())

    val running get() = server?.isAlive == true

    fun dir(ctx: Context) = File(ctx.filesDir, "drop").apply { mkdirs() }

    fun ip(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    } catch (e: Exception) { null }

    fun pageUrl(): String = "http://${ip() ?: "?"}:$PORT/?k=${Prefs.dropKey}"
    fun sendUrl(): String = "http://${ip() ?: "?"}:$PORT/send?k=${Prefs.dropKey}"

    fun start(ctx: Context) {
        Prefs.init(ctx)
        if (!Prefs.dropEnabled || running) return
        // история после перезапуска — из папки с полученными файлами
        if (received.isEmpty()) dir(ctx).listFiles()?.sortedByDescending { it.lastModified() }?.take(50)?.forEach {
            received += Item(kindOf(it.name), it.name, it, null, it.lastModified())
        }
        try {
            server = Server(ctx.applicationContext).also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
        } catch (e: Exception) { server = null }
    }

    fun stop() { try { server?.stop() } catch (e: Exception) {}; server = null }

    fun qr(text: String, size: Int): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val px = IntArray(size * size) { i -> if (m[i % size, i / size]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    fun kindOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "bmp" -> "image"
            "mp4", "mov", "m4v", "3gp", "mkv", "webm", "avi" -> "video"
            "txt", "url", "webloc" -> "text"
            else -> "file"
        }
    }

    /** Новые файлы пришли — спрашиваем на ТВ или сразу открываем. */
    fun incoming(ctx: Context, items: List<Item>, from: String) {
        main.post {
            val b = Batch(nextId++, items, from)
            pending[b.id] = b
            if (Prefs.dropAutoAccept) accept(ctx, b.id)
            else ctx.startActivity(Intent(ctx, DropActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("batch", b.id))
        }
    }

    fun decline(ctx: Context, id: Int) {
        val b = pending.remove(id) ?: return
        b.items.forEach { it.file?.delete() }
    }

    fun accept(ctx: Context, id: Int) {
        val b = pending.remove(id) ?: return
        received.addAll(0, b.items)
        while (received.size > 50) received.removeAt(received.size - 1)
        val media = b.items.filter { it.kind == "image" || it.kind == "video" }
        val texts = b.items.filter { it.kind == "text" }
        if (media.isNotEmpty()) openViewer(ctx, media)
        else texts.firstOrNull()?.text?.let { handleText(ctx, it) }
        if (media.isEmpty() && texts.isEmpty())
            Notify.post(ctx, "Sky Drop", "Файлы сохранены: ${b.items.joinToString { it.name }}", keep = false)
    }

    fun openViewer(ctx: Context, media: List<Item>) {
        ViewerActivity.items = media
        ctx.startActivity(Intent(ctx, ViewerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Ссылка → YouTube или браузер; текст → в поле ввода на экране или в буфер обмена. */
    fun handleText(ctx: Context, raw: String) {
        val text = raw.trim()
        val url = Regex("""https?://\S+""").find(text)?.value
        if (url != null) {
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {}
        }
        val svc = HomeInterceptService.instance
        val field = try { svc?.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (e: Exception) { null }
        if (field != null && field.isEditable) {
            val args = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            if (field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                Notify.post(ctx, "Sky Drop", "Текст вставлен в поле ввода", keep = false); return
            }
        }
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("Sky Drop", text))
        Notify.post(ctx, "Sky Drop · текст скопирован", text)
    }

    /** Веб-сервер приёма. */
    private class Server(val ctx: Context) : NanoHTTPD(PORT) {
        override fun serve(s: IHTTPSession): Response {
            val key = s.parameters["k"]?.firstOrNull()
            val path = s.uri.trimEnd('/')
            if (path == "/ping") return text("Sky OS")
            if (key != Prefs.dropKey) {
                return if (s.method == Method.GET) html(DropPage.noKey())
                else newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain; charset=utf-8",
                    "Неверный ключ. Отсканируйте QR-код в Настройках Sky OS → Sky Drop")
            }
            if (s.method == Method.GET) return html(DropPage.page(Prefs.dropKey))
            if (s.method != Method.POST) return text("?")
            return try {
                val files = HashMap<String, String>()
                s.parseBody(files)
                val names = s.parameters["file"] ?: emptyList()
                val items = ArrayList<Item>()
                val dir = dir(ctx)
                // файлы: file, file2, file3...
                files.keys.filter { it.startsWith("file") }.sortedBy { it.removePrefix("file").toIntOrNull() ?: 1 }
                    .forEachIndexed { i, k ->
                        val tmp = File(files[k]!!)
                        val orig = names.getOrNull(i)?.takeIf { it.isNotBlank() } ?: "file$i"
                        val safe = orig.replace(Regex("""[\\/:*?"<>|]"""), "_")
                        var dst = File(dir, safe); var n = 1
                        while (dst.exists()) dst = File(dir, safe.substringBeforeLast('.') + "_" + n++ + "." + safe.substringAfterLast('.', "bin"))
                        tmp.copyTo(dst, overwrite = true)
                        val kind = kindOf(dst.name)
                        if (kind == "text" && dst.length() < 64_000) {
                            val t = dst.readText(); dst.delete(); items += Item("text", "Текст", null, t)
                        } else items += Item(kind, dst.name, dst, null)
                    }
                // текст / ссылка
                s.parameters["text"]?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { items += Item("text", "Текст", null, it) }
                files["postData"]?.takeIf { it.isNotBlank() && !it.startsWith("/") }?.let { items += Item("text", "Текст", null, it) }
                if (items.isEmpty()) return text("Нечего отправлять")
                val from = s.headers["user-agent"]?.let { if ("iPhone" in it) "iPhone" else if ("iPad" in it) "iPad" else "Телефон" } ?: "Телефон"
                incoming(ctx, items, from)
                text("Отправлено на телевизор ✓")
            } catch (e: Exception) {
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain; charset=utf-8", "Ошибка: ${e.message}")
            }
        }

        private fun text(t: String) = newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", t)
        private fun html(t: String) = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", t)
    }
}

/** Страница, которая открывается на айфоне. */
object DropPage {
    private const val STYLE = """
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-title" content="Sky Drop">
<style>
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
body{margin:0;font:17px -apple-system,system-ui,sans-serif;background:#000;color:#fff;padding:24px 18px calc(24px + env(safe-area-inset-bottom))}
h1{font-size:34px;margin:8px 0 4px}.sub{color:#8e8e93;margin-bottom:22px}
.card{background:#1c1c1e;border-radius:16px;padding:18px;margin-bottom:16px}
button{width:100%;border:0;border-radius:14px;padding:16px;font:600 17px -apple-system,system-ui;color:#fff;background:#0a84ff}
button.sec{background:#2c2c2e}
textarea{width:100%;min-height:90px;border:0;border-radius:12px;background:#2c2c2e;color:#fff;font:17px -apple-system,system-ui;padding:12px;margin-bottom:12px;resize:none}
.bar{height:6px;border-radius:3px;background:#2c2c2e;overflow:hidden;margin-top:14px;display:none}.bar i{display:block;height:100%;width:0;background:#30d158;transition:width .2s}
#st{margin-top:10px;color:#8e8e93;min-height:22px}.ok{color:#30d158!important}.err{color:#ff453a!important}
.hint{color:#8e8e93;font-size:14px;line-height:1.4}
</style>"""

    fun noKey() = """<!doctype html><html lang="ru"><head><meta charset="utf-8"><title>Sky Drop</title>$STYLE</head><body>
<h1>Sky Drop</h1><div class="sub">Нет ключа доступа</div>
<div class="card hint">Откройте на телевизоре <b>Настройки Sky OS → Sky Drop</b> и отсканируйте QR-код камерой iPhone.</div></body></html>"""

    fun page(key: String) = """<!doctype html><html lang="ru"><head><meta charset="utf-8"><title>Sky Drop</title>$STYLE</head><body>
<h1>Sky Drop</h1><div class="sub">Отправка на телевизор Sky OS</div>
<div class="card">
<input id="f" type="file" multiple accept="image/*,video/*,*/*" hidden>
<button onclick="f.click()">Выбрать фото или видео</button>
<div class="bar" id="bar"><i id="p"></i></div><div id="st"></div>
</div>
<div class="card">
<textarea id="t" placeholder="Ссылка или текст (например, пароль Wi‑Fi)"></textarea>
<button class="sec" onclick="sendText()">Отправить текст</button>
</div>
<div class="card hint">Совет: нажмите «Поделиться» → «На экран „Домой“», чтобы Sky Drop открывался как приложение.<br><br>
Чтобы отправлять прямо из «Фото» через «Поделиться», создайте Быструю команду — инструкция на телевизоре в Настройках Sky OS → Sky Drop.</div>
<script>
const K="$key";
const st=document.getElementById('st'),bar=document.getElementById('bar'),p=document.getElementById('p');
function send(fd,label){
  const x=new XMLHttpRequest();x.open('POST','/send?k='+K);
  bar.style.display='block';p.style.width='0';st.className='';st.textContent=label;
  x.upload.onprogress=e=>{if(e.lengthComputable)p.style.width=(e.loaded/e.total*100)+'%'};
  x.onload=()=>{p.style.width='100%';st.className=x.status==200?'ok':'err';st.textContent=x.status==200?'Отправлено — подтвердите на телевизоре':x.responseText};
  x.onerror=()=>{st.className='err';st.textContent='Нет связи с телевизором. Он в той же сети Wi‑Fi?'};
  x.send(fd);
}
document.getElementById('f').onchange=e=>{const fs=e.target.files;if(!fs.length)return;const fd=new FormData();
  for(const f of fs)fd.append('file',f,f.name);send(fd,'Отправка: '+fs.length+' шт.');e.target.value=''};
function sendText(){const v=document.getElementById('t').value.trim();if(!v)return;const fd=new FormData();fd.append('text',v);send(fd,'Отправка текста…')}
</script></body></html>"""
}
