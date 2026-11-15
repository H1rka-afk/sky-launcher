package com.sky.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * Уведомления в стиле iOS: баннер сверху экрана.
 * Внутри Sky OS рисуется прямо в окне, поверх других приложений — через службу Sky OS.
 */
object Notify {
    class Item(val pkg: String, val app: String, val title: String, val text: String, val icon: Bitmap?, val time: Long)

    /** Последние уведомления (новые первыми) — показываются на экране блокировки. */
    val history = ArrayList<Item>()
    var onHistory: (() -> Unit)? = null

    /** Окно Sky OS на переднем плане (главный экран, настройки, блокировка). */
    var activityHost: FrameLayout? = null

    private val queue = ArrayDeque<Item>()
    private var showing = false
    private val main = Handler(Looper.getMainLooper())
    private var lastKey = ""
    private var lastKeyTime = 0L

    fun post(ctx: Context, title: String, text: String, pkg: String = ctx.packageName,
             app: String = "Sky OS", icon: Bitmap? = null, keep: Boolean = true) {
        val key = "$pkg|$title|$text"
        val now = System.currentTimeMillis()
        if (key == lastKey && now - lastKeyTime < 10_000) return
        lastKey = key; lastKeyTime = now
        val item = Item(pkg, app, title, text, icon ?: IconFactory.logo(ctx.dpi(42)), now)
        if (keep) {
            history.add(0, item)
            while (history.size > 20) history.removeAt(history.size - 1)
            onHistory?.invoke()
        }
        queue.addLast(item)
        if (!showing) next(ctx.applicationContext)
    }

    private fun next(app: Context) {
        val item = queue.removeFirstOrNull()
        if (item == null) { showing = false; return }
        showing = true
        val host = activityHost
        val svc = HomeInterceptService.instance
        when {
            host != null && host.isAttachedToWindow -> {
                val v = banner(host.context, item)
                host.addView(v, FrameLayout.LayoutParams(host.context.dpi(430), FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = host.context.dpi(18) })
                animate(v) { host.removeView(v); next(app) }
            }
            svc != null -> {
                val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val v = banner(svc, item)
                val lp = WindowManager.LayoutParams(
                    svc.dpi(430), WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = svc.dpi(18) }
                try {
                    val wrap = FrameLayout(svc).apply { clipChildren = false }
                    wrap.addView(v)
                    wm.addView(wrap, lp)
                    animate(v) { try { wm.removeView(wrap) } catch (e: Exception) {}; next(app) }
                } catch (e: Exception) { next(app) }
            }
            else -> next(app)   // показать негде — уведомление останется в истории
        }
    }

    private fun animate(v: View, done: () -> Unit) {
        v.alpha = 0f; v.translationY = -v.context.dp(90f)
        v.animate().alpha(1f).translationY(0f).setDuration(320).setInterpolator(DecelerateInterpolator(2f)).withLayer().start()
        main.postDelayed({
            v.animate().alpha(0f).translationY(-v.context.dp(70f)).setDuration(260).withLayer().withEndAction(done).start()
        }, 4800)
    }

    fun ago(time: Long): String {
        val m = ((System.currentTimeMillis() - time) / 60_000L).toInt()
        return when { m < 1 -> "сейчас"; m < 60 -> "$m мин назад"; else -> "${m / 60} ч назад" }
    }

    /** Карточка уведомления: используется и для баннера, и на экране блокировки. */
    fun card(ctx: Context, item: Item, translucent: Boolean = false): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Ui.round(ctx, 20f, if (translucent) 0x99242426.toInt() else 0xF22C2C2E.toInt(), 0x24FFFFFF)
        setPadding(ctx.dpi(11), ctx.dpi(9), ctx.dpi(14), ctx.dpi(9))
        addView(ImageView(ctx).apply { setImageBitmap(item.icon) }, LinearLayout.LayoutParams(ctx.dpi(34), ctx.dpi(34)))
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(Ui.text(ctx, 10f, 0xB3FFFFFF.toInt(), Ui.medium, false).apply {
            text = item.app.uppercase(); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; letterSpacing = 0.04f
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(Ui.text(ctx, 10f, 0x99FFFFFF.toInt(), Ui.regular, false).apply { text = ago(item.time) })
        col.addView(head)
        if (item.title.isNotBlank()) col.addView(Ui.text(ctx, 13f, Color.WHITE, Ui.bold, false).apply {
            text = item.title; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dpi(2) })
        if (item.text.isNotBlank()) col.addView(Ui.text(ctx, 12f, 0xE6FFFFFF.toInt(), Ui.regular, false).apply {
            text = item.text; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dpi(1) })
        addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ctx.dpi(11) })
    }

    private fun banner(ctx: Context, item: Item) = card(ctx, item)
}
