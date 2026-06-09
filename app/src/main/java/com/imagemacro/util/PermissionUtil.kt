package com.imagemacro.util

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import com.imagemacro.service.MacroAccessibilityService

object PermissionUtil {

    fun canDrawOverlay(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    fun isAccessibilityEnabled(ctx: Context): Boolean {
        val expected = ComponentName(ctx, MacroAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            val name = splitter.next()
            if (name.equals(expected, ignoreCase = true)) return true
        }
        return false
    }
}
