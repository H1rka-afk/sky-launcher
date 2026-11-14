package com.sky.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

class Wallpaper(val name: String, val base: Int, val colors: IntArray)

object Wallpapers {
    private fun w(name: String, base: Long, vararg c: Long) =
        Wallpaper(name, base.toInt(), c.map { it.toInt() }.toIntArray())

    val all = listOf(
        w("Аврора", 0xFF070B1F, 0xFF5E5CE6, 0xFF0A84FF, 0xFF64D2FF, 0xFFBF5AF2),
        w("Закат", 0xFF1A0820, 0xFFFF375F, 0xFFFF9F0A, 0xFFBF5AF2, 0xFFFF6B3D),
        w("Океан", 0xFF00121F, 0xFF0A84FF, 0xFF30D5C8, 0xFF5AC8FA, 0xFF1E3A8A),
        w("Лес", 0xFF04140C, 0xFF30D158, 0xFF00C7BE, 0xFF8FD14F, 0xFF0B5D3B),
        w("Графит", 0xFF0B0B0D, 0xFF48484A, 0xFF636366, 0xFF8E8E93, 0xFF2C2C2E),
        w("Персик", 0xFF2A0D14, 0xFFFF9F0A, 0xFFFFD60A, 0xFFFF375F, 0xFFFF6482),
    )

    fun get(i: Int) = all[i.coerceIn(0, all.size - 1)]
}

/**
 * Живые обои: мягкие цветные пятна медленно плавают, как на iPhone.
 * Рисуются в маленький буфер и растягиваются — это почти не нагружает ТВ.
 */
class WallpaperView(ctx: Context, private val animated: Boolean = true) : View(ctx) {

    var wallpaper: Wallpaper = Wallpapers.all[0]
        set(v) { field = v; invalidate() }

    private val bw = 240
    private val bh = 135
    private val buf = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
    private val bc = Canvas(buf)
    private val blob = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scale = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val shade = Paint()
    private val dst = Rect()
    private val t0 = SystemClock.uptimeMillis()
    private var running = false

    fun start() { running = true; invalidate() }
    fun stop() { running = false }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        dst.set(0, 0, w, h)
        shade.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(0x47000000, 0x00000000, 0x14000000, 0x66000000),
            floatArrayOf(0f, 0.3f, 0.6f, 1f), Shader.TileMode.CLAMP
        )
    }

    private fun render(t: Float) {
        bc.drawColor(wallpaper.base)
        wallpaper.colors.forEachIndexed { i, col ->
            val ph = i * 1.9f
            val cx = bw * (0.5f + 0.42f * sin(t * 0.07f * (1 + i * 0.3f) + ph))
            val cy = bh * (0.5f + 0.40f * cos(t * 0.05f * (1 + i * 0.25f) + ph * 1.3f))
            val r = bw * (0.50f + 0.08f * sin(t * 0.09f + i))
            blob.shader = RadialGradient(
                cx, cy, r,
                intArrayOf(col and 0xE6FFFFFF.toInt(), col and 0x66FFFFFF, col and 0x00FFFFFF),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
            )
            bc.drawCircle(cx, cy, r, blob)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val t = if (animated) (SystemClock.uptimeMillis() - t0) / 1000f + 20f else 20f
        render(t)
        canvas.drawBitmap(buf, null, dst, scale)
        if (animated) canvas.drawRect(dst, shade)
        if (animated && running) postInvalidateDelayed(50)
    }
}
