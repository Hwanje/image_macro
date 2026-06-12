package com.imagemacro.capture

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.imagemacro.service.MacroService

/**
 * 화면 캡처 권한(MediaProjection)을 요청하는 투명 액티비티.
 * 허용되면 토큰을 MacroService 로 넘겨 오버레이/엔진(또는 캡처 전용 셔터)을 띄운다.
 */
class ProjectionRequestActivity : AppCompatActivity() {

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val captureMode = intent.getBooleanExtra(EXTRA_CAPTURE_MODE, false)
        val provideOnly = intent.getBooleanExtra(EXTRA_PROVIDE_ONLY, false)
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val macroId = intent.getStringExtra(EXTRA_MACRO_ID)
            val svc = Intent(this, MacroService::class.java).apply {
                action = when {
                    provideOnly -> MacroService.ACTION_PROVIDE_PROJECTION
                    captureMode -> MacroService.ACTION_START_CAPTURE
                    else -> MacroService.ACTION_START
                }
                putExtra(MacroService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(MacroService.EXTRA_DATA, result.data)
                if (captureMode) putExtra(MacroService.EXTRA_RETURN_MACRO_ID, macroId)
                else putExtra(MacroService.EXTRA_MACRO_ID, macroId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc)
            else startService(svc)
            // 캡처/제공 모드면 앱을 내려 캡처할 다른 앱이 보이게 한다
            if (captureMode || provideOnly) moveTaskToBack(true)
        } else if (captureMode) {
            CaptureBus.deliver(null)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        launcher.launch(mpm.createScreenCaptureIntent())
    }

    companion object {
        const val EXTRA_MACRO_ID = "macro_id"
        const val EXTRA_CAPTURE_MODE = "capture_mode"
        const val EXTRA_PROVIDE_ONLY = "provide_only"  // 실행중인 서비스에 프로젝션만 공급
    }
}
