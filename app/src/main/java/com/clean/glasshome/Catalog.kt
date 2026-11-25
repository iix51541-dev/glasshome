package com.clean.glasshome

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.provider.Settings

object Catalog {
    // Factory/test apps that should never show on Home.
    private val HIDE = setOf(
        "com.hisilicon.hifactory", "com.newlink.factorymenu", "com.newlink.tvtestingtools",
        "com.newlink.isuperred.ijkplayerdemo", "com.example.bluetoothsetting"
    )
    private val VENDOR = listOf("com.hisilicon.", "com.newlink.", "com.zhiying.")
    private val SKIP = listOf("factory", "test", "provision", "demo")

    fun build(ctx: Context): List<Row> {
        val pm = ctx.packageManager
        val rows = ArrayList<Row>()

        // Shortcuts to standard Android settings screens.
        val shortcuts = ArrayList<Item>()
        fun add(label: String, action: String) {
            val i = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (i.resolveActivity(pm) != null) shortcuts.add(Item(label, null, null, i))
        }
        add("Settings", Settings.ACTION_SETTINGS)
        add("Wi-Fi", Settings.ACTION_WIFI_SETTINGS)
        add("Bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS)
        add("Display", Settings.ACTION_DISPLAY_SETTINGS)
        if (shortcuts.isNotEmpty()) rows.add(Row("Shortcuts", shortcuts))

        // Installed apps.
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
                apps.add(Item(ri.loadLabel(pm).toString(), null, icon(ri.loadIcon(pm)), launch))
            }
        }
        apps.sortBy { a -> a.label.lowercase() }
        if (apps.isNotEmpty()) rows.add(Row("Apps", apps))

        // Every screen the projector's own apps expose. Used to find hidden menus such as keystone.
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
                sys.add(Item(a.loadLabel(pm).toString(), a.name.substringAfterLast('.'), icon(a.loadIcon(pm)), i))
            }
        }
        if (sys.isNotEmpty()) rows.add(Row("System screens", sys))
        return rows
    }

    private fun icon(d: Drawable): Bitmap {
        val s = 112
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, s, s)
        d.draw(Canvas(b))
        return b
    }
}
