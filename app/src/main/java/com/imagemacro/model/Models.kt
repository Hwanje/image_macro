package com.imagemacro.model

import java.util.UUID

/**
 * 매크로 한 단계(스텝)의 종류.
 * 시중 매크로 앱들의 "~일때 ~한다 / 반복 / 알고리즘" 개념을 단계 목록으로 표현한다.
 */
enum class StepType(val display: String) {
    TAP("탭 (좌표)"),
    SWIPE("스와이프"),
    WAIT("대기"),
    FIND_TAP("이미지 찾아 탭"),
    IF_IMAGE("이미지가 보이면 (조건)"),
    LOOP("반복"),
    TOAST("메시지 표시"),
    BACK("뒤로가기"),
    HOME("홈");
}

/**
 * 매크로의 한 단계. Gson 직렬화를 위해 단일 클래스로 모든 필드를 보관한다.
 * children / elseChildren 로 LOOP·IF_IMAGE 의 중첩 알고리즘을 표현한다.
 */
data class Step(
    var type: StepType = StepType.TAP,
    // 좌표 동작
    var x: Int = 0,
    var y: Int = 0,
    var x2: Int = 0,
    var y2: Int = 0,
    var duration: Long = 300,        // 스와이프 시간(ms)
    // 대기
    var waitMs: Long = 1000,
    // 이미지 감지
    var templateName: String? = null, // 템플릿 파일명 (templates 폴더)
    var threshold: Float = 0.85f,     // 일치 임계값 0~1
    var regionL: Int = -1,            // 검색 영역(없으면 -1 = 전체화면)
    var regionT: Int = -1,
    var regionR: Int = -1,
    var regionB: Int = -1,
    var offsetX: Int = 0,             // 찾은 위치 기준 탭 보정
    var offsetY: Int = 0,
    // 반복
    var loopCount: Int = 1,           // 0 = 무한
    // 메시지
    var message: String = "",
    // 중첩
    var children: MutableList<Step> = mutableListOf(),
    var elseChildren: MutableList<Step> = mutableListOf()
) {
    fun hasRegion(): Boolean = regionL >= 0 && regionT >= 0 && regionR > regionL && regionB > regionT

    /** 편집 화면에 보여줄 한 줄 요약 */
    fun summary(): String = when (type) {
        StepType.TAP -> "탭  ($x, $y)"
        StepType.SWIPE -> "스와이프  ($x,$y) → ($x2,$y2)  ${duration}ms"
        StepType.WAIT -> "대기  ${waitMs}ms"
        StepType.FIND_TAP -> "이미지 찾아 탭  [${templateName ?: "미지정"}]  ≥${(threshold * 100).toInt()}%"
        StepType.IF_IMAGE -> "만약 이미지 보이면  [${templateName ?: "미지정"}]  (then ${children.size} / else ${elseChildren.size})"
        StepType.LOOP -> "반복 ${if (loopCount == 0) "∞" else loopCount}회  (${children.size}단계)"
        StepType.TOAST -> "메시지  \"$message\""
        StepType.BACK -> "뒤로가기"
        StepType.HOME -> "홈"
    }
}

data class Macro(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "새 매크로",
    var steps: MutableList<Step> = mutableListOf(),
    var repeatCount: Int = 1,     // 전체 반복 횟수, 0 = 무한
    var stepDelayMs: Long = 300   // 각 단계 사이 기본 지연
)
