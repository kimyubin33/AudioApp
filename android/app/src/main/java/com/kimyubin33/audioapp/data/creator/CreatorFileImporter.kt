package com.kimyubin33.audioapp.data.creator

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.UUID
import kotlin.math.ceil

data class CreatorImportResult(val drafts: List<CreatorDraft>, val notices: List<String>)

class CreatorFileImporter(private val context: Context, private val store: CreatorDraftStore) {
    suspend fun import(uris: List<Uri>, existing: List<CreatorDraft>, progress: (String) -> Unit): CreatorImportResult {
        val scope = currentCoroutineContext()
        val added = mutableListOf<CreatorDraft>()
        val notices = mutableListOf<String>()
        var total = existing.sumOf { if (it.fileId != null) it.bytes else 0L }
        var returned = false
        try {
            for (uri in uris.take(CreatorFileRules.MAX_DRAFTS)) {
                scope.ensureActive()
                var name = "선택한 파일"
                val batch = mutableListOf<CreatorDraft>()
                val skipped = mutableListOf<String>()
                fun copyMedia(input: java.io.InputStream, entryName: String, source: String) {
                    if (existing.size + added.size + batch.size >= CreatorFileRules.MAX_DRAFTS)
                        throw CreatorFileException("초안은 최대 20개입니다. 완료한 초안을 정리해 주세요.")
                    val id = UUID.randomUUID().toString()
                    val target = store.file(id)
                    try {
                        val remaining = CreatorFileRules.MAX_IMPORT_BYTES - total - batch.sumOf { it.bytes }
                        val copied = CreatorFileRules.copy(input, target, minOf(CreatorFileRules.MAX_FILE_BYTES, remaining)) { scope.ensureActive() }
                        scope.ensureActive()
                        batch += inspect(target, id, entryName, source, copied)
                    } catch (e: Exception) { target.delete(); throw e }
                }
                try {
                    require(uri.scheme == "content")
                    name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    }?.substringAfterLast('/')?.substringAfterLast('\\')?.filterNot {
                        it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt()
                    }?.take(255)?.ifBlank { null } ?: "파일"
                    progress("$name 확인 중")
                    if (name.endsWith(".zip", true)) {
                        // A bounded, private temporary copy lets us verify the central directory and EOF.
                        // UUID storage also allows normal orphan cleanup after process termination.
                        val archive = store.file(UUID.randomUUID().toString())
                        try {
                            (context.contentResolver.openInputStream(uri) ?: throw CreatorFileException("파일을 열 수 없습니다.")).use {
                                CreatorFileRules.copy(it, archive, CreatorFileRules.MAX_ARCHIVE_BYTES) { scope.ensureActive() }
                            }
                            val entries = CreatorZipArchive.entries(archive) { scope.ensureActive() }
                            val media = entries.filter { !it.directory && CreatorFileRules.mediaName(it.name) }
                            if (existing.size + added.size + media.size > CreatorFileRules.MAX_DRAFTS ||
                                total + media.sumOf { it.size } > CreatorFileRules.MAX_IMPORT_BYTES)
                                throw CreatorFileException("초안은 최대 20개·총 360MiB입니다.")
                            CreatorZipArchive.visit(archive, entries, { scope.ensureActive() }) { entry, input ->
                                if (!entry.directory && CreatorFileRules.mediaName(entry.name)) {
                                    copyMedia(input, entry.name.substringAfterLast('/'), "$name / ${entry.name}")
                                } else if (!entry.directory) {
                                    skipped += "$name / ${entry.name.take(120)}: 지원하지 않는 항목 제외"
                                }
                            }
                        } finally { archive.delete() }
                    } else {
                        if (!CreatorFileRules.mediaName(name)) throw CreatorFileException("MP3·M4A·오디오 MP4·ZIP만 지원합니다.")
                        (context.contentResolver.openInputStream(uri) ?: throw CreatorFileException("파일을 열 수 없습니다.")).use {
                            copyMedia(it, name, name)
                        }
                    }
                    scope.ensureActive()
                    added += batch
                    total += batch.sumOf { it.bytes }
                    notices += skipped
                } catch (e: Exception) {
                    batch.forEach(store::delete)
                    if (e is CancellationException) throw e
                    notices += "$name: ${if (e is CreatorFileException) e.message else "손상되었거나 읽을 수 없는 파일입니다."}"
                }
            }
            scope.ensureActive()
            if (uris.size > CreatorFileRules.MAX_DRAFTS) notices += "한 번에 20개 입력 파일까지 선택할 수 있습니다."
            returned = true
            return CreatorImportResult(added, notices.take(50))
        } finally {
            // Covers cancellation between input files, not only cancellation during a copy.
            if (!returned) added.forEach(store::delete)
        }
    }

    private fun inspect(file: File, id: String, name: String, source: String, copied: CreatorFileRules.Copied): CreatorDraft {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes")
                throw CreatorFileException("현재 버전에서는 오디오 파일만 업로드할 수 있습니다.")
            if (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != "yes")
                throw CreatorFileException("재생할 오디오 트랙이 없습니다.")
            if (name.codePointCount(0, name.length) > 255) throw CreatorFileException("파일 이름은 255자 이하여야 합니다.")
            val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty()
            val expected = if (name.endsWith(".mp3", true)) "audio/mpeg" else "audio/mp4"
            if (mime != expected) throw CreatorFileException("확장자와 실제 미디어 형식이 일치하지 않습니다.")
            if (ms < 1 || ms > 1_200_000L) throw CreatorFileException("오디오는 최대 20분입니다.")
            file.setReadOnly()
            return CreatorDraft(fileId = id, name = name, source = source, bytes = copied.bytes, sha256 = copied.sha256,
                durationSeconds = ceil(ms / 1000.0).toInt(), isVideo = false, title = name.substringBeforeLast('.').take(100))
        } finally { retriever.release() }
    }
}
