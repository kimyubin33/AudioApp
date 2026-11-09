package com.kimyubin33.audioapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kimyubin33.audioapp.ui.theme.Ash
import com.kimyubin33.audioapp.ui.theme.AudioAppTheme
import com.kimyubin33.audioapp.ui.theme.Ink

/**
 * 하단 탭 네 개.
 *  - 오디오: 각 채널이 만든 오디오 (실시간 오디오는 나중에 이 탭에 들어온다)
 *  - 숏츠: AI 숏츠 영상
 *  - 구독: 구독한 채널의 숏츠와 영상
 *  - 보관함: 시청 기록, 저장한 항목, 차단 관리, 설정
 */
enum class MainTab(val label: String) {
    Audio("오디오"),
    Shorts("숏츠"),
    Subscriptions("구독"),
    Library("보관함"),
}

/** 붓으로 그은 듯 끝이 둥근 선으로 그린 탭 아이콘 (24 x 24 칸 기준). */
@Composable
private fun TabIcon(tab: MainTab, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val u = size.minDimension / 24f
        val style = Stroke(width = 1.9f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (tab) {
            // 소리의 높낮이: 길이가 다른 세로 획 네 줄
            MainTab.Audio -> {
                listOf(5f to (10f to 14f), 10f to (5f to 19f), 15f to (8f to 16f), 20f to (11f to 13f)).forEach { (x, y) ->
                    drawLine(tint, Offset(x * u, y.first * u), Offset(x * u, y.second * u), strokeWidth = 2.2f * u, cap = StrokeCap.Round)
                }
            }

            // 세로로 긴 화면 + 재생 표시
            MainTab.Shorts -> {
                drawRoundRect(tint, topLeft = Offset(6.5f * u, 2.5f * u), size = Size(11f * u, 19f * u), cornerRadius = CornerRadius(3.2f * u), style = style)
                playMark(tint, 12f * u, 12f * u, 3.4f * u)
            }

            // 겹쳐 쌓인 화면 (구독 목록)
            MainTab.Subscriptions -> {
                drawLine(tint, Offset(6f * u, 4.5f * u), Offset(18f * u, 4.5f * u), strokeWidth = 1.9f * u, cap = StrokeCap.Round)
                drawLine(tint, Offset(8.5f * u, 8f * u), Offset(15.5f * u, 8f * u), strokeWidth = 1.9f * u, cap = StrokeCap.Round)
                drawRoundRect(tint, topLeft = Offset(3f * u, 11.5f * u), size = Size(18f * u, 10f * u), cornerRadius = CornerRadius(2.4f * u), style = style)
                playMark(tint, 12.2f * u, 16.5f * u, 2.6f * u)
            }

            // 책갈피
            MainTab.Library -> {
                val bookmark = Path().apply {
                    moveTo(7f * u, 3.5f * u)
                    lineTo(17f * u, 3.5f * u)
                    lineTo(17f * u, 20.5f * u)
                    lineTo(12f * u, 16.2f * u)
                    lineTo(7f * u, 20.5f * u)
                    close()
                }
                drawPath(bookmark, tint, style = style)
            }
        }
    }
}

/** 가운데가 (cx, cy)인 작은 재생 삼각형(채움). */
private fun DrawScope.playMark(color: Color, cx: Float, cy: Float, r: Float) {
    val path = Path().apply {
        moveTo(cx - r * 0.55f, cy - r)
        lineTo(cx + r * 0.95f, cy)
        lineTo(cx - r * 0.55f, cy + r)
        close()
    }
    drawPath(path, color)
}

/** 화면 맨 아래 탭 막대. 위쪽에 붓으로 그은 가는 선이 있고, 선택된 탭은 아래에 먹점으로 표시한다. */
@Composable
fun BottomNav(selected: MainTab, onSelect: (MainTab) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        InkDivider()
        Row(modifier = Modifier.fillMaxWidth().height(58.dp)) {
            MainTab.entries.forEach { tab ->
                val isSelected = tab == selected
                val tint = if (isSelected) Ink else Ash
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(tab) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    TabIcon(tab = tab, tint = tint, modifier = Modifier.size(24.dp))
                    Text(text = tab.label, style = MaterialTheme.typography.labelSmall, color = tint)
                    Box(
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(4.dp)
                            .background(if (isSelected) Ink else Color.Transparent, CircleShape),
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 100)
@Composable
private fun BottomNavPreview() {
    AudioAppTheme { BottomNav(selected = MainTab.Shorts, onSelect = {}) }
}
