package com.kimyubin33.audioapp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import uniffi.audio_core.ReportReason

/** 신고 사유와 화면에 보일 이름 */
private val reasonLabels = listOf(
    ReportReason.SPAM to "스팸",
    ReportReason.INAPPROPRIATE to "부적절한 내용",
    ReportReason.COPYRIGHT to "저작권 침해",
    ReportReason.MISSING_AI_LABEL to "\"AI 생성\" 표시가 없거나 잘못됨",
    ReportReason.OTHER to "기타",
)

private const val DETAIL_MAX_CHARS = 500

/**
 * 신고 창. 사유를 고르고 (선택) 설명을 쓴다. 사유가 '기타'면 설명이 필요하다.
 * 입력 규칙은 Rust가 다시 검증하므로 여기서는 사용자가 알 수 있게 막아 주는 정도만 한다.
 */
@Composable
fun ReportDialog(
    submitting: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (ReportReason, String?) -> Unit,
) {
    var reason by remember { mutableStateOf<ReportReason?>(null) }
    var detail by remember { mutableStateOf("") }

    val trimmed = detail.trim()
    val canSubmit = !submitting && reason != null && (reason != ReportReason.OTHER || trimmed.isNotEmpty())

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("신고하기") },
        text = {
            Column {
                reasonLabels.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = reason == value, role = Role.RadioButton) { reason = value },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = reason == value,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(
                                selectedColor = MaterialTheme.colorScheme.primary,
                                unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = detail,
                    onValueChange = { if (it.length <= DETAIL_MAX_CHARS) detail = it },
                    label = { Text(if (reason == ReportReason.OTHER) "설명 (필수)" else "설명 (선택)") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(reason!!, trimmed.ifEmpty { null }) },
                enabled = canSubmit,
            ) { Text(if (submitting) "보내는 중..." else "신고") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !submitting) {
                Text("취소", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

/** 차단 확인 창. */
@Composable
fun BlockDialog(
    creator: String,
    submitting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("크리에이터를 차단할까요?") },
        text = {
            Text(
                text = "$creator 의 곡이 목록에서 사라집니다.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !submitting) {
                Text(if (submitting) "처리 중..." else "차단")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !submitting) {
                Text("취소", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}
