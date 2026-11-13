package com.kimyubin33.audioapp.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kimyubin33.audioapp.data.AudioApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.audio_core.ContentDetail
import uniffi.audio_core.ContentKind

@Composable
internal fun ShortFilters(selected: ShortFilter,onSelect: (ShortFilter)->Unit,onPlay:(()->Unit)?=null) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=24.dp),
        horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
        ShortFilter.entries.forEach { style->
            FilterChip(selected==style,{onSelect(style)},label={Text(style.label)},
                colors=FilterChipDefaults.filterChipColors(selectedContainerColor=MaterialTheme.colorScheme.onSurface,selectedLabelColor=MaterialTheme.colorScheme.surface))
        }
        if(onPlay!=null) TextButton(onClick=onPlay){Text("연속 재생")}
    }
}

private object PostImageCache {
    val images=object:LruCache<String,Bitmap>(8*1024*1024) {
        override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount
    }
}

@Composable
internal fun RemotePostImage(id:String,label:String,modifier:Modifier=Modifier) {
    val context=LocalContext.current.applicationContext
    var retry by remember(id){mutableIntStateOf(0)}
    val result by produceState<Result<Bitmap>?>(null,id,retry) {
        value=null
        value=try {
            val cached=PostImageCache.images.get(id)
            Result.success(cached?:run {
                val api=withContext(Dispatchers.IO){AudioApi.get(context)}
                val bytes=api.postImage(id)
                withContext(Dispatchers.Default) {
                    val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
                    BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                    require(bounds.outWidth>0&&bounds.outHeight>0&&bounds.outWidth.toLong()*bounds.outHeight<=40_000_000)
                    val options=BitmapFactory.Options().apply {
                        var sample=1
                        while(maxOf(bounds.outWidth,bounds.outHeight)/sample>1600) sample*=2
                        inSampleSize=sample
                    }
                    requireNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)).also{PostImageCache.images.put(id,it)}
                }
            })
        } catch(e:CancellationException){throw e} catch(e:Exception){Result.failure(e)}
    }
    val bitmap=result?.getOrNull()
    if(bitmap!=null) Image(bitmap.asImageBitmap(),label,modifier,contentScale=ContentScale.Crop)
    else Box(modifier.background(MaterialTheme.colorScheme.surfaceContainer),contentAlignment=Alignment.Center) {
        if(result?.isFailure==true) TextButton(onClick={retry++}){Text("사진 다시 보기")}
        else CircularProgressIndicator(Modifier.size(24.dp),strokeWidth=2.dp)
    }
}

@Composable
fun PostDetailScreen(
    id:String,load:suspend(String)->ContentDetail,onViewed:(ContentDetail)->Unit,
    saved:Boolean,subscribed:Boolean,onSave:(ContentDetail)->Unit,onSubscribe:(String)->Unit,
    onReport:(String)->Unit,onBlock:(String)->Unit,onAudio:(String)->Unit,onBack:()->Unit,
) {
    var retry by remember(id){mutableIntStateOf(0)}
    val latestViewed by rememberUpdatedState(onViewed)
    val result by produceState<Result<ContentDetail>?>(null,id,retry) {
        value=null
        value=try {
            val detail=load(id);require(detail.item.content.kind==ContentKind.POST)
            latestViewed(detail);Result.success(detail)
        } catch(e:CancellationException){throw e} catch(e:Exception){Result.failure(e)}
    }
    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max=760.dp).fillMaxSize(),contentPadding=PaddingValues(bottom=32.dp),
            verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item { AccountTopBar("Post",onBack) }
            val detail=result?.getOrNull()
            if(detail!=null) {
                val item=detail.item
                if(item.hasImage) item { RemotePostImage(id,item.content.title,Modifier.fillMaxWidth().aspectRatio(4f/3f)) }
                item {
                    Column(Modifier.padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                        Text(item.content.title,style=MaterialTheme.typography.headlineMedium)
                        Text(metaLine(item.content.creatorId,false,item.content.aiGenerated,true),style=MaterialTheme.typography.bodySmall,
                            color=MaterialTheme.colorScheme.onSurfaceVariant)
                        detail.placeName?.let{Text(it,style=MaterialTheme.typography.labelLarge)}
                        SelectionContainer{Text(detail.body.orEmpty(),style=MaterialTheme.typography.bodyLarge)}
                        detail.audioContentId?.let{audio->OutlinedButton(onClick={onAudio(audio)}){Text("Listen")}}
                    }
                }
                item {
                    FlowRow(Modifier.padding(horizontal=24.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick={onSave(detail)}){Text(if(saved) "Saved" else "Save")}
                        OutlinedButton(onClick={onSubscribe(item.content.creatorId)}){Text(if(subscribed) "구독 중" else "구독")}
                        TextButton(onClick={onReport(item.content.creatorId)}){Text("신고")}
                        TextButton(onClick={onBlock(item.content.creatorId)}){Text("차단")}
                    }
                }
            } else item {
                Column(Modifier.padding(24.dp)) {
                    Text(if(result==null) "불러오는 중…" else userMessage(result!!.exceptionOrNull()!!))
                    if(result!=null) TextButton(onClick={retry++}){Text("다시 시도")}
                }
            }
        }
    }
}
