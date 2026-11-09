package com.kimyubin33.audioapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kimyubin33.audioapp.ui.theme.AudioAppTheme
import com.kimyubin33.audioapp.ui.theme.BrushFont
import com.kimyubin33.audioapp.ui.theme.Ink
import com.kimyubin33.audioapp.ui.theme.Paper

/** 목록 한 줄에 필요한 정보만 담은 화면용 모델 (Rust·네트워크와 무관해서 Preview에서도 쓸 수 있다). */
data class HomeItem(
    val id: String,
    val title: String,
    val creator: String,
    val aiGenerated: Boolean,
    val isVideo: Boolean = false,
)

/** "creator · 영상 · AI 생성" 처럼 보조 정보를 한 줄로. */
internal fun metaLine(creator: String, isVideo: Boolean, aiGenerated: Boolean): String =
    listOfNotNull(creator, if (isVideo) "쇼츠" else null, if (aiGenerated) "AI 생성" else null).joinToString(" · ")

/** 한 피드에서 오디오와 쇼츠를 발견한다. 검색은 사용자가 열 때만 펼친다. */
@Composable
fun FeedScreen(
    title: String,
    emptyMessage: String,
    items: List<HomeItem>,
    loading: Boolean,
    error: String?,
    hasMore: Boolean,
    notice: String?,
    onOpen: (String) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
    searchQuery: String? = null,
    onSearch: (String) -> Unit = {},
    onCreateMemory: (() -> Unit)? = null,
    format: FeedFormat = FeedFormat.All,
    onFormat: (FeedFormat) -> Unit = {},
    onOpenShorts: (() -> Unit)? = null,
) {
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(searchQuery) { if (!searchQuery.isNullOrEmpty()) searchOpen = true }
    LaunchedEffect(format) { listState.scrollToItem(0) }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max=760.dp).fillMaxSize(), state=listState, contentPadding=PaddingValues(bottom=24.dp)) {
            item(key="header") {
                AlbumHeader(onSearch=if(searchQuery!=null) ({ searchOpen=!searchOpen }) else null, onCreate=onCreateMemory)
                if (title != "피드") {
                    Text(title,style=MaterialTheme.typography.titleLarge,modifier=Modifier.padding(horizontal=24.dp,vertical=12.dp))
                }
                if (searchOpen && searchQuery!=null) OutlinedTextField(
                    value=searchQuery,onValueChange=onSearch,singleLine=true,label={Text("검색")},
                    trailingIcon={if(searchQuery.isNotEmpty()) TextButton(onClick={onSearch("")}){Text("지우기")}},
                    modifier=Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=12.dp),
                    shape=RoundedCornerShape(14.dp),
                )
                if(title=="피드") {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=24.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        FeedFormat.entries.forEach { entry ->
                            FilterChip(selected=format==entry,onClick={onFormat(entry)},label={Text(entry.label)},
                                colors=FilterChipDefaults.filterChipColors(selectedContainerColor=Ink,selectedLabelColor=Paper))
                        }
                    }
                    if(format==FeedFormat.Shorts && onOpenShorts!=null) TextButton(onClick=onOpenShorts,modifier=Modifier.padding(horizontal=16.dp)){Text("연속 재생")}
                    Spacer(Modifier.height(12.dp))
                } else Spacer(Modifier.height(12.dp))
            }
            if(notice!=null) item { StatusMessage(notice) }
            if(items.isEmpty()) item {
                when {
                    error!=null -> StatusMessage(error,actionLabel="다시 시도",onAction=onRetry)
                    loading -> StatusMessage("불러오는 중…")
                    else -> StatusMessage(emptyMessage)
                }
            } else {
                items(items,key={it.id}) { FeedCard(it,onOpen) }
                item {
                    when {
                        loading -> StatusMessage("불러오는 중…")
                        error!=null -> StatusMessage(error,actionLabel="다시 시도",onAction=onRetry)
                        hasMore -> StatusMessage(null,actionLabel="더 보기",onAction=onLoadMore)
                    }
                }
            }
        }
    }
}

@Composable
internal fun FeedCard(item: HomeItem, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal=24.dp).padding(bottom=28.dp)
        .clickable(role=Role.Button,onClickLabel="이야기 재생"){onOpen(item.id)}) {
        Box(Modifier.clip(RoundedCornerShape(14.dp))) {
            InkArt(seed=item.id,isVideo=item.isVideo,modifier=Modifier.fillMaxWidth().aspectRatio(16f/9f))
            if(item.isVideo) PlayBadge(Modifier.align(Alignment.BottomEnd).padding(12.dp))
        }
        Row(Modifier.padding(start=8.dp,end=8.dp,top=12.dp),verticalAlignment=Alignment.CenterVertically) {
            CreatorMark(item.creator,size=36.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title,style=MaterialTheme.typography.titleMedium.copy(fontFamily=FontFamily.Default,fontWeight=FontWeight.SemiBold),
                    maxLines=2,overflow=TextOverflow.Ellipsis)
                Text(metaLine(item.creator,item.isVideo,item.aiGenerated),style=MaterialTheme.typography.bodySmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
        }
    }
}

/** 크리에이터를 나타내는 먹빛 낙관(도장) 같은 동그라미. 크리에이터 id 끝 두 글자를 붓글씨로 새긴다. */
@Composable
internal fun CreatorMark(creator: String, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(Ink),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = creator.takeLast(2),
            color = Paper,
            fontFamily = BrushFont,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

/** 영상임을 알리는 작은 검은 동그라미 + 흰 삼각형. */
@Composable
private fun PlayBadge(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(30.dp)) {
        drawCircle(Ink.copy(alpha = 0.92f))
        val s = size.minDimension
        val path = Path().apply {
            moveTo(s * 0.40f, s * 0.30f)
            lineTo(s * 0.72f, s * 0.50f)
            lineTo(s * 0.40f, s * 0.70f)
            close()
        }
        drawPath(path, Color.White)
    }
}

@Composable
private fun StatusMessage(
    message: String?,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp)) {
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (actionLabel != null) {
            TextButton(onClick = onAction) {
                Text(actionLabel, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

private val previewItems = listOf(
    HomeItem("sample-1", "샘플 오디오 하나", "creator_01", true),
    HomeItem("sample-4", "짧은 영상 샘플", "creator_01", true, isVideo = true),
    HomeItem("sample-2", "파리의 재즈", "creator_02", true),
)

@Preview(showBackground = true, widthDp = 360, heightDp = 760)
@Composable
private fun FeedScreenPreview() {
    AudioAppTheme {
        FeedScreen(
            title = "피드", emptyMessage = "", onCreateMemory = {}, searchQuery = "",
            items = previewItems, loading = false, error = null, hasMore = true, notice = null,
            onOpen = {}, onRetry = {}, onLoadMore = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 400)
@Composable
private fun FeedScreenErrorPreview() {
    AudioAppTheme {
        FeedScreen(
            title = "구독", emptyMessage = "",
            items = emptyList(), loading = false, error = "서버에 연결할 수 없습니다. 연결을 확인해 주세요.",
            hasMore = false, notice = null, onOpen = {}, onRetry = {}, onLoadMore = {},
        )
    }
}
