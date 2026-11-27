package com.clean.glasshome

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.provider.Settings
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

    private val AMBER = Color.rgb(255, 169, 72)

    fun build(context: Context): Data {
        val pm = context.packageManager
        val shortcuts = ArrayList<Item>()

        // Keep the projector's Store button when its factory store is present.
        try {
            val storeIntent = pm.getLaunchIntentForPackage("com.zhiying.appstore")
            if (storeIntent != null) {
                val icon = try {
                    toBitmap(pm.getApplicationIcon("com.zhiying.appstore"), 88, 88, Bitmap.Config.ARGB_8888)
                } catch (_: Exception) { null }
                shortcuts.add(Item("Store", "Projector app store", "com.zhiying.appstore",
                    storeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), icon, null,
                    AMBER, 0xFF6A371B.toInt(), Glyph.STORE))
            }
        } catch (_: Exception) { }

        addShortcut(pm, shortcuts, "Wi-Fi", Settings.ACTION_WIFI_SETTINGS, Glyph.WIFI)
        addShortcut(pm, shortcuts, "Settings", Settings.ACTION_SETTINGS, Glyph.SETTINGS)
        addShortcut(
            pm, shortcuts, "Apps", Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS,
            Glyph.APPS, Settings.ACTION_APPLICATION_SETTINGS
        )
        shortcuts.add(Item(
            "Wallpaper", "Choose your own background", null,
            Intent("com.clean.glasshome.PICK_WALLPAPER"), null, null,
            AMBER, 0xFF54301B.toInt(), Glyph.WALLPAPER
        ))

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

                val bannerDrawable: Drawable? = try {
                    info.loadBanner(pm) ?: info.applicationInfo.loadBanner(pm)
                } catch (_: Exception) { null }
                val banner = bannerDrawable?.let { toBitmap(it, 240, 135, Bitmap.Config.RGB_565) }
                val icon = try { toBitmap(resolved.loadIcon(pm), 96, 96, Bitmap.Config.ARGB_8888) }
                    catch (_: Exception) { null }
                val accent = accentFrom(banner ?: icon)
                apps.add(Item(
                    resolved.loadLabel(pm).toString(), null, packageName, launchIntent,
                    icon, banner, accent, tileFrom(accent), Glyph.NONE
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

    private fun addShortcut(
        contextPm: android.content.pm.PackageManager,
        target: MutableList<Item>,
        label: String,
        action: String,
        glyph: Int,
        fallbackAction: String? = null
    ) {
        val candidates = listOfNotNull(action, fallbackAction)
        for (candidate in candidates) {
            val intent = Intent(candidate).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(contextPm) != null) {
                target.add(Item(label, null, null, intent, null, null, AMBER, 0xFF51301B.toInt(), glyph))
                return
            }
        }
    }

    private fun toBitmap(drawable: Drawable, width: Int, height: Int, config: Bitmap.Config): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, config)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun accentFrom(bitmap: Bitmap?): Int {
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
}
