package com.eva.devicebridge

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Screenshot capture using AccessibilityService.takeScreenshot() (API 30+).
 *
 * This is the crux of why the whole project needs no MediaProjection consent
 * dialog: prior to API 30, capturing the screen from an app required either
 * root, a signature/system permission, or MediaProjection -- which shows a
 * "Start recording or casting?" dialog EVERY time the projection session is
 * (re)started, defeating the "one-time toggle" requirement. takeScreenshot()
 * needs nothing beyond the Accessibility Service binding itself.
 */
@RequiresApi(Build.VERSION_CODES.R)
object ScreenshotHelper {

    private val mainExecutor: Executor = object : Executor {
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        override fun execute(command: Runnable) {
            handler.post(command)
        }
    }

    /**
     * Blocking wrapper around the callback-based takeScreenshot API.
     * Returns PNG bytes, or null on failure/timeout.
     */
    fun capturePng(service: AccessibilityService, displayId: Int = 0): ByteArray? {
        val latch = CountDownLatch(1)
        var pngBytes: ByteArray? = null

        service.takeScreenshot(
            displayId,
            mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    try {
                        val hardwareBuffer: HardwareBuffer = result.hardwareBuffer
                        val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, result.colorSpace)
                        if (bitmap != null) {
                            // wrapHardwareBuffer gives back a HARDWARE-config bitmap,
                            // which can't be compressed directly on some API levels;
                            // copy to a software bitmap first for safety.
                            val softwareBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                            val out = ByteArrayOutputStream()
                            softwareBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                            pngBytes = out.toByteArray()
                            softwareBitmap.recycle()
                            bitmap.recycle()
                        }
                    } finally {
                        result.hardwareBuffer.close()
                        latch.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    latch.countDown()
                }
            }
        )

        latch.await(5, TimeUnit.SECONDS)
        return pngBytes
    }
}
