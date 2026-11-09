package com.kimyubin33.audioapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kimyubin33.audioapp.BuildConfig
import com.kimyubin33.audioapp.ui.theme.Ash
import com.kimyubin33.audioapp.ui.theme.AudioAppTheme
import com.kimyubin33.audioapp.ui.theme.Ink

/** 하단 탭. 숏폼 탭은 숏폼 화면을 만들 때 추가한다. */
enum class MainTab(val label: String) {
    Home("홈"),
    Me("나"),
}

/** 붓으로 그은 듯 끝이 둥근 선으로 그린 탭 아이콘 (24 x 24 칸 기준). */
@Composable
private fun TabIcon(tab: MainTab, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val u = size.minDimension / 24f
        val style = Stroke(width = 1.9f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (tab) {
            MainTab.Home -> {
                val roof = Path().apply {
                    moveTo(3f * u, 11f * u)
                    lineTo(12f * u, 3.5f * u)
                    lineTo(21f * u, 11f * u)
                }
                val body = Path().apply {
                    moveTo(5.5f * u, 9.5f * u)
                    lineTo(5.5f * u, 20f * u)
                    lineTo(18.5f * u, 20f * u)
                    lineTo(18.5f * u, 9.5f * u)
                }
                drawPath(roof, tint, style = style)
                drawPath(body, tint, style = style)
            }

            MainTab.Me -> {
                drawCircle(tint, radius = 3.7f * u, center = Offset(12f * u, 8f * u), style = style)
                val shoulders = Path().apply {
                    moveTo(4.5f * u, 20.5f * u)
                    cubicTo(4.5f * u, 15.8f * u, 8f * u, 13.8f * u, 12f * u, 13.8f * u)
                    cubicTo(16f * u, 13.8f * u, 19.5f * u, 15.8f * u, 19.5f * u, 20.5f * u)
                }
                drawPath(shoulders, tint, style = style)
            }
        }
    }
}

/** 화면 맨 아래 탭 막대. 위쪽에 붓으로 그은 가는 선이 있고, 선택된 탭은 먹점으로 표시한다. */
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
                    // 선택 표시: 작은 먹점
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

/** "나" 탭. 계정과 관련된 항목을 모아 둔다. 지금은 차단 관리와 앱 정보. */
@Composable
fun MeScreen(onOpenBlocked: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = "나",
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp),
        )
        InkDivider(Modifier.padding(bottom = 8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onOpenBlocked)
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("차단 관리", style = MaterialTheme.typography.titleMedium)
                Text("차단한 크리에이터를 확인하고 해제합니다", style = MaterialTheme.typography.bodySmall, color = Ash)
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = Ash)
        }
        InkDivider(Modifier.padding(horizontal = 20.dp))

        Spacer(Modifier.weight(1f))
        Text(
            text = "버전 ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall,
            color = Ash,
            modifier = Modifier.padding(20.dp),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun MeScreenPreview() {
    AudioAppTheme { MeScreen(onOpenBlocked = {}) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 100)
@Composable
private fun BottomNavPreview() {
    AudioAppTheme { BottomNav(selected = MainTab.Home, onSelect = {}) }
}
