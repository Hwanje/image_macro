package com.imagemacro.engine

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 화면 좌표(원본 해상도) 기준 매칭 결과 */
data class MatchResult(
    val centerX: Int,
    val centerY: Int,
    val left: Int,
    val top: Int,
    val score: Float
)

private class GrayImg(val w: Int, val h: Int, val px: FloatArray)

/**
 * 화면에서 템플릿 이미지를 찾는다.
 * 그레이스케일 + 다운스케일 후 ZNCC(zero-mean normalized cross-correlation) 로
 * 밝기 변화에 어느정도 강인하게 매칭한다. 거친 탐색 -> 정밀 탐색 2단계.
 */
object TemplateMatcher {

    /**
     * @param screen   캡처된 전체 화면 비트맵
     * @param template 찾을 템플릿
     * @param threshold 0~1, 이 값 이상이면 발견으로 간주
     * @param region   검색 영역(원본 좌표, 없으면 null=전체)
     */
    fun match(
        screen: Bitmap,
        template: Bitmap,
        threshold: Float,
        region: IntArray? = null
    ): MatchResult? {
        val sl = region?.getOrNull(0)?.coerceIn(0, screen.width) ?: 0
        val st = region?.getOrNull(1)?.coerceIn(0, screen.height) ?: 0
        val sr = region?.getOrNull(2)?.coerceIn(0, screen.width) ?: screen.width
        val sb = region?.getOrNull(3)?.coerceIn(0, screen.height) ?: screen.height
        val regW = sr - sl
        val regH = sb - st
        if (regW <= 0 || regH <= 0) return null
        if (template.width > regW || template.height > regH) return null

        // 다운스케일 비율: 화면 짧은변 기준 ~360px 가 되도록.
        val scale = max(1, min(screen.width, screen.height) / 360)

        val scr = toGray(screen, sl, st, regW, regH, scale)
        val tpl = toGray(template, 0, 0, template.width, template.height, scale)
        if (tpl.w < 3 || tpl.h < 3) return null
        if (tpl.w > scr.w || tpl.h > scr.h) return null

        // 템플릿 zero-mean & norm 사전계산
        var tMean = 0f
        for (v in tpl.px) tMean += v
        tMean /= tpl.px.size
        var tNorm = 0f
        val tZero = FloatArray(tpl.px.size)
        for (i in tpl.px.indices) {
            val d = tpl.px[i] - tMean
            tZero[i] = d
            tNorm += d * d
        }
        tNorm = sqrt(tNorm)
        if (tNorm < 1e-3f) return null  // 단색 템플릿은 매칭 불가

        // 1단계: 거친 탐색 (stride)
        val coarseStride = max(1, min(tpl.w, tpl.h) / 4)
        var best = nccScan(scr, tpl, tZero, tNorm, 0, 0, scr.w - tpl.w, scr.h - tpl.h, coarseStride)
        if (best == null) return null

        // 2단계: best 주변 정밀 탐색 (stride=1)
        val pad = coarseStride
        val fine = nccScan(
            scr, tpl, tZero, tNorm,
            max(0, best.first - pad), max(0, best.second - pad),
            min(scr.w - tpl.w, best.first + pad), min(scr.h - tpl.h, best.second + pad),
            1
        )
        if (fine != null && fine.third > best.third) best = fine

        if (best.third < threshold) return null

        // 다운스케일 좌표 -> 원본 화면 좌표
        val left = sl + best.first * scale
        val top = st + best.second * scale
        val cx = left + (tpl.w * scale) / 2
        val cy = top + (tpl.h * scale) / 2
        return MatchResult(cx, cy, left, top, best.third)
    }

    /** 주어진 범위를 stride 로 훑으며 ZNCC 최고점 위치 반환. Triple(bx,by,score) */
    private fun nccScan(
        scr: GrayImg, tpl: GrayImg, tZero: FloatArray, tNorm: Float,
        x0: Int, y0: Int, x1: Int, y1: Int, stride: Int
    ): Triple<Int, Int, Float>? {
        var bestScore = -1f
        var bestX = -1
        var bestY = -1
        val tw = tpl.w
        val th = tpl.h
        val n = tw * th
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                // 윈도우 평균
                var mean = 0f
                var ti = 0
                for (ty in 0 until th) {
                    var si = (y + ty) * scr.w + x
                    for (tx in 0 until tw) {
                        mean += scr.px[si]; si++; ti++
                    }
                }
                mean /= n
                // zero-mean 내적 & 윈도우 norm
                var dot = 0f
                var wNorm = 0f
                ti = 0
                for (ty in 0 until th) {
                    var si = (y + ty) * scr.w + x
                    for (tx in 0 until tw) {
                        val d = scr.px[si] - mean
                        dot += d * tZero[ti]
                        wNorm += d * d
                        si++; ti++
                    }
                }
                if (wNorm > 1e-3f) {
                    val score = dot / (sqrt(wNorm) * tNorm)
                    if (score > bestScore) {
                        bestScore = score; bestX = x; bestY = y
                    }
                }
                x += stride
            }
            y += stride
        }
        if (bestX < 0) return null
        return Triple(bestX, bestY, bestScore)
    }

    private fun toGray(bmp: Bitmap, l: Int, t: Int, w: Int, h: Int, scale: Int): GrayImg {
        val src = IntArray(w * h)
        bmp.getPixels(src, 0, w, l, t, w, h)
        val ow = w / scale
        val oh = h / scale
        val out = FloatArray(ow * oh)
        var oi = 0
        for (oy in 0 until oh) {
            val sy = oy * scale
            var rowBase = sy * w
            for (ox in 0 until ow) {
                val p = src[rowBase + ox * scale]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                out[oi++] = 0.299f * r + 0.587f * g + 0.114f * b
            }
        }
        return GrayImg(ow, oh, out)
    }
}
