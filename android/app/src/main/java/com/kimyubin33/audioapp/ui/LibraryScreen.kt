package com.kimyubin33.audioapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kimyubin33.audioapp.BuildConfig
import com.kimyubin33.audioapp.data.LibraryEntry
import com.kimyubin33.audioapp.ui.theme.Ash
import com.kimyubin33.audioapp.ui.theme.AudioAppTheme

/** 기록에 보여 줄 최대 개수 (더 오래된 것은 기기에는 남아 있지만 여기서는 최근 것만) */
private const val HISTORY_SHOWN = 20

/**
 * 보관함 탭. YouTube 모바일의 "나/보관함"처럼 시청 기록과 저장한 항목을 모아 보여 준다.
 * 기록과 저장은 이 기기 안에만 있고 서버로 보내지 않는다.
 */
@Composable
fun LibraryScreen(
    history: List<LibraryEntry>,
    saved: List<LibraryEntry>,
    onOpen: (String) -> Unit,
    onClearHistory: () -> Unit,
    onOpenBlocked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            Text(
                text = "보관함",
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp),
            )
            InkDivider(Modifier.padding(bottom = 8.dp))
        }

        // ── 기록 ──
        item {
            SectionHeader(
                title = "기록",
                action = if (history.isNotEmpty()) "모두 지우기" else null,
                onAction = onClearHistory,
            )
        }
        if (history.isEmpty()) {
            item { EmptyNote("아직 본 콘텐츠가 없습니다.") }
        } else {
            items(history.take(HISTORY_SHOWN), key = { "h-" + it.id }) { LibraryRow(it, onOpen) }
        }

        // ── 저장한 항목 ──
        item { SectionHeader(title = "저장한 항목", action = null, onAction = {}) }
        if (saved.isEmpty()) {
            item { EmptyNote("저장한 항목이 없습니다. 시청 화면에서 '저장'을 눌러 보세요.") }
        } else {
            items(saved, key = { "s-" + it.id }) { LibraryRow(it, onOpen) }
        }

        // ── 설정 ──
        item {
            InkDivider(Modifier.padding(top = 16.dp, start = 20.dp, end = 20.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onOpenBlocked)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("차단 관리", style = MaterialTheme.typography.titleMedium)
                    Text("차단한 크리에이터를 확인하고 해제합니다", style = MaterialTheme.typography.bodySmall, color = Ash)
                }
                Text("›", style = MaterialTheme.typography.titleLarge, color = Ash)
            }
            Text(
                text = "버전 ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = Ash,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, action: String?, onAction: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        if (action != null) {
            TextButton(onClick = onAction) { Text(action, color = Ash) }
        }
    }
}

@Composable
private fun EmptyNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = Ash,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

/** 작은 먹 그림 + 제목 + 보조 정보. */
@Composable
private fun LibraryRow(entry: LibraryEntry, onOpen: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "열기") { onOpen(entry.id) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InkArt(
            seed = entry.id,
            isVideo = entry.isVideo,
            modifier = Modifier.width(120.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(
                text = metaLine(entry.creator, entry.isVideo, entry.aiGenerated),
                style = MaterialTheme.typography.bodySmall,
                color = Ash,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val previewEntries = listOf(
    LibraryEntry("sample-2", "파리의 재즈", "creator_01", true, false, 0),
    LibraryEntry("sample-4", "짧은 영상 샘플", "creator_01", true, true, 0),
)

@Preview(showBackground = true, widthDp = 360, heightDp = 760)
@Composable
private fun LibraryScreenPreview() {
    AudioAppTheme {
        LibraryScreen(history = previewEntries, saved = previewEntries.take(1), onOpen = {}, onClearHistory = {}, onOpenBlocked = {})
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 600)
@Composable
private fun LibraryScreenEmptyPreview() {
    AudioAppTheme {
        LibraryScreen(history = emptyList(), saved = emptyList(), onOpen = {}, onClearHistory = {}, onOpenBlocked = {})
    }
}
