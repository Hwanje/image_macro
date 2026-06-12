package com.imagemacro.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.imagemacro.R
import com.imagemacro.capture.CaptureBus
import com.imagemacro.capture.ScreenCaptureManager
import com.imagemacro.engine.MacroEngine
import com.imagemacro.model.Macro
import com.imagemacro.model.MacroStore
import com.imagemacro.model.Step
import com.imagemacro.model.StepType
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
    private var captureSession: CaptureSession? = null
    private var captureOnly = false
    private var captureRecordMode = false   // 캡처 후 '이미지 찾아 탭' 단계까지 추가
    private var pointOverlay: View? = null   // 탭 좌표 녹화용 전체화면 오버레이

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingAfterProjection: (() -> Unit)? = null  // 프로젝션 확보 후 실행할 작업
    private var startCountdown: Runnable? = null              // 시작 지연(예약) 카운트다운
    private var autoStopRunnable: Runnable? = null            // 자동 중지(예약)
    private var intervalText: TextView? = null
    private var repeatText: TextView? = null
    private var timerText: TextView? = null
    private var delayText: TextView? = null

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_START_CAPTURE = "start_capture"  // 프로젝션 토큰과 함께, 캡처 전용 모드
        const val ACTION_CAPTURE = "capture"              // 실행중인 서비스에 캡처 세션 요청
        const val ACTION_PROVIDE_PROJECTION = "provide_projection" // 실행중 서비스에 프로젝션 공급
        // 간격(초) 후보값 — 패널에서 순환
        private val INTERVALS = longArrayOf(100, 200, 300, 500, 1000, 2000, 3000, 5000)
        private val AUTO_STOPS = intArrayOf(0, 60, 300, 600, 1800, 3600)      // 0=끄기
        private val START_DELAYS = intArrayOf(0, 3, 5, 10, 30)                // 0=즉시
        const val EXTRA_RESULT_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_MACRO_ID = "macro_id"
        const val EXTRA_RETURN_MACRO_ID = "return_macro_id"

        private const val CHANNEL_ID = "macro_fg"
        private const val NOTI_ID = 1001

        @Volatile var isRunning = false; private set
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopEverything(); return START_NOT_STICKY }
            ACTION_START -> startPanel(intent)
            ACTION_START_CAPTURE -> startCaptureOnly(intent)
            ACTION_CAPTURE -> beginCapture(intent.getStringExtra(EXTRA_RETURN_MACRO_ID))
            ACTION_PROVIDE_PROJECTION -> provideProjection(intent)
        }
        return START_NOT_STICKY
    }

    /** 프로젝션 토큰을 받아 화면 캡처를 준비한다. 성공하면 true. */
    private fun setupProjection(intent: Intent): Boolean {
        capture?.release(); capture = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null

        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val data: Intent? = intent.getParcelableExtra(EXTRA_DATA)
        if (data == null) return false

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data) ?: return false
        capture = ScreenCaptureManager(this, projection!!).also { it.start() }
        // 프로젝션을 가졌으니 FGS 유형을 mediaProjection 으로 갱신
        startAsForeground(withProjection = true)
        return true
    }

    /** 오버레이 패널 시작. 프로젝션(EXTRA_DATA)이 있으면 캡처도 준비, 없으면 좌표 전용. */
    private fun startPanel(intent: Intent) {
        this.captureOnly = false

        // 재시작이면 이전 상태 정리
        captureSession?.dismiss(); captureSession = null
        engine?.stop(); engine = null

        @Suppress("DEPRECATION")
        val hasData = intent.getParcelableExtra<Intent>(EXTRA_DATA) != null
        if (hasData) {
            startAsForeground(withProjection = true)
            setupProjection(intent)
        } else {
            // 화면 공유 없이 다른 앱 위에 띄우기 (좌표 전용)
            startAsForeground(withProjection = false)
        }
        isRunning = true

        val id = intent.getStringExtra(EXTRA_MACRO_ID)
        macro = id?.let { MacroStore.find(this, it) } ?: MacroStore.load(this).firstOrNull()

        engine = MacroEngine(
            context = this,
            capture = capture,
            onStatus = { s -> statusText?.text = s },
            onFinished = { setToggleLabel(false); cancelAutoStop() }
        )

        showOverlay()
        updateTitle()
    }

    /** 편집기에서 요청한 캡처 전용 모드: 패널 없이 바로 셔터를 띄운다. */
    private fun startCaptureOnly(intent: Intent) {
        this.captureOnly = true
        startAsForeground(withProjection = true)
        captureSession?.dismiss(); captureSession = null
        engine?.stop(); engine = null
        if (!setupProjection(intent)) { stopEverything(); return }
        isRunning = true
        beginCapture(intent.getStringExtra(EXTRA_RETURN_MACRO_ID))
    }

    /** 실행중인 패널에 프로젝션을 공급하고, 대기중이던 작업(이미지 캡처/실행)을 이어간다. */
    private fun provideProjection(intent: Intent) {
        val after = pendingAfterProjection
        pendingAfterProjection = null
        if (!setupProjection(intent)) {
            statusText?.text = "화면 캡처 권한이 거부됨"
            overlay?.visibility = View.VISIBLE
            return
        }
        // 엔진의 capture 참조를 갱신하기 위해 재생성
        engine?.stop()
        engine = MacroEngine(
            context = this,
            capture = capture,
            onStatus = { s -> statusText?.text = s },
            onFinished = { setToggleLabel(false); cancelAutoStop() }
        )
        after?.invoke()
    }

    /** 프로젝션이 없으면 권한 화면을 띄워 받아오고, 받은 뒤 after 를 실행한다. */
    private fun requestProjection(after: () -> Unit) {
        pendingAfterProjection = after
        overlay?.visibility = View.GONE
        val i = Intent(this, com.imagemacro.capture.ProjectionRequestActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(com.imagemacro.capture.ProjectionRequestActivity.EXTRA_PROVIDE_ONLY, true)
        }
        startActivity(i)
    }

    // ---------------- 오버레이 캡처 세션 ----------------

    /**
     * 패널을 숨기고 셔터→영역선택→템플릿 저장 세션을 시작한다.
     * record=true 면 저장된 템플릿으로 '이미지 찾아 탭' 단계까지 현재 매크로에 추가한다.
     */
    private fun beginCapture(returnMacroId: String?, record: Boolean = false) {
        val cap = capture
        if (cap == null) {
            if (!isRunning) { stopSelf(); return }
            // 화면 공유 없이 떠 있던 상태 → 이미지 작업을 위해 이제 프로젝션을 요청
            Toast.makeText(this, "이미지 캡처를 위해 화면 공유를 한 번 허용해 주세요", Toast.LENGTH_LONG).show()
            requestProjection { beginCapture(returnMacroId, record) }
            return
        }
        if (captureSession != null) return
        captureRecordMode = record

        engine?.takeIf { it.isRunning }?.let {
            it.stop(); setToggleLabel(false); statusText?.text = "정지됨"
        }
        overlay?.visibility = View.GONE

        captureSession = CaptureSession(this, windowManager, cap) { name ->
            captureSession = null
            val wasRecord = captureRecordMode
            captureRecordMode = false
            if (name != null && wasRecord) {
                appendStep(Step(type = StepType.FIND_TAP, templateName = name))
            } else if (name != null) {
                val msg = if (returnMacroId == null)
                    "템플릿 저장됨 — 단계 편집의 '저장된 템플릿'에서 사용하세요"
                else "템플릿 저장됨"
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
            CaptureBus.deliver(name)
            if (returnMacroId != null) bringEditorToFront(returnMacroId)
            if (this.captureOnly) stopEverything()
            else overlay?.visibility = View.VISIBLE
        }.also { it.start() }
    }

    // ---------------- 앱 위에서 단계 만들기 (오버레이 빌더) ----------------

    /** 패널을 숨기고 전체화면 오버레이를 띄워 탭할 좌표 한 점을 녹화한다. */
    private fun beginPointPick() {
        if (pointOverlay != null) return
        overlay?.visibility = View.GONE

        val pick = PointPickView(this,
            onPicked = { x, y ->
                removePointPick()
                appendStep(Step(type = StepType.TAP, x = x, y = y))
                overlay?.visibility = View.VISIBLE
            },
            onCancel = {
                removePointPick()
                overlay?.visibility = View.VISIBLE
            }
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        windowManager.addView(pick, params)
        pointOverlay = pick
        Toast.makeText(this, "탭할 위치를 누르세요 (이 매크로 실행 시 그 위치를 탭합니다)", Toast.LENGTH_LONG).show()
    }

    private fun removePointPick() {
        pointOverlay?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        pointOverlay = null
    }

    /** 현재 매크로(없으면 새로 생성)에 단계를 추가하고 저장한다. */
    private fun appendStep(step: Step) {
        // 편집기에서 바뀐 내용을 덮어쓰지 않도록 저장소의 최신본을 기준으로 추가
        val base = macro?.let { MacroStore.find(this, it.id) } ?: macro
            ?: Macro(name = "오버레이 매크로").also { MacroStore.upsert(this, it) }
        base.steps.add(step)
        MacroStore.upsert(this, base)
        macro = base
        titleText?.text = base.name
        statusText?.text = "단계 추가됨: ${step.summary()}  (총 ${base.steps.size}개)"
        setToggleLabel(false)
    }

    private fun bringEditorToFront(macroId: String) {
        val i = Intent(this, MacroEditorActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MacroEditorActivity.EXTRA_MACRO_ID, macroId)
        }
        startActivity(i)
    }

    // ---------------- 오버레이 ----------------

    private fun showOverlay() {
        overlay?.let { it.visibility = View.VISIBLE; return }
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_panel, null)

        statusText = view.findViewById(R.id.txtStatus)
        titleText = view.findViewById(R.id.txtTitle)
        btnToggle = view.findViewById(R.id.btnToggle)
        intervalText = view.findViewById(R.id.btnInterval)
        repeatText = view.findViewById(R.id.btnRepeat)
        timerText = view.findViewById(R.id.btnTimer)
        delayText = view.findViewById(R.id.btnStartDelay)

        view.findViewById<View>(R.id.btnToggle).setOnClickListener { toggleRun() }
        view.findViewById<View>(R.id.btnEdit).setOnClickListener { openEditor() }
        view.findViewById<View>(R.id.btnCapture).setOnClickListener { beginCapture(null) }
        view.findViewById<View>(R.id.btnClose).setOnClickListener { stopEverything() }
        // 앱 위에서 바로 단계를 추가하는 빌드 컨트롤
        view.findViewById<View>(R.id.btnAddTap).setOnClickListener { beginPointPick() }
        view.findViewById<View>(R.id.btnAddFind).setOnClickListener { beginCapture(null, record = true) }
        // 실행 옵션(간격/반복/예약)
        view.findViewById<View>(R.id.btnInterval).setOnClickListener { cycleInterval() }
        view.findViewById<View>(R.id.btnRepeat).setOnClickListener { toggleRepeat() }
        view.findViewById<View>(R.id.btnTimer).setOnClickListener { cycleAutoStop() }
        view.findViewById<View>(R.id.btnStartDelay).setOnClickListener { cycleStartDelay() }

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
        // 카운트다운 대기중이면 취소
        if (startCountdown != null) {
            cancelStartCountdown(); setToggleLabel(false); statusText?.text = "시작 취소됨"; return
        }
        val e = engine ?: return
        val m = macro
        if (e.isRunning) {
            e.stop(); cancelAutoStop(); setToggleLabel(false); statusText?.text = "정지됨"
            return
        }
        if (m == null || m.steps.isEmpty()) { statusText?.text = "단계가 없습니다"; return }
        if (!MacroAccessibilityService.isReady()) { statusText?.text = "접근성 권한 필요"; return }

        // 이미지 감지를 쓰는데 화면 캡처가 없으면 먼저 프로젝션을 받는다
        if (m.usesImageDetection() && capture == null) {
            Toast.makeText(this, "이미지 감지를 위해 화면 공유를 한 번 허용해 주세요", Toast.LENGTH_LONG).show()
            requestProjection { overlay?.visibility = View.VISIBLE; runWithSchedule(m) }
            return
        }
        runWithSchedule(m)
    }

    /** 시작 지연(예약) 카운트다운 후 실행하고, 자동 중지(예약)를 건다. */
    private fun runWithSchedule(m: Macro) {
        val delay = m.startDelaySec
        if (delay <= 0) { startEngineNow(m); return }
        btnToggle?.text = "취소"
        var left = delay
        val r = object : Runnable {
            override fun run() {
                if (left <= 0) { startCountdown = null; startEngineNow(m); return }
                statusText?.text = "시작까지 ${left}초… (다른 앱으로 이동하세요)"
                left--
                mainHandler.postDelayed(this, 1000)
            }
        }
        startCountdown = r
        mainHandler.post(r)
    }

    private fun startEngineNow(m: Macro) {
        val e = engine ?: return
        setToggleLabel(true)
        e.start(m)
        if (m.autoStopSec > 0) scheduleAutoStop(m.autoStopSec)
    }

    private fun scheduleAutoStop(sec: Int) {
        cancelAutoStop()
        val r = Runnable {
            autoStopRunnable = null
            engine?.stop(); setToggleLabel(false)
            statusText?.text = "자동 중지됨 (${sec}초 경과)"
        }
        autoStopRunnable = r
        mainHandler.postDelayed(r, sec * 1000L)
    }

    private fun cancelAutoStop() {
        autoStopRunnable?.let { mainHandler.removeCallbacks(it) }
        autoStopRunnable = null
    }

    private fun cancelStartCountdown() {
        startCountdown?.let { mainHandler.removeCallbacks(it) }
        startCountdown = null
    }

    // ---------------- 실행 옵션(간격/반복/예약) ----------------

    private fun cycleInterval() {
        val m = macro ?: return
        val idx = INTERVALS.indexOfFirst { it >= m.stepDelayMs }.let { if (it < 0) 0 else it }
        m.stepDelayMs = INTERVALS[(idx + 1) % INTERVALS.size]
        MacroStore.upsert(this, m); refreshOptionLabels()
        statusText?.text = "간격 ${fmtMs(m.stepDelayMs)}"
    }

    private fun toggleRepeat() {
        val m = macro ?: return
        m.repeatCount = if (m.repeatCount == 0) 1 else 0   // 0=무한 ↔ 1회
        MacroStore.upsert(this, m); refreshOptionLabels()
        statusText?.text = if (m.repeatCount == 0) "무한 반복 (감시 모드)" else "1회 실행"
    }

    private fun cycleAutoStop() {
        val m = macro ?: return
        val idx = AUTO_STOPS.indexOf(m.autoStopSec).let { if (it < 0) 0 else it }
        m.autoStopSec = AUTO_STOPS[(idx + 1) % AUTO_STOPS.size]
        MacroStore.upsert(this, m); refreshOptionLabels()
        statusText?.text = if (m.autoStopSec == 0) "자동 중지 끔" else "자동 중지 ${fmtSec(m.autoStopSec)} 뒤"
    }

    private fun cycleStartDelay() {
        val m = macro ?: return
        val idx = START_DELAYS.indexOf(m.startDelaySec).let { if (it < 0) 0 else it }
        m.startDelaySec = START_DELAYS[(idx + 1) % START_DELAYS.size]
        MacroStore.upsert(this, m); refreshOptionLabels()
        statusText?.text = if (m.startDelaySec == 0) "즉시 시작" else "${m.startDelaySec}초 뒤 시작"
    }

    private fun refreshOptionLabels() {
        val m = macro
        intervalText?.text = "⏱ ${fmtMs(m?.stepDelayMs ?: 300)}"
        repeatText?.text = if (m?.repeatCount == 0) "🔁 무한" else "🔁 1회"
        timerText?.text = if (m == null || m.autoStopSec == 0) "⏰ 끔" else "⏰ ${fmtSec(m.autoStopSec)}"
        delayText?.text = if (m == null || m.startDelaySec == 0) "▷ 즉시" else "▷ ${m.startDelaySec}s"
    }

    private fun fmtMs(ms: Long): String {
        if (ms < 1000) return "${ms}ms"
        val s = ms / 1000.0
        return if (s == s.toLong().toDouble()) "${s.toLong()}s" else "${s}s"
    }

    private fun fmtSec(sec: Int): String = if (sec < 60) "${sec}s" else "${sec / 60}m"

    private fun setToggleLabel(running: Boolean) {
        btnToggle?.text = if (running) "■ 정지" else "▶ 시작"
    }

    private fun updateTitle() {
        titleText?.text = macro?.name ?: "매크로 없음"
        statusText?.text = if (capture == null) "준비됨 (화면 공유 없음 · 좌표 모드)" else "준비됨"
        setToggleLabel(false)
        refreshOptionLabels()
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

    private fun startAsForeground(withProjection: Boolean) {
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
            // Android 14+ 는 프로젝션이 없으면 mediaProjection 유형으로 시작할 수 없어
            // specialUse 로 띄운다 (화면 공유 없이 오버레이만).
            val type = if (Build.VERSION.SDK_INT >= 34 && !withProjection)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            startForeground(NOTI_ID, noti, type)
        } else {
            startForeground(NOTI_ID, noti)
        }
    }

    private fun stopEverything() {
        isRunning = false
        cancelStartCountdown(); cancelAutoStop()
        pendingAfterProjection = null
        removePointPick()
        captureSession?.dismiss(); captureSession = null
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

    /**
     * 반투명 전체화면 오버레이. 다른 앱 위에서 한 점을 눌러 탭 좌표를 녹화한다.
     * 화면이 비치도록 반투명이며, 상단의 '취소'를 누르면 녹화를 중단한다.
     */
    private class PointPickView(
        ctx: Context,
        private val onPicked: (Int, Int) -> Unit,
        private val onCancel: () -> Unit
    ) : View(ctx) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private var px = -1f; private var py = -1f
        private val cancelRect = android.graphics.RectF()

        init { setBackgroundColor(Color.argb(90, 0, 0, 0)) }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    px = event.x; py = event.y; invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    if (cancelRect.contains(event.x, event.y)) { onCancel(); return true }
                    onPicked(event.x.toInt(), event.y.toInt())
                }
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // 안내 + 취소 버튼
            paint.color = Color.WHITE; paint.textSize = 44f; paint.style = android.graphics.Paint.Style.FILL
            canvas.drawText("탭할 위치를 누르세요", 48f, 120f, paint)
            cancelRect.set(width - 220f, 60f, width - 40f, 140f)
            paint.color = Color.parseColor("#33000000")
            canvas.drawRoundRect(cancelRect, 16f, 16f, paint)
            paint.color = Color.parseColor("#FF8A80"); paint.textSize = 40f
            canvas.drawText("✕ 취소", width - 200f, 112f, paint)
            // 선택 표시
            if (px >= 0) {
                paint.color = Color.parseColor("#FF5252"); paint.style = android.graphics.Paint.Style.STROKE
                paint.strokeWidth = 6f
                canvas.drawCircle(px, py, 36f, paint)
                paint.style = android.graphics.Paint.Style.FILL
                canvas.drawCircle(px, py, 8f, paint)
                paint.color = Color.WHITE; paint.textSize = 36f
                canvas.drawText("(${px.toInt()}, ${py.toInt()})", px + 50f, py, paint)
            }
        }
    }
}
