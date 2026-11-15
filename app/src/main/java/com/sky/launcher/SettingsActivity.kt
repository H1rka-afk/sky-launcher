package com.sky.launcher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date

/** Переключатель в стиле iOS. */
class IosSwitch(ctx: Context) : View(ctx) {
    var checked = false
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    override fun onDraw(c: Canvas) {
        val h = height.toFloat(); val w = width.toFloat()
        p.color = if (checked) Ui.GREEN else 0xFF39393D.toInt()
        r.set(0f, 0f, w, h); c.drawRoundRect(r, h / 2, h / 2, p)
        p.color = Color.WHITE
        val k = h * 0.42f
        c.drawCircle(if (checked) w - h / 2 else h / 2, h / 2, k, p)
    }
}

/** Настройки самой Sky OS (не телевизора), в стиле iPadOS. */
class SettingsActivity : Activity() {

    companion object {
        /** Ставится, когда что-то изменилось — главный экран перерисуется. */
        var changed = false
        const val EXTRA_SECTION = "section"
        private const val REQ_LOC = 7
    }

    private class Section(val id: String, val title: String, val icon: Int, val color: Int)

    private val sections = listOf(
        Section("about", "Об этой системе", R.drawable.ic_info, Ui.BLUE),
        Section("update", "Обновление ПО", R.drawable.ic_refresh, 0xFF8E8E93.toInt()),
        Section("passcode", "Код-пароль", R.drawable.ic_lock, Ui.RED),
        Section("notify", "Уведомления", R.drawable.ic_info, 0xFFFF3B30.toInt()),
        Section("wallpaper", "Обои", R.drawable.ic_image, 0xFF32ADE6.toInt()),
        Section("home", "Главный экран", R.drawable.ic_apps, Ui.PURPLE),
        Section("cc", "Пункт управления", R.drawable.ic_tune, 0xFF8E8E93.toInt()),
        Section("weather", "Погода", R.drawable.ic_weather, 0xFF0A84FF.toInt()),
        Section("wifi", "Wi‑Fi", R.drawable.ic_wifi, Ui.BLUE),
        Section("bluetooth", "Bluetooth", R.drawable.ic_bt, Ui.BLUE),
        Section("display", "Экран и анимации", R.drawable.ic_tv, 0xFF30B0C7.toInt()),
        Section("access", "Автозапуск и сон", R.drawable.ic_moon, Ui.GREEN),
        Section("tv", "Настройки телевизора", R.drawable.ic_gear, 0xFF636366.toInt()),
    )

    private lateinit var model: HomeModel
    private lateinit var repo: AppRepository
    private lateinit var sidebar: LinearLayout
    private lateinit var content: LinearLayout
    private val cache = HashMap<String, LinearLayout>()
    private val heavy = setOf("wallpaper", "cc", "home", "display")
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingShow: Runnable? = null
    private var rebuilding = false
    private var progressLabel: TextView? = null
    private var lastUpdState = ""

    private fun updState() = "${Updater.available?.code}|${Updater.checking}|${Updater.lastError}|${Updater.progress >= 0}"
    private fun progressText() = if (Updater.progress >= 0) "Загрузка… ${Updater.progress}%" else "Загрузить и установить"
    private lateinit var contentScroll: ScrollView
    private val sideRows = HashMap<String, View>()
    private var current = ""
    private val W = ViewGroup.LayoutParams.WRAP_CONTENT
    private val M = ViewGroup.LayoutParams.MATCH_PARENT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Wallpapers.load(this)
        repo = AppRepository(this)
        model = HomeModel.get(this)
        if (model.apps.isEmpty()) model.sync(repo.load())

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.BLACK)
            setPadding(dpi(36), dpi(24), dpi(36), 0)
        }
        // Боковое меню
        val sideScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipToPadding = false }
        sidebar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dpi(24)) }
        sideScroll.addView(sidebar)
        root.addView(sideScroll, LinearLayout.LayoutParams(dpi(330), M))
        // Содержимое
        contentScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipToPadding = false }
        content = newContent()
        contentScroll.addView(content)
        root.addView(contentScroll, LinearLayout.LayoutParams(0, M, 1f))
        setContentView(root)

        buildSidebar()
        val start = intent.getStringExtra(EXTRA_SECTION) ?: "about"
        show(start)
        sideRows[start]?.requestFocus()
        Updater.onChange = { updateSideBadge(); if (current == "update") refreshUpdate() }
        WeatherData.onUpdate = { if (current == "weather") rebuild() }
    }

    override fun onResume() {
        super.onResume()
        if (current == "access" || current == "wifi" || current == "bluetooth") rebuild()
    }

    override fun onDestroy() {
        Updater.onChange = null
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            val f = currentFocus
            if (f != null && isInContent(f)) { sideRows[current]?.requestFocus(); return true }
            finish(); return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun newContent() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(36), dpi(6), dpi(20), dpi(40)) }

    private fun isInContent(v: View): Boolean {
        var p: Any? = v.parent
        while (p is View) { if (p === contentScroll) return true; p = p.parent }
        return false
    }

    // ---------------- Боковое меню ----------------

    private fun iconBox(icon: Int, color: Int, size: Int = 30): View = FrameLayout(this).apply {
        background = Ui.round(this@SettingsActivity, size * 0.24f, color, 0)
        addView(ImageView(this@SettingsActivity).apply { setImageResource(icon); setColorFilter(Color.WHITE) },
            FrameLayout.LayoutParams(dpi(size * 6 / 10), dpi(size * 6 / 10), Gravity.CENTER))
    }

    private var updateBadge: TextView? = null

    private fun buildSidebar() {
        sidebar.addView(Ui.text(this, 32f, Color.WHITE, Ui.bold, false).apply { text = "Настройки" },
            LinearLayout.LayoutParams(W, W).apply { bottomMargin = dpi(16); marginStart = dpi(6) })
        // Карточка системы
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Ui.round(this@SettingsActivity, 14f, 0xFF1C1C1E.toInt(), 0)
            setPadding(dpi(14), dpi(12), dpi(14), dpi(12))
        }
        card.addView(ImageView(this).apply { setImageBitmap(IconFactory.logo(dpi(52))) }, LinearLayout.LayoutParams(dpi(52), dpi(52)))
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        t.addView(Ui.text(this, 19f, Color.WHITE, Ui.medium, false).apply { text = "Sky OS" })
        t.addView(Ui.text(this, 13f, 0xFF8E8E93.toInt(), Ui.regular, false).apply { text = "Версия ${Updater.currentName(this@SettingsActivity)} · ${Build.MODEL}" })
        card.addView(t, LinearLayout.LayoutParams(0, W, 1f).apply { marginStart = dpi(14) })
        sidebar.addView(card, LinearLayout.LayoutParams(M, W).apply { bottomMargin = dpi(18) })

        val groups = listOf(listOf("about", "update"), listOf("passcode", "notify"), listOf("wallpaper", "home", "cc", "weather"),
            listOf("wifi", "bluetooth", "display", "access"), listOf("tv"))
        for (g in groups) {
            val box = group(sidebar)
            g.forEachIndexed { i, id ->
                val s = sections.first { it.id == id }
                val row = row(box, i == g.size - 1)
                row.addView(iconBox(s.icon, s.color), LinearLayout.LayoutParams(dpi(30), dpi(30)))
                row.addView(Ui.text(this, 17f, Color.WHITE, Ui.regular, false).apply { text = s.title },
                    LinearLayout.LayoutParams(0, W, 1f).apply { marginStart = dpi(14) })
                if (id == "update") {
                    updateBadge = Ui.text(this, 13f, Color.WHITE, Ui.bold, false).apply {
                        text = "1"; gravity = Gravity.CENTER; background = Ui.oval(this@SettingsActivity, Ui.RED)
                    }
                    row.addView(updateBadge, LinearLayout.LayoutParams(dpi(22), dpi(22)).apply { marginEnd = dpi(8) })
                }
                row.addView(chevron())
                row.setOnFocusChangeListener { v, f ->
                    highlight(v, f, i == g.size - 1)
                    if (rebuilding) return@setOnFocusChangeListener
                    // раздел открывается, когда фокус задержался — быстрое листание меню не тормозит
                    pendingShow?.let { handler.removeCallbacks(it) }
                    if (f && id != "tv") { pendingShow = Runnable { show(id) }.also { handler.postDelayed(it, 160) } }
                }
                row.setOnClickListener {
                    if (id == "tv") safeStart(Intent(Settings.ACTION_SETTINGS))
                    else { show(id); content.post { firstFocusable(content)?.requestFocus() } }
                }
                sideRows[id] = row
            }
        }
        updateSideBadge()
    }

    private fun updateSideBadge() { updateBadge?.visibility = if (Updater.available != null) View.VISIBLE else View.GONE }

    private fun firstFocusable(g: ViewGroup): View? {
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i)
            if (c.isFocusable && c.visibility == View.VISIBLE) return c
            if (c is ViewGroup) firstFocusable(c)?.let { return it }
        }
        return null
    }

    // ---------------- Строительные блоки ----------------

    private fun group(parent: LinearLayout, title: String? = null): LinearLayout {
        if (title != null) parent.addView(Ui.text(this, 13f, 0xFF8E8E93.toInt(), Ui.medium, false).apply { text = title.uppercase() },
            LinearLayout.LayoutParams(W, W).apply { marginStart = dpi(16); bottomMargin = dpi(6); topMargin = dpi(4) })
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.round(this@SettingsActivity, 14f, 0xFF1C1C1E.toInt(), 0)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(14f)) }
            }
            clipToOutline = true
        }
        parent.addView(box, LinearLayout.LayoutParams(M, W).apply { bottomMargin = dpi(22) })
        return box
    }

    private fun row(box: LinearLayout, last: Boolean): LinearLayout {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            isFocusable = true; isClickable = true
            setPadding(dpi(14), 0, dpi(14), 0)
            minimumHeight = dpi(54)
        }
        if (box.childCount > 0) box.addView(View(this).apply { setBackgroundColor(0xFF38383A.toInt()) },
            LinearLayout.LayoutParams(M, 1).apply { marginStart = dpi(14) })
        box.addView(r, LinearLayout.LayoutParams(M, W))
        r.setOnFocusChangeListener { v, f -> highlight(v, f, last) }
        return r
    }

    private fun highlight(v: View, f: Boolean, last: Boolean) {
        v.setBackgroundColor(if (f) 0xFF3A3A3C.toInt() else Color.TRANSPARENT)
    }

    private fun chevron() = Ui.text(this, 20f, 0xFF5A5A5E.toInt(), Ui.regular, false).apply { text = "›" }

    private fun header(title: String, sub: String? = null) {
        content.addView(Ui.text(this, 28f, Color.WHITE, Ui.bold, false).apply { text = title },
            LinearLayout.LayoutParams(W, W).apply { bottomMargin = dpi(if (sub == null) 18 else 4) })
        if (sub != null) content.addView(Ui.text(this, 15f, 0xFF8E8E93.toInt(), Ui.regular, false).apply { text = sub },
            LinearLayout.LayoutParams(W, W).apply { bottomMargin = dpi(18) })
    }

    private fun footer(text: String) {
        content.addView(Ui.text(this, 13f, 0xFF8E8E93.toInt(), Ui.regular, false).apply { this.text = text; setLineSpacing(0f, 1.2f) },
            LinearLayout.LayoutParams(M, W).apply { topMargin = -dpi(14); bottomMargin = dpi(22); marginStart = dpi(16); marginEnd = dpi(16) })
    }

    /** Строка «название — значение» (+ действие). */
    private fun item(box: LinearLayout, title: String, value: String? = null, color: Int = Color.WHITE,
                     last: Boolean = false, arrow: Boolean = false, icon: Int = 0, onClick: (() -> Unit)? = null): LinearLayout {
        val r = row(box, last)
        if (icon != 0) r.addView(ImageView(this).apply { setImageResource(icon); setColorFilter(color) },
            LinearLayout.LayoutParams(dpi(22), dpi(22)).apply { marginEnd = dpi(12) })
        r.addView(Ui.text(this, 17f, color, Ui.regular, false).apply { text = title; maxLines = 2 },
            LinearLayout.LayoutParams(0, W, 1f))
        if (value != null) r.addView(Ui.text(this, 16f, 0xFF8E8E93.toInt(), Ui.regular, false).apply {
            text = value; gravity = Gravity.END; maxLines = 2
        }, LinearLayout.LayoutParams(W, W).apply { marginStart = dpi(12) })
        if (arrow) r.addView(chevron(), LinearLayout.LayoutParams(W, W).apply { marginStart = dpi(10) })
        if (onClick != null) r.setOnClickListener { onClick() }
        return r
    }

    private fun toggle(box: LinearLayout, title: String, get: () -> Boolean, set: (Boolean) -> Unit) {
        val r = row(box, false)
        r.addView(Ui.text(this, 17f, Color.WHITE, Ui.regular, false).apply { text = title }, LinearLayout.LayoutParams(0, W, 1f))
        val sw = IosSwitch(this).apply { checked = get() }
        r.addView(sw, LinearLayout.LayoutParams(dpi(52), dpi(32)))
        r.setOnClickListener { val v = !get(); set(v); sw.checked = v; changed = true; cache.keys.retainAll(setOf(current)) }
    }

    // ---------------- Разделы ----------------

    private fun rebuild() {
        val f = currentFocus
        val inContent = f != null && isInContent(f)
        val focusIdx = if (inContent) focusIndex(f!!) else -1
        val scroll = contentScroll.scrollY
        rebuilding = true
        show(current, force = true)
        contentScroll.scrollTo(0, scroll)
        if (inContent) {
            val list = focusables(content)
            (list.getOrNull(focusIdx) ?: list.lastOrNull())?.requestFocus()
        }
        rebuilding = false
    }

    private fun focusables(g: ViewGroup, out: ArrayList<View> = ArrayList()): ArrayList<View> {
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i)
            if (c.isFocusable) out += c else if (c is ViewGroup) focusables(c, out)
        }
        return out
    }

    private fun focusIndex(v: View) = focusables(content).indexOf(v)

    private fun show(id: String, force: Boolean = false) {
        if (id == current && !force) return
        current = id
        val cached = if (!force && id in heavy) cache[id] else null
        content = cached ?: newContent()
        contentScroll.removeAllViews()
        contentScroll.addView(content)
        if (!force) contentScroll.scrollTo(0, 0)
        if (cached != null) return
        if (id in heavy) cache[id] = content
        when (id) {
            "about" -> about()
            "passcode" -> passcode()
            "notify" -> notifications()
            "update" -> update()
            "wallpaper" -> wallpaper()
            "home" -> home()
            "cc" -> controlCenter()
            "weather" -> weather()
            "wifi" -> wifi()
            "bluetooth" -> bluetooth()
            "display" -> display()
            "access" -> access()
        }
    }

    private fun passcode() {
        header("Код-пароль", "Экран блокировки появляется при включении телевизора и после бездействия на главном экране.")
        val g = group(content)
        if (!Lock.hasPin()) {
            item(g, "Включить код-пароль", color = Ui.BLUE, last = true) { askNewPin() }
            footer("Пока код-пароль не задан, экран блокировки открывается кнопкой OK.")
        } else {
            item(g, "Сменить код-пароль", color = Ui.BLUE) { askPin("Введите текущий код-пароль") { askNewPin() } }
            item(g, "Выключить код-пароль", color = Ui.RED, last = true) {
                askPin("Введите текущий код-пароль") { Lock.setPin(null); toast("Код-пароль выключен"); rebuild() }
            }
            footer("Код вводится цифрами на пульте или кнопками на экране. После 5 ошибок — пауза 30 секунд.")
        }
        val g2 = group(content, "Экран блокировки")
        toggle(g2, "Блокировать при включении ТВ", { Prefs.lockOnWake }) { Prefs.lockOnWake = it }
        val opts = listOf(0, 5, 10, 15, 30, 60)
        fun name(m: Int) = if (m == 0) "Никогда" else "Через $m мин"
        item(g2, "Автоблокировка на главном экране", name(Prefs.lockIdleMin), arrow = true) {
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Автоблокировка")
                .setItems(opts.map { name(it) }.toTypedArray()) { _, w -> Prefs.lockIdleMin = opts[w]; rebuild() }.show()
        }
        toggle(g2, "Напоминать о код-пароле", { Prefs.pinReminder }) { Prefs.pinReminder = it }
        val g3 = group(content)
        item(g3, "Заблокировать сейчас", color = Ui.BLUE, last = true) { Lock.lockNow(this) }
        footer("Код-пароль защищает Sky OS. Системные настройки телевизора он не закрывает.")
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun askPin(title: String, ok: () -> Unit) {
        input(title, "", password = true, digits = true) { pin ->
            if (Lock.check(pin)) ok() else toast("Неверный код-пароль")
        }
    }

    private fun askNewPin() {
        input("Новый код-пароль (4 цифры)", "", password = true, digits = true) { a ->
            if (a.length != 4 || a.any { !it.isDigit() }) { toast("Нужно ровно 4 цифры"); return@input }
            input("Повторите код-пароль", "", password = true, digits = true) { b ->
                if (a == b) { Lock.setPin(a); toast("Код-пароль установлен"); rebuild() } else toast("Коды не совпадают")
            }
        }
    }

    private fun notifications() {
        header("Уведомления", "Баннеры в стиле iOS сверху экрана.")
        val g = group(content)
        toggle(g, "Уведомления приложений", { Prefs.appNotifications }) { Prefs.appNotifications = it }
        footer("Уведомления других приложений показываются, когда включена служба Sky OS (Настройки → Автозапуск и сон).")
        val g2 = group(content)
        item(g2, "Показать пример", color = Ui.BLUE, last = true) {
            Notify.post(this, "Привет от Sky OS", "Так выглядят уведомления — как на iPhone", keep = false)
        }
        if (Notify.history.isNotEmpty()) {
            val g3 = group(content, "Недавние")
            Notify.history.take(8).forEachIndexed { i, n ->
                item(g3, "${n.app}: ${n.title}", Notify.ago(n.time), last = i == minOf(8, Notify.history.size) - 1)
            }
            val g4 = group(content)
            item(g4, "Очистить", color = Ui.RED, last = true) { Notify.history.clear(); rebuild() }
        }
    }

    private fun about() {
        header("Об этой системе")
        val g = group(content)
        item(g, "Название", "Sky OS")
        item(g, "Версия", "${Updater.currentName(this)} (${Updater.currentCode(this)})")
        item(g, "Модель", "${Build.MANUFACTURER} ${Build.MODEL}")
        item(g, "Версия Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        val dm = resources.displayMetrics
        item(g, "Разрешение экрана", "${dm.widthPixels} × ${dm.heightPixels}", last = true)
        val g2 = group(content)
        val st = StatFs(Environment.getDataDirectory().path)
        item(g2, "Память", String.format(RU, "%.1f ГБ свободно из %.1f ГБ", st.availableBytes / 1e9, st.totalBytes / 1e9))
        item(g2, "Приложений", model.apps.size.toString())
        item(g2, "Сеть", when (Sys.net(this)) {
            Sys.Net.WIFI -> "Wi‑Fi" + (Sys.currentSsid(this)?.let { " · $it" } ?: "")
            Sys.Net.ETHERNET -> "Ethernet"; Sys.Net.NONE -> "Нет подключения"
        })
        item(g2, "IP‑адрес", ipAddress() ?: "—", last = true)
        val g3 = group(content)
        item(g3, "Перезапустить Sky OS", color = Ui.BLUE) { changed = true; android.os.Process.killProcess(android.os.Process.myPid()) }
        footer("Sky OS — главный экран для Android TV в стиле iOS. Фото обоев: Unsplash. Погода: Open‑Meteo / wttr.in.")
    }

    private fun ipAddress(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }?.hostAddress
    } catch (e: Exception) { null }

    // Экран обновления строится один раз; дальше меняются только надписи — фокус никуда не прыгает.
    private var updTitle: TextView? = null
    private var updSub: TextView? = null
    private var updNotes: TextView? = null
    private var updAction: TextView? = null
    private var updFooter: TextView? = null

    private fun update() {
        header("Обновление ПО")
        val g = group(content)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(16), dpi(16), dpi(16), dpi(16))
        }
        g.addView(card, LinearLayout.LayoutParams(M, W))
        card.addView(ImageView(this).apply { setImageBitmap(IconFactory.logo(dpi(60))) }, LinearLayout.LayoutParams(dpi(60), dpi(60)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        updTitle = Ui.text(this, 19f, Color.WHITE, Ui.medium, false)
        updSub = Ui.text(this, 14f, 0xFF8E8E93.toInt(), Ui.regular, false)
        updNotes = Ui.text(this, 15f, Color.WHITE, Ui.regular, false)
        col.addView(updTitle); col.addView(updSub)
        col.addView(updNotes, LinearLayout.LayoutParams(W, W).apply { topMargin = dpi(8) })
        card.addView(col, LinearLayout.LayoutParams(0, W, 1f).apply { marginStart = dpi(16) })
        val r = item(g, "", color = Ui.BLUE, last = true) {
            when {
                Updater.progress >= 0 || Updater.checking -> {}
                Updater.available != null -> Updater.downloadAndInstall(this)
                else -> Updater.check(this, manual = true)
            }
            refreshUpdate()
        }
        updAction = r.getChildAt(0) as? TextView
        updFooter = Ui.text(this, 13f, 0xFF8E8E93.toInt(), Ui.regular, false).apply { setLineSpacing(0f, 1.2f) }
        content.addView(updFooter, LinearLayout.LayoutParams(M, W).apply { topMargin = -dpi(14); bottomMargin = dpi(22); marginStart = dpi(16); marginEnd = dpi(16) })
        val g2 = group(content)
        toggle(g2, "Автоматически проверять обновления", { Prefs.autoUpdate }) { Prefs.autoUpdate = it }
        val last = Prefs.lastUpdateCheck
        footer("Проверка выполняется при включении и раз в 6 часов. Последняя проверка: " +
            (if (last == 0L) "ещё не было" else SimpleDateFormat("d MMMM, H:mm", RU).format(Date(last))))
        refreshUpdate()
    }

    private fun refreshUpdate() {
        val a = Updater.available
        val p = Updater.progress
        updTitle?.text = if (a != null) "Sky OS ${a.name}" else "Sky OS ${Updater.currentName(this)}"
        updSub?.text = when {
            Updater.checking -> "Проверка обновлений…"
            a != null -> "Доступно обновление"
            Updater.lastError != null -> "Не удалось проверить: ${Updater.lastError}"
            else -> "Установлена последняя версия"
        }
        updNotes?.text = a?.notes ?: ""
        updNotes?.visibility = if (a?.notes.isNullOrBlank()) View.GONE else View.VISIBLE
        updAction?.text = when {
            p >= 0 -> "Загрузка… $p%"
            Updater.checking -> "Проверка…"
            a != null -> "Загрузить и установить"
            else -> "Проверить обновления"
        }
        updFooter?.text = if (a != null) "После загрузки откроется окно установки Android — нажмите «Установить». " +
            "Если появится запрос, разрешите Sky OS устанавливать приложения." else ""
        updFooter?.visibility = if (a != null) View.VISIBLE else View.GONE
    }

    private fun wallpaper() {
        header("Обои", "Живые обои плавно двигаются. Фото природы — Unsplash.")
        val g = group(content)
        toggle(g, "Движение обоев", { Prefs.motion }) { Prefs.motion = it }
        footer("Выключите, если телевизор работает медленно — обои станут неподвижными.")
        val cur = repo.wallpaper
        var row: LinearLayout? = null
        Wallpapers.all.forEachIndexed { i, w ->
            if (i % 3 == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
                content.addView(row, LinearLayout.LayoutParams(W, W).apply { bottomMargin = dpi(16) })
            }
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                isFocusable = true; isClickable = true; clipChildren = false
            }
            val frame = FrameLayout(this).apply {
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(12f)) }
                }
                clipToOutline = true
            }
            frame.addView(WallpaperView(this, animated = false).apply { wallpaper = w }, FrameLayout.LayoutParams(M, M))
            if (i == cur) frame.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_check); setColorFilter(Color.WHITE)
                background = Ui.oval(this@SettingsActivity, Ui.BLUE, Color.WHITE, 2f)
                setPadding(dpi(4), dpi(4), dpi(4), dpi(4))
            }, FrameLayout.LayoutParams(dpi(24), dpi(24), Gravity.TOP or Gravity.END).apply { topMargin = dpi(6); marginEnd = dpi(6) })
            cell.addView(frame, LinearLayout.LayoutParams(dpi(146), dpi(82)))
            cell.addView(Ui.text(this, 13f, 0xCCFFFFFF.toInt(), Ui.regular, false).apply { text = w.name; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(W, W).apply { topMargin = dpi(6) })
            Ui.zoomOnFocus(cell, 1.06f) { f -> frame.foreground = if (f) Ui.round(this, 12f, 0, Color.WHITE, 3f) else null }
            cell.setOnClickListener { repo.wallpaper = i; changed = true; rebuild() }
            row!!.addView(cell, LinearLayout.LayoutParams(dpi(146), W).apply { if (i % 3 > 0) marginStart = dpi(14) })
        }
    }

    private fun home() {
        header("Главный экран")
        val g = group(content)
        toggle(g, "Подписи под иконками", { Prefs.labels }) { Prefs.labels = it }
        footer("Удерживайте OK на иконке → «Переместить». Наведите приложение на другое и нажмите OK — получится папка, как в iOS.")
        val g2 = group(content)
        item(g2, "Убрать пустые места между иконками", color = Ui.BLUE) {
            model.fillGaps(); model.save(); changed = true
            Toast.makeText(this, "Иконки переставлены", Toast.LENGTH_SHORT).show()
        }
        item(g2, "Сбросить раскладку главного экрана", color = Ui.RED, last = true) {
            confirm("Сбросить раскладку?", "Папки, расположение иконок и виджетов вернутся к исходным.") {
                model.resetLayout(); changed = true
                Toast.makeText(this, "Раскладка сброшена", Toast.LENGTH_SHORT).show()
            }
        }
        val g3 = group(content, "Dock")
        item(g3, "Приложений в Dock", "${model.dock.size} из ${HomeModel.DOCK_MAX}", last = true)
    }

    private fun controlCenter() {
        header("Пункт управления", "Wi‑Fi, Bluetooth, звук, сон и громкость есть всегда. Остальные кнопки можно выбрать.")
        val tiles = Prefs.ccTiles.toMutableList()
        val g = group(content, "Включено (${tiles.size} из ${CcTiles.MAX})")
        if (tiles.isEmpty()) item(g, "Нет кнопок", last = true)
        tiles.forEachIndexed { i, id ->
            val (name, icon) = tileInfo(id)
            item(g, name, icon = icon, last = i == tiles.size - 1, arrow = true) {
                val opts = arrayOf("Переместить выше", "Переместить ниже", "Убрать")
                AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(name).setItems(opts) { _, w ->
                    val l = Prefs.ccTiles.toMutableList()
                    val k = l.indexOf(id)
                    when (w) {
                        0 -> if (k > 0) { l.removeAt(k); l.add(k - 1, id) }
                        1 -> if (k < l.size - 1) { l.removeAt(k); l.add(k + 1, id) }
                        2 -> l.remove(id)
                    }
                    Prefs.ccTiles = l; changed = true; rebuild()
                }.show()
            }
        }
        footer("Первые две кнопки встают рядом с громкостью, остальные — рядами по четыре.")
        val g2 = group(content, "Добавить")
        val rest = CcTiles.all.filter { it.id !in tiles }
        rest.forEach { d ->
            item(g2, d.name, icon = d.icon) { addTile(d.id) }
        }
        item(g2, "Приложение…", color = Ui.BLUE, icon = R.drawable.ic_add, last = true) {
            val list = model.apps.values.filter { it.special == null }.sortedBy { model.label(it.pkg).lowercase() }
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Кнопка запуска приложения")
                .setItems(list.map { model.label(it.pkg) }.toTypedArray()) { _, w -> addTile("app:" + list[w].pkg) }.show()
        }
    }

    private fun tileInfo(id: String): Pair<String, Int> =
        if (id.startsWith("app:")) ("Приложение: " + model.label(id.removePrefix("app:"))) to R.drawable.ic_apps
        else (CcTiles.get(id)?.name ?: id) to (CcTiles.get(id)?.icon ?: R.drawable.ic_info)

    private fun addTile(id: String) {
        val l = Prefs.ccTiles.toMutableList()
        if (id in l) return
        if (l.size >= CcTiles.MAX) { Toast.makeText(this, "Не больше ${CcTiles.MAX} кнопок — сначала уберите одну", Toast.LENGTH_LONG).show(); return }
        l += id; Prefs.ccTiles = l; changed = true; rebuild()
    }

    private fun weather() {
        header("Погода")
        val d = WeatherData.data
        val g = group(content)
        item(g, "Город", WeatherData.city(this), arrow = true) { pickCity() }
        item(g, "Сейчас", if (d != null) "${d.temp}°, ${WeatherData.desc(d.code)}" else "нет данных")
        item(g, "Источник", if (d != null) WeatherData.source.ifBlank { "—" } else "—")
        item(g, "Обновлено", if (d != null) SimpleDateFormat("H:mm", RU).format(Date(d.time)) else "—", last = WeatherData.lastError == null)
        WeatherData.lastError?.let { item(g, "Ошибка", it, color = Ui.RED, last = true) }
        val g2 = group(content)
        item(g2, "Обновить сейчас", color = Ui.BLUE, last = true) { WeatherData.refresh(this, true); Toast.makeText(this, "Обновляю…", Toast.LENGTH_SHORT).show() }
        footer("Данные: Open‑Meteo, при недоступности — wttr.in. Обновляется каждые 30 минут.")
    }

    private fun pickCity() {
        input("Город для погоды", WeatherData.city(this)) { q ->
            if (q.isBlank()) return@input
            Toast.makeText(this, "Ищу «$q»…", Toast.LENGTH_SHORT).show()
            WeatherData.search(q) { res ->
                when {
                    res == null -> Toast.makeText(this, "Нет связи с сервером", Toast.LENGTH_LONG).show()
                    res.isEmpty() -> Toast.makeText(this, "Город не найден", Toast.LENGTH_LONG).show()
                    else -> AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Выберите город")
                        .setItems(res.map { it.title }.toTypedArray()) { _, w ->
                            WeatherData.setCity(this, res[w]); changed = true; rebuild()
                        }.show()
                }
            }
        }
    }

    private fun wifi() {
        header("Wi‑Fi")
        val g = group(content)
        toggle(g, "Wi‑Fi", { Sys.wifiOn(this) }) { if (!Sys.setWifi(this, it)) safeStart(Intent(Settings.ACTION_WIFI_SETTINGS)); content.postDelayed({ rebuild() }, 2000) }
        item(g, "Подключено к", Sys.currentSsid(this) ?: if (Sys.net(this) == Sys.Net.ETHERNET) "Ethernet" else "—", last = true)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            val g2 = group(content, "Сети")
            item(g2, "Показать доступные сети", color = Ui.BLUE, last = true) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOC)
            }
            footer("Android показывает список сетей только приложениям с доступом к местоположению.")
        } else if (Sys.wifiOn(this)) {
            val nets = Sys.wifiScan(this)
            val cur = Sys.currentSsid(this)
            val g2 = group(content, "Сети")
            if (nets.isEmpty()) item(g2, "Сети не найдены — включите «Местоположение»", color = Ui.BLUE, last = true) {
                safeStart(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            nets.take(15).forEachIndexed { i, n ->
                item(g2, (if (n.ssid == cur) "✓  " else "") + n.ssid, if (n.secure) "🔒" else null,
                    last = i == minOf(nets.size, 15) - 1) { connect(n) }
            }
            val g3 = group(content)
            item(g3, "Обновить список", color = Ui.BLUE) { content.postDelayed({ rebuild() }, 2500) }
        }
        val g4 = group(content)
        item(g4, "Настройки Wi‑Fi телевизора", arrow = true, last = true) { safeStart(Intent(Settings.ACTION_WIFI_SETTINGS)) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOC) rebuild()
    }

    private fun connect(n: Sys.WifiNet) {
        fun go(pwd: String?) {
            if (Sys.connectWifi(this, n, pwd)) Toast.makeText(this, "Подключение к «${n.ssid}»…", Toast.LENGTH_SHORT).show()
            else { Toast.makeText(this, "Не удалось — открываю настройки", Toast.LENGTH_LONG).show(); safeStart(Intent(Settings.ACTION_WIFI_SETTINGS)) }
            content.postDelayed({ rebuild() }, 4000)
        }
        when {
            !n.secure || Sys.isKnownWifi(this, n.ssid) -> go(null)
            else -> input("Пароль для «${n.ssid}»", "", password = true) { go(it) }
        }
    }

    private fun bluetooth() {
        header("Bluetooth")
        val g = group(content)
        toggle(g, "Bluetooth", { Sys.btOn(this) }) { on ->
            if (!on) confirm("Выключить Bluetooth?", "Если пульт подключён по Bluetooth, он перестанет работать.") {
                if (!Sys.setBt(this, false)) safeStart(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); content.postDelayed({ rebuild() }, 1500)
            } else { if (!Sys.setBt(this, true)) safeStart(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); content.postDelayed({ rebuild() }, 1500) }
        }
        item(g, "Устройства Bluetooth", arrow = true, last = true) { safeStart(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
    }

    private fun display() {
        header("Экран и анимации")
        val g = group(content)
        toggle(g, "Движение обоев", { Prefs.motion }) { Prefs.motion = it }
        toggle(g, "Подписи под иконками", { Prefs.labels }) { Prefs.labels = it }
        footer("Если интерфейс подтормаживает, выключите движение обоев — это самая заметная экономия.")
        val g2 = group(content)
        item(g2, "Изображение и звук телевизора", arrow = true, last = true) { safeStart(Intent(Settings.ACTION_DISPLAY_SETTINGS)) }
    }

    private fun access() {
        header("Автозапуск и сон")
        val on = HomeInterceptService.instance != null
        val g = group(content)
        item(g, "Служба Sky OS", if (on) "Включена" else "Выключена", color = Color.WHITE)
        item(g, if (on) "Открыть Спец. возможности" else "Включить в Спец. возможностях", color = Ui.BLUE, arrow = true, last = true) {
            safeStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        footer("Служба нужна, чтобы Sky OS открывалась при включении телевизора и по кнопке Home, а также для режима сна, таймера сна и снимка экрана.")
    }

    // ---------------- Диалоги ----------------

    private fun confirm(title: String, msg: String, ok: () -> Unit) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(title).setMessage(msg)
            .setPositiveButton("Да") { _, _ -> ok() }.setNegativeButton("Отмена", null).show()
    }

    private fun input(title: String, initial: String, password: Boolean = false, digits: Boolean = false, done: (String) -> Unit) {
        val et = EditText(this).apply {
            setText(initial); setSelection(initial.length); isSingleLine = true
            inputType = when {
                digits -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else -> InputType.TYPE_CLASS_TEXT
            }
            if (digits) filters = arrayOf(android.text.InputFilter.LengthFilter(4))
        }
        val box = FrameLayout(this).apply { setPadding(dpi(20), dpi(8), dpi(20), 0); addView(et) }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(title).setView(box)
            .setPositiveButton("Готово") { _, _ -> done(et.text.toString()) }.setNegativeButton("Отмена", null).show()
    }

    private fun safeStart(i: Intent) {
        try { startActivity(i) } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (e2: Exception) {}
        }
    }
}
