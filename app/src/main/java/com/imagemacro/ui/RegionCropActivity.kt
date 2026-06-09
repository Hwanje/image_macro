package com.imagemacro.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.imagemacro.databinding.ActivityRegionCropBinding
import com.imagemacro.model.MacroStore
import kotlin.math.max
import kotlin.math.min

/**
 * 갤러리/스크린샷에서 이미지를 골라 사각형으로 영역을 드래그하면
 * 그 부분을 잘라 템플릿(PNG)으로 저장한다.
 * 스크린샷은 기기 해상도와 동일하므로 실행시 화면 매칭에 그대로 쓰인다.
 */
class RegionCropActivity : AppCompatActivity() {

    private lateinit var b: ActivityRegionCropBinding
    private var cropView: CropView? = null

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) { finish(); return@registerForActivityResult }
        val bmp = loadBitmap(uri)
        if (bmp == null) { Toast.makeText(this, "이미지를 불러오지 못함", Toast.LENGTH_SHORT).show(); finish(); return@registerForActivityResult }
        cropView = CropView(this, bmp)
        b.cropContainer.removeAllViews()
        b.cropContainer.addView(cropView)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityRegionCropBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnPick.setOnClickListener { pickImage.launch("image/*") }
        b.btnSave.setOnClickListener { saveCrop() }

        pickImage.launch("image/*")
    }

    private fun saveCrop() {
        val cv = cropView
        val rect = cv?.cropRectInBitmap()
        if (cv == null || rect == null || rect.width() < 8 || rect.height() < 8) {
            Toast.makeText(this, "영역을 드래그해서 선택하세요", Toast.LENGTH_SHORT).show(); return
        }
        val cropped = Bitmap.createBitmap(cv.bitmap, rect.left, rect.top, rect.width(), rect.height())
        val name = MacroStore.saveTemplate(this, cropped)
        setResult(RESULT_OK, Intent().putExtra(RESULT_TEMPLATE, name))
        finish()
    }

    private fun loadBitmap(uri: Uri): Bitmap? = try {
        contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it)?.copy(Bitmap.Config.ARGB_8888, false)
        }
    } catch (e: Exception) { null }

    /** 비트맵을 화면에 맞춰 그리고, 드래그로 사각형 영역을 선택한다. */
    private inner class CropView(ctx: Context, val bitmap: Bitmap) : View(ctx) {
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF5252"); style = Paint.Style.STROKE; strokeWidth = 5f
        }
        private val dim = Paint().apply { color = Color.argb(140, 0, 0, 0) }
        private var dispRect = RectF()
        private var scale = 1f
        private val sel = RectF()
        private var dragging = false

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // fit-center 배치
            scale = min(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
            val dw = bitmap.width * scale
            val dh = bitmap.height * scale
            val left = (width - dw) / 2f
            val top = (height - dh) / 2f
            dispRect.set(left, top, left + dw, top + dh)
            canvas.drawBitmap(bitmap, null, dispRect, null)
            // 선택 밖 어둡게 + 테두리
            if (!sel.isEmpty) {
                canvas.drawRect(sel, border)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val x = event.x.coerceIn(dispRect.left, dispRect.right)
            val y = event.y.coerceIn(dispRect.top, dispRect.bottom)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { sel.set(x, y, x, y); dragging = true }
                MotionEvent.ACTION_MOVE -> if (dragging) { sel.right = x; sel.bottom = y; invalidate() }
                MotionEvent.ACTION_UP -> { dragging = false; invalidate() }
            }
            return true
        }

        /** 화면 선택영역 → 비트맵 픽셀 좌표 */
        fun cropRectInBitmap(): Rect? {
            if (sel.isEmpty || scale <= 0f) return null
            val l = ((min(sel.left, sel.right) - dispRect.left) / scale).toInt()
            val t = ((min(sel.top, sel.bottom) - dispRect.top) / scale).toInt()
            val r = ((max(sel.left, sel.right) - dispRect.left) / scale).toInt()
            val bm = ((max(sel.top, sel.bottom) - dispRect.top) / scale).toInt()
            return Rect(
                l.coerceIn(0, bitmap.width), t.coerceIn(0, bitmap.height),
                r.coerceIn(0, bitmap.width), bm.coerceIn(0, bitmap.height)
            )
        }
    }

    companion object {
        const val RESULT_TEMPLATE = "template"
    }
}
