package com.sky.launcher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.CycleInterpolator
import android.view.animation.DecelerateInterpolator

/**
 * Островок экрана блокировки, как на iPhone: чёрная капсула с замком слева.
 * При разблокировке дужка замка подпрыгивает и открывается, затем островок сжимается и исчезает.
 */
class LockIsland(ctx: Context) : View(ctx) {

    private val d = ctx.resources.displayMetrics.density
    private val capsule = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val shackle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.9f * d; strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()
    private val path = Path()

    /** Полный размер капсулы. */
    private val fullW = 118f * d
    private val fullH = 30f * d
    private var capW = fullW
    private var capH = fullH
    private var open = 0f          // 0 — закрыт, 1 — открыт
    private var lockAlpha = 1f
    private var anim: ValueAnimator? = null

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((fullW + 24 * d).toInt(), (fullH + 8 * d).toInt())

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val top = 0f
        rect.set(cx - capW / 2, top, cx + capW / 2, top + capH)
        c.drawRoundRect(rect, capH / 2, capH / 2, capsule)
        if (lockAlpha <= 0f || capW < 40 * d) return
        // замок — у левого края капсулы
        val a = (255 * lockAlpha).toInt()
        body.alpha = a; shackle.alpha = a
        val lx = cx - capW / 2 + 17f * d
        val midY = top + capH / 2
        val bw = 11f * d; val bh = 8.5f * d
        val bTop = midY - bh / 2 + 2.6f * d
        rect.set(lx - bw / 2, bTop, lx + bw / 2, bTop + bh)
        c.drawRoundRect(rect, 2f * d, 2f * d, body)
        // дужка: поднимается и поворачивается вокруг правой ножки — левая выходит из корпуса
        val sw = 7f * d
        val lift = 3.2f * d * open
        c.save()
        c.rotate(-24f * open, lx + sw / 2, bTop - 2f * d)
        c.translate(0f, -lift)
        path.reset()
        val legBottom = bTop + 0.5f * d
        val arcTop = bTop - 7.6f * d
        path.moveTo(lx - sw / 2, legBottom)
        path.lineTo(lx - sw / 2, arcTop + sw / 2)
        path.arcTo(RectF(lx - sw / 2, arcTop, lx + sw / 2, arcTop + sw), 180f, 180f)
        path.lineTo(lx + sw / 2, legBottom + lift)
        c.drawPath(path, shackle)
        c.restore()
    }

    /** Появление: капсула вырастает из маленькой. */
    fun appear() {
        animateTo(220) { f -> capW = 44 * d + (fullW - 44 * d) * f; capH = 18 * d + (fullH - 18 * d) * f; lockAlpha = f }
    }

    /** Неверный код — островок качает головой, как iPhone. */
    fun shake() {
        animate().translationX(9f * d).setInterpolator(CycleInterpolator(3f)).setDuration(420)
            .withEndAction { translationX = 0f }.start()
    }

    /** Разблокировка: замок открывается, пауза, островок сжимается и пропадает. */
    fun unlock(done: () -> Unit) {
        anim?.cancel()
        val spring = android.animation.TimeInterpolator { t ->
            (1f - kotlin.math.exp(-6f * t) * kotlin.math.cos(10f * t)).toFloat()
        }
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420; interpolator = spring
            addUpdateListener { open = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    postDelayed({
                        val w0 = capW; val h0 = capH
                        animateTo(300) { f ->
                            capW = w0 + (36 * d - w0) * f; capH = h0 + (14 * d - h0) * f
                            lockAlpha = 1f - minOf(1f, f * 2.2f)
                            alpha = if (f > 0.6f) 1f - (f - 0.6f) / 0.4f else 1f
                        }
                        postDelayed(done, 310)
                    }, 240)
                }
            })
            start()
        }
    }

    private fun animateTo(ms: Long, step: (Float) -> Unit) {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms; interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { step(it.animatedValue as Float); invalidate() }
            start()
        }
    }
}
