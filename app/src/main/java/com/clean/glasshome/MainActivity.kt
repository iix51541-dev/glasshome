package com.clean.glasshome

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View

class MainActivity : Activity() {
    companion object { private const val REQUEST_WALLPAPER = 5102 }
    private lateinit var home: HomeView
    private var receiverRegistered = false
    @Volatile private var weatherRequestRunning = false

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            loadApps()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        home = HomeView(this) { pickWallpaper() }
        setContentView(home)
        window.setBackgroundDrawable(null)
        restoreWallpaper()

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
        }
        applyImmersiveMode()
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(packageReceiver)
            receiverRegistered = false
        }
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
        // A TV launcher remains at Home; app navigation is handled by the system.
    }

    private fun loadApps() {
        Thread {
            try {
                val data = Catalog.build(applicationContext)
                runOnUiThread {
                    if (!isFinishing && ::home.isInitialized) home.setData(data)
                }
            } catch (_: Exception) {
                // Keep the already-rendered home screen if package discovery fails.
            }
        }.start()
    }

    private fun pickWallpaper() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            startActivityForResult(intent, REQUEST_WALLPAPER)
        } catch (_: Exception) {
            val fallback = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
            try { startActivityForResult(fallback, REQUEST_WALLPAPER) }
            catch (_: Exception) { android.widget.Toast.makeText(this, "No image picker is available", android.widget.Toast.LENGTH_SHORT).show() }
        }
    }

    @Deprecated("Legacy result API is retained for Android 9 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_WALLPAPER || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) { /* Some fallback providers grant temporary access only. */ }
        getSharedPreferences("wallpaper", Context.MODE_PRIVATE).edit().putString("uri", uri.toString()).apply()
        loadWallpaper(uri)
    }

    private fun restoreWallpaper() {
        val value = getSharedPreferences("wallpaper", Context.MODE_PRIVATE).getString("uri", null) ?: return
        try { loadWallpaper(Uri.parse(value)) } catch (_: Exception) { }
    }

    private fun loadWallpaper(uri: Uri) {
        Thread {
            val bitmap = try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                val sample = maxOf(1, maxOf(bounds.outWidth / 1600, bounds.outHeight / 900))
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                    inDither = true
                }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            } catch (_: Exception) { null }
            if (bitmap != null) runOnUiThread {
                if (!isFinishing && ::home.isInitialized) home.setWallpaper(bitmap) else bitmap.recycle()
            }
        }.start()
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
        } catch (_: Exception) {
            null
        }
    }
}
