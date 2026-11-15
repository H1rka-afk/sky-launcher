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
    val special: String? = null,
    val category: Int = -1
)

class AppRepository(ctx: Context) {
    private val ctx = ctx.applicationContext
    private val prefs = ctx.getSharedPreferences("skyos", Context.MODE_PRIVATE)

    companion object {
        /** Подменяется в тестах для скриншотов. */
        var testApps: List<AppEntry>? = null
        /** Список приложений кэшируется; сбрасывается при установке/удалении приложений. */
        @Volatile var dirty = true
        private var cache: List<AppEntry>? = null
        const val SETTINGS = "sky.settings"
        val DOCK_DEFAULTS = listOf(
            "com.google.android.youtube.tv", "ru.kinopoisk.tv", "ru.kinopoisk",
            "ru.okko.tv", "ru.ivi.client", "com.netflix.ninja", "ru.rt.video.app.tv",
            "com.android.vending", "com.google.android.youtube.tvmusic"
        )
    }

    fun settingsEntry() = AppEntry(SETTINGS, "Настройки", Intent(ctx, SettingsActivity::class.java), null, "settings")

    fun load(): List<AppEntry> {
        testApps?.let { return listOf(settingsEntry()) + it }
        val c = cache
        if (!dirty && c != null) return c
        dirty = false
        IconFactory.clear()
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
                result += AppEntry(pkg, ri.loadLabel(pm).toString(), launch, icon, null, ri.activityInfo.applicationInfo.category)
            }
        }
        add(pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0), true)
        add(pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0), false)

        return (listOf(settingsEntry()) + result.sortedBy { it.label.lowercase() }).also { cache = it }
    }

    var wallpaper: Int
        get() = prefs.getInt("wallpaper", 0)
        set(v) = prefs.edit().putInt("wallpaper", v).apply()
}

/** Плитка приложения: иконка-сквиркл + подпись. */
class AppTileView(ctx: Context, private val iconDp: Int = 72, showLabel: Boolean = Prefs.labels) : LinearLayout(ctx) {
    val icon = ImageView(ctx)
    private val label = Ui.text(ctx, 13f)
    private val badge = Ui.text(ctx, 13f, android.graphics.Color.WHITE, Ui.bold, false).apply {
        gravity = Gravity.CENTER; background = Ui.oval(ctx, Ui.RED); elevation = ctx.dp(8f)
    }

    /** Красный значок как в iOS (например, «1» на Настройках при обновлении). */
    fun setBadge(n: Int) {
        badge.text = n.toString()
        badge.visibility = if (n > 0) View.VISIBLE else View.GONE
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        isFocusable = true; isClickable = true; isLongClickable = true
        clipChildren = false; clipToPadding = false
        val s = ctx.dpi(iconDp)
        icon.elevation = 0f   // тени дорого обходятся видеочипу ТВ
        icon.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
                o.setRoundRect(0, 0, v.width, v.height, v.width * 0.27f)
            }
        }
        val frame = android.widget.FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
        frame.addView(icon, android.widget.FrameLayout.LayoutParams(s, s))
        badge.visibility = View.GONE
        frame.addView(badge, android.widget.FrameLayout.LayoutParams(ctx.dpi(24), ctx.dpi(24), Gravity.TOP or Gravity.END)
            .apply { topMargin = -ctx.dpi(7); marginEnd = -ctx.dpi(7) })
        addView(frame, LayoutParams(s, s))
        if (showLabel) {
            label.gravity = Gravity.CENTER
            label.maxLines = 1
            label.ellipsize = TextUtils.TruncateAt.END
            addView(label, LayoutParams(ctx.dpi(iconDp + 30), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dpi(8) })
        }
        Ui.zoomOnFocus(this, 1.15f) { f -> label.typeface = if (f) Ui.medium else Ui.regular }
    }

    fun bindFolder(name: String, apps: List<AppEntry>) {
        icon.elevation = 0f
        icon.setImageBitmap(IconFactory.folder(context, apps, context.dpi(iconDp)))
        label.text = name
    }

    fun bind(app: AppEntry, name: String = app.label) {
        icon.setImageBitmap(IconFactory.get(context, app, context.dpi(iconDp)))
        label.text = name
    }
}

