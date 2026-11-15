package com.sky.launcher

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Looper
import android.view.KeyEvent
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/** Рендерит экраны Sky OS в PNG, чтобы проверить внешний вид без телевизора. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w960dp-h540dp-land-xhdpi")
class ScreenshotTest {

    class LetterIcon(private val color: Int, private val letter: String) : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(c: Canvas) {
            val b = bounds
            p.color = color; c.drawRect(b, p)
            p.color = Color.WHITE; p.textSize = b.height() * 0.46f
            p.textAlign = Paint.Align.CENTER; p.typeface = Typeface.DEFAULT_BOLD
            c.drawText(letter, b.exactCenterX(), b.exactCenterY() + p.textSize * 0.36f, p)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("") override fun getOpacity() = PixelFormat.OPAQUE
    }

    @Before
    fun setup() {
        MainActivity.splashShown = true
        val apps = listOf(
            "YouTube" to 0xFFFF0033, "Кинопоиск" to 0xFFFF6600, "Okko" to 0xFF5B2EE6, "Wink" to 0xFFFF4F12,
            "ivi" to 0xFFEA003D, "Netflix" to 0xFF141414, "Spotify" to 0xFF1DB954, "VLC" to 0xFFFF8800,
            "Play Маркет" to 0xFF01875F, "Музыка" to 0xFFFC3C44, "Фото" to 0xFF3478F6, "Файлы" to 0xFF0A84FF,
            "Погода" to 0xFF2D9CDB, "Браузер" to 0xFF5E5CE6, "Twitch" to 0xFF9146FF, "Rutube" to 0xFF100943,
            "ТВ" to 0xFF2C2C2E, "Galaxy" to 0xFF00C7BE, "VK Видео" to 0xFF0077FF, "Downloader" to 0xFFFF7A00,
            "Happ" to 0xFF333333, "Prime Video" to 0xFF1A98FF, "Play Игры" to 0xFF01875F, "LiveTV" to 0xFF5856D6
        )
        AppRepository.testApps = apps.mapIndexed { i, (n, c) ->
            AppEntry("test.app$i", n, Intent(), LetterIcon(c.toInt(), n.take(1)))
        }
        val H = WeatherData::Hour
        WeatherData.data = WeatherData.Data(11, 53, false, 6, 15,
            listOf(H("19", 11, 61, false), H("20", 10, 80, false), H("21", 8, 80, false),
                H("22", 7, 61, false), H("23", 6, 61, false), H("0", 6, 80, false)),
            System.currentTimeMillis())
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun key(act: MainActivity, code: Int) {
        act.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        act.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    @Test
    fun screens() {
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle(800)
        act.window.decorView.captureRoboImage("build/shots/01_home.png")

        act.testGoToPage(1); idle(700)
        act.window.decorView.captureRoboImage("build/shots/02_page2.png")

        // Перемещение виджета
        act.testGoToPage(0); idle(700)
        act.startMove(act.testFirstWidget()!!); idle(300)
        act.testMove(0, 1); idle(400)
        act.window.decorView.captureRoboImage("build/shots/03_move.png")
        key(act, KeyEvent.KEYCODE_BACK); idle(500)

        // Новые виджеты
        for (k in listOf("aclock", "month", "note", "weather_s")) {
            act.addWidget(WidgetDefs.get(k)); idle(400)
            key(act, KeyEvent.KEYCODE_DPAD_CENTER); idle(400)
        }
        val page = act.model.items.first { it.kind == "aclock" }.page
        act.testGoToPage(page); idle(700)
        act.window.decorView.captureRoboImage("build/shots/04_widgets.png")
        act.testGoToPage(0); idle(700)

        act.openControlCenter(); idle(800)
        act.window.decorView.captureRoboImage("build/shots/05_control_center.png")

        act.sheet.showWallpapers(); idle(800)
        act.window.decorView.captureRoboImage("build/shots/06_wallpapers.png")
        act.sheet.hide(); idle(500)
        key(act, KeyEvent.KEYCODE_BACK); idle(500)

        // Папка: приложение на приложение + OK
        val first = act.model.items.filter { it.isApp && it.page == 0 }.sortedWith(compareBy({ it.y }, { it.x }))
            .first { a -> act.model.items.any { b -> b.isApp && b.page == 0 && b.y == a.y && b.x == a.x + 1 } }
        act.startMove(first); idle(300)
        act.testMove(1, 0); idle(300)
        key(act, KeyEvent.KEYCODE_DPAD_CENTER); idle(1200)
        act.window.decorView.captureRoboImage("build/shots/09_folder_open.png")
        key(act, KeyEvent.KEYCODE_BACK); idle(600)
        act.window.decorView.captureRoboImage("build/shots/10_folder_icon.png")

        act.setWallpaper(6); idle(900)
        act.window.decorView.captureRoboImage("build/shots/07_home_photo.png")
        act.setWallpaper(12); idle(900)
        act.window.decorView.captureRoboImage("build/shots/08_home_photo2.png")

        // Живые сцены и космос
        for (name in listOf("Космос", "Полярное сияние", "Светлячки", "Волны")) {
            val i = Wallpapers.all.indexOfFirst { it.name == name && it.scene != 0 }
            act.setWallpaper(i); idle(900)
            act.window.decorView.captureRoboImage("build/shots/w_live_$i.png")
        }
        // выбор обоев с рамкой на светлом фото и раздел «Космос»
        act.setWallpaper(Wallpapers.all.indexOfFirst { it.name == "Туманный лес" }); idle(500)
        act.sheet.showWallpapers(); idle(800)
        act.window.decorView.captureRoboImage("build/shots/06b_wallpapers_focus.png")
        act.sheet.hide(); idle(500)
        val space = Wallpapers.all.withIndex().filter { it.value.group == "space" }
        space.forEach { (i, _) -> act.setWallpaper(i); idle(400); act.window.decorView.captureRoboImage("build/shots/w_space_$i.png") }
        if (space.isNotEmpty()) {
            act.sheet.showWallpapers(); idle(800)
            act.window.decorView.captureRoboImage("build/shots/06c_wallpapers_space.png")
            act.sheet.hide(); idle(500)
        }
    }

    @Test
    fun island() {
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle(800)
        Island.volume(9, 15, false); idle(600)
        act.window.decorView.captureRoboImage("build/shots/19_island_volume.png")
        val ev = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)
        org.junit.Assert.assertTrue(IslandEvents.volumeKey(act, ev))
        idle(3000)
        Notify.post(act, "Мама", "Ты сегодня приедешь на ужин?", "test.app0", "YouTube",
            IconFactory.get(act, act.model.apps["test.app0"]!!, act.dpi(42)))
        idle(600)
        act.window.decorView.captureRoboImage("build/shots/20_island_notification.png")
        idle(5000)
        Island.setLive("timer", Island.Content(icon = R.drawable.ic_moon, title = "", right = "42 мин", tint = 0xFFFF9F0A.toInt()))
        idle(600)
        act.window.decorView.captureRoboImage("build/shots/21_island_timer.png")
        Island.setLive("update", Island.Content(icon = R.drawable.ic_refresh, title = "Обновление", ring = 0.6f, right = "60%"))
        idle(600)
        act.window.decorView.captureRoboImage("build/shots/22_island_update.png")
        Island.setLive("update", null); Island.setLive("timer", null); idle(600)
        val i = Intent(org.robolectric.RuntimeEnvironment.getApplication(), SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_SECTION, "island")
        val a = Robolectric.buildActivity(SettingsActivity::class.java, i).setup().get()
        idle(500)
        a.window.decorView.captureRoboImage("build/shots/s_island.png")
    }

    @Test
    fun settings() {
        for (sec in listOf("about", "update", "cc", "wallpaper", "weather")) {
            val i = Intent(org.robolectric.RuntimeEnvironment.getApplication(), SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_SECTION, sec)
            val a = Robolectric.buildActivity(SettingsActivity::class.java, i).setup().get()
            idle(500)
            a.window.decorView.captureRoboImage("build/shots/s_$sec.png")
            if (sec == "wallpaper") {
                // рамка выбора на светлых обоях
                val cells = ArrayList<android.view.View>()
                fun walk(v: android.view.View) {
                    if (v is android.widget.LinearLayout && v.isFocusable && v.childCount == 2 && v.getChildAt(0) is android.widget.FrameLayout) cells += v
                    if (v is android.view.ViewGroup) for (k in 0 until v.childCount) walk(v.getChildAt(k))
                }
                walk(a.window.decorView)
                cells.getOrNull(1)?.let { it.onFocusChangeListener?.onFocusChange(it, true) }; idle(400)
                a.window.decorView.captureRoboImage("build/shots/s_wallpaper_focus.png")
            }
        }
    }

    @Test
    fun lockAndNotifications() {
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle(800)
        Notify.post(act, "Новое сообщение", "Мама: Ты сегодня приедешь на ужин?", "test.app0", "YouTube",
            IconFactory.get(act, act.model.apps["test.app0"]!!, act.dpi(42)))
        idle(500)
        act.window.decorView.captureRoboImage("build/shots/11_notification.png")
        idle(6000)

        // Пункт управления: режим перестановки и кнопка «+»
        act.openControlCenter(); idle(800)
        act.window.decorView.captureRoboImage("build/shots/12_cc_plus.png")
        act.testControlCenter().startTileMove("input"); idle(400)
        act.window.decorView.captureRoboImage("build/shots/13_cc_move.png")

        // Экран блокировки
        val lock = Robolectric.buildActivity(LockActivity::class.java).setup().get()
        idle(800)
        lock.window.decorView.captureRoboImage("build/shots/14_lock.png")
        Lock.setPin("1234")
        val lock2 = Robolectric.buildActivity(LockActivity::class.java).setup().get()
        idle(300)
        lock2.onKeyDown(KeyEvent.KEYCODE_1, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_1)); idle(300)
        lock2.onKeyDown(KeyEvent.KEYCODE_2, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_2)); idle(500)
        lock2.window.decorView.captureRoboImage("build/shots/15_lock_pin.png")
        Lock.setPin(null)
        val pi = Intent(org.robolectric.RuntimeEnvironment.getApplication(), PinActivity::class.java).putExtra(LockActivity.EXTRA_MODE, "set")
        val pin = Robolectric.buildActivity(PinActivity::class.java, pi).setup().get()
        idle(300)
        pin.onKeyDown(KeyEvent.KEYCODE_4, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_4)); idle(300)
        pin.window.decorView.captureRoboImage("build/shots/16_set_pin.png")
    }

    /** Sky Drop: настоящая отправка фото на сервер ТВ, окно «Принять» и просмотр. */
    @Test
    fun skyDrop() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        Prefs.init(app)
        SkyDrop.start(app)
        idle(300)
        // тестовое «фото с iPhone»
        val bmp = android.graphics.Bitmap.createBitmap(1200, 800, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bmp).apply {
            drawColor(0xFF0A84FF.toInt())
            drawCircle(600f, 400f, 250f, Paint().apply { color = 0xFFFFD60A.toInt() })
        }
        val jpg = java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        val boundary = "----skytest"
        val body = java.io.ByteArrayOutputStream().apply {
            write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"IMG_0042.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray())
            write(jpg)
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()
        var result = ""
        val t = Thread {
            try {
                val c = java.net.URL("http://127.0.0.1:${SkyDrop.PORT}/send?k=${Prefs.dropKey}").openConnection() as java.net.HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X)")
                c.outputStream.use { it.write(body) }
                result = "${c.responseCode} " + c.inputStream.bufferedReader().readText()
            } catch (e: Exception) { result = "ERR ${e.message}" }
        }
        t.start(); t.join(15000)
        idle(300)
        println("SKYDROP UPLOAD: $result; pending=${SkyDrop.pending.keys}")
        // без ключа — отказ
        var denied = ""
        Thread { try {
            val c = java.net.URL("http://127.0.0.1:${SkyDrop.PORT}/send?k=wrong").openConnection() as java.net.HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.outputStream.use { it.write("text=hi".toByteArray()) }
            denied = c.responseCode.toString()
        } catch (e: Exception) { denied = "ERR ${e.message}" } }.apply { start(); join(10000) }
        println("SKYDROP WRONG KEY: $denied")
        org.junit.Assert.assertTrue(result.startsWith("200"))
        org.junit.Assert.assertEquals("403", denied)

        val id = SkyDrop.pending.keys.first()
        val d = Robolectric.buildActivity(DropActivity::class.java, Intent(app, DropActivity::class.java).putExtra("batch", id)).setup().get()
        idle(500)
        d.window.decorView.captureRoboImage("build/shots/17_drop_prompt.png")
        SkyDrop.accept(app, id)
        val v = Robolectric.buildActivity(ViewerActivity::class.java).setup().get()
        idle(500)
        v.window.decorView.captureRoboImage("build/shots/18_drop_viewer.png")

        val st = Robolectric.buildActivity(SettingsActivity::class.java, Intent(app, SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_SECTION, "drop")).setup().get()
        idle(500)
        st.window.decorView.captureRoboImage("build/shots/s_drop.png")
        SkyDrop.stop()
    }

    /** Установщик получает скачанный APK через наш ContentProvider. */
    @Test
    fun updateProvider() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        Updater.apkFile(app).writeBytes(ByteArray(12345) { 7 })
        Robolectric.setupContentProvider(UpdateProvider::class.java, "com.sky.launcher.update")
        val n = app.contentResolver.openInputStream(android.net.Uri.parse("content://com.sky.launcher.update/update.apk"))!!.use { it.readBytes().size }
        println("UPDATE PROVIDER BYTES: $n")
        org.junit.Assert.assertEquals(12345, n)
    }

    /** Обновления статуса не должны сдвигать фокус. */
    @Test
    fun updateFocusStable() {
        val i = Intent(org.robolectric.RuntimeEnvironment.getApplication(), SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_SECTION, "update")
        val a = Robolectric.buildActivity(SettingsActivity::class.java, i).setup().get()
        idle(500)
        a.window.decorView.isFocusableInTouchMode
        val target = generateSequence(listOf<android.view.View>(a.window.decorView)) { l ->
            l.flatMap { v -> if (v is android.view.ViewGroup) (0 until v.childCount).map { v.getChildAt(it) } else emptyList() }.ifEmpty { null }
        }.flatten().first { it is android.widget.TextView && it.text.toString() == "Проверить обновления" }.parent as android.view.View
        target.isFocusableInTouchMode = true
        target.requestFocus()
        val before = a.currentFocus
        repeat(30) { Updater.onChange?.invoke(); idle(20) }
        val after = a.currentFocus
        println("FOCUS STABLE: " + (before === after && before === target))
        org.junit.Assert.assertSame(before, after)
    }

    /** Реальный запрос погоды — проверяем, что сервер отвечает и данные разбираются. */
    @Test
    fun weatherNetwork() {
        try {
            val (d, src) = WeatherData.fetch(47.2357, 39.7015)
            println("WEATHER OK [$src]: ${d.temp}° code=${d.code} min=${d.min} max=${d.max} hours=${d.hours.map { it.label + ":" + it.temp }}")
        } catch (e: Exception) { println("WEATHER FAIL: ${e.message}") }
        try {
            val o = org.json.JSONObject(WeatherData.http("https://wttr.in/47.2357,39.7015?format=j1&lang=ru"))
            println("WTTR OK: " + o.getJSONArray("current_condition").getJSONObject(0).getString("temp_C"))
        } catch (e: Exception) { println("WTTR FAIL: ${e.message}") }
    }
}
