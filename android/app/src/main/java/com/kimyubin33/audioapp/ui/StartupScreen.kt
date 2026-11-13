package com.kimyubin33.audioapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun StartupScreen(failed: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 360.dp).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (failed) {
                Text("오디오에 연결하지 못했습니다.", style = MaterialTheme.typography.titleMedium)
                Text("잠시 후 다시 시도해 주세요.", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onRetry) { Text("다시 연결") }
            } else {
                CircularProgressIndicator()
                Text("오디오 연결 중", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
