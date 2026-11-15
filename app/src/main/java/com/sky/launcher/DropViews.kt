package com.sky.launcher

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Outline
import android.media.ExifInterface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.VideoView
import java.io.File

/** Загрузка фото с телефона: уменьшение под экран + поворот по EXIF (айфон пишет фото «боком»). */
object DropImages {
    fun load(f: File, maxSide: Int): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
        val deg = try {
            when (ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (e: Exception) { 0f }
        if (b != null && deg != 0f) Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg) }, true) else b
    } catch (e: Throwable) { null }
}

/** «iPhone хочет отправить…» — Принять / Отклонить, поверх любого приложения. */
class DropActivity : Activity() {
    private var batchId = 0
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { SkyDrop.decline(this, batchId); finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        batchId = intent.getIntExtra("batch", 0)
        val b = SkyDrop.pending[batchId] ?: run { finish(); return }

        val root = FrameLayout(this).apply { setBackgroundColor(0x99000000.toInt()) }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = Ui.round(this@DropActivity, 26f, 0xF22C2C2E.toInt(), 0x24FFFFFF)
            setPadding(dpi(22), dpi(22), dpi(22), dpi(16))
        }
        // превью
        val first = b.items.first()
        val preview: View = when {
            first.kind == "image" && first.file != null -> ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(DropImages.load(first.file, 600))
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(14f)) }
                }
                clipToOutline = true
            }
            else -> FrameLayout(this).apply {
                background = Ui.round(this@DropActivity, 14f, 0xFF3A3A3C.toInt(), 0)
                addView(ImageView(this@DropActivity).apply {
                    setImageResource(when (first.kind) { "video" -> R.drawable.ic_tv; "text" -> R.drawable.ic_text; else -> R.drawable.ic_storage })
                }, FrameLayout.LayoutParams(dpi(40), dpi(40), Gravity.CENTER))
            }
        }
        card.addView(preview, LinearLayout.LayoutParams(dpi(200), dpi(130)))
        val count = b.items.size
        val what = when {
            b.items.all { it.kind == "image" } -> if (count == 1) "фото" else "$count фото"
            b.items.all { it.kind == "video" } -> if (count == 1) "видео" else "$count видео"
            b.items.all { it.kind == "text" } -> if ((b.items[0].text ?: "").startsWith("http")) "ссылку" else "текст"
            else -> "$count файл(а)"
        }
        card.addView(Ui.text(this, 13f, 0xFF8E8E93.toInt(), Ui.medium, false).apply { text = "SKY DROP"; letterSpacing = 0.1f },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(14) })
        card.addView(Ui.text(this, 18f, Color.WHITE, Ui.bold, false).apply { text = "${b.from} хочет отправить $what"; gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(4) })
        val sub = if (first.kind == "text") (first.text ?: "").take(120) else b.items.joinToString(", ") { it.name }.take(90)
        card.addView(Ui.text(this, 13f, 0xCCFFFFFF.toInt(), Ui.regular, false).apply { text = sub; gravity = Gravity.CENTER; maxLines = 3 },
            LinearLayout.LayoutParams(dpi(320), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(6) })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(label: String, color: Int, fill: Int, action: () -> Unit) = Ui.text(this, 15f, color, Ui.medium, false).apply {
            text = label; gravity = Gravity.CENTER; isFocusable = true; isClickable = true
            background = Ui.round(this@DropActivity, 14f, fill, 0)
            Ui.zoomOnFocus(this, 1.06f) { f -> background = Ui.round(this@DropActivity, 14f, fill, if (f) Color.WHITE else 0, 2.5f) }
            setOnClickListener { handler.removeCallbacks(timeout); action(); finish() }
        }
        val no = btn("Отклонить", Color.WHITE, 0xFF3A3A3C.toInt()) { SkyDrop.decline(this, batchId) }
        val yes = btn("Принять", Color.WHITE, Ui.BLUE) { SkyDrop.accept(applicationContext, batchId) }
        buttons.addView(no, LinearLayout.LayoutParams(dpi(150), dpi(44)))
        buttons.addView(yes, LinearLayout.LayoutParams(dpi(150), dpi(44)).apply { marginStart = dpi(12) })
        card.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(18) })
        root.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setContentView(root)
        yes.requestFocus()
        card.alpha = 0f; card.scaleX = 0.92f; card.scaleY = 0.92f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start()
        handler.postDelayed(timeout, 60_000)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { handler.removeCallbacks(timeout); SkyDrop.decline(this, batchId); finish(); return true }
        return super.onKeyDown(keyCode, event)
    }
}

/** Просмотр присланных фото и видео: ◀ ▶ — листать, OK — пауза видео, Назад — закрыть. */
class ViewerActivity : Activity() {
    companion object { var items: List<SkyDrop.Item> = emptyList() }

    private var i = 0
    private lateinit var image: ImageView
    private lateinit var video: VideoView
    private lateinit var counter: TextView
    private lateinit var title: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        video = VideoView(this)
        root.addView(video, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        root.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Ui.round(this@ViewerActivity, 18f, 0x99000000.toInt(), 0)
            setPadding(dpi(14), dpi(7), dpi(14), dpi(7))
        }
        title = Ui.text(this, 13f, Color.WHITE, Ui.medium); counter = Ui.text(this, 13f, 0xB3FFFFFF.toInt(), Ui.regular)
        top.addView(title); top.addView(counter, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dpi(10) })
        root.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dpi(14) })
        setContentView(root)
        show()
    }

    private fun show() {
        val it = items.getOrNull(i) ?: run { finish(); return }
        title.text = "Sky Drop · ${it.name}"
        counter.text = if (items.size > 1) "${i + 1} из ${items.size}" else ""
        if (it.kind == "video" && it.file != null) {
            image.visibility = View.GONE; video.visibility = View.VISIBLE
            video.setVideoPath(it.file.path); video.setOnPreparedListener { mp -> mp.isLooping = false; video.start() }
        } else {
            video.stopPlayback(); video.visibility = View.GONE; image.visibility = View.VISIBLE
            image.setImageBitmap(it.file?.let { f -> DropImages.load(f, 1920) })
            image.alpha = 0f; image.animate().alpha(1f).setDuration(250).start()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (i < items.size - 1) { i++; show() }; return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (i > 0) { i--; show() }; return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (video.visibility == View.VISIBLE) { if (video.isPlaying) video.pause() else video.start() }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() { super.onPause(); video.pause() }
}
