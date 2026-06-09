package com.imagemacro.ui

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity

/**
 * 반투명 전체화면. 사용자가 한 점을 탭하면 그 좌표(원본 해상도)를 결과로 돌려준다.
 */
class CoordinatePickerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val label = intent.getStringExtra(EXTRA_LABEL) ?: "탭할 위치를 선택하세요"
        setContentView(PickView(this, label))
    }

    private inner class PickView(ctx: AppCompatActivity, val label: String) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var px = -1f; private var py = -1f

        init { setBackgroundColor(Color.argb(120, 0, 0, 0)) }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                px = event.x; py = event.y; invalidate()
            } else if (event.action == MotionEvent.ACTION_UP) {
                val data = Intent().putExtra(RESULT_X, px.toInt()).putExtra(RESULT_Y, py.toInt())
                setResult(RESULT_OK, data); finish()
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            paint.color = Color.WHITE
            paint.textSize = 48f
            canvas.drawText(label, 48f, 140f, paint)
            if (px >= 0) {
                paint.color = Color.parseColor("#FF5252")
                canvas.drawCircle(px, py, 36f, paint.apply { style = Paint.Style.STROKE; strokeWidth = 6f })
                canvas.drawCircle(px, py, 8f, paint.apply { style = Paint.Style.FILL })
                paint.color = Color.WHITE
                canvas.drawText("(${px.toInt()}, ${py.toInt()})", px + 50f, py, paint)
            }
        }
    }

    companion object {
        const val EXTRA_LABEL = "label"
        const val RESULT_X = "x"
        const val RESULT_Y = "y"
    }
}
