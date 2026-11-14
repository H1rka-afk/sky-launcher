package com.sky.launcher

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val RU: Locale = Locale.forLanguageTag("ru")

fun now(pattern: String, locale: Locale = RU): String = SimpleDateFormat(pattern, locale).format(Date())

/** Большие часы. */
class ClockWidget(ctx: Context) : LinearLayout(ctx) {
    private val time = Ui.text(ctx, 60f, Color.WHITE, Ui.light)
    private val date = Ui.text(ctx, 15f, 0xE6FFFFFF.toInt(), Ui.medium)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        background = Ui.round(ctx, 26f, Ui.GLASS)
        setPadding(ctx.dpi(24), 0, ctx.dpi(24), 0)
        addView(time)
        addView(date, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dpi(6) })
        update()
    }

    fun update() {
        time.text = now("H:mm")
        date.text = now("EEEE, d MMMM").replaceFirstChar { it.uppercase() }
    }
}

/** Календарь в стиле iOS. */
class CalendarWidget(ctx: Context) : LinearLayout(ctx) {
    private val wd = Ui.text(ctx, 13f, Ui.RED, Ui.bold, false)
    private val day = Ui.text(ctx, 52f, 0xFF1C1C1E.toInt(), Ui.light, false)
    private val mon = Ui.text(ctx, 12f, 0xFF8E8E93.toInt(), Ui.medium, false)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        background = Ui.round(ctx, 26f, 0xF2FFFFFF.toInt(), 0)
        elevation = ctx.dp(4f)
        addView(wd); addView(day, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dpi(4) })
        addView(mon, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dpi(2) })
        update()
    }

    fun update() {
        wd.text = now("EEEE").uppercase()
        day.text = now("d")
        mon.text = now("LLLL").replaceFirstChar { it.uppercase() }
    }
}

/** Состояние устройства: сеть, память, приложения. */
class DeviceWidget(ctx: Context) : LinearLayout(ctx) {
    private val title = Ui.text(ctx, 12f, 0xA6FFFFFF.toInt(), Ui.medium)
    private val netRow = Row(ctx, R.drawable.ic_wifi)
    private val storeRow = Row(ctx, R.drawable.ic_storage)
    private val appsRow = Row(ctx, R.drawable.ic_apps)

    class Row(ctx: Context, icon: Int) : LinearLayout(ctx) {
        val img = ImageView(ctx).apply { setImageResource(icon) }
        val txt = Ui.text(ctx, 15f, Color.WHITE, Ui.regular)
        init {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(img, LayoutParams(ctx.dpi(18), ctx.dpi(18)))
            addView(txt, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = ctx.dpi(10) })
        }
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        background = Ui.round(ctx, 26f, Ui.GLASS)
        setPadding(ctx.dpi(22), 0, ctx.dpi(22), 0)
        title.text = "SKY OS"; title.letterSpacing = 0.12f
        addView(title)
        val lp = { LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dpi(9) } }
        addView(netRow, lp()); addView(storeRow, lp()); addView(appsRow, lp())
    }

    fun update(appCount: Int) {
        when (Sys.net(context)) {
            Sys.Net.WIFI -> { netRow.img.setImageResource(R.drawable.ic_wifi); netRow.txt.text = "Wi‑Fi подключён" }
            Sys.Net.ETHERNET -> { netRow.img.setImageResource(R.drawable.ic_ethernet); netRow.txt.text = "Ethernet подключён" }
            Sys.Net.NONE -> { netRow.img.setImageResource(R.drawable.ic_wifi); netRow.txt.text = "Нет подключения" }
        }
        netRow.img.alpha = if (Sys.net(context) == Sys.Net.NONE) 0.4f else 1f
        storeRow.txt.text = String.format(RU, "%.1f ГБ свободно", Sys.freeGb())
        appsRow.txt.text = "Приложений: $appCount"
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
        addView(time, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL)
            .apply { marginStart = ctx.dpi(10) })

        pill.orientation = LinearLayout.HORIZONTAL
        pill.gravity = Gravity.CENTER_VERTICAL
        pill.setPadding(ctx.dpi(14), ctx.dpi(7), ctx.dpi(14), ctx.dpi(7))
        pill.isFocusable = true; pill.isClickable = true
        hint.text = "Пункт управления"; hint.visibility = View.GONE
        pill.addView(hint, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = ctx.dpi(12) })
        bt.setImageResource(R.drawable.ic_bt)
        val cc = ImageView(ctx).apply { setImageResource(R.drawable.ic_tune) }
        val s = ctx.dpi(19)
        pill.addView(wifi, LinearLayout.LayoutParams(s, s))
        pill.addView(bt, LinearLayout.LayoutParams(s, s).apply { marginStart = ctx.dpi(10) })
        pill.addView(cc, LinearLayout.LayoutParams(s, s).apply { marginStart = ctx.dpi(12) })
        addView(pill, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
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
