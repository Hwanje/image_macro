package com.imagemacro.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * MediaProjection 으로 화면을 가상 디스플레이에 미러링하고,
 * 필요할 때 최신 프레임 한 장을 Bitmap 으로 가져온다.
 */
class ScreenCaptureManager(
    private val context: Context,
    private val projection: MediaProjection
) : ScreenGrabber {
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    var width = 0; private set
    var height = 0; private set
    private var densityDpi = 0

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { release() }
    }

    fun start() {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        width = metrics.widthPixels
        height = metrics.heightPixels
        densityDpi = metrics.densityDpi

        handlerThread = HandlerThread("capture").also { it.start() }
        handler = Handler(handlerThread!!.looper)

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        projection.registerCallback(projectionCallback, handler)
        virtualDisplay = projection.createVirtualDisplay(
            "macro-capture",
            width, height, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null, handler
        )
    }

    /** 현재 화면 프레임을 가져온다(없으면 null). 호출부에서 recycle 책임. */
    override fun capture(): Bitmap? {
        val reader = imageReader ?: return null
        val image = reader.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            val bmp = Bitmap.createBitmap(
                width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
            )
            bmp.copyPixelsFromBuffer(buffer)
            if (rowPadding == 0) bmp
            else Bitmap.createBitmap(bmp, 0, 0, width, height).also { bmp.recycle() }
        } catch (e: Exception) {
            null
        } finally {
            image.close()
        }
    }

    override fun release() {
        try { projection.unregisterCallback(projectionCallback) } catch (_: Exception) {}
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        handlerThread?.quitSafely(); handlerThread = null; handler = null
    }
}
