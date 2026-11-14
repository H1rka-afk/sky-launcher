package com.sky.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.View
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Погода с Open‑Meteo (бесплатно, без ключа). */
object WeatherData {
    class Hour(val label: String, val temp: Int, val code: Int, val day: Boolean)
    class Data(val temp: Int, val code: Int, val day: Boolean, val min: Int, val max: Int, val hours: List<Hour>, val time: Long)
    class Place(val name: String, val title: String, val lat: Double, val lon: Double)

    var data: Data? = null
    var failed = false
    var onUpdate: (() -> Unit)? = null
    private var loading = false
    private var cacheLoaded = false
    private val main = Handler(Looper.getMainLooper())

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("skyos_weather", Context.MODE_PRIVATE)

    fun city(ctx: Context): String = prefs(ctx).getString("city", "Ростов-на-Дону")!!
    private fun lat(ctx: Context) = prefs(ctx).getFloat("lat", 47.2357f).toDouble()
    private fun lon(ctx: Context) = prefs(ctx).getFloat("lon", 39.7015f).toDouble()

    fun setCity(ctx: Context, p: Place) {
        prefs(ctx).edit().putString("city", p.name).putFloat("lat", p.lat.toFloat()).putFloat("lon", p.lon.toFloat())
            .remove("cache").apply()
        data = null
        refresh(ctx, true)
    }

    private fun http(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 10_000
        try { return c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
    }

    fun refresh(ctx: Context, force: Boolean = false) {
        val app = ctx.applicationContext
        if (!cacheLoaded) {
            cacheLoaded = true
            prefs(app).getString("cache", null)?.let { try { data = parse(JSONObject(it)) } catch (e: Exception) {} }
        }
        val d = data
        if (loading || (!force && d != null && System.currentTimeMillis() - d.time < 30 * 60_000L)) return
        loading = true
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${lat(app)}&longitude=${lon(app)}" +
            "&current=temperature_2m,weather_code,is_day&hourly=temperature_2m,weather_code,is_day" +
            "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=2"
        Thread {
            try {
                val txt = http(url)
                val parsed = parse(JSONObject(txt))
                prefs(app).edit().putString("cache", txt).apply()
                main.post { data = parsed; failed = false; loading = false; onUpdate?.invoke() }
            } catch (e: Exception) {
                main.post { failed = true; loading = false; onUpdate?.invoke() }
            }
        }.start()
    }

    private fun parse(o: JSONObject): Data {
        val cur = o.getJSONObject("current")
        val hourly = o.getJSONObject("hourly")
        val times = hourly.getJSONArray("time")
        val temps = hourly.getJSONArray("temperature_2m")
        val codes = hourly.getJSONArray("weather_code")
        val days = hourly.getJSONArray("is_day")
        val curHour = cur.getString("time").substring(0, 13)
        var start = 0
        for (i in 0 until times.length()) if (times.getString(i).substring(0, 13) >= curHour) { start = i; break }
        val hours = (start until minOf(start + 6, times.length())).map { i ->
            Hour(times.getString(i).substring(11, 13).toInt().toString(), temps.getDouble(i).roundToInt(), codes.getInt(i), days.getInt(i) == 1)
        }
        val daily = o.getJSONObject("daily")
        return Data(
            cur.getDouble("temperature_2m").roundToInt(), cur.getInt("weather_code"), cur.getInt("is_day") == 1,
            daily.getJSONArray("temperature_2m_min").getDouble(0).roundToInt(),
            daily.getJSONArray("temperature_2m_max").getDouble(0).roundToInt(),
            hours, System.currentTimeMillis()
        )
    }

    fun search(query: String, cb: (List<Place>?) -> Unit) {
        Thread {
            val res = try {
                val txt = http("https://geocoding-api.open-meteo.com/v1/search?count=8&language=ru&format=json&name=" +
                    URLEncoder.encode(query.trim(), "UTF-8"))
                val arr = JSONObject(txt).optJSONArray("results")
                (0 until (arr?.length() ?: 0)).map { i ->
                    val r = arr!!.getJSONObject(i)
                    val name = r.getString("name")
                    val extra = listOf(r.optString("admin1"), r.optString("country")).filter { it.isNotBlank() && it != name }
                    Place(name, (listOf(name) + extra).joinToString(", "), r.getDouble("latitude"), r.getDouble("longitude"))
                }
            } catch (e: Exception) { null }
            main.post { cb(res) }
        }.start()
    }

    fun desc(code: Int): String = when (code) {
        0 -> "Ясно"; 1 -> "Преимущественно ясно"; 2 -> "Переменная облачность"; 3 -> "Пасмурно"
        45, 48 -> "Туман"; 51, 53, 55 -> "Морось"; 56, 57 -> "Ледяная морось"
        61 -> "Небольшой дождь"; 63 -> "Дождь"; 65 -> "Сильный дождь"; 66, 67 -> "Ледяной дождь"
        71 -> "Небольшой снег"; 73 -> "Снег"; 75 -> "Сильный снег"; 77 -> "Снежная крупа"
        80, 81 -> "Ливень"; 82 -> "Сильный ливень"; 85, 86 -> "Снегопад"
        95 -> "Гроза"; 96, 99 -> "Гроза с градом"; else -> "—"
    }

    /** Цвет фона виджета под погоду, как в iOS. */
    fun gradient(code: Int, day: Boolean): IntArray {
        val wet = code >= 45
        val cloudy = code == 3 || code == 2
        return when {
            !day && wet -> intArrayOf(0xFF3B4E54.toInt(), 0xFF1E2C30.toInt())
            !day -> intArrayOf(0xFF1B2A55.toInt(), 0xFF0B1330.toInt())
            code in 71..86 -> intArrayOf(0xFF8BA0B3.toInt(), 0xFF5E7386.toInt())
            wet -> intArrayOf(0xFF5D7280.toInt(), 0xFF3C4B57.toInt())
            cloudy -> intArrayOf(0xFF6F88A0.toInt(), 0xFF4A6178.toInt())
            else -> intArrayOf(0xFF3D8BE8.toInt(), 0xFF2563C4.toInt())
        }
    }
}

/** Значки погоды в стиле SF Symbols, рисуются кодом. */
object WeatherIcon {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }

    private fun cloudPath(l: Float, t: Float, w: Float): Path {
        val h = w * 0.58f
        val p = Path()
        p.addRoundRect(RectF(l, t + h * 0.42f, l + w, t + h), h * 0.29f, h * 0.29f, Path.Direction.CW)
        p.addCircle(l + w * 0.33f, t + h * 0.52f, w * 0.21f, Path.Direction.CW)
        p.addCircle(l + w * 0.6f, t + h * 0.4f, w * 0.27f, Path.Direction.CW)
        return p
    }

    private fun cloud(c: Canvas, l: Float, t: Float, w: Float, cut: Boolean) {
        if (cut) {
            val sw = w * 0.07f
            val m = android.graphics.Matrix()
            m.setScale((w + 2 * sw) / w, (w + 2 * sw) / w, l + w / 2, t + w * 0.29f)
            val big = cloudPath(l, t, w); big.transform(m)
            c.drawPath(big, clear)
        }
        c.drawPath(cloudPath(l, t, w), fill)
    }

    private fun sun(c: Canvas, cx: Float, cy: Float, r: Float) {
        c.drawCircle(cx, cy, r * 0.55f, fill)
        line.strokeWidth = r * 0.14f
        for (i in 0 until 8) {
            val a = Math.PI / 4 * i
            c.drawLine(cx + (r * 0.78f * cos(a)).toFloat(), cy + (r * 0.78f * sin(a)).toFloat(),
                cx + (r * 1.05f * cos(a)).toFloat(), cy + (r * 1.05f * sin(a)).toFloat(), line)
        }
    }

    private fun moon(c: Canvas, cx: Float, cy: Float, r: Float) {
        val p = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
        val cut = Path().apply { addCircle(cx + r * 0.55f, cy - r * 0.4f, r * 0.82f, Path.Direction.CW) }
        p.op(cut, Path.Op.DIFFERENCE)
        c.drawPath(p, fill)
    }

    private fun drops(c: Canvas, l: Float, t: Float, s: Float, n: Int, len: Float) {
        line.strokeWidth = s * 0.06f
        for (i in 0 until n) {
            val x = l + s * (0.3f + 0.2f * i)
            c.drawLine(x, t, x - s * 0.06f, t + s * len, line)
        }
    }

    fun draw(c: Canvas, code: Int, day: Boolean, l: Float, t: Float, s: Float) {
        val save = c.saveLayer(l - s * 0.1f, t - s * 0.1f, l + s * 1.1f, t + s * 1.1f, null)
        fun body(cx: Float, cy: Float, r: Float) = if (day) sun(c, cx, cy, r) else moon(c, cx, cy, r * 0.85f)
        when {
            code == 0 -> body(l + s / 2, t + s / 2, s * 0.42f)
            code == 1 || code == 2 -> { body(l + s * 0.64f, t + s * 0.32f, s * 0.28f); cloud(c, l + s * 0.02f, t + s * 0.36f, s * 0.78f, true) }
            code == 45 || code == 48 -> {
                cloud(c, l + s * 0.1f, t + s * 0.08f, s * 0.8f, false)
                line.strokeWidth = s * 0.07f
                c.drawLine(l + s * 0.12f, t + s * 0.68f, l + s * 0.88f, t + s * 0.68f, line)
                c.drawLine(l + s * 0.22f, t + s * 0.84f, l + s * 0.78f, t + s * 0.84f, line)
            }
            code in 51..67 -> { cloud(c, l + s * 0.1f, t + s * 0.06f, s * 0.8f, false); drops(c, l, t + s * 0.66f, s, 3, if (code < 60) 0.14f else 0.22f) }
            code in 80..82 -> {
                body(l + s * 0.68f, t + s * 0.24f, s * 0.24f)
                cloud(c, l + s * 0.04f, t + s * 0.16f, s * 0.74f, true)
                drops(c, l - s * 0.04f, t + s * 0.7f, s, 3, 0.18f)
            }
            code in 71..77 || code == 85 || code == 86 -> {
                cloud(c, l + s * 0.1f, t + s * 0.06f, s * 0.8f, false)
                for (i in 0 until 3) c.drawCircle(l + s * (0.3f + 0.2f * i), t + s * (0.76f + 0.06f * (i % 2)), s * 0.055f, fill)
            }
            code >= 95 -> {
                cloud(c, l + s * 0.1f, t + s * 0.04f, s * 0.8f, false)
                val b = Path().apply {
                    moveTo(l + s * 0.52f, t + s * 0.58f); lineTo(l + s * 0.36f, t + s * 0.8f); lineTo(l + s * 0.5f, t + s * 0.8f)
                    lineTo(l + s * 0.42f, t + s * 1.0f); lineTo(l + s * 0.66f, t + s * 0.72f); lineTo(l + s * 0.52f, t + s * 0.72f); close()
                }
                c.drawPath(b, fill)
            }
            else -> { cloud(c, l + s * 0.18f, t + s * 0.1f, s * 0.72f, false); cloud(c, l, t + s * 0.3f, s * 0.82f, true) }
        }
        c.restoreToCount(save)
    }
}

class WeatherIconView(ctx: Context) : View(ctx) {
    var code = 3; var day = true
    fun set(code: Int, day: Boolean) { this.code = code; this.day = day; invalidate() }
    override fun onDraw(c: Canvas) {
        val s = minOf(width, height).toFloat()
        WeatherIcon.draw(c, code, day, (width - s) / 2, (height - s) / 2, s)
    }
}
