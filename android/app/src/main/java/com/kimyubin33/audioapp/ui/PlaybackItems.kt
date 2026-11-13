package com.kimyubin33.audioapp.ui

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import uniffi.audio_core.ContentDetail
import uniffi.audio_core.ContentKind

// 미니 플레이어가 재생 중인 곡의 정보를 플레이어에서 바로 읽을 수 있도록 메타데이터 extras에 함께 담는다
internal const val EXTRA_CREATOR_ID = "creator_id"
internal const val EXTRA_AI_GENERATED = "ai_generated"
internal const val EXTRA_IS_VIDEO = "is_video"

/** 서버가 알려준 곡 상세를 재생용 [MediaItem]으로 바꾼다. (재생 주소는 Rust가 https만 통과시킨 값) */
internal fun ContentDetail.toMediaItem(): MediaItem {
    val content = item.content
    require(content.kind == ContentKind.AUDIO)
    val mediaUrl = requireNotNull(streamUrl)
    return MediaItem.Builder()
        .setMediaId(item.id)
        .setUri(mediaUrl)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(content.title)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                // 알림창·잠금화면에 보이는 정보 (AI 생성 표시 포함)
                .setArtist(if (content.aiGenerated) "${content.creatorId} · AI 생성" else content.creatorId)
                .setExtras(
                    Bundle().apply {
                        putString(EXTRA_CREATOR_ID, content.creatorId)
                        putBoolean(EXTRA_AI_GENERATED, content.aiGenerated)
                        putBoolean(EXTRA_IS_VIDEO, content.kind == ContentKind.SHORT_VIDEO)
                    }
                )
                .build()
        )
        .build()
}
