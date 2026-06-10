package com.imagemacro.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Releases 기반 인앱 업데이트.
 *
 * 동작 방식:
 *  1. https://api.github.com/repos/{OWNER}/{REPO}/releases/latest 에서 최신 릴리스 조회
 *  2. 태그(v1.2 형식)와 현재 versionName 비교
 *  3. 새 버전이면 릴리스에 첨부된 APK 에셋을 내려받아 설치 화면 호출
 *
 * 사용하려면 GitHub 저장소에 "v버전명" 태그로 릴리스를 만들고 APK를 에셋으로 첨부하면 된다.
 */
object UpdateManager {

    private const val OWNER = "Hwanje"
    private const val REPO = "image_macro"
    private const val API_LATEST = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    data class ReleaseInfo(
        val versionName: String,   // 태그에서 v 접두어를 뗀 값 (예: "1.2")
        val apkUrl: String,
        val notes: String
    )

    /** 새 버전이 있으면 ReleaseInfo, 없거나 확인 실패면 null. 네트워크 예외는 던지지 않는다. */
    suspend fun checkForUpdate(context: Context): ReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(API_LATEST).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            val body = try {
                if (conn.responseCode != 200) return@withContext null
                conn.inputStream.bufferedReader().readText()
            } finally {
                conn.disconnect()
            }

            val json = JsonParser.parseString(body).asJsonObject
            val tag = json.get("tag_name")?.asString ?: return@withContext null
            val remote = tag.removePrefix("v").removePrefix("V")

            val current = context.packageManager
                .getPackageInfo(context.packageName, 0).versionName ?: return@withContext null
            if (!isNewer(remote, current)) return@withContext null

            val apkUrl = json.getAsJsonArray("assets")
                ?.map { it.asJsonObject }
                ?.firstOrNull { it.get("name")?.asString?.endsWith(".apk") == true }
                ?.get("browser_download_url")?.asString
                ?: return@withContext null

            ReleaseInfo(
                versionName = remote,
                apkUrl = apkUrl,
                notes = json.get("body")?.takeIf { !it.isJsonNull }?.asString ?: ""
            )
        } catch (e: Exception) {
            null
        }
    }

    /** "1.2.1" 형식 버전 문자열을 숫자 단위로 비교 */
    private fun isNewer(remote: String, current: String): Boolean {
        val r = remote.split(".").map { it.trim().toIntOrNull() ?: 0 }
        val c = current.split(".").map { it.trim().toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, c.size)) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv != cv) return rv > cv
        }
        return false
    }

    /** APK를 앱 전용 캐시에 내려받는다. onProgress는 0~100 (전체 크기를 모르면 -1). */
    suspend fun downloadApk(
        context: Context,
        info: ReleaseInfo,
        onProgress: (Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // 이전에 받다 만 파일이 남지 않도록 매번 비운다
        dir.listFiles()?.forEach { it.delete() }
        val outFile = File(dir, "update-${info.versionName}.apk")

        val conn = URL(info.apkUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode != 200) {
                throw IllegalStateException("다운로드 실패 (HTTP ${conn.responseCode})")
            }
            val total = conn.contentLength
            conn.inputStream.use { input ->
                outFile.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        downloaded += n
                        onProgress(if (total > 0) (downloaded * 100 / total).toInt() else -1)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        outFile
    }

    /** 출처를 알 수 없는 앱 설치 권한이 있는지 (Android 8.0+) */
    fun canInstallPackages(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /** 출처를 알 수 없는 앱 설치 허용 설정 화면으로 이동 */
    fun openInstallPermissionSettings(context: Context) {
        val i = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        )
        context.startActivity(i)
    }

    /** 내려받은 APK의 패키지 설치 화면을 띄운다 */
    fun installApk(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(i)
    }
}
