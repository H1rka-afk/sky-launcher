package com.sky.launcher

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Outline
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView

class SheetItem(val label: String, val icon: Int = 0, val destructive: Boolean = false, val action: () -> Unit)

/** Всплывающие меню в стиле iOS: действия, ввод текста, обои, Wi‑Fi, источники. */
class Sheet(private val act: MainActivity) : FrameLayout(act) {
    private val scrim = View(act)
    private var card: View? = null
    private var closing = false
    private val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    private val MATCH = LinearLayout.LayoutParams.MATCH_PARENT

    val open get() = visibility == VISIBLE && !closing

    init {
        visibility = GONE
        isClickable = true
        scrim.setBackgroundColor(0x80000000.toInt())
        addView(scrim, LayoutParams(MATCH, MATCH))
    }

    private fun present(v: View, lp: LayoutParams, focus: View?) {
        val wasOpen = visibility == VISIBLE
        card?.let { removeView(it) }
        card = v
        addView(v, lp)
        closing = false
        visibility = VISIBLE
        if (!wasOpen) {
            act.overlayOpened(this)
            scrim.alpha = 0f; scrim.animate().alpha(1f).setDuration(170).start()
        }
        v.alpha = 0f; v.translationY = act.dp(30f)
        v.animate().alpha(1f).translationY(0f).setDuration(200).setInterpolator(DecelerateInterpolator(2f)).withLayer().start()
        (focus ?: v).requestFocus()
    }

    fun hide(then: (() -> Unit)? = null) {
        if (!open) return
        closing = true
        hideKeyboard()
        act.overlayClosing(this, then != null)
        card?.animate()?.alpha(0f)?.translationY(act.dp(20f))?.setDuration(150)?.withLayer()?.start()
        scrim.animate().alpha(0f).setDuration(160).withEndAction {
            visibility = GONE
            card?.let { removeView(it) }; card = null
            closing = false
            act.overlayClosed(this)
            then?.invoke()
        }.start()
    }

    private fun hideKeyboard() {
        val imm = act.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(windowToken, 0)
    }

    private fun box(padH: Int, padV: Int) = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false; clipToPadding = false
        background = Ui.round(act, 30f, 0xEB1C1C1E.toInt(), 0x24FFFFFF)
        elevation = 0f
        setPadding(act.dpi(padH), act.dpi(padV), act.dpi(padH), act.dpi(padV))
    }

    private fun row(label: String, icon: Int, color: Int, center: Boolean, bold: Boolean) = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = if (center) Gravity.CENTER else Gravity.CENTER_VERTICAL
        isFocusable = true; isClickable = true
        setPadding(act.dpi(18), 0, act.dpi(18), 0)
        if (icon != 0) addView(ImageView(act).apply { setImageResource(icon); setColorFilter(color) },
            LinearLayout.LayoutParams(act.dpi(18), act.dpi(18)).apply { marginEnd = act.dpi(12) })
        addView(Ui.text(act, 15f, color, if (bold) Ui.medium else Ui.regular, false).apply {
            text = label; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        setOnFocusChangeListener { v, f -> v.background = if (f) Ui.round(act, 16f, 0x33FFFFFF, 0) else null }
    }

    fun showList(title: String, items: List<SheetItem>) {
        val b = box(10, 16)
        b.addView(Ui.text(act, 12f, 0x99FFFFFF.toInt(), Ui.medium, false).apply { text = title; gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = act.dpi(10); marginStart = act.dpi(12); marginEnd = act.dpi(12) })
        val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        var first: View? = null
        items.forEach { item ->
            val r = row(item.label, item.icon, if (item.destructive) Ui.RED else Color.WHITE, false, false)
            r.setOnClickListener { hide { item.action() } }
            list.addView(r, LinearLayout.LayoutParams(MATCH, act.dpi(44)))
            if (first == null) first = r
        }
        if (items.size > 6) {
            val sv = ScrollView(act).apply { isVerticalScrollBarEnabled = false; isFillViewport = true }
            sv.addView(list)
            b.addView(sv, LinearLayout.LayoutParams(MATCH, act.dpi(44 * 6)))
        } else b.addView(list)
        val cancel = row("Отмена", 0, Ui.BLUE, true, true)
        cancel.setOnClickListener { hide() }
        b.addView(cancel, LinearLayout.LayoutParams(MATCH, act.dpi(44)).apply { topMargin = act.dpi(4) })
        present(b, LayoutParams(act.dpi(380), WRAP, Gravity.CENTER), first ?: cancel)
    }

    /** Ввод текста (название, заметка, пароль, город). */
    fun showInput(title: String, initial: String, hint: String, password: Boolean = false, onDone: (String) -> Unit) {
        val b = box(22, 20)
        b.addView(Ui.text(act, 18f, Color.WHITE, Ui.medium, false).apply { text = title })
        val et = EditText(act).apply {
            setText(initial); setSelection(initial.length)
            this.hint = hint
            setTextColor(Color.WHITE); setHintTextColor(0x80FFFFFF.toInt())
            textSize = 18f
            background = Ui.round(act, 14f, 0x2EFFFFFF, 0x33FFFFFF)
            setPadding(act.dpi(16), act.dpi(12), act.dpi(16), act.dpi(12))
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
            inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setOnFocusChangeListener { _, f -> background = Ui.round(act, 14f, if (f) 0x40FFFFFF else 0x2EFFFFFF, if (f) Color.WHITE else 0x33FFFFFF, if (f) 2f else 1f) }
        }
        b.addView(et, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = act.dpi(14) })
        b.addView(Ui.text(act, 13f, 0x80FFFFFF.toInt(), Ui.regular, false).apply { text = "Нажмите OK на поле, чтобы открыть клавиатуру" },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = act.dpi(8) })
        val submit = { val v = et.text.toString(); hide { onDone(v) } }
        et.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { submit(); true } else false }
        val buttons = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        val cancel = row("Отмена", 0, Color.WHITE, true, false).apply { setOnClickListener { hide() } }
        val ok = row("Готово", 0, Ui.BLUE, true, true).apply { setOnClickListener { submit() } }
        buttons.addView(cancel, LinearLayout.LayoutParams(0, act.dpi(52), 1f))
        buttons.addView(ok, LinearLayout.LayoutParams(0, act.dpi(52), 1f).apply { marginStart = act.dpi(10) })
        b.addView(buttons, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = act.dpi(14) })
        present(b, LayoutParams(act.dpi(500), WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = act.dpi(50) }, et)
    }

    fun showWallpapers() {
        val b = box(28, 22)
        b.addView(Ui.text(act, 22f, Color.WHITE, Ui.medium, false).apply { text = "Обои" })
        b.addView(Ui.text(act, 14f, 0x99FFFFFF.toInt(), Ui.regular, false).apply { text = "Живые обои двигаются · фото природы и космоса: Unsplash" },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = act.dpi(4); bottomMargin = act.dpi(8) })
        val content = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; setPadding(act.dpi(8), act.dpi(8), act.dpi(8), act.dpi(16)) }
        val cur = act.repo.wallpaper
        var focus: View? = null
        val cols = 4
        fun section(title: String, list: List<Pair<Int, Wallpaper>>) {
            if (list.isEmpty()) return
            content.addView(Ui.text(act, 15f, 0xCCFFFFFF.toInt(), Ui.medium, false).apply { text = title },
                LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = act.dpi(10); bottomMargin = act.dpi(10) })
            var row: LinearLayout? = null
            list.forEachIndexed { n, (i, w) ->
                if (n % cols == 0) {
                    row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
                    content.addView(row, LinearLayout.LayoutParams(WRAP, WRAP).apply { if (n > 0) topMargin = act.dpi(6) })
                }
                val cell = LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                    isFocusable = true; isClickable = true; clipChildren = false
                }
                val frame = FrameLayout(act).apply {
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, act.dp(12f)) }
                    }
                    clipToOutline = true
                }
                frame.addView(WallpaperView(act, animated = false).apply { wallpaper = w }, LayoutParams(MATCH, MATCH))
                if (i == cur) {
                    frame.addView(ImageView(act).apply {
                        setImageResource(R.drawable.ic_check); setColorFilter(Color.WHITE)
                        background = Ui.oval(act, Ui.BLUE, Color.WHITE, 2f)
                        setPadding(act.dpi(4), act.dpi(4), act.dpi(4), act.dpi(4))
                    }, LayoutParams(act.dpi(26), act.dpi(26), Gravity.TOP or Gravity.END).apply { topMargin = act.dpi(7); marginEnd = act.dpi(7) })
                }
                // рамка выбора — снаружи картинки с зазором, как в iOS, видна на любых обоях
                val ring = FrameLayout(act).apply { setPadding(act.dpi(4), act.dpi(4), act.dpi(4), act.dpi(4)) }
                ring.addView(frame, LayoutParams(act.dpi(192), act.dpi(108)))
                cell.addView(ring, LinearLayout.LayoutParams(WRAP, WRAP))
                val name = Ui.text(act, 13f, 0xCCFFFFFF.toInt(), Ui.regular, false).apply { text = w.name }
                cell.addView(name, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = act.dpi(4) })
                cell.setOnFocusChangeListener { _, f ->
                    ring.background = if (f) Ui.round(act, 16f, 0, Color.WHITE, 3f) else null
                    name.setTextColor(if (f) Color.WHITE else 0xCCFFFFFF.toInt())
                }
                cell.setOnClickListener { act.setWallpaper(i); hide() }
                row!!.addView(cell, LinearLayout.LayoutParams(WRAP, WRAP).apply { if (n % cols > 0) marginStart = act.dpi(8) })
                if (i == cur) focus = cell
            }
        }
        val indexed = Wallpapers.all.withIndex().map { it.index to it.value }
        section("Живые", indexed.filter { it.second.photo == null })
        section("Природа", indexed.filter { it.second.group == "nature" })
        section("Космос", indexed.filter { it.second.group == "space" })
        val sv = ScrollView(act).apply { isVerticalScrollBarEnabled = false; clipChildren = false; clipToPadding = false }
        sv.addView(content)
        b.addView(sv, LinearLayout.LayoutParams(WRAP, act.dpi(400)))
        present(b, LayoutParams(WRAP, WRAP, Gravity.CENTER), focus)
    }

    /** Открытая папка как в iOS: название сверху и сетка приложений на «стекле». */
    fun showFolder(folder: HomeItem) {
        val b = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false; clipToPadding = false
        }
        b.addView(Ui.text(act, 24f, Color.WHITE, Ui.medium).apply { text = folder.name ?: "Папка" },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = act.dpi(22) })
        val panel = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
            background = Ui.round(act, 40f, 0x33FFFFFF, 0x33FFFFFF)
            setPadding(act.dpi(26), act.dpi(26), act.dpi(26), act.dpi(18))
        }
        val cols = 5
        var row: LinearLayout? = null
        var first: View? = null
        folder.children.forEachIndexed { i, pkg ->
            val a = act.model.apps[pkg] ?: return@forEachIndexed
            if (i % cols == 0) {
                row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
                panel.addView(row, LinearLayout.LayoutParams(WRAP, WRAP).apply { if (i > 0) topMargin = act.dpi(10) })
            }
            val t = AppTileView(act, 54, true)
            t.bind(a, act.model.label(pkg))
            t.setOnClickListener { v -> hide { act.launchPkg(pkg) } }
            t.setOnLongClickListener { hide { act.folderAppMenu(folder, pkg) }; true }
            row!!.addView(t, LinearLayout.LayoutParams(act.dpi(96), WRAP))
            if (first == null) first = t
        }
        b.addView(panel)
        b.addView(Ui.text(act, 13f, 0x99FFFFFF.toInt(), Ui.regular).apply { text = "Удерживайте OK на приложении — убрать из папки" },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = act.dpi(16) })
        present(b, LayoutParams(WRAP, WRAP, Gravity.CENTER), first)
    }

    fun showInputs() {
        val items = ArrayList<SheetItem>()
        try {
            val tim = act.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
            tim?.tvInputList?.forEach { info ->
                val label = info.loadLabel(act).toString()
                val uri = if (info.isPassthroughInput) TvContract.buildChannelUriForPassthroughInput(info.id)
                          else TvContract.buildChannelsUriForInput(info.id)
                items += SheetItem(label, if (info.isPassthroughInput) R.drawable.ic_input else R.drawable.ic_tv) {
                    try { act.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    catch (e: Exception) { act.toast("Не удалось открыть «$label»") }
                }
            }
        } catch (e: Exception) {}
        if (items.isEmpty()) items += SheetItem("Источники не найдены — открыть настройки", R.drawable.ic_gear) {
            act.safeStart(Intent(Settings.ACTION_SETTINGS))
        }
        showList("Источник сигнала", items)
    }

    /** Список сетей Wi‑Fi (удержание OK на значке Wi‑Fi в Пункте управления). */
    fun showWifi() {
        if (act.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            act.pendingWifi = true
            act.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), MainActivity.REQ_WIFI)
            return
        }
        if (!Sys.wifiOn(act)) Sys.setWifi(act, true)
        val nets = Sys.wifiScan(act)
        val cur = Sys.currentSsid(act)
        val settings = SheetItem("Настройки Wi‑Fi", R.drawable.ic_gear) { act.safeStart(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        if (nets.isEmpty()) {
            showList("Сети не найдены", listOf(
                SheetItem("Обновить список", R.drawable.ic_refresh) { postDelayed({ showWifi() }, 2500) },
                SheetItem("Включить «Местоположение» (нужно для поиска сетей)", R.drawable.ic_location) {
                    act.safeStart(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                },
                settings
            ))
            return
        }
        val items = nets.take(15).map { n ->
            SheetItem((if (n.ssid == cur) "✓  " else "") + n.ssid + "   " + bars(n.level),
                if (n.secure) R.drawable.ic_lock else R.drawable.ic_wifi) { connect(n) }
        } + SheetItem("Обновить список", R.drawable.ic_refresh) { postDelayed({ showWifi() }, 2500) } + settings
        showList(if (cur != null) "Wi‑Fi · подключено к «$cur»" else "Выберите сеть Wi‑Fi", items)
    }

    private fun bars(level: Int) = when {
        level >= -55 -> "▂▄▆█"; level >= -67 -> "▂▄▆"; level >= -78 -> "▂▄"; else -> "▂"
    }

    private fun connect(n: Sys.WifiNet) {
        fun go(pwd: String?) {
            if (Sys.connectWifi(act, n, pwd)) act.toast("Подключение к «${n.ssid}»…")
            else { act.toast("Не удалось подключиться — открываю настройки Wi‑Fi"); act.safeStart(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        }
        when {
            !n.secure -> go(null)
            Sys.isKnownWifi(act, n.ssid) -> go(null)
            else -> showInput("Пароль для «${n.ssid}»", "", "Пароль Wi‑Fi", password = true) { go(it) }
        }
    }
}
