package com.imagemacro.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import com.imagemacro.R
import com.imagemacro.capture.ScreenGrabber
import com.imagemacro.model.MacroStore
import kotlin.math.abs

/**
 * 다른 앱 위에서 진행되는 템플릿 캡처 세션.
 *  1) 떠다니는 📷 셔터 버튼 표시 — 캡처할 화면을 띄운 뒤 누른다
 *  2) 셔터를 누르면 현재 프레임을 얼려 전체화면 오버레이로 표시
 *  3) 드래그로 영역을 선택해 저장하면 잘라낸 PNG 가 템플릿으로 보관된다
 */
class CaptureSession(
    private val context: Context,
    private val windowManager: WindowManager,
    private val capture: ScreenGrabber,
    private val onFinished: (String?) -> Unit,  // 저장된 템플릿 파일명, 취소면 null
    private val onError: ((String) -> Unit)? = null  // 캡처 실패 시(있으면 토스트 대신 호출)
) {
    private val handler = Handler(Looper.getMainLooper())
    private var shutter: View? = null
    private var cropRoot: View? = null
    private var frame: Bitmap? = null
    private var finished = false

    fun start() {
        showShutter()
        Toast.makeText(context, "캡처할 화면을 띄우고 📷 캡처를 누르세요", Toast.LENGTH_LONG).show()
    }

    /** 서비스 종료 등 외부 정리용 (콜백 없이 제거) */
    fun dismiss() {
        finished = true
        handler.removeCallbacksAndMessages(null)
        removeShutter()
        removeCrop()
    }

    private fun finish(name: String?) {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        // 오버레이가 아직 붙어 있는 동안 콜백 → 백그라운드에서도 편집기 복귀(액티비티 시작)가 허용된다
        onFinished(name)
        removeShutter()
        removeCrop()
    }

    // ---------------- 셔터 (떠다니는 캡처 버튼) ----------------

    private fun showShutter() {
        if (finished || shutter != null) return
        val v = LayoutInflater.from(context).inflate(R.layout.overlay_shutter, null)
        val params = baseParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 24; y = 160 }

        v.findViewById<View>(R.id.btnShoot).setOnClickListener { shoot() }
        v.findViewById<View>(R.id.btnShutterCancel).setOnClickListener { finish(null) }
        enableDrag(v, v.findViewById(R.id.dragHandle), params)

        windowManager.addView(v, params)
        shutter = v
    }

    private fun removeShutter() {
        shutter?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        shutter = null
    }

    /** 셔터를 숨기고, 셔터 없는 프레임이 도착할 시간을 준 뒤 화면을 얼린다. */
    private fun shoot() {
        shutter?.visibility = View.INVISIBLE
        Toast.makeText(context, "캡처 중…", Toast.LENGTH_SHORT).show()
        handler.postDelayed({ grabFrame() }, 450)
    }

    private fun grabFrame() {
        if (finished) return
        // capture() 는 블로킹(접근성 스크린샷, 내부적으로 재시도)이라 백그라운드에서 실행 후 복귀
        Thread {
            val bmp = capture.capture()
            handler.post {
                if (finished) { bmp?.recycle(); return@post }
                if (bmp != null) {
                    removeShutter(); showCrop(bmp)
                } else {
                    val why = MacroAccessibilityService.instance?.screenshotErrorText()
                        ?: "캡처 실패 — 다시 시도하세요"
                    val handler = onError
                    if (handler != null) {
                        // 소유자(서비스)가 처리 — 예: 화면 공유 방식으로 전환 후 재시도
                        finished = true
                        removeShutter(); removeCrop()
                        handler(why)
                    } else {
                        Toast.makeText(context, why, Toast.LENGTH_LONG).show()
                        shutter?.visibility = View.VISIBLE
                    }
                }
            }
        }.start()
    }

    // ---------------- 얼린 화면 + 영역 선택 ----------------

    private fun showCrop(bmp: Bitmap) {
        frame = bmp
        val root = FrameLayout(context)
        val select = SelectView(context, bmp)
        root.addView(select, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val actions = LayoutInflater.from(context)
            .inflate(R.layout.overlay_crop_actions, root, false)
        root.addView(actions)

        // 선택영역이 버튼 바 쪽을 덮으면 바를 반대편으로 옮긴다
        select.onSelection = { rect ->
            val lp = actions.layoutParams as FrameLayout.LayoutParams
            val g = (if (rect.centerY() > select.height / 2f) Gravity.TOP else Gravity.BOTTOM) or
                Gravity.CENTER_HORIZONTAL
            if (lp.gravity != g) { lp.gravity = g; actions.layoutParams = lp }
        }

        actions.findViewById<View>(R.id.btnRetake).setOnClickListener {
            removeCrop()
            showShutter()
        }
        actions.findViewById<View>(R.id.btnCropCancel).setOnClickListener { finish(null) }
        actions.findViewById<View>(R.id.btnCropSave).setOnClickListener {
            val r = select.selectionInBitmap()
            if (r == null || r.width() < 8 || r.height() < 8) {
                Toast.makeText(context, "찾을 부분을 드래그해서 선택하세요", Toast.LENGTH_SHORT).show()
            } else {
                val cropped = Bitmap.createBitmap(bmp, r.left, r.top, r.width(), r.height())
                val name = MacroStore.saveTemplate(context, cropped)
                cropped.recycle()
                finish(name)
            }
        }

        // 화면과 같은 픽셀 크기로 (0,0) 에 고정해 캡처본과 1:1 로 보이게 한다
        val params = baseParams(bmp.width, bmp.height).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
            flags = flags or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        windowManager.addView(root, params)
        cropRoot = root
    }

    private fun removeCrop() {
        cropRoot?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        cropRoot = null
        frame = null
    }

    // ---------------- 공통 ----------------

    private fun baseParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    )

    private fun enableDrag(view: View, handle: View, params: WindowManager.LayoutParams) {
        var startX = 0; var startY = 0
        var touchX = 0f; var touchY = 0f
        var moved = false
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = e.rawX; touchY = e.rawY; moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt()
                    val dy = (e.rawY - touchY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) moved = true
                    params.x = startX + dx
                    params.y = startY + dy
                    windowManager.updateViewLayout(view, params)
                    true
                }
                else -> moved
            }
        }
    }

    /**
     * 얼린 화면을 보여주고, 가운데에 놓인 사각형의 모서리·변 핸들을 끌어
     * 크기를 조절하거나 안쪽을 끌어 위치를 옮겨 영역을 정한다(드래그로 새로 그리지 않음).
     */
    private class SelectView(ctx: Context, private val bitmap: Bitmap) : View(ctx) {
        var onSelection: ((RectF) -> Unit)? = null

        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF5252"); style = Paint.Style.STROKE; strokeWidth = 4f
        }
        private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF5252"); style = Paint.Style.FILL
        }
        private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f
        }
        private val dim = Paint().apply { color = Color.argb(120, 0, 0, 0) }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 38f
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }
        private val dst = RectF()
        private val rect = RectF()       // 현재 선택 사각형(뷰 좌표)
        private var ready = false

        private val handleR = 16f        // 핸들 그리기 반지름
        private val touchR = 64f         // 핸들 터치 인식 반지름
        private val minSize = 48f        // 최소 사각형 크기

        // 드래그 상태
        private enum class Mode { NONE, MOVE, L, T, R, B, LT, RT, LB, RB }
        private var mode = Mode.NONE
        private var lastX = 0f
        private var lastY = 0f

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            super.onSizeChanged(w, h, ow, oh)
            // 기본 사각형: 화면 가운데 50% 크기
            val cw = w * 0.5f; val ch = h * 0.5f
            rect.set((w - cw) / 2f, (h - ch) / 2f, (w + cw) / 2f, (h + ch) / 2f)
            ready = true
            post { onSelection?.invoke(RectF(rect)) }
            invalidate()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    mode = hitTest(event.x, event.y)
                    lastX = event.x; lastY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - lastX; val dy = event.y - lastY
                    lastX = event.x; lastY = event.y
                    applyDrag(dx, dy)
                    onSelection?.invoke(RectF(rect))
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mode = Mode.NONE
            }
            return true
        }

        private fun near(px: Float, py: Float, x: Float, y: Float) =
            abs(px - x) <= touchR && abs(py - y) <= touchR

        private fun hitTest(x: Float, y: Float): Mode = when {
            near(x, y, rect.left, rect.top) -> Mode.LT
            near(x, y, rect.right, rect.top) -> Mode.RT
            near(x, y, rect.left, rect.bottom) -> Mode.LB
            near(x, y, rect.right, rect.bottom) -> Mode.RB
            near(x, y, rect.centerX(), rect.top) -> Mode.T
            near(x, y, rect.centerX(), rect.bottom) -> Mode.B
            near(x, y, rect.left, rect.centerY()) -> Mode.L
            near(x, y, rect.right, rect.centerY()) -> Mode.R
            rect.contains(x, y) -> Mode.MOVE
            else -> Mode.NONE
        }

        private fun applyDrag(dx: Float, dy: Float) {
            when (mode) {
                Mode.MOVE -> {
                    var nx = dx; var ny = dy
                    if (rect.left + nx < 0) nx = -rect.left
                    if (rect.right + nx > width) nx = width - rect.right
                    if (rect.top + ny < 0) ny = -rect.top
                    if (rect.bottom + ny > height) ny = height - rect.bottom
                    rect.offset(nx, ny)
                }
                Mode.L, Mode.LT, Mode.LB -> rect.left =
                    (rect.left + dx).coerceIn(0f, rect.right - minSize)
                Mode.R, Mode.RT, Mode.RB -> rect.right =
                    (rect.right + dx).coerceIn(rect.left + minSize, width.toFloat())
                else -> {}
            }
            when (mode) {
                Mode.T, Mode.LT, Mode.RT -> rect.top =
                    (rect.top + dy).coerceIn(0f, rect.bottom - minSize)
                Mode.B, Mode.LB, Mode.RB -> rect.bottom =
                    (rect.bottom + dy).coerceIn(rect.top + minSize, height.toFloat())
                else -> {}
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            dst.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawBitmap(bitmap, null, dst, null)
            if (!ready) { canvas.drawRect(dst, dim); return }
            // 선택 영역 밖을 어둡게
            canvas.drawRect(0f, 0f, dst.right, rect.top, dim)
            canvas.drawRect(0f, rect.top, rect.left, rect.bottom, dim)
            canvas.drawRect(rect.right, rect.top, dst.right, rect.bottom, dim)
            canvas.drawRect(0f, rect.bottom, dst.right, dst.bottom, dim)
            canvas.drawRect(rect, border)
            // 핸들 8개
            for ((hx, hy) in handlePoints()) {
                canvas.drawCircle(hx, hy, handleR, handleFill)
                canvas.drawCircle(hx, hy, handleR, handleRing)
            }
            val b = selectionInBitmap()
            if (b != null) {
                val tx = rect.left.coerceAtMost(width - 220f).coerceAtLeast(8f)
                val ty = (rect.top - 16f).coerceAtLeast(44f)
                canvas.drawText("${b.width()} × ${b.height()}", tx, ty, label)
            }
        }

        private fun handlePoints() = listOf(
            rect.left to rect.top, rect.centerX() to rect.top, rect.right to rect.top,
            rect.left to rect.centerY(), rect.right to rect.centerY(),
            rect.left to rect.bottom, rect.centerX() to rect.bottom, rect.right to rect.bottom
        )

        /** 화면(뷰) 좌표 → 비트맵 픽셀 좌표 */
        fun selectionInBitmap(): Rect? {
            if (!ready || width == 0 || height == 0) return null
            val sx = bitmap.width.toFloat() / width
            val sy = bitmap.height.toFloat() / height
            return Rect(
                (rect.left * sx).toInt().coerceIn(0, bitmap.width),
                (rect.top * sy).toInt().coerceIn(0, bitmap.height),
                (rect.right * sx).toInt().coerceIn(0, bitmap.width),
                (rect.bottom * sy).toInt().coerceIn(0, bitmap.height)
            )
        }
    }
}
