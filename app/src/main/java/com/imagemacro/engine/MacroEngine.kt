package com.imagemacro.engine

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.imagemacro.capture.ScreenGrabber
import com.imagemacro.model.Macro
import com.imagemacro.model.MacroStore
import com.imagemacro.model.Step
import com.imagemacro.model.StepType
import com.imagemacro.service.MacroAccessibilityService

/**
 * 매크로(스텝 목록)를 해석·실행하는 엔진.
 * 화면 캡처(ScreenCaptureManager) + 제스처(MacroAccessibilityService) 를 조합한다.
 * 별도 스레드에서 동작하며 stop() 으로 즉시 중단 가능.
 */
class MacroEngine(
    private val context: Context,
    private val capture: ScreenGrabber?,   // 화면 캡처 없이(좌표 전용) 실행 시 null
    private val onStatus: (String) -> Unit,
    private val onFinished: () -> Unit
) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private val main = Handler(Looper.getMainLooper())
    private val templateCache = HashMap<String, Bitmap?>()

    val isRunning: Boolean get() = running

    fun start(macro: Macro) {
        if (running) return
        running = true
        thread = Thread {
            try {
                runMacro(macro)
            } catch (e: InterruptedException) {
                // 중단됨
            } catch (e: Exception) {
                status("오류: ${e.message}")
            } finally {
                running = false
                templateCache.clear()
                main.post { onFinished() }
            }
        }.also { it.start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun runMacro(macro: Macro) {
        var round = 0
        while (running && (macro.repeatCount == 0 || round < macro.repeatCount)) {
            round++
            status(if (macro.repeatCount == 0) "실행중 (${round}회차 · ∞)" else "실행중 (${round}/${macro.repeatCount})")
            runTopLevel(macro.steps, macro.stepDelayMs)
            if (!running) break
        }
        status("완료")
    }

    /** 최상위 단계는 인덱스 기반으로 실행해 JUMP(조건 이동)로 단계를 건너뛸 수 있게 한다. */
    private fun runTopLevel(steps: List<Step>, delayBetween: Long) {
        var i = 0
        while (running && i in steps.indices) {
            val target = execStep(steps[i])   // -1 = 다음 단계, 그 외 = 이동할 0-based 인덱스
            i = if (target >= 0) target.coerceIn(0, steps.size - 1) else i + 1
            sleep(delayBetween)
        }
    }

    /** 중첩(LOOP/IF_IMAGE 안쪽) 단계 실행. JUMP 의 반환값은 무시한다(이동은 최상위에서만). */
    private fun runSteps(steps: List<Step>, delayBetween: Long) {
        for (step in steps) {
            if (!running) return
            execStep(step)
            sleep(delayBetween)
        }
    }

    /** @return JUMP 으로 이동할 0-based 인덱스. 이동 없으면 -1. */
    private fun execStep(step: Step): Int {
        val acc = MacroAccessibilityService.instance
        when (step.type) {
            StepType.TAP -> {
                status("탭 (${step.x}, ${step.y})")
                acc?.tap(step.x, step.y)
            }
            StepType.SWIPE -> {
                status("스와이프")
                acc?.swipe(step.x, step.y, step.x2, step.y2, step.duration)
            }
            StepType.WAIT -> {
                status("대기 ${step.waitMs}ms")
                sleep(step.waitMs)
            }
            StepType.TOAST -> {
                main.post { Toast.makeText(context, step.message, Toast.LENGTH_SHORT).show() }
            }
            StepType.BACK -> { status("뒤로가기"); acc?.goBack() }
            StepType.HOME -> { status("홈"); acc?.goHome() }
            StepType.FIND_TAP -> {
                val m = findImage(step)
                if (m != null) {
                    status("이미지 발견 (${(m.score * 100).toInt()}%) → 탭")
                    acc?.tap(m.centerX + step.offsetX, m.centerY + step.offsetY)
                } else {
                    status("이미지 미발견")
                }
            }
            StepType.IF_IMAGE -> {
                val m = findImage(step)
                if (m != null) {
                    status("조건 참 (${(m.score * 100).toInt()}%)")
                    runSteps(step.children, 0)
                } else {
                    status("조건 거짓")
                    runSteps(step.elseChildren, 0)
                }
            }
            StepType.LOOP -> {
                var i = 0
                while (running && (step.loopCount == 0 || i < step.loopCount)) {
                    i++
                    status("반복 ${i}${if (step.loopCount == 0) "" else "/${step.loopCount}"}")
                    runSteps(step.children, 0)
                }
            }
            StepType.JUMP -> return evalJump(step)
        }
        return -1
    }

    /** 조건 이동 평가. 이동하면 0-based 대상 인덱스, 아니면 -1. */
    private fun evalJump(step: Step): Int {
        val targetIdx = step.gotoStep - 1   // 1-based → 0-based (범위는 호출부에서 보정)
        if (step.templateName == null) {
            status("→ ${step.gotoStep}번으로 이동")
            return targetIdx
        }
        val found = findImage(step) != null
        return if (found == step.jumpIfFound) {
            status("${if (step.jumpIfFound) "이미지 보임" else "이미지 없음"} → ${step.gotoStep}번으로 이동")
            targetIdx
        } else {
            status("조건 불충족 → 다음 단계")
            -1
        }
    }

    private fun findImage(step: Step): MatchResult? {
        val cap = capture ?: run { status("이미지 단계 건너뜀 (화면 캡처 없음)"); return null }
        val name = step.templateName ?: return null
        val tpl = templateCache.getOrPut(name) { MacroStore.loadTemplate(context, name) } ?: return null
        val screen = cap.capture() ?: run {
            val why = MacroAccessibilityService.instance?.screenshotErrorText()
            status(why ?: "화면 캡처 실패")
            return null
        }
        val region = if (step.hasRegion())
            intArrayOf(step.regionL, step.regionT, step.regionR, step.regionB) else null
        val result = TemplateMatcher.match(screen, tpl, step.threshold, region)
        screen.recycle()
        return result
    }

    private fun status(msg: String) = main.post { onStatus(msg) }

    private fun sleep(ms: Long) {
        if (ms <= 0) return
        var left = ms
        while (running && left > 0) {
            val chunk = minOf(left, 50)
            Thread.sleep(chunk)
            left -= chunk
        }
    }
}
