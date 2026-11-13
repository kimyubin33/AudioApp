package com.kimyubin33.audioapp.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kimyubin33.audioapp.data.PublishedPolicy
import kotlinx.coroutines.CancellationException

/** Browse is always accessible. Consent is never inferred from opening a URL or dismissing this dialog. */
@Composable
fun PolicyDialog(mode: String, onDismiss: () -> Unit, onAccept: (String) -> Unit = {}) {
    val context = LocalContext.current
    var retry by remember { mutableIntStateOf(0) }
    var accepted by rememberSaveable(mode) { mutableStateOf(false) }
    val result by produceState<Result<PublishedPolicy>?>(null, retry) {
        value = null
        try { value = Result.success(PublishedPolicy.load(context)) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { value = Result.failure(e) }
    }
    val policy = result?.getOrNull()
    LaunchedEffect(policy?.version) { accepted = false }
    fun open(url: String) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: android.content.ActivityNotFoundException) { Toast.makeText(context,"문서를 열 수 있는 앱이 없습니다.",Toast.LENGTH_SHORT).show() }
    }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (mode == "creator") "업로드 이용약관" else "약관 및 개인정보") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (result == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else if (policy == null) {
                    Text("문서를 불러오지 못했습니다.")
                    TextButton(onClick = { retry++ }) { Text("다시 시도") }
                } else {
                    policy.supportEmail?.let { email -> androidx.compose.foundation.text.selection.SelectionContainer { Text("운영 문의: $email") } }
                    policy.terms?.let { TextButton(onClick = { open(it) }) { Text("서비스 이용약관") } }
                    policy.privacy?.let { TextButton(onClick = { open(it) }) { Text("개인정보처리방침") } }
                    policy.creatorTerms?.let { TextButton(onClick = { open(it) }) { Text("크리에이터 이용약관") } }
                    if (!policy.ready) Text("운영 약관이 아직 준비되지 않았습니다. 준비 후 가입·업로드를 이용할 수 있습니다.")
                    else if (mode != "browse") {
                        Text(if (mode == "creator") "업로드한 콘텐츠의 이용허락과 금지 콘텐츠 기준을 확인해 주세요."
                            else "서비스 이용약관을 확인해 주세요. 개인정보 처리 내용은 개인정보처리방침에서 확인할 수 있습니다.")
                        Row(Modifier.fillMaxWidth().toggleable(accepted, role = Role.Checkbox, onValueChange = { accepted = it }).padding(vertical = 8.dp)) {
                            Checkbox(accepted, onCheckedChange = null)
                            Text(if (mode == "creator") "크리에이터 이용약관에 동의합니다." else "서비스 이용약관에 동의합니다.",
                                Modifier.weight(1f).padding(top = 12.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (mode == "browse") TextButton(onClick = onDismiss) { Text("닫기") }
            else TextButton(enabled = policy?.ready == true && accepted,
                onClick = { policy?.version?.let(onAccept) }) { Text("동의하고 계속") }
        },
        dismissButton = { if (mode != "browse") TextButton(onClick = onDismiss) { Text("취소") } })
}
