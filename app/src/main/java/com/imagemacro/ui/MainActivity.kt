package com.imagemacro.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.imagemacro.capture.ProjectionRequestActivity
import com.imagemacro.databinding.ActivityMainBinding
import com.imagemacro.model.Macro
import com.imagemacro.model.MacroStore
import com.imagemacro.update.UpdateManager
import com.imagemacro.util.PermissionUtil
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var adapter: MacroListAdapter
    private var macros = mutableListOf<Macro>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        adapter = MacroListAdapter(
            onRun = { runMacro(it) },
            onEdit = { openEditor(it.id) },
            onDelete = { confirmDelete(it) }
        )
        b.recycler.layoutManager = LinearLayoutManager(this)
        b.recycler.adapter = adapter

        b.fabAdd.setOnClickListener { openEditor(null) }
        b.btnOverlay.setOnClickListener { requestOverlay() }
        b.btnOverlayBuild.setOnClickListener { startOverlayBuilder() }
        b.btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 99)
        }

        checkForUpdate()
    }

    /** 앱 실행 시 GitHub 최신 릴리스를 확인하고 새 버전이 있으면 업데이트를 제안한다 */
    private fun checkForUpdate() {
        lifecycleScope.launch {
            val info = UpdateManager.checkForUpdate(this@MainActivity) ?: return@launch
            if (isFinishing || isDestroyed) return@launch
            AlertDialog.Builder(this@MainActivity)
                .setTitle("업데이트 알림")
                .setMessage(
                    buildString {
                        append("새 버전 v${info.versionName}이(가) 있습니다.\n지금 업데이트할까요?")
                        if (info.notes.isNotBlank()) {
                            append("\n\n변경 사항:\n${info.notes.trim()}")
                        }
                    }
                )
                .setPositiveButton("업데이트") { _, _ -> startUpdate(info) }
                .setNegativeButton("나중에", null)
                .show()
        }
    }

    private fun startUpdate(info: UpdateManager.ReleaseInfo) {
        if (!UpdateManager.canInstallPackages(this)) {
            Toast.makeText(this, "'이 출처 허용'을 켠 뒤 다시 시도하세요", Toast.LENGTH_LONG).show()
            UpdateManager.openInstallPermissionSettings(this)
            return
        }

        val pad = (16 * resources.displayMetrics.density).toInt()
        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        val percentText = TextView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(progressBar, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(percentText)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("업데이트 다운로드 중")
            .setView(container)
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            try {
                val apk = UpdateManager.downloadApk(this@MainActivity, info) { p ->
                    runOnUiThread {
                        if (p >= 0) {
                            progressBar.isIndeterminate = false
                            progressBar.progress = p
                            percentText.text = "$p%"
                        } else {
                            progressBar.isIndeterminate = true
                        }
                    }
                }
                dialog.dismiss()
                UpdateManager.installApk(this@MainActivity, apk)
            } catch (e: Exception) {
                dialog.dismiss()
                Toast.makeText(
                    this@MainActivity,
                    "업데이트 다운로드 실패: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
        refreshPermissionUi()
    }

    private fun reload() {
        macros = MacroStore.load(this)
        adapter.submit(macros)
        b.emptyView.visibility = if (macros.isEmpty()) android.view.View.VISIBLE
                                 else android.view.View.GONE
    }

    private fun refreshPermissionUi() {
        val overlay = PermissionUtil.canDrawOverlay(this)
        val acc = PermissionUtil.isAccessibilityEnabled(this)
        b.btnOverlay.text = if (overlay) "✓ 다른 앱 위에 표시  허용됨" else "① 다른 앱 위에 표시 권한 허용"
        b.btnOverlay.isEnabled = !overlay
        b.btnAccessibility.text = if (acc) "✓ 접근성 서비스  켜짐" else "② 접근성 서비스 켜기"
        b.btnAccessibility.isEnabled = !acc
    }

    private fun requestOverlay() {
        val i = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(i)
    }

    private fun openEditor(id: String?) {
        val i = Intent(this, MacroEditorActivity::class.java)
        if (id != null) i.putExtra(MacroEditorActivity.EXTRA_MACRO_ID, id)
        startActivity(i)
    }

    private fun runMacro(macro: Macro) {
        if (!PermissionUtil.canDrawOverlay(this)) {
            Toast.makeText(this, "먼저 '다른 앱 위에 표시' 권한을 허용하세요", Toast.LENGTH_SHORT).show()
            requestOverlay(); return
        }
        if (!PermissionUtil.isAccessibilityEnabled(this)) {
            Toast.makeText(this, "먼저 접근성 서비스를 켜세요", Toast.LENGTH_SHORT).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return
        }
        if (macro.steps.isEmpty()) {
            Toast.makeText(this, "단계가 없는 매크로입니다", Toast.LENGTH_SHORT).show(); return
        }
        val i = Intent(this, ProjectionRequestActivity::class.java).apply {
            putExtra(ProjectionRequestActivity.EXTRA_MACRO_ID, macro.id)
        }
        startActivity(i)
        Toast.makeText(this, "오버레이 패널에서 ▶ 시작을 누르세요", Toast.LENGTH_LONG).show()
    }

    /**
     * 새 매크로를 만들고 곧바로 오버레이 빌더를 띄운다.
     * 다른 앱으로 전환한 뒤 떠 있는 패널의 ＋탭 / ＋이미지 로 단계를 쌓을 수 있다.
     */
    private fun startOverlayBuilder() {
        if (!PermissionUtil.canDrawOverlay(this)) {
            Toast.makeText(this, "먼저 '다른 앱 위에 표시' 권한을 허용하세요", Toast.LENGTH_SHORT).show()
            requestOverlay(); return
        }
        val macro = Macro(name = "오버레이 매크로")
        MacroStore.upsert(this, macro)
        val i = Intent(this, ProjectionRequestActivity::class.java).apply {
            putExtra(ProjectionRequestActivity.EXTRA_MACRO_ID, macro.id)
        }
        startActivity(i)
        Toast.makeText(
            this,
            "대상 앱으로 이동한 뒤, 떠 있는 패널의 ＋탭 위치 / ＋이미지 찾아 탭 으로 단계를 추가하세요",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun confirmDelete(macro: Macro) {
        AlertDialog.Builder(this)
            .setTitle("삭제")
            .setMessage("'${macro.name}' 매크로를 삭제할까요?")
            .setPositiveButton("삭제") { _, _ ->
                MacroStore.delete(this, macro.id); reload()
            }
            .setNegativeButton("취소", null)
            .show()
    }
}
