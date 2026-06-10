package com.imagemacro.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * 매크로 목록과 템플릿 이미지를 앱 내부 저장소에 보관한다.
 * - macros.json : 매크로 정의
 * - templates/  : 이미지 감지용 템플릿 PNG
 */
object MacroStore {
    private val gson = Gson()

    private fun macrosFile(ctx: Context) = File(ctx.filesDir, "macros.json")
    fun templatesDir(ctx: Context): File =
        File(ctx.filesDir, "templates").apply { if (!exists()) mkdirs() }

    fun load(ctx: Context): MutableList<Macro> {
        val f = macrosFile(ctx)
        if (!f.exists()) return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<Macro>>() {}.type
            gson.fromJson<MutableList<Macro>>(f.readText(), type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun saveAll(ctx: Context, macros: List<Macro>) {
        macrosFile(ctx).writeText(gson.toJson(macros))
    }

    fun upsert(ctx: Context, macro: Macro) {
        val list = load(ctx)
        val i = list.indexOfFirst { it.id == macro.id }
        if (i >= 0) list[i] = macro else list.add(macro)
        saveAll(ctx, list)
    }

    fun delete(ctx: Context, id: String) {
        val list = load(ctx).filterNot { it.id == id }
        saveAll(ctx, list)
    }

    fun find(ctx: Context, id: String): Macro? = load(ctx).firstOrNull { it.id == id }

    // ---- 템플릿 이미지 ----

    fun templateFile(ctx: Context, name: String) = File(templatesDir(ctx), name)

    fun saveTemplate(ctx: Context, bitmap: Bitmap): String {
        val name = "tpl_${System.currentTimeMillis()}.png"
        templateFile(ctx, name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        return name
    }

    fun loadTemplate(ctx: Context, name: String?): Bitmap? {
        if (name == null) return null
        val f = templateFile(ctx, name)
        if (!f.exists()) return null
        return BitmapFactory.decodeFile(f.absolutePath)
    }

    /** 저장된 템플릿 PNG 목록 (최신순) */
    fun listTemplates(ctx: Context): List<File> =
        templatesDir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".png") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
}
