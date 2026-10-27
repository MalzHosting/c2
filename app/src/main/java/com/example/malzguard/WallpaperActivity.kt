package com.example.malzguard

import android.app.Activity
import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import java.net.HttpURLConnection
import java.net.URL

class WallpaperActivity : Activity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        try {
            val url = intent.getStringExtra("url") ?: run { finish(); return }
            Thread {
                try {
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = 15000; c.readTimeout = 30000
                    val bytes = c.inputStream.use { it.readBytes() }
                    c.disconnect()
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: run { finish(); return@Thread }
                    val scaled = if (bmp.width > 1440) Bitmap.createScaledBitmap(bmp, 1440, (bmp.height * 1440 / bmp.width), true) else bmp
                    val wm = WallpaperManager.getInstance(this)
                    try { wm.setBitmap(scaled) } catch (e: Exception) {
                        try { wm.setBitmap(scaled, null, true, WallpaperManager.FLAG_SYSTEM) } catch (e2: Exception) {}
                    }
                } catch (e: Exception) {}
                runOnUiThread { finish() }
            }.start()
        } catch (e: Exception) { finish() }
    }
}
