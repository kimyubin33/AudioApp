package com.kimyubin33.audioapp.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kimyubin33.audioapp.BuildConfig
import com.kimyubin33.audioapp.data.LocalProfile
import com.kimyubin33.audioapp.data.LocalProfileStore
import com.kimyubin33.audioapp.ui.theme.AudioAppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun ProfileAvatar(file: File?, label: String, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val path=file?.absolutePath
    val bitmap by produceState<Pair<String?,ImageBitmap?>?>(null,path) {
        val decoded=withContext(Dispatchers.IO) { file?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath)?.asImageBitmap() }.getOrNull() } }
        value=path to decoded
    }
    Box(modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer)
        .border(1.dp,MaterialTheme.colorScheme.outline,CircleShape),contentAlignment=Alignment.Center) {
        val image=bitmap?.takeIf { it.first==path }?.second
        if(image!=null) Image(image,label,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
        else AlbumIcon(AlbumSymbol.Person,Modifier.size(size*0.55f))
    }
}

@Composable
internal fun AccountBadge(state: AccountUiState, onClick: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(24.dp)).clickable(role=Role.Button,onClickLabel="계정 설정",onClick=onClick)
        .heightIn(min=48.dp).padding(horizontal=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        ProfileAvatar(state.avatar,"프로필",size=36.dp)
        Text(when {
            state.signedIn -> state.name
            state.loading -> "…"
            state.hasSession -> "계정"
            else -> "로그인"
        },style=MaterialTheme.typography.labelSmall,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.widthIn(max=90.dp))
    }
}

@Composable
fun AccountScreen(
    state: AccountUiState,
    onBack: () -> Unit, onLogin: () -> Unit, onDeveloperLogin: () -> Unit,
    onProfile: () -> Unit, onMoments: () -> Unit, onBlocked: () -> Unit,
    onClearHistory: () -> Unit, onPermissions: () -> Unit, onSignOut: () -> Unit, onRetry: () -> Unit,
    developerLogin: Boolean = false,
) {
    var clearHistory by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max=720.dp).fillMaxSize(),contentPadding=PaddingValues(bottom=24.dp)) {
            item {
                AccountTopBar("Account",onBack)
                Column(Modifier.fillMaxWidth().padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,
                    verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    ProfileAvatar(state.avatar,"프로필",size=80.dp)
                    if(state.signedIn) {
                        Text(state.name,style=MaterialTheme.typography.titleLarge)
                        TextButton(onClick=onProfile,enabled=!state.busy&&!state.loading) { Text("Edit Profile") }
                    } else if(!state.hasSession && !state.loading) {
                        Button(onClick=onLogin,enabled=!state.busy) { Text("Google로 로그인") }
                        if(developerLogin) TextButton(onClick=onDeveloperLogin,enabled=!state.busy){Text("개발용 로그인")}
                    }
                    if(state.loading||state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.error?.let {
                        Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
                        TextButton(onClick=onRetry,enabled=!state.busy){Text("다시 확인")}
                    }
                }
                InkDivider(Modifier.padding(horizontal=24.dp))
            }
            item { AccountRow("Moments",onMoments) }
            item { AccountRow("차단 목록",onBlocked,enabled=state.signedIn&&!state.busy) }
            item { AccountRow("시청 기록 삭제",{clearHistory=true},enabled=!state.busy) }
            item { AccountRow("앱 권한",onPermissions) }
            if(state.hasSession || state.signedIn) item { AccountRow("Log out",onSignOut,enabled=!state.busy) }
            item {
                Text("Version ${BuildConfig.VERSION_NAME}",style=MaterialTheme.typography.bodySmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(horizontal=24.dp,vertical=24.dp))
            }
        }
    }
    if(clearHistory) AlertDialog(onDismissRequest={clearHistory=false},title={Text("시청 기록을 삭제할까요?")},
        text={Text("이 기기의 시청 기록을 모두 삭제합니다. 저장한 콘텐츠와 Moments는 유지됩니다.")},
        confirmButton={TextButton(onClick={clearHistory=false;onClearHistory()}){Text("삭제")}},
        dismissButton={TextButton(onClick={clearHistory=false}){Text("취소")}})
}

@Composable
private fun AccountRow(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().clickable(enabled=enabled,role=Role.Button,onClick=onClick).padding(horizontal=24.dp,vertical=20.dp),
        verticalAlignment=Alignment.CenterVertically) {
        Text(label,style=MaterialTheme.typography.bodyLarge,modifier=Modifier.weight(1f),
            color=if(enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        Text("›",style=MaterialTheme.typography.titleLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun AccountTopBar(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
        TextButton(onClick=onBack){Text("닫기")}
        Text(title,style=MaterialTheme.typography.titleLarge,modifier=Modifier.padding(start=8.dp))
    }
    InkDivider()
}

@Composable
fun ProfileEditorScreen(viewModel: AccountViewModel,userId: String,onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    var name by rememberSaveable(state.userId,state.profile?.name) { mutableStateOf(state.profile?.name.orEmpty()) }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri->uri?.let { viewModel.setAvatar(it,userId) } }
    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max=600.dp).fillMaxSize(),contentPadding=PaddingValues(bottom=24.dp),
            horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item { AccountTopBar("Profile",onBack) }
            item { ProfileAvatar(state.avatar,"프로필 사진",Modifier.padding(top=16.dp),96.dp) }
            item {
                TextButton(onClick={picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))},
                    enabled=state.signedIn&&!state.busy&&!state.loading){Text("사진 변경")}
                if(state.avatar!=null) TextButton(onClick={viewModel.removeAvatar(userId)},enabled=!state.busy&&!state.loading){Text("사진 삭제")}
            }
            item {
                OutlinedTextField(name,{if(LocalProfileStore.validName(it)) name=it},label={Text("Name")},singleLine=true,
                    supportingText={Text("${name.length}/40")},enabled=state.signedIn&&!state.busy&&!state.loading,
                    modifier=Modifier.fillMaxWidth().padding(horizontal=24.dp))
            }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text("이 계정의 프로필 · 이 기기에 저장",style=MaterialTheme.typography.bodySmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(state.busy||state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.error?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error) }
                    Button(onClick={viewModel.saveName(name,userId,onBack)},enabled=state.signedIn&&!state.busy&&!state.loading,
                        modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)){Text("Save")}
                }
            }
        }
    }
}

@Composable
fun MomentsScreen(
    state: MemoryUiState, photo: (String) -> File?, onBack: () -> Unit,
    onCreate: () -> Unit, onOpen: (String) -> Unit, onRetry: () -> Unit,
) {
    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max=760.dp).fillMaxSize(),contentPadding=PaddingValues(bottom=24.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick=onBack){Text("닫기")}
                    Text("Moments",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
                    TextButton(onClick=onCreate){Text("New")}
                }
                InkDivider()
            }
            if(!state.loaded) item {
                Text(state.error?:"불러오는 중…",Modifier.padding(24.dp))
                if(state.error!=null) TextButton(onClick=onRetry){Text("다시 시도")}
            } else if(state.album.memories.isEmpty()) item {
                Text("Moment가 없습니다.",Modifier.padding(24.dp),style=MaterialTheme.typography.bodyMedium)
            }
            items(state.album.memories,key={it.id}) { memory->MemoryCard(memory,memory.photoId?.let(photo)){onOpen(memory.id)} }
        }
    }
}

@Preview(showBackground=true,widthDp=360,heightDp=760)
@Composable
private fun AccountGuestPreview() {
    AudioAppTheme { AccountScreen(AccountUiState(loading=false),{},{},{},{},{},{},{},{},{},{}) }
}

@Preview(showBackground=true,widthDp=360,heightDp=760)
@Composable
private fun AccountMemberPreview() {
    AudioAppTheme { AccountScreen(AccountUiState(userId="preview-user",profile=LocalProfile("preview-user","Member"),hasSession=true,loading=false),
        {},{},{},{},{},{},{},{},{},{}) }
}
