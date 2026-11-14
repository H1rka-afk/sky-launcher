package com.sky.launcher

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

class SheetItem(val label: String, val icon: Int = 0, val destructive: Boolean = false, val action: () -> Unit)

/** Всплывающие меню в стиле iOS (действия, обои, источники). */
class Sheet(private val act: MainActivity) : FrameLayout(act) {
    private val scrim = View(act)
    private var card: View? = null
    private var closing = false

    val open get() = visibility == VISIBLE && !closing

    init {
        visibility = GONE
        isClickable = true
        scrim.setBackgroundColor(0x80000000.toInt())
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun present(v: View, lp: LayoutParams, focus: View?) {
        card?.let { removeView(it) }
        card = v
        addView(v, lp)
        closing = false
        visibility = VISIBLE
        act.overlayOpened(this)
        scrim.alpha = 0f; scrim.animate().alpha(1f).setDuration(200).start()
        v.alpha = 0f; v.translationY = act.dp(40f)
        v.animate().alpha(1f).translationY(0f).setDuration(280).setInterpolator(DecelerateInterpolator(2f)).start()
        (focus ?: v).requestFocus()
    }

    fun hide(then: (() -> Unit)? = null) {
        if (!open) return
        closing = true
        card?.animate()?.alpha(0f)?.translationY(act.dp(30f))?.setDuration(180)?.start()
        scrim.animate().alpha(0f).setDuration(200).withEndAction {
            visibility = GONE
            card?.let { removeView(it) }; card = null
            closing = false
            act.overlayClosed(this)
            then?.invoke()
        }.start()
    }

    private fun box(padH: Int, padV: Int) = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false; clipToPadding = false
        background = Ui.round(act, 30f, 0xEB1C1C1E.toInt(), 0x24FFFFFF)
        elevation = act.dp(24f)
        setPadding(act.dpi(padH), act.dpi(padV), act.dpi(padH), act.dpi(padV))
    }

    fun showList(title: String, items: List<SheetItem>) {
        val b = box(10, 16)
        val t = Ui.text(act, 14f, 0x99FFFFFF.toInt(), Ui.medium, false).apply { text = title; gravity = Gravity.CENTER }
        b.addView(t, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = act.dpi(10) })
        var first: View? = null
        val all = items + SheetItem("Отмена") {}
        all.forEachIndexed { i, item ->
            val cancel = i == all.size - 1
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = if (cancel) Gravity.CENTER else Gravity.CENTER_VERTICAL
                isFocusable = true; isClickable = true
                setPadding(act.dpi(18), 0, act.dpi(18), 0)
            }
            if (item.icon != 0) {
                row.addView(ImageView(act).apply {
                    setImageResource(item.icon); setColorFilter(if (item.destructive) Ui.RED else Color.WHITE)
                }, LinearLayout.LayoutParams(act.dpi(22), act.dpi(22)).apply { marginEnd = act.dpi(14) })
            }
            row.addView(Ui.text(act, 17f,
                when { item.destructive -> Ui.RED; cancel -> Ui.BLUE; else -> Color.WHITE },
                if (cancel) Ui.medium else Ui.regular, false).apply { text = item.label })
            row.setOnFocusChangeListener { v, f -> v.background = if (f) Ui.round(act, 16f, 0x33FFFFFF, 0) else null }
            row.setOnClickListener { hide { item.action() } }
            b.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, act.dpi(54))
                .apply { if (cancel) topMargin = act.dpi(6) })
            if (first == null) first = row
        }
        present(b, LayoutParams(act.dpi(440), LayoutParams.WRAP_CONTENT, Gravity.CENTER), first)
    }

    fun showWallpapers() {
        val b = box(28, 22)
        b.addView(Ui.text(act, 22f, Color.WHITE, Ui.medium, false).apply { text = "Обои" })
        b.addView(Ui.text(act, 14f, 0x99FFFFFF.toInt(), Ui.regular, false).apply { text = "Живые обои Sky OS — плавно двигаются" },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = act.dpi(4); bottomMargin = act.dpi(16) })
        val cur = act.repo.wallpaper
        var focus: View? = null
        var row: LinearLayout? = null
        Wallpapers.all.forEachIndexed { i, w ->
            if (i % 3 == 0) {
                row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
                b.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { if (i > 0) topMargin = act.dpi(14) })
            }
            val cell = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                isFocusable = true; isClickable = true; clipChildren = false
            }
            val frame = FrameLayout(act).apply {
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, act.dp(16f)) }
                }
                clipToOutline = true
                elevation = act.dp(4f)
            }
            frame.addView(WallpaperView(act, animated = false).apply { wallpaper = w },
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            if (i == cur) {
                frame.addView(ImageView(act).apply {
                    setImageResource(R.drawable.ic_check); setColorFilter(Color.WHITE)
                    background = Ui.oval(act, Ui.BLUE, Color.WHITE, 2f)
                    setPadding(act.dpi(4), act.dpi(4), act.dpi(4), act.dpi(4))
                }, LayoutParams(act.dpi(28), act.dpi(28), Gravity.TOP or Gravity.END).apply { topMargin = act.dpi(8); marginEnd = act.dpi(8) })
            }
            cell.addView(frame, LinearLayout.LayoutParams(act.dpi(216), act.dpi(122)))
            val name = Ui.text(act, 14f, 0xCCFFFFFF.toInt(), Ui.regular, false).apply { text = w.name }
            cell.addView(name, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = act.dpi(8) })
            Ui.zoomOnFocus(cell, 1.07f) { f ->
                frame.foreground = if (f) Ui.round(act, 16f, 0, Color.WHITE, 3f) else null
                name.setTextColor(if (f) Color.WHITE else 0xCCFFFFFF.toInt())
            }
            cell.setOnClickListener { act.setWallpaper(i); hide() }
            row!!.addView(cell, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { if (i % 3 > 0) marginStart = act.dpi(18) })
            if (i == cur) focus = cell
        }
        present(b, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER), focus)
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
}
