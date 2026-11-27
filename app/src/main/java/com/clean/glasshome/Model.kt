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
    const val SYSTEM = 6
    const val PREV = 7
    const val NEXT = 8
    const val PLAY = 9
    const val PAUSE = 10
    const val NOTE = 11
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
    val glyph: Int,
    /** Pre-rendered, rounded dock tile (same size/shape for every app). */
    val art: Bitmap? = null
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
