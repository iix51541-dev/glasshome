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
    private var receiverRegistered = false
    @Volatile private var weatherRequestRunning = false

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            loadApps()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        home = HomeView(this)
        setContentView(home)
        window.setBackgroundDrawable(null)

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
