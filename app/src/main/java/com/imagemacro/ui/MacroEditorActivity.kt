package com.imagemacro.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.imagemacro.capture.CaptureBus
import com.imagemacro.capture.ProjectionRequestActivity
import com.imagemacro.databinding.ActivityEditorBinding
import com.imagemacro.model.Macro
import com.imagemacro.model.MacroStore
import com.imagemacro.model.Step
import com.imagemacro.model.StepType
import com.imagemacro.service.MacroService
import com.imagemacro.util.PermissionUtil
import java.io.File

/**
 * 매크로 편집기. 단계(스텝)를 추가/수정/정렬/삭제하며,
 * 반복(LOOP)·조건(IF_IMAGE)은 하위 단계로 들어가 중첩 알고리즘을 구성한다.
 */
class MacroEditorActivity : AppCompatActivity() {

    private lateinit var b: ActivityEditorBinding
    private lateinit var macro: Macro

    // 현재 편집중인 단계 목록 스택 (루트 -> 반복/조건 내부)
    private val navStack = ArrayDeque<Pair<MutableList<Step>, String>>()
    private val currentList get() = navStack.last().first
    private var adapter: StepListAdapter? = null

    private var pendingPoint: ((Int, Int) -> Unit)? = null
    private var pendingTemplate: ((String) -> Unit)? = null
    private var awaitingCapture = false

    private val pointLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val d = res.data
        if (res.resultCode == RESULT_OK && d != null) {
            pendingPoint?.invoke(
                d.getIntExtra(CoordinatePickerActivity.RESULT_X, 0),
                d.getIntExtra(CoordinatePickerActivity.RESULT_Y, 0)
            )
        }
        pendingPoint = null
    }

    private val cropLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val name = res.data?.getStringExtra(RegionCropActivity.RESULT_TEMPLATE)
        if (res.resultCode == RESULT_OK && name != null) pendingTemplate?.invoke(name)
        pendingTemplate = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(b.root)

        val id = intent.getStringExtra(EXTRA_MACRO_ID)
        macro = (id?.let { MacroStore.find(this, it) }) ?: Macro()

        b.editName.setText(macro.name)
        b.editRepeat.setText(macro.repeatCount.toString())
        b.editDelay.setText(macro.stepDelayMs.toString())

        b.recycler.layoutManager = LinearLayoutManager(this)
        navStack.addLast(macro.steps to "전체")
        bindList()

        b.btnAddStep.setOnClickListener { chooseStepType() }
        b.btnSave.setOnClickListener { save() }
    }

    private fun bindList() {
        adapter = StepListAdapter(
            currentList,
            onTap = { onStepTap(it) },
            onUp = { move(it, -1) },
            onDown = { move(it, +1) },
            onDelete = { currentList.removeAt(it); adapter?.notifyDataSetChanged() }
        )
        b.recycler.adapter = adapter
        b.txtBreadcrumb.text = navStack.joinToString("  ›  ") { it.second }
        b.btnBackLevel.visibility = if (navStack.size > 1) View.VISIBLE else View.GONE
        b.btnBackLevel.setOnClickListener { goUp() }
    }

    private fun move(pos: Int, dir: Int) {
        val to = pos + dir
        if (to < 0 || to >= currentList.size) return
        val tmp = currentList[pos]; currentList[pos] = currentList[to]; currentList[to] = tmp
        adapter?.notifyDataSetChanged()
    }

    private fun goUp() {
        if (navStack.size > 1) { navStack.removeLast(); bindList() }
    }

    override fun onBackPressed() {
        if (navStack.size > 1) goUp() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (awaitingCapture) {
            CaptureBus.onResult = null
            awaitingCapture = false
        }
        super.onDestroy()
    }

    private fun onStepTap(pos: Int) {
        val step = currentList[pos]
        when (step.type) {
            StepType.LOOP -> { navStack.addLast(step.children to "반복"); bindList() }
            StepType.IF_IMAGE -> {
                AlertDialog.Builder(this)
                    .setTitle("어느 분기를 편집할까요?")
                    .setItems(arrayOf("이미지 보일 때 (then)", "안 보일 때 (else)", "조건/이미지 설정")) { _, w ->
                        when (w) {
                            0 -> { navStack.addLast(step.children to "then"); bindList() }
                            1 -> { navStack.addLast(step.elseChildren to "else"); bindList() }
                            2 -> editStep(step)
                        }
                    }.show()
            }
            else -> editStep(step)
        }
    }

    private fun chooseStepType() {
        val types = StepType.values()
        AlertDialog.Builder(this)
            .setTitle("단계 추가")
            .setItems(types.map { it.display }.toTypedArray()) { _, w ->
                val step = Step(type = types[w])
                currentList.add(step)
                adapter?.notifyItemInserted(currentList.size - 1)
                if (step.type != StepType.BACK && step.type != StepType.HOME) editStep(step)
            }.show()
    }

    // ---------------- 단계별 파라미터 편집 ----------------

    private fun editStep(step: Step) {
        when (step.type) {
            StepType.TAP -> dialogTap(step)
            StepType.SWIPE -> dialogSwipe(step)
            StepType.WAIT -> dialogWait(step)
            StepType.TOAST -> dialogToast(step)
            StepType.LOOP -> dialogLoop(step)
            StepType.FIND_TAP -> dialogImage(step, withOffset = true)
            StepType.IF_IMAGE -> dialogImage(step, withOffset = false)
            StepType.JUMP -> dialogJump(step)
            StepType.BACK, StepType.HOME -> {}
        }
    }

    private fun dialogJump(step: Step) {
        val root = column()
        root.addView(hint("조건이 맞으면 지정한 번호의 단계로 이동합니다.\n(번호는 최상위 단계 기준 1부터)"))
        val eGoto = numField("이동할 단계 번호 (1부터)", step.gotoStep)
        root.addView(eGoto)
        root.addView(hint("조건 이미지: ${step.templateName ?: "없음 (무조건 이동)"}"))
        root.addView(pickButton("🖼 조건 이미지 선택/캡처") {
            step.gotoStep = eGoto.intVal().coerceAtLeast(1)
            pickTemplate { name -> step.templateName = name; dialogJump(step) }
        })
        root.addView(pickButton(
            if (step.jumpIfFound) "조건: 이미지가 보이면 이동 ✓" else "조건: 이미지가 안 보이면 이동 ✓"
        ) {
            step.gotoStep = eGoto.intVal().coerceAtLeast(1)
            step.jumpIfFound = !step.jumpIfFound
            dialogJump(step)
        })
        root.addView(pickButton("무조건 이동으로 (이미지 제거)") {
            step.templateName = null
            step.gotoStep = eGoto.intVal().coerceAtLeast(1)
            dialogJump(step)
        })
        show("조건 이동", root) { step.gotoStep = eGoto.intVal().coerceAtLeast(1) }
    }

    private fun dialogTap(step: Step) {
        val root = column()
        val ex = numField("X 좌표", step.x)
        val ey = numField("Y 좌표", step.y)
        root.addView(ex); root.addView(ey)
        root.addView(pickButton("📍 화면에서 좌표 선택") {
            step.x = ex.intVal(); step.y = ey.intVal()
            pickPoint("탭할 위치 선택") { x, y -> step.x = x; step.y = y; dialogTap(step) }
        })
        show("탭 (좌표)", root) {
            step.x = ex.intVal(); step.y = ey.intVal()
        }
    }

    private fun dialogSwipe(step: Step) {
        val root = column()
        val ex1 = numField("시작 X", step.x); val ey1 = numField("시작 Y", step.y)
        val ex2 = numField("끝 X", step.x2); val ey2 = numField("끝 Y", step.y2)
        val ed = numField("시간(ms)", step.duration.toInt())
        listOf(ex1, ey1, ex2, ey2, ed).forEach { root.addView(it) }
        root.addView(pickButton("📍 시작점 선택") {
            saveSwipe(step, ex1, ey1, ex2, ey2, ed)
            pickPoint("시작점 선택") { x, y -> step.x = x; step.y = y; dialogSwipe(step) }
        })
        root.addView(pickButton("📍 끝점 선택") {
            saveSwipe(step, ex1, ey1, ex2, ey2, ed)
            pickPoint("끝점 선택") { x, y -> step.x2 = x; step.y2 = y; dialogSwipe(step) }
        })
        show("스와이프", root) { saveSwipe(step, ex1, ey1, ex2, ey2, ed) }
    }

    private fun saveSwipe(s: Step, a: EditText, b: EditText, c: EditText, d: EditText, e: EditText) {
        s.x = a.intVal(); s.y = b.intVal(); s.x2 = c.intVal(); s.y2 = d.intVal()
        s.duration = e.intVal().toLong().coerceAtLeast(50)
    }

    private fun dialogWait(step: Step) {
        val root = column()
        val e = numField("대기 시간(ms)", step.waitMs.toInt())
        root.addView(e)
        show("대기", root) { step.waitMs = e.intVal().toLong().coerceAtLeast(0) }
    }

    private fun dialogToast(step: Step) {
        val root = column()
        val e = textField("표시할 메시지", step.message)
        root.addView(e)
        show("메시지", root) { step.message = e.text.toString() }
    }

    private fun dialogLoop(step: Step) {
        val root = column()
        val e = numField("반복 횟수 (0 = 무한)", step.loopCount)
        root.addView(e)
        root.addView(hint("확인 후 단계를 탭하면 반복 안쪽으로 들어갑니다."))
        show("반복", root) { step.loopCount = e.intVal().coerceAtLeast(0) }
    }

    private fun dialogImage(step: Step, withOffset: Boolean) {
        val root = column()
        val tplLabel = hint("템플릿: ${step.templateName ?: "미지정"}")
        root.addView(tplLabel)
        root.addView(pickButton("🖼 이미지 선택/캡처") {
            pickTemplate { name -> step.templateName = name; dialogImage(step, withOffset) }
        })
        val eth = numField("일치 임계값 % (예: 85)", (step.threshold * 100).toInt())
        root.addView(eth)

        val eOffX: EditText?; val eOffY: EditText?
        if (withOffset) {
            eOffX = numField("탭 보정 X (중앙기준)", step.offsetX)
            eOffY = numField("탭 보정 Y (중앙기준)", step.offsetY)
            root.addView(eOffX); root.addView(eOffY)
        } else { eOffX = null; eOffY = null }

        root.addView(hint(if (step.hasRegion())
            "검색영역: (${step.regionL},${step.regionT})~(${step.regionR},${step.regionB})"
        else "검색영역: 전체화면"))
        root.addView(pickButton("◰ 영역 좌상단 선택") {
            applyImage(step, eth, eOffX, eOffY)
            pickPoint("검색영역 좌상단") { x, y -> step.regionL = x; step.regionT = y; dialogImage(step, withOffset) }
        })
        root.addView(pickButton("◳ 영역 우하단 선택") {
            applyImage(step, eth, eOffX, eOffY)
            pickPoint("검색영역 우하단") { x, y -> step.regionR = x; step.regionB = y; dialogImage(step, withOffset) }
        })
        root.addView(pickButton("⤬ 영역 초기화(전체화면)") {
            step.regionL = -1; step.regionT = -1; step.regionR = -1; step.regionB = -1
            applyImage(step, eth, eOffX, eOffY); dialogImage(step, withOffset)
        })

        val title = if (withOffset) "이미지 찾아 탭" else "이미지가 보이면 (조건)"
        show(title, root) { applyImage(step, eth, eOffX, eOffY) }
    }

    private fun applyImage(step: Step, eth: EditText, ox: EditText?, oy: EditText?) {
        step.threshold = (eth.intVal().coerceIn(10, 100)) / 100f
        ox?.let { step.offsetX = it.intVal() }
        oy?.let { step.offsetY = it.intVal() }
    }

    // ---------------- 결과 런처 헬퍼 ----------------

    private fun pickPoint(label: String, cb: (Int, Int) -> Unit) {
        pendingPoint = cb
        pointLauncher.launch(Intent(this, CoordinatePickerActivity::class.java)
            .putExtra(CoordinatePickerActivity.EXTRA_LABEL, label))
    }

    private fun pickTemplate(cb: (String) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("템플릿 이미지 가져오기")
            .setItems(arrayOf(
                "📷 화면 캡처 (다른 앱 위에서)",
                "🖼 갤러리/스크린샷에서 잘라내기",
                "📁 저장된 템플릿에서 선택"
            )) { _, w ->
                when (w) {
                    0 -> overlayCapture(cb)
                    1 -> {
                        pendingTemplate = cb
                        cropLauncher.launch(Intent(this, RegionCropActivity::class.java))
                    }
                    2 -> pickSavedTemplate(cb)
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /**
     * 다른 앱 위에서 직접 캡처: 앱을 내리고 떠다니는 📷 셔터를 띄운다.
     * 캡처가 끝나면 서비스가 CaptureBus 로 결과를 주고 편집기를 다시 앞으로 가져온다.
     */
    private fun overlayCapture(cb: (String) -> Unit) {
        if (!PermissionUtil.canDrawOverlay(this)) {
            Toast.makeText(this, "'다른 앱 위에 표시' 권한을 먼저 허용하세요", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
            return
        }
        awaitingCapture = true
        CaptureBus.onResult = { name ->
            awaitingCapture = false
            if (name != null && !isFinishing && !isDestroyed) cb(name)
        }
        if (MacroService.isRunning) {
            // 프로젝션이 이미 살아있으면 바로 캡처 세션 요청
            startService(Intent(this, MacroService::class.java).apply {
                action = MacroService.ACTION_CAPTURE
                putExtra(MacroService.EXTRA_RETURN_MACRO_ID, macro.id)
            })
            moveTaskToBack(true)
        } else {
            // 화면 캡처 동의부터 받는다 (허용되면 캡처 전용 서비스가 셔터를 띄움)
            startActivity(Intent(this, ProjectionRequestActivity::class.java).apply {
                putExtra(ProjectionRequestActivity.EXTRA_CAPTURE_MODE, true)
                putExtra(ProjectionRequestActivity.EXTRA_MACRO_ID, macro.id)
            })
        }
    }

    private fun pickSavedTemplate(cb: (String) -> Unit) {
        val files = MacroStore.listTemplates(this)
        if (files.isEmpty()) {
            Toast.makeText(this, "저장된 템플릿이 없습니다. '📷 화면 캡처'로 만들어 보세요.", Toast.LENGTH_LONG).show()
            return
        }
        val density = resources.displayMetrics.density
        val thumb = (56 * density).toInt()
        val pad = (10 * density).toInt()
        val adapter = object : ArrayAdapter<File>(this, 0, files) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView as? LinearLayout ?: LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(pad, pad, pad, pad)
                    addView(ImageView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(thumb, thumb)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    })
                    addView(TextView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                        ).apply { marginStart = pad }
                    })
                }
                val f = getItem(position)!!
                val bmp = BitmapFactory.decodeFile(f.absolutePath)
                (row.getChildAt(0) as ImageView).setImageBitmap(bmp)
                (row.getChildAt(1) as TextView).text =
                    if (bmp != null) "${f.name}\n${bmp.width}×${bmp.height}" else f.name
                return row
            }
        }
        AlertDialog.Builder(this)
            .setTitle("저장된 템플릿")
            .setAdapter(adapter) { _, w -> cb(files[w].name) }
            .setNegativeButton("취소", null)
            .show()
    }

    // ---------------- 다이얼로그 빌더 헬퍼 ----------------

    private fun column(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val p = (16 * resources.displayMetrics.density).toInt()
        setPadding(p, p, p, 0)
    }

    private fun numField(hint: String, value: Int): EditText = EditText(this).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
        setText(value.toString())
    }

    private fun textField(hint: String, value: String): EditText = EditText(this).apply {
        this.hint = hint; setText(value)
    }

    private fun hint(text: String): TextView = TextView(this).apply {
        this.text = text
        setPadding(0, 12, 0, 12)
    }

    private fun pickButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        setOnClickListener { runningDialog?.dismiss(); onClick() }
    }

    private var runningDialog: AlertDialog? = null

    private fun show(title: String, content: View, onOk: () -> Unit) {
        val scroll = ScrollView(this).apply { addView(content) }
        runningDialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("확인") { _, _ -> onOk(); adapter?.notifyDataSetChanged() }
            .setNegativeButton("취소", null)
            .create()
        runningDialog!!.show()
    }

    private fun EditText.intVal(): Int = text.toString().trim().toIntOrNull() ?: 0

    // ---------------- 저장 ----------------

    private fun save() {
        macro.name = b.editName.text.toString().ifBlank { "이름없는 매크로" }
        macro.repeatCount = b.editRepeat.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 1
        macro.stepDelayMs = b.editDelay.text.toString().toLongOrNull()?.coerceAtLeast(0) ?: 300
        MacroStore.upsert(this, macro)
        Toast.makeText(this, "저장됨", Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        const val EXTRA_MACRO_ID = "macro_id"
    }
}
