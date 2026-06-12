package com.imagemacro.capture

import android.graphics.Bitmap
import com.imagemacro.service.MacroAccessibilityService

/**
 * 접근성 서비스의 takeScreenshot 으로 화면을 캡처한다 (화면 공유 없음, Android 12+).
 *
 * 시스템은 접근성 스크린샷 호출 빈도를 약 초당 1회로 제한한다.
 * 호출 간 최소 간격을 두고, 빈도 제한으로 실패하면 1초 이상 쉬고 재시도한다.
 */
class AccessibilityScreenGrabber : ScreenGrabber {

    @Volatile private var lastShotAt = 0L

    override fun capture(): Bitmap? {
        val svc = MacroAccessibilityService.instance ?: return null

        var attempt = 0
        while (attempt < MAX_ATTEMPTS) {
            attempt++
            // 직전 캡처와의 간격이 너무 짧으면 잠시 기다려 빈도 제한을 피한다
            val sinceLast = System.currentTimeMillis() - lastShotAt
            if (sinceLast in 0 until MIN_INTERVAL_MS) {
                if (!sleep(MIN_INTERVAL_MS - sinceLast)) return null
            }

            val bmp = svc.captureScreen()
            lastShotAt = System.currentTimeMillis()
            if (bmp != null) return bmp
            if (attempt >= MAX_ATTEMPTS) break

            // 실패 → 사유에 따라 대기 후 재시도.
            //  - 빈도 제한: 1초 이상 쉬어야 풀린다
            //  - 내부 오류 등 일시적 실패: 시도할수록 더 길게(backoff)
            val wait = if (svc.lastErrorWasRateLimit()) RATE_LIMIT_WAIT_MS else 700L * attempt
            if (!sleep(wait)) return null
        }
        return null
    }

    /** 중단(엔진 stop) 시 false 를 돌려 즉시 빠져나오게 한다. */
    private fun sleep(ms: Long): Boolean = try {
        Thread.sleep(ms); true
    } catch (_: InterruptedException) {
        false
    }

    companion object {
        // 접근성 스크린샷 빈도 제한 회피용 최소 간격
        private const val MIN_INTERVAL_MS = 1000L
        // 빈도 제한(INTERVAL_TIME_SHORT)으로 실패했을 때 추가 대기
        private const val RATE_LIMIT_WAIT_MS = 1100L
        // 한 번 capture() 호출에서 재시도 횟수
        private const val MAX_ATTEMPTS = 4
    }
}
