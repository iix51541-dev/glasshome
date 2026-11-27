package com.clean.glasshome

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import java.io.File

class MainActivity : Activity() {
    private lateinit var home: HomeView
    private var mediaMonitor: MediaMonitor? = null
    private var receiverRegistered = false
    @Volatile private var weatherRequestRunning = false
    @Volatile private var loadGeneration = 0
    private val wallpaperFile by lazy { File(filesDir, "glasshome-wallpaper.img") }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Catalog.invalidate(intent?.data?.schemeSpecificPart)
            loadApps()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        home = HomeView(this)
        setContentView(home)
        window.setBackgroundDrawable(null)
        restoreWallpaper()

        mediaMonitor = MediaMonitor(applicationContext) { info ->
            runOnUiThread { if (!isFinishing && ::home.isInitialized) home.setMedia(info) }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        registerReceiver(packageReceiver, filter)
        receiverRegistered = true
    }

    override fun onResume() {
        super.onResume()
        if (::home.isInitialized) {
            loadApps()
            refreshWeather()
            home.onHostResume()
            mediaMonitor?.start()
        }
        applyImmersiveMode()
    }

    override fun onPause() {
        if (::home.isInitialized) home.onHostPause()
        mediaMonitor?.stop()
        super.onPause()
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(packageReceiver)
            receiverRegistered = false
        }
        mediaMonitor?.stop()
        mediaMonitor = null
        if (::home.isInitialized) home.release()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        home.resetFocus()
        loadApps()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    private fun applyImmersiveMode() {
        home.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    override fun onBackPressed() {
        if (::home.isInitialized) home.returnToDock()
    }

    private fun loadApps() {
        // Only the newest request is applied, so overlapping reloads can never apply stale data.
        val generation = ++loadGeneration
        Thread {
            try {
                val data = Catalog.build(applicationContext)
                runOnUiThread {
                    if (!isFinishing && ::home.isInitialized && generation == loadGeneration) home.setData(data)
                }
            } catch (_: Throwable) { }
        }.start()
    }

    /** Restores a wallpaper chosen in an earlier version; otherwise the built-in wallpaper is used. */
    private fun restoreWallpaper() {
        if (!wallpaperFile.exists()) return
        Thread {
            val bitmap = try { decodeWallpaper(wallpaperFile) } catch (_: Throwable) { null }
            if (bitmap != null) runOnUiThread {
                if (!isFinishing && ::home.isInitialized) home.setWallpaper(bitmap) else bitmap.recycle()
            }
        }.start()
    }

    private fun decodeWallpaper(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 900) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
            inDither = true
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private fun refreshWeather() {
        val store = getSharedPreferences("weather", Context.MODE_PRIVATE)
        val cached = readCachedWeather(store)
        home.setWeather(cached)
        val lastAttempt = store.getLong("last_attempt", 0L)
        val now = System.currentTimeMillis()
        if (weatherRequestRunning || now - lastAttempt < 10 * 60 * 1000L) return
        weatherRequestRunning = true
        Thread {
            val fresh = WeatherService.fetch()
            store.edit().putLong("last_attempt", System.currentTimeMillis()).apply()
            if (fresh != null) {
                store.edit()
                    .putString("city", fresh.city)
                    .putInt("temperature", fresh.temperature)
                    .putInt("feels_like", fresh.feelsLike)
                    .putString("description", fresh.description)
                    .putInt("humidity", fresh.humidity)
                    .putInt("wind", fresh.windKmh)
                    .putLong("last_success", System.currentTimeMillis())
                    .apply()
            }
            val display = fresh ?: cached
            runOnUiThread {
                weatherRequestRunning = false
                if (!isFinishing && ::home.isInitialized) home.setWeather(display)
            }
        }.start()
    }

    private fun readCachedWeather(store: android.content.SharedPreferences): WeatherInfo? {
        if (store.getLong("last_success", 0L) == 0L) return null
        return try {
            WeatherInfo(
                city = store.getString("city", "Sulaymaniyah") ?: "Sulaymaniyah",
                temperature = store.getInt("temperature", 0),
                feelsLike = store.getInt("feels_like", 0),
                description = store.getString("description", "Current conditions") ?: "Current conditions",
                humidity = store.getInt("humidity", 0),
                windKmh = store.getInt("wind", 0)
            )
        } catch (_: Exception) { null }
    }
}
