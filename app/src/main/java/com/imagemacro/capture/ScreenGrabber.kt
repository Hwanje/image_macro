package com.imagemacro.capture

import android.graphics.Bitmap

/**
 * 화면 한 장을 Bitmap 으로 가져오는 공통 인터페이스.
 * 구현체:
 *  - [AccessibilityScreenGrabber] : 접근성 서비스의 takeScreenshot (화면 공유 없음, API 30+)
 *  - [ScreenCaptureManager]       : MediaProjection (화면 공유, API 30 미만 폴백)
 *
 * capture() 는 블로킹이므로 반드시 메인 스레드가 아닌 곳에서 호출한다.
 */
interface ScreenGrabber {
    fun capture(): Bitmap?
    fun release() {}
}
