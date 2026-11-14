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
            "ТВ" to 0xFF2C2C2E, "Galaxy" to 0xFF00C7BE
        )
        AppRepository.testApps = apps.mapIndexed { i, (n, c) ->
            AppEntry("test.app$i", n, Intent(), LetterIcon(c.toInt(), n.take(1)))
        }
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test
    fun screens() {
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle(800)
        act.window.decorView.captureRoboImage("build/shots/1_home.png")

        act.openControlCenter(); idle(800)
        act.window.decorView.captureRoboImage("build/shots/2_control_center.png")

        act.sheet.showWallpapers(); idle(800)
        act.window.decorView.captureRoboImage("build/shots/3_wallpapers.png")

        act.sheet.hide(); idle(500)
        act.setWallpaper(1)
        act.onKeyDown(android.view.KeyEvent.KEYCODE_BACK, android.view.KeyEvent(0, android.view.KeyEvent.KEYCODE_BACK)); idle(800)
        act.window.decorView.captureRoboImage("build/shots/4_home_sunset.png")
    }
}
