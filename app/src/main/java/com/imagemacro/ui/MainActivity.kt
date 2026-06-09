package com.imagemacro.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.imagemacro.capture.ProjectionRequestActivity
import com.imagemacro.databinding.ActivityMainBinding
import com.imagemacro.model.Macro
import com.imagemacro.model.MacroStore
import com.imagemacro.util.PermissionUtil

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
        b.btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 99)
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
