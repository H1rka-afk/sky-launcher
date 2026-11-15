package com.sky.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Анимированные обои. Всё тяжёлое (фон, туманности, силуэты) готовится один раз
 * под размер экрана, а в каждом кадре рисуются только звёзды, ленты и частицы.
 */
class LiveScenes(ctx: Context, val kind: Int, val w: Int, val h: Int, private val colors: IntArray) {

    companion object {
        const val SPACE = 1
        const val AURORA = 2
        const val FIREFLIES = 3
        const val WAVES = 4
    }

    private val d = ctx.resources.displayMetrics.density
    private val fw = w.coerceAtLeast(1).toFloat()
    private val fh = h.coerceAtLeast(1).toFloat()
    private val rnd = Random(kind * 7919L)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val bg = Paint(Paint.DITHER_FLAG)
    private val path = Path()

    // ---- звёзды ----
    private val starN = when (kind) { SPACE -> 220; AURORA -> 120; WAVES -> 0; else -> 40 }
    private val sx = FloatArray(starN) { rnd.nextFloat() }
    private val sy = FloatArray(starN) { rnd.nextFloat() * if (kind == SPACE) 1f else 0.6f }
    private val sr = FloatArray(starN) { (0.5f + rnd.nextFloat() * rnd.nextFloat() * 1.6f) * d }
    private val sph = FloatArray(starN) { rnd.nextFloat() * 6.28f }
    private val ssp = FloatArray(starN) { 0.6f + rnd.nextFloat() * 2.2f }
    private val sdepth = FloatArray(starN) { 0.2f + rnd.nextFloat() }

    // ---- туманность (маленький буфер, растягивается видеочипом) ----
    private val nb = if (kind == SPACE) Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888) else null
    private val nbc = nb?.let { Canvas(it) }
    private val dst = Rect(0, 0, w, h)

    // ---- светлячки ----
    private val flyN = 46
    private val fx = FloatArray(flyN) { rnd.nextFloat() }
    private val fy = FloatArray(flyN) { 0.3f + rnd.nextFloat() * 0.7f }
    private val fph = FloatArray(flyN) { rnd.nextFloat() * 6.28f }
    private val fsz = FloatArray(flyN) { 0.5f + rnd.nextFloat() }
    private val glow: Bitmap? = if (kind == FIREFLIES) makeGlow((40 * d).toInt().coerceAtLeast(8)) else null

    private val trees = Path()
    private val rf = android.graphics.RectF()

    init {
        bg.shader = when (kind) {
            AURORA -> LinearGradient(0f, 0f, 0f, fh, intArrayOf(0xFF01060E.toInt(), 0xFF041A26.toInt(), 0xFF0A2A2E.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
            FIREFLIES -> LinearGradient(0f, 0f, 0f, fh, intArrayOf(0xFF020812.toInt(), 0xFF06201C.toInt(), 0xFF0B2B1A.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            WAVES -> LinearGradient(0f, 0f, 0f, fh, intArrayOf(0xFF0B1E4D.toInt(), 0xFF2A4A9C.toInt(), 0xFFE58FB0.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            else -> null
        }
        if (kind == AURORA || kind == FIREFLIES) buildTrees(if (kind == AURORA) 0.86f else 0.8f)
    }

    private fun makeGlow(size: Int): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val r = size / 2f
        val gp = Paint(Paint.ANTI_ALIAS_FLAG)
        gp.shader = RadialGradient(r, r, r, intArrayOf(0xFFFFFFE0.toInt(), 0xCCE8FF7A.toInt(), 0x33C8F04A, 0x00C8F04A),
            floatArrayOf(0f, 0.12f, 0.4f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(r, r, r, gp)
        return b
    }

    /** Силуэт елового леса по нижнему краю. */
    private fun buildTrees(level: Float) {
        val r = Random(31)
        trees.reset()
        trees.moveTo(0f, fh)
        var x = -10f * d
        while (x < fw + 20 * d) {
            val th = fh * (0.08f + r.nextFloat() * 0.12f)
            val tw = th * (0.32f + r.nextFloat() * 0.1f)
            val base = fh * level + r.nextFloat() * fh * 0.04f
            trees.lineTo(x, base)
            // ярусы ели
            for (k in 0 until 4) {
                val y0 = base - th * k / 4f
                val y1 = base - th * (k + 1) / 4f
                val half = tw / 2f * (1f - k * 0.22f)
                trees.lineTo(x + tw / 2f - half, y0)
                trees.lineTo(x + tw / 2f, y1 - th * 0.08f)
                trees.lineTo(x + tw / 2f + half, y0)
            }
            trees.lineTo(x + tw, base)
            x += tw * (0.55f + r.nextFloat() * 0.5f)
        }
        trees.lineTo(fw, fh)
        trees.close()
    }

    fun draw(c: Canvas, t: Float) {
        when (kind) {
            SPACE -> space(c, t)
            AURORA -> aurora(c, t)
            FIREFLIES -> fireflies(c, t)
            WAVES -> waves(c, t)
        }
    }

    private fun stars(c: Canvas, t: Float, drift: Float) {
        for (i in 0 until starN) {
            val tw = 0.55f + 0.45f * sin(t * ssp[i] + sph[i])
            p.color = 0xFFFFFF or ((255 * tw * (0.5f + 0.5f * sdepth[i] / 1.2f)).toInt().coerceIn(0, 255) shl 24)
            var x = (sx[i] - t * drift * sdepth[i]) % 1f
            if (x < 0) x += 1f
            c.drawCircle(x * fw, sy[i] * fh, sr[i], p)
        }
    }

    // ---------------- Космос ----------------
    private fun space(c: Canvas, t: Float) {
        val nc = nbc!!
        nc.drawColor(0xFF02030A.toInt())
        colors.forEachIndexed { i, col ->
            val ph = i * 2.1f
            val cx = 160 * (0.5f + 0.38f * sin(t * 0.02f * (1 + i * 0.4f) + ph))
            val cy = 90 * (0.5f + 0.3f * cos(t * 0.017f * (1 + i * 0.3f) + ph))
            val r = 160 * (0.42f + 0.06f * sin(t * 0.05f + i))
            p.shader = RadialGradient(cx, cy, r, intArrayOf(col and 0xAAFFFFFF.toInt(), col and 0x44FFFFFF, col and 0x00FFFFFF),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            nc.drawCircle(cx, cy, r, p)
        }
        p.shader = null
        c.drawBitmap(nb!!, null, dst, bmpPaint)
        stars(c, t, 0.0025f)
        // падающая звезда раз в ~11 секунд
        val cycle = 11f
        val k = (t % cycle) / 1.1f
        if (k < 1f) {
            val n = (t / cycle).toInt()
            val r = Random(n * 131L)
            val x0 = fw * (0.2f + r.nextFloat() * 0.7f)
            val y0 = fh * (0.05f + r.nextFloat() * 0.3f)
            val len = fw * 0.22f
            val hx = x0 - len * k
            val hy = y0 + len * 0.45f * k
            p.strokeWidth = 1.6f * d
            p.strokeCap = Paint.Cap.ROUND
            p.shader = LinearGradient(hx, hy, hx + len * 0.35f, hy - len * 0.16f,
                ((255 * (1 - k)).toInt() shl 24) or 0xFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
            c.drawLine(hx, hy, hx + len * 0.35f, hy - len * 0.16f, p)
            p.shader = null
        }
    }

    // ---------------- Полярное сияние ----------------
    private val ribbonColors = intArrayOf(0xFF3CFFA0.toInt(), 0xFF2BE0D0.toInt(), 0xFF9B6BFF.toInt())

    private fun aurora(c: Canvas, t: Float) {
        c.drawRect(0f, 0f, fw, fh, bg)
        stars(c, t, 0.0008f)
        for (k in ribbonColors.indices) {
            val col = ribbonColors[k]
            val baseY = fh * (0.30f + k * 0.07f)
            val amp = fh * (0.07f + k * 0.015f)
            val height = fh * (0.30f - k * 0.05f)
            path.reset()
            val steps = 36
            for (i in 0..steps) {
                val x = fw * i / steps
                val u = i / steps.toFloat()
                val y = baseY + amp * sin(u * 5.2f + t * (0.35f + k * 0.12f) + k * 1.7f) +
                    amp * 0.45f * sin(u * 11f - t * 0.5f + k)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            for (i in steps downTo 0) {
                val x = fw * i / steps
                val u = i / steps.toFloat()
                val y = baseY - height * (0.7f + 0.3f * sin(u * 3.1f + t * 0.22f + k * 2f)) +
                    amp * sin(u * 5.2f + t * (0.35f + k * 0.12f) + k * 1.7f)
                path.lineTo(x, y)
            }
            path.close()
            val pulse = 0.75f + 0.25f * sin(t * 0.4f + k * 2.2f)
            val a = (0xE0 * pulse).toInt()
            p.shader = LinearGradient(0f, baseY - height, 0f, baseY + amp * 1.4f,
                intArrayOf(col and 0x00FFFFFF, (a / 2 shl 24) or (col and 0xFFFFFF), (a shl 24) or (col and 0xFFFFFF), col and 0x00FFFFFF),
                floatArrayOf(0f, 0.45f, 0.82f, 1f), Shader.TileMode.CLAMP)
            c.drawPath(path, p)
        }
        p.shader = null
        // отражение-свечение у горизонта
        p.shader = LinearGradient(0f, fh * 0.7f, 0f, fh, 0x00000000, 0x3326D6A0, Shader.TileMode.CLAMP)
        c.drawRect(0f, fh * 0.7f, fw, fh, p)
        p.shader = null
        p.color = 0xFF010407.toInt()
        c.drawPath(trees, p)
    }

    // ---------------- Светлячки ----------------
    private fun fireflies(c: Canvas, t: Float) {
        c.drawRect(0f, 0f, fw, fh, bg)
        stars(c, t, 0.0005f)
        // луна
        val mx = fw * 0.9f; val my = fh * 0.62f
        p.shader = RadialGradient(mx, my, fh * 0.14f, intArrayOf(0x44E8F4FF, 0x14E8F4FF, 0x00E8F4FF),
            floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(mx, my, fh * 0.14f, p)
        p.shader = null
        p.color = 0xD9EFF5FF.toInt()
        c.drawCircle(mx, my, fh * 0.026f, p)
        p.color = 0xFF020A08.toInt()
        c.drawPath(trees, p)
        val g = glow!!
        val gs = g.width
        for (i in 0 until flyN) {
            val x = fw * ((fx[i] + 0.04f * sin(t * 0.21f + fph[i]) + 0.02f * sin(t * 0.53f + fph[i] * 2)) % 1f)
            var y = (fy[i] - t * 0.006f * fsz[i]) % 0.75f
            if (y < 0) y += 0.75f
            val yy = fh * (0.25f + y) + fh * 0.02f * sin(t * 0.4f + fph[i])
            val blink = (0.5f + 0.5f * sin(t * (0.8f + fsz[i]) + fph[i] * 3)).let { it * it }
            bmpPaint.alpha = (60 + 195 * blink).toInt()
            val s = gs * (0.4f + 0.6f * fsz[i])
            rf.set(x - s / 2, yy - s / 2, x + s / 2, yy + s / 2)
            c.drawBitmap(g, null, rf, bmpPaint)
        }
        bmpPaint.alpha = 255
    }

    // ---------------- Волны ----------------
    private val waveCols = intArrayOf(0x66A78BFA, 0x7760A5FA, 0x8838BDF8.toInt(), 0x99312E81.toInt(), 0xCC1E1B4B.toInt())

    private fun waves(c: Canvas, t: Float) {
        c.drawRect(0f, 0f, fw, fh, bg)
        // солнце
        p.shader = RadialGradient(fw * 0.3f, fh * 0.5f, fh * 0.4f, intArrayOf(0x88FFD7A8.toInt(), 0x22FFB0C8, 0x00FFB0C8),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(fw * 0.3f, fh * 0.5f, fh * 0.4f, p)
        p.shader = null
        for (k in waveCols.indices) {
            val base = fh * (0.48f + k * 0.11f)
            val amp = fh * (0.03f + k * 0.006f)
            val speed = 0.25f + k * 0.08f
            val dir = if (k % 2 == 0) 1f else -1f
            path.reset()
            path.moveTo(0f, fh)
            val steps = 32
            for (i in 0..steps) {
                val u = i / steps.toFloat()
                val y = base + amp * sin(u * 2 * PI.toFloat() * (1.2f + k * 0.25f) + t * speed * dir + k) +
                    amp * 0.4f * sin(u * 13f + t * speed * 1.7f)
                path.lineTo(fw * u, y)
            }
            path.lineTo(fw, fh)
            path.close()
            p.color = waveCols[k]
            c.drawPath(path, p)
        }
    }
}
