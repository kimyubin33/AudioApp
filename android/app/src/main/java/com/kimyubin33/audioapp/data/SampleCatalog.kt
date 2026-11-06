package com.kimyubin33.audioapp.data

import uniffi.audio_core.Content
import uniffi.audio_core.ContentKind
import uniffi.audio_core.createContent

/** 목록에 보여줄 한 곡. 표시 정보([content])는 Rust가 검증한 값이다. */
data class Track(
    val id: String,
    val url: String,
    val content: Content,
)

/**
 * 개발용 임시 목록 (Media3 데모에서 쓰는 공개 파일).
 * 실제 서버가 생기면 서버 응답으로 바꾼다. 재생 시간(durationSeconds)은 임시 값이라 화면에는 보여주지 않는다.
 */
object SampleCatalog {
    val tracks: List<Track> by lazy {
        listOf(
            sample(
                id = "sample-1",
                url = "https://storage.googleapis.com/exoplayer-test-media-0/play.mp3",
                title = "샘플 오디오 하나",
            ),
            sample(
                id = "sample-2",
                url = "https://storage.googleapis.com/exoplayer-test-media-0/Jazz_In_Paris.mp3",
                title = "파리의 재즈",
            ),
        )
    }

    fun find(id: String): Track? = tracks.firstOrNull { it.id == id }

    private fun sample(id: String, url: String, title: String) = Track(
        id = id,
        url = url,
        content = createContent(
            title = title,
            creatorId = "creator_01",
            kind = ContentKind.AUDIO,
            durationSeconds = 200u,
            aiGenerated = true,
            aiTool = null,
        ),
    )
}
