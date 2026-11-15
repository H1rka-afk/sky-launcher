package com.sky.launcher

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.transition.ChangeBounds
import android.transition.TransitionManager
import android.view.FocusFinder
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        var splashShown = false
        const val REQ_WIFI = 42
    }

    lateinit var repo: AppRepository
    lateinit var model: HomeModel
    lateinit var sheet: Sheet
    var pendingWifi = false

    private lateinit var root: FrameLayout
    private lateinit var wallpaper: WallpaperView
    private lateinit var home: LinearLayout
    private lateinit var status: StatusBar
    private lateinit var pageHost: FrameLayout
    private var pageView: PageView? = null
    private lateinit var dots: LinearLayout
    private lateinit var dock: LinearLayout
    private lateinit var cc: ControlCenter
    private lateinit var hint: TextView

    var currentPage = 0
        private set
    private var appsSig = ""
    private var moving: HomeItem? = null
    private var moveBackup: List<Pair<HomeItem, IntArray>> = emptyList()
    private val wiggles = ArrayList<ValueAnimator>()

    private val handler = Handler(Looper.getMainLooper())
    private val focusStack = ArrayList<View?>()

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private val tick = object : Runnable {
        override fun run() {
            status.update(); updateWidgets()
            WeatherData.refresh(this@MainActivity)
            // следующий раз — ровно в начале минуты (меньше лишней работы)
            handler.postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L + 200L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        repo = AppRepository(this)
        model = HomeModel.get(this)
        Wallpapers.load(this)
        WeatherData.onUpdate = { updateWidgets() }

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        wallpaper = WallpaperView(this).apply { wallpaper = Wallpapers.get(repo.wallpaper) }
        root.addView(wallpaper, FrameLayout.LayoutParams(MATCH, MATCH))

        home = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false; clipToPadding = false
            setPadding(dpi(40), dpi(14), dpi(40), dpi(12))
        }
        status = StatusBar(this) { openControlCenter() }
        home.addView(status, LinearLayout.LayoutParams(MATCH, dpi(38)))

        pageHost = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        home.addView(pageHost, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dpi(4) })

        dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        home.addView(dots, LinearLayout.LayoutParams(MATCH, dpi(16)).apply { topMargin = dpi(2) })

        dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            clipChildren = false; clipToPadding = false
            background = Ui.round(context, 34f, 0x3DFFFFFF, 0x33FFFFFF)
            setPadding(dpi(18), dpi(12), dpi(18), dpi(12))
        }
        val dockWrap = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        dockWrap.addView(dock, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_HORIZONTAL))
        home.addView(dockWrap, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dpi(4) })
        root.addView(home, FrameLayout.LayoutParams(MATCH, MATCH))
        // Главный экран хранится как готовая картинка в видеопамяти: когда двигаются обои,
        // телевизору не нужно каждый кадр заново рисовать все иконки и виджеты.
        home.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        hint = Ui.text(this, 15f, Color.WHITE, Ui.medium, false).apply {
            background = Ui.round(context, 20f, 0xCC1C1C1E.toInt(), 0x33FFFFFF)
            setPadding(dpi(20), dpi(10), dpi(20), dpi(10))
            visibility = View.GONE
            elevation = 0f
        }
        root.addView(hint, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dpi(12) })

        cc = ControlCenter(this); root.addView(cc, FrameLayout.LayoutParams(MATCH, MATCH))
        sheet = Sheet(this); root.addView(sheet, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)

        if (!splashShown && SystemClock.elapsedRealtime() < 15 * 60_000L) showSplash()
        splashShown = true

        // Установка/удаление приложений — обновляем список
        registerReceiver(pkgReceiver, android.content.IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED); addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        })
    }

    private val pkgReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: Intent) {
            AppRepository.dirty = true
            if (hasWindowFocus() || !isFinishing) handler.postDelayed({ reloadApps() }, 500)
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(pkgReceiver) } catch (e: Exception) {}
        super.onDestroy()
    }

    private fun reloadApps(force: Boolean = false) {
        val all = repo.load()
        val sig = all.joinToString("|") { it.pkg + ":" + it.label }
        if (force || sig != appsSig || pageView == null) {
            appsSig = sig
            model.sync(all)
            renderAll()
        }
    }

    override fun onResume() {
        super.onResume()
        WeatherData.onUpdate = { updateWidgets() }
        Updater.onChange = { updateBadges() }
        if (SettingsActivity.changed) {
            SettingsActivity.changed = false
            IconFactory.clear()
            if (Wallpapers.get(repo.wallpaper) !== wallpaper.wallpaper) wallpaper.wallpaper = Wallpapers.get(repo.wallpaper)
            cc.rebuildTiles()
            reloadApps(force = true)
        } else reloadApps()
        wallpaper.start()
        handler.removeCallbacks(tick); handler.post(tick)
        if (cc.open) cc.refresh()
        Updater.autoCheck(this)
        updateBadges()
        Kiosk.enterIfOwner(this)
    }

    override fun onPause() {
        super.onPause()
        wallpaper.stop()
        handler.removeCallbacks(tick)
    }

    override fun onRestart() {
        super.onRestart()
        if (cc.open || sheet.open) return
        home.alpha = 0f
        home.animate().alpha(1f).setDuration(200).withLayer().start()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (moving != null) cancelMove()
        if (sheet.open) { sheet.hide(); return }
        if (cc.open) { cc.hide(); return }
        if (currentPage != 0) showPage(0, -1, 0, true)
        else pageView?.post { firstFocus()?.requestFocus() }
    }

    // ================= Отрисовка =================

    private fun renderAll() {
        currentPage = currentPage.coerceIn(0, model.pageCount - 1)
        val focusTag = currentFocus?.tag as? String
        renderDock()
        renderPage()
        renderDots()
        updateBadges()
        if (!cc.open && !sheet.open) home.post { restoreFocus(focusTag) }
    }

    private fun restoreFocus(tag: String?) {
        val v = tag?.let { pageView?.viewFor(it) ?: findInDock(it) }
        (v ?: firstFocus())?.requestFocus()
    }

    private fun findInDock(tag: String): View? =
        (0 until dock.childCount).map { dock.getChildAt(it) }.firstOrNull { it.tag == tag }

    private fun firstFocus(): View? = pageView?.getChildAt(0) ?: dock.getChildAt(0) ?: status.pill

    private fun buildPage(p: Int): PageView {
        val pv = PageView(this)
        for (item in model.onPage(p)) {
            val v: View = if (item.isFolder) {
                AppTileView(this).apply {
                    bindFolder(item.name ?: "Папка", item.children.mapNotNull { model.apps[it] })
                    setOnClickListener { sheet.showFolder(item) }
                    setOnLongClickListener { itemMenu(item); true }
                }
            } else if (item.isApp) {
                val a = model.apps[item.pkg] ?: continue
                AppTileView(this).apply {
                    bind(a, model.label(a.pkg))
                    setOnClickListener { launch(it, a) }
                    setOnLongClickListener { itemMenu(item); true }
                }
            } else {
                WidgetDefs.create(this, item).apply {
                    setOnClickListener { onClickWidget() }
                    setOnLongClickListener { itemMenu(item); true }
                }
            }
            v.tag = item.id
            pv.addView(v, PageView.LP(item))
        }
        return pv
    }

    private fun renderPage() {
        val pv = buildPage(currentPage)
        pageHost.removeAllViews()
        pageHost.addView(pv, FrameLayout.LayoutParams(MATCH, MATCH))
        pageView = pv
    }

    private fun renderDock() {
        dock.removeAllViews()
        model.dock.forEachIndexed { i, pkg ->
            val a = model.apps[pkg] ?: return@forEachIndexed
            val t = AppTileView(this, 60, false)
            t.bind(a)
            t.tag = "dock:$pkg"
            t.setOnClickListener { launch(it, a) }
            t.setOnLongClickListener { dockMenu(pkg); true }
            dock.addView(t, LinearLayout.LayoutParams(WRAP, WRAP).apply { if (i > 0) marginStart = dpi(22) })
        }
        dock.visibility = if (model.dock.isEmpty()) View.INVISIBLE else View.VISIBLE
    }

    private fun renderDots() {
        dots.removeAllViews()
        val n = maxOf(model.pageCount, currentPage + 1)
        if (n <= 1) return
        for (i in 0 until n) {
            dots.addView(View(this).apply {
                background = Ui.oval(context, if (i == currentPage) Color.WHITE else 0x66FFFFFF)
            }, LinearLayout.LayoutParams(dpi(7), dpi(7)).apply { marginStart = dpi(5); marginEnd = dpi(5) })
        }
    }

    private fun updateWidgets() {
        val pv = pageView ?: return
        for (i in 0 until pv.childCount) (pv.getChildAt(i) as? WidgetView)?.update()
    }

    /** Листание страниц: dir = 1 — вправо, -1 — влево. */
    private fun showPage(p: Int, dir: Int, row: Int?, fromLeft: Boolean) {
        val old = pageView
        currentPage = p
        val pv = buildPage(p)
        pageHost.clipChildren = true
        pageHost.addView(pv, FrameLayout.LayoutParams(MATCH, MATCH))
        pageView = pv
        val w = pageHost.width.toFloat().coerceAtLeast(1f)
        pv.translationX = dir * w
        pv.animate().translationX(0f).setDuration(300).setInterpolator(DecelerateInterpolator(2f)).withLayer()
            .withEndAction { pageHost.clipChildren = false }.start()
        old?.animate()?.translationX(-dir * w)?.setDuration(300)?.setInterpolator(DecelerateInterpolator(2f))?.withLayer()
            ?.withEndAction { pageHost.removeView(old) }?.start()
        renderDots()
        if (moving != null) { pv.post { highlightMove() }; return }
        pv.post {
            val kids = (0 until pv.childCount).map { pv.getChildAt(it) }
            val inRow = kids.filter { v ->
                val it = (v.layoutParams as PageView.LP).item
                row == null || row in it.y until it.y + it.h
            }
            val pick = if (fromLeft) inRow.minByOrNull { (it.layoutParams as PageView.LP).item.x }
                       else inRow.maxByOrNull { val i2 = (it.layoutParams as PageView.LP).item; i2.x + i2.w }
            (pick ?: kids.firstOrNull())?.requestFocus()
        }
    }

    // ================= Клавиши =================

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        if (moving != null && !sheet.open && !cc.open) {
            val handled = when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> true
                else -> false
            }
            if (handled) {
                if (e.action == KeyEvent.ACTION_DOWN) when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> moveBy(-1, 0)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> moveBy(1, 0)
                    KeyEvent.KEYCODE_DPAD_UP -> moveBy(0, -1)
                    KeyEvent.KEYCODE_DPAD_DOWN -> moveBy(0, 1)
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (e.repeatCount == 0) dropMove()
                    else -> cancelMove()
                }
                return true
            }
        }
        if (e.action == KeyEvent.ACTION_DOWN && !sheet.open && !cc.open &&
            (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            val pv = pageView; val f = currentFocus
            if (pv != null && f != null && f.parent === pv) {
                val right = code == KeyEvent.KEYCODE_DPAD_RIGHT
                if (FocusFinder.getInstance().findNextFocus(pv, f, if (right) View.FOCUS_RIGHT else View.FOCUS_LEFT) == null) {
                    val item = (f.layoutParams as PageView.LP).item
                    if (right && currentPage < model.pageCount - 1) showPage(currentPage + 1, 1, item.y, true)
                    else if (!right && currentPage > 0) showPage(currentPage - 1, -1, item.y, false)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(e)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                when {
                    sheet.open -> sheet.hide()
                    cc.open -> cc.hide()
                    currentPage > 0 -> showPage(0, -1, 0, true)
                }
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                if (sheet.open) sheet.hide() else if (cc.open) cc.hide() else openControlCenter()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ================= Перемещение («дрожащие» иконки как в iOS) =================

    fun startMove(item: HomeItem) {
        moving = item
        moveBackup = model.items.map { it to intArrayOf(it.page, it.x, it.y) }
        currentPage = item.page
        renderPage(); renderDots()
        pageView?.post { highlightMove() }
        hint.text = "◀ ▶ ▲ ▼  переместить     OK — готово     Назад — отмена"
        hint.visibility = View.VISIBLE
        hint.alpha = 0f; hint.animate().alpha(1f).setDuration(200).start()
    }

    private fun highlightMove() {
        val pv = pageView ?: return
        val m = moving ?: return
        stopWiggles()
        val over = model.overlapping(m).map { it.id }.toSet()
        var mv: View? = null
        for (i in 0 until pv.childCount) {
            val v = pv.getChildAt(i)
            val it = (v.layoutParams as PageView.LP).item
            if (it === m) { mv = v; continue }
            v.alpha = if (it.id in over) 0.3f else 0.9f
            v.animate().cancel(); v.scaleX = 1f; v.scaleY = 1f; v.translationZ = 0f
            v.foreground = null
            wiggle(v, 1.6f / it.w)
        }
        mv?.let {
            it.bringToFront()
            it.animate().cancel()
            it.alpha = 1f; it.scaleX = 1.08f; it.scaleY = 1.08f; it.translationZ = dp(24f)
            it.foreground = Ui.round(this, if (m.isApp) 20f else 26f, 0x1AFFFFFF, Color.WHITE, 3f)
            wiggle(it, 2.2f / m.w)
        }
    }

    private fun wiggle(v: View, deg: Float) {
        wiggles += ObjectAnimator.ofFloat(v, View.ROTATION, -deg, deg).apply {
            duration = (120 + Math.random() * 50).toLong()
            repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopWiggles() {
        wiggles.forEach { it.cancel() }; wiggles.clear()
        pageView?.let { pv -> for (i in 0 until pv.childCount) pv.getChildAt(i).rotation = 0f }
    }

    private fun moveBy(dx: Int, dy: Int) {
        val m = moving ?: return
        val ny = m.y + dy
        if (ny < 0 || ny + m.h > ROWS) return
        var nx = m.x + dx
        var np = m.page
        if (nx < 0) {
            if (np == 0) return
            np -= 1; nx = COLS - m.w
        } else if (nx + m.w > COLS) {
            val lastOther = model.items.filter { it !== m }.maxOfOrNull { it.page } ?: 0
            if (np > lastOther) return
            np += 1; nx = 0
        }
        m.x = nx; m.y = ny
        if (np != m.page) {
            val dir = if (np > m.page) 1 else -1
            m.page = np
            showPage(np, dir, null, true)
        } else {
            val pv = pageView ?: return
            TransitionManager.beginDelayedTransition(pv, ChangeBounds().setDuration(150))
            pv.requestLayout()
            highlightMove()
        }
    }

    private fun dropMove() {
        val m = moving ?: return
        val orig = moveBackup.firstOrNull { it.first === m }?.second
        val over = model.overlapping(m)
        var openFolder: HomeItem? = null
        if (m.isApp && over.size == 1 && over[0].isApp) {
            openFolder = model.makeFolder(over[0], m)            // приложение на приложение — новая папка
        } else if (m.isApp && over.size == 1 && over[0].isFolder) {
            model.addToFolder(over[0], m); openFolder = over[0]   // в существующую папку
        } else {
            for (o in over) model.place(o, m.page)
        }
        finishMove()
        if (openFolder != null) model.fillGaps()     // как в iOS: иконки сдвигаются, пустых мест нет
        model.compact(); model.save()
        currentPage = (openFolder ?: m).page
        val id = (openFolder ?: m).id
        renderAll()
        pageView?.post { pageView?.viewFor(id)?.requestFocus() }
        openFolder?.let { f -> handler.postDelayed({ sheet.showFolder(f) }, 350) }
    }

    private fun cancelMove() {
        moveBackup.forEach { (h, p) -> h.page = p[0]; h.x = p[1]; h.y = p[2] }
        val id = moving?.id
        finishMove()
        model.compact()
        renderAll()
        if (id != null) pageView?.post { pageView?.viewFor(id)?.requestFocus() }
    }

    private fun finishMove() {
        stopWiggles()
        moving = null
        hint.animate().alpha(0f).setDuration(150).withEndAction { hint.visibility = View.GONE }.start()
    }

    // ================= Меню элементов =================

    private fun itemMenu(item: HomeItem) {
        if (moving != null) return
        val items = ArrayList<SheetItem>()
        items += SheetItem("Переместить", R.drawable.ic_move) { startMove(item) }
        if (item.isFolder) {
            items += SheetItem("Открыть", R.drawable.ic_apps) { sheet.showFolder(item) }
            items += SheetItem("Переименовать папку", R.drawable.ic_text) {
                sheet.showInput("Название папки", item.name ?: "", "Папка") { v ->
                    item.name = v.trim().ifEmpty { "Папка" }; model.save(); renderAll()
                }
            }
            items += SheetItem("Распустить папку", R.drawable.ic_trash, destructive = true) {
                model.dissolve(item); model.compact(); model.save(); renderAll()
            }
            sheet.showList(item.name ?: "Папка", items)
            return
        }
        if (item.isApp) {
            val a = model.apps[item.pkg] ?: return
            items += SheetItem("Переименовать", R.drawable.ic_text) { rename(a.pkg) }
            items += SheetItem("Добавить в Dock", R.drawable.ic_dock) { addToDock(item) }
            items += SheetItem("Добавить виджет", R.drawable.ic_add) { widgetPicker() }
            appActions(a, items)
            sheet.showList(model.label(a.pkg), items)
        } else {
            val v = pageView?.viewFor(item.id) as? WidgetView
            v?.menuItems()?.let { items += it }
            items += SheetItem("Добавить виджет", R.drawable.ic_add) { widgetPicker() }
            items += SheetItem("Удалить виджет", R.drawable.ic_trash, destructive = true) {
                model.items.remove(item); model.compact(); model.save(); renderAll()
            }
            sheet.showList(WidgetDefs.get(item.kind).name, items)
        }
    }

    private fun dockMenu(pkg: String) {
        val a = model.apps[pkg] ?: return
        val items = arrayListOf(
            SheetItem("Убрать из Dock", R.drawable.ic_dock) { removeFromDock(pkg) },
            SheetItem("Переименовать", R.drawable.ic_text) { rename(pkg) },
        )
        appActions(a, items)
        sheet.showList(model.label(pkg), items)
    }

    private fun appActions(a: AppEntry, items: ArrayList<SheetItem>) {
        if (a.special != null) return
        items += SheetItem("О приложении", R.drawable.ic_info) {
            safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}")))
        }
        items += SheetItem("Удалить приложение", R.drawable.ic_trash, destructive = true) {
            safeStart(Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.pkg}")))
        }
    }

    private fun rename(pkg: String) {
        val original = model.apps[pkg]?.label ?: pkg
        sheet.showInput("Название приложения", model.label(pkg), original) { v ->
            val t = v.trim()
            if (t.isEmpty() || t == original) model.labels.remove(pkg) else model.labels[pkg] = t
            model.save(); renderAll()
        }
    }

    private fun addToDock(item: HomeItem) {
        val pkg = item.pkg ?: return
        if (model.dock.size >= HomeModel.DOCK_MAX) {
            toast("В Dock помещается не больше ${HomeModel.DOCK_MAX} приложений. Сначала уберите одно из Dock.")
            return
        }
        model.items.remove(item)
        val idx = if (model.dock.lastOrNull() == AppRepository.SETTINGS) model.dock.size - 1 else model.dock.size
        model.dock.add(idx, pkg)
        model.compact(); model.save(); renderAll()
    }

    private fun removeFromDock(pkg: String) {
        model.dock.remove(pkg)
        model.place(HomeItem(model.newId(), "app", pkg, 0, 0, 0, 1, 1), currentPage)
        model.save(); renderAll()
    }

    fun widgetPicker() {
        sheet.showList("Добавить виджет", WidgetDefs.all.map { d ->
            SheetItem("${d.name}  ·  ${d.w}×${d.h}", d.icon) { addWidget(d) }
        })
    }

    fun folderAppMenu(folder: HomeItem, pkg: String) {
        val a = model.apps[pkg] ?: return
        val items = arrayListOf(
            SheetItem("Убрать из папки", R.drawable.ic_move) {
                model.removeFromFolder(folder, pkg); renderAll()
            },
            SheetItem("Переименовать", R.drawable.ic_text) { rename(pkg) },
        )
        appActions(a, items)
        sheet.showList(model.label(pkg), items)
    }

    fun launchPkg(pkg: String) {
        val a = model.apps[pkg] ?: return
        if (a.special == "settings") { openSettings(); return }
        try { startActivity(Intent(a.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Exception) { toast("Не удалось открыть «${model.label(pkg)}»") }
    }

    fun openSettings(section: String? = null) {
        val i = Intent(this, SettingsActivity::class.java)
        if (section != null) i.putExtra(SettingsActivity.EXTRA_SECTION, section)
        startActivity(i)
    }

    /** Значок «1» на Настройках и баннер, когда вышло обновление. */
    private var bannerShownFor = -1
    private fun updateBadges() {
        val n = if (Updater.available != null) 1 else 0
        fun mark(v: View?) { (v as? AppTileView)?.setBadge(n) }
        model.items.firstOrNull { it.pkg == AppRepository.SETTINGS }?.let { mark(pageView?.viewFor(it.id)) }
        mark(findInDock("dock:" + AppRepository.SETTINGS))
        val a = Updater.available
        if (a != null && a.code != bannerShownFor && !cc.open && !sheet.open && moving == null) {
            bannerShownFor = a.code
            hint.text = "Доступно обновление Sky OS ${a.name} — Настройки → Обновление ПО"
            hint.visibility = View.VISIBLE; hint.alpha = 0f
            hint.animate().alpha(1f).setDuration(250).start()
            handler.postDelayed({ if (moving == null) hint.animate().alpha(0f).setDuration(300).withEndAction { hint.visibility = View.GONE }.start() }, 9000)
        }
    }

    fun resizeWidget(item: HomeItem, sizes: List<Pair<Int, Int>>) {
        sheet.showList("Размер виджета", sizes.map { (w, h) ->
            SheetItem((if (item.w == w && item.h == h) "✓  " else "") + "$w × $h", R.drawable.ic_move) {
                model.resize(item, w, h); renderAll()
            }
        })
    }

    // ---------- Таймер сна и снимок экрана ----------

    private var sleepAt = 0L
    private val sleepRunnable = Runnable { sleepAt = 0L; sleepNow() }

    fun timerCaption(): String {
        if (sleepAt == 0L) return "Таймер сна · выключен"
        val left = ((sleepAt - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0) + 1
        return "Таймер сна · осталось $left мин"
    }

    fun showSleepTimer() {
        val opts = listOf(0, 15, 30, 45, 60, 90, 120)
        sheet.showList(timerCaption(), opts.map { m ->
            SheetItem(if (m == 0) "Выключить таймер" else "$m минут", R.drawable.ic_timer) {
                handler.removeCallbacks(sleepRunnable)
                if (m == 0) { sleepAt = 0L; toast("Таймер сна выключен") }
                else {
                    sleepAt = System.currentTimeMillis() + m * 60_000L
                    handler.postDelayed(sleepRunnable, m * 60_000L)
                    toast("Телевизор уснёт через $m мин")
                }
            }
        })
    }

    fun sleepNow() {
        val svc = HomeInterceptService.instance
        if (svc != null && android.os.Build.VERSION.SDK_INT >= 28)
            svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        else toast("Для сна включите Sky OS в «Спец. возможностях»")
    }

    fun screenshot() {
        val svc = HomeInterceptService.instance
        if (svc != null && android.os.Build.VERSION.SDK_INT >= 28)
            svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
        else toast("Для снимка экрана включите Sky OS в «Спец. возможностях»")
    }

    fun addWidget(d: WidgetDefs.Def) {
        val item = HomeItem(model.newId(), d.kind, null, 0, 0, 0, d.w, d.h)
        model.place(item, currentPage)
        model.save()
        currentPage = item.page
        renderAll()
        startMove(item)
    }

    fun pickCity() {
        sheet.showInput("Город для погоды", WeatherData.city(this), "Например: Ростов-на-Дону") { q ->
            if (q.isBlank()) return@showInput
            toast("Ищу «$q»…")
            WeatherData.search(q) { res ->
                when {
                    res == null -> toast("Нет связи с сервером погоды")
                    res.isEmpty() -> toast("Город не найден")
                    else -> sheet.showList("Выберите город", res.map { p ->
                        SheetItem(p.title, R.drawable.ic_location) { WeatherData.setCity(this, p); updateWidgets() }
                    })
                }
            }
        }
    }

    // ================= Оверлеи =================

    fun openControlCenter() { if (!cc.open && !sheet.open && moving == null) cc.show() }

    fun overlayOpened(overlay: View) {
        focusStack.add(currentFocus)
        if (overlay === sheet && cc.visibility == View.VISIBLE) cc.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        home.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        dimHome(true)
    }

    /**
     * Главный экран за папкой / Пунктом управления: прячется и появляется мгновенно.
     * Плавное затемнение всего экрана слишком тяжело для процессора телевизора.
     */
    private fun dimHome(dim: Boolean) {
        if (dim) wallpaper.pauseMotion() else wallpaper.resumeMotion()
        home.animate().cancel()
        home.alpha = 1f; home.scaleX = 1f; home.scaleY = 1f
        home.visibility = if (dim) View.INVISIBLE else View.VISIBLE
    }

    /** Вызывается в начале закрытия: главный экран возвращается сразу, параллельно с анимацией. */
    fun overlayClosing(overlay: View, chained: Boolean) {
        val nothingLeft = if (overlay === sheet) cc.visibility != View.VISIBLE else sheet.visibility != View.VISIBLE
        if (nothingLeft && !chained) dimHome(false)
    }

    fun overlayClosed(overlay: View) {
        if (overlay === sheet && cc.visibility == View.VISIBLE) {
            cc.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        } else if (cc.visibility != View.VISIBLE && sheet.visibility != View.VISIBLE) {
            home.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            dimHome(false)   // в том же кадре: если следом откроется другое меню, мигания не будет
        }
        val f = if (focusStack.isEmpty()) null else focusStack.removeAt(focusStack.size - 1)
        (f?.takeIf { it.isAttachedToWindow && it.isShown } ?: firstFocus())?.requestFocus()
    }

    fun setWallpaper(i: Int) {
        repo.wallpaper = i
        wallpaper.alpha = 0.3f
        wallpaper.wallpaper = Wallpapers.get(i)
        wallpaper.animate().alpha(1f).setDuration(500).start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_WIFI && pendingWifi) {
            pendingWifi = false
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) sheet.showWifi()
            else safeStart(Intent(Settings.ACTION_WIFI_SETTINGS))
        }
    }

    // ================= Заставка =================

    private fun showSplash() {
        val s = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val size = dpi(104)
        box.addView(ImageView(this).apply { setImageBitmap(IconFactory.logo(size)) }, LinearLayout.LayoutParams(size, size))
        box.addView(Ui.text(this, 34f, Color.WHITE, Ui.light, false).apply { text = "Sky OS"; letterSpacing = 0.04f },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dpi(22) })
        s.addView(box, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        root.addView(s, FrameLayout.LayoutParams(MATCH, MATCH))
        box.alpha = 0f; box.scaleX = 0.9f; box.scaleY = 0.9f
        box.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(200).setDuration(700).withEndAction {
            s.animate().alpha(0f).setStartDelay(900).setDuration(550).withEndAction { root.removeView(s) }.start()
            home.scaleX = 1.08f; home.scaleY = 1.08f
            home.animate().scaleX(1f).scaleY(1f).setStartDelay(900).setDuration(750)
                .setInterpolator(DecelerateInterpolator(1.6f)).start()
        }.start()
    }

    // ================= Утилиты =================

    private fun launch(v: View, a: AppEntry) {
        if (a.special == "settings") { openSettings(); return }
        try {
            val opts = ActivityOptions.makeScaleUpAnimation(v, 0, 0, v.width, v.height).toBundle()
            startActivity(Intent(a.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), opts)
        } catch (e: Exception) {
            toast("Не удалось открыть «${model.label(a.pkg)}»")
        }
    }

    fun safeStart(i: Intent) {
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (e2: Exception) { toast("Недоступно на этом телевизоре") }
        }
    }

    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    /** Для скриншот‑теста. */
    fun testGoToPage(p: Int) = showPage(p, 1, 0, true)
    fun testMove(dx: Int, dy: Int) = moveBy(dx, dy)
    fun testFirstWidget(): HomeItem? = model.items.firstOrNull { !it.isApp }
}
