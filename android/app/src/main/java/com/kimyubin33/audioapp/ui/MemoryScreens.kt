package com.kimyubin33.audioapp.ui

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import android.content.ClipData
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kimyubin33.audioapp.data.memory.*
import com.kimyubin33.audioapp.ui.theme.AudioAppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MemoryEditorScreen(viewModel: MemoryViewModel,onBack: () -> Unit,onSave: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(viewModel::importPhoto) }
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle,viewModel) {
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_STOP) viewModel.flush() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); viewModel.flush() }
    }
    MemoryEditorContent(state,photo=state.album.draft.photoId?.let(viewModel::photo),onBack=onBack,
        onPhoto={picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))},
        onEdit=viewModel::edit,onApply=viewModel::applyCandidate,onUndo=viewModel::undoCandidate,onSave=onSave,onRetry=viewModel::reload,
        onRetrySave=viewModel::flush)
}

@Composable
internal fun MemoryEditorContent(
    state: MemoryUiState,photo: File?,onBack: () -> Unit,onPhoto: () -> Unit,
    onEdit: ((MemoryDraft) -> MemoryDraft) -> Unit,onApply: () -> Unit,onUndo: () -> Unit,
    onSave: () -> Unit,onRetry: () -> Unit,onRetrySave: () -> Unit,
) {
    val draft=state.album.draft
    var decorate by rememberSaveable { mutableStateOf(false) }
    var template by rememberSaveable { mutableIntStateOf(0) }
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    fun finishTyping() { focus.clearFocus(); keyboard?.hide() }
    val clipboard=LocalClipboard.current
    val copyScope=rememberCoroutineScope()
    val context=LocalContext.current
    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.TopCenter) {
        Column(Modifier.widthIn(max=720.dp).fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(bottom=24.dp),
            verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick=onBack){Text("닫기")}
                    Text("Moment",style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
                    Text(if(state.saving) "저장 중…" else if(state.loaded && state.error==null) "초안 자동 저장" else "",style=MaterialTheme.typography.labelSmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                InkDivider()
            }
            if(!state.loaded) item {
                Column(Modifier.padding(24.dp)) {
                    Text(state.error ?: "앨범을 여는 중…")
                    if(state.error!=null) TextButton(onClick=onRetry,enabled=!state.busy){Text("다시 시도")}
                }
            } else {
                item {
                    Column(Modifier.padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        if(draft.photoId!=null) MemoryPhoto(photo,Modifier.fillMaxWidth().aspectRatio(4f/3f).clip(RoundedCornerShape(16.dp)),draft.title)
                        OutlinedButton(onClick=onPhoto,enabled=!state.busy,modifier=Modifier.fillMaxWidth()) {
                            AlbumIcon(AlbumSymbol.Camera,Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                            Text(if(draft.photoId==null) "Add Photo" else "Change Photo")
                        }
                        if(draft.photoId!=null) TextButton(onClick={onEdit { it.copy(photoId=null) }},enabled=!state.busy){Text("사진 빼기")}
                        if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("JPG·PNG·WebP · 10MB · 위치 정보 제거",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item {
                    Column(Modifier.padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(draft.title,{v->onEdit{it.copy(title=v)}},label={Text("추억의 제목")},singleLine=true,
                            supportingText={Text("${draft.title.length}/80")},enabled=!state.busy,modifier=Modifier.fillMaxWidth())
                        OutlinedTextField(draft.body,{v->onEdit{it.copy(body=v)}},label={Text("그날의 이야기")},minLines=4,
                            supportingText={Text("${draft.body.length}/2000")},enabled=!state.busy,modifier=Modifier.fillMaxWidth())
                        OutlinedTextField(draft.place,{v->onEdit{it.copy(place=v)}},label={Text("장소 이름 · 선택")},singleLine=true,
                            leadingIcon={AlbumIcon(AlbumSymbol.Pin,Modifier.size(20.dp))},
                            enabled=!state.busy,modifier=Modifier.fillMaxWidth())
                        OutlinedButton(onClick={decorate=!decorate},modifier=Modifier.fillMaxWidth(),enabled=!state.busy) {
                            AlbumIcon(AlbumSymbol.Edit,Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if(decorate) "꾸며보기 접기" else "AI로 꾸며보기 · 선택")
                        }
                    }
                }
                if(decorate) item {
                    OutlinedCard(Modifier.fillMaxWidth().padding(horizontal=24.dp),shape=RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            Text("프롬프트 복사 → 외부 AI 실행 → 결과 붙여넣기",
                                style=MaterialTheme.typography.bodySmall)
                            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                                listOf("그날의 분위기","20초 목소리","15초 쇼츠").forEachIndexed { i,label ->
                                    FilterChip(selected=template==i,onClick={template=i},label={Text(label)})
                                }
                            }
                            val prompt=memoryPrompt(template,draft.place,draft.body)
                            Text(prompt,style=MaterialTheme.typography.bodySmall)
                            Text("외부 도구에 보낼 얼굴·목소리·주소·번호판과 이용 권한을 먼저 확인하세요.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(onClick={copyScope.launch {
                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("추억 꾸미기",prompt)))
                                Toast.makeText(context,"프롬프트를 복사했습니다.",Toast.LENGTH_SHORT).show()
                            }}){Text("프롬프트 복사")}
                            Text("현재 글",style=MaterialTheme.typography.labelLarge)
                            Text(draft.body.ifBlank{"아직 적은 글이 없어요."},style=MaterialTheme.typography.bodyMedium)
                            OutlinedTextField(draft.candidate,{v->onEdit{it.copy(candidate=v)}},label={Text("가져온 글 · 비교용 후보")},minLines=3,
                                enabled=!state.busy,modifier=Modifier.fillMaxWidth(),supportingText={Text("${draft.candidate.length}/2000")},
                                keyboardOptions=KeyboardOptions(imeAction=ImeAction.Done),keyboardActions=KeyboardActions(onDone={finishTyping()}))
                            Button(onClick={finishTyping();onApply()},enabled=draft.candidate.isNotBlank()&&!state.busy){Text("이 버전 사용")}
                            TextButton(onClick={finishTyping();onUndo()},enabled=draft.previousBody!=null&&!state.busy){Text("이전 글로 되돌리기")}
                        }
                    }
                }
                if(draft.previousBody!=null && !decorate) item(key="undo") {
                    TextButton(onClick={finishTyping();onUndo()},enabled=!state.busy,modifier=Modifier.padding(horizontal=24.dp)){Text("이전 글로 되돌리기")}
                }
                item {
                    Column(Modifier.padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                            AlbumIcon(AlbumSymbol.Lock,Modifier.size(18.dp))
                            Text("나만 보기",style=MaterialTheme.typography.labelLarge)
                        }
                        Text("기기에만 저장 · 앱 삭제 시 함께 삭제",
                            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(state.error!=null) {
                            Text(state.error,color=MaterialTheme.colorScheme.error)
                            TextButton(onClick=onRetrySave,enabled=!state.busy){Text("초안 저장 다시 시도")}
                        }
                    }
                }
            }
        }
        if(state.loaded) {
            InkDivider()
            Button(onClick={finishTyping();onSave()},enabled=draft.canSave&&!state.busy,
                modifier=Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=12.dp).heightIn(min=52.dp)) {
                Text("Save Moment")
            }
        }
        }
    }
}

/** 외부 서비스 호출은 하지 않는다. 현재 기억의 사실을 유지하도록 명시한 복사 전용 템플릿. */
internal fun memoryPrompt(template: Int,place: String,body: String): String {
    val facts="장소: ${place.ifBlank { "[장소]" }}\n기억: ${body.ifBlank { "[내가 본 장면과 그때 감정]" }}"
    val request=when(template) {
        1 -> "한국어 20초 낭독문으로 다듬어 주세요. 직접 고쳐 읽을 수 있게 짧은 문장으로 써 주세요."
        2 -> "세로 9:16, 15초 영상 구성을 도입 3초·장면 9초·여운 3초로 나누고 짧은 자막과 느린 화면 이동을 제안해 주세요. 인물의 얼굴과 실제 장면은 바꾸지 마세요."
        else -> "담백한 일기와 영화 예고편 말투로 각각 두 문장을 제안해 주세요."
    }
    return "$facts\n$request\n내가 적은 사실을 유지하고 새로운 사건이나 대화는 넣지 마세요."
}

@Composable
internal fun MemoryPhoto(file: File?,modifier: Modifier,description: String) {
    val bitmap by produceState<ImageBitmap?>(null,file?.absolutePath) {
        value=withContext(Dispatchers.IO) {
            file?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath)?.asImageBitmap() }.getOrNull() }
        }
    }
    val image=bitmap
    if(image!=null) Image(image,contentDescription=description,modifier=modifier,contentScale=ContentScale.Crop)
    else Box(modifier.background(MaterialTheme.colorScheme.surfaceContainer),contentAlignment=Alignment.Center) {
        AlbumIcon(AlbumSymbol.Camera,Modifier.size(32.dp),MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun MemoryCard(memory: PlaceMemory,photo: File?,onOpen: () -> Unit) {
    Card(onClick=onOpen,modifier=Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=8.dp),shape=RoundedCornerShape(16.dp),
        colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainer)) {
        if(memory.photoId!=null) MemoryPhoto(photo,Modifier.fillMaxWidth().aspectRatio(16f/9f),memory.title)
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(memory.title,style=MaterialTheme.typography.titleLarge)
            Text(listOfNotNull(memory.place.takeIf{it.isNotBlank()},"나만 보기").joinToString(" · "),
                style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun MemoryDetailScreen(memory: PlaceMemory?,photo: File?,busy: Boolean,error: String?,onBack: () -> Unit,onDelete: () -> Unit,onShare: ()->Unit = {},onShareVideo: ()->Unit = {}) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    var sharing by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max=760.dp).fillMaxSize(),contentPadding=PaddingValues(bottom=32.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick=onBack){Text("뒤로")}
                    Text("Moment",style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
                    if(memory!=null) TextButton(onClick={sharing=true},enabled=!busy){Text("Share")}
                    if(memory!=null) TextButton(onClick={confirm=true},enabled=!busy){Text("삭제")}
                }
                InkDivider()
            }
            if(memory==null) item { Text("이 추억을 찾을 수 없습니다.",Modifier.padding(24.dp)) }
            else {
                if(memory.photoId!=null) item { MemoryPhoto(photo,Modifier.fillMaxWidth().padding(24.dp).aspectRatio(4f/3f).clip(RoundedCornerShape(16.dp)),memory.title) }
                item {
                    Column(Modifier.padding(horizontal=24.dp,vertical=16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                        Text(memory.title,style=MaterialTheme.typography.headlineMedium)
                        Text(SimpleDateFormat("yyyy.MM.dd",Locale.KOREA).format(Date(memory.savedAt))+" · 나만 보기",
                            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(memory.place.isNotBlank()) Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            AlbumIcon(AlbumSymbol.Pin,Modifier.size(18.dp)); Text(memory.place,style=MaterialTheme.typography.labelLarge)
                        }
                        if(memory.body.isNotBlank()) Text(memory.body,style=MaterialTheme.typography.bodyLarge)
                        if(error!=null) Text(error,color=MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
    if(sharing) AlertDialog(onDismissRequest={sharing=false},title={Text("Share")},
        text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick={sharing=false;onShare()},modifier=Modifier.fillMaxWidth()){Text("Post")}
            OutlinedButton(onClick={sharing=false;onShareVideo()},modifier=Modifier.fillMaxWidth()){Text("Moment Video")}
            Text("영상은 편집한 MP4 파일을 선택합니다. 공개 전 검수를 거칩니다.",style=MaterialTheme.typography.bodySmall)
        }},confirmButton={},dismissButton={TextButton(onClick={sharing=false}){Text("취소")}})
    if(confirm) AlertDialog(onDismissRequest={confirm=false},title={Text("이 추억을 삭제할까요?")},
        text={Text("이 기기에 보관한 사진과 글이 삭제됩니다. 삭제한 추억은 되돌릴 수 없습니다.")},
        confirmButton={TextButton(onClick={confirm=false;onDelete()},enabled=!busy){Text("삭제")}},
        dismissButton={TextButton(onClick={confirm=false}){Text("취소")}})
}

@Preview(showBackground=true,widthDp=360,heightDp=760)
@Composable
private fun MemoryEditorPreview() { AudioAppTheme { MemoryEditorContent(MemoryUiState(loaded=true),null,{},{},{},{},{},{},{},{}) } }
