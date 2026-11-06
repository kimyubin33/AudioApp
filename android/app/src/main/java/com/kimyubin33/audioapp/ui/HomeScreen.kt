package com.kimyubin33.audioapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.Composable
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
)

@Composable
fun HomeScreen(
    items: List<HomeItem>,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 24.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(40.dp))
        }
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
                        text = if (item.aiGenerated) "${item.creator} · AI 생성" else item.creator,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    AudioAppTheme {
        HomeScreen(
            items = listOf(
                HomeItem("1", "샘플 오디오 하나", "creator_01", true),
                HomeItem("2", "파리의 재즈", "creator_01", true),
            ),
            onOpen = {},
        )
    }
}
