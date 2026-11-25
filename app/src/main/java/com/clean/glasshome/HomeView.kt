package com.clean.glasshome

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
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
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

class Item(val label: String, val sub: String?, val icon: Bitmap?, val intent: Intent) {
    val initial: String = label.take(1).uppercase()
    var lbl: CharSequence? = null
    var sbl: CharSequence? = null
}

class Row(val title: String, val items: List<Item>) {
    val scale = FloatArray(items.size) { 1f }
    var idx = 0
    var scroll = 0f
}

/** One custom-drawn view: no layouts, no WebView, no libraries. Light on RAM and CPU. */
class HomeView(ctx: Context) : View(ctx) {
    private var rows: List<Row> = emptyList()
    private var fr = 0
    private var frAnim = 0f
    private var moving = false
    private var last = 0L

    private var bg: Bitmap? = null
    private val bgDst = Rect()
    private val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val iconDst = RectF()
    private var ww = 0f
    private var hh = 0f
    private var tile = 0f
    private var pitch = 0f
    private var margin = 0f
    private var rowPitch = 0f
    private var focusY = 0f
    private var rad = 0f
    private var sw = 1.5f

    private val pTime = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.color = Color.WHITE; it.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }
    private val pDate = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = Color.WHITE; it.alpha = 170 }
    private val pTitle = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.color = Color.WHITE; it.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val pLabel = TextPaint(Paint.ANTI_ALIAS_FLAG).also { it.color = Color.WHITE; it.textAlign = Paint.Align.CENTER }
    private val pSub = TextPaint(Paint.ANTI_ALIAS_FLAG).also { it.color = Color.WHITE; it.textAlign = Paint.Align.CENTER }
    private val pInit = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.color = Color.WHITE; it.textAlign = Paint.Align.CENTER
        it.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }
    private val glass = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glassF = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).also { it.style = Paint.Style.STROKE; it.color = Color.WHITE }
    private val bmp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    private val timeFmt = DateFormat.getTimeFormat(ctx)
    private val dateFmt = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault())
    private var minute = -1L
    private var timeStr = ""
    private var dateStr = ""
    private val tick = object : Runnable {
        override fun run() { invalidate(); postDelayed(this, 20000) }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestFocus()
        postDelayed(tick, 20000)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    fun setRows(newRows: List<Row>) {
        val keep = rows.getOrNull(fr)?.title
        rows = newRows
        val want = keep ?: "Apps"
        val k = newRows.indexOfFirst { r -> r.title == want }
        fr = if (k >= 0) k else 0
        if (keep == null) frAnim = fr.toFloat()
        invalidate()
    }

    fun resetFocus() {
        val k = rows.indexOfFirst { r -> r.title == "Apps" }
        fr = if (k >= 0) k else 0
        for (r in rows) r.idx = 0
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        ww = w.toFloat(); hh = h.toFloat()
        tile = hh * 0.16f
        pitch = tile + hh * 0.03f
        margin = ww * 0.06f
        rowPitch = hh * 0.31f
        focusY = hh * 0.43f
        rad = tile * 0.22f
        sw = max(1.5f, hh * 0.0018f)
        pTime.textSize = hh * 0.15f
        pDate.textSize = hh * 0.034f
        pTitle.textSize = hh * 0.026f
        pLabel.textSize = hh * 0.027f
        pSub.textSize = hh * 0.02f
        pInit.textSize = tile * 0.42f
        val isz = tile * 0.56f
        val o = (tile - isz) / 2f
        iconDst.set(o, o, o + isz, o + isz)
        glass.shader = LinearGradient(0f, 0f, 0f, tile, 0x38FFFFFF, 0x12FFFFFF, Shader.TileMode.CLAMP)
        glassF.shader = LinearGradient(0f, 0f, 0f, tile, 0x66FFFFFF, 0x2EFFFFFF, Shader.TileMode.CLAMP)
        glow.shader = RadialGradient(tile / 2f, tile / 2f, tile * 0.95f, 0x558FA3FF, 0x008FA3FF, Shader.TileMode.CLAMP)
        bgDst.set(0, 0, w, h)
        makeBg(w, h)
        for (r in rows) for (i in r.items) { i.lbl = null; i.sbl = null }
        invalidate()
    }

    // Background is painted once at quarter size and stretched: smooth gradients, tiny memory.
    private fun makeBg(w: Int, h: Int) {
        val bw = max(1, w / 4)
        val bh = max(1, h / 4)
        bg?.recycle()
        val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.DITHER_FLAG)
        val fw = bw.toFloat()
        val fh = bh.toFloat()
        p.shader = LinearGradient(0f, 0f, 0f, fh, 0xFF0A0E1A.toInt(), 0xFF121A3A.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, fw, fh, p)
        p.shader = RadialGradient(fw * 0.12f, fh * 0.02f, fw * 0.62f, 0x703B3FD8, 0x003B3FD8, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, fw, fh, p)
        p.shader = RadialGradient(fw * 0.92f, fh * 1.0f, fw * 0.55f, 0x590E8FA8, 0x000E8FA8, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, fw, fh, p)
        bg = b
    }

    private fun step(v: Float, t: Float, k: Float, dt: Float): Float {
        val n = v + (t - v) * (1f - exp(-k * dt))
        if (abs(t - n) > 0.003f) { moving = true; return n }
        return t
    }

    private fun rowAlpha(d: Float): Float = when {
        d <= -1f -> 0f
        d < 0f -> 1f + d
        d < 1f -> 1f - 0.55f * d
        else -> max(0f, 0.45f - 0.45f * (d - 1f))
    }

    override fun onDraw(c: Canvas) {
        if (ww == 0f) return
        val now = System.nanoTime()
        val dt = if (last == 0L) 0.016f else min((now - last) / 1e9f, 0.05f)
        last = now
        moving = false

        bg?.let { c.drawBitmap(it, null, bgDst, bgPaint) }

        val ms = System.currentTimeMillis()
        if (ms / 60000L != minute) {
            minute = ms / 60000L
            val d = Date(ms)
            timeStr = timeFmt.format(d)
            dateStr = dateFmt.format(d)
        }
        c.drawText(timeStr, margin, hh * 0.22f, pTime)
        c.drawText(dateStr, margin + hh * 0.004f, hh * 0.285f, pDate)

        frAnim = step(frAnim, fr.toFloat(), 12f, dt)
        for (r in rows.indices) {
            val row = rows[r]
            val d = r - frAnim
            val a = rowAlpha(d)
            if (a <= 0.01f) continue
            val y = focusY + d * rowPitch
            pTitle.alpha = (180f * a).toInt()
            c.drawText(row.title, margin, y - hh * 0.022f, pTitle)

            val maxScroll = max(0f, row.items.size * pitch - (ww - margin * 2f))
            row.scroll = step(row.scroll, min(max(0, row.idx - 2) * pitch, maxScroll), 12f, dt)

            for (i in row.items.indices) {
                val x = margin - row.scroll + i * pitch
                if (x > ww || x + tile < 0f) continue
                val focused = r == fr && i == row.idx
                row.scale[i] = step(row.scale[i], if (focused) 1.1f else 1f, 18f, dt)
                drawTile(c, row.items[i], x, y, row.scale[i], a)
            }
        }
        if (moving) postInvalidateOnAnimation() else last = 0L
    }

    private fun drawTile(c: Canvas, item: Item, x: Float, y: Float, s: Float, a: Float) {
        val p = ((s - 1f) / 0.1f).coerceIn(0f, 1f)
        c.save()
        c.translate(x + tile / 2f, y + tile / 2f)
        c.scale(s, s)
        c.translate(-tile / 2f, -tile / 2f)
        if (p > 0.02f) {
            glow.alpha = (255f * p * a).toInt()
            c.drawRect(-tile * 0.4f, -tile * 0.4f, tile * 1.4f, tile * 1.4f, glow)
        }
        glass.alpha = (255f * a).toInt()
        c.drawRoundRect(0f, 0f, tile, tile, rad, rad, glass)
        if (p > 0.02f) {
            glassF.alpha = (255f * p * a).toInt()
            c.drawRoundRect(0f, 0f, tile, tile, rad, rad, glassF)
        }
        stroke.strokeWidth = sw * (1f + 0.6f * p)
        stroke.alpha = ((64f + 170f * p) * a).toInt()
        c.drawRoundRect(0f, 0f, tile, tile, rad, rad, stroke)
        val ic = item.icon
        if (ic != null) {
            bmp.alpha = (255f * a).toInt()
            c.drawBitmap(ic, null, iconDst, bmp)
        } else {
            pInit.alpha = (255f * a).toInt()
            c.drawText(item.initial, tile / 2f, tile / 2f - (pInit.ascent() + pInit.descent()) / 2f, pInit)
        }
        c.restore()

        val cx = x + tile / 2f
        val ly = y + tile + (s - 1f) * tile / 2f + hh * 0.045f
        val lb = item.lbl ?: TextUtils.ellipsize(item.label, pLabel, pitch * 0.96f, TextUtils.TruncateAt.END)
            .also { item.lbl = it }
        pLabel.alpha = ((150f + 105f * p) * a).toInt()
        c.drawText(lb, 0, lb.length, cx, ly, pLabel)
        val sub = item.sub
        if (sub != null) {
            val sb = item.sbl ?: TextUtils.ellipsize(sub, pSub, pitch * 0.96f, TextUtils.TruncateAt.END)
                .also { item.sbl = it }
            pSub.alpha = (120f * a).toInt()
            c.drawText(sb, 0, sb.length, cx, ly + hh * 0.032f, pSub)
        }
    }

    private fun open(item: Item) {
        try {
            context.startActivity(Intent(item.intent))
        } catch (e: Exception) {
            Toast.makeText(context, "Can't open " + item.label, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onKeyDown(k: Int, e: KeyEvent): Boolean {
        if (rows.isEmpty()) return super.onKeyDown(k, e)
        val row = rows[fr]
        when (k) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (row.idx < row.items.size - 1) row.idx++ }
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (row.idx > 0) row.idx-- }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (fr < rows.size - 1) fr++ }
            KeyEvent.KEYCODE_DPAD_UP -> { if (fr > 0) fr-- }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                if (e.repeatCount == 0) open(row.items[row.idx])
            }
            else -> return super.onKeyDown(k, e)
        }
        invalidate()
        return true
    }

    // Air-mouse remotes act as a pointer: tap a tile to open it.
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action != MotionEvent.ACTION_UP) return true
        for (r in rows.indices) {
            val row = rows[r]
            val d = r - frAnim
            if (rowAlpha(d) < 0.3f) continue
            val y = focusY + d * rowPitch
            if (e.y < y || e.y > y + tile) continue
            for (i in row.items.indices) {
                val x = margin - row.scroll + i * pitch
                if (e.x >= x && e.x <= x + tile) {
                    fr = r; row.idx = i
                    invalidate()
                    open(row.items[i])
                    return true
                }
            }
        }
        return true
    }
}
