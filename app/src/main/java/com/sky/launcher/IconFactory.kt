package com.sky.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/** Делает из иконок приложений аккуратные «сквирклы» как в iOS. */
object IconFactory {
    private val cache = HashMap<String, Bitmap>()

    fun squircle(size: Float): Path {
        val p = Path()
        val r = size / 2f
        val n = 5.0
        for (i in 0 until 160) {
            val a = 2 * Math.PI * i / 160
            val ca = cos(a); val sa = sin(a)
            val x = r + r * sign(ca) * abs(ca).pow(2.0 / n)
            val y = r + r * sign(sa) * abs(sa).pow(2.0 / n)
            if (i == 0) p.moveTo(x.toFloat(), y.toFloat()) else p.lineTo(x.toFloat(), y.toFloat())
        }
        p.close()
        return p
    }

    fun get(ctx: Context, app: AppEntry, size: Int): Bitmap =
        cache.getOrPut(app.pkg + "@" + size) { render(ctx, app, size) }

    private fun mask(c: Canvas, size: Int) {
        val path = squircle(size.toFloat())
        path.fillType = Path.FillType.INVERSE_WINDING
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        c.drawPath(path, p)
        path.fillType = Path.FillType.WINDING
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = size / 110f; color = 0x1A000000
        }
        c.drawPath(path, edge)
    }

    private fun gradient(c: Canvas, size: Int, top: Int, bottom: Int) {
        val p = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, size.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p)
    }

    private fun render(ctx: Context, app: AppEntry, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val d = app.icon
        when {
            app.special == "settings" -> {
                gradient(c, size, 0xFFA1A1A6.toInt(), 0xFF5E5E63.toInt())
                ctx.getDrawable(R.drawable.ic_gear)?.mutate()?.let {
                    val ins = (size * 0.15f).toInt()
                    it.setBounds(ins, ins, size - ins, size - ins)
                    it.setTint(0xFFF2F2F7.toInt()); it.draw(c)
                }
            }
            d == null -> gradient(c, size, 0xFF8E8E93.toInt(), 0xFF48484A.toInt())
            Build.VERSION.SDK_INT >= 26 && d is AdaptiveIconDrawable -> {
                val ext = (size * 0.25f).toInt()
                val bg = d.background
                if (bg != null) { bg.setBounds(-ext, -ext, size + ext, size + ext); bg.draw(c) } else c.drawColor(Color.WHITE)
                d.foreground?.let { it.setBounds(-ext, -ext, size + ext, size + ext); it.draw(c) }
            }
            else -> {
                val src = toBitmap(d, 192)
                val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                if (opaqueCorners(src)) {
                    c.drawBitmap(src, null, Rect(0, 0, size, size), paint)
                } else {
                    gradient(c, size, 0xFFFFFFFF.toInt(), 0xFFE8E8ED.toInt())
                    val ins = (size * 0.15f).toInt()
                    c.drawBitmap(src, null, Rect(ins, ins, size - ins, size - ins), paint)
                }
            }
        }
        mask(c, size)
        return bmp
    }

    private fun toBitmap(d: Drawable, s: Int): Bitmap {
        val w = if (d.intrinsicWidth > 0 && d.intrinsicHeight > 0) d.intrinsicWidth else s
        val h = if (d.intrinsicWidth > 0 && d.intrinsicHeight > 0) d.intrinsicHeight else s
        // Широкие баннеры вписываем в квадрат по центру
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        if (w > h * 1.3f) {
            val bh = (s * h / w.toFloat()).toInt()
            d.setBounds(0, (s - bh) / 2, s, (s + bh) / 2)
        } else d.setBounds(0, 0, s, s)
        d.draw(c)
        return b
    }

    private fun opaqueCorners(b: Bitmap): Boolean {
        val m = b.width / 12
        val pts = listOf(m to m, b.width - 1 - m to m, m to b.height - 1 - m, b.width - 1 - m to b.height - 1 - m)
        return pts.all { (x, y) -> Color.alpha(b.getPixel(x, y)) > 230 }
    }

    /** Логотип Sky OS для заставки. */
    fun logo(size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint().apply {
            shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(),
                intArrayOf(0xFF0A84FF.toInt(), 0xFF5E5CE6.toInt(), 0xFFBF5AF2.toInt()), null, Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bg)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x73FFFFFF }
        val r = size * 0.19f
        val cx = size / 2f; val cy = size / 2f
        for (i in 0 until 6) {
            val a = Math.PI / 3 * i
            c.drawCircle(cx + (r * 0.95f * cos(a)).toFloat(), cy + (r * 0.95f * sin(a)).toFloat(), r, p)
        }
        p.color = Color.WHITE
        c.drawCircle(cx, cy, r * 0.55f, p)
        mask(c, size)
        return bmp
    }
}
