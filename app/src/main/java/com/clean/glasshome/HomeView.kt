package com.clean.glasshome

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
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
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.cos

/**
 * GlassHome 5.2 — a one-screen TV launcher inspired by the user's reference:
 * full-bleed warm wallpaper, compact controls, a featured app panel and one
 * horizontal app dock. Motion is short, event-driven and never runs continuously.
 */
class HomeView(ctx: Context, private val onWallpaperRequest: () -> Unit = {}) : View(ctx) {
    companion object {
        private const val ZONE_DOCK = 0
        private const val ZONE_FEATURE = 1
        private const val ZONE_QUICK = 2
    }

    private var pills: List<Item> = emptyList()
    private var apps: List<Item> = emptyList()
    private var quickRects: Array<RectF> = emptyArray()
    private var dockRects: Array<RectF> = emptyArray()
    private var focusZone = ZONE_DOCK
    private var appIndex = 0
    private var quickIndex = 0
    private var dockScroll = 0f
    private var focusProgress = 1f
    private var previousFeatured: Item? = null
    private var weather: WeatherInfo? = null

    private var focusAnimator: ValueAnimator? = null
    private var scrollAnimator: ValueAnimator? = null
    private var wallpaperAnimator: ValueAnimator? = null
    private var wallpaperAlpha = 1f

    private var bg: Bitmap? = null
    private var customWallpaper = false
    private val bgSrc = Rect()
    private val bgDst = Rect()
    private val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val wallpaperScrim = Paint(Paint.ANTI_ALIAS_FLAG)
    private val topScrim = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cardFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cardEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x88FFFFFF.toInt()
    }
    private val focusStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFFFB45C.toInt()
    }
    private val softStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x55FFFFFF
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glyphStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }
    private val glyphFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val path = Path()
    private val workRect = RectF()
    private val bitmapSrcRect = Rect()
    private val tileShadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var focusHaloShader: Shader? = null
    private val coverRect = RectF()
    private val featureButton = RectF()
    private val dockViewport = RectF()
    private val dockFrame = RectF()

    private var ww = 0f
    private var hh = 0f
    private var margin = 0f
    private var controlW = 0f
    private var controlH = 0f
    private var controlGap = 0f
    private var tileW = 0f
    private var tileH = 0f
    private var tileGap = 0f
    private var tilePitch = 0f
    private var tileRadius = 0f
    private var coverSize = 0f
    private var textLeft = 0f
    private var featureTop = 0f
    private var dockTop = 0f

    private fun createTypeface(name: String, weight: Int = Typeface.BOLD) = Typeface.create(name, weight)
    private fun makeText(color: Int, face: String = "sans-serif", weight: Int = Typeface.NORMAL) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; typeface = createTypeface(face, weight) }

    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = createTypeface("sans-serif", Typeface.BOLD) }
    private val datePaint = makeText(0xE8FFFFFF.toInt(), "sans-serif-medium", Typeface.NORMAL)
    private val weatherPaint = makeText(0xCFFFFFFF.toInt(), "sans-serif-medium", Typeface.NORMAL)
    private val eyebrowPaint = makeText(0xFFFFC078.toInt(), "sans-serif", Typeface.BOLD)
    private val titlePaint = makeText(Color.WHITE, "sans-serif", Typeface.BOLD)
    private val subtitlePaint = makeText(0xE6FFFFFF.toInt(), "sans-serif", Typeface.NORMAL)
    private val buttonPaint = makeText(0xFF21130A.toInt(), "sans-serif", Typeface.BOLD)
    private val quickTextPaint = makeText(Color.WHITE, "sans-serif-medium", Typeface.NORMAL)
    private val appLabelPaint = makeText(0xEFFFFFFF.toInt(), "sans-serif-medium", Typeface.NORMAL).apply { textAlign = Paint.Align.CENTER }
    private val hintPaint = makeText(0xBFFFFFFF.toInt(), "sans-serif-medium", Typeface.NORMAL)

    private val prefs = ctx.getSharedPreferences("last", Context.MODE_PRIVATE)
    private val timeFormat = DateFormat.getTimeFormat(ctx)
    private val dateFormat = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault())
    private var minute = -1L
    private var timeText = ""
    private var dateText = ""

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
        animateFocus()
    }

    fun setData(data: Data) {
        val oldPackage = apps.getOrNull(appIndex)?.pkg
        pills = data.pills
        apps = data.rows.firstOrNull()?.items ?: emptyList()
        quickRects = Array(pills.size) { RectF() }
        dockRects = Array(apps.size) { RectF() }
        val preserved = apps.indexOfFirst { it.pkg != null && it.pkg == oldPackage }
        appIndex = if (preserved >= 0) preserved else appIndex.coerceIn(0, max(0, apps.size - 1))
        quickIndex = quickIndex.coerceIn(0, max(0, pills.size - 1))
        for (item in apps) {
            item.bp = null
            item.lbl = null
        }
        updateDockScroll(false)
        invalidate()
    }

    fun setWallpaper(bitmap: Bitmap) {
        val old = bg
        bg = bitmap
        customWallpaper = true
        if (old != null && old !== bitmap && !old.isRecycled) old.recycle()
        wallpaperAnimator?.cancel()
        wallpaperAlpha = 0f
        wallpaperAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                wallpaperAlpha = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun setWeather(info: WeatherInfo?) {
        weather = info
        invalidate()
    }

    fun resetFocus() {
        focusZone = ZONE_DOCK
        appIndex = 0
        quickIndex = 0
        previousFeatured = null
        updateDockScroll(false)
        animateFocus()
    }

    fun returnToDock() {
        focusZone = ZONE_DOCK
        animateFocus()
    }

    fun animateReturn() {
        previousFeatured = null
        animateFocus()
    }

    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        super.onSizeChanged(w, h, oldW, oldH)
        ww = w.toFloat()
        hh = h.toFloat()
        margin = ww * 0.058f
        controlW = hh * 0.145f
        controlH = hh * 0.055f
        controlGap = hh * 0.012f
        tileW = min(ww * 0.14f, hh * 0.255f)
        tileH = hh * 0.105f
        tileGap = ww * 0.018f
        tilePitch = tileW + tileGap
        tileRadius = hh * 0.018f
        coverSize = hh * 0.178f
        featureTop = hh * 0.355f
        dockTop = hh * 0.715f

        timePaint.textSize = hh * 0.047f
        datePaint.textSize = hh * 0.023f
        weatherPaint.textSize = hh * 0.018f
        eyebrowPaint.textSize = hh * 0.018f
        titlePaint.textSize = hh * 0.044f
        subtitlePaint.textSize = hh * 0.022f
        buttonPaint.textSize = hh * 0.018f
        quickTextPaint.textSize = hh * 0.0155f
        appLabelPaint.textSize = hh * 0.018f
        hintPaint.textSize = hh * 0.014f

        cardEdge.strokeWidth = max(1f, hh * 0.0016f)
        softStroke.strokeWidth = max(1f, hh * 0.0014f)
        focusStroke.strokeWidth = max(2f, hh * 0.003f)
        glyphStroke.strokeWidth = max(2f, hh * 0.002f)
        tileShadePaint.shader = LinearGradient(
            0f, tileH * 0.28f, 0f, tileH,
            0x00000000, 0x55000000, Shader.TileMode.CLAMP
        )
        focusHaloShader = RadialGradient(
            0f, 0f, tileW * 0.82f,
            intArrayOf(0x5EFFA94D, 0x24FF8C32, 0x00FF8C32),
            floatArrayOf(0f, 0.57f, 1f), Shader.TileMode.CLAMP
        )

        wallpaperScrim.shader = LinearGradient(
            0f, 0f, 0f, hh,
            intArrayOf(0x22000000, 0x18000000, 0x5A080402, 0xC9080503.toInt()),
            floatArrayOf(0f, 0.34f, 0.68f, 1f), Shader.TileMode.CLAMP
        )
        topScrim.shader = LinearGradient(
            0f, 0f, 0f, hh * 0.24f,
            intArrayOf(0x65000000, 0x00000000), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        if (!customWallpaper) makeDefaultWallpaper()
        updateDockScroll(false)
        updateQuickRects()
        invalidate()
    }

    private fun makeDefaultWallpaper() {
        val options = BitmapFactory.Options().apply {
            inSampleSize = 2
            inPreferredConfig = Bitmap.Config.RGB_565
            inDither = true
        }
        val decoded = try {
            context.assets.open("glasshome_wallpaper.jpg").use { BitmapFactory.decodeStream(it, null, options) }
        } catch (_: Exception) { null }
        if (decoded != null) {
            val old = bg
            bg = decoded
            if (old != null && old !== decoded && !old.isRecycled) old.recycle()
        }
    }

    private fun updateQuickRects() {
        if (ww <= 0f || hh <= 0f) return
        val total = pills.size * controlW + max(0, pills.size - 1) * controlGap
        val left = ww - margin - total
        for (i in pills.indices) {
            val x = left + i * (controlW + controlGap)
            quickRects.getOrNull(i)?.set(x, hh * 0.052f, x + controlW, hh * 0.052f + controlH)
        }
    }

    private fun currentApp(): Item? = apps.getOrNull(appIndex)

    private fun animateFocus() {
        focusAnimator?.cancel()
        focusProgress = 0f
        focusAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 210L
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener {
                focusProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun updateDockScroll(animate: Boolean) {
        if (ww <= 0f) return
        val left = margin * 0.78f
        val right = ww - margin * 0.78f
        val viewportWidth = right - left - hh * 0.05f
        val visible = max(1, ((viewportWidth + tileGap) / max(1f, tilePitch)).toInt())
        val center = visible / 2
        val maxScroll = max(0f, apps.size * tilePitch - tileGap - viewportWidth)
        val target = ((appIndex - center).coerceAtLeast(0) * tilePitch).coerceAtMost(maxScroll)
        if (!animate || kotlin.math.abs(target - dockScroll) < 0.5f) {
            scrollAnimator?.cancel()
            dockScroll = target
            return
        }
        scrollAnimator?.cancel()
        scrollAnimator = ValueAnimator.ofFloat(dockScroll, target).apply {
            duration = 220L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                dockScroll = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun changeApp(index: Int) {
        if (apps.isEmpty()) return
        val next = index.coerceIn(0, apps.lastIndex)
        if (next == appIndex) return
        previousFeatured = currentApp()
        appIndex = next
        animateFocus()
        updateDockScroll(true)
    }

    private fun changeQuick(index: Int) {
        if (pills.isEmpty()) return
        val next = index.coerceIn(0, pills.lastIndex)
        if (next == quickIndex) return
        quickIndex = next
        animateFocus()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (ww <= 0f || hh <= 0f) return

        drawWallpaper(canvas)
        canvas.drawRect(0f, 0f, ww, hh, topScrim)
        drawTopBar(canvas)

        val selected = currentApp()
        if (previousFeatured != null && focusProgress < 1f) {
            drawFeatured(canvas, previousFeatured, 1f - focusProgress, -hh * 0.012f * focusProgress)
            drawFeatured(canvas, selected, focusProgress, hh * 0.012f * (1f - focusProgress))
        } else {
            drawFeatured(canvas, selected, 1f, 0f)
        }
        if (focusProgress >= 0.995f) previousFeatured = null
        drawDock(canvas)
        drawNavigationHint(canvas)
    }

    private fun drawWallpaper(canvas: Canvas) {
        val bitmap = bg
        if (bitmap != null && !bitmap.isRecycled) {
            val bitmapRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
            val viewRatio = ww / hh
            if (bitmapRatio > viewRatio) {
                val cropW = (bitmap.height * viewRatio).toInt().coerceAtLeast(1)
                val left = (bitmap.width - cropW) / 2
                bgSrc.set(left, 0, left + cropW, bitmap.height)
            } else {
                val cropH = (bitmap.width / viewRatio).toInt().coerceAtLeast(1)
                val top = (bitmap.height - cropH) / 2
                bgSrc.set(0, top, bitmap.width, top + cropH)
            }
            bgDst.set(0, 0, ww.toInt(), hh.toInt())
            bgPaint.alpha = (255f * wallpaperAlpha).toInt().coerceIn(0, 255)
            canvas.drawBitmap(bitmap, bgSrc, bgDst, bgPaint)
            bgPaint.alpha = 255
        } else {
            canvas.drawColor(0xFF25100C.toInt())
        }
        canvas.drawRect(0f, 0f, ww, hh, wallpaperScrim)
    }

    private fun drawTopBar(canvas: Canvas) {
        val now = System.currentTimeMillis()
        if (now / 60000L != minute) {
            minute = now / 60000L
            val date = Date(now)
            timeText = timeFormat.format(date)
            dateText = dateFormat.format(date)
        }
        val topY = hh * 0.102f
        timePaint.alpha = 255
        canvas.drawText(timeText, margin, topY, timePaint)
        val timeWidth = timePaint.measureText(timeText)
        val sepX = margin + timeWidth + hh * 0.022f
        shade.color = 0xA6FFFFFF.toInt()
        shade.strokeWidth = max(1f, hh * 0.0014f)
        canvas.drawLine(sepX, hh * 0.067f, sepX, hh * 0.106f, shade)
        datePaint.alpha = 225
        val dateX = sepX + hh * 0.022f
        canvas.drawText(dateText, dateX, topY, datePaint)

        weather?.let { info ->
            val weatherX = dateX + datePaint.measureText(dateText) + hh * 0.028f
            weatherPaint.alpha = 190
            canvas.drawText("·  ${info.temperature}°  ${info.city}", weatherX, topY, weatherPaint)
        }

        updateQuickRects()
        for (i in pills.indices) drawQuickControl(canvas, pills[i], quickRects[i], i == quickIndex && focusZone == ZONE_QUICK)
    }

    private fun drawQuickControl(canvas: Canvas, item: Item, rect: RectF, focused: Boolean) {
        val scale = if (focused) 1f + 0.035f * focusProgress else 1f
        canvas.save()
        canvas.translate(rect.centerX(), rect.centerY())
        canvas.scale(scale, scale)
        val halfW = rect.width() / 2f
        val halfH = rect.height() / 2f
        workRect.set(-halfW, -halfH, halfW, halfH)
        cardFill.color = if (focused) 0x66FFB14B else 0x3AFFFFFF
        canvas.drawRoundRect(workRect, halfH, halfH, cardFill)
        softStroke.alpha = if (focused) 0 else 95
        canvas.drawRoundRect(workRect, halfH, halfH, softStroke)
        if (focused) {
            focusStroke.alpha = (255f * focusProgress).toInt()
            canvas.drawRoundRect(workRect, halfH, halfH, focusStroke)
        }
        val iconSize = halfH * 0.84f
        val iconX = -halfW + halfH * 0.92f
        drawGlyph(canvas, item.glyph, iconX, 0f, iconSize)
        quickTextPaint.alpha = 255
        val labelX = -halfW + halfH * 1.65f
        val baseline = quickTextPaint.textSize * 0.36f
        val fitted = item.lbl ?: TextUtils.ellipsize(item.label, quickTextPaint, halfW * 1.62f, TextUtils.TruncateAt.END)
            .also { item.lbl = it }
        canvas.drawText(fitted, 0, fitted.length, labelX, baseline, quickTextPaint)
        canvas.restore()
    }

    private fun drawFeatured(canvas: Canvas, item: Item?, alpha: Float, offsetY: Float) {
        val y = featureTop + offsetY
        val recentlyOpened = item?.pkg?.let { prefs.getLong(it, 0L) > 0L } == true
        eyebrowPaint.alpha = (245f * alpha).toInt()
        val heading = when {
            item == null -> "WELCOME HOME"
            recentlyOpened -> "PICK UP WHERE YOU LEFT OFF"
            else -> "QUICK LAUNCH"
        }
        canvas.drawText(heading, margin, y - hh * 0.095f, eyebrowPaint)

        coverRect.set(margin, y, margin + coverSize, y + coverSize)
        cardFill.color = if (item != null) (item.tile or 0xFF000000.toInt()) else 0xFF492416.toInt()
        cardFill.alpha = (245f * alpha).toInt()
        canvas.drawRoundRect(coverRect, hh * 0.016f, hh * 0.016f, cardFill)

        if (item != null) {
            val art = item.icon
            val banner = item.banner
            if (art != null && !art.isRecycled) {
                val inset = coverSize * 0.17f
                workRect.set(coverRect.left + inset, coverRect.top + inset, coverRect.right - inset, coverRect.bottom - inset)
                iconPaint.alpha = (255f * alpha).toInt()
                canvas.drawBitmap(art, null, workRect, iconPaint)
            } else if (banner != null && !banner.isRecycled) {
                drawBitmapCrop(canvas, banner, coverRect, alpha)
            }
        } else {
            drawGlyph(canvas, Glyph.WALLPAPER, coverRect.centerX(), coverRect.centerY(), coverSize * 0.48f)
        }
        cardEdge.alpha = (110f * alpha).toInt()
        canvas.drawRoundRect(coverRect, hh * 0.016f, hh * 0.016f, cardEdge)

        textLeft = coverRect.right + hh * 0.034f
        val baseTitle = item?.label ?: "Your entertainment, in one place"
        val title = TextUtils.ellipsize(baseTitle, titlePaint, ww * 0.49f, TextUtils.TruncateAt.END)
        titlePaint.alpha = (255f * alpha).toInt()
        canvas.drawText(title, 0, title.length, textLeft, y + hh * 0.066f, titlePaint)

        val subtitle = if (item == null) "Choose an app from the dock below" else {
            val opened = item.pkg?.let { prefs.getLong(it, 0L) } ?: 0L
            if (opened > 0L) "Recently opened  ·  Ready when you are" else "Ready to launch  ·  Select OPEN to continue"
        }
        subtitlePaint.alpha = (225f * alpha).toInt()
        canvas.drawText(subtitle, textLeft, y + hh * 0.105f, subtitlePaint)

        val buttonTop = y + hh * 0.132f
        if (item != null) {
            featureButton.set(textLeft, buttonTop, textLeft + hh * 0.165f, buttonTop + hh * 0.053f)
            cardFill.color = 0xFFFFB65E.toInt()
            cardFill.alpha = (255f * alpha).toInt()
            canvas.drawRoundRect(featureButton, featureButton.height() / 2f, featureButton.height() / 2f, cardFill)
            drawPlayTriangle(canvas, featureButton.left + featureButton.height() * 0.48f, featureButton.centerY(), featureButton.height() * 0.22f, alpha)
            buttonPaint.alpha = (255f * alpha).toInt()
            canvas.drawText("OPEN APP", featureButton.left + featureButton.height() * 0.88f, featureButton.centerY() + buttonPaint.textSize * 0.34f, buttonPaint)
        } else {
            featureButton.setEmpty()
        }
    }

    private fun drawDock(canvas: Canvas) {
        val left = margin * 0.78f
        val right = ww - margin * 0.78f
        val top = dockTop - hh * 0.012f
        val bottom = hh * 0.914f
        dockFrame.set(left, top, right, bottom)
        cardFill.color = 0x2BFFFFFF
        canvas.drawRoundRect(dockFrame, hh * 0.035f, hh * 0.035f, cardFill)
        cardEdge.alpha = 105
        canvas.drawRoundRect(dockFrame, hh * 0.035f, hh * 0.035f, cardEdge)

        dockViewport.set(left + hh * 0.025f, top, right - hh * 0.025f, bottom)
        val tileTop = top + hh * 0.014f
        val tileBottom = tileTop + tileH
        for (i in apps.indices) {
            val x = dockViewport.left + i * tilePitch - dockScroll
            val rect = dockRects.getOrNull(i) ?: continue
            rect.set(x, tileTop, x + tileW, tileBottom)
            if (rect.right < dockViewport.left || rect.left > dockViewport.right) continue
            val focused = focusZone == ZONE_DOCK && i == appIndex
            drawAppTile(canvas, apps[i], rect, focused)
        }
        if (apps.isEmpty()) {
            appLabelPaint.alpha = 220
            canvas.drawText("No TV apps found", dockFrame.centerX(), dockFrame.centerY(), appLabelPaint)
        }
    }

    private fun drawAppTile(canvas: Canvas, item: Item, rect: RectF, focused: Boolean) {
        val scale = if (focused) 1f + 0.055f * focusProgress else 1f
        canvas.save()
        canvas.translate(rect.centerX(), rect.centerY())
        canvas.scale(scale, scale)
        val halfW = rect.width() / 2f
        val halfH = rect.height() / 2f
        workRect.set(-halfW, -halfH, halfW, halfH)

        if (focused) {
            glowPaint.shader = focusHaloShader
            canvas.drawRoundRect(-halfW - hh * 0.018f, -halfH - hh * 0.018f,
                halfW + hh * 0.018f, halfH + hh * 0.018f, tileRadius * 1.6f, tileRadius * 1.6f, glowPaint)
        }

        cardFill.color = if (item.tile != 0) item.tile else 0xFF26201C.toInt()
        cardFill.alpha = 255
        canvas.drawRoundRect(workRect, tileRadius, tileRadius, cardFill)
        val banner = item.banner
        if (banner != null && !banner.isRecycled) {
            drawBitmapCrop(canvas, banner, workRect, 1f)
            // Precomputed lower-edge shade; no per-frame shader allocation.
            canvas.drawRoundRect(workRect, tileRadius, tileRadius, tileShadePaint)
        } else {
            val icon = item.icon
            if (icon != null && !icon.isRecycled) {
                val side = min(rect.width(), rect.height()) * 0.64f
                workRect.set(-side / 2f, -side / 2f, side / 2f, side / 2f)
                iconPaint.alpha = 255
                canvas.drawBitmap(icon, null, workRect, iconPaint)
            }
        }
        if (focused) {
            focusStroke.alpha = (255f * focusProgress).toInt()
            canvas.drawRoundRect(-halfW, -halfH, halfW, halfH, tileRadius, tileRadius, focusStroke)
            focusStroke.alpha = (95f * focusProgress).toInt()
            canvas.drawRoundRect(-halfW - hh * 0.004f, -halfH - hh * 0.004f,
                halfW + hh * 0.004f, halfH + hh * 0.004f, tileRadius * 1.2f, tileRadius * 1.2f, focusStroke)
        } else {
            softStroke.alpha = 75
            canvas.drawRoundRect(-halfW, -halfH, halfW, halfH, tileRadius, tileRadius, softStroke)
        }
        canvas.restore()

        val label = item.lbl ?: TextUtils.ellipsize(item.label, appLabelPaint, rect.width() * 1.08f, TextUtils.TruncateAt.END)
            .also { item.lbl = it }
        appLabelPaint.alpha = if (focused) 255 else 205
        canvas.drawText(label, 0, label.length, rect.centerX(), rect.bottom + hh * 0.036f, appLabelPaint)
    }

    private fun drawBitmapCrop(canvas: Canvas, bitmap: Bitmap, target: RectF, alpha: Float) {
        val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val targetRatio = target.width() / target.height()
        if (ratio > targetRatio) {
            val cropW = (bitmap.height * targetRatio).toInt().coerceAtLeast(1)
            val left = (bitmap.width - cropW) / 2
            bitmapSrcRect.set(left, 0, left + cropW, bitmap.height)
        } else {
            val cropH = (bitmap.width / targetRatio).toInt().coerceAtLeast(1)
            val top = (bitmap.height - cropH) / 2
            bitmapSrcRect.set(0, top, bitmap.width, top + cropH)
        }
        iconPaint.alpha = (255f * alpha).toInt().coerceIn(0, 255)
        canvas.drawBitmap(bitmap, bitmapSrcRect, target, iconPaint)
        iconPaint.alpha = 255
    }

    private fun drawPlayTriangle(canvas: Canvas, cx: Float, cy: Float, size: Float, alpha: Float) {
        path.reset()
        path.moveTo(cx - size * 0.48f, cy - size)
        path.lineTo(cx + size * 0.8f, cy)
        path.lineTo(cx - size * 0.48f, cy + size)
        path.close()
        glyphFill.color = 0xFF24170D.toInt()
        glyphFill.alpha = (255f * alpha).toInt()
        canvas.drawPath(path, glyphFill)
    }

    private fun drawNavigationHint(canvas: Canvas) {
        hintPaint.alpha = 175
        val hint = "◀  ▶  BROWSE     OK  OPEN     ▲  TOP CONTROLS"
        val y = hh * 0.972f
        canvas.drawText(hint, margin, y, hintPaint)
    }

    private fun drawGlyph(canvas: Canvas, glyph: Int, cx: Float, cy: Float, size: Float) {
        glyphFill.color = Color.WHITE
        glyphFill.alpha = 255
        canvas.save()
        canvas.translate(cx, cy)
        glyphStroke.strokeWidth = max(1.6f, size * 0.075f)
        glyphStroke.color = Color.WHITE
        when (glyph) {
            Glyph.WIFI -> {
                for (i in 1..3) {
                    val r = size * 0.14f * i
                    workRect.set(-r, -r * 0.35f, r, r * 1.65f)
                    canvas.drawArc(workRect, 225f, 90f, false, glyphStroke)
                }
                canvas.drawCircle(0f, size * 0.36f, size * 0.07f, glyphFill)
            }
            Glyph.SETTINGS -> {
                canvas.drawCircle(0f, 0f, size * 0.23f, glyphStroke)
                for (i in 0 until 8) {
                    val a = i * 0.7853982f
                    val c = cos(a); val s = sin(a)
                    canvas.drawLine(c * size * 0.25f, s * size * 0.25f,
                        c * size * 0.39f, s * size * 0.39f, glyphStroke)
                }
                canvas.drawCircle(0f, 0f, size * 0.075f, glyphStroke)
            }
            Glyph.APPS -> {
                val cell = size * 0.17f
                for (row in -1..1) for (col in -1..1) {
                    workRect.set(col * size * 0.28f - cell / 2f, row * size * 0.28f - cell / 2f,
                        col * size * 0.28f + cell / 2f, row * size * 0.28f + cell / 2f)
                    canvas.drawRoundRect(workRect, cell * 0.22f, cell * 0.22f, glyphFill)
                }
            }
            Glyph.STORE -> {
                workRect.set(-size * 0.32f, -size * 0.10f, size * 0.32f, size * 0.36f)
                canvas.drawRoundRect(workRect, size * 0.07f, size * 0.07f, glyphStroke)
                workRect.set(-size * 0.18f, -size * 0.35f, size * 0.18f, size * 0.02f)
                canvas.drawArc(workRect, 200f, 140f, false, glyphStroke)
            }
            Glyph.WALLPAPER -> {
                workRect.set(-size * 0.38f, -size * 0.28f, size * 0.38f, size * 0.28f)
                canvas.drawRoundRect(workRect, size * 0.06f, size * 0.06f, glyphStroke)
                path.reset()
                path.moveTo(-size * 0.27f, size * 0.16f)
                path.lineTo(-size * 0.05f, -size * 0.08f)
                path.lineTo(size * 0.10f, size * 0.08f)
                path.lineTo(size * 0.22f, -size * 0.04f)
                path.lineTo(size * 0.31f, size * 0.16f)
                canvas.drawPath(path, glyphStroke)
                canvas.drawCircle(size * 0.20f, -size * 0.15f, size * 0.045f, glyphFill)
            }
        }
        canvas.restore()
    }

    private fun open(item: Item) {
        if (item.intent.action == "com.clean.glasshome.PICK_WALLPAPER") {
            onWallpaperRequest()
            return
        }
        item.pkg?.let { prefs.edit().putLong(it, System.currentTimeMillis()).apply() }
        try {
            context.startActivity(Intent(item.intent))
            if (context is Activity) context.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        } catch (_: Exception) {
            Toast.makeText(context, "Can't open ${item.label}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun activateFocused() {
        when (focusZone) {
            ZONE_QUICK -> pills.getOrNull(quickIndex)?.let { open(it) }
            ZONE_FEATURE, ZONE_DOCK -> currentApp()?.let { open(it) }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                when (focusZone) {
                    ZONE_DOCK -> changeApp(appIndex - 1)
                    ZONE_QUICK -> changeQuick(quickIndex - 1)
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                when (focusZone) {
                    ZONE_DOCK -> changeApp(appIndex + 1)
                    ZONE_QUICK -> changeQuick(quickIndex + 1)
                }
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                focusZone = when (focusZone) {
                    ZONE_DOCK -> ZONE_FEATURE
                    ZONE_FEATURE -> ZONE_QUICK
                    else -> ZONE_QUICK
                }
                animateFocus()
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                focusZone = when (focusZone) {
                    ZONE_QUICK -> ZONE_FEATURE
                    ZONE_FEATURE -> ZONE_DOCK
                    else -> ZONE_DOCK
                }
                animateFocus()
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A -> {
                if (event.repeatCount == 0) activateFocused()
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        invalidate()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        for (i in quickRects.indices) {
            if (quickRects[i].contains(event.x, event.y)) {
                focusZone = ZONE_QUICK
                quickIndex = i
                animateFocus()
                open(pills[i])
                return true
            }
        }
        if (featureButton.contains(event.x, event.y) || coverRect.contains(event.x, event.y)) {
            focusZone = ZONE_FEATURE
            activateFocused()
            return true
        }
        for (i in dockRects.indices) {
            if (dockRects[i].contains(event.x, event.y)) {
                focusZone = ZONE_DOCK
                if (appIndex != i) {
                    previousFeatured = currentApp()
                    appIndex = i
                    animateFocus()
                    updateDockScroll(true)
                    invalidate()
                }
                open(apps[i])
                return true
            }
        }
        if (dockFrame.contains(event.x, event.y)) {
            focusZone = ZONE_DOCK
            animateFocus()
            return true
        }
        return true
    }

    fun release() {
        removeCallbacks(tick)
        focusAnimator?.cancel()
        scrollAnimator?.cancel()
        wallpaperAnimator?.cancel()
        bg?.recycle()
        bg = null
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        focusAnimator?.cancel()
        scrollAnimator?.cancel()
        wallpaperAnimator?.cancel()
        super.onDetachedFromWindow()
    }
}
