package com.clean.glasshome

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint

object Glyph {
    const val NONE = 0
    const val WIFI = 1
    const val SETTINGS = 2
    const val APPS = 3
    const val STORE = 4
    const val WALLPAPER = 5
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

class Row(val title: String, val items: List<Item>)
class Data(val pills: List<Item>, val rows: List<Row>)

data class WeatherInfo(
    val city: String,
    val temperature: Int,
    val feelsLike: Int,
    val description: String,
    val humidity: Int,
    val windKmh: Int
)
