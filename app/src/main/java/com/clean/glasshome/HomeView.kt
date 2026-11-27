package com.clean.glasshome

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
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * GlassHome 5.3 - one-screen launcher drawn on a single hardware-accelerated canvas.
 *
 * Layout: clock + quick controls (top), media / featured-app panel (middle), glass app dock (bottom).
 * Animation: one frame loop that only runs while something is still moving. Every moving value
 * (focus position, glow, entrance, crossfades) eases toward its target with time-based smoothing,
 * so key presses can interrupt each other without jumps and nothing runs while the screen is idle.
 */
class HomeView(ctx: Context) : View(ctx) {
    companion object {
        private const val ZONE_QUICK = 0
        private const val ZONE_PANEL = 1
        private const val ZONE_DOCK = 2
        private const val BTN_PREV = 0
        private const val BTN_PLAY = 1
        private const val BTN_NEXT = 2
        private const val BTN_OPEN = 3
        private const val TILE_RATIO = 16f / 9f
    }

    // ---- data ----
    private var pills: List<Item> = emptyList()
    private var apps: List<Item> = emptyList()
    private var media: MediaInfo? = null
    private var weather: WeatherInfo? = null
    private var pillRects: Array<RectF> = emptyArray()
    private var pillAmt = FloatArray(0)
    private var dockRects: Array<RectF> = emptyArray()
    private val btnRects = Array(4) { RectF() }
    private val btnAmt = FloatArray(4)
    private var btnOrder = IntArray(0)

    // ---- focus ----
    private var zone = ZONE_DOCK
    private var appIndex = 0
    private var quickIndex = 0
    private var panelButton = BTN_OPEN

    // ---- animated values (all eased toward targets in stepAnimation) ----
    private var focusPos = 0f
    private var dockFocus = 1f
    private var swap = 1f
    private var swapFrom: Any? = null
    private var enter = 0f
    private var dockIn = 0f
    private var press = 0f
    private var wallpaperAlpha = 1f
    private var glowCur: Shader? = null
    private var glowPrev: Shader? = null
    private var animating = false
    private var lastFrameNanos = 0L
    private var resumed = true
    private val frameRunnable = Runnable { stepAnimation() }

    // ---- background ----
    private var bg: Bitmap? = null
    private var customWallpaper = false
    private val bgSrc = Rect()
    private val bgDst = Rect()
    private val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val wallpaperScrim = Paint()
    private val vignette = Paint()
    private val topScrim = Paint()
    private val fadePaint = Paint().apply { color = Color.BLACK }

    // ---- paints (allocated once) ----
    private val cardFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cardEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x88FFFFFF.toInt() }
    private val softStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x66FFFFFF }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFFFE9CC.toInt() }
    private val controlHalo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tileHalo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint()
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dockFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dockEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val glyphStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val glyphFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val workRect = RectF()
    private val artRect = RectF()
    private var tileHaloShader: Shader? = null

    private fun makeText(color: Int, family: String, style: Int = Typeface.NORMAL) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; typeface = Typeface.create(family, style) }

    private val timePaint = makeText(Color.WHITE, "sans-serif-medium")
    private val datePaint = makeText(0xE6FFFFFF.toInt(), "sans-serif-medium")
    private val weatherPaint = makeText(0xB8FFFFFF.toInt(), "sans-serif")
    private val titlePaint = makeText(Color.WHITE, "sans-serif-medium")
    private val subtitlePaint = makeText(0xD8FFFFFF.toInt(), "sans-serif")
    private val controlText = makeText(Color.WHITE, "sans-serif-medium")
    private val appLabelPaint = makeText(Color.WHITE, "sans-serif-medium").apply { textAlign = Paint.Align.CENTER }
    private val smallLeft = makeText(0xCCFFFFFF.toInt(), "sans-serif")
    private val smallRight = makeText(0xCCFFFFFF.toInt(), "sans-serif").apply { textAlign = Paint.Align.RIGHT }

    private val prefs = ctx.getSharedPreferences("last", Context.MODE_PRIVATE)
    private val timeFormat = DateFormat.getTimeFormat(ctx)
    private val dateFormat = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault())
    private var minute = -1L
    private var timeText = ""
    private var dateText = ""
    private var shownSecond = -1L
    private var elapsedText = ""
    private var remainText = ""

    // ---- geometry ----
    private var ww = 0f
    private var hh = 0f
    private var margin = 0f
    private var inset = 0f
    private var pillH = 0f
    private var pillTop = 0f
    private var pillGap = 0f
    private var artSize = 0f
    private var artTop = 0f
    private var artRadius = 0f
    private var textLeft = 0f
    private var btnSize = 0f
    private var btnGap = 0f
    private var btnTop = 0f
    private var dockLeft = 0f
    private var dockRight = 0f
    private var dockTop = 0f
    private var dockBottom = 0f
    private var dockRadius = 0f
    private var viewportLeft = 0f
    private var viewportW = 0f
    private var tileW = 0f
    private var tileH = 0f
    private var tileGap = 0f
    private var tilePitch = 0f
    private var tileTop = 0f
    private var tileRadius = 0f
    private var progLeft = 0f
    private var progRight = 0f
    private var progY = 0f
    private var timesY = 0f
    private var glowCx = 0f
    private var glowCy = 0f
    private var glowR = 0f

    private val clockTick = object : Runnable {
        override fun run() {
            invalidate()
            scheduleClock()
        }
    }
    private val mediaTick = object : Runnable {
        override fun run() {
            invalidate()
            if (shouldTickMedia()) postDelayed(this, 1000L)
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
        zone = ZONE_DOCK
        dockFocus = 1f
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestFocus()
        scheduleClock()
        updateMediaTicker()
        kick()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frameRunnable)
        removeCallbacks(clockTick)
        removeCallbacks(mediaTick)
        animating = false
        super.onDetachedFromWindow()
    }

    fun onHostResume() {
        resumed = true
        scheduleClock()
        updateMediaTicker()
        enter = 0f
        kick()
    }

    fun onHostPause() {
        resumed = false
        removeCallbacks(clockTick)
        removeCallbacks(mediaTick)
    }

    fun release() {
        removeCallbacks(frameRunnable)
        removeCallbacks(clockTick)
        removeCallbacks(mediaTick)
        animating = false
        bg?.recycle()
        bg = null
    }

    private fun scheduleClock() {
        removeCallbacks(clockTick)
        // Fire just after the next minute boundary so the clock never lags.
        postDelayed(clockTick, 60000L - System.currentTimeMillis() % 60000L + 50L)
    }

    private fun shouldTickMedia(): Boolean {
        val m = media
        return resumed && isAttachedToWindow && m != null && m.playing && m.durationMs > 0L
    }

    private fun updateMediaTicker() {
        removeCallbacks(mediaTick)
        if (shouldTickMedia()) postDelayed(mediaTick, 1000L)
    }

    // ------------------------------------------------------------------ public API

    fun setData(data: Data) {
        val oldPackage = apps.getOrNull(appIndex)?.pkg
        val wasEmpty = apps.isEmpty()
        val previousContent = content()
        pills = data.pills
        apps = data.rows.firstOrNull()?.items ?: emptyList()
        pillRects = Array(pills.size) { RectF() }
        pillAmt = FloatArray(pills.size)
        dockRects = Array(apps.size) { RectF() }
        val preserved = apps.indexOfFirst { it.pkg != null && it.pkg == oldPackage }
        appIndex = if (preserved >= 0) preserved else appIndex.coerceIn(0, max(0, apps.size - 1))
        quickIndex = quickIndex.coerceIn(0, max(0, pills.size - 1))
        focusPos = appIndex.toFloat()
        if (wasEmpty && apps.isNotEmpty()) dockIn = 0f
        for (item in apps) item.lbl = null
        layoutPills()
        layoutPanel()
        if (media == null && previousContent !== content()) {
            glowPrev = null
            glowCur = buildGlow(accentOf(content()))
        }
        kick()
    }

    fun setWallpaper(bitmap: Bitmap) {
        val old = bg
        bg = bitmap
        customWallpaper = true
        if (old != null && old !== bitmap && !old.isRecycled) old.recycle()
        wallpaperAlpha = 0f
        kick()
    }

    fun setWeather(info: WeatherInfo?) {
        weather = info
        invalidate()
    }

    fun setMedia(info: MediaInfo?) {
        val old = media
        val previousContent = content()
        val contentChanged = (old == null) != (info == null) || old?.key != info?.key
        media = info
        layoutPanel()
        if (btnOrder.isEmpty() && zone == ZONE_PANEL) zone = ZONE_DOCK
        if (zone == ZONE_PANEL && !btnOrder.contains(panelButton)) panelButton = defaultButton()
        if (contentChanged) beginSwap(previousContent)
        updateMediaTicker()
        kick()
    }

    fun resetFocus() {
        val from = if (media == null) currentApp() else null
        zone = ZONE_DOCK
        appIndex = 0
        quickIndex = 0
        if (media == null && from !== currentApp()) beginSwap(from)
        layoutPanel()
        kick()
    }

    fun returnToDock() {
        zone = ZONE_DOCK
        kick()
    }

    // ------------------------------------------------------------------ sizing

    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        super.onSizeChanged(w, h, oldW, oldH)
        ww = w.toFloat()
        hh = h.toFloat()
        margin = ww * 0.06f
        inset = hh * 0.034f
        pillH = hh * 0.068f
        pillTop = hh * 0.072f
        pillGap = hh * 0.014f
        artSize = hh * 0.2f
        artTop = hh * 0.285f
        val artLeft = margin + inset
        artRect.set(artLeft, artTop, artLeft + artSize, artTop + artSize)
        artRadius = artSize * 0.085f
        textLeft = artRect.right + hh * 0.034f
        btnSize = artSize * 0.36f
        btnGap = btnSize * 0.24f
        btnTop = artTop + artSize * 0.62f

        dockLeft = margin
        dockRight = ww - margin
        dockTop = hh * 0.655f
        dockRadius = hh * 0.04f
        tileGap = hh * 0.026f
        viewportLeft = dockLeft + inset
        viewportW = dockRight - dockLeft - 2f * inset
        tileW = (viewportW - 4f * tileGap) / 5f
        tileH = tileW / TILE_RATIO
        tilePitch = tileW + tileGap
        tileTop = dockTop + hh * 0.022f
        dockBottom = tileTop + tileH + hh * 0.07f
        tileRadius = tileH * 0.22f

        progLeft = artLeft
        progRight = ww - margin - inset
        progY = artRect.bottom + hh * 0.075f
        timesY = artRect.bottom + hh * 0.048f
        glowCx = artRect.centerX()
        glowCy = artRect.centerY()
        glowR = hh * 0.62f

        timePaint.textSize = hh * 0.040f
        datePaint.textSize = hh * 0.040f
        weatherPaint.textSize = hh * 0.024f
        titlePaint.textSize = hh * 0.048f
        titlePaint.letterSpacing = -0.01f
        subtitlePaint.textSize = hh * 0.027f
        controlText.textSize = hh * 0.0225f
        appLabelPaint.textSize = hh * 0.024f
        smallLeft.textSize = hh * 0.021f
        smallRight.textSize = hh * 0.021f

        cardEdge.strokeWidth = max(1f, hh * 0.0016f)
        softStroke.strokeWidth = max(1f, hh * 0.0016f)
        ringPaint.strokeWidth = max(2.5f, hh * 0.0034f)
        dockEdge.strokeWidth = max(1.5f, hh * 0.0018f)
        linePaint.strokeWidth = max(1f, hh * 0.0016f)
        trackPaint.strokeWidth = max(3f, hh * 0.004f)

        tileHaloShader = RadialGradient(
            0f, 0f, tileW * 0.82f,
            intArrayOf(0x66FFA94D, 0x26FF8C32, 0x00FF8C32),
            floatArrayOf(0f, 0.57f, 1f), Shader.TileMode.CLAMP
        )
        tileHalo.shader = tileHaloShader
        dockFill.shader = LinearGradient(0f, dockTop, 0f, dockBottom, 0x40FFFFFF, 0x1AFFFFFF, Shader.TileMode.CLAMP)
        dockEdge.shader = LinearGradient(0f, dockTop, 0f, dockBottom, 0x88FFFFFF.toInt(), 0x22FFFFFF, Shader.TileMode.CLAMP)
        sheenPaint.shader = LinearGradient(0f, artRect.top, 0f, artRect.bottom, 0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)

        wallpaperScrim.shader = LinearGradient(
            0f, 0f, 0f, hh,
            intArrayOf(0x22000000, 0x14000000, 0x5A080402, 0xCC080503.toInt()),
            floatArrayOf(0f, 0.34f, 0.68f, 1f), Shader.TileMode.CLAMP
        )
        vignette.shader = RadialGradient(
            ww * 0.5f, hh * 0.45f, max(ww, hh) * 0.78f,
            intArrayOf(0x00000000, 0x00000000, 0x66000000), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        topScrim.shader = LinearGradient(
            0f, 0f, 0f, hh * 0.24f,
            intArrayOf(0x66000000, 0x00000000), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        if (!customWallpaper) makeDefaultWallpaper()
        for (item in apps) item.lbl = null
        layoutPills()
        layoutPanel()
        glowPrev = null
        glowCur = buildGlow(accentOf(content()))
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

    private fun controlWidth(label: String): Float = pillH * 0.98f + controlText.measureText(label) + pillH * 0.44f

    private fun layoutPills() {
        if (ww <= 0f) return
        var total = 0f
        for (p in pills) total += controlWidth(p.label)
        total += max(0, pills.size - 1) * pillGap
        var x = ww - margin - total
        for (i in pills.indices) {
            val w = controlWidth(pills[i].label)
            pillRects[i].set(x, pillTop, x + w, pillTop + pillH)
            x += w + pillGap
        }
    }

    private fun layoutPanel() {
        if (ww <= 0f) return
        val m = media
        val list = ArrayList<Int>(4)
        if (m != null) {
            if (m.canPrev) list.add(BTN_PREV)
            list.add(BTN_PLAY)
            if (m.canNext) list.add(BTN_NEXT)
            if (m.canOpen) list.add(BTN_OPEN)
        } else if (currentApp() != null) {
            list.add(BTN_OPEN)
        }
        btnOrder = list.toIntArray()
        var x = textLeft
        val h = btnSize
        for (id in btnOrder) {
            val w = if (id == BTN_OPEN) controlWidth("Open") * (h / pillH) else h
            btnRects[id].set(x, btnTop, x + w, btnTop + h)
            x += w + btnGap
        }
    }

    // ------------------------------------------------------------------ content helpers

    private fun currentApp(): Item? = apps.getOrNull(appIndex)
    private fun content(): Any? = media ?: currentApp()

    private fun accentOf(src: Any?): Int = when (src) {
        is MediaInfo -> src.accent
        is Item -> src.accent
        else -> Catalog.AMBER
    }

    private fun buildGlow(color: Int): Shader? {
        if (ww <= 0f) return null
        return RadialGradient(
            glowCx, glowCy, glowR,
            intArrayOf(withAlpha(color, 0x6E), withAlpha(color, 0x24), withAlpha(color, 0x00)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
    }

    private fun beginSwap(from: Any?) {
        swapFrom = from
        swap = 0f
        glowPrev = glowCur
        glowCur = buildGlow(accentOf(content()))
        kick()
    }

    private fun defaultButton(): Int =
        if (btnOrder.contains(BTN_PLAY)) BTN_PLAY else (btnOrder.firstOrNull() ?: BTN_OPEN)

    // ------------------------------------------------------------------ animation loop

    private fun kick() {
        invalidate()
        if (!animating) {
            animating = true
            lastFrameNanos = System.nanoTime()
            postOnAnimation(frameRunnable)
        }
    }

    private fun approach(cur: Float, target: Float, k: Float): Float {
        val d = target - cur
        return if (abs(d) < 0.003f) target else cur + d * k
    }

    private fun stepAnimation() {
        val now = System.nanoTime()
        val dt = ((now - lastFrameNanos) / 1000000000f).coerceIn(0.001f, 0.05f)
        lastFrameNanos = now
        val kFast = 1f - exp(-dt / 0.06f)
        val kMid = 1f - exp(-dt / 0.085f)
        val kSlow = 1f - exp(-dt / 0.15f)
        var busy = false

        val fpTarget = appIndex.toFloat()
        focusPos = approach(focusPos, fpTarget, kFast)
        if (focusPos != fpTarget) busy = true

        val dockTarget = if (zone == ZONE_DOCK) 1f else 0f
        dockFocus = approach(dockFocus, dockTarget, kMid)
        if (dockFocus != dockTarget) busy = true

        for (i in pillAmt.indices) {
            val t = if (zone == ZONE_QUICK && i == quickIndex) 1f else 0f
            pillAmt[i] = approach(pillAmt[i], t, kMid)
            if (pillAmt[i] != t) busy = true
        }
        for (i in btnAmt.indices) {
            val t = if (zone == ZONE_PANEL && i == panelButton) 1f else 0f
            btnAmt[i] = approach(btnAmt[i], t, kMid)
            if (btnAmt[i] != t) busy = true
        }

        swap = approach(swap, 1f, kMid)
        if (swap != 1f) busy = true else { swapFrom = null; glowPrev = null }
        enter = approach(enter, 1f, kSlow)
        if (enter != 1f) busy = true
        dockIn = approach(dockIn, 1f, kSlow)
        if (dockIn != 1f) busy = true
        press = approach(press, 0f, kMid)
        if (press != 0f) busy = true
        wallpaperAlpha = approach(wallpaperAlpha, 1f, kSlow)
        if (wallpaperAlpha != 1f) busy = true

        invalidate()
        if (busy) postOnAnimation(frameRunnable) else animating = false
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (ww <= 0f || hh <= 0f) return

        drawWallpaper(canvas)
        val e = enter

        canvas.save()
        canvas.translate(0f, -(1f - e) * hh * 0.03f)
        drawTopBar(canvas)
        canvas.restore()

        canvas.save()
        canvas.translate(0f, (1f - e) * hh * 0.035f)
        drawGlow(canvas)
        drawPanel(canvas, swapFrom, 1f - swap, -hh * 0.012f * swap)
        drawPanel(canvas, content(), swap, hh * 0.012f * (1f - swap))
        val m = media
        if (m != null) drawProgress(canvas, m)
        drawPanelButtons(canvas)
        canvas.restore()

        canvas.save()
        canvas.translate(0f, (1f - e * e) * hh * 0.06f)
        drawDock(canvas)
        canvas.restore()

        if (e < 0.995f) {
            fadePaint.alpha = ((1f - e) * 140f).toInt().coerceIn(0, 255)
            canvas.drawRect(0f, 0f, ww, hh, fadePaint)
        }
    }

    private fun drawWallpaper(canvas: Canvas) {
        canvas.drawColor(0xFF1A0B08.toInt())
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
        }
        canvas.drawRect(0f, 0f, ww, hh, wallpaperScrim)
        canvas.drawRect(0f, 0f, ww, hh, vignette)
        canvas.drawRect(0f, 0f, ww, hh * 0.24f, topScrim)
    }

    private fun drawGlow(canvas: Canvas) {
        val cur = glowCur ?: return
        val prev = glowPrev
        if (prev != null && swap < 1f) {
            glowPaint.shader = prev
            glowPaint.alpha = (255f * (1f - swap)).toInt()
            canvas.drawRect(glowCx - glowR, glowCy - glowR, glowCx + glowR, glowCy + glowR, glowPaint)
        }
        glowPaint.shader = cur
        glowPaint.alpha = (255f * swap).toInt()
        canvas.drawRect(glowCx - glowR, glowCy - glowR, glowCx + glowR, glowCy + glowR, glowPaint)
    }

    private fun drawTopBar(canvas: Canvas) {
        val now = System.currentTimeMillis()
        if (now / 60000L != minute) {
            minute = now / 60000L
            val date = Date(now)
            timeText = timeFormat.format(date)
            dateText = dateFormat.format(date)
        }
        val cy = pillTop + pillH / 2f
        val base = cy + timePaint.textSize * 0.35f
        canvas.drawText(timeText, margin, base, timePaint)
        val sepX = margin + timePaint.measureText(timeText) + hh * 0.024f
        linePaint.color = 0x99FFFFFF.toInt()
        canvas.drawLine(sepX, cy - hh * 0.02f, sepX, cy + hh * 0.02f, linePaint)
        val dateX = sepX + hh * 0.024f
        canvas.drawText(dateText, dateX, base, datePaint)
        val info = weather
        if (info != null) {
            val weatherX = dateX + datePaint.measureText(dateText) + hh * 0.03f
            canvas.drawText("\u00B7  ${info.temperature}\u00B0  ${info.city}", weatherX, base - hh * 0.002f, weatherPaint)
        }
        for (i in pills.indices) {
            val focused = zone == ZONE_QUICK && i == quickIndex
            drawControl(canvas, pillRects[i], pillAmt[i], pills[i].glyph, pills[i].label, if (focused) press else 0f)
        }
    }

    /** Glass capsule control (pill or circle). Focus = white fill, dark content, soft halo. */
    private fun drawControl(canvas: Canvas, rect: RectF, amt: Float, glyph: Int, label: String?, pressed: Float) {
        val scale = (1f + 0.07f * amt) * (1f - 0.05f * pressed)
        canvas.save()
        canvas.translate(rect.centerX(), rect.centerY())
        canvas.scale(scale, scale)
        val halfW = rect.width() / 2f
        val halfH = rect.height() / 2f
        if (amt > 0.02f) {
            val g = hh * 0.012f
            controlHalo.color = withAlpha(0xFFFFC27A.toInt(), (80f * amt).toInt())
            workRect.set(-halfW - g, -halfH - g, halfW + g, halfH + g)
            canvas.drawRoundRect(workRect, halfH + g, halfH + g, controlHalo)
        }
        workRect.set(-halfW, -halfH, halfW, halfH)
        cardFill.color = lerpColor(0x38FFFFFF, 0xF4FFFFFF.toInt(), amt)
        canvas.drawRoundRect(workRect, halfH, halfH, cardFill)
        if (amt < 0.98f) {
            softStroke.alpha = (110f * (1f - amt)).toInt()
            canvas.drawRoundRect(workRect, halfH, halfH, softStroke)
        }
        val fg = lerpColor(Color.WHITE, 0xFF1E140C.toInt(), amt)
        if (label == null) {
            drawGlyph(canvas, glyph, 0f, 0f, rect.height() * 0.46f, fg)
        } else {
            drawGlyph(canvas, glyph, -halfW + rect.height() * 0.62f, 0f, rect.height() * 0.5f, fg)
            controlText.color = fg
            canvas.drawText(label, -halfW + rect.height() * 0.98f, controlText.textSize * 0.35f, controlText)
        }
        canvas.restore()
    }

    private fun drawPanel(canvas: Canvas, src: Any?, alpha: Float, dy: Float) {
        if (alpha <= 0.01f) return
        val a = (255f * alpha).toInt().coerceIn(0, 255)
        canvas.save()
        canvas.translate(0f, dy)

        // soft layered shadow under the artwork
        for (k in 1..3) {
            val g = k * hh * 0.006f
            shadowPaint.color = withAlpha(Color.BLACK, (46f * alpha / k).toInt())
            workRect.set(artRect.left - g, artRect.top - g + hh * 0.014f, artRect.right + g, artRect.bottom + g + hh * 0.014f)
            canvas.drawRoundRect(workRect, artRadius + g, artRadius + g, shadowPaint)
        }

        var title: String
        var subtitle: String?
        when (src) {
            is MediaInfo -> {
                title = src.title
                subtitle = src.artist ?: src.appLabel
                val art = src.art
                if (art != null && !art.isRecycled) {
                    iconPaint.alpha = a
                    canvas.drawBitmap(art, null, artRect, iconPaint)
                    iconPaint.alpha = 255
                } else {
                    cardFill.color = withAlpha(scaleValueDark(src.accent), a)
                    canvas.drawRoundRect(artRect, artRadius, artRadius, cardFill)
                    sheenPaint.alpha = a
                    canvas.drawRoundRect(artRect, artRadius, artRadius, sheenPaint)
                    drawGlyph(canvas, Glyph.NOTE, artRect.centerX(), artRect.centerY(), artSize * 0.46f, withAlpha(Color.WHITE, a))
                }
            }
            is Item -> {
                title = src.label
                val opened = src.pkg?.let { prefs.getLong(it, 0L) } ?: 0L
                subtitle = if (opened > 0L) "Recently opened  \u00B7  Ready when you are" else "Ready to launch"
                cardFill.color = withAlpha(src.tile, a)
                canvas.drawRoundRect(artRect, artRadius, artRadius, cardFill)
                sheenPaint.alpha = a
                canvas.drawRoundRect(artRect, artRadius, artRadius, sheenPaint)
                val icon = src.icon
                if (icon != null && !icon.isRecycled) {
                    val pad = artSize * 0.2f
                    workRect.set(artRect.left + pad, artRect.top + pad, artRect.right - pad, artRect.bottom - pad)
                    iconPaint.alpha = a
                    canvas.drawBitmap(icon, null, workRect, iconPaint)
                    iconPaint.alpha = 255
                }
            }
            else -> {
                title = "Welcome home"
                subtitle = "Choose an app below"
                cardFill.color = withAlpha(0xFF492416.toInt(), a)
                canvas.drawRoundRect(artRect, artRadius, artRadius, cardFill)
                sheenPaint.alpha = a
                canvas.drawRoundRect(artRect, artRadius, artRadius, sheenPaint)
                drawGlyph(canvas, Glyph.APPS, artRect.centerX(), artRect.centerY(), artSize * 0.46f, withAlpha(Color.WHITE, a))
            }
        }
        cardEdge.alpha = (90f * alpha).toInt()
        canvas.drawRoundRect(artRect, artRadius, artRadius, cardEdge)

        val maxW = ww - margin - inset - textLeft
        val titleText = fit(title, titlePaint, maxW)
        titlePaint.alpha = a
        canvas.drawText(titleText, 0, titleText.length, textLeft, artTop + artSize * 0.27f, titlePaint)
        if (subtitle != null) {
            val sub = fit(subtitle, subtitlePaint, maxW)
            subtitlePaint.alpha = (216f * alpha).toInt()
            canvas.drawText(sub, 0, sub.length, textLeft, artTop + artSize * 0.46f, subtitlePaint)
        }
        titlePaint.alpha = 255
        canvas.restore()
    }

    private fun drawPanelButtons(canvas: Canvas) {
        val m = media
        for (id in btnOrder) {
            val rect = btnRects[id]
            val amt = btnAmt[id]
            val pr = if (zone == ZONE_PANEL && panelButton == id) press else 0f
            when (id) {
                BTN_PREV -> drawControl(canvas, rect, amt, Glyph.PREV, null, pr)
                BTN_NEXT -> drawControl(canvas, rect, amt, Glyph.NEXT, null, pr)
                BTN_PLAY -> drawControl(canvas, rect, amt, if (m != null && m.playing) Glyph.PAUSE else Glyph.PLAY, null, pr)
                else -> drawControl(canvas, rect, amt, Glyph.PLAY, "Open", pr)
            }
        }
    }

    private fun drawProgress(canvas: Canvas, m: MediaInfo) {
        if (m.durationMs <= 0L) return
        val pos = m.currentPosition().coerceIn(0L, m.durationMs)
        val second = pos / 1000L
        if (second != shownSecond) {
            shownSecond = second
            elapsedText = formatTime(pos)
            remainText = "-" + formatTime(m.durationMs - pos)
        }
        val a = swap
        val frac = pos.toFloat() / m.durationMs.toFloat()
        trackPaint.color = withAlpha(Color.WHITE, (70f * a).toInt())
        canvas.drawLine(progLeft, progY, progRight, progY, trackPaint)
        val x = progLeft + (progRight - progLeft) * frac
        trackPaint.color = withAlpha(Color.WHITE, (235f * a).toInt())
        canvas.drawLine(progLeft, progY, x, progY, trackPaint)
        glyphFill.color = withAlpha(Color.WHITE, (255f * a).toInt())
        canvas.drawCircle(x, progY, hh * 0.0085f, glyphFill)
        smallLeft.alpha = (204f * a).toInt()
        smallRight.alpha = (204f * a).toInt()
        canvas.drawText(elapsedText, progLeft, timesY, smallLeft)
        canvas.drawText(remainText, progRight, timesY, smallRight)
    }

    private fun drawDock(canvas: Canvas) {
        val a = (255f * dockIn).toInt()
        workRect.set(dockLeft, dockTop, dockRight, dockBottom)
        dockFill.alpha = a
        canvas.drawRoundRect(workRect, dockRadius, dockRadius, dockFill)
        dockEdge.alpha = a
        canvas.drawRoundRect(workRect, dockRadius, dockRadius, dockEdge)

        if (apps.isEmpty()) {
            appLabelPaint.alpha = 220
            canvas.drawText("No TV apps found", (dockLeft + dockRight) / 2f, (dockTop + dockBottom) / 2f, appLabelPaint)
            return
        }

        val maxScroll = max(0f, apps.size * tilePitch - tileGap - viewportW)
        val scroll = ((focusPos - 2f) * tilePitch).coerceIn(0f, maxScroll)
        val clipL = dockLeft + inset * 0.45f
        val clipR = dockRight - inset * 0.45f
        val push = tileW * 0.045f
        canvas.save()
        canvas.clipRect(clipL, dockTop, clipR, dockBottom)
        for (i in apps.indices) {
            val d = i - focusPos
            val baseX = viewportLeft + i * tilePitch - scroll
            if (i < dockRects.size) dockRects[i].set(baseX, tileTop, baseX + tileW, tileTop + tileH)
            val x = baseX + push * d.coerceIn(-1f, 1f)
            if (x + tileW < clipL - tileW * 0.2f || x > clipR) continue
            drawTile(canvas, apps[i], x, max(0f, 1f - abs(d)))
        }
        canvas.restore()
    }

    private fun drawTile(canvas: Canvas, item: Item, x: Float, bump: Float) {
        val amt = bump * (0.5f + 0.5f * dockFocus)
        val s = (1f + 0.12f * amt) * (1f - 0.045f * press * bump * dockFocus)
        val cx = x + tileW / 2f
        val cy = tileTop + tileH / 2f - tileH * 0.035f * amt
        val halfW = tileW / 2f
        val halfH = tileH / 2f
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(s, s)
        if (amt > 0.02f) {
            for (k in 1..3) {
                val g = k * hh * 0.007f
                shadowPaint.color = withAlpha(Color.BLACK, (70f * amt * dockIn / k).toInt())
                workRect.set(-halfW - g, -halfH - g + tileH * 0.12f, halfW + g, halfH + g + tileH * 0.12f)
                canvas.drawRoundRect(workRect, tileRadius + g, tileRadius + g, shadowPaint)
            }
            val g = hh * 0.02f
            tileHalo.alpha = (255f * amt * dockIn).toInt()
            workRect.set(-halfW - g, -halfH - g, halfW + g, halfH + g)
            canvas.drawRoundRect(workRect, tileRadius + g, tileRadius + g, tileHalo)
        }
        val art = item.art
        if (art != null && !art.isRecycled) {
            tilePaint.alpha = (255f * dockIn * (0.86f + 0.14f * amt)).toInt().coerceIn(0, 255)
            workRect.set(-halfW, -halfH, halfW, halfH)
            canvas.drawBitmap(art, null, workRect, tilePaint)
        }
        if (amt > 0.02f) {
            ringPaint.alpha = (235f * amt * dockIn).toInt()
            canvas.drawRoundRect(-halfW, -halfH, halfW, halfH, tileRadius, tileRadius, ringPaint)
        }
        canvas.restore()

        if (bump > 0.04f) {
            val label = item.lbl ?: TextUtils.ellipsize(item.label, appLabelPaint, tileW * 1.25f, TextUtils.TruncateAt.END)
                .also { item.lbl = it }
            appLabelPaint.alpha = (255f * bump * dockIn * (0.55f + 0.45f * dockFocus)).toInt().coerceIn(0, 255)
            canvas.drawText(label, 0, label.length, cx, tileTop + tileH + hh * 0.052f, appLabelPaint)
        }
    }

    // ------------------------------------------------------------------ glyphs

    private fun drawGlyph(canvas: Canvas, glyph: Int, cx: Float, cy: Float, size: Float, color: Int) {
        glyphFill.color = color
        glyphStroke.color = color
        glyphStroke.strokeWidth = max(1.8f, size * 0.085f)
        canvas.save()
        canvas.translate(cx, cy)
        when (glyph) {
            Glyph.WIFI -> {
                canvas.translate(0f, -size * 0.06f)
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
                    val c = cos(a)
                    val s = sin(a)
                    canvas.drawLine(c * size * 0.25f, s * size * 0.25f, c * size * 0.39f, s * size * 0.39f, glyphStroke)
                }
                canvas.drawCircle(0f, 0f, size * 0.075f, glyphStroke)
            }
            Glyph.SYSTEM -> {
                for (i in -1..1) {
                    val y = i * size * 0.24f
                    canvas.drawLine(-size * 0.36f, y, size * 0.36f, y, glyphStroke)
                    canvas.drawCircle(i * 0.16f * size, y, size * 0.085f, glyphFill)
                }
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
            Glyph.PLAY -> {
                path.reset()
                path.moveTo(-size * 0.26f, -size * 0.34f)
                path.lineTo(size * 0.34f, 0f)
                path.lineTo(-size * 0.26f, size * 0.34f)
                path.close()
                canvas.drawPath(path, glyphFill)
            }
            Glyph.PAUSE -> {
                workRect.set(-size * 0.28f, -size * 0.32f, -size * 0.07f, size * 0.32f)
                canvas.drawRoundRect(workRect, size * 0.05f, size * 0.05f, glyphFill)
                workRect.set(size * 0.07f, -size * 0.32f, size * 0.28f, size * 0.32f)
                canvas.drawRoundRect(workRect, size * 0.05f, size * 0.05f, glyphFill)
            }
            Glyph.NEXT -> {
                path.reset()
                path.moveTo(-size * 0.32f, -size * 0.28f)
                path.lineTo(size * 0.16f, 0f)
                path.lineTo(-size * 0.32f, size * 0.28f)
                path.close()
                canvas.drawPath(path, glyphFill)
                workRect.set(size * 0.2f, -size * 0.28f, size * 0.32f, size * 0.28f)
                canvas.drawRoundRect(workRect, size * 0.04f, size * 0.04f, glyphFill)
            }
            Glyph.PREV -> {
                path.reset()
                path.moveTo(size * 0.32f, -size * 0.28f)
                path.lineTo(-size * 0.16f, 0f)
                path.lineTo(size * 0.32f, size * 0.28f)
                path.close()
                canvas.drawPath(path, glyphFill)
                workRect.set(-size * 0.32f, -size * 0.28f, -size * 0.2f, size * 0.28f)
                canvas.drawRoundRect(workRect, size * 0.04f, size * 0.04f, glyphFill)
            }
            Glyph.NOTE -> {
                canvas.drawCircle(-size * 0.14f, size * 0.24f, size * 0.17f, glyphFill)
                workRect.set(size * 0.01f, -size * 0.36f, size * 0.10f, size * 0.24f)
                canvas.drawRect(workRect, glyphFill)
                path.reset()
                path.moveTo(size * 0.10f, -size * 0.36f)
                path.lineTo(size * 0.34f, -size * 0.24f)
                path.lineTo(size * 0.34f, -size * 0.06f)
                path.lineTo(size * 0.10f, -size * 0.16f)
                path.close()
                canvas.drawPath(path, glyphFill)
            }
        }
        canvas.restore()
    }

    // ------------------------------------------------------------------ actions

    private fun open(item: Item) {
        item.pkg?.let { prefs.edit().putLong(it, System.currentTimeMillis()).apply() }
        startIntent(Intent(item.intent), item.label)
    }

    private fun openPackage(pkg: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return
        prefs.edit().putLong(pkg, System.currentTimeMillis()).apply()
        startIntent(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), pkg)
    }

    private fun startIntent(intent: Intent, name: String) {
        try {
            context.startActivity(intent)
            val host = context
            if (host is Activity) host.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        } catch (_: Exception) {
            Toast.makeText(context, "Can't open $name", Toast.LENGTH_SHORT).show()
        }
    }

    private fun activatePanelButton(id: Int) {
        val m = media
        if (m == null) {
            val app = currentApp()
            if (app != null) open(app)
            return
        }
        try {
            val controls = m.controller.transportControls
            when (id) {
                BTN_PREV -> controls.skipToPrevious()
                BTN_NEXT -> controls.skipToNext()
                BTN_PLAY -> if (m.playing) controls.pause() else controls.play()
                else -> openPackage(m.pkg)
            }
        } catch (_: Exception) { }
    }

    private fun activate() {
        press = 1f
        kick()
        when (zone) {
            ZONE_QUICK -> pills.getOrNull(quickIndex)?.let { open(it) }
            ZONE_PANEL -> activatePanelButton(panelButton)
            else -> currentApp()?.let { open(it) }
        }
    }

    private fun moveApp(index: Int) {
        if (apps.isEmpty()) return
        val next = index.coerceIn(0, apps.lastIndex)
        if (next == appIndex) return
        val from = if (media == null) currentApp() else null
        appIndex = next
        if (media == null) beginSwap(from)
        kick()
    }

    private fun moveQuick(index: Int) {
        if (pills.isEmpty()) return
        val next = index.coerceIn(0, pills.lastIndex)
        if (next == quickIndex) return
        quickIndex = next
        kick()
    }

    private fun movePanel(direction: Int) {
        if (btnOrder.isEmpty()) return
        val current = btnOrder.indexOf(panelButton)
        val next = (if (current < 0) 0 else current + direction).coerceIn(0, btnOrder.size - 1)
        panelButton = btnOrder[next]
        kick()
    }

    private fun setZone(target: Int) {
        if (target == zone) return
        zone = target
        if (target == ZONE_PANEL) panelButton = defaultButton()
        kick()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> when (zone) {
                ZONE_DOCK -> moveApp(appIndex - 1)
                ZONE_QUICK -> moveQuick(quickIndex - 1)
                else -> movePanel(-1)
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> when (zone) {
                ZONE_DOCK -> moveApp(appIndex + 1)
                ZONE_QUICK -> moveQuick(quickIndex + 1)
                else -> movePanel(1)
            }
            KeyEvent.KEYCODE_DPAD_UP -> when (zone) {
                ZONE_DOCK -> setZone(if (btnOrder.isNotEmpty()) ZONE_PANEL else ZONE_QUICK)
                ZONE_PANEL -> if (pills.isNotEmpty()) setZone(ZONE_QUICK)
                else -> { }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> when (zone) {
                ZONE_QUICK -> setZone(if (btnOrder.isNotEmpty()) ZONE_PANEL else ZONE_DOCK)
                ZONE_PANEL -> setZone(ZONE_DOCK)
                else -> { }
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A -> {
                if (event.repeatCount == 0) activate()
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val x = event.x
        val y = event.y
        for (i in pillRects.indices) {
            if (pillRects[i].contains(x, y)) {
                zone = ZONE_QUICK
                quickIndex = i
                press = 1f
                kick()
                open(pills[i])
                return true
            }
        }
        for (id in btnOrder) {
            if (btnRects[id].contains(x, y)) {
                zone = ZONE_PANEL
                panelButton = id
                press = 1f
                kick()
                activatePanelButton(id)
                return true
            }
        }
        if (artRect.contains(x, y)) {
            val m = media
            if (m != null) openPackage(m.pkg) else currentApp()?.let { open(it) }
            return true
        }
        for (i in dockRects.indices) {
            if (dockRects[i].contains(x, y)) {
                zone = ZONE_DOCK
                moveApp(i)
                press = 1f
                kick()
                open(apps[i])
                return true
            }
        }
        return true
    }

    // ------------------------------------------------------------------ small helpers

    private fun fit(text: String, paint: TextPaint, maxWidth: Float): CharSequence =
        if (paint.measureText(text) <= maxWidth) text
        else TextUtils.ellipsize(text, paint, maxWidth, TextUtils.TruncateAt.END)

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private fun scaleValueDark(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[2] = 0.38f
        return Color.HSVToColor(hsv)
    }

    private fun lerpColor(c0: Int, c1: Int, t: Float): Int {
        val a = (Color.alpha(c0) + (Color.alpha(c1) - Color.alpha(c0)) * t).toInt()
        val r = (Color.red(c0) + (Color.red(c1) - Color.red(c0)) * t).toInt()
        val g = (Color.green(c0) + (Color.green(c1) - Color.green(c0)) * t).toInt()
        val b = (Color.blue(c0) + (Color.blue(c1) - Color.blue(c0)) * t).toInt()
        return Color.argb(a, r, g, b)
    }

    private fun formatTime(ms: Long): String {
        val total = max(0L, ms) / 1000L
        val minutes = total / 60L
        val seconds = total % 60L
        return minutes.toString() + ":" + (if (seconds < 10L) "0" else "") + seconds.toString()
    }
}
