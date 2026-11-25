package com.clean.glasshome

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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

/** One custom-drawn view: no layouts, no WebView, no libraries. Light on RAM and CPU. */
class HomeView(ctx: Context) : View(ctx) {
    private var pills: List<Item> = emptyList()
    private var pillSc: Array<Sp> = emptyArray<Sp>()
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
    private var last = 0L

    private var bg: Bitmap? = null
    private val bgDst = Rect()
    private val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val tmp = RectF()
    private val iconRect = RectF()
    private val sysIcon = RectF()
    private val gp = Path()

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
    private var rad = 0f
    private var sw = 1.5f
    private var pd = 0f
    private var pg = 0f

    private fun tf(name: String) = Typeface.create(name, Typeface.NORMAL)
    private fun fillPaint() = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun strokePaint() = Paint(Paint.ANTI_ALIAS_FLAG).also { it.style = Paint.Style.STROKE; it.color = Color.WHITE }

    private val pTime = fillPaint().also { it.color = Color.WHITE; it.typeface = tf("sans-serif-light") }
    private val pDate = fillPaint().also { it.color = Color.WHITE; it.typeface = tf("sans-serif") }
    private val pTitle = fillPaint().also { it.color = Color.WHITE; it.typeface = tf("sans-serif-medium") }
    private val heroP = TextPaint(Paint.ANTI_ALIAS_FLAG).also { it.color = Color.WHITE; it.typeface = tf("sans-serif-light") }
    private val heroS = TextPaint(Paint.ANTI_ALIAS_FLAG).also { it.color = Color.WHITE; it.typeface = tf("sans-serif") }
    private val pInL = TextPaint(Paint.ANTI_ALIAS_FLAG).also { it.color = Color.WHITE; it.typeface = tf("sans-serif-medium") }
    private val pInS = TextPaint(Paint.ANTI_ALIAS_FLAG).also { it.color = Color.WHITE; it.typeface = tf("sans-serif") }
    private val divP = fillPaint().also { it.color = Color.WHITE; it.strokeWidth = 2f }
    private val fill = fillPaint()
    private val shadeP = fillPaint()
    private val glassP = fillPaint()
    private val haloP = fillPaint()
    private val ringP = strokePaint()
    private val edgeP = strokePaint()
    private val bmpP = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dockFill = fillPaint().also { it.color = Color.WHITE }
    private val dockStroke = strokePaint()
    private val pillFill = fillPaint().also { it.color = Color.WHITE }
    private val pillStroke = strokePaint()
    private val glyphP = strokePaint().also { it.strokeCap = Paint.Cap.ROUND; it.strokeJoin = Paint.Join.ROUND }
    private val glyphFill = fillPaint().also { it.color = Color.WHITE }

    private val prefs = ctx.getSharedPreferences("last", Context.MODE_PRIVATE)
    private val timeFmt = DateFormat.getTimeFormat(ctx)
    private val dateFmt = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault())
    private val shortFmt = SimpleDateFormat("d MMM", Locale.getDefault())
    private var minute = -1L
    private var timeStr = ""
    private var dateStr = ""
    private var timeW = 0f
    private val tick = object : Runnable {
        override fun run() { invalidate(); postDelayed(this, 60000) }
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

    fun setData(d: Data) {
        val keep = rows.getOrNull(fr)?.title
        pills = d.pills
        pillSc = Array(pills.size) { Sp(1f) }
        rows = d.rows
        val k = rows.indexOfFirst { r -> r.title == (keep ?: "Apps") }
        fr = if (k >= 0) k else 0
        ti = min(ti, max(0, pills.size - 1))
        if (pills.isEmpty()) onBar = false
        heroItem = null
        invalidate()
    }

    fun resetFocus() {
        fr = 0; ti = 0; onBar = false
        for (r in rows) r.idx = 0
        heroItem = null
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        ww = w.toFloat(); hh = h.toFloat()
        th = hh * 0.16f
        tw = th * 1.72f
        gap = hh * 0.022f
        pitch = tw + gap
        padC = hh * 0.02f
        margin = ww * 0.05f
        rowPitch = hh * 0.30f
        dockY = hh * 0.62f
        rad = th * 0.17f
        sw = max(1.5f, hh * 0.0018f)
        pd = hh * 0.062f
        pg = hh * 0.016f
        pTime.textSize = hh * 0.07f
        pDate.textSize = hh * 0.03f
        pTitle.textSize = hh * 0.024f
        heroP.textSize = hh * 0.075f
        heroS.textSize = hh * 0.028f
        pInL.textSize = hh * 0.026f
        pInS.textSize = hh * 0.019f
        val isz = th * 0.56f
        iconRect.set((tw - isz) / 2f, (th - isz) / 2f, (tw + isz) / 2f, (th + isz) / 2f)
        val ss = th * 0.3f
        sysIcon.set(th * 0.12f, th * 0.12f, th * 0.12f + ss, th * 0.12f + ss)
        shadeP.shader = LinearGradient(0f, 0f, 0f, th, 0x00000000, 0x70000000, Shader.TileMode.CLAMP)
        glassP.shader = LinearGradient(0f, 0f, 0f, th, 0x38FFFFFF, 0x14FFFFFF, Shader.TileMode.CLAMP)
        haloP.shader = RadialGradient(tw / 2f, th / 2f, tw * 0.62f, 0x70FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        glowP.shader = RadialGradient(ww * 0.28f, hh * 0.30f, ww * 0.8f,
            intArrayOf(0xB0FFFFFF.toInt(), 0x50FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        bgDst.set(0, 0, w, h)
        makeBg(w, h)
        for (r in rows) for (i in r.items) { i.lbl = null; i.sbl = null; i.bp = null }
        minute = -1L
        invalidate()
    }

    private fun makeBg(w: Int, h: Int) {
        // Render once at one-third resolution and scale it; RGB_565 halves wallpaper memory.
        val bw = max(1, w / 3)
        val bh = max(1, h / 3)
        bg?.recycle()
        val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.RGB_565)
        val c = Canvas(b)
        val p = Paint(Paint.DITHER_FLAG)
        p.shader = LinearGradient(0f, 0f, bw.toFloat(), bh.toFloat(),
            0xFF080D1B.toInt(), 0xFF17152F.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, bw.toFloat(), bh.toFloat(), p)
        p.shader = RadialGradient(bw * 0.78f, bh * 0.20f, bw * 0.72f,
            intArrayOf(0x553C82B8, 0x223C82B8, 0x003C82B8), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, bw.toFloat(), bh.toFloat(), p)
        p.shader = RadialGradient(bw * 0.12f, bh * 0.82f, bw * 0.70f,
            intArrayOf(0x554F3D91, 0x224F3D91, 0x004F3D91), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, bw.toFloat(), bh.toFloat(), p)
        p.shader = null
        bg = b
    }

    private fun rowAlpha(d: Float): Float = when {
        d <= -1f -> 0f
        d < 0f -> 1f + d
        d < 1f -> 1f - 0.4f * d
        else -> max(0f, 0.6f - 0.6f * (d - 1f))
    }

    private fun focusItem(): Item? {
        if (onBar) return pills.getOrNull(ti)
        val row = rows.getOrNull(fr) ?: return null
        return row.items.getOrNull(row.idx)
    }

    private fun rel(ms: Long): String {
        val now = System.currentTimeMillis()
        val off = TimeZone.getDefault().getOffset(now).toLong()
        val day = 86400000L
        val diff = (now + off) / day - (ms + off) / day
        val t = timeFmt.format(Date(ms))
        return when (diff) {
            0L -> "today, $t"
            1L -> "yesterday, $t"
            else -> shortFmt.format(Date(ms))
        }
    }

    private fun subFor(item: Item?): String {
        if (item == null) return ""
        val s = item.sub
        if (s != null) return s
        val p = item.pkg ?: return "Press OK to open"
        val t = prefs.getLong(p, 0L)
        return if (t == 0L) "Press OK to open" else "Last opened " + rel(t)
    }

    private fun setHero(item: Item?) {
        prevLabel = heroLabel
        prevSub = heroSub
        heroItem = item
        heroLabel = if (item == null) "" else TextUtils.ellipsize(item.label, heroP, ww * 0.7f, TextUtils.TruncateAt.END)
        heroSub = subFor(item)
        heroA.x = 0f
        heroA.v = 0f
    }

    private fun pillCx(i: Int): Float = ww - margin - pd / 2f - (pills.size - 1 - i) * (pd + pg)
    private fun pillCy(): Float = hh * 0.078f

    override fun onDraw(c: Canvas) {
        if (ww == 0f) return
        val now = System.nanoTime()
        val dt = if (last == 0L) 0.016f else min((now - last) / 1e9f, 0.05f)
        last = now
        var busy = false

        val fi = focusItem()
        if (fi !== heroItem) setHero(fi)

        // Static cached wallpaper: no per-frame blur, glow, or color animation.
        bg?.let { c.drawBitmap(it, null, bgDst, bgPaint) }

        // Clock and date.
        val ms = System.currentTimeMillis()
        if (ms / 60000L != minute) {
            minute = ms / 60000L
            val d = Date(ms)
            timeStr = timeFmt.format(d)
            dateStr = dateFmt.format(d)
            timeW = pTime.measureText(timeStr)
        }
        val base = hh * 0.103f
        pTime.alpha = 255
        c.drawText(timeStr, margin, base, pTime)
        val dx = margin + timeW + hh * 0.022f
        divP.alpha = 90
        c.drawLine(dx, base - hh * 0.042f, dx, base + hh * 0.004f, divP)
        pDate.alpha = 190
        c.drawText(dateStr, dx + hh * 0.022f, base, pDate)

        // Top-right buttons.
        for (i in pills.indices) {
            val focused = onBar && i == ti
            if (pillSc[i].go(if (focused) 1.18f else 1f, 420f, 26f, dt)) busy = true
            drawPill(c, pills[i], pillCx(i), pillCy(), pillSc[i].x)
        }

        // Hero title: crossfades when focus changes.
        if (heroA.go(1f, 260f, 32f, dt)) busy = true
        val ha = 1f - frAnim.x.coerceIn(0f, 1f)
        if (ha > 0.01f) {
            val t = heroA.x.coerceIn(0f, 1f)
            val hx = margin + padC
            val hy = hh * 0.45f
            if (t < 0.99f) {
                heroP.alpha = (255f * (1f - t) * ha).toInt()
                c.drawText(prevLabel, 0, prevLabel.length, hx, hy - hh * 0.012f * t, heroP)
                heroS.alpha = (150f * (1f - t) * ha).toInt()
                c.drawText(prevSub, 0, prevSub.length, hx, hy + hh * 0.056f - hh * 0.012f * t, heroS)
            }
            heroP.alpha = (255f * t * ha).toInt()
            c.drawText(heroLabel, 0, heroLabel.length, hx, hy + hh * 0.014f * (1f - t), heroP)
            heroS.alpha = (150f * t * ha).toInt()
            c.drawText(heroSub, 0, heroSub.length, hx, hy + hh * 0.056f + hh * 0.014f * (1f - t), heroS)
        }

        // Rows.
        if (frAnim.go(fr.toFloat(), 220f, 29.7f, dt)) busy = true
        val vw = ww - 2f * margin - 2f * padC
        for (r in rows.indices) {
            val row = rows[r]
            val d = r - frAnim.x
            val a = rowAlpha(d)
            val maxScroll = max(0f, row.items.size * pitch - gap - vw)
            val target = min(max(0, row.idx - 1) * pitch, maxScroll)
            if (row.scroll.go(target, 220f, 29.7f, dt, 0.3f)) busy = true
            for (i in row.items.indices) {
                val on = !onBar && r == fr && i == row.idx
                if (row.sc[i].go(if (on) 1.09f else 1f, 420f, 26f, dt)) busy = true
            }
            if (a <= 0.01f) continue
            val y = dockY + d * rowPitch

            dockFill.alpha = (36f * a).toInt()
            dockStroke.strokeWidth = sw
            dockStroke.alpha = (64f * a).toInt()
            tmp.set(margin, y - padC, ww - margin, y + th + padC)
            c.drawRoundRect(tmp, th * 0.26f, th * 0.26f, dockFill)
            c.drawRoundRect(tmp, th * 0.26f, th * 0.26f, dockStroke)
            if (r == 1) {
                pTitle.alpha = (170f * a).toInt()
                c.drawText(row.title, margin + padC, y - padC - hh * 0.014f, pTitle)
            }

            c.save()
            c.clipRect(margin + padC * 0.4f, 0f, ww - margin - padC * 0.4f, hh)
            for (i in row.items.indices) {
                val x = margin + padC - row.scroll.x + i * pitch
                if (x > ww || x + tw < 0f) continue
                drawTile(c, row.items[i], x, y, row.sc[i].x, a)
            }
            c.restore()
        }

        if (busy) postInvalidateOnAnimation() else last = 0L
    }

    private fun makeBp(item: Item, b: Bitmap): Paint {
        val sh = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val sc = max(tw / b.width, th / b.height)
        val m = Matrix()
        m.setScale(sc, sc)
        m.postTranslate((tw - b.width * sc) / 2f, (th - b.height * sc) / 2f)
        sh.setLocalMatrix(m)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = sh
        item.bp = p
        return p
    }

    private fun drawTile(c: Canvas, item: Item, x: Float, y: Float, s: Float, a: Float) {
        val p = ((s - 1f) / 0.09f).coerceIn(0f, 1f)
        c.save()
        c.translate(x + tw / 2f, y + th / 2f)
        c.scale(s, s)
        c.translate(-tw / 2f, -th / 2f)
        if (p > 0.02f) {
            haloP.alpha = (150f * p * a).toInt()
            c.drawRect(-tw * 0.25f, -th * 0.5f, tw * 1.25f, th * 1.5f, haloP)
        }
        val ia = (255f * a).toInt()
        val bn = item.banner
        if (bn != null) {
            val bp = item.bp ?: makeBp(item, bn)
            bp.alpha = ia
            c.drawRoundRect(0f, 0f, tw, th, rad, rad, bp)
        } else if (item.tile != 0) {
            fill.color = item.tile
            fill.alpha = ia
            c.drawRoundRect(0f, 0f, tw, th, rad, rad, fill)
            shadeP.alpha = ia
            c.drawRoundRect(0f, 0f, tw, th, rad, rad, shadeP)
            val ic = item.icon
            if (ic != null) { bmpP.alpha = ia; c.drawBitmap(ic, null, iconRect, bmpP) }
        } else {
            glassP.alpha = ia
            c.drawRoundRect(0f, 0f, tw, th, rad, rad, glassP)
            val ic = item.icon
            if (ic != null) { bmpP.alpha = ia; c.drawBitmap(ic, null, sysIcon, bmpP) }
            val lb = item.lbl ?: TextUtils.ellipsize(item.label, pInL, tw * 0.86f, TextUtils.TruncateAt.END).also { item.lbl = it }
            pInL.alpha = ia
            c.drawText(lb, 0, lb.length, th * 0.12f, th * 0.72f, pInL)
            val sub = item.sub
            if (sub != null) {
                val sb = item.sbl ?: TextUtils.ellipsize(sub, pInS, tw * 0.86f, TextUtils.TruncateAt.END).also { item.sbl = it }
                pInS.alpha = (150f * a).toInt()
                c.drawText(sb, 0, sb.length, th * 0.12f, th * 0.87f, pInS)
            }
        }
        edgeP.strokeWidth = sw
        edgeP.alpha = (50f * (1f - p) * a).toInt()
        c.drawRoundRect(0f, 0f, tw, th, rad, rad, edgeP)
        if (p > 0.02f) {
            ringP.strokeWidth = sw * 2.2f
            ringP.alpha = (255f * p * a).toInt()
            val o = ringP.strokeWidth * 0.9f
            c.drawRoundRect(-o, -o, tw + o, th + o, rad + o, rad + o, ringP)
        }
        c.restore()
    }

    private fun drawPill(c: Canvas, item: Item, cx: Float, cy: Float, s: Float) {
        val p = ((s - 1f) / 0.18f).coerceIn(0f, 1f)
        c.save()
        c.translate(cx, cy)
        c.scale(s, s)
        pillFill.alpha = (40f + 60f * p).toInt()
        c.drawCircle(0f, 0f, pd / 2f, pillFill)
        pillStroke.strokeWidth = sw * (1f + p)
        pillStroke.alpha = (70f + 185f * p).toInt()
        c.drawCircle(0f, 0f, pd / 2f, pillStroke)
        glyph(c, item.glyph, pd)
        c.restore()
    }

    private fun glyph(c: Canvas, g: Int, d: Float) {
        glyphP.strokeWidth = d * 0.07f
        when (g) {
            Glyph.WIFI -> {
                val oy = d * 0.16f
                for (k in 1..3) {
                    val r = d * 0.1f * k
                    tmp.set(-r, oy - r, r, oy + r)
                    c.drawArc(tmp, 225f, 90f, false, glyphP)
                }
                c.drawCircle(0f, oy, d * 0.04f, glyphFill)
            }
            Glyph.GEAR -> {
                c.drawCircle(0f, 0f, d * 0.17f, glyphP)
                for (k in 0 until 8) {
                    val an = k * 0.7853982f
                    val ca = cos(an)
                    val sa = sin(an)
                    c.drawLine(ca * d * 0.17f, sa * d * 0.17f, ca * d * 0.27f, sa * d * 0.27f, glyphP)
                }
            }
            Glyph.BT -> {
                gp.reset()
                gp.moveTo(-0.17f * d, -0.17f * d)
                gp.lineTo(0.17f * d, 0.17f * d)
                gp.lineTo(0f, 0.3f * d)
                gp.lineTo(0f, -0.3f * d)
                gp.lineTo(0.17f * d, -0.17f * d)
                gp.lineTo(-0.17f * d, 0.17f * d)
                c.drawPath(gp, glyphP)
            }
        }
    }

    private fun open(item: Item) {
        item.pkg?.let { prefs.edit().putLong(it, System.currentTimeMillis()).apply() }
        try {
            context.startActivity(Intent(item.intent))
        } catch (e: Exception) {
            Toast.makeText(context, "Can't open " + item.label, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onKeyDown(k: Int, e: KeyEvent): Boolean {
        if (rows.isEmpty() && pills.isEmpty()) return super.onKeyDown(k, e)
        val row = rows.getOrNull(fr)
        when (k) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (onBar) { if (ti < pills.size - 1) ti++ }
                else if (row != null && row.idx < row.items.size - 1) row.idx++
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (onBar) { if (ti > 0) ti-- }
                else if (row != null && row.idx > 0) row.idx--
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
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                if (e.repeatCount == 0) focusItem()?.let { open(it) }
            }
            else -> return super.onKeyDown(k, e)
        }
        invalidate()
        return true
    }

    // Air-mouse remotes act as a pointer: tap a button or tile to open it.
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action != MotionEvent.ACTION_UP) return true
        for (i in pills.indices) {
            val dx = e.x - pillCx(i)
            val dy = e.y - pillCy()
            if (dx * dx + dy * dy <= pd * pd * 0.36f) {
                onBar = true; ti = i
                invalidate()
                open(pills[i])
                return true
            }
        }
        for (r in rows.indices) {
            val row = rows[r]
            val d = r - frAnim.x
            if (rowAlpha(d) < 0.3f) continue
            val y = dockY + d * rowPitch
            if (e.y < y || e.y > y + th) continue
            for (i in row.items.indices) {
                val x = margin + padC - row.scroll.x + i * pitch
                if (e.x >= x && e.x <= x + tw) {
                    onBar = false; fr = r; row.idx = i
                    invalidate()
                    open(row.items[i])
                    return true
                }
            }
        }
        return true
    }
}
