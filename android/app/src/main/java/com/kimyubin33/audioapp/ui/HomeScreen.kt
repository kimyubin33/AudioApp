package com.kimyubin33.audioapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kimyubin33.audioapp.R
import com.kimyubin33.audioapp.ui.theme.AudioAppTheme

/** 목록 한 줄에 필요한 정보만 담은 화면용 모델 (Rust·네트워크와 무관해서 Preview에서도 쓸 수 있다). */
data class HomeItem(
    val id: String,
    val title: String,
    val creator: String,
    val aiGenerated: Boolean,
    val isVideo: Boolean = false,
)

@Composable
fun HomeScreen(
    items: List<HomeItem>,
    loading: Boolean,
    error: String?,
    hasMore: Boolean,
    /** 곡을 열다 실패했을 때 등, 잠깐 알려줄 문구 */
    notice: String?,
    onOpen: (String) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenBlocked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 24.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onOpenBlocked) {
                    Text("차단 관리", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(if (notice == null) 32.dp else 8.dp))
        }
        if (notice != null) {
            item {
                Text(
                    text = notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(24.dp))
            }
        }

        if (items.isEmpty()) {
            // 목록이 비어 있을 때: 불러오는 중이거나, 실패했거나
            item {
                when {
                    error != null -> StatusMessage(message = error, actionLabel = "다시 시도", onAction = onRetry)
                    loading -> StatusMessage(message = "불러오는 중...")
                    else -> StatusMessage(message = "아직 올라온 곡이 없습니다.")
                }
            }
        } else {
            items(items, key = { it.id }) { item ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { onOpen(item.id) },
                ) {
                    Column(modifier = Modifier.padding(vertical = 24.dp)) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Light,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = listOfNotNull(
                                item.creator,
                                if (item.isVideo) "영상" else null,
                                if (item.aiGenerated) "AI 생성" else null,
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                }
            }
            // 목록 끝: 더 불러오는 중이거나, 실패했거나, 더 있거나
            item {
                when {
                    loading -> StatusMessage(message = "불러오는 중...")
                    error != null -> StatusMessage(message = error, actionLabel = "다시 시도", onAction = onRetry)
                    hasMore -> StatusMessage(message = null, actionLabel = "더 보기", onAction = onLoadMore)
                }
            }
        }
    }
}

@Composable
private fun StatusMessage(
    message: String?,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 24.dp)) {
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (actionLabel != null) {
            TextButton(onClick = onAction) {
                Text(actionLabel, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

private val previewItems = listOf(
    HomeItem("1", "샘플 오디오 하나", "creator_01", true),
    HomeItem("2", "파리의 재즈", "creator_01", true),
    HomeItem("3", "짧은 영상 샘플", "creator_01", true, isVideo = true),
)

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    AudioAppTheme {
        HomeScreen(
            items = previewItems, loading = false, error = null, hasMore = true, notice = null,
            onOpen = {}, onRetry = {}, onLoadMore = {}, onOpenBlocked = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenErrorPreview() {
    AudioAppTheme {
        HomeScreen(
            items = emptyList(), loading = false, error = "서버에 연결할 수 없습니다. 연결을 확인해 주세요.",
            hasMore = false, notice = null, onOpen = {}, onRetry = {}, onLoadMore = {}, onOpenBlocked = {},
        )
    }
}
