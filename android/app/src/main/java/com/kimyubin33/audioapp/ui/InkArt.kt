package com.kimyubin33.audioapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.kimyubin33.audioapp.ui.theme.AudioAppTheme
import com.kimyubin33.audioapp.ui.theme.Ink
import com.kimyubin33.audioapp.ui.theme.PaperDeep
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * 곡마다 다른 먹 그림 썸네일. 이미지 파일 없이 [seed](곡 id)로 결정적으로 그린다.
 * 같은 곡은 언제나 같은 그림이고, 곡이 다르면 붓 자국이 달라진다.
 *  - 음원: 한 번에 그은 원상(엔소). 시작은 먹이 진하고 끝은 비백(마른 붓)으로 날린다.
 *  - 영상: 대각선으로 내리긋는 획 두 줄.
 */
@Composable
fun InkArt(seed: String, modifier: Modifier = Modifier, isVideo: Boolean = false) {
    // clipToBounds: 먹 번짐이 그림 영역 밖(제목 글씨 주변)으로 흘러나가지 않게 한다
    Canvas(modifier = modifier.background(PaperDeep).clipToBounds()) {
        drawInkArt(seed, isVideo)
    }
}

private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun DrawScope.drawInkArt(seed: String, isVideo: Boolean) {
    val rnd = Random(seed.hashCode())
    val w = size.width
    val h = size.height

    // 1) 종이에 스민 먹물: 아주 옅게 번진 얼룩 몇 개
    repeat(3) {
        val center = Offset(w * (0.1f + 0.8f * rnd.nextFloat()), h * (0.1f + 0.8f * rnd.nextFloat()))
        val radius = h * (0.45f + 0.5f * rnd.nextFloat())
        drawCircle(
            brush = Brush.radialGradient(listOf(Ink.copy(alpha = 0.07f + 0.05f * rnd.nextFloat()), Color.Transparent), center, radius),
            radius = radius,
            center = center,
        )
    }

    if (isVideo) {
        // 영상: 대각선 획 두 줄 (굵은 획, 가는 획)
        // 경로의 제어점은 여기서 한 번만 정한다 (경로 안에서 난수를 뽑으면 점마다 흔들려 획이 찢어진다)
        val flip = rnd.nextBoolean()
        fun x(v: Float) = if (flip) w - v else v
        fun j() = rnd.nextFloat() - 0.5f
        val a0 = Offset(x(w * (0.12f + 0.06f * j())), h * 0.84f)
        val a1 = Offset(x(w * 0.30f), h * (0.30f + 0.12f * j()))
        val a2 = Offset(x(w * 0.58f), h * (0.74f + 0.12f * j()))
        val a3 = Offset(x(w * (0.88f + 0.05f * j())), h * 0.18f)
        brushStroke(rnd = rnd, maxWidth = h * 0.085f, at = { t -> cubic(a0, a1, a2, a3, t) })

        val b0 = Offset(x(w * (0.30f + 0.05f * j())), h * 0.90f)
        val b1 = Offset(x(w * 0.44f), h * 0.50f)
        val b2 = Offset(x(w * 0.66f), h * 0.66f)
        val b3 = Offset(x(w * (0.80f + 0.06f * j())), h * 0.38f)
        brushStroke(rnd = rnd, maxWidth = h * 0.035f, at = { t -> cubic(b0, b1, b2, b3, t) }, dryStart = 0.30f)
    } else {
        // 음원: 원상 (엔소). 열린 틈의 위치와 크기가 곡마다 다르다.
        val c = Offset(w * (0.40f + 0.20f * rnd.nextFloat()), h * (0.46f + 0.08f * rnd.nextFloat()))
        val r = h * (0.27f + 0.08f * rnd.nextFloat())
        val start = rnd.nextFloat() * 2f * PI.toFloat()
        val sweep = (1.70f + 0.20f * rnd.nextFloat()) * PI.toFloat()
        val phase = rnd.nextFloat() * 6f
        brushStroke(
            rnd = rnd,
            maxWidth = r * 0.30f,
            at = { t ->
                val a = start + sweep * t
                val rr = r * (1f + 0.035f * sin(t * 3.2f * PI.toFloat() + phase))
                Offset(c.x + rr * cos(a), c.y + rr * sin(a))
            },
        )
        // 한 점 (달 또는 낙점): 원 안쪽 어딘가에 작은 먹점
        drawCircle(Ink.copy(alpha = 0.88f), radius = r * (0.07f + 0.03f * rnd.nextFloat()), center = Offset(c.x + r * 0.10f * (rnd.nextFloat() - 0.5f) * 4f, c.y + r * 0.12f * (rnd.nextFloat() - 0.5f) * 4f))
    }

    // 3) 튄 먹방울
    repeat(4 + rnd.nextInt(4)) {
        val p = Offset(w * rnd.nextFloat(), h * rnd.nextFloat())
        drawCircle(Ink.copy(alpha = 0.25f + 0.5f * rnd.nextFloat()), radius = h * (0.004f + 0.012f * rnd.nextFloat()), center = p)
    }
}

private fun cubic(p0: Offset, p1: Offset, p2: Offset, p3: Offset, t: Float): Offset {
    val u = 1f - t
    return Offset(
        u * u * u * p0.x + 3 * u * u * t * p1.x + 3 * u * t * t * p2.x + t * t * t * p3.x,
        u * u * u * p0.y + 3 * u * u * t * p1.y + 3 * u * t * t * p2.y + t * t * t * p3.y,
    )
}

/**
 * 붓 한 획. [at] 은 0..1 에서 붓이 지나는 길.
 * 시작은 붓을 꾹 눌러 진하고(먹을 머금음), 갈수록 가늘어지며 끝에서는 마른 붓결(비백)이 생긴다.
 */
private fun DrawScope.brushStroke(rnd: Random, maxWidth: Float, at: (Float) -> Offset, dryStart: Float = 0.42f) {
    val steps = 260
    fun width(t: Float) = maxWidth * (0.28f + 0.72f * smoothstep(0f, 0.12f, t)) * (1f - t).pow(0.55f)

    // 번진 가장자리 (물기 많은 먹): 크고 옅게 먼저
    for (i in 0..steps) {
        val t = i / steps.toFloat()
        drawCircle(Ink.copy(alpha = 0.10f), radius = width(t) * 0.62f, center = at(t))
    }
    // 획 본체
    for (i in 0..steps) {
        val t = i / steps.toFloat()
        val wobble = (rnd.nextFloat() - 0.5f) * maxWidth * 0.025f
        val p = at(t)
        drawCircle(Ink.copy(alpha = 0.93f), radius = (width(t) * 0.5f + wobble).coerceAtLeast(0.4f), center = p)
    }
    // 비백: 획 뒷부분을 붓털 결대로 가르는 마른 줄
    repeat(11) {
        val lateral = (rnd.nextFloat() - 0.5f) * 0.92f
        val t0 = dryStart + (1f - dryStart) * rnd.nextFloat() * 0.7f
        var prev: Offset? = null
        val seg = 36
        for (i in 0..seg) {
            val t = t0 + (0.985f - t0) * i / seg
            val p = at(t)
            val q = at((t + 0.01f).coerceAtMost(1f))
            val dx = q.x - p.x
            val dy = q.y - p.y
            val len = hypot(dx, dy).coerceAtLeast(0.001f)
            val off = lateral * width(t)
            val point = Offset(p.x + (-dy / len) * off, p.y + (dx / len) * off)
            prev?.let { drawLine(PaperDeep.copy(alpha = 0.85f), it, point, strokeWidth = maxWidth * (0.012f + 0.02f * rnd.nextFloat())) }
            prev = point
        }
    }
}

/** 한 번에 그은 듯 양 끝이 가늘고 가운데가 살짝 굵은 먹선. 구분선·장식에 쓴다. */
@Composable
fun InkDivider(modifier: Modifier = Modifier, color: Color = Ink.copy(alpha = 0.30f)) {
    Canvas(modifier = modifier.fillMaxWidth().height(5.dp)) {
        val mid = size.height / 2f
        val bulge = size.height * 0.40f
        val path = Path().apply {
            moveTo(0f, mid)
            quadraticTo(size.width / 2f, mid - bulge, size.width, mid)
            quadraticTo(size.width / 2f, mid + bulge * 0.55f, 0f, mid)
            close()
        }
        drawPath(path, color)
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 420)
@Composable
private fun InkArtPreview() {
    AudioAppTheme {
        androidx.compose.foundation.layout.Column {
            InkArt("sample-1", Modifier.fillMaxWidth().height(200.dp))
            InkDivider()
            InkArt("sample-4", Modifier.fillMaxWidth().height(200.dp), isVideo = true)
        }
    }
}
