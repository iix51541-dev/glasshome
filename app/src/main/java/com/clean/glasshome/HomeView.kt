package com.clean.glasshome

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * GlassHome 5.0: a single hardware-accelerated Canvas view.
 * The wallpaper is decoded once at one-third resolution. All cards, glass
 * panels and focus effects are drawn with cached Paint objects.
 */
class HomeView(ctx: Context) : View(ctx) {
    private var pills: List<Item> = emptyList()
    private var pillSc: Array<Sp> = emptyArray()
    private var rows: List<Row> = emptyList()
    private var fr = 0
    private var ti = 0
    private var onBar = false

    private val frAnim = Sp(0f)
    private val heroA = Sp(1f)
    private var heroItem: Item? = null
    private var heroLabel: CharSequence = ""
    private var heroSub: CharSequence = ""
    private var prevLabel: CharSequence = ""
    private var prevSub: CharSequence = ""
    private var lastFrame = 0L

    private var weather: WeatherInfo? = null
    private var weatherMessage = "Connect to Wi-Fi to refresh local conditions"

    private var bg: Bitmap? = null
    private val bgDst = Rect()
    private val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val scratch = RectF()
    private val iconRect = RectF()
    private val sysIcon = RectF()
    private val glyphPath = Path()

    private var ww = 0f
    private var hh = 0f
    private var tw = 0f
    private var th = 0f
    private var gap = 0f
    private var pitch = 0f
    private var padC = 0f
    private var margin = 0f
    private var rowPitch = 0f
    private var dockY = 0f
    private var radius = 0f
    private var strokeW = 1.5f
    private var pillSize = 0f
    private var pillGap = 0f

    private fun typeface(name: String) = Typeface.create(name, Typeface.NORMAL)
    private fun paint() = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun strokePaint() = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.style = Paint.Style.STROKE
        it.color = Color.WHITE
    }

    private val pTime = paint().also { it.color = Color.WHITE; it.typeface = typeface("sans-serif-light") }
    private val pDate = paint().also { it.color = 0xDDEAF4FF.toInt(); it.typeface = typeface("sans-serif") }
    private val pBrand = paint().also { it.color = 0xFFE4F5FF.toInt(); it.typeface = typeface("sans-serif-medium") }
    private val pEyebrow = paint().also { it.color = 0xFF91DCE9.toInt(); it.typeface = typeface("sans-serif-medium") }
    private val pTitle = paint().also { it.color = 0xFFEAF5FF.toInt(); it.typeface = typeface("sans-serif-medium") }
    private val pGreeting = TextPaint(Paint.ANTI_ALIAS_FLAG).also {
        it.color = Color.WHITE
        it.typeface = typeface("sans-serif-light")
    }
    private val heroP = TextPaint(Paint.ANTI_ALIAS_FLAG).also {
        it.color = Color.WHITE
        it.typeface = typeface("sans-serif-light")
    }
    private val heroS = TextPaint(Paint.ANTI_ALIAS_FLAG).also {
        it.color = 0xD9FFFFFF.toInt()
        it.typeface = typeface("sans-serif")
    }
    private val appNameP = TextPaint(Paint.ANTI_ALIAS_FLAG).also {
        it.color = Color.WHITE
        it.typeface = typeface("sans-serif-medium")
        it.textAlign = Paint.Align.CENTER
    }
    private val weatherLarge = TextPaint(Paint.ANTI_ALIAS_FLAG).also {
        it.color = Color.WHITE
        it.typeface = typeface("sans-serif-light")
    }
    private val weatherSmall = TextPaint(Paint.ANTI_ALIAS_FLAG).also {
        it.color = 0xDDEAF4FF.toInt()
        it.typeface = typeface("sans-serif")
    }

    private val fill = paint()
    private val scrim = paint()
    private val glassFill = paint()
    private val glassEdge = strokePaint()
    private val halo = paint()
    private val ring = strokePaint()
    private val edge = strokePaint()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val pillFill = paint()
    private val pillStroke = strokePaint()
    private val glyphStroke = strokePaint().also {
        it.strokeCap = Paint.Cap.ROUND
        it.strokeJoin = Paint.Join.ROUND
    }
    private val glyphFill = paint().also { it.color = Color.WHITE }
    private val divider = paint().also { it.color = 0x77FFFFFF }

    private val prefs = ctx.getSharedPreferences("last", Context.MODE_PRIVATE)
    private val timeFormat = DateFormat.getTimeFormat(ctx)
    private val dateFormat = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault())
    private var minute = -1L
    private var timeText = ""
    private var dateText = ""
    private var timeWidth = 0f

    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, 60000L)
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestFocus()
        postDelayed(tick, 20000L)
    }


    fun setData(data: Data) {
        val previousTitle = rows.getOrNull(fr)?.title
        pills = data.pills
        pillSc = Array(pills.size) { Sp(1f) }
        rows = data.rows
        val newIndex = rows.indexOfFirst { it.title == (previousTitle ?: "Apps") }
        fr = if (newIndex >= 0) newIndex else 0
        ti = min(ti, max(0, pills.size - 1))
        if (pills.isEmpty()) onBar = false
        heroItem = null
        invalidate()
    }

    fun setWeather(info: WeatherInfo?) {
        weather = info
        weatherMessage = if (info == null) "Weather is temporarily unavailable" else ""
        invalidate()
    }

    fun resetFocus() {
        fr = 0
        ti = 0
        onBar = false
        for (row in rows) row.idx = 0
        heroItem = null
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        super.onSizeChanged(w, h, oldW, oldH)
        ww = w.toFloat()
        hh = h.toFloat()

        // Geometry is relative to the real 16:9 display size.
        margin = ww * 0.055f
        padC = hh * 0.018f
        th = hh * 0.160f
        tw = th * 1.72f
        gap = hh * 0.023f
        pitch = tw + gap
        dockY = hh * 0.550f
        rowPitch = hh * 0.220f
        radius = th * 0.12f
        strokeW = max(1.5f, hh * 0.0017f)
        pillSize = hh * 0.045f
        pillGap = hh * 0.014f

        pTime.textSize = hh * 0.052f
        pDate.textSize = hh * 0.022f
        pBrand.textSize = hh * 0.021f
        pEyebrow.textSize = hh * 0.017f
        pTitle.textSize = hh * 0.021f
        pGreeting.textSize = hh * 0.073f
        heroP.textSize = hh * 0.050f
        heroS.textSize = hh * 0.023f
        appNameP.textSize = hh * 0.020f
        weatherLarge.textSize = hh * 0.075f
        weatherSmall.textSize = hh * 0.019f

        val iconSize = th * 0.52f
        iconRect.set(
            (tw - iconSize) / 2f, (th - iconSize) / 2f,
            (tw + iconSize) / 2f, (th + iconSize) / 2f
        )
        val systemIconSize = th * 0.27f
        sysIcon.set(th * 0.12f, th * 0.12f, th * 0.12f + systemIconSize, th * 0.12f + systemIconSize)

        val s = hh * 0.008f
        scrim.shader = LinearGradient(
            0f, 0f, 0f, hh,
            intArrayOf(0x15030A18, 0x1A030A18, 0x9D030914.toInt(), 0xD9060D1A.toInt()),
            floatArrayOf(0f, 0.34f, 0.78f, 1f),
            Shader.TileMode.CLAMP
        )
        glassFill.shader = LinearGradient(
            0f, 0f, 0f, hh * 0.30f,
            0x56FFFFFF, 0x1AFFFFFF, Shader.TileMode.CLAMP
        )
        halo.shader = RadialGradient(
            tw / 2f, th / 2f, tw * 0.72f,
            intArrayOf(0x6A82E8F5, 0x3082E8F5, 0x0082E8F5),
            floatArrayOf(0f, 0.48f, 1f), Shader.TileMode.CLAMP
        )
        glassEdge.strokeWidth = strokeW
        edge.strokeWidth = strokeW
        ring.color = 0xFFD8F5FF.toInt()
        ring.strokeWidth = strokeW * 1.55f
        divider.strokeWidth = max(1.2f, hh * 0.0014f)
        bgDst.set(0, 0, w, h)
        makeWallpaper(w, h)
        for (row in rows) for (item in row.items) {
            item.lbl = null
            item.sbl = null
            item.bp = null
        }
        minute = -1L
        invalidate()
    }

    private fun makeWallpaper(w: Int, h: Int) {
        val targetW = max(1, w / 3)
        val targetH = max(1, h / 3)
        var decoded: Bitmap? = null
        try {
            val options = BitmapFactory.Options().apply {
                inSampleSize = 2
                inPreferredConfig = Bitmap.Config.RGB_565
                inDither = true
            }
            context.assets.open("glasshome_wallpaper.jpg").use { input ->
                decoded = BitmapFactory.decodeStream(input, null, options)
            }
        } catch (_: Exception) {
            decoded = null
        }
        val source = decoded
        if (source != null) {
            bg?.recycle()
            bg = Bitmap.createScaledBitmap(source, targetW, targetH, true).also {
                if (it !== source) source.recycle()
            }
        } else {
            // Safe fallback if the wallpaper asset is missing.
            val fallback = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.RGB_565)
            val canvas = Canvas(fallback)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.shader = LinearGradient(
                0f, 0f, targetW.toFloat(), targetH.toFloat(),
                0xFF071426.toInt(), 0xFF1B2040.toInt(), Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, targetW.toFloat(), targetH.toFloat(), p)
            p.shader = RadialGradient(
                targetW * 0.68f, targetH * 0.28f, targetW * 0.65f,
                0x8053DCEB.toInt(), 0x003B4D84, Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, targetW.toFloat(), targetH.toFloat(), p)
            p.shader = null
            bg?.recycle()
            bg = fallback
        }
    }

    private fun rowAlpha(distance: Float): Float = when {
        distance <= -1f -> 0f
        distance < 0f -> 1f + distance
        distance < 1f -> 1f - 0.38f * distance
        else -> max(0f, 0.62f - 0.62f * (distance - 1f))
    }

    private fun focusedItem(): Item? {
        if (onBar) return pills.getOrNull(ti)
        val row = rows.getOrNull(fr) ?: return null
        return row.items.getOrNull(row.idx)
    }

    private fun relativeOpenedTime(time: Long): String {
        val now = System.currentTimeMillis()
        val offset = TimeZone.getDefault().getOffset(now).toLong()
        val day = 86400000L
        val difference = (now + offset) / day - (time + offset) / day
        val formattedTime = timeFormat.format(Date(time))
        return when (difference) {
            0L -> "Opened today at $formattedTime"
            1L -> "Opened yesterday at $formattedTime"
            else -> "Last opened " + SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(time))
        }
    }

    private fun subtitleFor(item: Item?): String {
        if (item == null) return "Your space for movies, shows and everything you enjoy."
        val custom = item.sub
        if (custom != null) return custom
        val packageName = item.pkg ?: return "Press OK to open"
        val openedAt = prefs.getLong(packageName, 0L)
        return if (openedAt == 0L) "Press OK to open" else relativeOpenedTime(openedAt)
    }

    private fun updateHero(item: Item?) {
        prevLabel = heroLabel
        prevSub = heroSub
        heroItem = item
        heroLabel = if (item == null) "Explore your apps" else
            TextUtils.ellipsize(item.label, heroP, ww * 0.58f, TextUtils.TruncateAt.END)
        heroSub = subtitleFor(item)
        heroA.x = 0f
        heroA.v = 0f
    }

    private fun greeting(): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when {
            hour < 12 -> "Good morning."
            hour < 18 -> "Good afternoon."
            else -> "Good evening."
        }
    }

    private fun pillCenterX(index: Int): Float =
        ww - margin - pillSize / 2f - (pills.size - 1 - index) * (pillSize + pillGap)

    private fun pillCenterY(): Float = hh * 0.080f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (ww == 0f || hh == 0f) return

        val nowNs = System.nanoTime()
        val dt = if (lastFrame == 0L) 0.016f else min((nowNs - lastFrame) / 1e9f, 0.05f)
        lastFrame = nowNs
        var animating = false

        val selected = focusedItem()
        if (selected !== heroItem) updateHero(selected)

        // Tiny focus-linked parallax. The cached bitmap is only transformed, never re-rendered.
        val focusIndex = if (onBar) ti else rows.getOrNull(fr)?.idx ?: 0
        val pan = ((focusIndex % 5) - 2) * ww * 0.0018f
        val overscanX = ww * 0.012f
        val overscanY = hh * 0.012f
        bgDst.set(
            (-overscanX + pan).toInt(), (-overscanY).toInt(),
            (ww + overscanX + pan).toInt(), (hh + overscanY).toInt()
        )
        bg?.let { canvas.drawBitmap(it, null, bgDst, bgPaint) }
        canvas.drawRect(0f, 0f, ww, hh, scrim)

        // Brand.
        pBrand.alpha = 245
        canvas.drawText("GLASSHOME", margin, hh * 0.085f, pBrand)
        pEyebrow.alpha = 210
        canvas.drawText("YOUR HOME, BEAUTIFULLY", margin, hh * 0.112f, pEyebrow)

        // Time and date use a single cached minute tick.
        val currentMs = System.currentTimeMillis()
        if (currentMs / 60000L != minute) {
            minute = currentMs / 60000L
            val date = Date(currentMs)
            timeText = timeFormat.format(date)
            dateText = dateFormat.format(date)
            timeWidth = pTime.measureText(timeText)
        }
        val quickLeft = if (pills.isNotEmpty()) pillCenterX(0) - pillSize / 2f - hh * 0.035f else ww - margin
        val dateWidth = pDate.measureText(dateText)
        val timeX = quickLeft - hh * 0.028f - dateWidth - hh * 0.025f - timeWidth
        pTime.alpha = 255
        canvas.drawText(timeText, timeX, hh * 0.091f, pTime)
        pDate.alpha = 200
        canvas.drawText(dateText, timeX + timeWidth + hh * 0.025f, hh * 0.087f, pDate)

        // Quick settings shortcuts.
        for (i in pills.indices) {
            val focused = onBar && i == ti
            if (pillSc[i].go(if (focused) 1.14f else 1f, 390f, 28f, dt)) animating = true
            drawPill(canvas, pills[i], pillCenterX(i), pillCenterY(), pillSc[i].x)
        }

        // Calm hero area; focused app information crossfades smoothly.
        pEyebrow.alpha = 220
        canvas.drawText(greeting().uppercase(Locale.getDefault()), margin, hh * 0.245f, pEyebrow)
        pGreeting.alpha = 255
        canvas.drawText("Settle in.", margin, hh * 0.335f, pGreeting)

        if (heroA.go(1f, 260f, 32f, dt)) animating = true
        val heroAlpha = (1f - frAnim.x).coerceIn(0f, 1f)
        if (heroAlpha > 0.01f) {
            val blend = heroA.x.coerceIn(0f, 1f)
            val x = margin
            val y = hh * 0.425f
            if (blend < 0.99f) {
                heroP.alpha = (245f * (1f - blend) * heroAlpha).toInt()
                canvas.drawText(prevLabel, 0, prevLabel.length, x, y, heroP)
                heroS.alpha = (190f * (1f - blend) * heroAlpha).toInt()
                canvas.drawText(prevSub, 0, prevSub.length, x, y + hh * 0.040f, heroS)
            }
            heroP.alpha = (245f * blend * heroAlpha).toInt()
            canvas.drawText(heroLabel, 0, heroLabel.length, x, y + hh * 0.012f * (1f - blend), heroP)
            heroS.alpha = (190f * blend * heroAlpha).toInt()
            canvas.drawText(heroSub, 0, heroSub.length, x, y + hh * 0.050f, heroS)
        }

        drawWeather(canvas)

        // Rows move like a soft carousel. There are no per-frame allocations.
        if (frAnim.go(fr.toFloat(), 220f, 30f, dt)) animating = true
        val viewportWidth = ww - 2f * margin - 2f * padC
        for (r in rows.indices) {
            val row = rows[r]
            val distance = r - frAnim.x
            val alpha = rowAlpha(distance)
            val maxScroll = max(0f, row.items.size * pitch - gap - viewportWidth)
            val targetScroll = min(max(0, row.idx - 1) * pitch, maxScroll)
            if (row.scroll.go(targetScroll, 220f, 30f, dt, 0.3f)) animating = true

            for (i in row.items.indices) {
                val focused = !onBar && r == fr && i == row.idx
                if (row.sc[i].go(if (focused) 1.075f else 1f, 390f, 28f, dt)) animating = true
            }
            if (alpha <= 0.01f) continue

            val y = dockY + distance * rowPitch
            pTitle.alpha = (205f * alpha).toInt()
            canvas.drawText(row.title.uppercase(Locale.getDefault()), margin + padC, y - padC - hh * 0.012f, pTitle)

            canvas.save()
            canvas.clipRect(margin + padC * 0.35f, 0f, ww - margin - padC * 0.35f, hh)
            for (i in row.items.indices) {
                val x = margin + padC - row.scroll.x + i * pitch
                if (x > ww || x + tw < 0f) continue
                drawTile(canvas, row.items[i], x, y, row.sc[i].x, alpha, !onBar && r == fr && i == row.idx)
            }
            canvas.restore()
        }

        // Subtle remote navigation hint.
        appNameP.textAlign = Paint.Align.LEFT
        appNameP.color = 0xBFEAF4FF.toInt()
        appNameP.textSize = hh * 0.015f
        appNameP.alpha = 170
        canvas.drawText("←  →  BROWSE     OK  OPEN     ↑  QUICK SETTINGS", margin, hh * 0.985f, appNameP)

        if (animating) postInvalidateOnAnimation() else lastFrame = 0L
    }

    private fun drawWeather(canvas: Canvas) {
        val left = ww * 0.705f
        val top = hh * 0.185f
        val right = ww - margin
        val bottom = hh * 0.415f
        scratch.set(left, top, right, bottom)
        glassFill.alpha = 220
        canvas.drawRoundRect(scratch, hh * 0.026f, hh * 0.026f, glassFill)
        glassEdge.alpha = 100
        glassEdge.strokeWidth = strokeW
        canvas.drawRoundRect(scratch, hh * 0.026f, hh * 0.026f, glassEdge)

        appNameP.textAlign = Paint.Align.LEFT
        appNameP.textSize = hh * 0.015f
        appNameP.color = 0xFF92DCEB.toInt()
        appNameP.alpha = 230
        canvas.drawText("LOCAL WEATHER", left + hh * 0.026f, top + hh * 0.050f, appNameP)

        val info = weather
        if (info == null) {
            weatherLarge.textSize = hh * 0.034f
            weatherLarge.alpha = 235
            canvas.drawText("Weather", left + hh * 0.026f, top + hh * 0.112f, weatherLarge)
            weatherSmall.textSize = hh * 0.017f
            weatherSmall.alpha = 185
            canvas.drawText(weatherMessage, left + hh * 0.026f, top + hh * 0.158f, weatherSmall)
            return
        }

        weatherLarge.textSize = hh * 0.075f
        weatherLarge.alpha = 255
        canvas.drawText("${info.temperature}°", left + hh * 0.026f, top + hh * 0.150f, weatherLarge)
        weatherSmall.textSize = hh * 0.019f
        weatherSmall.alpha = 235
        val city = info.city.uppercase(Locale.getDefault())
        canvas.drawText(city, left + hh * 0.155f, top + hh * 0.093f, weatherSmall)
        canvas.drawText(info.description, left + hh * 0.155f, top + hh * 0.129f, weatherSmall)
        weatherSmall.textSize = hh * 0.016f
        weatherSmall.alpha = 185
        val details = "Feels ${info.feelsLike}°  ·  Humidity ${info.humidity}%  ·  Wind ${info.windKmh} km/h"
        val fitted = TextUtils.ellipsize(details, weatherSmall, right - left - hh * 0.052f, TextUtils.TruncateAt.END)
        canvas.drawText(fitted, 0, fitted.length, left + hh * 0.026f, bottom - hh * 0.028f, weatherSmall)
    }

    private fun makeBitmapPaint(item: Item, bitmap: Bitmap): Paint {
        val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val scale = max(tw / bitmap.width, th / bitmap.height)
        val matrix = Matrix()
        matrix.setScale(scale, scale)
        matrix.postTranslate((tw - bitmap.width * scale) / 2f, (th - bitmap.height * scale) / 2f)
        shader.setLocalMatrix(matrix)
        val result = Paint(Paint.ANTI_ALIAS_FLAG).also { it.shader = shader }
        item.bp = result
        return result
    }

    private fun drawTile(
        canvas: Canvas,
        item: Item,
        x: Float,
        y: Float,
        scale: Float,
        alpha: Float,
        focused: Boolean
    ) {
        val progress = ((scale - 1f) / 0.075f).coerceIn(0f, 1f)
        canvas.save()
        canvas.translate(x + tw / 2f, y + th / 2f)
        canvas.scale(scale, scale)
        canvas.translate(-tw / 2f, -th / 2f)

        // A restrained shadow and cyan halo replace expensive real-time blur.
        fill.color = 0x66000000
        fill.alpha = (75f * alpha).toInt()
        scratch.set(0f, hh * 0.006f, tw, th + hh * 0.006f)
        canvas.drawRoundRect(scratch, radius, radius, fill)
        if (progress > 0.02f) {
            halo.alpha = (125f * progress * alpha).toInt()
            canvas.drawRoundRect(-tw * 0.13f, -th * 0.18f, tw * 1.13f, th * 1.18f, radius * 1.4f, radius * 1.4f, halo)
        }

        val imageAlpha = (255f * alpha).toInt()
        val banner = item.banner
        if (banner != null) {
            val bannerPaint = item.bp ?: makeBitmapPaint(item, banner)
            bannerPaint.alpha = imageAlpha
            canvas.drawRoundRect(0f, 0f, tw, th, radius, radius, bannerPaint)
            // Bottom fade keeps the label readable on artwork.
            val saved = fill.shader
            fill.shader = LinearGradient(
                0f, th * 0.48f, 0f, th,
                0x00000000, 0x66000000, Shader.TileMode.CLAMP
            )
            fill.alpha = imageAlpha
            canvas.drawRoundRect(0f, 0f, tw, th, radius, radius, fill)
            fill.shader = saved
        } else {
            fill.color = if (item.tile != 0) item.tile else 0x553C6580
            fill.alpha = imageAlpha
            canvas.drawRoundRect(0f, 0f, tw, th, radius, radius, fill)
            glassFill.alpha = (100f * alpha).toInt()
            canvas.drawRoundRect(0f, 0f, tw, th, radius, radius, glassFill)
            item.icon?.let {
                bitmapPaint.alpha = imageAlpha
                canvas.drawBitmap(it, null, iconRect, bitmapPaint)
            }
        }

        edge.color = 0x9CFFFFFF.toInt()
        edge.alpha = (60f * (1f - progress) * alpha).toInt()
        edge.strokeWidth = strokeW
        canvas.drawRoundRect(0f, 0f, tw, th, radius, radius, edge)
        if (focused || progress > 0.02f) {
            ring.alpha = (220f * max(progress, if (focused) 0.68f else 0f) * alpha).toInt()
            val out = ring.strokeWidth * 0.75f
            canvas.drawRoundRect(-out, -out, tw + out, th + out, radius + out, radius + out, ring)
        }

        // App name sits below the art, like a premium TV carousel.
        val label = item.lbl ?: TextUtils.ellipsize(
            item.label, appNameP, tw * 0.96f, TextUtils.TruncateAt.END
        ).also { item.lbl = it }
        appNameP.textAlign = Paint.Align.CENTER
        appNameP.textSize = hh * 0.020f
        appNameP.color = Color.WHITE
        appNameP.alpha = (if (focused) 255f else 210f * alpha).toInt()
        canvas.drawText(label, 0, label.length, tw / 2f, th + hh * 0.030f, appNameP)

        canvas.restore()
    }

    private fun drawPill(canvas: Canvas, item: Item, centerX: Float, centerY: Float, scale: Float) {
        val progress = ((scale - 1f) / 0.14f).coerceIn(0f, 1f)
        canvas.save()
        canvas.translate(centerX, centerY)
        canvas.scale(scale, scale)
        pillFill.color = 0x36FFFFFF
        pillFill.alpha = (55f + 65f * progress).toInt()
        canvas.drawCircle(0f, 0f, pillSize / 2f, pillFill)
        pillStroke.color = 0xFFE8F8FF.toInt()
        pillStroke.strokeWidth = strokeW
        pillStroke.alpha = (65f + 170f * progress).toInt()
        canvas.drawCircle(0f, 0f, pillSize / 2f, pillStroke)
        drawGlyph(canvas, item.glyph, pillSize)
        canvas.restore()
    }

    private fun drawGlyph(canvas: Canvas, glyph: Int, size: Float) {
        glyphStroke.strokeWidth = size * 0.065f
        glyphStroke.color = Color.WHITE
        when (glyph) {
            Glyph.WIFI -> {
                val offsetY = size * 0.15f
                for (i in 1..3) {
                    val r = size * 0.105f * i
                    scratch.set(-r, offsetY - r, r, offsetY + r)
                    canvas.drawArc(scratch, 225f, 90f, false, glyphStroke)
                }
                canvas.drawCircle(0f, offsetY, size * 0.045f, glyphFill)
            }
            Glyph.GEAR -> {
                canvas.drawCircle(0f, 0f, size * 0.17f, glyphStroke)
                for (i in 0 until 8) {
                    val angle = i * 0.7853982f
                    val cx = cos(angle)
                    val sy = sin(angle)
                    canvas.drawLine(cx * size * 0.18f, sy * size * 0.18f, cx * size * 0.29f, sy * size * 0.29f, glyphStroke)
                }
            }
            Glyph.BT -> {
                glyphPath.reset()
                glyphPath.moveTo(-0.17f * size, -0.17f * size)
                glyphPath.lineTo(0.17f * size, 0.17f * size)
                glyphPath.lineTo(0f, 0.30f * size)
                glyphPath.lineTo(0f, -0.30f * size)
                glyphPath.lineTo(0.17f * size, -0.17f * size)
                glyphPath.lineTo(-0.17f * size, 0.17f * size)
                canvas.drawPath(glyphPath, glyphStroke)
            }
            Glyph.DISPLAY -> {
                scratch.set(-size * 0.28f, -size * 0.22f, size * 0.28f, size * 0.18f)
                canvas.drawRoundRect(scratch, size * 0.045f, size * 0.045f, glyphStroke)
                canvas.drawLine(0f, size * 0.18f, 0f, size * 0.29f, glyphStroke)
                canvas.drawLine(-size * 0.16f, size * 0.29f, size * 0.16f, size * 0.29f, glyphStroke)
            }
            Glyph.SOUND -> {
                glyphPath.reset()
                glyphPath.moveTo(-size * 0.27f, -size * 0.10f)
                glyphPath.lineTo(-size * 0.12f, -size * 0.10f)
                glyphPath.lineTo(size * 0.06f, -size * 0.25f)
                glyphPath.lineTo(size * 0.06f, size * 0.25f)
                glyphPath.lineTo(-size * 0.12f, size * 0.10f)
                glyphPath.lineTo(-size * 0.27f, size * 0.10f)
                glyphPath.close()
                canvas.drawPath(glyphPath, glyphStroke)
                scratch.set(-size * 0.14f, -size * 0.34f, size * 0.50f, size * 0.34f)
                canvas.drawArc(scratch, -50f, 100f, false, glyphStroke)
            }
        }
    }

    private fun open(item: Item) {
        item.pkg?.let { prefs.edit().putLong(it, System.currentTimeMillis()).apply() }
        try {
            context.startActivity(Intent(item.intent))
        } catch (_: Exception) {
            Toast.makeText(context, "Can't open ${item.label}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (rows.isEmpty() && pills.isEmpty()) return super.onKeyDown(keyCode, event)
        val row = rows.getOrNull(fr)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (onBar) {
                    if (ti < pills.size - 1) ti++
                } else if (row != null && row.idx < row.items.size - 1) {
                    row.idx++
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (onBar) {
                    if (ti > 0) ti--
                } else if (row != null && row.idx > 0) {
                    row.idx--
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (onBar) onBar = false
                else if (fr < rows.size - 1) fr++
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (!onBar) {
                    if (fr > 0) fr--
                    else if (pills.isNotEmpty()) onBar = true
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A -> {
                if (event.repeatCount == 0) focusedItem()?.let { open(it) }
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        invalidate()
        return true
    }

    /** Air-mouse remotes can tap a quick setting or an app card. */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        for (i in pills.indices) {
            val dx = event.x - pillCenterX(i)
            val dy = event.y - pillCenterY()
            if (dx * dx + dy * dy <= pillSize * pillSize * 0.36f) {
                onBar = true
                ti = i
                invalidate()
                open(pills[i])
                return true
            }
        }
        for (r in rows.indices) {
            val row = rows[r]
            val distance = r - frAnim.x
            if (rowAlpha(distance) < 0.3f) continue
            val y = dockY + distance * rowPitch
            if (event.y < y || event.y > y + th + hh * 0.045f) continue
            for (i in row.items.indices) {
                val x = margin + padC - row.scroll.x + i * pitch
                if (event.x >= x && event.x <= x + tw) {
                    onBar = false
                    fr = r
                    row.idx = i
                    invalidate()
                    open(row.items[i])
                    return true
                }
            }
        }
        return true
    }

    fun release() {
        removeCallbacks(tick)
        bg?.recycle()
        bg = null
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }
}
