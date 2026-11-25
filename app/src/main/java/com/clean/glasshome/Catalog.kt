package com.clean.glasshome

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.provider.Settings
import kotlin.math.max
import kotlin.math.min

object Catalog {
    private val HIDE = setOf(
        "com.hisilicon.hifactory", "com.newlink.factorymenu", "com.newlink.tvtestingtools",
        "com.newlink.isuperred.ijkplayerdemo", "com.example.bluetoothsetting"
    )
    private val VENDOR = listOf("com.hisilicon.", "com.newlink.", "com.zhiying.")
    private val SKIP = listOf("factory", "test", "provision", "demo")
    val DEF: Int = Color.HSVToColor(floatArrayOf(245f, 0.62f, 0.95f))

    fun build(ctx: Context): Data {
        val pm = ctx.packageManager

        // Top-right buttons.
        val pills = ArrayList<Item>()
        fun pill(label: String, action: String, glyph: Int) {
            val i = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (i.resolveActivity(pm) != null) pills.add(Item(label, null, null, i, null, null, DEF, 0, glyph))
        }
        pill("Wi-Fi", Settings.ACTION_WIFI_SETTINGS, Glyph.WIFI)
        pill("Bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS, Glyph.BT)
        pill("Settings", Settings.ACTION_SETTINGS, Glyph.GEAR)

        val rows = ArrayList<Row>()

        // Installed apps (banner if the app ships one, otherwise a coloured tile with its icon).
        val apps = ArrayList<Item>()
        val seen = HashSet<String>()
        for (cat in arrayOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val q = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (ri in pm.queryIntentActivities(q, 0)) {
                val ai = ri.activityInfo
                val p = ai.packageName
                if (p == ctx.packageName || p in HIDE || !seen.add(p)) continue
                val launch = Intent(Intent.ACTION_MAIN).addCategory(cat).setClassName(p, ai.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                val bd: Drawable? = try { ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm) } catch (e: Exception) { null }
                val banner = if (bd != null) bmp(bd, 288, 162) else null
                val icon = bmp(ri.loadIcon(pm), 96, 96)
                val accent = accentOf(banner ?: icon)
                apps.add(Item(ri.loadLabel(pm).toString(), null, p, launch, icon, banner, accent,
                    if (banner == null) tileOf(accent) else 0, Glyph.NONE))
            }
        }
        apps.sortBy { a -> a.label.lowercase() }
        if (apps.isNotEmpty()) rows.add(Row("Apps", apps))

        // Every screen the projector's own apps expose: used to find hidden menus such as keystone.
        val sys = ArrayList<Item>()
        for (app in pm.getInstalledApplications(0)) {
            if (sys.size >= 48) break
            val p = app.packageName
            if (VENDOR.none { v -> p.startsWith(v) } || SKIP.any { s -> p.contains(s) }) continue
            val info = try { pm.getPackageInfo(p, PackageManager.GET_ACTIVITIES) } catch (e: Exception) { continue }
            for (a in info.activities ?: continue) {
                if (sys.size >= 48) break
                if (!a.exported || !a.enabled) continue
                if (SKIP.any { s -> a.name.lowercase().contains(s) }) continue
                val i = Intent().setClassName(p, a.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                sys.add(Item(a.loadLabel(pm).toString(), a.name.substringAfterLast('.'), null, i,
                    bmp(a.loadIcon(pm), 64, 64), null, DEF, 0, Glyph.NONE))
            }
        }
        if (sys.isNotEmpty()) rows.add(Row("System screens", sys))
        return Data(pills, rows)
    }

    private fun bmp(d: Drawable, w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, w, h)
        d.draw(Canvas(b))
        return b
    }

    private fun accentOf(b: Bitmap): Int {
        val s = Bitmap.createScaledBitmap(b, 8, 8, true)
        var r = 0L; var g = 0L; var bl = 0L; var n = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            val p = s.getPixel(x, y)
            if (Color.alpha(p) < 128) continue
            r += Color.red(p); g += Color.green(p); bl += Color.blue(p); n++
        }
        if (n == 0) return DEF
        val hsv = FloatArray(3)
        Color.RGBToHSV((r / n).toInt(), (g / n).toInt(), (bl / n).toInt(), hsv)
        if (hsv[1] < 0.2f) return DEF
        hsv[1] = max(hsv[1], 0.55f)
        hsv[2] = 0.95f
        return Color.HSVToColor(hsv)
    }

    private fun tileOf(accent: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(accent, hsv)
        hsv[1] = min(hsv[1], 0.8f)
        hsv[2] = 0.5f
        return Color.HSVToColor(hsv)
    }
}
