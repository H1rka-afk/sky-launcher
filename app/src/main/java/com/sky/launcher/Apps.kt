package com.sky.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout

data class AppEntry(
    val pkg: String,
    val label: String,
    val intent: Intent,
    val icon: Drawable?,
    val special: String? = null
)

class AppRepository(ctx: Context) {
    private val ctx = ctx.applicationContext
    private val prefs = ctx.getSharedPreferences("skyos", Context.MODE_PRIVATE)

    companion object {
        /** Подменяется в тестах для скриншотов. */
        var testApps: List<AppEntry>? = null
        const val SETTINGS = "sky.settings"
        val DOCK_DEFAULTS = listOf(
            "com.google.android.youtube.tv", "ru.kinopoisk.tv", "ru.kinopoisk",
            "ru.okko.tv", "ru.ivi.client", "com.netflix.ninja", "ru.rt.video.app.tv",
            "com.android.vending", "com.google.android.youtube.tvmusic"
        )
    }

    fun settingsEntry() = AppEntry(SETTINGS, "Настройки", Intent(Settings.ACTION_SETTINGS), null, "settings")

    fun load(): List<AppEntry> {
        testApps?.let { return listOf(settingsEntry()) + it }
        val pm = ctx.packageManager
        val seen = HashSet<String>()
        val result = ArrayList<AppEntry>()

        fun add(list: List<ResolveInfo>, tv: Boolean) {
            for (ri in list) {
                val pkg = ri.activityInfo.packageName
                if (pkg == ctx.packageName || !seen.add(pkg)) continue
                val launch = (if (tv) pm.getLeanbackLaunchIntentForPackage(pkg)
                              else pm.getLaunchIntentForPackage(pkg)) ?: continue
                val icon = try { ri.loadIcon(pm) } catch (e: Exception) { null }
                result += AppEntry(pkg, ri.loadLabel(pm).toString(), launch, icon)
            }
        }
        add(pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0), true)
        add(pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0), false)

        return listOf(settingsEntry()) + result.sortedBy { it.label.lowercase() }
    }

    var wallpaper: Int
        get() = prefs.getInt("wallpaper", 0)
        set(v) = prefs.edit().putInt("wallpaper", v).apply()
}

/** Плитка приложения: иконка-сквиркл + подпись. */
class AppTileView(ctx: Context, private val iconDp: Int = 72, showLabel: Boolean = true) : LinearLayout(ctx) {
    val icon = ImageView(ctx)
    private val label = Ui.text(ctx, 13f)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        isFocusable = true; isClickable = true; isLongClickable = true
        clipChildren = false; clipToPadding = false
        val s = ctx.dpi(iconDp)
        icon.elevation = ctx.dp(6f)
        icon.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
                o.setRoundRect(0, 0, v.width, v.height, v.width * 0.27f)
            }
        }
        addView(icon, LayoutParams(s, s))
        if (showLabel) {
            label.gravity = Gravity.CENTER
            label.maxLines = 1
            label.ellipsize = TextUtils.TruncateAt.END
            addView(label, LayoutParams(ctx.dpi(iconDp + 30), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dpi(8) })
        }
        Ui.zoomOnFocus(this, 1.15f) { f -> label.typeface = if (f) Ui.medium else Ui.regular }
    }

    fun bind(app: AppEntry, name: String = app.label) {
        icon.setImageBitmap(IconFactory.get(context, app, context.dpi(iconDp)))
        label.text = name
    }
}

