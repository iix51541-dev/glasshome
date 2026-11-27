package com.clean.glasshome

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.provider.Settings
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

object Catalog {
    /** These system/testing packages stay out of the app dock. They are NOT uninstalled. */
    private val HIDE_PACKAGES = setOf(
        "com.hisilicon.hifactory",
        "com.newlink.factorymenu",
        "com.newlink.tvtestingtools",
        "com.newlink.isuperred.ijkplayerdemo",
        "com.example.bluetoothsetting",
        "com.amazon.avod.thirdpartyclient",
        "com.zhiying.appstore",
        "com.mobisystems.editor.office_registered",
        "com.netflix.mediaclient",
        "com.hisilicon.miracast",
        "org.xbmc.kodi",
        "com.zhiying.ytb.zy.tv"
    )

    val AMBER: Int = Color.rgb(255, 169, 72)

    private const val TILE_W = 320
    private const val TILE_H = 180
    private const val ICON_PX = 128

    private class Rendered(val art: Bitmap, val icon: Bitmap?, val accent: Int, val tile: Int)

    /** Rendered tiles are reused between rebuilds so returning to the launcher stays instant. */
    private val cache = ConcurrentHashMap<String, Rendered>()

    fun invalidate(pkg: String?) {
        if (pkg == null) { cache.clear(); return }
        val prefix = "$pkg/"
        val iterator = cache.keys.iterator()
        while (iterator.hasNext()) if (iterator.next().startsWith(prefix)) iterator.remove()
    }

    fun build(context: Context): Data {
        val pm = context.packageManager
        val shortcuts = ArrayList<Item>()

        // Keep the projector's Store button when its factory store is present.
        try {
            val storeIntent = pm.getLaunchIntentForPackage("com.zhiying.appstore")
            if (storeIntent != null) {
                shortcuts.add(Item("Store", "Projector app store", "com.zhiying.appstore",
                    storeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), null, null,
                    AMBER, 0xFF6A371B.toInt(), Glyph.STORE))
            }
        } catch (_: Exception) { }

        addShortcut(pm, shortcuts, "Wi-Fi", Settings.ACTION_WIFI_SETTINGS, Glyph.WIFI)
        // Unchanged behaviour: whatever the projector resolves ACTION_SETTINGS to.
        addShortcut(pm, shortcuts, "Settings", Settings.ACTION_SETTINGS, Glyph.SETTINGS)
        // Full native Android Settings app (only when it is a different screen from the one above).
        val nativeSettings = nativeSettingsIntent(pm)
        if (nativeSettings != null) {
            shortcuts.add(Item("System", null, null, nativeSettings, null, null,
                AMBER, 0xFF51301B.toInt(), Glyph.SYSTEM))
        }

        val apps = ArrayList<Item>()
        val seen = HashSet<String>()
        for (category in arrayOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            val activities: List<android.content.pm.ResolveInfo> = try {
                pm.queryIntentActivities(query, 0)
            } catch (_: Exception) { emptyList() }
            for (resolved in activities) {
                val info = resolved.activityInfo ?: continue
                val packageName = info.packageName
                if (packageName == context.packageName || packageName in HIDE_PACKAGES || !seen.add(packageName)) continue

                val launchIntent = Intent(Intent.ACTION_MAIN)
                    .addCategory(category)
                    .setClassName(packageName, info.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

                val key = packageName + "/" + info.name
                var rendered = cache[key]
                if (rendered == null) {
                    rendered = try { render(pm, resolved) } catch (_: Throwable) { null }
                    if (rendered != null) cache[key] = rendered
                }
                if (rendered == null) continue
                apps.add(Item(
                    resolved.loadLabel(pm).toString(), null, packageName, launchIntent,
                    rendered.icon, null, rendered.accent, rendered.tile, Glyph.NONE, rendered.art
                ))
            }
        }
        apps.sortBy { it.label.lowercase() }

        // Move apps opened recently toward the front without duplicating them.
        val recentPrefs = context.getSharedPreferences("last", Context.MODE_PRIVATE)
        val ordered = apps.sortedWith(
            compareByDescending<Item> { it.pkg?.let { pkg -> recentPrefs.getLong(pkg, 0L) } ?: 0L }
                .thenBy { it.label.lowercase() }
        )
        val rows = if (ordered.isEmpty()) emptyList() else listOf(Row("Apps", ordered))
        return Data(shortcuts, rows)
    }

    private fun render(pm: PackageManager, resolved: android.content.pm.ResolveInfo): Rendered? {
        val info = resolved.activityInfo ?: return null
        val icon = try { drawableToBitmap(resolved.loadIcon(pm), ICON_PX, ICON_PX) } catch (_: Exception) { null }
        val banner: Drawable? = try {
            info.loadBanner(pm) ?: info.applicationInfo.loadBanner(pm)
        } catch (_: Exception) { null }
        val cover = banner?.let { try { renderCover(it, TILE_W, TILE_H) } catch (_: Exception) { null } }
        val accent = accentFrom(cover ?: icon)
        val tile = tileFrom(accent)
        val art = composeTile(icon, cover, accent, tile)
        return Rendered(art, icon, accent, tile)
    }

    /**
     * Every dock tile is drawn here with the same size, corner radius, sheen and edge, so the
     * dock looks uniform no matter what shape the original app artwork has. Banner artwork is
     * cover-cropped (never stretched); plain icons are centred on a tinted glass-like card.
     */
    private fun composeTile(icon: Bitmap?, cover: Bitmap?, accent: Int, tile: Int): Bitmap {
        val w = TILE_W.toFloat()
        val h = TILE_H.toFloat()
        val out = Bitmap.createBitmap(TILE_W, TILE_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val radius = h * 0.22f
        val rect = RectF(0f, 0f, w, h)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        if (cover != null) {
            paint.shader = BitmapShader(cover, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.shader = LinearGradient(0f, h * 0.6f, 0f, h, 0x00000000, 0x4D000000, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(rect, radius, radius, paint)
        } else {
            paint.shader = LinearGradient(0f, 0f, 0f, h,
                scaleValue(tile, 1.6f), scaleValue(tile, 0.72f), Shader.TileMode.CLAMP)
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.shader = RadialGradient(w * 0.5f, h * 0.42f, h * 0.95f,
                intArrayOf(withAlpha(accent, 0x66), withAlpha(accent, 0x00)), null, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(rect, radius, radius, paint)
            if (icon != null) {
                val side = h * 0.58f
                val dst = RectF((w - side) / 2f, (h - side) / 2f, (w + side) / 2f, (h + side) / 2f)
                paint.shader = null
                canvas.drawBitmap(icon, null, dst, paint)
            }
        }
        // Soft top sheen for the glass look.
        paint.shader = LinearGradient(0f, 0f, 0f, h * 0.5f, 0x30FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(RectF(0f, 0f, w, h * 0.5f), radius, radius, paint)
        // Thin edge highlight.
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = 0x38FFFFFF
        canvas.drawRoundRect(RectF(1f, 1f, w - 1f, h - 1f), radius, radius, paint)
        return out
    }

    /** Native Android Settings, only when it differs from what ACTION_SETTINGS opens. */
    private fun nativeSettingsIntent(pm: PackageManager): Intent? {
        val generic = Intent(Settings.ACTION_SETTINGS).resolveActivity(pm)
        val candidates = ArrayList<Intent>()
        candidates.add(Intent().setClassName("com.android.settings", "com.android.settings.Settings"))
        candidates.add(Intent().setClassName("com.android.settings", "com.android.settings.homepage.SettingsHomepageActivity"))
        candidates.add(Intent(Settings.ACTION_SETTINGS).setPackage("com.android.settings"))
        for (candidate in candidates) {
            val found = (try { candidate.resolveActivity(pm) } catch (_: Exception) { null }) ?: continue
            if (generic != null && found.packageName == generic.packageName && found.className == generic.className) continue
            return candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return null
    }

    private fun addShortcut(
        pm: PackageManager,
        target: MutableList<Item>,
        label: String,
        action: String,
        glyph: Int,
        fallbackAction: String? = null
    ) {
        val candidates = listOfNotNull(action, fallbackAction)
        for (candidate in candidates) {
            val intent = Intent(candidate).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(pm) != null) {
                target.add(Item(label, null, null, intent, null, null, AMBER, 0xFF51301B.toInt(), glyph))
                return
            }
        }
    }

    private fun drawableToBitmap(drawable: Drawable, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)
        return bitmap
    }

    /** Draws a drawable so it fills width x height, cropping the overflow instead of stretching. */
    private fun renderCover(drawable: Drawable, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val iw = drawable.intrinsicWidth
        val ih = drawable.intrinsicHeight
        if (iw > 0 && ih > 0) {
            val scale = max(width / iw.toFloat(), height / ih.toFloat())
            val dw = (iw * scale).toInt()
            val dh = (ih * scale).toInt()
            val left = (width - dw) / 2
            val top = (height - dh) / 2
            drawable.setBounds(left, top, left + dw, top + dh)
        } else {
            drawable.setBounds(0, 0, width, height)
        }
        drawable.draw(canvas)
        return bitmap
    }

    /** Centre-crops [src] to a square with rounded corners. Used for album art. */
    fun roundedSquare(src: Bitmap, size: Int, radiusFraction: Float): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val side = min(src.width, src.height)
        val scale = size.toFloat() / side.toFloat()
        val matrix = Matrix()
        matrix.setScale(scale, scale)
        matrix.preTranslate(-((src.width - side) / 2).toFloat(), -((src.height - side) / 2).toFloat())
        val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        shader.setLocalMatrix(matrix)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.shader = shader
        val r = size * radiusFraction
        Canvas(out).drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), r, r, paint)
        return out
    }

    fun accentFrom(bitmap: Bitmap?): Int {
        if (bitmap == null) return AMBER
        var red = 0L; var green = 0L; var blue = 0L; var count = 0
        val stepX = max(1, bitmap.width / 8)
        val stepY = max(1, bitmap.height / 8)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val color = bitmap.getPixel(x, y)
                if (Color.alpha(color) >= 128) {
                    red += Color.red(color); green += Color.green(color); blue += Color.blue(color); count++
                }
                x += stepX
            }
            y += stepY
        }
        if (count == 0) return AMBER
        val hsv = FloatArray(3)
        Color.RGBToHSV((red / count).toInt(), (green / count).toInt(), (blue / count).toInt(), hsv)
        if (hsv[1] < 0.2f) return AMBER
        hsv[1] = max(hsv[1], 0.52f)
        hsv[2] = 0.94f
        return Color.HSVToColor(hsv)
    }

    private fun tileFrom(accent: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(accent, hsv)
        hsv[1] = min(hsv[1], 0.76f)
        hsv[2] = 0.38f
        return Color.HSVToColor(hsv)
    }

    private fun scaleValue(color: Int, factor: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[2] = (hsv[2] * factor).coerceIn(0f, 1f)
        return Color.HSVToColor(hsv)
    }

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
}
