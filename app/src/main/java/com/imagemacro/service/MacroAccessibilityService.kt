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

    /** 마지막 takeScreenshot 결과 코드 (0=성공/미시도). 실패 원인 안내에 사용. */
    @Volatile
    var lastScreenshotError = 0
        private set

    /** 마지막 실패가 '너무 잦은 호출(빈도 제한)' 때문이었는지 */
    fun lastErrorWasRateLimit(): Boolean =
        lastScreenshotError == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT

    /** 마지막 캡처 실패 사유를 사람이 읽을 메시지로. 성공/미시도면 null. */
    fun screenshotErrorText(): String? = when (lastScreenshotError) {
        0 -> null
        ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "캡처가 너무 잦아요 — 잠시 후 다시 시도하세요"
        ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "접근성 권한이 필요합니다"
        ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "캡처할 화면을 찾을 수 없습니다"
        ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "캡처 중 내부 오류가 발생했습니다"
        else ->
            // ERROR_TAKE_SCREENSHOT_SECURE_WINDOW(=5, API 33+) 등
            if (Build.VERSION.SDK_INT >= 33 &&
                lastScreenshotError == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW)
                "보안 화면이라 캡처할 수 없습니다 (게임/보안앱 보호)"
            else "캡처 실패 (코드 $lastScreenshotError)"
    }

    /**
     * 화면 공유(MediaProjection) 없이 접근성 서비스로 현재 화면을 한 장 캡처한다.
     * HardwareBuffer→Bitmap 변환(Bitmap.wrapHardwareBuffer)이 API 31 부터라
     * **Android 12(API 31)+** 에서만 동작한다. 블로킹이므로 백그라운드 스레드에서 호출할 것.
     */
    fun captureScreen(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
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
                            lastScreenshotError = 0
                        } catch (_: Throwable) {
                            // wrapHardwareBuffer/copy 실패 등 (Error 포함)
                            lastScreenshotError = ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR
                        } finally {
                            latch.countDown()
                        }
                    }
                    override fun onFailure(errorCode: Int) {
                        lastScreenshotError = errorCode
                        latch.countDown()
                    }
                })
        } catch (_: Throwable) {
            lastScreenshotError = ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR
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
