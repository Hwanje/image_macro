package com.imagemacro.capture

/**
 * 오버레이 캡처 결과(템플릿 파일명)를 편집기로 전달하는 인프로세스 브릿지.
 * 편집기가 onResult 를 등록하고 캡처를 요청하면, 세션이 끝날 때 서비스가 deliver() 한다.
 */
object CaptureBus {
    @Volatile var onResult: ((String?) -> Unit)? = null

    /** name=null 이면 취소. 콜백은 1회성으로 비워진다. */
    fun deliver(name: String?) {
        val cb = onResult
        onResult = null
        cb?.invoke(name)
    }
}
