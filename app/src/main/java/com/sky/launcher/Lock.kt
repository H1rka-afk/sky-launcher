package com.sky.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.CycleInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.security.MessageDigest

/** Состояние блокировки и код-пароль. */
object Lock {
    var locked = false

    fun hasPin() = Prefs.pinHash != null

    fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("skyos:$pin".toByteArray()).joinToString("") { "%02x".format(it) }

    fun check(pin: String) = hash(pin) == Prefs.pinHash

    fun setPin(pin: String?) { Prefs.pinHash = pin?.let { hash(it) } }

    fun lockNow(ctx: Context) {
        Prefs.init(ctx)
        locked = true
        if (LockActivity.visible) return
        ctx.startActivity(Intent(ctx, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Экран блокировки в стиле iOS: дата, большие часы, погода, уведомления. */
open class LockActivity : Activity() {

    companion object {
        var visible = false
        /** Режимы для настроек: "set" — задать новый код, "verify" — проверить текущий. */
        const val EXTRA_MODE = "mode"
    }

    private var mode = ""
    private var firstPin: String? = null

    private lateinit var wallpaper: WallpaperView
    private lateinit var main: LinearLayout
    private lateinit var time: TextView
    private lateinit var date: TextView
    private lateinit var notes: LinearLayout
    private lateinit var hint: LinearLayout
    private lateinit var pad: LinearLayout
    private lateinit var padTitle: TextView
    private lateinit var dots: LinearLayout
    private val entered = StringBuilder()
    private var fails = 0
    private var blockedUntil = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val M = ViewGroup.LayoutParams.MATCH_PARENT
    private val W = ViewGroup.LayoutParams.WRAP_CONTENT

    private val tick = object : Runnable {
        override fun run() {
            time.text = now("H:mm")
            date.text = now("EEEE, d MMMM").replaceFirstChar { it.uppercase() }
            handler.postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L + 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Wallpapers.load(this)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        wallpaper = WallpaperView(this).apply { wallpaper = Wallpapers.get(AppRepository(this@LockActivity).wallpaper) }
        root.addView(wallpaper, FrameLayout.LayoutParams(M, M))
        root.addView(View(this).apply { setBackgroundColor(0x40000000) }, FrameLayout.LayoutParams(M, M))

        // --- Основной вид ---
        main = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        // место под островок с замком
        main.addView(View(this), LinearLayout.LayoutParams(1, dpi(40)))
        date = Ui.text(this, 17f, Color.WHITE, Ui.medium)
        main.addView(date, LinearLayout.LayoutParams(W, W).apply { topMargin = dpi(14) })
        time = Ui.text(this, 96f, Color.WHITE, Typeface.create("sans-serif-medium", Typeface.NORMAL)).apply { letterSpacing = -0.02f }
        main.addView(time, LinearLayout.LayoutParams(W, W).apply { topMargin = -dpi(4) })
        main.addView(weatherLine(), LinearLayout.LayoutParams(W, W).apply { topMargin = dpi(2) })
        main.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        notes = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        main.addView(notes, LinearLayout.LayoutParams(dpi(440), W))
        hint = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        hint.addView(Ui.text(this, 14f, Color.WHITE, Ui.medium).apply { text = "Нажмите OK, чтобы открыть" })
        main.addView(hint, LinearLayout.LayoutParams(W, W).apply { topMargin = dpi(18); bottomMargin = dpi(30) })
        root.addView(main, FrameLayout.LayoutParams(M, M))

        // --- Ввод код-пароля (как в iOS: стеклянные круглые кнопки с буквами) ---
        pad = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; visibility = View.GONE
            setBackgroundColor(0x8C000000.toInt())
        }
        padTitle = Ui.text(this, 18f, Color.WHITE, Ui.medium)
        padTitle.gravity = Gravity.CENTER
        pad.addView(padTitle, LinearLayout.LayoutParams(W, W))
        dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        pad.addView(dots, LinearLayout.LayoutParams(W, W).apply { topMargin = dpi(14); bottomMargin = dpi(22) })
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "cancel", "0", "del")
        val letters = mapOf("2" to "A B C", "3" to "D E F", "4" to "G H I", "5" to "J K L", "6" to "M N O",
            "7" to "P Q R S", "8" to "T U V", "9" to "W X Y Z")
        val key = dpi(60)
        var first: View? = null
        for (r in 0 until 4) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            for (c in 0 until 3) {
                val k = keys[r * 3 + c]
                val b: View
                if (k == "cancel" || k == "del") {
                    // текстовые кнопки снизу, как «Отмена»/«Удалить» в iOS
                    val tv = Ui.text(this, 15f, Color.WHITE, Ui.regular, false).apply {
                        text = if (k == "del") "Удалить" else "Отмена"; gravity = Gravity.CENTER
                        isFocusable = true; isClickable = true
                    }
                    tv.setOnFocusChangeListener { _, f ->
                        tv.background = if (f) Ui.round(this, 30f, 0x33FFFFFF, 0) else null
                    }
                    tv.setOnClickListener { if (k == "del") backspace() else cancelPad() }
                    b = tv
                } else {
                    val box = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                        background = Ui.oval(this@LockActivity, 0x38FFFFFF)
                        isFocusable = true; isClickable = true
                    }
                    val num = Ui.text(this, 28f, Color.WHITE, Ui.light, false).apply { text = k; gravity = Gravity.CENTER }
                    box.addView(num, LinearLayout.LayoutParams(W, W))
                    val sub = letters[k]?.let { l ->
                        Ui.text(this, 8f, Color.WHITE, Ui.bold, false).apply { text = l; letterSpacing = 0.1f }
                            .also { box.addView(it, LinearLayout.LayoutParams(W, W).apply { topMargin = -dpi(1) }) }
                    }
                    box.setOnFocusChangeListener { _, f ->
                        box.background = Ui.oval(this, if (f) 0xF2FFFFFF.toInt() else 0x38FFFFFF)
                        val col = if (f) Color.BLACK else Color.WHITE
                        num.setTextColor(col); sub?.setTextColor(col)
                    }
                    box.setOnClickListener { digit(k) }
                    if (k == "5") first = box
                    b = box
                }
                row.addView(b, LinearLayout.LayoutParams(key, key).apply { if (c > 0) marginStart = dpi(24) })
            }
            pad.addView(row, LinearLayout.LayoutParams(W, W).apply { if (r > 0) topMargin = dpi(14) })
        }
        pad.addView(Ui.text(this, 12f, 0x99FFFFFF.toInt(), Ui.regular, false).apply { text = "Цифры можно вводить кнопками пульта" },
            LinearLayout.LayoutParams(W, W).apply { topMargin = dpi(22) })
        padFirst = first
        root.addView(pad, FrameLayout.LayoutParams(M, M))
        // Dynamic Island с замком (только на экране блокировки)
        island = LockIsland(this)
        root.addView(island, FrameLayout.LayoutParams(W, W, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dpi(10) })
        setContentView(root)
        root.isFocusable = true

        mode = intent.getStringExtra(EXTRA_MODE) ?: ""
        if (mode.isNotEmpty()) {
            main.visibility = View.GONE
            pad.visibility = View.VISIBLE
            padTitle.text = if (mode == "set") "Новый код-пароль" else "Введите текущий код-пароль"
            updateDots()
            pad.post { padFirst?.requestFocus() }
            island?.visibility = View.GONE
        } else island?.appear()
    }

    private var padFirst: View? = null
    private var island: LockIsland? = null

    private fun weatherLine(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val d = WeatherData.data ?: return row
        row.addView(WeatherIconView(this).apply { set(d.code, d.day) }, LinearLayout.LayoutParams(dpi(18), dpi(18)))
        row.addView(Ui.text(this, 15f, Color.WHITE, Ui.medium).apply {
            text = "${d.temp}°  ${WeatherData.desc(d.code)} · ${WeatherData.city(this@LockActivity)}"
        }, LinearLayout.LayoutParams(W, W).apply { marginStart = dpi(10) })
        return row
    }

    private fun renderNotes() {
        notes.removeAllViews()
        val items = ArrayList<Notify.Item>()
        if (!Lock.hasPin() && Prefs.pinReminder) items += Notify.Item(packageName, "Sky OS", "Установите код-пароль",
            "Защитите Sky OS паролем: Настройки → Код-пароль", IconFactory.logo(dpi(34)), System.currentTimeMillis())
        items += Notify.history.take(3)
        items.take(3).forEachIndexed { i, it ->
            notes.addView(Notify.card(this, it, translucent = true), LinearLayout.LayoutParams(M, W).apply { if (i > 0) topMargin = dpi(10) })
        }
    }

    override fun onResume() {
        super.onResume()
        if (mode.isEmpty()) { visible = true; Lock.locked = true }
        handler.post(tick)
        wallpaper.start()
        renderNotes()
        Notify.onHistory = { renderNotes() }
        hint.animate().cancel(); hint.alpha = 1f
    }

    override fun onPause() {
        super.onPause()
        visible = false
        handler.removeCallbacks(tick)
        wallpaper.stop()
        Notify.onHistory = null
    }

    // ---------- Ввод ----------

    private fun showPad() {
        entered.clear(); updateDots()
        padTitle.text = "Введите код-пароль"
        main.animate().alpha(0f).setDuration(160).withEndAction { main.visibility = View.INVISIBLE }.start()
        pad.visibility = View.VISIBLE; pad.alpha = 0f
        pad.animate().alpha(1f).setDuration(200).start()
        padFirst?.requestFocus()
    }

    private fun cancelPad() {
        if (mode.isNotEmpty()) { setResult(RESULT_CANCELED); finish() }
        else if (entered.isNotEmpty()) { entered.clear(); updateDots() }
        else hidePad()
    }

    private fun hidePad() {
        pad.animate().alpha(0f).setDuration(150).withEndAction { pad.visibility = View.GONE }.start()
        main.visibility = View.VISIBLE; main.animate().alpha(1f).setDuration(200).start()
    }

    private fun updateDots() {
        dots.removeAllViews()
        for (i in 0 until 4) dots.addView(View(this).apply {
            background = Ui.oval(this@LockActivity, if (i < entered.length) Color.WHITE else 0, Color.WHITE, 1.5f)
        }, LinearLayout.LayoutParams(dpi(13), dpi(13)).apply { if (i > 0) marginStart = dpi(22) })
    }

    private fun digit(d: String) {
        val now = System.currentTimeMillis()
        if (now < blockedUntil) { padTitle.text = "Попробуйте через ${(blockedUntil - now) / 1000 + 1} с"; return }
        if (entered.length >= 4) return
        entered.append(d); updateDots()
        if (entered.length == 4) handler.postDelayed({ verify() }, 120)
    }

    private fun backspace() { if (entered.isNotEmpty()) { entered.setLength(entered.length - 1); updateDots() } }

    private fun shake() {
        dots.animate().translationX(dp(18f)).setInterpolator(CycleInterpolator(3f)).setDuration(400)
            .withEndAction { dots.translationX = 0f; updateDots() }.start()
    }

    private fun verify() {
        val pin = entered.toString()
        if (mode == "set") {
            entered.clear()
            val f = firstPin
            when {
                f == null -> { firstPin = pin; padTitle.text = "Повторите код-пароль"; updateDots() }
                f == pin -> { Lock.setPin(pin); setResult(RESULT_OK); finish() }
                else -> { firstPin = null; padTitle.text = "Коды не совпали. Новый код-пароль"; shake() }
            }
            return
        }
        if (mode == "verify") {
            entered.clear()
            if (Lock.check(pin)) { setResult(RESULT_OK); finish() } else { padTitle.text = "Неверный код-пароль"; shake() }
            return
        }
        if (Lock.check(entered.toString())) { unlock(); return }
        fails++
        entered.clear()
        island?.shake()
        dots.animate().translationX(dp(18f)).setInterpolator(CycleInterpolator(3f)).setDuration(400)
            .withEndAction { dots.translationX = 0f; updateDots() }.start()
        if (fails >= 5) { blockedUntil = System.currentTimeMillis() + 30_000; fails = 0; padTitle.text = "Слишком много попыток — подождите 30 с" }
        else padTitle.text = "Неверный код-пароль"
    }

    private var unlocking = false

    private fun unlock() {
        if (unlocking) return
        unlocking = true
        Lock.locked = false
        val target = if (pad.visibility == View.VISIBLE) pad else main
        // как на iPhone: содержимое уезжает вверх, замок в островке открывается, островок исчезает
        target.animate().translationY(-dp(120f)).alpha(0f).setDuration(320).setInterpolator(DecelerateInterpolator()).start()
        val isl = island
        if (isl != null && isl.visibility == View.VISIBLE) isl.unlock { close() } else target.postDelayed({ close() }, 320)
    }

    private fun close() {
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, android.R.anim.fade_out)
        // напоминание о код-пароле — не чаще раза в 12 часов
        if (!Lock.hasPin() && Prefs.pinReminder) {
            val p = getSharedPreferences("skyos_settings", Context.MODE_PRIVATE)
            val last = p.getLong("pin_remind_ts", 0)
            if (System.currentTimeMillis() - last > 12 * 3600_000L) {
                p.edit().putLong("pin_remind_ts", System.currentTimeMillis()).apply()
                Handler(Looper.getMainLooper()).postDelayed({
                    Notify.post(applicationContext, "Установите код-пароль",
                        "Откройте Настройки → Код-пароль, чтобы защитить Sky OS", keep = false)
                }, 900)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val padOn = pad.visibility == View.VISIBLE
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            if (mode.isEmpty() && !Lock.hasPin()) { unlock(); return true }
            if (!padOn) showPad()
            digit((keyCode - KeyEvent.KEYCODE_0).toString()); return true
        }
        if (keyCode == KeyEvent.KEYCODE_DEL && padOn) { backspace(); return true }
        if ((keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) && mode.isNotEmpty()) {
            setResult(RESULT_CANCELED); finish(); return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (padOn) hidePad() else if (!Lock.hasPin()) unlock()
            return true
        }
        if (!padOn && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
                keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
            if (Lock.hasPin()) showPad() else unlock()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

/** Ввод код-пароля из настроек (обычное окно, чтобы вернуть результат). */
class PinActivity : LockActivity()
