package com.sky.launcher

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : Activity() {

    companion object { var splashShown = false }

    lateinit var repo: AppRepository
    lateinit var sheet: Sheet
    private lateinit var root: FrameLayout
    private lateinit var wallpaper: WallpaperView
    private lateinit var home: LinearLayout
    private lateinit var status: StatusBar
    private lateinit var clockW: ClockWidget
    private lateinit var calW: CalendarWidget
    private lateinit var devW: DeviceWidget
    private lateinit var grid: RecyclerView
    private lateinit var dock: LinearLayout
    private lateinit var cc: ControlCenter

    private val adapter = AppsAdapter(::launch, ::appMenu)
    private val handler = Handler(Looper.getMainLooper())
    private val focusStack = ArrayList<View?>()
    private var dockPkgs: List<String> = emptyList()
    private var appCount = 0

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private val tick = object : Runnable {
        override fun run() {
            status.update(); clockW.update(); calW.update(); devW.update(appCount)
            handler.postDelayed(this, 15_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = AppRepository(this)

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        wallpaper = WallpaperView(this).apply { wallpaper = Wallpapers.get(repo.wallpaper) }
        root.addView(wallpaper, FrameLayout.LayoutParams(MATCH, MATCH))

        home = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false; clipToPadding = false
            setPadding(dpi(48), dpi(16), dpi(48), dpi(16))
        }

        status = StatusBar(this) { openControlCenter() }
        home.addView(status, LinearLayout.LayoutParams(MATCH, dpi(38)))

        val widgets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        clockW = ClockWidget(this); calW = CalendarWidget(this); devW = DeviceWidget(this)
        val wh = dpi(116)
        widgets.addView(clockW, LinearLayout.LayoutParams(dpi(300), wh))
        widgets.addView(calW, LinearLayout.LayoutParams(wh, wh).apply { marginStart = dpi(16) })
        widgets.addView(devW, LinearLayout.LayoutParams(dpi(260), wh).apply { marginStart = dpi(16) })
        home.addView(widgets, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dpi(6) })

        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(context, 8)
            adapter = this@MainActivity.adapter
            clipToPadding = false
            setPadding(0, dpi(14), 0, dpi(14))
            itemAnimator = null
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        home.addView(grid, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dpi(4) })

        dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            clipChildren = false; clipToPadding = false
            background = Ui.round(context, 34f, 0x3DFFFFFF, 0x33FFFFFF)
            setPadding(dpi(18), dpi(12), dpi(18), dpi(12))
        }
        val dockWrap = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        dockWrap.addView(dock, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_HORIZONTAL))
        home.addView(dockWrap, LinearLayout.LayoutParams(MATCH, WRAP))

        root.addView(home, FrameLayout.LayoutParams(MATCH, MATCH))
        cc = ControlCenter(this); root.addView(cc, FrameLayout.LayoutParams(MATCH, MATCH))
        sheet = Sheet(this); root.addView(sheet, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)

        if (!splashShown && SystemClock.elapsedRealtime() < 15 * 60_000L) showSplash()
        splashShown = true
    }

    override fun onResume() {
        super.onResume()
        reload()
        wallpaper.start()
        handler.removeCallbacks(tick); handler.post(tick)
        if (cc.open) cc.refresh()
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
        // Эффект возврата на главный экран, как в iOS
        home.alpha = 0f; home.scaleX = 1.06f; home.scaleY = 1.06f
        home.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(340)
            .setInterpolator(DecelerateInterpolator(1.8f)).start()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (sheet.open) { sheet.hide(); return }
        if (cc.open) { cc.hide(); return }
        grid.scrollToPosition(0)
        grid.post { firstFocus()?.requestFocus() }
    }

    // ---------- Приложения ----------

    private fun reload() {
        val all = repo.load()
        appCount = all.size
        val dockApps = repo.dockApps(all)
        val newDock = dockApps.map { it.pkg }
        val gridApps = all.filter { it.pkg !in newDock }
        val changed = gridApps.map { it.pkg } != adapter.items.map { it.pkg } || newDock != dockPkgs
        if (!changed) return
        dockPkgs = newDock
        adapter.items = gridApps
        adapter.notifyDataSetChanged()

        dock.removeAllViews()
        dockApps.forEachIndexed { i, a ->
            val t = AppTileView(this, 60, false)
            t.bind(a)
            t.setOnClickListener { launch(it, a) }
            t.setOnLongClickListener { appMenu(a); true }
            dock.addView(t, LinearLayout.LayoutParams(WRAP, WRAP).apply { if (i > 0) marginStart = dpi(22) })
        }
        dock.visibility = if (dockApps.isEmpty()) View.GONE else View.VISIBLE
        devW.update(appCount)
        if (!cc.open && !sheet.open) grid.post { if (currentFocus == null || !home.hasFocus()) firstFocus()?.requestFocus() }
    }

    private fun firstFocus(): View? = grid.getChildAt(0) ?: dock.getChildAt(0) ?: status.pill

    private fun launch(v: View, a: AppEntry) {
        try {
            val opts = ActivityOptions.makeScaleUpAnimation(v, 0, 0, v.width, v.height).toBundle()
            startActivity(Intent(a.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), opts)
        } catch (e: Exception) {
            toast("Не удалось открыть «${a.label}»")
        }
    }

    private fun appMenu(a: AppEntry) {
        val inDock = a.pkg in dockPkgs
        val items = arrayListOf(
            SheetItem(if (inDock) "Убрать из Dock" else "Добавить в Dock", R.drawable.ic_dock) { toggleDock(a) }
        )
        if (a.special == null) {
            items += SheetItem("О приложении", R.drawable.ic_info) {
                safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}")))
            }
            items += SheetItem("Удалить приложение", R.drawable.ic_trash, destructive = true) {
                safeStart(Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.pkg}")))
            }
        }
        sheet.showList(a.label, items)
    }

    private fun toggleDock(a: AppEntry) {
        val l = dockPkgs.toMutableList()
        if (a.pkg in l) l.remove(a.pkg)
        else {
            if (l.size >= 7) { toast("В Dock помещается до 7 приложений"); return }
            val idx = if (l.lastOrNull() == AppRepository.SETTINGS) l.size - 1 else l.size
            l.add(idx, a.pkg)
        }
        repo.dock = l
        reload()
    }

    // ---------- Оверлеи ----------

    fun openControlCenter() { if (!cc.open && !sheet.open) cc.show() }

    fun overlayOpened(overlay: View) {
        focusStack.add(currentFocus)
        if (overlay === sheet && cc.visibility == View.VISIBLE) cc.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        home.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        home.animate().alpha(0.07f).scaleX(0.94f).scaleY(0.94f).setDuration(260).start()
    }

    fun overlayClosed(overlay: View) {
        if (overlay === sheet && cc.visibility == View.VISIBLE) {
            cc.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        } else if (cc.visibility != View.VISIBLE && sheet.visibility != View.VISIBLE) {
            home.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            home.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).start()
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                if (sheet.open) sheet.hide() else if (cc.open) cc.hide()
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                if (sheet.open) sheet.hide() else if (cc.open) cc.hide() else openControlCenter()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------- Заставка ----------

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

    // ---------- Утилиты ----------

    fun safeStart(i: Intent) {
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (e2: Exception) { toast("Недоступно на этом телевизоре") }
        }
    }

    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
