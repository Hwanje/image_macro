package com.imagemacro.capture

import android.graphics.Bitmap
import com.imagemacro.service.MacroAccessibilityService

/**
 * 접근성 서비스의 takeScreenshot 으로 화면을 캡처한다 (화면 공유 없음).
 *
 * 시스템은 접근성 스크린샷 호출 빈도를 제한하므로(대략 초당 수 회),
 * 호출 간 최소 간격을 두고 실패 시 한 번 더 시도한다.
 */
class AccessibilityScreenGrabber : ScreenGrabber {

    @Volatile private var lastShotAt = 0L

    override fun capture(): Bitmap? {
        val svc = MacroAccessibilityService.instance ?: return null

        // 직전 캡처와의 간격이 너무 짧으면 잠시 기다려 빈도 제한을 피한다
        val sinceLast = System.currentTimeMillis() - lastShotAt
        if (sinceLast in 0 until MIN_INTERVAL_MS) {
            try { Thread.sleep(MIN_INTERVAL_MS - sinceLast) } catch (_: InterruptedException) { return null }
        }

        var bmp = svc.captureScreen()
        if (bmp == null) {
            // 빈도 제한 등으로 실패 → 잠깐 쉬고 한 번 재시도
            try { Thread.sleep(MIN_INTERVAL_MS) } catch (_: InterruptedException) { return null }
            bmp = svc.captureScreen()
        }
        lastShotAt = System.currentTimeMillis()
        return bmp
    }

    companion object {
        // 접근성 스크린샷 빈도 제한 회피용 최소 간격
        private const val MIN_INTERVAL_MS = 350L
    }
}
