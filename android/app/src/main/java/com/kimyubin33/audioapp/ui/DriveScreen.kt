package com.kimyubin33.audioapp.ui

import android.animation.ValueAnimator
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.kimyubin33.audioapp.data.RadioCatalog
import com.kimyubin33.audioapp.data.location.AudioRegion
import com.kimyubin33.audioapp.data.location.LocationProblem
import com.kimyubin33.audioapp.ui.theme.AudioAppTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val DriveInk = Color(0xFF101714)
private val DrivePaper = Color(0xFFF2F0E6)
private val DriveMoss = Color(0xFFAFBFA6)

/** Phone/OEM design surface. Android Auto uses the host's media UI, not this Compose surface. */
@Composable
internal fun DriveScreen(state: NearbyUiState, player: Player?, onEnable: () -> Unit,
    onDisable: () -> Unit, onRefresh: () -> Unit, onSettings: () -> Unit,
    onLocationSettings: () -> Unit, denied: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playback = player?.let { rememberPlaybackSnapshot(it).value }
    var topic by rememberSaveable { mutableStateOf(RadioCatalog.preference(context)) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun start() {
        if (player == null || busy) return
        busy = true; error = null
        scope.launch {
            try {
                if (RadioCatalog.mix(context,emptySet()).isEmpty()) {
                    error = "선택한 취향의 오디오가 없습니다."
                } else {
                    player.repeatMode = Player.REPEAT_MODE_OFF
                    player.setMediaItem(MediaItem.Builder().setMediaId(RadioCatalog.MIX).build())
                    player.prepare()
                    player.play()
                    val resolved = kotlinx.coroutines.withTimeoutOrNull(12_000) {
                        while(player.currentMediaItem?.mediaId == RadioCatalog.MIX || player.mediaItemCount == 0) {
                            kotlinx.coroutines.delay(100)
                        }
                        true
                    }
                    if(resolved != true) error = "재생을 시작하지 못했습니다. 다시 시도해 주세요."
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = userMessage(e) }
            finally { busy = false }
        }
    }
    DrivePanel(state,playback,topic,busy,error,denied,onSettings={settings=true},
        onEnable=onEnable,onDisable=onDisable,onRefresh=onRefresh,onPermission=onSettings,onLocationSettings=onLocationSettings,
        onPlay={
            if(playback?.hasError==true) start()
            else if(playback?.isPlaying==true) player?.pause()
            else if(playback?.mediaId!=null && player?.playbackState!=Player.STATE_ENDED) { player?.prepare(); player?.play() } else start()
        },
        onNext={if(player?.hasNextMediaItem()==true) player.seekToNextMediaItem() else start()},
        onMix=::start)
    if(settings) {
        var edit by rememberSaveable { mutableStateOf(topic) }
        AlertDialog(onDismissRequest={settings=false},title={Text("Tune your drive")},
            text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("출발 전에 듣고 싶은 주제를 선택하세요.",style=MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    listOf("" to "Mix","자연" to "Calm","이야기" to "Story").forEach{(value,label)->
                        FilterChip(edit==value,{edit=value},label={Text(label)})
                    }
                }
                OutlinedTextField(edit,{if(it.length<=40)edit=it},label={Text("키워드")},singleLine=true)
                Text("주변 오디오를 먼저 찾고, 선택한 주제로 이어 듣습니다.",style=MaterialTheme.typography.bodySmall)
            }},
            confirmButton={TextButton(onClick={RadioCatalog.setPreference(context,edit);topic=edit;settings=false}){Text("Save")}},
            dismissButton={TextButton(onClick={settings=false}){Text("Cancel")}})
    }
}

@Composable
internal fun DrivePanel(state: NearbyUiState, playback: PlaybackSnapshot?, topic: String,
    busy: Boolean, error: String?, denied: Boolean, onSettings: () -> Unit,
    onEnable: () -> Unit, onDisable: () -> Unit, onRefresh: () -> Unit, onPermission: () -> Unit,
    onLocationSettings: () -> Unit, onPlay: () -> Unit, onNext: () -> Unit, onMix: () -> Unit) {
    MaterialTheme(colorScheme=darkColorScheme(background=DriveInk,surface=DriveInk,
        primary=DrivePaper,onPrimary=DriveInk,onSurface=DrivePaper,surfaceContainer=Color(0xFF202923))) {
        CompositionLocalProvider(LocalContentColor provides DrivePaper) {
        BoxWithConstraints(Modifier.fillMaxSize().background(DriveInk)) {
            val wide = maxWidth >= 600.dp && maxWidth > maxHeight
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Drive",style=MaterialTheme.typography.headlineMedium)
                        Text(if(topic.isEmpty()) "Audio radio" else topic,
                            style=MaterialTheme.typography.labelSmall,color=DriveMoss,maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                    TextButton(onClick=onSettings,modifier=Modifier.heightIn(min=48.dp)){Text("Tune")}
                }
                Spacer(Modifier.height(12.dp))
                if(wide) {
                    Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(24.dp)) {
                        AudioField(state.regions,playback?.isPlaying==true,Modifier.weight(1.15f).fillMaxHeight())
                        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                            verticalArrangement=Arrangement.spacedBy(16.dp)) {
                            DriveStatus(state,denied,onEnable,onDisable,onRefresh,onPermission,onLocationSettings)
                            DriveControls(playback,busy,error,onPlay,onNext,onMix)
                        }
                    }
                } else {
                    AudioField(state.regions,playback?.isPlaying==true,Modifier.fillMaxWidth().weight(1f))
                    Column(Modifier.fillMaxWidth().heightIn(max=300.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        DriveStatus(state,denied,onEnable,onDisable,onRefresh,onPermission,onLocationSettings)
                        DriveControls(playback,busy,error,onPlay,onNext,onMix)
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun DriveStatus(state: NearbyUiState, denied: Boolean, onEnable: () -> Unit,
    onDisable: () -> Unit, onRefresh: () -> Unit, onPermission: () -> Unit, onLocationSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Text(if(state.enabled) "Nearby · 10 km" else "Anywhere",Modifier.weight(1f),
            style=MaterialTheme.typography.labelLarge,color=DriveMoss)
        TextButton(onClick=if(state.enabled) onDisable else onEnable){Text(if(state.enabled) "Off" else "내 주변")}
    }
    when {
        denied || state.problem==LocationProblem.Permission -> TextButton(onClick=onPermission){Text("위치 권한 설정")}
        state.problem==LocationProblem.Disabled || state.problem==LocationProblem.Unavailable ->
            TextButton(onClick=onLocationSettings){Text("위치 켜기")}
        state.problem==LocationProblem.Waiting || state.problem==LocationProblem.Stale ->
            TextButton(onClick=onRefresh){Text("위치 다시 확인")}
        state.error!=null -> TextButton(onClick=onRefresh){Text("주변 오디오 다시 불러오기")}
        state.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
        state.enabled && state.loaded && state.regions.isEmpty() ->
            Text("주변 오디오가 없습니다. Mix로 이어 들으세요.",style=MaterialTheme.typography.bodySmall,color=DriveMoss)
        !state.enabled -> Text("위치 없이도 Mix를 들을 수 있습니다.",style=MaterialTheme.typography.bodySmall,color=DriveMoss)
    }
    state.regions.take(2).forEach { region ->
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text(region.title,Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyMedium)
            Text(" · %.1f km".format(java.util.Locale.ROOT,region.radiusMeters/1000.0),style=MaterialTheme.typography.labelMedium,color=DriveMoss)
        }
    }
}

@Composable
private fun DriveControls(playback: PlaybackSnapshot?, busy: Boolean, error: String?,
    onPlay: () -> Unit,onNext: () -> Unit,onMix: () -> Unit) {
    Surface(shape=RoundedCornerShape(24.dp),color=Color(0xFF202923)) {
        Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(if(playback?.isPlaying==true) "NOW PLAYING" else "READY WHEN YOU ARE",
                style=MaterialTheme.typography.labelSmall,color=DriveMoss)
            AnimatedContent(playback?.title?.ifBlank { null } ?: "Drive Mix",label="track title") { title ->
                Text(title,style=MaterialTheme.typography.titleLarge,maxLines=2,overflow=TextOverflow.Ellipsis)
            }
            if(error!=null || playback?.hasError==true) Text(error ?: "재생하지 못했습니다. 다시 시도해 주세요.",
                style=MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Button(onClick=onPlay,enabled=!busy,modifier=Modifier.weight(1f).heightIn(min=64.dp)) {
                    Text(if(busy) "Loading" else if(playback?.isPlaying==true) "Pause" else "Play")
                }
                OutlinedButton(onClick=onNext,enabled=!busy,modifier=Modifier.weight(1f).heightIn(min=64.dp)){Text("Next")}
            }
            TextButton(onClick=onMix,enabled=!busy,modifier=Modifier.align(Alignment.End)){Text("New mix")}
        }
    }
}

/** North-up relative field, not navigation roads. All plotted circles come from real content places. */
@Composable
private fun AudioField(regions: List<AudioRegion>, playing: Boolean, modifier: Modifier) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val breath = remember { Animatable(0f) }
    LaunchedEffect(playing,lifecycle) {
        if(playing && lifecycle.isAtLeast(Lifecycle.State.STARTED) && ValueAnimator.areAnimatorsEnabled()) {
            breath.animateTo(1f,infiniteRepeatable(tween(6000,easing=FastOutSlowInEasing),RepeatMode.Reverse))
        } else breath.snapTo(0f)
    }
    Box(modifier.clip(RoundedCornerShape(28.dp)).background(Color(0xFF151E18))) {
        Canvas(Modifier.fillMaxSize().semantics {
            contentDescription = if(regions.isEmpty()) "오디오 영역 대기" else "북쪽 기준 주변 오디오 영역 ${regions.size}개"
        }) {
            val center=Offset(size.width/2,size.height/2)
            val scale=size.minDimension/24000f
            drawCircle(Brush.radialGradient(listOf(Color(0xFF31402F),Color.Transparent),center,size.maxDimension*.7f),
                radius=size.maxDimension*.7f,center=center)
            listOf(5000,10000).forEach { meters ->
                drawCircle(DrivePaper.copy(alpha=.07f),meters*scale,center,style=Stroke(1.dp.toPx()))
            }
            regions.asReversed().forEach { region ->
                val point=center+Offset(region.eastMeters.toFloat()*scale,-region.northMeters.toFloat()*scale)
                val radius=region.radiusMeters*scale
                drawCircle(Brush.radialGradient(listOf(DriveMoss.copy(alpha=.22f),DriveMoss.copy(alpha=.02f)),point,radius),
                    radius=radius,center=point)
                drawArc(DrivePaper.copy(alpha=.62f),-110f,306f,false,
                    point-Offset(radius,radius),Size(radius*2,radius*2),style=Stroke(2.5.dp.toPx()))
                drawArc(DriveMoss.copy(alpha=.30f),-104f,300f,false,
                    point-Offset(radius*1.04f,radius*1.04f),Size(radius*2.08f,radius*2.08f),style=Stroke(.7.dp.toPx()))
                drawCircle(DrivePaper,3.dp.toPx(),point)
            }
            if(regions.isEmpty()) {
                val radius=size.minDimension*.26f
                drawArc(DrivePaper.copy(alpha=.65f),-100f,302f,false,
                    center-Offset(radius,radius),Size(radius*2,radius*2),style=Stroke(5.dp.toPx()))
                drawArc(DriveMoss.copy(alpha=.3f),-90f,290f,false,
                    center-Offset(radius*1.035f,radius*1.035f),Size(radius*2.07f,radius*2.07f),style=Stroke(1.dp.toPx()))
            }
            drawCircle(DriveMoss.copy(alpha=.08f+breath.value*.08f),(17+breath.value*6).dp.toPx(),center)
            drawCircle(DrivePaper,5.dp.toPx(),center)
        }
        Text("N",color=DriveMoss,style=MaterialTheme.typography.labelSmall,
            modifier=Modifier.align(Alignment.TopCenter).padding(12.dp))
        Text("AUDIO FIELD",color=DriveMoss,style=MaterialTheme.typography.labelSmall,
            modifier=Modifier.align(Alignment.BottomStart).padding(16.dp))
    }
}

@Preview(name="Navigation side panel",widthDp=380,heightDp=640)
@Preview(name="Wide dashboard",widthDp=960,heightDp=480)
@Composable
private fun DrivePreview() {
    AudioAppTheme {
        DrivePanel(NearbyUiState(enabled=true,loaded=true,regions=listOf(
            AudioRegion("river","강변",1800.0,2300.0,2400,listOf("a")),
            AudioRegion("forest","숲길",-2400.0,-3000.0,3800,listOf("b")))),
            PlaybackSnapshot("a","돌아가는 길, 잔잔한 파도","creator",true,false,false,false),
            "",false,null,false,{},{},{},{},{},{},{},{},{})
    }
}
