package com.clean.glasshome

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View

class MainActivity : Activity() {
    private lateinit var home: HomeView

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { load() }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        home = HomeView(this)
        setContentView(home)
        window.setBackgroundDrawable(null)
        val f = IntentFilter()
        f.addAction(Intent.ACTION_PACKAGE_ADDED)
        f.addAction(Intent.ACTION_PACKAGE_REMOVED)
        f.addAction(Intent.ACTION_PACKAGE_CHANGED)
        f.addDataScheme("package")
        registerReceiver(pkgReceiver, f)
        load()
    }

    override fun onDestroy() {
        unregisterReceiver(pkgReceiver)
        super.onDestroy()
    }

    override fun onNewIntent(i: Intent?) {
        super.onNewIntent(i)
        home.resetFocus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            home.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    override fun onBackPressed() {} // stay on Home

    private fun load() {
        Thread {
            val data = Catalog.build(this)
            home.post { home.setData(data) }
        }.start()
    }
}
