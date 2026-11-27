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
    /**
     * These packages are intentionally omitted from GlassHome's app carousel.
     * This does NOT uninstall their APKs or alter /system. iOSCast is deliberately
     * not in this list, and the factory launcher remains installed as a fallback.
     */
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

    val DEFAULT_ACCENT: Int =
        Color.HSVToColor(floatArrayOf(190f, 0.48f, 0.94f))

    fun build(context: Context): Data {
        val packageManager = context.packageManager

        val shortcuts = ArrayList<Item>()
        fun addShortcut(label: String, action: String, glyph: Int) {
            val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(packageManager) != null) {
                shortcuts.add(
                    Item(label, null, null, intent, null, null, DEFAULT_ACCENT, 0, glyph)
                )
            }
        }

        addShortcut("Wi-Fi", Settings.ACTION_WIFI_SETTINGS, Glyph.WIFI)
        addShortcut("Bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS, Glyph.BT)
        addShortcut("Display", Settings.ACTION_DISPLAY_SETTINGS, Glyph.DISPLAY)
        addShortcut("Sound", Settings.ACTION_SOUND_SETTINGS, Glyph.SOUND)
        addShortcut("Settings", Settings.ACTION_SETTINGS, Glyph.GEAR)

        val appItems = ArrayList<Item>()
        val seenPackages = HashSet<String>()

        // Query both TV and standard launch intents; keep only one entry per package.
        for (category in arrayOf(
            Intent.CATEGORY_LEANBACK_LAUNCHER,
            Intent.CATEGORY_LAUNCHER
        )) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            val activities = packageManager.queryIntentActivities(query, 0)
            for (resolved in activities) {
                val activityInfo = resolved.activityInfo
                val packageName = activityInfo.packageName
                if (packageName == context.packageName ||
                    packageName in HIDE_PACKAGES ||
                    !seenPackages.add(packageName)
                ) continue

                val launchIntent = Intent(Intent.ACTION_MAIN)
                    .addCategory(category)
                    .setClassName(packageName, activityInfo.name)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    )

                val bannerDrawable: Drawable? = try {
                    activityInfo.loadBanner(packageManager)
                        ?: activityInfo.applicationInfo.loadBanner(packageManager)
                } catch (_: Exception) {
                    null
                }

                val banner = bannerDrawable?.let {
                    toBitmap(it, 240, 135, Bitmap.Config.RGB_565)
                }
                val icon = try {
                    toBitmap(resolved.loadIcon(packageManager), 88, 88, Bitmap.Config.ARGB_8888)
                } catch (_: Exception) {
                    null
                }
                val accent = accentFrom(banner ?: icon)
                appItems.add(
                    Item(
                        resolved.loadLabel(packageManager).toString(),
                        null,
                        packageName,
                        launchIntent,
                        icon,
                        banner,
                        accent,
                        if (banner == null) tileFrom(accent) else 0,
                        Glyph.NONE
                    )
                )
            }
        }

        appItems.sortBy { it.label.lowercase() }
        val rows = ArrayList<Row>()
        if (appItems.isNotEmpty()) rows.add(Row("Apps", appItems))

        // The recents row is derived from launches recorded by GlassHome.
        // It appears after the first app has been opened and never needs a database.
        val lastOpened = context.getSharedPreferences("last", Context.MODE_PRIVATE)
        val recentItems = appItems
            .filter { item -> item.pkg?.let { lastOpened.getLong(it, 0L) > 0L } == true }
            .sortedByDescending { item -> item.pkg?.let { lastOpened.getLong(it, 0L) } ?: 0L }
            .take(8)
        if (recentItems.isNotEmpty()) rows.add(Row("Recently opened", recentItems))

        return Data(shortcuts, rows)
    }

    private fun toBitmap(
        drawable: Drawable,
        width: Int,
        height: Int,
        config: Bitmap.Config
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, config)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun accentFrom(bitmap: Bitmap?): Int {
        if (bitmap == null) return DEFAULT_ACCENT
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0
        val stepX = max(1, bitmap.width / 8)
        val stepY = max(1, bitmap.height / 8)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val color = bitmap.getPixel(x, y)
                if (Color.alpha(color) >= 128) {
                    red += Color.red(color)
                    green += Color.green(color)
                    blue += Color.blue(color)
                    count++
                }
                x += stepX
            }
            y += stepY
        }
        if (count == 0) return DEFAULT_ACCENT
        val hsv = FloatArray(3)
        Color.RGBToHSV(
            (red / count).toInt(),
            (green / count).toInt(),
            (blue / count).toInt(),
            hsv
        )
        if (hsv[1] < 0.2f) return DEFAULT_ACCENT
        hsv[1] = max(hsv[1], 0.52f)
        hsv[2] = 0.94f
        return Color.HSVToColor(hsv)
    }

    private fun tileFrom(accent: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(accent, hsv)
        hsv[1] = min(hsv[1], 0.76f)
        hsv[2] = 0.48f
        return Color.HSVToColor(hsv)
    }
}
