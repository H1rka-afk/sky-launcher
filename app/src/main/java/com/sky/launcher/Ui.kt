package com.sky.launcher

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.TextView

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

object Ui {
    val BLUE = 0xFF0A84FF.toInt()
    val GREEN = 0xFF30D158.toInt()
    val RED = 0xFFFF453A.toInt()
    val PURPLE = 0xFF5E5CE6.toInt()
    val ORANGE = 0xFFFF9F0A.toInt()
    const val GLASS = 0x2EFFFFFF
    const val GLASS_FOCUS = 0x5CFFFFFF
    const val STROKE = 0x24FFFFFF

    val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val bold: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

    fun round(ctx: Context, radiusDp: Float, fill: Int, stroke: Int = STROKE, strokeDp: Float = 1f) =
        GradientDrawable().apply {
            cornerRadius = ctx.dp(radiusDp)
            setColor(fill)
            if (stroke != 0) setStroke(ctx.dp(strokeDp).toInt().coerceAtLeast(1), stroke)
        }

    fun oval(ctx: Context, fill: Int, stroke: Int = 0, strokeDp: Float = 2f) =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            if (stroke != 0) setStroke(ctx.dp(strokeDp).toInt().coerceAtLeast(1), stroke)
        }

    fun text(
        ctx: Context, sizeSp: Float, color: Int = Color.WHITE,
        face: Typeface = regular, shadow: Boolean = true
    ) = TextView(ctx).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        typeface = face
        includeFontPadding = false
        if (shadow) setShadowLayer(ctx.dp(6f), 0f, ctx.dp(1f), 0x55000000)
    }

    /** Плавное увеличение при выборе пультом, как в tvOS. */
    fun zoomOnFocus(v: View, scale: Float = 1.1f, extra: ((Boolean) -> Unit)? = null) {
        v.setOnFocusChangeListener { view, f ->
            view.animate()
                .scaleX(if (f) scale else 1f).scaleY(if (f) scale else 1f)
                .translationZ(if (f) view.context.dp(8f) else 0f)
                .setDuration(180).setInterpolator(DecelerateInterpolator()).start()
            extra?.invoke(f)
        }
    }
}
