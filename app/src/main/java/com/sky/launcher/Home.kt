package com.sky.launcher

import android.content.Context
import android.view.View
import android.view.ViewGroup
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

const val COLS = 8
const val ROWS = 3

/** Элемент главного экрана: приложение или виджет, занимает w×h ячеек. */
class HomeItem(
    val id: String, val kind: String, val pkg: String?,
    var page: Int, var x: Int, var y: Int, var w: Int, var h: Int
) {
    val isApp get() = kind == "app"

    fun toJson(): JSONObject = JSONObject().put("id", id).put("k", kind).put("pkg", pkg ?: "")
        .put("p", page).put("x", x).put("y", y).put("w", w).put("h", h)

    companion object {
        fun fromJson(o: JSONObject) = HomeItem(
            o.getString("id"), o.getString("k"), o.optString("pkg").ifEmpty { null },
            o.getInt("p"), o.getInt("x"), o.getInt("y"), o.getInt("w"), o.getInt("h")
        )
    }
}

/** Раскладка главного экрана: страницы, Dock, свои названия. Хранится в SharedPreferences. */
class HomeModel(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("skyos_home", Context.MODE_PRIVATE)
    val items = ArrayList<HomeItem>()
    val dock = ArrayList<String>()
    val labels = HashMap<String, String>()
    var apps: Map<String, AppEntry> = emptyMap()
        private set
    private var loaded = false

    companion object { const val DOCK_MAX = 5 }

    fun newId(): String = UUID.randomUUID().toString().substring(0, 8)

    fun sync(all: List<AppEntry>) {
        apps = all.associateBy { it.pkg }
        if (!loaded) { loaded = true; if (!restore()) defaults() }
        items.removeAll { it.isApp && it.pkg !in apps }
        dock.retainAll { it in apps }
        while (dock.size > DOCK_MAX) dock.removeAt(dock.size - 1)
        for (a in all) {
            if (a.pkg !in dock && items.none { it.pkg == a.pkg })
                place(HomeItem(newId(), "app", a.pkg, 0, 0, 0, 1, 1))
        }
        compact(); save()
    }

    private fun defaults() {
        dock.clear(); items.clear()
        AppRepository.DOCK_DEFAULTS.filter { it in apps }.take(4).forEach { dock += it }
        if (dock.size < DOCK_MAX) dock += AppRepository.SETTINGS
        items += HomeItem(newId(), "clock", null, 0, 0, 0, 3, 1)
        items += HomeItem(newId(), "calendar", null, 0, 3, 0, 1, 1)
        items += HomeItem(newId(), "weather", null, 0, 4, 0, 4, 2)
    }

    fun free(p: Int, x: Int, y: Int, w: Int, h: Int, ignore: HomeItem?): Boolean =
        x >= 0 && y >= 0 && x + w <= COLS && y + h <= ROWS && items.none {
            it !== ignore && it.page == p && x < it.x + it.w && it.x < x + w && y < it.y + it.h && it.y < y + h
        }

    fun overlapping(a: HomeItem) = items.filter {
        it !== a && it.page == a.page && a.x < it.x + it.w && it.x < a.x + a.w && a.y < it.y + it.h && it.y < a.y + a.h
    }

    /** Ставит элемент в первое свободное место, начиная со страницы startPage. */
    fun place(item: HomeItem, startPage: Int = 0) {
        var p = startPage.coerceAtLeast(0)
        while (p < 60) {
            for (y in 0..ROWS - item.h) for (x in 0..COLS - item.w) {
                if (free(p, x, y, item.w, item.h, item)) {
                    item.page = p; item.x = x; item.y = y
                    if (item !in items) items += item
                    return
                }
            }
            p++
        }
    }

    val pageCount get() = (items.maxOfOrNull { it.page } ?: 0) + 1

    fun compact() {
        val map = items.map { it.page }.distinct().sorted().withIndex().associate { it.value to it.index }
        items.forEach { it.page = map[it.page] ?: 0 }
    }

    fun onPage(p: Int) = items.filter { it.page == p }

    fun label(pkg: String) = labels[pkg] ?: apps[pkg]?.label ?: pkg

    fun note(id: String): String = prefs.getString("note_$id", null) ?: "Удерживайте OK, чтобы написать заметку"
    fun setNote(id: String, text: String) = prefs.edit().putString("note_$id", text).apply()

    fun save() {
        val arr = JSONArray(); items.forEach { arr.put(it.toJson()) }
        val lab = JSONObject(); labels.forEach { (k, v) -> lab.put(k, v) }
        prefs.edit().putString("items", arr.toString()).putString("dock", dock.joinToString(","))
            .putString("labels", lab.toString()).apply()
    }

    private fun restore(): Boolean {
        val s = prefs.getString("items", null) ?: return false
        return try {
            items.clear(); dock.clear(); labels.clear()
            val arr = JSONArray(s)
            for (i in 0 until arr.length()) items += HomeItem.fromJson(arr.getJSONObject(i))
            prefs.getString("dock", "")!!.split(",").filter { it.isNotBlank() }.forEach { dock += it }
            val lab = JSONObject(prefs.getString("labels", "{}")!!)
            lab.keys().forEach { labels[it] = lab.getString(it) }
            true
        } catch (e: Exception) { items.clear(); dock.clear(); false }
    }
}

/** Страница главного экрана: раскладывает детей по сетке COLS×ROWS. */
class PageView(ctx: Context) : ViewGroup(ctx) {
    class LP(val item: HomeItem) : ViewGroup.LayoutParams(0, 0)

    private val gap = ctx.dpi(8)

    init { clipChildren = false; clipToPadding = false }

    private fun gapFor(item: HomeItem) = if (item.isApp) 0 else gap

    override fun onMeasure(ws: Int, hs: Int) {
        val w = MeasureSpec.getSize(ws); val h = MeasureSpec.getSize(hs)
        val cw = w / COLS; val ch = h / ROWS
        for (i in 0 until childCount) {
            val c = getChildAt(i); val it = (c.layoutParams as LP).item; val g = gapFor(it)
            c.measure(
                MeasureSpec.makeMeasureSpec((cw * it.w - 2 * g).coerceAtLeast(0), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((ch * it.h - 2 * g).coerceAtLeast(0), MeasureSpec.EXACTLY)
            )
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cw = width / COLS; val ch = height / ROWS
        val ox = (width - cw * COLS) / 2
        for (i in 0 until childCount) {
            val c = getChildAt(i); val it = (c.layoutParams as LP).item; val g = gapFor(it)
            val left = ox + it.x * cw + g; val top = it.y * ch + g
            c.layout(left, top, left + c.measuredWidth, top + c.measuredHeight)
        }
    }

    override fun checkLayoutParams(p: ViewGroup.LayoutParams?) = p is LP
    override fun generateDefaultLayoutParams(): ViewGroup.LayoutParams = LP(HomeItem("", "app", null, 0, 0, 0, 1, 1))
    override fun generateLayoutParams(p: ViewGroup.LayoutParams?): ViewGroup.LayoutParams = generateDefaultLayoutParams()

    fun viewFor(id: String): View? = (0 until childCount).map { getChildAt(it) }.firstOrNull { it.tag == id }
}
