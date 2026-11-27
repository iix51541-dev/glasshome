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
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {
    companion object { private const val REQUEST_WALLPAPER = 5102 }

    private lateinit var home: HomeView
    private var receiverRegistered = false
    @Volatile private var weatherRequestRunning = false
    private val wallpaperFile by lazy { File(filesDir, "glasshome-wallpaper.img") }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { loadApps() }
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
            home.animateReturn()
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
        if (::home.isInitialized) home.returnToDock()
    }

    private fun loadApps() {
        Thread {
            try {
                val data = Catalog.build(applicationContext)
                runOnUiThread {
                    if (!isFinishing && ::home.isInitialized) home.setData(data)
                }
            } catch (_: Exception) { }
        }.start()
    }

    private fun pickWallpaper() {
        val documentIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivityForResult(documentIntent, REQUEST_WALLPAPER)
        } catch (_: Exception) {
            val contentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            try {
                startActivityForResult(Intent.createChooser(contentIntent, "Choose wallpaper"), REQUEST_WALLPAPER)
            } catch (_: Exception) {
                Toast.makeText(this, "No image picker is available on this projector", Toast.LENGTH_LONG).show()
            }
        }
    }

    @Deprecated("Legacy result API is retained for Android 9 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_WALLPAPER) return
        if (resultCode != RESULT_OK) return
        val uri = data?.data
        if (uri == null) {
            Toast.makeText(this, "No image was selected", Toast.LENGTH_SHORT).show()
            return
        }
        copyAndApplyWallpaper(uri)
    }

    /** Copy the chosen image into app-private storage so it never depends on a temporary URI grant. */
    private fun copyAndApplyWallpaper(uri: Uri) {
        Thread {
            var decoded: Bitmap? = null
            try {
                val temp = File(filesDir, "glasshome-wallpaper.tmp")
                contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("The selected image could not be opened")

                decoded = decodeWallpaper(temp)
                    ?: throw IllegalArgumentException("The selected file is not a supported image")

                if (wallpaperFile.exists()) wallpaperFile.delete()
                if (!temp.renameTo(wallpaperFile)) {
                    temp.copyTo(wallpaperFile, overwrite = true)
                    temp.delete()
                }
                val bitmap = decoded
                runOnUiThread {
                    if (!isFinishing && ::home.isInitialized && bitmap != null) {
                        home.setWallpaper(bitmap)
                        Toast.makeText(this, "Wallpaper applied", Toast.LENGTH_SHORT).show()
                    } else bitmap?.recycle()
                }
            } catch (error: Exception) {
                decoded?.recycle()
                runOnUiThread {
                    Toast.makeText(this, "Couldn't apply wallpaper: ${error.message ?: "unsupported image"}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun restoreWallpaper() {
        if (!wallpaperFile.exists()) return
        Thread {
            val bitmap = decodeWallpaper(wallpaperFile)
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
