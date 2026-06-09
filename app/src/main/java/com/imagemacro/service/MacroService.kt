package com.imagemacro.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.imagemacro.R
import com.imagemacro.capture.ScreenCaptureManager
import com.imagemacro.engine.MacroEngine
import com.imagemacro.model.Macro
import com.imagemacro.model.MacroStore
import com.imagemacro.ui.MacroEditorActivity
import kotlin.math.abs

/**
 * 포그라운드 서비스:
 *  - MediaProjection 으로 화면 캡처를 준비
 *  - 다른 앱 위에 떠 있는 컨트롤 패널(오버레이) 표시
 *  - 시작/정지/수정/닫기 제공, 매크로 엔진 구동
 */
class MacroService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlay: View? = null
    private var statusText: TextView? = null
    private var titleText: TextView? = null
    private var btnToggle: TextView? = null

    private var projection: MediaProjection? = null
    private var capture: ScreenCaptureManager? = null
    private var engine: MacroEngine? = null
    private var macro: Macro? = null

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val EXTRA_RESULT_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_MACRO_ID = "macro_id"

        private const val CHANNEL_ID = "macro_fg"
        private const val NOTI_ID = 1001

        @Volatile var isRunning = false; private set
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopEverything(); return START_NOT_STICKY }
            ACTION_START -> startWithProjection(intent)
        }
        return START_NOT_STICKY
    }

    private fun startWithProjection(intent: Intent) {
        startAsForeground()

        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val data: Intent? = intent.getParcelableExtra(EXTRA_DATA)
        if (data == null) { stopEverything(); return }

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data)
        if (projection == null) { stopEverything(); return }

        capture = ScreenCaptureManager(this, projection!!).also { it.start() }

        val id = intent.getStringExtra(EXTRA_MACRO_ID)
        macro = id?.let { MacroStore.find(this, it) } ?: MacroStore.load(this).firstOrNull()

        engine = MacroEngine(
            context = this,
            capture = capture!!,
            onStatus = { s -> statusText?.text = s },
            onFinished = { setToggleLabel(false) }
        )

        isRunning = true
        showOverlay()
        updateTitle()
    }

    // ---------------- 오버레이 ----------------

    private fun showOverlay() {
        if (overlay != null) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_panel, null)

        statusText = view.findViewById(R.id.txtStatus)
        titleText = view.findViewById(R.id.txtTitle)
        btnToggle = view.findViewById(R.id.btnToggle)

        view.findViewById<View>(R.id.btnToggle).setOnClickListener { toggleRun() }
        view.findViewById<View>(R.id.btnEdit).setOnClickListener { openEditor() }
        view.findViewById<View>(R.id.btnClose).setOnClickListener { stopEverything() }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24; y = 160
        }

        enableDrag(view, params)
        windowManager.addView(view, params)
        overlay = view
    }

    private fun enableDrag(view: View, params: WindowManager.LayoutParams) {
        val handle = view.findViewById<View>(R.id.dragHandle)
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

    private fun toggleRun() {
        val e = engine ?: return
        val m = macro
        if (e.isRunning) {
            e.stop(); setToggleLabel(false); statusText?.text = "정지됨"
        } else if (m != null && m.steps.isNotEmpty()) {
            if (!MacroAccessibilityService.isReady()) {
                statusText?.text = "접근성 권한 필요"
                return
            }
            setToggleLabel(true)
            e.start(m)
        } else {
            statusText?.text = "단계가 없습니다"
        }
    }

    private fun setToggleLabel(running: Boolean) {
        btnToggle?.text = if (running) "■ 정지" else "▶ 시작"
    }

    private fun updateTitle() {
        titleText?.text = macro?.name ?: "매크로 없음"
        statusText?.text = "준비됨"
        setToggleLabel(false)
    }

    private fun openEditor() {
        val m = macro ?: return
        val i = Intent(this, MacroEditorActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(MacroEditorActivity.EXTRA_MACRO_ID, m.id)
        }
        startActivity(i)
    }

    // ---------------- 생명주기 ----------------

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "매크로 실행", NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }
        val noti: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("이미지 매크로 실행중")
            .setContentText("오버레이 패널에서 제어하세요")
            .setSmallIcon(R.drawable.ic_macro)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTI_ID, noti, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTI_ID, noti)
        }
    }

    private fun stopEverything() {
        isRunning = false
        engine?.stop(); engine = null
        capture?.release(); capture = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        overlay?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        overlay = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }
}
