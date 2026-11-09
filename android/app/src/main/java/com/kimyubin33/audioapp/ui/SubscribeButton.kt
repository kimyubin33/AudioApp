package com.kimyubin33.audioapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kimyubin33.audioapp.ui.theme.AudioAppTheme
import com.kimyubin33.audioapp.ui.theme.Ink
import com.kimyubin33.audioapp.ui.theme.Paper

/**
 * 구독 버튼. 구독 전에는 먹으로 채운 버튼, 구독 중에는 테두리만 있는 버튼.
 * @param onDark 영상 위처럼 어두운 바탕에 놓을 때 true (색을 뒤집는다)
 */
@Composable
fun SubscribeButton(subscribed: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, onDark: Boolean = false) {
    val ink = if (onDark) Paper else Ink
    val onInk = if (onDark) Ink else Paper
    val shape = RoundedCornerShape(3.dp)
    val base = if (subscribed) modifier.border(1.dp, ink.copy(alpha = 0.7f), shape) else modifier.background(ink, shape)
    Text(
        text = if (subscribed) "구독 중" else "구독",
        style = MaterialTheme.typography.labelLarge,
        color = if (subscribed) ink else onInk,
        modifier = base
            .clickable(role = Role.Button, onClickLabel = if (subscribed) "구독 해제" else "구독", onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

@Preview(showBackground = true)
@Composable
private fun SubscribeButtonPreview() {
    AudioAppTheme {
        androidx.compose.foundation.layout.Row(Modifier.padding(16.dp)) {
            SubscribeButton(subscribed = false, onClick = {})
            androidx.compose.foundation.layout.Spacer(Modifier.padding(8.dp))
            SubscribeButton(subscribed = true, onClick = {})
        }
    }
}
