package com.kimyubin33.audioapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kimyubin33.audioapp.data.LibraryEntry
import com.kimyubin33.audioapp.ui.theme.Ash
import com.kimyubin33.audioapp.ui.theme.AudioAppTheme

/** 저장한 콘텐츠와 최근 감상 기록. 계정/설정과 개인 Moments는 상단 계정 메뉴로 모은다. */
@Composable
fun LibraryScreen(
    history: List<LibraryEntry>,
    saved: List<LibraryEntry>,
    onOpen: (String) -> Unit,
    onAccount: () -> Unit,
    modifier: Modifier = Modifier,
    account: AccountUiState = AccountUiState(loading=false),
) {
    var historyTab by rememberSaveable { mutableStateOf(false) }
    Box(modifier.fillMaxSize(), contentAlignment=Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max=760.dp).fillMaxSize(),contentPadding=PaddingValues(bottom=24.dp)) {
            item(key="header") {
                Row(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text("보관함",style=MaterialTheme.typography.headlineMedium,modifier=Modifier.weight(1f))
                    AccountBadge(account,onAccount)
                }
                InkDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilterChip(!historyTab,{historyTab=false},label={Text("Saved")},
                        colors=FilterChipDefaults.filterChipColors(selectedContainerColor=MaterialTheme.colorScheme.onSurface,selectedLabelColor=MaterialTheme.colorScheme.surface))
                    FilterChip(historyTab,{historyTab=true},label={Text("History")},
                        colors=FilterChipDefaults.filterChipColors(selectedContainerColor=MaterialTheme.colorScheme.onSurface,selectedLabelColor=MaterialTheme.colorScheme.surface))
                }
            }
            val entries=if(historyTab) history.take(20) else saved
            if(entries.isEmpty()) item { Text(if(historyTab) "기록이 없습니다." else "저장한 항목이 없습니다.",
                style=MaterialTheme.typography.bodyMedium,color=Ash,modifier=Modifier.padding(horizontal=24.dp,vertical=16.dp)) }
            else items(entries,key={it.id}) { LibraryRow(it,onOpen) }
        }
    }
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
        LibraryScreen(history = previewEntries, saved = previewEntries.take(1), onOpen = {}, onAccount = {})
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 600)
@Composable
private fun LibraryScreenEmptyPreview() {
    AudioAppTheme {
        LibraryScreen(history = emptyList(), saved = emptyList(), onOpen = {}, onAccount = {})
    }
}
