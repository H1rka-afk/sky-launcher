package com.sky.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

val RU: Locale = Locale.forLanguageTag("ru")

fun now(pattern: String, locale: Locale = RU): String = SimpleDateFormat(pattern, locale).format(Date())

private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT

object WidgetDefs {
    class Def(val kind: String, val name: String, val w: Int, val h: Int, val icon: Int)

    val all = listOf(
        Def("weather", "Погода", 3, 1, R.drawable.ic_weather),
        Def("weather", "Погода (большой)", 4, 2, R.drawable.ic_weather),
        Def("weather", "Погода (квадрат)", 2, 2, R.drawable.ic_weather),
        Def("clock", "Часы", 3, 1, R.drawable.ic_clock),
        Def("aclock", "Часы со стрелками", 2, 2, R.drawable.ic_clock),
        Def("calendar", "Календарь", 1, 1, R.drawable.ic_calendar),
        Def("month", "Календарь на месяц", 2, 2, R.drawable.ic_calendar),
        Def("photo", "Фото природы", 4, 2, R.drawable.ic_image),
        Def("note", "Заметка", 2, 2, R.drawable.ic_note),
        Def("device", "Устройство", 3, 1, R.drawable.ic_tv),
    )

    fun get(kind: String) = all.firstOrNull { it.kind == kind } ?: all[0]

    fun create(act: MainActivity, item: HomeItem): WidgetView = when (item.kind) {
        "weather", "weather_s" -> WeatherWidget(act, item)
        "clock" -> ClockWidget(act, item)
        "aclock" -> AnalogClockWidget(act, item)
        "calendar" -> CalendarWidget(act, item)
        "month" -> MonthWidget(act, item)
        "photo" -> PhotoWidget(act, item)
        "note" -> NoteWidget(act, item)
        else -> DeviceWidget(act, item)
    }
}

/** Базовый виджет: фокус пультом, подсветка, меню. */
abstract class WidgetView(val act: MainActivity, val item: HomeItem) : FrameLayout(act) {
    protected val radius = 26f

    init {
        isFocusable = true; isClickable = true; isLongClickable = true
        clipChildren = false
        Ui.zoomOnFocus(this, 1.035f) { f -> foreground = if (f) Ui.round(act, radius, 0, Color.WHITE, 3f) else null }
    }

    open fun update() {}
    open fun onClickWidget() {}
    open fun menuItems(): List<SheetItem> = emptyList()

    protected fun glass() { background = Ui.round(act, radius, Ui.GLASS) }

    protected fun lp(w: Int = WRAP, h: Int = WRAP, top: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = act.dpi(top) }

    protected fun col(gravity: Int = Gravity.CENTER_VERTICAL) = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL; this.gravity = gravity
    }
}

// ---------------- Часы ----------------

class ClockWidget(act: MainActivity, item: HomeItem) : WidgetView(act, item) {
    private val time = Ui.text(act, 50f, Color.WHITE, Ui.light)
    private val date = Ui.text(act, 14f, 0xE6FFFFFF.toInt(), Ui.medium)

    init {
        glass()
        val c = col().apply { setPadding(act.dpi(22), 0, act.dpi(22), 0) }
        c.addView(time); c.addView(date, lp(top = 4))
        addView(c, LayoutParams(MATCH, MATCH))
        update()
    }

    override fun update() {
        time.text = now("H:mm")
        date.text = now("EEEE, d MMMM").replaceFirstChar { it.uppercase() }
    }
}

class AnalogClockWidget(act: MainActivity, item: HomeItem) : WidgetView(act, item) {
    private val dial = object : View(act) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val s = minOf(width, height).toFloat(); val cx = width / 2f; val cy = height / 2f; val r = s / 2f
            p.style = Paint.Style.FILL; p.color = Color.WHITE
            c.drawCircle(cx, cy, r, p)
            p.color = 0xFF1C1C1E.toInt(); p.textAlign = Paint.Align.CENTER
            p.textSize = r * 0.2f; p.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            for (i in 1..12) {
                val a = Math.PI / 6 * i - Math.PI / 2
                c.drawText(i.toString(), cx + (r * 0.76f * cos(a)).toFloat(), cy + (r * 0.76f * sin(a)).toFloat() + p.textSize * 0.36f, p)
            }
            p.color = 0xFFB0B0B5.toInt()
            for (i in 0 until 60) if (i % 5 != 0) {
                val a = Math.PI / 30 * i
                c.drawCircle(cx + (r * 0.93f * cos(a)).toFloat(), cy + (r * 0.93f * sin(a)).toFloat(), r * 0.012f, p)
            }
            val cal = Calendar.getInstance()
            val sec = cal.get(Calendar.SECOND); val min = cal.get(Calendar.MINUTE) + sec / 60f
            val hr = cal.get(Calendar.HOUR) + min / 60f
            p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
            fun hand(angle: Double, len: Float, w: Float, color: Int) {
                p.color = color; p.strokeWidth = w
                val a = angle - Math.PI / 2
                c.drawLine(cx, cy, cx + (len * cos(a)).toFloat(), cy + (len * sin(a)).toFloat(), p)
            }
            hand(Math.PI / 6 * hr, r * 0.5f, r * 0.07f, 0xFF1C1C1E.toInt())
            hand(Math.PI / 30 * min, r * 0.72f, r * 0.05f, 0xFF1C1C1E.toInt())
            hand(Math.PI / 30 * sec, r * 0.8f, r * 0.02f, Ui.ORANGE)
            p.style = Paint.Style.FILL; p.color = Ui.ORANGE; c.drawCircle(cx, cy, r * 0.045f, p)
            if (isAttachedToWindow && isShown) postInvalidateDelayed(1000L - System.currentTimeMillis() % 1000L)
        }
    }

    init {
        background = Ui.round(act, radius, 0xFF1C1C1E.toInt(), 0x26FFFFFF)
        val pad = act.dpi(14)
        addView(dial, LayoutParams(MATCH, MATCH).apply { setMargins(pad, pad, pad, pad) })
    }
}

// ---------------- Календарь ----------------

class CalendarWidget(act: MainActivity, item: HomeItem) : WidgetView(act, item) {
    private val wd = Ui.text(act, 11f, Ui.RED, Ui.bold, false)
    private val day = Ui.text(act, 40f, 0xFF1C1C1E.toInt(), Ui.light, false)
    private val mon = Ui.text(act, 11f, 0xFF8E8E93.toInt(), Ui.medium, false)

    init {
        background = Ui.round(act, radius, 0xF5FFFFFF.toInt(), 0)
        val c = col(Gravity.CENTER)
        wd.gravity = Gravity.CENTER; wd.maxLines = 1
        wd.setAutoSizeTextTypeUniformWithConfiguration(7, 11, 1, TypedValue.COMPLEX_UNIT_SP)
        c.addView(wd, lp(MATCH, act.dpi(14)))
        c.addView(day, lp(top = 2)); c.addView(mon, lp(top = 2))
        addView(c, LayoutParams(MATCH, MATCH).apply { setMargins(act.dpi(6), 0, act.dpi(6), 0) })
        update()
    }

    override fun update() {
        wd.text = now("EEEE").uppercase()
        day.text = now("d")
        mon.text = now("LLLL").replaceFirstChar { it.uppercase() }
    }
}

class MonthWidget(act: MainActivity, item: HomeItem) : WidgetView(act, item) {
    private val grid = object : View(act) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        override fun onDraw(c: Canvas) {
            val cal = Calendar.getInstance()
            val today = cal.get(Calendar.DAY_OF_MONTH)
            val days = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            cal.set(Calendar.DAY_OF_MONTH, 1)
            val first = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 // понедельник = 0
            val w = width.toFloat(); val h = height.toFloat()
            val head = h * 0.13f
            p.typeface = Typeface.create("sans-serif", Typeface.BOLD); p.textSize = head * 0.72f; p.color = Ui.RED
            p.textAlign = Paint.Align.LEFT
            c.drawText(now("LLLL").uppercase(), w * 0.03f, head * 0.8f, p)
            p.textAlign = Paint.Align.CENTER
            val rows = (first + days + 6) / 7
            val cw = w / 7f; val top = head * 1.35f; val rh = (h - top - head * 0.9f) / rows
            p.textSize = head * 0.55f; p.color = 0xFF8E8E93.toInt(); p.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            listOf("П", "В", "С", "Ч", "П", "С", "В").forEachIndexed { i, s -> c.drawText(s, cw * (i + 0.5f), top, p) }
            p.textSize = minOf(rh * 0.5f, cw * 0.48f)
            for (d in 1..days) {
                val idx = first + d - 1; val cx = cw * (idx % 7 + 0.5f); val cy = top + head * 0.55f + rh * (idx / 7 + 0.5f)
                if (d == today) {
                    p.color = Ui.RED; c.drawCircle(cx, cy - p.textSize * 0.35f, p.textSize * 0.8f, p)
                    p.color = Color.WHITE
                } else p.color = if (idx % 7 >= 5) 0xFF8E8E93.toInt() else 0xFF1C1C1E.toInt()
                c.drawText(d.toString(), cx, cy, p)
            }
        }
    }

    init {
        background = Ui.round(act, radius, 0xF5FFFFFF.toInt(), 0)
        val pad = act.dpi(14)
        addView(grid, LayoutParams(MATCH, MATCH).apply { setMargins(pad, pad, pad, act.dpi(6)) })
    }

    override fun update() { grid.invalidate() }
}

// ---------------- Погода ----------------

/** Погода: вид зависит от размера (3×1 — компактный, 2×2 — квадрат, 4×2 — большой). */
class WeatherWidget(act: MainActivity, item: HomeItem) : WidgetView(act, item) {
    private val compact = item.h == 1
    private val large = item.w >= 4 && item.h >= 2
    private val city = Ui.text(act, if (compact) 13f else 15f, Color.WHITE, Ui.medium, false)
    private val temp = Ui.text(act, when { compact -> 40f; large -> 54f; else -> 44f }, Color.WHITE, Ui.light, false)
    private val icon = WeatherIconView(act)
    private val cond = Ui.text(act, if (compact) 12f else 14f, Color.WHITE, Ui.medium, false)
    private val range = Ui.text(act, if (compact) 12f else 14f, Color.WHITE, Ui.medium, false)
    private val hoursRow = LinearLayout(act)
    private val msg = Ui.text(act, 13f, 0xCCFFFFFF.toInt(), Ui.regular, false)
    private var shown = -1L

    init {
        city.maxLines = 1; city.ellipsize = TextUtils.TruncateAt.END
        cond.maxLines = 1; cond.ellipsize = TextUtils.TruncateAt.END
        hoursRow.orientation = LinearLayout.HORIZONTAL
        val cityRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        cityRow.addView(city, LinearLayout.LayoutParams(WRAP, WRAP))
        cityRow.addView(ImageView(act).apply { setImageResource(R.drawable.ic_location); alpha = 0.9f },
            LinearLayout.LayoutParams(act.dpi(12), act.dpi(12)).apply { marginStart = act.dpi(5) })
        when {
            compact -> {
                val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(act.dpi(18), act.dpi(10), act.dpi(14), act.dpi(10)) }
                val left = col(Gravity.CENTER_VERTICAL)
                left.addView(cityRow); left.addView(temp, lp(top = 1))
                val condRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                condRow.addView(icon, LinearLayout.LayoutParams(act.dpi(16), act.dpi(16)).apply { marginEnd = act.dpi(5) })
                condRow.addView(cond)
                left.addView(condRow, lp(top = 1))
                row.addView(left, LinearLayout.LayoutParams(0, MATCH, 1f))
                row.addView(hoursRow, LinearLayout.LayoutParams(act.dpi(168), MATCH))
                addView(row, LayoutParams(MATCH, MATCH))
            }
            large -> {
                val box = col(Gravity.TOP).apply { setPadding(act.dpi(20), act.dpi(16), act.dpi(20), act.dpi(14)) }
                val top = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
                val left = col(Gravity.TOP)
                left.addView(cityRow); left.addView(temp, lp(top = 2))
                top.addView(left, LinearLayout.LayoutParams(0, WRAP, 1f))
                val right = col(Gravity.END)
                right.addView(icon, LinearLayout.LayoutParams(act.dpi(30), act.dpi(30)))
                right.addView(cond, lp(top = 6)); right.addView(range, lp(top = 3))
                cond.gravity = Gravity.END
                top.addView(right)
                box.addView(top, lp(MATCH))
                box.addView(hoursRow, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = act.dpi(6) })
                addView(box, LayoutParams(MATCH, MATCH))
            }
            else -> {
                val box = col(Gravity.TOP).apply { setPadding(act.dpi(16), act.dpi(14), act.dpi(16), act.dpi(14)) }
                box.addView(cityRow); box.addView(temp, lp(top = 2))
                box.addView(View(act), LinearLayout.LayoutParams(1, 0, 1f))
                box.addView(icon, LinearLayout.LayoutParams(act.dpi(24), act.dpi(24)))
                box.addView(cond, lp(MATCH, top = 4)); box.addView(range, lp(top = 2))
                addView(box, LayoutParams(MATCH, MATCH))
            }
        }
        msg.gravity = Gravity.CENTER
        addView(msg, LayoutParams(MATCH, WRAP, if (compact) Gravity.CENTER_VERTICAL or Gravity.END else Gravity.BOTTOM).apply {
            if (compact) { width = act.dpi(170); marginEnd = act.dpi(8) } else bottomMargin = act.dpi(24)
        })
        update()
    }

    override fun update() {
        val d = WeatherData.data
        val stamp = (d?.time ?: 0L) + (if (WeatherData.failed) 1 else 0) + WeatherData.city(act).hashCode()
        if (stamp == shown) return          // ничего не изменилось — не перерисовываем
        shown = stamp
        city.text = WeatherData.city(act)
        val g = WeatherData.gradient(d?.code ?: 3, d?.day ?: true)
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, g).apply { cornerRadius = act.dp(radius) }
        hoursRow.removeAllViews()
        if (d == null) {
            temp.text = "--°"; cond.text = ""; range.text = ""; icon.visibility = View.INVISIBLE
            msg.text = if (WeatherData.failed) "Нет связи с сервером погоды.\nУдерживайте OK → «Обновить»" else "Загрузка погоды…"
            msg.visibility = View.VISIBLE
            return
        }
        msg.visibility = View.GONE; icon.visibility = View.VISIBLE
        temp.text = "${d.temp}°"; icon.set(d.code, d.day)
        cond.text = WeatherData.desc(d.code)
        range.text = "↓ ${d.min}°  ↑ ${d.max}°"
        if (!large && !compact) return
        val n = if (compact) 4 else 6
        for (h in d.hours.take(n)) {
            val c = col(Gravity.CENTER)
            c.addView(Ui.text(act, if (compact) 11f else 13f, Color.WHITE, Ui.medium, false).apply { text = h.label })
            val s = act.dpi(if (compact) 20 else 26)
            c.addView(WeatherIconView(act).apply { set(h.code, h.day) }, LinearLayout.LayoutParams(s, s).apply { topMargin = act.dpi(if (compact) 4 else 6) })
            c.addView(Ui.text(act, if (compact) 13f else 15f, Color.WHITE, Ui.medium, false).apply { text = "${h.temp}°" }, lp(top = if (compact) 4 else 6))
            hoursRow.addView(c, LinearLayout.LayoutParams(0, MATCH, 1f))
        }
    }

    override fun onClickWidget() { act.toast("Обновляю погоду…"); WeatherData.refresh(act, true) }

    override fun menuItems() = listOf(
        SheetItem("Выбрать город", R.drawable.ic_location) { act.pickCity() },
        SheetItem("Обновить", R.drawable.ic_refresh) { WeatherData.refresh(act, true) },
        SheetItem("Размер виджета", R.drawable.ic_move) { act.resizeWidget(item, listOf(3 to 1, 2 to 2, 4 to 2)) },
    )
}

// ---------------- Фото, заметка, устройство ----------------

class PhotoWidget(act: MainActivity, item: HomeItem) : WidgetView(act, item) {
    private val photos = Wallpapers.all.filter { it.photo != null }
    private var idx = (System.currentTimeMillis() / 60_000L % maxOf(1, photos.size)).toInt()
    private val a = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val b = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
    private val name = Ui.text(act, 14f, Color.WHITE, Ui.medium)
    private val next = Runnable { advance() }

    init {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, act.dp(radius)) }
        }
        clipToOutline = true
        setBackgroundColor(0xFF1C1C1E.toInt())
        addView(a, LayoutParams(MATCH, MATCH)); addView(b, LayoutParams(MATCH, MATCH))
        addView(name, LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START).apply { setMargins(act.dpi(18), 0, 0, act.dpi(14)) })
        show(a)
    }

    private fun show(v: ImageView) {
        if (photos.isEmpty()) { name.text = "Нет фото"; return }
        val w = photos[idx % photos.size]
        v.setImageBitmap(Photos.decode(act, w.photo!!, 960))
        name.text = w.name
    }

    private fun advance() {
        if (photos.isEmpty()) return
        idx++
        val (front, back) = if (a.alpha > 0.5f) a to b else b to a
        show(back)
        back.animate().alpha(1f).setDuration(1200).start()
        front.animate().alpha(0f).setDuration(1200).start()
        schedule()
    }

    private fun schedule() { removeCallbacks(next); postDelayed(next, 20_000) }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); schedule() }
    override fun onDetachedFromWindow() { removeCallbacks(next); super.onDetachedFromWindow() }
    override fun onClickWidget() { advance() }
    override fun menuItems() = listOf(SheetItem("Следующее фото", R.drawable.ic_image) { advance() })
}

class NoteWidget(act: MainActivity, item: HomeItem) : WidgetView(act, item) {
    private val body = Ui.text(act, 16f, 0xFF1C1C1E.toInt(), Ui.regular, false)

    init {
        background = Ui.round(act, radius, 0xFFFFFCF2.toInt(), 0)
        val c = col(Gravity.TOP).apply { setPadding(act.dpi(18), act.dpi(16), act.dpi(18), act.dpi(14)) }
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(act).apply { setImageResource(R.drawable.ic_note); setColorFilter(Ui.ORANGE) },
            LinearLayout.LayoutParams(act.dpi(16), act.dpi(16)))
        head.addView(Ui.text(act, 13f, Ui.ORANGE, Ui.bold, false).apply { text = "ЗАМЕТКА" }, lp().apply { marginStart = act.dpi(6) })
        c.addView(head)
        body.maxLines = 6; body.ellipsize = TextUtils.TruncateAt.END; body.setLineSpacing(0f, 1.15f)
        c.addView(body, lp(MATCH, top = 10))
        addView(c, LayoutParams(MATCH, MATCH))
        update()
    }

    override fun update() { body.text = act.model.note(item.id) }
    override fun onClickWidget() { edit() }
    private fun edit() {
        act.sheet.showInput("Заметка", act.model.note(item.id).takeUnless { it.startsWith("Удерживайте") } ?: "", "Текст заметки") {
            act.model.setNote(item.id, it); update()
        }
    }
    override fun menuItems() = listOf(SheetItem("Изменить текст", R.drawable.ic_note) { edit() })
}

class DeviceWidget(act: MainActivity, item: HomeItem) : WidgetView(act, item) {
    private class Row(ctx: Context, icon: Int) : LinearLayout(ctx) {
        val img = ImageView(ctx).apply { setImageResource(icon) }
        val txt: TextView = Ui.text(ctx, 14f, Color.WHITE, Ui.regular)
        init {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(img, LayoutParams(ctx.dpi(16), ctx.dpi(16)))
            addView(txt, LayoutParams(WRAP, WRAP).apply { marginStart = ctx.dpi(10) })
        }
    }

    private val net = Row(act, R.drawable.ic_wifi)
    private val store = Row(act, R.drawable.ic_storage)
    private val apps = Row(act, R.drawable.ic_apps)

    init {
        glass()
        val c = col().apply { setPadding(act.dpi(20), 0, act.dpi(20), 0) }
        c.addView(Ui.text(act, 11f, 0xA6FFFFFF.toInt(), Ui.medium).apply { text = "SKY OS"; letterSpacing = 0.12f })
        c.addView(net, lp(top = 6)); c.addView(store, lp(top = 6)); c.addView(apps, lp(top = 6))
        addView(c, LayoutParams(MATCH, MATCH))
        update()
    }

    override fun update() {
        when (Sys.net(act)) {
            Sys.Net.WIFI -> { net.img.setImageResource(R.drawable.ic_wifi); net.txt.text = Sys.currentSsid(act)?.let { "Wi‑Fi: $it" } ?: "Wi‑Fi подключён" }
            Sys.Net.ETHERNET -> { net.img.setImageResource(R.drawable.ic_ethernet); net.txt.text = "Ethernet подключён" }
            Sys.Net.NONE -> { net.img.setImageResource(R.drawable.ic_wifi); net.txt.text = "Нет подключения" }
        }
        store.txt.text = String.format(RU, "%.1f ГБ свободно", Sys.freeGb())
        apps.txt.text = "Приложений: ${act.model.apps.size}"
    }
}

/** Верхняя строка: время слева, значки и вход в Пункт управления справа. */
class StatusBar(ctx: Context, onOpen: () -> Unit) : FrameLayout(ctx) {
    private val time = Ui.text(ctx, 17f, Color.WHITE, Ui.medium)
    val pill = LinearLayout(ctx)
    private val hint = Ui.text(ctx, 14f, Color.WHITE, Ui.medium)
    private val wifi = ImageView(ctx)
    private val bt = ImageView(ctx)

    init {
        clipChildren = false
        addView(time, LayoutParams(WRAP, WRAP, Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = ctx.dpi(10) })
        pill.orientation = LinearLayout.HORIZONTAL
        pill.gravity = Gravity.CENTER_VERTICAL
        pill.setPadding(ctx.dpi(14), ctx.dpi(7), ctx.dpi(14), ctx.dpi(7))
        pill.isFocusable = true; pill.isClickable = true
        hint.text = "Пункт управления"; hint.visibility = View.GONE
        pill.addView(hint, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = ctx.dpi(12) })
        bt.setImageResource(R.drawable.ic_bt)
        val cc = ImageView(ctx).apply { setImageResource(R.drawable.ic_tune) }
        val s = ctx.dpi(19)
        pill.addView(wifi, LinearLayout.LayoutParams(s, s))
        pill.addView(bt, LinearLayout.LayoutParams(s, s).apply { marginStart = ctx.dpi(10) })
        pill.addView(cc, LinearLayout.LayoutParams(s, s).apply { marginStart = ctx.dpi(12) })
        addView(pill, LayoutParams(WRAP, WRAP, Gravity.END or Gravity.CENTER_VERTICAL))
        pill.setOnClickListener { onOpen() }
        Ui.zoomOnFocus(pill, 1.06f) { f ->
            pill.background = if (f) Ui.round(ctx, 20f, 0x47FFFFFF, 0x40FFFFFF) else null
            hint.visibility = if (f) View.VISIBLE else View.GONE
        }
    }

    fun update() {
        time.text = now("H:mm")
        val n = Sys.net(context)
        wifi.setImageResource(if (n == Sys.Net.ETHERNET) R.drawable.ic_ethernet else R.drawable.ic_wifi)
        wifi.alpha = if (n == Sys.Net.NONE) 0.35f else 1f
        bt.visibility = if (Sys.btOn(context)) View.VISIBLE else View.GONE
    }
}
