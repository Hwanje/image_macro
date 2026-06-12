package com.imagemacro.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 실제 탭/스와이프/전역동작(뒤로·홈)을 수행하는 접근성 서비스.
 * 같은 프로세스의 매크로 엔진이 instance 를 통해 호출한다.
 */
class MacroAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: MacroAccessibilityService? = null
            private set

        fun isReady() = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** 한 점을 탭. 완료까지 블로킹(엔진은 백그라운드 스레드에서 호출). */
    fun tap(x: Int, y: Int, holdMs: Long = 50): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, holdMs.coerceAtLeast(1))
        return dispatchBlocking(GestureDescription.Builder().addStroke(stroke).build())
    }

    /** 스와이프 (드래그). */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1))
        return dispatchBlocking(GestureDescription.Builder().addStroke(stroke).build())
    }

    fun goBack() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun goHome() = performGlobalAction(GLOBAL_ACTION_HOME)

    // 스크린샷 콜백을 받는 전용 스레드 (메인 스레드 블로킹 회피)
    private val shotExecutor = Executors.newSingleThreadExecutor()

    /**
     * 화면 공유(MediaProjection) 없이 접근성 서비스로 현재 화면을 한 장 캡처한다.
     * Android 11(API 30)+ 에서만 동작. 블로킹이므로 백그라운드 스레드에서 호출할 것.
     */
    fun captureScreen(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val latch = CountDownLatch(1)
        var result: Bitmap? = null
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, shotExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            val hw = screenshot.hardwareBuffer
                            val hwBmp = Bitmap.wrapHardwareBuffer(hw, screenshot.colorSpace)
                            // 픽셀 접근을 위해 소프트웨어 비트맵으로 복사
                            result = hwBmp?.copy(Bitmap.Config.ARGB_8888, false)
                            hwBmp?.recycle()
                            hw.close()
                        } catch (_: Exception) {
                        } finally {
                            latch.countDown()
                        }
                    }
                    override fun onFailure(errorCode: Int) { latch.countDown() }
                })
        } catch (_: Exception) {
            return null
        }
        latch.await(2, TimeUnit.SECONDS)
        return result
    }

    private fun dispatchBlocking(gesture: GestureDescription): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { ok = true; latch.countDown() }
            override fun onCancelled(g: GestureDescription?) { ok = false; latch.countDown() }
        }, null)
        if (!dispatched) return false
        latch.await(3, TimeUnit.SECONDS)
        return ok
    }
}
