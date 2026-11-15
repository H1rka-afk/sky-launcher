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
        addView(img, LayoutParams(ctx.dpi(22), ctx.dpi(22), Gravity.CENTER))
        Ui.zoomOnFocus(this, 1.14f) { f -> paint(); if (f) onFocused?.invoke() }
        paint()
    }

    private fun paint() {
        background = Ui.oval(context, if (on) onColor else 0x38FFFFFF, if (isFocused) Color.WHITE else 0, 2.5f)
        img.setColorFilter(Color.WHITE)
    }
}

/** Квадратная плитка 1×1. */
class CcTile(ctx: Context, icon: Int, bitmap: android.graphics.Bitmap? = null) : FrameLayout(ctx) {
    var onFocused: (() -> Unit)? = null
    init {
        isFocusable = true; isClickable = true
        val img = ImageView(ctx).apply { if (bitmap != null) setImageBitmap(bitmap) else setImageResource(icon) }
        val sz = ctx.dpi(if (bitmap != null) 36 else 24)
        addView(img, LayoutParams(sz, sz, Gravity.CENTER))
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
    private val caption = Ui.text(act, 13f, Color.WHITE, Ui.medium)
    private val bigTime = Ui.text(act, 36f, Color.WHITE, Ui.light)
    private val bigDate = Ui.text(act, 13f, 0xD9FFFFFF.toInt(), Ui.medium)
    private val u = act.dpi(62)
    private val g = act.dpi(11)

    private val wifiB = RoundToggle(act, R.drawable.ic_wifi, Ui.BLUE)
    private val btB = RoundToggle(act, R.drawable.ic_bt, Ui.BLUE)
    private val muteB = RoundToggle(act, R.drawable.ic_volume_off, Ui.RED)
    private val sleepB = RoundToggle(act, R.drawable.ic_moon, Ui.PURPLE)
    private val vol = VolumeSlider(act)
    private var movingId: String? = null
    private var moveBackup: List<String> = emptyList()
    private val wiggles = ArrayList<android.animation.ValueAnimator>()
    private val slotCol = LinearLayout(act)
    private val rows = LinearLayout(act)
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
        val ts = act.dpi(48)
        fun pair(a: View, b: View) = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; clipChildren = false
            addView(a, LinearLayout.LayoutParams(ts, ts))
            addView(b, LinearLayout.LayoutParams(ts, ts).apply { marginStart = act.dpi(18) })
        }
        conn.addView(pair(wifiB, btB))
        conn.addView(pair(muteB, sleepB), lp().apply { topMargin = act.dpi(18) })
        rowA.addView(conn, LinearLayout.LayoutParams(2 * u + g, 2 * u + g))
        rowA.addView(vol, LinearLayout.LayoutParams(u, 2 * u + g).apply { marginStart = g })
        slotCol.orientation = LinearLayout.VERTICAL; slotCol.clipChildren = false
        rowA.addView(slotCol, LinearLayout.LayoutParams(u, 2 * u + g).apply { marginStart = g })
        panel.addView(rowA)

        // Настраиваемые плитки (Настройки Sky OS → Пункт управления)
        rows.orientation = LinearLayout.VERTICAL; rows.clipChildren = false
        panel.addView(rows, lp())
        rebuildTiles()

        caption.gravity = Gravity.CENTER
        panel.addView(caption, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = act.dpi(20) })

        addView(panel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
            .apply { topMargin = act.dpi(34); marginEnd = act.dpi(56) })

        // Действия
        wifiB.onFocused = { caption.text = "Wi‑Fi · " + (if (wifiB.on) (Sys.currentSsid(act) ?: "включён") else "выключен") + "  ·  удерживайте OK — выбор сети" }
        wifiB.setOnLongClickListener { act.sheet.showWifi(); true }
        wifiB.setOnClickListener {
            val target = !Sys.wifiOn(act)
            if (Sys.setWifi(act, target)) { wifiB.on = target; wifiB.onFocused?.invoke(); postDelayed({ refresh() }, 1500) }
            else act.safeStart(Intent(Settings.ACTION_WIFI_SETTINGS))
        }
        btB.onFocused = { caption.text = "Bluetooth · " + (if (btB.on) "включён" else "выключен") + "  ·  удерживайте OK — устройства" }
        btB.setOnLongClickListener { act.safeStart(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); true }
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

    /** Перестраивает плитки по списку из настроек. */
    // ---------- Настраиваемые кнопки: «+», удержание OK, перестановка ----------

    fun rebuildTiles(focusId: String? = null) {
        wiggles.forEach { it.cancel() }; wiggles.clear()
        slotCol.removeAllViews(); rows.removeAllViews()
        val ids = Prefs.ccTiles.take(CcTiles.MAX)
        val views = ids.mapNotNull { id -> makeTile(id)?.also { it.tag = id } }.toMutableList<View>()
        if (ids.size < CcTiles.MAX && movingId == null) views += plusTile()
        views.forEachIndexed { i, t ->
            if (i < 2) {
                slotCol.addView(t, LinearLayout.LayoutParams(u, u).apply { if (i > 0) topMargin = g })
            } else {
                val k = i - 2
                if (k % 4 == 0) rows.addView(LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false },
                    lp().apply { topMargin = g })
                val row = rows.getChildAt(rows.childCount - 1) as LinearLayout
                row.addView(t, LinearLayout.LayoutParams(u, u).apply { if (k % 4 > 0) marginStart = g })
            }
            if (movingId != null) {
                val moving = t.tag == movingId
                if (moving) t.foreground = Ui.round(act, 24f, 0, Color.WHITE, 3f)
                wiggles += android.animation.ObjectAnimator.ofFloat(t, View.ROTATION, if (moving) -3f else -1.5f, if (moving) 3f else 1.5f).apply {
                    duration = (120 + Math.random() * 50).toLong()
                    repeatMode = android.animation.ValueAnimator.REVERSE; repeatCount = android.animation.ValueAnimator.INFINITE
                    start()
                }
            }
        }
        val f = focusId ?: movingId
        if (f != null && open) post { views.firstOrNull { it.tag == f }?.requestFocus() }
    }

    private fun plusTile() = CcTile(act, R.drawable.ic_add).apply {
        tag = "+"
        background = Ui.round(act, 24f, 0x14FFFFFF, 0x59FFFFFF, 1.5f)
        onFocused = { caption.text = "Добавить кнопку" }
        setOnClickListener { addPicker() }
    }

    private fun makeTile(id: String): CcTile? {
        val t = if (id.startsWith("app:")) {
            val pkg = id.removePrefix("app:")
            val a = act.model.apps[pkg] ?: return null
            CcTile(act, 0, IconFactory.get(act, a, act.dpi(44))).apply {
                onFocused = { caption.text = if (movingId != null) moveHint() else "Открыть «${act.model.label(pkg)}» · удерживайте OK — изменить" }
                setOnClickListener { if (movingId == null) { hide(); act.launchPkg(pkg) } }
            }
        } else {
            val d = CcTiles.get(id) ?: return null
            CcTile(act, d.icon).apply {
                onFocused = { caption.text = if (movingId != null) moveHint() else (if (id == "timer") act.timerCaption() else d.name) }
                setOnClickListener { if (movingId == null) runTile(id) }
            }
        }
        t.setOnLongClickListener { if (movingId == null) tileMenu(id); true }
        return t
    }

    private fun tileName(id: String) =
        if (id.startsWith("app:")) act.model.label(id.removePrefix("app:")) else CcTiles.get(id)?.name ?: id

    private fun moveHint() = "◀ ▶ переставить · OK готово · Назад отмена"

    private fun tileMenu(id: String) {
        val items = arrayListOf(
            SheetItem("Переставить", R.drawable.ic_move) { startTileMove(id) },
            SheetItem("Убрать из Пункта управления", R.drawable.ic_trash, destructive = true) {
                Prefs.ccTiles = Prefs.ccTiles - id; rebuildTiles("+")
            },
        )
        if (Prefs.ccTiles.size < CcTiles.MAX) items += SheetItem("Добавить кнопку", R.drawable.ic_add) { addPicker() }
        act.sheet.showList(tileName(id), items)
    }

    private fun addPicker() {
        val have = Prefs.ccTiles
        val items = ArrayList<SheetItem>()
        CcTiles.all.filter { it.id !in have }.forEach { d -> items += SheetItem(d.name, d.icon) { addTile(d.id) } }
        items += SheetItem("Кнопка приложения…", R.drawable.ic_apps) {
            val apps = act.model.apps.values.filter { it.special == null && "app:${it.pkg}" !in have }
                .sortedBy { act.model.label(it.pkg).lowercase() }
            act.sheet.showList("Кнопка приложения", apps.map { a -> SheetItem(act.model.label(a.pkg), R.drawable.ic_apps) { addTile("app:${a.pkg}") } })
        }
        act.sheet.showList("Добавить кнопку", items)
    }

    private fun addTile(id: String) {
        val l = Prefs.ccTiles.toMutableList()
        if (id in l) return
        if (l.size >= CcTiles.MAX) { act.toast("Не больше ${CcTiles.MAX} кнопок"); return }
        l += id; Prefs.ccTiles = l
        rebuildTiles(id)
    }

    fun startTileMove(id: String) {
        movingId = id
        moveBackup = Prefs.ccTiles
        caption.text = moveHint()
        rebuildTiles(id)
    }

    private fun stepTile(d: Int) {
        val id = movingId ?: return
        val l = Prefs.ccTiles.toMutableList()
        val i = l.indexOf(id); val j = i + d
        if (i < 0 || j < 0 || j >= l.size) return
        l.removeAt(i); l.add(j, id)
        Prefs.ccTiles = l
        rebuildTiles(id)
    }

    private fun endTileMove(save: Boolean) {
        val id = movingId ?: return
        if (!save) Prefs.ccTiles = moveBackup
        movingId = null
        rebuildTiles(id)
        caption.text = if (save) "Готово" else "Отменено"
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (movingId != null && !act.sheet.open) {
            val handled = e.keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK)
            if (handled) {
                val ok = e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER
                if (e.action == KeyEvent.ACTION_DOWN && !(ok && e.repeatCount > 0)) when (e.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> stepTile(-1)
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> stepTile(1)
                    KeyEvent.KEYCODE_BACK -> endTileMove(false)
                    else -> endTileMove(true)
                }
                return true
            }
        }
        return super.dispatchKeyEvent(e)
    }

    private fun runTile(id: String) {
        when (id) {
            "wallpaper" -> act.sheet.showWallpapers()
            "sky_settings" -> { hide(); act.openSettings() }
            "tv_settings" -> act.safeStart(Intent(Settings.ACTION_SETTINGS))
            "display" -> act.safeStart(Intent(Settings.ACTION_DISPLAY_SETTINGS))
            "input" -> act.sheet.showInputs()
            "network" -> act.sheet.showWifi()
            "apps" -> act.safeStart(Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS))
            "timer" -> act.showSleepTimer()
            "screenshot" -> { hide(); postDelayed({ act.screenshot() }, 600) }
            "weather" -> { WeatherData.refresh(act, true); caption.text = "Погода обновляется…" }
            "update" -> { hide(); act.openSettings("update") }
            "bt_devices" -> act.safeStart(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            "accessibility" -> act.safeStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            "widget" -> { hide(); act.widgetPicker() }
            "drop" -> { hide(); act.openSettings("drop") }
            "lock" -> { hide(); android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ Lock.lockNow(act) }, 250) }
        }
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
            .setDuration(220).setInterpolator(DecelerateInterpolator(2f)).withLayer().start()
        wifiB.requestFocus()
    }

    fun hide() {
        if (!open) return
        if (movingId != null) { movingId = null; rebuildTiles() }
        closing = true
        act.overlayClosing(this, false)
        panel.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).translationY(-act.dp(10f)).setDuration(150).withLayer().start()
        scrim.animate().alpha(0f).setDuration(160).withEndAction {
            visibility = GONE; closing = false
            act.overlayClosed(this)
        }.start()
    }
}

class TileDef(val id: String, val name: String, val icon: Int)

/** Все плитки, которые можно добавить в Пункт управления. */
object CcTiles {
    const val MAX = 9
    val all = listOf(
        TileDef("wallpaper", "Обои", R.drawable.ic_image),
        TileDef("sky_settings", "Настройки Sky OS", R.drawable.ic_gear),
        TileDef("lock", "Заблокировать экран", R.drawable.ic_lock),
        TileDef("tv_settings", "Настройки телевизора", R.drawable.ic_tune),
        TileDef("display", "Экран и изображение", R.drawable.ic_tv),
        TileDef("input", "Источник сигнала (HDMI)", R.drawable.ic_input),
        TileDef("network", "Выбор сети Wi‑Fi", R.drawable.ic_wifi),
        TileDef("timer", "Таймер сна", R.drawable.ic_timer),
        TileDef("screenshot", "Снимок экрана", R.drawable.ic_crop),
        TileDef("apps", "Управление приложениями", R.drawable.ic_apps),
        TileDef("weather", "Обновить погоду", R.drawable.ic_weather),
        TileDef("update", "Обновление ПО", R.drawable.ic_refresh),
        TileDef("bt_devices", "Устройства Bluetooth", R.drawable.ic_bt),
        TileDef("accessibility", "Спец. возможности", R.drawable.ic_info),
        TileDef("widget", "Добавить виджет", R.drawable.ic_add),
        TileDef("drop", "Sky Drop — принять с iPhone", R.drawable.ic_input),
    )
    fun get(id: String) = all.firstOrNull { it.id == id }
}
