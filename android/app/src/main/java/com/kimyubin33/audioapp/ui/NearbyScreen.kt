package com.kimyubin33.audioapp.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kimyubin33.audioapp.data.location.LocationProblem
import com.kimyubin33.audioapp.ui.theme.AudioAppTheme

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@Composable
fun NearbyScreen(viewModel: NearbyViewModel, onBack: () -> Unit, onOpen: (String) -> Unit, notice: String?,
    embedded: Boolean = false, onSearchPlace: ((String) -> Unit)? = null, player: androidx.media3.common.Player? = null) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var rationale by remember { mutableStateOf(false) }
    var denied by rememberSaveable { mutableStateOf(false) }
    val permission = android.Manifest.permission.ACCESS_COARSE_LOCATION
    val permissions = arrayOf(permission, android.Manifest.permission.ACCESS_FINE_LOCATION)
    // 좁은 장소의 체류에는 정밀 위치가 유용하지만, 대략적인 위치만 허용해도 탐색할 수 있다.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        denied = !viewModel.hasPermission()
        if (!denied) viewModel.enable()
    }
    fun enableAfterNotice() {
        when {
            viewModel.hasPermission() -> { denied = false; viewModel.enable() }
            context.activity()?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, permission) } == true -> rationale = true
            denied -> context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            else -> launcher.launch(permissions)
        }
    }
    fun enable() { rationale = true }
    DisposableEffect(lifecycle, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) viewModel.enterForeground()
            if (event == Lifecycle.Event.ON_STOP) viewModel.leaveForeground()
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) viewModel.enterForeground()
        onDispose {
            lifecycle.removeObserver(observer)
            viewModel.leaveForeground()
        }
    }
    if (embedded) DriveScreen(state,player,::enable,viewModel::disable,viewModel::refresh,
        onSettings={context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}")))},
        onLocationSettings={context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))},denied=denied)
    else NearbyContent(state, onBack, ::enable, viewModel::disable, viewModel::refresh, viewModel::radius, onOpen,
        onPermissionSettings = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) },
        onLocationSettings = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
        denied = denied, notice = notice, embedded = embedded, onSearchPlace = onSearchPlace,
        onMap = { lat,lon,name ->
            val uri=Uri.parse("geo:$lat,$lon?q=$lat,$lon("+Uri.encode(name)+")")
            try { context.startActivity(Intent(Intent.ACTION_VIEW,uri)) }
            catch (_: android.content.ActivityNotFoundException) { android.widget.Toast.makeText(context,"지도를 열 수 있는 앱이 없습니다.",android.widget.Toast.LENGTH_SHORT).show() }
        })
    if (rationale) AlertDialog(
        onDismissRequest = { rationale = false },
        title = { Text("주변 이야기 위치 권한") },
        text = { Text("주변 오디오 검색을 위해 화면을 사용하는 동안 위치 좌표를 서버로 보냅니다. 현재 검색 처리에는 좌표를 데이터베이스에 저장하지 않습니다. 대략적인 위치만 허용할 수 있으며, 주변 탐색을 끄거나 이 화면을 벗어나면 위치 요청을 중지합니다. 거부해도 업로드·검색·재생은 사용할 수 있습니다.") },
        confirmButton = { TextButton(onClick = { rationale = false; if(viewModel.hasPermission()) enableAfterNotice() else launcher.launch(permissions) }) { Text("동의하고 계속") } },
        dismissButton = { TextButton(onClick = { rationale = false }) { Text("취소") } },
    )
}

@Composable
internal fun NearbyContent(
    state: NearbyUiState, onBack: () -> Unit, onEnable: () -> Unit, onDisable: () -> Unit,
    onRefresh: () -> Unit, onRadius: (Int) -> Unit, onOpen: (String) -> Unit,
    onPermissionSettings: () -> Unit, onLocationSettings: () -> Unit,
    denied: Boolean = false, notice: String? = null,
    embedded: Boolean = false, onSearchPlace: ((String) -> Unit)? = null,
    onMap: (Double, Double, String) -> Unit = { _,_,_ -> },
) {
    var placeQuery by rememberSaveable { mutableStateOf("") }
    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.TopCenter) {
    LazyColumn(Modifier.widthIn(max=760.dp).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if(!embedded) TextButton(onClick = onBack) { Text("뒤로") }
                Text("지도", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start=16.dp,top=12.dp,bottom=12.dp))
                if (state.enabled) TextButton(onClick = onDisable) { Text("끄기") }
            }
            InkDivider()
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if(onSearchPlace!=null) {
                    OutlinedTextField(placeQuery,{if(it.length<=100) placeQuery=it},label={Text("장소 검색")},singleLine=true,
                        leadingIcon={AlbumIcon(AlbumSymbol.Pin,Modifier.size(20.dp))},modifier=Modifier.fillMaxWidth())
                    OutlinedButton(onClick={onSearchPlace(placeQuery.trim())},enabled=placeQuery.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text("검색")}
                }
                if (!state.enabled) {
                    Text("위치를 서버로 보내 주변 오디오를 찾습니다. 위치 사용은 선택입니다.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onEnable, modifier=Modifier.fillMaxWidth()) { Text("내 주변") }
                } else {
                    Text("위치 소스: 스마트폰", style = MaterialTheme.typography.labelLarge)
                    state.accuracyMeters?.let { accuracy ->
                        Text("${if (state.precise) "정확한 위치" else "대략적인 위치"} · 오차 약 ${distanceLabel(accuracy)}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if(state.encounters>0) Text("이번 탐색에서 만난 지역 캐릭터 ${state.encounters}개", style = MaterialTheme.typography.bodySmall)
                    if (!state.precise) TextButton(onClick = onPermissionSettings) { Text("위치 정확도 설정") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1000, 5000, 10000).forEach { radius ->
                            FilterChip(selected = radius == state.radiusMeters, onClick = { onRadius(radius) },
                                label = { Text("${radius / 1000}km") },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.onSurface,
                                    selectedLabelColor = MaterialTheme.colorScheme.surface))
                        }
                    }
                }
                if (denied || state.problem == LocationProblem.Permission) {
                    Text("위치 권한이 꺼져 있습니다. 권한 없이도 다른 화면에서 작품을 검색하고 재생할 수 있습니다.")
                    TextButton(onClick = onPermissionSettings) { Text("앱 권한 설정") }
                    if (state.enabled) TextButton(onClick = onEnable) { Text("권한 다시 확인") }
                } else if (state.enabled) {
                    when (state.problem) {
                        LocationProblem.Disabled -> {
                            Text("기기의 위치 기능이 꺼져 있습니다.")
                            TextButton(onClick = onLocationSettings) { Text("기기 위치 설정") }
                            TextButton(onClick = onRefresh) { Text("위치 다시 확인") }
                        }
                        LocationProblem.Unavailable -> {
                            Text("현재 사용할 수 있는 위치 제공자가 없습니다. 기기 위치 설정을 확인해 주세요.")
                            TextButton(onClick = onLocationSettings) { Text("기기 위치 설정") }
                            TextButton(onClick = onRefresh) { Text("다시 시도") }
                        }
                        LocationProblem.Waiting, LocationProblem.Stale -> {
                            Text(if (state.problem == LocationProblem.Stale) "새 위치를 기다리고 있습니다. 오래된 위치로 장소 방문을 판단하지 않습니다."
                                else "스마트폰 위치를 확인하고 있습니다. 위치 수신에는 시간이 걸릴 수 있습니다.")
                            TextButton(onClick = onRefresh) { Text("위치 다시 확인") }
                        }
                        else -> {
                            TextButton(onClick = onRefresh, enabled = !state.loading) { Text("주변 작품 새로고침") }
                        }
                    }
                }
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.error != null) {
                    Text(state.error, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRefresh, enabled = !state.loading) { Text("다시 시도") }
                }
                if (notice != null) Text(notice, color = MaterialTheme.colorScheme.error)
                if (state.loaded && state.items.isEmpty() && state.error == null)
                    Text("주변 게시물이 없습니다.")
                if (state.truncated && state.loaded)
                    Text("주변 작품 중 일부를 표시합니다. 더 좁은 범위에서 다시 찾아보세요.", style = MaterialTheme.typography.bodySmall)
            }
        }
        items(state.items, key = { it.content.id }) { card ->
            OutlinedCard(onClick = { onOpen(card.content.id) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${card.place} · 약 ${distanceLabel(card.distanceMeters)}", style = MaterialTheme.typography.labelLarge)
                    if (card.character.isNotBlank()) Text(card.character, style = MaterialTheme.typography.titleLarge)
                    Text(card.content.title, style = MaterialTheme.typography.titleMedium)
                    if (card.story.isNotBlank()) Text(card.story, style = MaterialTheme.typography.bodyMedium)
                    Text(metaLine(card.content.creator, card.content.isVideo, card.content.aiGenerated), style = MaterialTheme.typography.bodySmall)
                    Text(when {
                        card.met -> "이번 탐색에서 만난 캐릭터"
                        card.inside -> "장소 범위 안 · 캐릭터와 머무는 중"
                        else -> "주변에서 발견 · 위치 오차를 고려해 방문을 확인합니다"
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (card.content.isVideo) "쇼츠 재생" else "오디오 재생", style = MaterialTheme.typography.labelLarge)
                    if(card.latitude!=null && card.longitude!=null) TextButton(onClick={onMap(card.latitude,card.longitude,card.place)}){Text("지도 앱에서 장소 보기")}
                }
            }
        }
    }
    }
}

private fun distanceLabel(meters: Int): String = if (meters < 1000) "${meters.coerceAtLeast(1)}m"
    else String.format(java.util.Locale.KOREA, "%.1fkm", meters / 1000.0)

@Preview(showBackground = true, widthDp = 360, heightDp = 760)
@Composable
private fun NearbyPreview() {
    AudioAppTheme {
        NearbyContent(NearbyUiState(enabled = true, loaded = true, accuracyMeters = 120,
            items = listOf(NearbyCard(HomeItem("preview", "강가에 남겨진 달빛", "creator_01", true), "한강공원", "달빛 수호자",
                "이곳의 바람을 기억하는 캐릭터가 강가의 이야기를 들려줍니다.", 350, false, false))),
            {}, {}, {}, {}, {}, {}, {}, {})
    }
}
