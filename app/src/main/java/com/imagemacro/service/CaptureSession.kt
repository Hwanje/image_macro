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
import kotlin.math.max
import kotlin.math.min

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
    private val onFinished: (String?) -> Unit   // 저장된 템플릿 파일명, 취소면 null
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
        handler.postDelayed({ grabFrame(0) }, 400)
    }

    private fun grabFrame(attempt: Int) {
        if (finished) return
        // capture() 는 블로킹(접근성 스크린샷)일 수 있어 백그라운드에서 실행 후 메인으로 복귀
        Thread {
            val bmp = capture.capture()
            handler.post {
                if (finished) { bmp?.recycle(); return@post }
                when {
                    bmp != null -> { removeShutter(); showCrop(bmp) }
                    attempt < 12 -> handler.postDelayed({ grabFrame(attempt + 1) }, 100)
                    else -> {
                        Toast.makeText(context, "캡처 실패 — 다시 시도하세요", Toast.LENGTH_SHORT).show()
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

    /** 얼린 화면을 그대로 보여주고 드래그로 사각형 영역을 선택한다. */
    private class SelectView(ctx: Context, private val bitmap: Bitmap) : View(ctx) {
        var onSelection: ((RectF) -> Unit)? = null

        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF5252"); style = Paint.Style.STROKE; strokeWidth = 4f
        }
        private val dim = Paint().apply { color = Color.argb(120, 0, 0, 0) }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 38f
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }
        private val dst = RectF()
        private val sel = RectF()       // 드래그 원시값 (방향 정규화 전)
        private var hasSel = false

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    sel.set(event.x, event.y, event.x, event.y); hasSel = true; invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    sel.right = event.x; sel.bottom = event.y; invalidate()
                    onSelection?.invoke(normalized())
                }
                MotionEvent.ACTION_UP -> invalidate()
            }
            return true
        }

        private fun normalized() = RectF(
            min(sel.left, sel.right), min(sel.top, sel.bottom),
            max(sel.left, sel.right), max(sel.top, sel.bottom)
        )

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            dst.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawBitmap(bitmap, null, dst, null)
            if (!hasSel) {
                canvas.drawRect(dst, dim)
                return
            }
            val r = normalized()
            // 선택 영역 밖을 어둡게
            canvas.drawRect(0f, 0f, dst.right, r.top, dim)
            canvas.drawRect(0f, r.top, r.left, r.bottom, dim)
            canvas.drawRect(r.right, r.top, dst.right, r.bottom, dim)
            canvas.drawRect(0f, r.bottom, dst.right, dst.bottom, dim)
            canvas.drawRect(r, border)
            val b = selectionInBitmap()
            if (b != null) {
                val tx = r.left.coerceAtMost(width - 220f).coerceAtLeast(8f)
                val ty = (r.top - 16f).coerceAtLeast(44f)
                canvas.drawText("${b.width()} × ${b.height()}", tx, ty, label)
            }
        }

        /** 화면(뷰) 좌표 → 비트맵 픽셀 좌표 */
        fun selectionInBitmap(): Rect? {
            if (!hasSel || width == 0 || height == 0) return null
            val r = normalized()
            val sx = bitmap.width.toFloat() / width
            val sy = bitmap.height.toFloat() / height
            return Rect(
                (r.left * sx).toInt().coerceIn(0, bitmap.width),
                (r.top * sy).toInt().coerceIn(0, bitmap.height),
                (r.right * sx).toInt().coerceIn(0, bitmap.width),
                (r.bottom * sy).toInt().coerceIn(0, bitmap.height)
            )
        }
    }
}
