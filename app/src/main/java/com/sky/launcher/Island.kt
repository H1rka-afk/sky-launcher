package com.sky.launcher

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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * Dynamic Island: чёрная «пилюля» сверху по центру.
 *  • короткие события (уведомления, Wi‑Fi, Bluetooth, снимок) — раскрываются на пару секунд;
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
            host.addView(p, FrameLayout.LayoutParams(p.boxW, p.boxH,
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
            wrap.addView(p, FrameLayout.LayoutParams(p.boxW, p.boxH, Gravity.CENTER_HORIZONTAL))
            val lp = WindowManager.LayoutParams(
                p.boxW, p.boxH,
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

    /** Пружина как у iOS: быстро растягивается, чуть перелетает и мягко садится. */
    private val spring = android.animation.TimeInterpolator { t ->
        (1f - kotlin.math.exp(-6.5f * t) * kotlin.math.cos(11f * t)).toFloat()
    }

    /**
     * Сама «пилюля». Вид фиксированного размера, а чёрная капсула рисуется внутри
     * с анимированными шириной и высотой — так она плавно «перетекает» между состояниями.
     */
    private class Pill(ctx: Context) : FrameLayout(ctx) {
        val boxW = ctx.dpi(440)
        val boxH = ctx.dpi(84)
        private val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        private val icon = ImageView(ctx)
        private val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        private val title = Ui.text(ctx, 12f, Color.WHITE, Ui.medium, false).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        private val sub = Ui.text(ctx, 11f, 0xB3FFFFFF.toInt(), Ui.regular, false).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        private val bar = Bar(ctx)
        private val ring = Ring(ctx)
        private val right = Ui.text(ctx, 12f, Color.WHITE, Ui.medium, false).apply { maxLines = 1 }
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        private val rect = RectF()
        private var bgW = ctx.dp(50f)
        private var bgH = ctx.dp(20f)
        private var morph: android.animation.ValueAnimator? = null
        private var kind: String? = null
        private var shown = false

        init {
            setWillNotDraw(false)
            clipChildren = false
            row.addView(icon, LinearLayout.LayoutParams(ctx.dpi(16), ctx.dpi(16)))
            texts.addView(title); texts.addView(sub)
            row.addView(texts, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ctx.dpi(8) })
            row.addView(bar, LinearLayout.LayoutParams(ctx.dpi(104), ctx.dpi(5)).apply { marginStart = ctx.dpi(10) })
            row.addView(ring, LinearLayout.LayoutParams(ctx.dpi(16), ctx.dpi(16)).apply { marginStart = ctx.dpi(10) })
            row.addView(right, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ctx.dpi(10) })
            addView(row, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            row.alpha = 0f
        }

        override fun onDraw(c: Canvas) {
            val l = (width - bgW) / 2f
            rect.set(l, 0f, l + bgW, bgH)
            val r = minOf(bgH / 2f, context.dp(22f))
            c.drawRoundRect(rect, r, r, paint)
        }

        private fun morphTo(w: Float, h: Float, ms: Long, interp: android.animation.TimeInterpolator, end: (() -> Unit)? = null) {
            morph?.cancel()
            val w0 = bgW; val h0 = bgH
            morph = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = ms; interpolator = interp
                addUpdateListener { val f = it.animatedValue as Float; bgW = w0 + (w - w0) * f; bgH = h0 + (h - h0) * f; invalidate() }
                if (end != null) addListener(object : android.animation.AnimatorListenerAdapter() {
                    var cancelled = false
                    override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
                    override fun onAnimationEnd(a: android.animation.Animator) { if (!cancelled) end() }
                })
                start()
            }
        }

        private fun apply(c: Content) {
            val big = c.expanded
            val ctx = context
            row.setPadding(ctx.dpi(if (big) 12 else 10), ctx.dpi(if (big) 9 else 5), ctx.dpi(if (big) 16 else 12), ctx.dpi(if (big) 9 else 5))
            val iconSize = ctx.dpi(if (big) 28 else 16)
            (icon.layoutParams as LinearLayout.LayoutParams).apply { width = iconSize; height = iconSize }
            when {
                c.bmp != null -> { icon.setImageBitmap(c.bmp); icon.clearColorFilter(); icon.visibility = VISIBLE }
                c.icon != null -> { icon.setImageResource(c.icon); icon.setColorFilter(c.tint); icon.visibility = VISIBLE }
                else -> icon.visibility = GONE
            }
            title.text = c.title
            title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, if (big) 13f else 12f)
            title.typeface = if (big) Ui.bold else Ui.medium
            texts.visibility = if (c.title.isEmpty() && c.sub.isNullOrEmpty()) GONE else VISIBLE
            title.visibility = if (c.title.isEmpty()) GONE else VISIBLE
            sub.text = c.sub ?: ""
            sub.visibility = if (c.sub.isNullOrEmpty()) GONE else VISIBLE
            texts.layoutParams = (texts.layoutParams as LinearLayout.LayoutParams).apply {
                width = if (big) ctx.dpi(270) else ViewGroup.LayoutParams.WRAP_CONTENT
            }
            title.maxWidth = ctx.dpi(270)
            bar.visibility = if (c.bar != null) VISIBLE else GONE
            c.bar?.let { bar.set(it) }
            ring.visibility = if (c.ring != null) VISIBLE else GONE
            c.ring?.let { ring.set(it) }
            right.text = c.right ?: ""
            right.visibility = if (c.right.isNullOrEmpty()) GONE else VISIBLE
        }

        private fun target(): Pair<Float, Float> {
            row.measure(MeasureSpec.makeMeasureSpec(boxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(boxH, MeasureSpec.AT_MOST))
            return row.measuredWidth.toFloat() to row.measuredHeight.toFloat()
        }

        private fun kindOf(c: Content) = when {
            c.bar != null -> "volume"
            c.ring != null -> "ring:" + c.title
            else -> "msg:" + c.title + c.sub + c.right
        }

        fun bind(c: Content) {
            val k = kindOf(c)
            if (shown && k.substringBefore(':') == kind?.substringBefore(':') && (k == "volume" || k.startsWith("ring"))) {
                // то же занятие (громкость, проценты) — обновляем на месте, капсула чуть «дышит»
                kind = k; apply(c)
                val (w, h) = target()
                if (kotlin.math.abs(w - bgW) > 1 || kotlin.math.abs(h - bgH) > 1) morphTo(w, h, 380, spring)
                return
            }
            if (shown && k == kind) return
            kind = k
            val first = !shown
            shown = true
            row.animate().cancel()
            if (first) {
                apply(c)
                val (w, h) = target()
                morphTo(w, h, 620, spring)
                row.alpha = 0f; row.scaleX = 0.85f; row.scaleY = 0.85f
                row.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(140).setDuration(220).start()
            } else {
                // содержимое гаснет, капсула перетекает в новый размер, новое содержимое проявляется
                row.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f).setStartDelay(0).setDuration(90).withEndAction {
                    apply(c)
                    val (w, h) = target()
                    morphTo(w, h, 560, spring)
                    row.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(110).setDuration(200).start()
                }.start()
            }
        }

        fun appear() {
            bgW = context.dp(50f); bgH = context.dp(20f)
            alpha = 0f
            animate().alpha(1f).setDuration(120).start()
        }

        fun disappear(done: () -> Unit) {
            row.animate().cancel()
            row.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setStartDelay(0).setDuration(110).start()
            morphTo(context.dp(50f), context.dp(20f), 300, android.view.animation.AccelerateDecelerateInterpolator()) {
                animate().alpha(0f).setDuration(140).withEndAction(done).start()
            }
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
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x40FFFFFF; strokeWidth = ctx.dp(2f) }
        private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = 0xFF30D158.toInt(); strokeWidth = ctx.dp(2f); strokeCap = Paint.Cap.ROUND
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
