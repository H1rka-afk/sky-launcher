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

/**
 * group: "live" — живые (градиенты и анимированные сцены), "nature" — фото природы, "space" — космос.
 * scene: 0 — переливающийся градиент, иначе анимированная сцена [LiveScenes].
 */
class Wallpaper(
    val name: String, val base: Int, val colors: IntArray, val photo: String? = null,
    val group: String = "live", val scene: Int = 0
)

/** Загрузка фото с уменьшением под нужную ширину + небольшой кэш. */
object Photos {
    /** Кэш по объёму памяти (48 МБ), чтобы превью не декодировались заново. */
    private val cache = object : android.util.LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun cached(path: String, targetW: Int): Bitmap? = cache.get("$path@$targetW")

    /** Декодирует в фоне и возвращает результат в главный поток. */
    fun decodeAsync(ctx: Context, path: String, targetW: Int, done: (Bitmap?) -> Unit) {
        val app = ctx.applicationContext
        pool.execute { val b = decode(app, path, targetW); main.post { done(b) } }
    }

    fun decode(ctx: Context, path: String, targetW: Int): Bitmap? {
        val key = "$path@$targetW"
        cache.get(key)?.let { return it }
        return try {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.assets.open(path).use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
            var sample = 1
            while (o.outWidth / (sample * 2) >= targetW) sample *= 2
            val b = ctx.assets.open(path).use {
                android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            }
            if (b != null) cache.put(key, b)
            b
        } catch (e: Throwable) { null }
    }
}

object Wallpapers {
    private fun w(name: String, base: Long, vararg c: Long) =
        Wallpaper(name, base.toInt(), c.map { it.toInt() }.toIntArray())

    private val gradients = listOf(
        w("Аврора", 0xFF070B1F, 0xFF5E5CE6, 0xFF0A84FF, 0xFF64D2FF, 0xFFBF5AF2),
        w("Закат", 0xFF1A0820, 0xFFFF375F, 0xFFFF9F0A, 0xFFBF5AF2, 0xFFFF6B3D),
        w("Океан", 0xFF00121F, 0xFF0A84FF, 0xFF30D5C8, 0xFF5AC8FA, 0xFF1E3A8A),
        w("Лес", 0xFF04140C, 0xFF30D158, 0xFF00C7BE, 0xFF8FD14F, 0xFF0B5D3B),
        w("Графит", 0xFF0B0B0D, 0xFF48484A, 0xFF636366, 0xFF8E8E93, 0xFF2C2C2E),
        w("Персик", 0xFF2A0D14, 0xFFFF9F0A, 0xFFFFD60A, 0xFFFF375F, 0xFFFF6482),
    )

    /** Анимированные сцены — в конце списка, чтобы номера уже выбранных обоев не сдвинулись. */
    private val scenes = listOf(
        Wallpaper("Космос", 0xFF02030A.toInt(), intArrayOf(0xFF5E2CA5.toInt(), 0xFF1E3A8A.toInt(), 0xFFB0307A.toInt()), scene = LiveScenes.SPACE),
        Wallpaper("Полярное сияние", 0xFF020B12.toInt(), IntArray(0), scene = LiveScenes.AURORA),
        Wallpaper("Светлячки", 0xFF03110E.toInt(), IntArray(0), scene = LiveScenes.FIREFLIES),
        Wallpaper("Волны", 0xFF04122B.toInt(), IntArray(0), scene = LiveScenes.WAVES),
    )

    var all: List<Wallpaper> = gradients + scenes
        private set

    /** Подгружает фото природы из assets/wallpapers.json (кладутся при сборке). */
    fun load(ctx: Context) {
        if (all.size > gradients.size + scenes.size) return
        val photos = try {
            val arr = org.json.JSONArray(ctx.assets.open("wallpapers.json").bufferedReader().readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Wallpaper(o.getString("name"), 0xFF000000.toInt(), IntArray(0), o.getString("file"), o.optString("group", "nature"))
            }
        } catch (e: Exception) { emptyList() }
        // порядок: старые градиенты, фото природы, сцены, космос — индексы прежних обоев сохраняются
        all = gradients + photos.filter { it.group == "nature" } + scenes + photos.filter { it.group != "nature" }
    }

    fun get(i: Int) = all[i.coerceIn(0, all.size - 1)]
}

/**
 * Живые обои. Градиент рисуется в крошечный буфер ~15 раз в секунду,
 * а фото рисуется один раз — плавное приближение делает видеокарта (свойства View),
 * поэтому почти не нагружает процессор телевизора.
 */
class WallpaperView(ctx: Context, private val animated: Boolean = true) : View(ctx) {

    var wallpaper: Wallpaper = Wallpapers.all[0]
        set(v) { field = v; photoBmp = null; invalidate(); restartMotion() }

    private val bw = 160
    private val bh = 90
    private val buf = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
    private val bc = Canvas(buf)
    private val blob = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scale = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val shade = Paint()
    private val dst = Rect()
    private val t0 = SystemClock.uptimeMillis()
    private var running = false
    private var kenBurns: android.animation.ValueAnimator? = null

    private val live get() = animated && running && Prefs.motion && !paused

    fun start() { running = true; invalidate(); restartMotion() }

    /** Пауза движения, пока открыта папка/меню — видеочип занят только ими. */
    private var paused = false
    fun pauseMotion() { paused = true; kenBurns?.pause() }
    fun resumeMotion() { if (!paused) return; paused = false; kenBurns?.resume(); invalidate() }
    fun stop() { running = false; kenBurns?.cancel(); kenBurns = null }

    /** Медленное «дыхание» фото — только трансформации, без перерисовки. */
    private fun restartMotion() {
        kenBurns?.cancel(); kenBurns = null
        scaleX = 1f; scaleY = 1f; translationX = 0f; translationY = 0f
        if (!live || wallpaper.photo == null) return
        kenBurns = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 45_000
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                val k = 1.06f + 0.05f * f
                scaleX = k; scaleY = k
                translationX = width * 0.018f * (f * 2 - 1)
                translationY = height * 0.01f * (1 - f * 2)
            }
            start()
        }
    }

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

    private var photoBmp: Bitmap? = null
    private val m = android.graphics.Matrix()

    private var loadingPhoto: String? = null

    private fun drawPhoto(canvas: Canvas): Boolean {
        val res = wallpaper.photo ?: return false
        if (photoBmp == null) {
            if (animated) photoBmp = Photos.decode(context, res, maxOf(width, 1280))
            else {
                // превью: маленькая картинка, декодируется в фоне — меню не тормозит
                val tw = 320
                photoBmp = Photos.cached(res, tw)
                if (photoBmp == null && loadingPhoto != res) {
                    loadingPhoto = res
                    Photos.decodeAsync(context, res, tw) { b -> if (wallpaper.photo == res) { photoBmp = b; invalidate() } }
                }
            }
        }
        val b = photoBmp ?: run { canvas.drawColor(0xFF1C1C1E.toInt()); return true }
        val s = maxOf(width / b.width.toFloat(), height / b.height.toFloat())
        m.setScale(s, s)
        m.postTranslate((width - b.width * s) / 2, (height - b.height * s) / 2)
        canvas.drawBitmap(b, m, scale)
        return true
    }

    private var scene: LiveScenes? = null

    override fun onDraw(canvas: Canvas) {
        if (wallpaper.scene != 0) {
            val t = if (live) (SystemClock.uptimeMillis() - t0) / 1000f + 20f else 20f
            val sc = scene?.takeIf { it.kind == wallpaper.scene && it.w == width && it.h == height }
                ?: LiveScenes(context, wallpaper.scene, width, height, wallpaper.colors).also { scene = it }
            sc.draw(canvas, t)
            if (live) postInvalidateDelayed(40L)
        } else if (!drawPhoto(canvas)) {
            val t = if (live) (SystemClock.uptimeMillis() - t0) / 1000f + 20f else 20f
            render(t)
            canvas.drawBitmap(buf, null, dst, scale)
            if (live) postInvalidateDelayed(66L)
        }
        if (animated) canvas.drawRect(dst, shade)
    }
}
