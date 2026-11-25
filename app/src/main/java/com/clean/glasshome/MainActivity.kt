package com.clean.glasshome

import android.app.Activity
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class MainActivity : Activity() {
    private lateinit var web: WebView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        web = WebView(this)
        web.setBackgroundColor(Color.BLACK)
        web.settings.javaScriptEnabled = true
        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl("file:///android_asset/index.html")
        setContentView(web)
        web.requestFocus()
    }

    override fun onNewIntent(i: Intent?) {
        super.onNewIntent(i)
        web.evaluateJavascript("goHome()", null)
    }

    override fun onBackPressed() {} // stay on Home

    inner class Bridge {
        @JavascriptInterface
        fun apps(): String {
            val out = JSONArray(); val seen = HashSet<String>()
            for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
                for (ri in packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                    val p = ri.activityInfo.packageName
                    if (p == packageName || !seen.add(p)) continue
                    out.put(JSONObject().put("p", p).put("l", ri.loadLabel(packageManager).toString())
                        .put("i", icon(ri.loadIcon(packageManager))))
                }
            }
            return out.toString()
        }

        @JavascriptInterface
        fun launch(p: String) = runOnUiThread {
            val i = packageManager.getLeanbackLaunchIntentForPackage(p) ?: packageManager.getLaunchIntentForPackage(p)
            i?.let { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(it) }
        }

        private fun icon(d: Drawable): String {
            val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, 96, 96); d.draw(Canvas(bmp))
            val s = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.PNG, 90, s)
            return "data:image/png;base64," + Base64.encodeToString(s.toByteArray(), Base64.NO_WRAP)
        }
    }
}
