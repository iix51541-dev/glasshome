package com.clean.glasshome

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.min

object Glyph {
    const val NONE = 0
    const val WIFI = 1
    const val BT = 2
    const val GEAR = 3
    const val DISPLAY = 4
    const val SOUND = 5
}

/** Small damped spring used for focus motion; no animation framework required. */
class Sp(var x: Float, var v: Float = 0f) {
    fun go(t: Float, k: Float, c: Float, dt: Float, e: Float = 0.002f): Boolean {
        var left = dt
        while (left > 0f) {
            val h = min(left, 0.008f)
            v += ((t - x) * k - v * c) * h
            x += v * h
            left -= h
        }
        if (abs(t - x) < e && abs(v) < e * 10f) {
            x = t
            v = 0f
            return false
        }
        return true
    }
}

class Item(
    val label: String,
    val sub: String?,
    val pkg: String?,
    val intent: Intent,
    val icon: Bitmap?,
    val banner: Bitmap?,
    val accent: Int,
    val tile: Int,
    val glyph: Int
) {
    var bp: Paint? = null
    var lbl: CharSequence? = null
    var sbl: CharSequence? = null
}

class Row(val title: String, val items: List<Item>) {
    val sc = Array(items.size) { Sp(1f) }
    val scroll = Sp(0f)
    var idx = 0
}

class Data(val pills: List<Item>, val rows: List<Row>)

data class WeatherInfo(
    val city: String,
    val temperature: Int,
    val feelsLike: Int,
    val description: String,
    val humidity: Int,
    val windKmh: Int
)
