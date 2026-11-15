package com.sky.launcher

import android.animation.LayoutTransition
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * Dynamic Island: чёрная «пилюля» сверху по центру.
 *  • короткие события (громкость, уведомления, Wi‑Fi, Bluetooth, снимок) — раскрываются на пару секунд;
 *  • «живые» занятия (таймер сна, Sky Drop, загрузка обновления) — висят компактно на главном экране.
 * На главном экране рисуется в окне Sky OS, поверх других приложений — через службу Sky OS.
 */
object Island {

    /** Что показать. bar — полоска 0..1 (громкость), ring — кольцо 0..1, -1 — крутилка, null — нет. */
    class Content(
        val icon: Int? = null, val bmp: Bitmap? = null, val title: String, val sub: String? = null,
        val right: String? = null, val bar: Float? = null, val ring: Float? = null,
        val tint: Int = Color.WHITE, val expanded: Boolean = false
    )

    private val main = Handler(Looper.getMainLooper())
    private var flash: Content? = null
    private val live = LinkedHashMap<String, Content>()
    private val hideFlash = Runnable { flash = null; render() }

    private var pill: Pill? = null
    private var pillHost: Any? = null          // FrameLayout активности или WindowManager службы
    private var overlayWrap: FrameLayout? = null

    val enabled get() = Prefs.island

    /** Короткое событие на [ms] миллисекунд. */
    fun show(c: Content, ms: Long = 2600) = main.post {
        if (!enabled) return@post
        flash = c
        main.removeCallbacks(hideFlash); main.postDelayed(hideFlash, ms)
        render()
    }

    /** «Живое» занятие: показывается, пока не снять (null). */
    fun setLive(key: String, c: Content?) = main.post {
        if (c == null) live.remove(key) else live[key] = c
        render()
    }

    fun message(title: String, sub: String? = null, icon: Int? = R.drawable.ic_info, tint: Int = Color.WHITE, ms: Long = 2600) =
        show(Content(icon = icon, title = title, sub = sub, tint = tint, expanded = sub != null), ms)

    fun volume(level: Int, max: Int, muted: Boolean) {
        val f = if (muted || max <= 0) 0f else level / max.toFloat()
        show(Content(icon = if (muted || level == 0) R.drawable.ic_volume_off else R.drawable.ic_volume,
            title = "", bar = f, right = if (muted) "Без звука" else "$level"), 1800)
    }

    fun notification(item: Notify.Item) =
        show(Content(bmp = item.icon, title = item.title.ifBlank { item.app }, sub = item.text.ifBlank { item.app }, expanded = true), 4500)

    /** Вызывается, когда главный экран Sky OS появился/скрылся. */
    fun hostChanged() = main.post { render() }

    // ---------- Отрисовка ----------

    private fun render() {
        val host = Notify.activityHost?.takeIf { it.isAttachedToWindow }
        // поверх чужих приложений — только короткие события, «живые» не мешают смотреть видео
        val c = flash ?: (if (host != null) live.values.lastOrNull() else null)
        if (c == null || !enabled) { collapse(); return }
        val p = ensurePill(host) ?: return
        p.bind(c)
    }

    private fun ensurePill(host: FrameLayout?): Pill? {
        val wantHost: Any? = host ?: HomeInterceptService.instance
        if (wantHost == null) return null
        if (pill != null && pillHost === wantHost) return pill
        removePill()
        if (host != null) {
            val p = Pill(host.context)
            host.addView(p, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = host.context.dpi(10) })
            p.appear()
            pill = p; pillHost = host
            return p
        }
        val svc = HomeInterceptService.instance ?: return null
        return try {
            val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val p = Pill(svc)
            val wrap = FrameLayout(svc).apply { clipChildren = false }
            wrap.addView(p, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = svc.dpi(10) }
            wm.addView(wrap, lp)
            p.appear()
            pill = p; pillHost = svc; overlayWrap = wrap
            p
        } catch (e: Exception) { null }
    }

    private fun collapse() {
        val p = pill ?: return
        pill = null
        val host = pillHost; val wrap = overlayWrap
        pillHost = null; overlayWrap = null
        p.disappear {
            when {
                wrap != null -> try { (host as? HomeInterceptService)?.let { (it.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(wrap) } } catch (e: Exception) {}
                else -> (p.parent as? ViewGroup)?.removeView(p)
            }
        }
    }

    private fun removePill() {
        val p = pill ?: return
        val wrap = overlayWrap; val host = pillHost
        pill = null; pillHost = null; overlayWrap = null
        if (wrap != null) try { (host as? HomeInterceptService)?.let { (it.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(wrap) } } catch (e: Exception) {}
        else (p.parent as? ViewGroup)?.removeView(p)
    }

    /** Сама «пилюля». Размер меняется плавно (LayoutTransition), как в iOS. */
    private class Pill(ctx: Context) : LinearLayout(ctx) {
        private val icon = ImageView(ctx)
        private val texts = LinearLayout(ctx).apply { orientation = VERTICAL }
        private val title = Ui.text(ctx, 13f, Color.WHITE, Ui.medium, false).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        private val sub = Ui.text(ctx, 12f, 0xB3FFFFFF.toInt(), Ui.regular, false).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        private val bar = Bar(ctx)
        private val ring = Ring(ctx)
        private val right = Ui.text(ctx, 13f, Color.WHITE, Ui.medium, false).apply { maxLines = 1 }

        init {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Ui.round(ctx, 22f, Color.BLACK, 0x22FFFFFF)
            minimumHeight = ctx.dpi(34)
            layoutTransition = LayoutTransition().apply {
                enableTransitionType(LayoutTransition.CHANGING)
                setDuration(260)
            }
            addView(icon, LayoutParams(ctx.dpi(20), ctx.dpi(20)))
            texts.addView(title); texts.addView(sub)
            addView(texts, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ctx.dpi(10) })
            addView(bar, LayoutParams(ctx.dpi(150), ctx.dpi(6)).apply { marginStart = ctx.dpi(12) })
            addView(ring, LayoutParams(ctx.dpi(20), ctx.dpi(20)).apply { marginStart = ctx.dpi(12) })
            addView(right, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ctx.dpi(12) })
        }

        fun bind(c: Content) {
            val big = c.expanded
            val pad = if (big) context.dpi(14) else context.dpi(12)
            setPadding(pad, if (big) context.dpi(11) else context.dpi(7), context.dpi(16), if (big) context.dpi(11) else context.dpi(7))
            background = Ui.round(context, if (big) 24f else 20f, Color.BLACK, 0x22FFFFFF)
            val iconSize = context.dpi(if (big) 34 else 20)
            (icon.layoutParams as LayoutParams).apply { width = iconSize; height = iconSize }
            when {
                c.bmp != null -> { icon.setImageBitmap(c.bmp); icon.clearColorFilter(); icon.visibility = VISIBLE }
                c.icon != null -> { icon.setImageResource(c.icon); icon.setColorFilter(c.tint); icon.visibility = VISIBLE }
                else -> icon.visibility = GONE
            }
            title.text = c.title
            title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, if (big) 14f else 13f)
            title.typeface = if (big) Ui.bold else Ui.medium
            texts.visibility = if (c.title.isEmpty() && c.sub == null) GONE else VISIBLE
            title.visibility = if (c.title.isEmpty()) GONE else VISIBLE
            sub.text = c.sub ?: ""
            sub.visibility = if (c.sub.isNullOrEmpty()) GONE else VISIBLE
            texts.layoutParams = (texts.layoutParams as LayoutParams).apply {
                width = if (big) context.dpi(320) else ViewGroup.LayoutParams.WRAP_CONTENT
            }
            title.maxWidth = context.dpi(320)
            bar.visibility = if (c.bar != null) VISIBLE else GONE
            c.bar?.let { bar.set(it) }
            ring.visibility = if (c.ring != null) VISIBLE else GONE
            c.ring?.let { ring.set(it) }
            right.text = c.right ?: ""
            right.visibility = if (c.right.isNullOrEmpty()) GONE else VISIBLE
        }

        fun appear() {
            alpha = 0f; scaleX = 0.4f; scaleY = 0.6f
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(OvershootInterpolator(1.2f)).start()
        }

        fun disappear(done: () -> Unit) {
            animate().alpha(0f).scaleX(0.5f).scaleY(0.6f).setDuration(220).withEndAction(done).start()
        }
    }

    /** Полоска громкости. */
    private class Bar(ctx: Context) : View(ctx) {
        private var v = 0f
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FFFFFF }
        private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val r = RectF()
        fun set(f: Float) { v = f.coerceIn(0f, 1f); invalidate() }
        override fun onDraw(c: Canvas) {
            val h = height.toFloat()
            r.set(0f, 0f, width.toFloat(), h); c.drawRoundRect(r, h / 2, h / 2, bg)
            if (v > 0f) { r.set(0f, 0f, maxOf(h, width * v), h); c.drawRoundRect(r, h / 2, h / 2, fg) }
        }
    }

    /** Кольцо прогресса; -1 — крутится без процента. */
    private class Ring(ctx: Context) : View(ctx) {
        private var v = 0f
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x40FFFFFF; strokeWidth = ctx.dp(2.5f) }
        private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = 0xFF30D158.toInt(); strokeWidth = ctx.dp(2.5f); strokeCap = Paint.Cap.ROUND
        }
        private val r = RectF()
        fun set(f: Float) { v = f; invalidate() }
        override fun onDraw(c: Canvas) {
            val s = fg.strokeWidth
            r.set(s, s, width - s, height - s)
            c.drawOval(r, bg)
            if (v < 0f) {
                val a = (System.currentTimeMillis() % 1000L) * 0.36f
                fg.color = Color.WHITE
                c.drawArc(r, a, 90f, false, fg)
                postInvalidateDelayed(40)
            } else {
                fg.color = 0xFF30D158.toInt()
                c.drawArc(r, -90f, 360f * v.coerceIn(0f, 1f), false, fg)
            }
        }
    }
}
