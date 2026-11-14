package com.sky.launcher

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    data class AppItem(val label: String, val icon: Drawable, val intent: Intent)

    private lateinit var grid: GridView
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            val now = Date()
            clock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
            date.text = SimpleDateFormat("EEEE, d MMMM", Locale("ru")).format(now)
            handler.postDelayed(this, 10_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        grid = findViewById(R.id.grid)
        clock = findViewById(R.id.clock)
        date = findViewById(R.id.date)

        grid.setOnItemClickListener { _, _, pos, _ ->
            val app = grid.adapter.getItem(pos) as AppItem
            startActivity(app.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun onResume() {
        super.onResume()
        grid.adapter = AppAdapter(this, loadApps())
        grid.requestFocus()
        handler.post(tick)
        enterKioskIfOwner()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    /** Приложения для ТВ (LEANBACK) + обычные, без дублей и без себя. */
    private fun loadApps(): List<AppItem> {
        val pm = packageManager
        val seen = HashSet<String>()
        val result = ArrayList<AppItem>()

        fun add(list: List<ResolveInfo>, tv: Boolean) {
            for (ri in list) {
                val pkg = ri.activityInfo.packageName
                if (pkg == packageName || !seen.add(pkg)) continue
                val launch = (if (tv) pm.getLeanbackLaunchIntentForPackage(pkg)
                              else pm.getLaunchIntentForPackage(pkg)) ?: continue
                val icon = (if (tv) ri.activityInfo.loadBanner(pm) else null)
                    ?: ri.loadIcon(pm)
                result += AppItem(ri.loadLabel(pm).toString(), icon, launch)
            }
        }

        add(pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0), true)
        add(pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0), false)

        // Пункт «Настройки» в конце
        result += AppItem("Настройки", getDrawable(android.R.drawable.ic_menu_preferences)!!,
            Intent(android.provider.Settings.ACTION_SETTINGS))

        return result.sortedBy { it.label.lowercase() }
    }

    /** Если приложение назначено Device Owner — закрепляемся (режим киоска). */
    private fun enterKioskIfOwner() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(packageName)) return
        val admin = ComponentName(this, AdminReceiver::class.java)
        // Разрешаем запуск всех установленных приложений внутри киоска
        val pkgs = packageManager.getInstalledApplications(0).map { it.packageName }.toTypedArray()
        dpm.setLockTaskPackages(admin, pkgs)
        // Делаем себя постоянным домашним экраном
        dpm.addPersistentPreferredActivity(
            admin,
            android.content.IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME); addCategory(Intent.CATEGORY_DEFAULT)
            },
            ComponentName(this, MainActivity::class.java)
        )
        startLockTask()
    }

    // Кнопка «Назад» на главном экране ничего не делает
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean =
        if (keyCode == KeyEvent.KEYCODE_BACK) true else super.onKeyDown(keyCode, event)

    private class AppAdapter(val ctx: Context, val apps: List<AppItem>) : BaseAdapter() {
        override fun getCount() = apps.size
        override fun getItem(p: Int) = apps[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val v = convert ?: LayoutInflater.from(ctx).inflate(R.layout.item_app, parent, false)
            v.findViewById<ImageView>(R.id.icon).setImageDrawable(apps[p].icon)
            v.findViewById<TextView>(R.id.label).text = apps[p].label
            return v
        }
    }
}
