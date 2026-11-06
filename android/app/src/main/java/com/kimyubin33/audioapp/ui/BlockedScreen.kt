package com.kimyubin33.audioapp.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kimyubin33.audioapp.ui.theme.AudioAppTheme

/**
 * 차단한 크리에이터 목록. 여기서 차단을 풀 수 있다.
 * @param creators null이면 아직 불러오는 중
 */
@Composable
fun BlockedScreen(
    creators: List<String>?,
    error: String?,
    onRetry: () -> Unit,
    onUnblock: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 24.dp),
    ) {
        item {
            Text(
                text = "차단 관리",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Light,
            )
            Spacer(Modifier.height(32.dp))
        }
        when {
            error != null -> item {
                Column {
                    Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onRetry) { Text("다시 시도", color = MaterialTheme.colorScheme.onSurface) }
                }
            }

            creators == null -> item {
                Text("불러오는 중...", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            creators.isEmpty() -> item {
                Text("차단한 크리에이터가 없습니다.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            else -> items(creators, key = { it }) { creator ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(creator, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Light)
                        TextButton(onClick = { onUnblock(creator) }) {
                            Text("해제", color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun BlockedScreenPreview() {
    AudioAppTheme {
        BlockedScreen(creators = listOf("creator_02", "creator_07"), error = null, onRetry = {}, onUnblock = {})
    }
}
