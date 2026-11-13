package com.kimyubin33.audioapp.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import java.io.File
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kimyubin33.audioapp.data.creator.CreatorDraft
import com.kimyubin33.audioapp.ui.theme.AudioAppTheme
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun StreamerScreen(viewModel: CreatorViewModel, onBack: () -> Unit, onLogin: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var policyTarget by remember { mutableStateOf<String?>(null) }
    var accepting by remember { mutableStateOf(false) }
    policyTarget?.let { id ->
        PolicyDialog("creator", onDismiss={policyTarget=null}, onAccept={version->
            policyTarget=null
            if(!accepting) {
                accepting=true
                scope.launch {
                    try {
                        com.kimyubin33.audioapp.data.AccountActions.withLogin(context) { it.acceptCreatorPolicy(version) }
                        viewModel.submit(id)
                    } catch(e:kotlinx.coroutines.CancellationException){throw e}
                    catch(_:Exception){android.widget.Toast.makeText(context,"약관 동의를 저장하지 못했습니다. 로그인 상태를 확인해 주세요.",android.widget.Toast.LENGTH_LONG).show()}
                    finally{accepting=false}
                }
            }
        })
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), viewModel::import)
    var photoTarget by rememberSaveable { mutableStateOf<String?>(null) }
    val photoPicker=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()){uri->
        val id=photoTarget;photoTarget=null;if(uri!=null&&id!=null)viewModel.addPostPhoto(id,uri)
    }
    LaunchedEffect(Unit) { viewModel.refresh() }
    StreamerContent(state, onBack, onLogin, onAdd = {
        picker.launch(arrayOf("audio/*", "application/zip", "application/x-zip-compressed"))
    }, onSelect = viewModel::select, onChange = viewModel::change, onRemove = viewModel::remove,
        onSubmit = { id ->
            if (com.kimyubin33.audioapp.BuildConfig.DEBUG && com.kimyubin33.audioapp.BuildConfig.DEV_LOGIN) viewModel.submit(id)
            else if(!accepting) policyTarget=id
        }, onRefresh = { viewModel.refresh() }, onMore = { viewModel.refresh(true) },
        onEditRemote = { id -> state.contents.find { it.id == id }?.let(viewModel::editRemote) },
        onCancel = viewModel::cancel, fileUri = { d -> viewModel.file(d)?.let(Uri::fromFile) },
        onNewPost={viewModel.newPost()},onPhoto={id->photoTarget=id;photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))},
        onRemovePhoto=viewModel::removePostPhoto,onFindAudio=viewModel::findAudio)
}

@Composable
private fun StreamerContent(
    state: CreatorUiState,
    onBack: () -> Unit,
    onLogin: () -> Unit,
    onAdd: () -> Unit,
    onSelect: (String?) -> Unit,
    onChange: (String, (CreatorDraft) -> CreatorDraft) -> Unit,
    onRemove: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onRefresh: () -> Unit,
    onMore: () -> Unit,
    onEditRemote: (String) -> Unit,
    onCancel: () -> Unit,
    fileUri: (CreatorDraft) -> Uri?,
    onNewPost: ()->Unit = {},
    onPhoto: (String)->Unit = {},
    onRemovePhoto: (String)->Unit = {},
    onFindAudio: (String)->Unit = {},
) {
    val draft = state.drafts.find { it.id == state.selectedId && !it.isVideo && !it.isPost }
    var submittedTab by rememberSaveable { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (draft == null) onBack() else onSelect(null) }) { Text(if (draft == null) "닫기" else "초안 목록") }
            Spacer(Modifier.weight(1f))
            Text("스트리머 모드", style = MaterialTheme.typography.titleMedium)
        }
        InkDivider()
        if (state.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(state.stage, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onCancel) { Text("중지") }
            }
        }
        state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
        if (draft != null && state.profile == null && !state.refreshing) {
            TextButton(onClick = onLogin, enabled = !state.busy, modifier = Modifier.padding(horizontal = 20.dp)) { Text("Google로 로그인") }
        }
        if (draft == null) {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            when {
                                state.profile?.canUpload == true -> Text("크리에이터 · ${state.profile.creatorId}", style = MaterialTheme.typography.labelLarge)
                                state.profile != null -> {
                                    Text("초안은 만들 수 있습니다. 제출은 크리에이터 승인 후 가능합니다.")
                                    SelectionContainer { Text("내 계정 ID: ${state.profile.userId}", style = MaterialTheme.typography.bodySmall) }
                                }
                                else -> {
                                    TextButton(onClick = onLogin, enabled = !state.busy) { Text("Google로 로그인") }
                                }
                            }
                            Button(onClick = onAdd, enabled = state.restored && !state.busy, modifier = Modifier.fillMaxWidth()) { Text("오디오 · ZIP 추가") }
                            Text("MP3·M4A · 오디오당 90MiB · 초안 20개/360MiB",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (state.importNotices.isNotEmpty()) item {
                    OutlinedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("파일 검사 결과", style = MaterialTheme.typography.titleSmall)
                        state.importNotices.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    } }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(!submittedTab, { submittedTab = false }, label = { Text("초안 ${state.drafts.count { !it.isVideo && !it.isPost }}") }, colors = creatorChipColors())
                        Spacer(Modifier.width(8.dp))
                        FilterChip(submittedTab, { submittedTab = true }, label = { Text("제출 목록") }, colors = creatorChipColors())
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onRefresh, enabled = !state.refreshing && !state.busy) { Text("새로고침") }
                    }
                }
                if (!submittedTab) {
                    if (state.drafts.none { !it.isVideo && !it.isPost }) item { Text("초안이 없습니다.", style = MaterialTheme.typography.bodyMedium) }
                    items(state.drafts.filterNot { it.isVideo || it.isPost }, key = { it.id }) { d ->
                        OutlinedCard(onClick = { onSelect(d.id) }, enabled = !state.busy) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(d.title.ifBlank { d.name.ifBlank { "New Post" } }, style = MaterialTheme.typography.titleMedium)
                                Text(if(d.isPost) "Post" else "${if (d.isVideo) d.shortStyle.replaceFirstChar{it.uppercase()} .ifBlank { "쇼츠" } else "오디오"} · ${formatBytes(d.bytes)} · ${formatDuration(d.durationSeconds)}", style = MaterialTheme.typography.bodySmall)
                                Text(when { d.submittedId != null -> "검수 요청 완료 · 제출 목록에서 상태 확인"
                                    d.uploadId != null -> "전송 결과 확인 필요 · 다시 시도 가능"
                                    d.remoteId != null -> "서버 정보 수정 중 · v${d.revision}"
                                    else -> "내 기기에만 저장된 초안" }, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                } else {
                    if (state.contents.isEmpty()) item { Text(if (state.refreshing) "불러오는 중…" else "아직 제출한 콘텐츠가 없습니다.") }
                    items(state.contents.filter { it.kind == "audio" }, key = { it.id }) { c ->
                        OutlinedCard { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(c.title, style = MaterialTheme.typography.titleMedium)
                            Text("${statusLabel(c.status)} · v${c.revision}", style = MaterialTheme.typography.labelLarge)
                            c.rejectReason?.let { Text("검수 사유: $it", style = MaterialTheme.typography.bodyMedium) }
                            Text(c.id, style = MaterialTheme.typography.bodySmall)
                            if (c.status in setOf("pending", "rejected") && c.submissionJson != null) {
                                TextButton(onClick = { onEditRemote(c.id) }, enabled = !state.busy && state.profile?.canUpload == true) { Text("정보 수정·재검수") }
                            }
                        } }
                    }
                    if (state.nextCursor != null) item { TextButton(onClick = onMore, enabled = !state.refreshing) { Text("더 보기") } }
                }
            }
        } else {
            DraftEditor(draft, editable = !state.busy && draft.uploadId == null && !(draft.textPost&&draft.uploadOwner!=null) && draft.submittedId == null,
                canSubmit = !state.busy && state.profile?.canUpload == true && draft.submittedId == null,
                uri = fileUri(draft), onChange = { transform -> onChange(draft.id, transform) },
                onSubmit = { onSubmit(draft.id) }, onRemove = { deleteTarget = draft.id },
                onPhoto={onPhoto(draft.id)},onRemovePhoto={onRemovePhoto(draft.id)},audioChoices=state.audioChoices,
                audioLoading=state.audioLoading,onFindAudio=onFindAudio)
        }
    }
    deleteTarget?.let { id ->
        AlertDialog(onDismissRequest = { deleteTarget = null }, title = { Text("기기의 초안을 지울까요?") },
            text = { Text("기기에 복사한 파일과 입력 내용이 삭제됩니다. 이미 제출한 서버 콘텐츠는 제출 목록에 유지됩니다.") },
            confirmButton = { TextButton(onClick = { deleteTarget = null; onRemove(id) }) { Text("초안 삭제") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("유지") } })
    }
}

@Composable
private fun DraftEditor(
    draft: CreatorDraft,
    editable: Boolean,
    canSubmit: Boolean,
    uri: Uri?,
    onChange: ((CreatorDraft) -> CreatorDraft) -> Unit,
    onSubmit: () -> Unit,
    onRemove: () -> Unit,
    onPhoto: ()->Unit,
    onRemovePhoto: ()->Unit,
    audioChoices: List<HomeItem>,
    audioLoading: Boolean,
    onFindAudio: (String)->Unit,
) {
    var step by rememberSaveable(draft.id) { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf(false) }
    val steps = listOf(if(draft.isPost) "내용 확인" else "파일 확인", "콘텐츠 정보", "권리 확인", "장소 연결", "최종 검토")
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text("${step + 1} / 5  ·  ${steps[step]}", style = MaterialTheme.typography.labelLarge)
            LinearProgressIndicator(progress = { (step + 1) / 5f }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Text(steps[step], style = MaterialTheme.typography.headlineSmall)
                if (draft.uploadId != null && draft.submittedId == null) Text("전송한 파일의 정보는 잠겨 있습니다. 최종 검토에서 다시 시도하면 서버 상태를 확인합니다.", style = MaterialTheme.typography.bodySmall)
                if (draft.submittedId != null) Text("검수 요청 완료 · ${draft.submittedId}", style = MaterialTheme.typography.bodyMedium)
            }
            when (step) {
                0 -> {
                    item { FileSummary(draft) }
                    if(draft.isPost) {
                        if(uri!=null) item { MemoryPhoto(File(requireNotNull(uri.path)),Modifier.fillMaxWidth().aspectRatio(4f/3f),draft.title) }
                        if(draft.remoteId==null) item {
                            Row { TextButton(onClick=onPhoto,enabled=editable){Text(if(uri==null) "Add Photo" else "Change Photo")}
                                if(uri!=null) TextButton(onClick=onRemovePhoto,enabled=editable){Text("사진 제거")} }
                            Text("JPG·PNG·WebP · 10MB · 위치 정보 제거",style=MaterialTheme.typography.bodySmall)
                        }
                        item { Text(draft.description.ifBlank{"다음 단계에서 글을 작성해 주세요."}) }
                    } else if (uri != null) item { CreatorPreview(uri, draft.isVideo) }
                    else item { Text("서버에 제출한 원본을 유지하는 정보 수정입니다. 파일 교체는 새 초안으로 제출해 주세요.") }
                    item { CheckLine(if(draft.isPost) "공유할 사진과 글을 확인했습니다." else "미리 재생해 확인했고, 이 파일이 올릴 최종 결과물입니다.", draft.previewConfirmed, editable) { value -> onChange { it.copy(previewConfirmed = value) } } }
                }
                1 -> {
                    item { Field("제목 *", draft.title, 100, editable) { v -> onChange { it.copy(title = v) } } }
                    item { Field("설명", draft.description, 2000, editable, multiline = true) { v -> onChange { it.copy(description = v,previewConfirmed=if(it.isPost) false else it.previewConfirmed) } } }
                    item { Field("태그 · 쉼표로 구분 (최대 10개, 각 30자)", draft.tags, 320, editable) { v -> onChange { it.copy(tags = v) } } }
                    if(draft.isVideo) item {
                        Text("쇼츠",style=MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            listOf("story" to "Story","moment" to "Moment").forEach{(value,label)->
                                FilterChip(draft.shortStyle==value,{onChange{it.copy(shortStyle=value)}},label={Text(label)},enabled=editable,colors=creatorChipColors())
                            }
                        }
                        Text(if(draft.shortStyle=="moment") "일상·여행·장소의 추억을 편집한 영상" else "드라마·상황극·연출한 이야기",style=MaterialTheme.typography.bodySmall)
                    }
                    item { CheckLine("AI 생성",draft.aiGenerated,editable){value->onChange{it.copy(aiGenerated=value,aiTool=if(value) it.aiTool else "",aiTermsConfirmed=false)}} }
                    if(draft.aiGenerated) item { Field("사용한 AI 도구 *", draft.aiTool, 50, editable) { v -> onChange { it.copy(aiTool = v, aiTermsConfirmed = false) } } }
                    if(draft.isPost) item {
                        var query by rememberSaveable(draft.id){mutableStateOf("")}
                        var expanded by rememberSaveable(draft.id){mutableStateOf(false)}
                        TextButton(onClick={expanded=!expanded;if(expanded)onFindAudio("")},enabled=editable){Text(if(draft.audioContentId.isBlank()) "오디오 연결 · 선택" else draft.audioTitle.ifBlank{"연결한 오디오"})}
                        if(draft.audioContentId.isNotBlank()) TextButton(onClick={onChange{it.copy(audioContentId="",audioTitle="")}},enabled=editable){Text("오디오 연결 해제")}
                        if(expanded) {
                            OutlinedTextField(query,{query=it},label={Text("오디오 검색")},singleLine=true,enabled=editable,modifier=Modifier.fillMaxWidth())
                            TextButton(onClick={onFindAudio(query)},enabled=editable&&!audioLoading){Text("검색")}
                            if(audioLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                            audioChoices.forEach{audio->TextButton(onClick={onChange{it.copy(audioContentId=audio.id,audioTitle=audio.title)};expanded=false},enabled=editable){Text(audio.title)}}
                        }
                    }
                }
                2 -> {
                    item { Text("보유 권한을 구체적으로 적어 주세요. 체크만으로 저작권이 인증되는 것은 아니며, 운영자가 검수합니다.") }
                    item { Field("권리자 / 업로드 권한자 *", draft.holder, 100, editable) { v -> onChange { it.copy(holder = v, rightsConfirmed = false) } } }
                    item {
                        Column {
                            listOf("original" to "직접 제작 · 사용 요소의 권리 보유", "licensed" to "이용 허락 · 라이선스 보유", "public_domain" to "퍼블릭 도메인 근거 확인").forEach { (id, label) ->
                                Row(Modifier.fillMaxWidth().selectable(draft.license == id, enabled = editable, role = Role.RadioButton,
                                    onClick = { onChange { it.copy(license = id, rightsConfirmed = false) } }), verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = draft.license == id, onClick = null, enabled = editable)
                                    Text(label, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                    item { Field("권리 근거·라이선스·출처·크레딧", draft.attribution, 1000, editable, multiline = true) { v -> onChange { it.copy(attribution = v, rightsConfirmed = false) } } }
                    item { Field("근거 링크 (선택 · HTTPS)", draft.sourceUrl, 2048, editable, keyboard = KeyboardType.Uri) { v -> onChange { it.copy(sourceUrl = v, rightsConfirmed = false) } } }
                    item { CheckLine("음원·영상·샘플·이미지 등 포함된 요소를 이 플랫폼에서 공유·재생할 권한이 있습니다.", draft.rightsConfirmed, editable) { v -> onChange { it.copy(rightsConfirmed = v) } } }
                    item { CheckLine("타인의 얼굴·이름·음성·캐릭터가 포함된 경우 필요한 이용 동의를 받았습니다.", draft.personConsentConfirmed, editable) { v -> onChange { it.copy(personConsentConfirmed = v) } } }
                    item { CheckLine("사용한 도구·모델·소재의 이용 약관과 배포 조건을 확인했습니다.", draft.aiTermsConfirmed, editable) { v -> onChange { it.copy(aiTermsConfirmed = v) } } }
                }
                3 -> {
                    item { CheckLine("공개 장소에 이 콘텐츠와 캐릭터·이야기를 연결하기", draft.placeEnabled, editable) { v -> onChange { it.copy(placeEnabled = v, placeConfirmed = false) } } }
                    item { Text("연결하지 않아도 오디오를 공유할 수 있습니다. 작품 속 장소를 입력하는 항목이며 현재 위치를 추적하지 않습니다.", style = MaterialTheme.typography.bodySmall) }
                    if (draft.placeEnabled) {
                        item { Field("공개 장소 이름 *", draft.placeName, 100, editable) { v -> onChange { it.copy(placeName = v, placeConfirmed = false) } } }
                        item { Field("위도 (-90 ~ 90)", draft.latitude, 24, editable, keyboard = KeyboardType.Text) { v -> onChange { it.copy(latitude = v, placeConfirmed = false) } } }
                        item { Field("경도 (-180 ~ 180)", draft.longitude, 24, editable, keyboard = KeyboardType.Text) { v -> onChange { it.copy(longitude = v, placeConfirmed = false) } } }
                        item { Field("연결 반경 · m (50 ~ 5,000)", draft.radius, 4, editable, keyboard = KeyboardType.Number) { v -> onChange { it.copy(radius = v) } } }
                        item { Field("지역 캐릭터 이름 (선택)", draft.character, 100, editable) { v -> onChange { it.copy(character = v) } } }
                        item { Field("이 장소와 연결되는 이야기 (선택)", draft.story, 2000, editable, multiline = true) { v -> onChange { it.copy(story = v) } } }
                        item { CheckLine("사적 주소나 민감한 장소가 아니며, 입력한 장소·좌표를 콘텐츠와 함께 공개해도 됩니다.", draft.placeConfirmed, editable) { v -> onChange { it.copy(placeConfirmed = v) } } }
                    }
                }
                else -> {
                    item { FileSummary(draft) }
                    item { ReviewSummary(draft) }
                    if(draft.isPost) item { CheckLine("공개할 사진과 글을 확인했습니다.",draft.previewConfirmed,editable){v->onChange{it.copy(previewConfirmed=v)}} }
                    item { Text("제출 즉시 공개되지 않습니다. 파일·AI 표시·권리·장소 정보를 검수한 뒤 공개되며, 반려 사유는 제출 목록에 표시됩니다.") }
                    item { Button(onClick = { confirm = true }, enabled = canSubmit, modifier = Modifier.fillMaxWidth()) { Text(if (draft.remoteId != null) "수정 후 재검수 요청" else if (draft.uploadId != null) "전송 결과 확인·다시 시도" else "검수 요청") } }
                    if (!canSubmit && draft.submittedId == null) item { Text("로그인 및 크리에이터 승인 상태를 확인해 주세요.", style = MaterialTheme.typography.bodySmall) }
                }
            }
            item { TextButton(onClick = onRemove, enabled = editable || draft.submittedId != null) { Text("이 기기의 초안 삭제") } }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { step-- }, enabled = step > 0, modifier = Modifier.weight(1f)) { Text("이전") }
            Button(onClick = { step++ }, enabled = step < 4, modifier = Modifier.weight(1f)) { Text("다음") }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("이 내용으로 검수를 요청할까요?") },
        text = { Text("${draft.title}\n${if(draft.isPost) "Post" else draft.name}\n${if(draft.aiGenerated) "AI 생성 · "+draft.aiTool else "직접 기록·편집한 콘텐츠"}\n\n제출 후 파일을 바꿀 수 없습니다. 공개는 운영자의 승인 후 진행됩니다.") },
        confirmButton = { TextButton(onClick = { confirm = false; onSubmit() }) { Text("검수 요청") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("계속 수정") } })
}

@Composable
private fun Field(label: String, value: String, max: Int, enabled: Boolean, multiline: Boolean = false,
                  keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    OutlinedTextField(value, onValueChange = { if (it.codePointCount(0, it.length) <= max) onChange(it) },
        modifier = Modifier.fillMaxWidth(), label = { Text(label) }, enabled = enabled,
        singleLine = !multiline, minLines = if (multiline) 3 else 1,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        supportingText = { Text("${value.codePointCount(0, value.length)} / $max") })
}

@Composable
private fun CheckLine(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange), verticalAlignment = Alignment.Top) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(top = 12.dp))
    }
}

@Composable
private fun FileSummary(d: CreatorDraft) {
    OutlinedCard { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(d.name.ifBlank { "Text Post" }, style = MaterialTheme.typography.titleMedium)
        if(d.textPost) { Text("사진 없이 글로 공유합니다."); return@Column }
        Text(d.source, style = MaterialTheme.typography.bodySmall)
        Text("${if(d.isPost) "Post" else if(d.isVideo) d.shortStyle.ifBlank{"쇼츠"} else "오디오"} · ${d.contentType}")
        Text("${formatBytes(d.bytes)} · ${d.bytes} bytes · ${formatDuration(d.durationSeconds)}")
        if (d.isVideo && d.width > 0) Text("${d.width} × ${d.height}px · 원본 해상도")
        Text("SHA-256 · 전송 전후 원본 대조", style = MaterialTheme.typography.labelMedium)
        SelectionContainer { Text(d.sha256, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
    } }
}

@Composable
private fun ReviewSummary(d: CreatorDraft) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(d.title, style = MaterialTheme.typography.titleLarge)
        Text(d.description.ifBlank { "설명 없음" })
        Text("태그: ${d.tags.ifBlank { "없음" }}\nAI 도구: ${d.aiTool.ifBlank { "입력 필요" }}")
        Text("권리자: ${d.holder.ifBlank { "입력 필요" }}\n권리 유형: ${licenseLabel(d.license)}")
        if (d.attribution.isNotBlank()) Text(d.attribution)
        if (d.sourceUrl.isNotBlank()) Text(d.sourceUrl)
        Text("권리 확인: ${if (d.rightsConfirmed && d.personConsentConfirmed && d.aiTermsConfirmed) "완료" else "미완료"}\n최종 파일 확인: ${if (d.previewConfirmed) "완료" else "미완료"}")
        Text(if (d.placeEnabled) "장소: ${d.placeName}\n좌표: ${d.latitude}, ${d.longitude} · ${d.radius}m\n캐릭터: ${d.character.ifBlank { "없음" }}\n${d.story}" else "장소 연결 없음")
    }
}

@Composable
private fun creatorChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.onSurface,
    selectedLabelColor = MaterialTheme.colorScheme.surface,
)

private fun formatBytes(bytes: Long) = String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0))
private fun formatDuration(seconds: Int) = "%d:%02d".format(seconds / 60, seconds % 60)
private fun statusLabel(status: String) = when (status) { "pending" -> "검수 대기"; "approved" -> "공개 중"; "rejected" -> "반려"; "removed" -> "공개 중단"; else -> status }
private fun licenseLabel(license: String) = when (license) { "original" -> "직접 제작·권리 보유"; "licensed" -> "이용 허락·라이선스"; else -> "퍼블릭 도메인" }

@Preview(showBackground = true, widthDp = 390, heightDp = 820)
@Composable
private fun StreamerScreenPreview() {
    AudioAppTheme { StreamerContent(CreatorUiState(restored = true), {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, { null }) }
}
