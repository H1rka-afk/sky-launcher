package com.sky.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

/** Круглая кнопка-переключатель (Wi‑Fi, Bluetooth...). */
class RoundToggle(ctx: Context, icon: Int, private val onColor: Int) : FrameLayout(ctx) {
    private val img = ImageView(ctx)
    var onFocused: (() -> Unit)? = null
    var on = false
        set(v) { field = v; paint() }

    init {
        isFocusable = true; isClickable = true
        img.setImageResource(icon)
        addView(img, LayoutParams(ctx.dpi(28), ctx.dpi(28), Gravity.CENTER))
        Ui.zoomOnFocus(this, 1.14f) { f -> paint(); if (f) onFocused?.invoke() }
        paint()
    }

    private fun paint() {
        background = Ui.oval(context, if (on) onColor else 0x38FFFFFF, if (isFocused) Color.WHITE else 0, 2.5f)
        img.setColorFilter(Color.WHITE)
    }
}

/** Квадратная плитка 1×1. */
class CcTile(ctx: Context, icon: Int) : FrameLayout(ctx) {
    var onFocused: (() -> Unit)? = null
    init {
        isFocusable = true; isClickable = true
        val img = ImageView(ctx).apply { setImageResource(icon) }
        addView(img, LayoutParams(ctx.dpi(30), ctx.dpi(30), Gravity.CENTER))
        background = Ui.round(ctx, 24f, Ui.GLASS)
        Ui.zoomOnFocus(this, 1.08f) { f ->
            background = if (f) Ui.round(ctx, 24f, Ui.GLASS_FOCUS, Color.WHITE, 2.5f) else Ui.round(ctx, 24f, Ui.GLASS)
            if (f) onFocused?.invoke()
        }
    }
}

/** Вертикальный ползунок громкости как в iOS. ▲/▼ — изменить. */
class VolumeSlider(ctx: Context) : View(ctx) {
    var max = 15
    var value = 0
    var fixed = false
    var onChange: ((Int) -> Unit)? = null
    var onFocused: (() -> Unit)? = null

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.GLASS }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dp(2.5f); color = Color.WHITE }
    private val icon = ctx.getDrawable(R.drawable.ic_volume)!!.mutate()
    private val iconOff = ctx.getDrawable(R.drawable.ic_volume_off)!!.mutate()
    private val rect = RectF()
    private val outer = Path()
    private val level = Path()
    var muted = false

    init {
        isFocusable = true; isClickable = true
        Ui.zoomOnFocus(this, 1.05f) { f -> if (f) onFocused?.invoke(); invalidate() }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val r = w * 0.34f
        rect.set(0f, 0f, w, h)
        outer.reset(); outer.addRoundRect(rect, r, r, Path.Direction.CW)
        c.drawPath(outer, bg)
        val frac = if (max > 0 && !muted) value.toFloat() / max else 0f
        val top = h - h * frac
        level.reset(); level.addRect(0f, top, w, h, Path.Direction.CW)
        level.op(outer, Path.Op.INTERSECT)
        c.drawPath(level, fill)
        if (isFocused) {
            val i = ring.strokeWidth / 2
            rect.inset(i, i); c.drawRoundRect(rect, r, r, ring)
        }
        val d = if (muted || value == 0) iconOff else icon
        val s = (w * 0.36f).toInt(); val l = ((w - s) / 2).toInt(); val t = (h - s - w * 0.3f).toInt()
        d.setBounds(l, t, l + s, t + s)
        d.setTint(if (top < t + s / 2f) 0xFF3A3A3C.toInt() else Color.WHITE)
        d.alpha = if (fixed) 110 else 255
        d.draw(c)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (!fixed) {
                val nv = (value + if (keyCode == KeyEvent.KEYCODE_DPAD_UP) 1 else -1).coerceIn(0, max)
                if (nv != value || muted) { value = nv; onChange?.invoke(nv); invalidate() }
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

/** Пункт управления Sky OS. */
class ControlCenter(private val act: MainActivity) : FrameLayout(act) {
    private val scrim = View(act)
    private val panel = LinearLayout(act)
    private val caption = Ui.text(act, 15f, Color.WHITE, Ui.medium)
    private val bigTime = Ui.text(act, 48f, Color.WHITE, Ui.light)
    private val bigDate = Ui.text(act, 15f, 0xD9FFFFFF.toInt(), Ui.medium)
    private val u = act.dpi(80)
    private val g = act.dpi(14)

    private val wifiB = RoundToggle(act, R.drawable.ic_wifi, Ui.BLUE)
    private val btB = RoundToggle(act, R.drawable.ic_bt, Ui.BLUE)
    private val muteB = RoundToggle(act, R.drawable.ic_volume_off, Ui.RED)
    private val sleepB = RoundToggle(act, R.drawable.ic_moon, Ui.PURPLE)
    private val vol = VolumeSlider(act)
    private var btArmed = 0L
    private var closing = false
    private val am = Sys.audio(act)

    val open get() = visibility == VISIBLE && !closing

    init {
        visibility = GONE
        clipChildren = false
        scrim.setBackgroundColor(0x8C000000.toInt())
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        isClickable = true

        panel.orientation = LinearLayout.VERTICAL
        panel.clipChildren = false; panel.clipToPadding = false

        val header = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        header.addView(bigTime)
        header.addView(bigDate, lp().apply { topMargin = act.dpi(4) })
        panel.addView(header, lp().apply { bottomMargin = act.dpi(18); marginStart = act.dpi(6) })

        // Ряд A: связь • громкость • обои/экран
        val rowA = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        val conn = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; clipChildren = false
            background = Ui.round(act, 32f, Ui.GLASS)
        }
        val ts = act.dpi(62)
        fun pair(a: View, b: View) = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; clipChildren = false
            addView(a, LinearLayout.LayoutParams(ts, ts))
            addView(b, LinearLayout.LayoutParams(ts, ts).apply { marginStart = act.dpi(18) })
        }
        conn.addView(pair(wifiB, btB))
        conn.addView(pair(muteB, sleepB), lp().apply { topMargin = act.dpi(18) })
        rowA.addView(conn, LinearLayout.LayoutParams(2 * u + g, 2 * u + g))
        rowA.addView(vol, LinearLayout.LayoutParams(u, 2 * u + g).apply { marginStart = g })
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        col.addView(tile(R.drawable.ic_image, "Обои") { act.sheet.showWallpapers() }, LinearLayout.LayoutParams(u, u))
        col.addView(tile(R.drawable.ic_tv, "Экран и изображение") { act.safeStart(Intent(Settings.ACTION_DISPLAY_SETTINGS)) },
            LinearLayout.LayoutParams(u, u).apply { topMargin = g })
        rowA.addView(col, LinearLayout.LayoutParams(u, 2 * u + g).apply { marginStart = g })
        panel.addView(rowA)

        // Ряд B: быстрые действия
        val rowB = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        listOf(
            tile(R.drawable.ic_gear, "Настройки") { act.safeStart(Intent(Settings.ACTION_SETTINGS)) },
            tile(R.drawable.ic_input, "Источник сигнала (HDMI)") { act.sheet.showInputs() },
            tile(R.drawable.ic_wifi, "Сеть и интернет") { act.safeStart(Intent(Settings.ACTION_WIFI_SETTINGS)) },
            tile(R.drawable.ic_apps, "Управление приложениями") { act.safeStart(Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)) },
        ).forEachIndexed { i, t ->
            rowB.addView(t, LinearLayout.LayoutParams(u, u).apply { if (i > 0) marginStart = g })
        }
        panel.addView(rowB, lp().apply { topMargin = g })

        caption.gravity = Gravity.CENTER
        panel.addView(caption, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = act.dpi(20) })

        addView(panel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
            .apply { topMargin = act.dpi(34); marginEnd = act.dpi(56) })

        // Действия
        wifiB.onFocused = { caption.text = "Wi‑Fi · " + if (wifiB.on) "включён" else "выключен" }
        wifiB.setOnClickListener {
            val target = !Sys.wifiOn(act)
            if (Sys.setWifi(act, target)) { wifiB.on = target; wifiB.onFocused?.invoke(); postDelayed({ refresh() }, 1500) }
            else act.safeStart(Intent(Settings.ACTION_WIFI_SETTINGS))
        }
        btB.onFocused = { caption.text = "Bluetooth · " + if (btB.on) "включён" else "выключен" }
        btB.setOnClickListener {
            if (!Sys.hasBt(act)) { act.safeStart(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); return@setOnClickListener }
            val on = Sys.btOn(act)
            if (on && SystemClock.uptimeMillis() - btArmed > 3000) {
                btArmed = SystemClock.uptimeMillis()
                caption.text = "Нажмите ещё раз, чтобы выключить. Пульт по Bluetooth может отключиться"
                return@setOnClickListener
            }
            btArmed = 0
            if (Sys.setBt(act, !on)) { btB.on = !on; btB.onFocused?.invoke(); postDelayed({ refresh() }, 2000) }
            else act.safeStart(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
        muteB.onFocused = { caption.text = if (muteB.on) "Звук выключен" else "Без звука" }
        muteB.setOnClickListener {
            try { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0) } catch (e: Exception) {}
            refresh(); muteB.onFocused?.invoke()
        }
        sleepB.onFocused = { caption.text = "Сон — выключить экран" }
        sleepB.setOnClickListener {
            val svc = HomeInterceptService.instance
            if (svc != null && Build.VERSION.SDK_INT >= 28) {
                hide(); svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            } else {
                act.toast("Включите Sky OS в «Спец. возможностях», чтобы работал режим сна")
                act.safeStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        vol.onFocused = { volCaption() }
        vol.onChange = { v ->
            try {
                if (am.isStreamMute(AudioManager.STREAM_MUSIC) && v > 0)
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
            } catch (e: Exception) {}
            vol.muted = false; muteB.on = false
            volCaption()
        }
    }

    private fun volCaption() {
        caption.text = if (vol.fixed) "Громкостью управляет внешнее устройство"
        else "Громкость · ${vol.value} из ${vol.max}   ▲ ▼"
    }

    private fun lp() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun tile(icon: Int, name: String, action: () -> Unit) = CcTile(act, icon).apply {
        onFocused = { caption.text = name }
        setOnClickListener { action() }
    }

    fun refresh() {
        bigTime.text = now("H:mm")
        bigDate.text = now("EEEE, d MMMM").replaceFirstChar { it.uppercase() }
        wifiB.on = Sys.wifiOn(act)
        btB.on = Sys.btOn(act)
        try {
            vol.fixed = am.isVolumeFixed
            vol.max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            vol.value = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            vol.muted = am.isStreamMute(AudioManager.STREAM_MUSIC)
        } catch (e: Exception) {}
        muteB.on = vol.muted
        vol.invalidate()
    }

    fun show() {
        if (open) return
        refresh()
        visibility = VISIBLE
        act.overlayOpened(this)
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(220).start()
        panel.alpha = 0f; panel.scaleX = 0.94f; panel.scaleY = 0.94f; panel.translationY = -act.dp(18f)
        panel.pivotX = act.dp(380f); panel.pivotY = 0f
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setDuration(300).setInterpolator(DecelerateInterpolator(2f)).start()
        wifiB.requestFocus()
    }

    fun hide() {
        if (!open) return
        closing = true
        panel.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).translationY(-act.dp(10f)).setDuration(180).start()
        scrim.animate().alpha(0f).setDuration(200).withEndAction {
            visibility = GONE; closing = false
            act.overlayClosed(this)
        }.start()
    }
}
