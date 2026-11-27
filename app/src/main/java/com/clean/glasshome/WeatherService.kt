package com.clean.glasshome

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Lightweight Open-Meteo client. No location permission or third-party SDK is used.
 * Change the city and coordinates here if the projector is used elsewhere.
 */
object WeatherService {
    private const val CITY = "Sulaymaniyah"
    private const val LATITUDE = 35.56
    private const val LONGITUDE = 45.43

    fun fetch(): WeatherInfo? {
        var connection: HttpURLConnection? = null
        return try {
            val address = String.format(
                Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f" +
                    "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m" +
                    "&timezone=auto",
                LATITUDE,
                LONGITUDE
            )
            val activeConnection = (URL(address).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4500
                readTimeout = 4500
                useCaches = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "GlassHome/5.0")
            }
            connection = activeConnection
            if (activeConnection.responseCode !in 200..299) return null
            val body = activeConnection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val current = JSONObject(body).getJSONObject("current")
            WeatherInfo(
                city = CITY,
                temperature = current.getDouble("temperature_2m").roundToInt(),
                feelsLike = current.getDouble("apparent_temperature").roundToInt(),
                description = describe(current.getInt("weather_code")),
                humidity = current.getInt("relative_humidity_2m"),
                windKmh = current.getDouble("wind_speed_10m").roundToInt()
            )
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun describe(code: Int): String = when (code) {
        0 -> "Clear sky"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Foggy"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61, 63, 65 -> "Rain"
        66, 67 -> "Freezing rain"
        71, 73, 75, 77 -> "Snow"
        80, 81, 82 -> "Rain showers"
        85, 86 -> "Snow showers"
        95, 96, 99 -> "Thunderstorm"
        else -> "Current conditions"
    }
}
