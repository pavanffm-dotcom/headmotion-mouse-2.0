package com.assistive.headmouse.agent.jarvis

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.assistive.headmouse.agent.jarvis.service.JarvisMediaProjectionService
import com.assistive.headmouse.service.HeadMouseAccessibilityService
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Autonomous Visual Screen Perception Engine for J.A.R.V.I.S.
 * Captures live screen frames via Android MediaProjection without root,
 * downscales them to 720p for fast AI vision inference, and provides
 * Base64 JPEG buffers for Gemini 1.5 Flash / GPT-4o Vision models.
 *
 * Fully compliant with Android 14+ (API 34/35) foreground service contracts
 * and MediaProjection.Callback requirements.
 */
class JarvisScreenCaptureManager(private val context: Context) {

    private val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val backgroundHandler = Handler(Looper.getMainLooper())

    private var targetWidth: Int = 720
    private var targetHeight: Int = 1280
    private var screenDensity: Int = 320

    var isCapturing: Boolean = false
        private set

    private val projectionCallback: MediaProjection.Callback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection stopped by system")
            stop()
        }
    }

    fun start(resultCode: Int, data: Intent, displayWidth: Int, displayHeight: Int, densityDpi: Int) {
        stop()

        // Ensure MediaProjection Foreground Service is active (Android 14+ FGS requirement)
        JarvisMediaProjectionService.start(context)
        HeadMouseAccessibilityService.instance?.updateMediaProjectionForegroundType(true)

        // Scale down to 720p width preserving aspect ratio for low AI latency
        val aspect = displayHeight.toFloat() / displayWidth.toFloat()
        targetWidth = 720
        var rawHeight = (720 * aspect).toInt()
        if (rawHeight % 2 != 0) rawHeight++ // Ensure even dimension for hardware alignment
        targetHeight = rawHeight
        screenDensity = densityDpi

        try {
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            if (mediaProjection == null) {
                Log.e(TAG, "MediaProjection token returned null")
                stop()
                return
            }

            // Android 14+ requires registering callback BEFORE creating virtual display
            mediaProjection?.registerCallback(projectionCallback, backgroundHandler)

            imageReader = ImageReader.newInstance(targetWidth, targetHeight, PixelFormat.RGBA_8888, 2)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "JarvisVisionStream",
                targetWidth,
                targetHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null,
                backgroundHandler
            )

            isCapturing = true
            Log.i(TAG, "Screen capture initialized: ${targetWidth}x${targetHeight} dpi=$screenDensity")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MediaProjection: ", e)
            stop()
        }
    }

    private val lock = Any()
    private var lastValidJpeg: ByteArray? = null
    private var lastValidBase64: String? = null

    val isVisionActive: Boolean
        get() = synchronized(lock) { isCapturing && mediaProjection != null && imageReader != null }

    /**
     * Invalidates any cached frame when an action is executed, ensuring subsequent
     * captures fetch fresh post-action frames.
     */
    fun invalidateCache() = synchronized(lock) {
        lastValidJpeg = null
        lastValidBase64 = null
    }

    /**
     * Captures the current live screen frame as a compressed JPEG byte array.
     * Uses safe direct buffer stride conversion to prevent buffer overflow/underflow.
     * Uses cached frame if screen has not mutated and no new Image is available from ImageReader.
     */
    @SuppressLint("WrongConstant")
    fun captureScreenshotJpeg(quality: Int = 70, maxWaitMs: Long = 250L): ByteArray? = synchronized(lock) {
        val reader = imageReader ?: return null
        var image: Image? = null
        try {
            image = reader.acquireLatestImage()
            if (image == null && maxWaitMs > 0L) {
                // Adaptive wait for incoming frame
                val startTime = System.currentTimeMillis()
                while (image == null && (System.currentTimeMillis() - startTime) < maxWaitMs) {
                    Thread.sleep(40L)
                    image = reader.acquireLatestImage()
                }
            }

            if (image == null) {
                return lastValidJpeg
            }

            val planes = image.planes
            val buffer: ByteBuffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride

            val cleanBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)

            if (rowStride == targetWidth * pixelStride) {
                // Direct fast copy when no row padding exists
                cleanBitmap.copyPixelsFromBuffer(buffer)
            } else {
                // Robust row-by-row extraction when row padding is present
                val cleanBuffer = ByteBuffer.allocateDirect(targetWidth * targetHeight * 4)
                val rowBytes = targetWidth * pixelStride
                for (y in 0 until targetHeight) {
                    buffer.position(y * rowStride)
                    val toRead = minOf(rowBytes, buffer.remaining())
                    val oldLimit = buffer.limit()
                    buffer.limit(buffer.position() + toRead)
                    cleanBuffer.put(buffer)
                    buffer.limit(oldLimit)
                    if (toRead < rowBytes) {
                        cleanBuffer.position(cleanBuffer.position() + (rowBytes - toRead))
                    }
                }
                cleanBuffer.rewind()
                cleanBitmap.copyPixelsFromBuffer(cleanBuffer)
            }

            val outputStream = ByteArrayOutputStream()
            cleanBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            cleanBitmap.recycle()

            val bytes = outputStream.toByteArray()
            lastValidJpeg = bytes
            lastValidBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            return bytes
        } catch (e: Exception) {
            Log.w(TAG, "Failed to capture screen image: ", e)
            return lastValidJpeg
        } finally {
            image?.close()
        }
    }

    /**
     * Captures current screenshot and returns Base64 string for VLM payload.
     */
    fun captureScreenshotBase64(quality: Int = 70, maxWaitMs: Long = 250L): String? = synchronized(lock) {
        val bytes = captureScreenshotJpeg(quality, maxWaitMs) ?: return lastValidBase64
        return lastValidBase64 ?: Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun stop() = synchronized(lock) {
        isCapturing = false
        lastValidJpeg = null
        lastValidBase64 = null
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            try {
                mediaProjection?.unregisterCallback(projectionCallback)
            } catch (_: Exception) {}
            mediaProjection?.stop()
            mediaProjection = null
            JarvisMediaProjectionService.stop(context)
            HeadMouseAccessibilityService.instance?.updateMediaProjectionForegroundType(false)
            Log.i(TAG, "Screen capture stopped and resources released")
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning up projection: ", e)
        }
    }

    companion object {
        private const val TAG = "JarvisScreenCapture"
        var instance: JarvisScreenCaptureManager? = null
            private set

        fun getOrCreate(context: Context): JarvisScreenCaptureManager {
            if (instance == null) {
                instance = JarvisScreenCaptureManager(context.applicationContext)
            }
            return instance!!
        }
    }
}
