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
import java.util.zip.ZipInputStream
import kotlin.math.ceil

data class CreatorImportResult(val drafts: List<CreatorDraft>, val notices: List<String>)

class CreatorFileImporter(private val context: Context, private val store: CreatorDraftStore) {
    suspend fun import(uris: List<Uri>, existing: List<CreatorDraft>, progress: (String) -> Unit): CreatorImportResult {
        val scope = currentCoroutineContext()
        val added = mutableListOf<CreatorDraft>()
        val notices = mutableListOf<String>()
        var total = existing.sumOf { if (it.fileId != null) it.bytes else 0L }
        fun remaining() = CreatorFileRules.MAX_IMPORT_BYTES - total
        fun room() {
            if (existing.size + added.size >= CreatorFileRules.MAX_DRAFTS) throw CreatorFileException("초안은 최대 20개입니다. 완료한 초안을 정리해 주세요.")
        }
        for (uri in uris.take(CreatorFileRules.MAX_DRAFTS)) {
            scope.ensureActive()
            var name = "선택한 파일"
            val batch = mutableListOf<CreatorDraft>()
            try {
                room()
                require(uri.scheme == "content")
                name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }?.substringAfterLast('/')?.substringAfterLast('\\')?.filterNot { it.isISOControl() }?.take(255)?.ifBlank { null } ?: "파일"
                progress("$name 확인 중")
                if (name.endsWith(".zip", true)) {
                    val input = context.contentResolver.openInputStream(uri) ?: throw CreatorFileException("파일을 열 수 없습니다.")
                    // ZIP 전체를 파일로 풀거나 메모리에 올리지 않고 항목별로 한도를 적용한다.
                    var compressedRead = 0L
                    val boundedInput = object : java.io.FilterInputStream(input) {
                        override fun read(): Int { val n = super.read(); if (n >= 0) count(1); return n }
                        override fun read(b: ByteArray, off: Int, len: Int): Int { val n = `in`.read(b, off, len); if (n > 0) count(n); return n }
                        fun count(n: Int) { compressedRead += n; if (compressedRead > CreatorFileRules.MAX_ARCHIVE_BYTES) throw CreatorFileException("ZIP은 최대 180MiB입니다.") }
                    }
                    ZipInputStream(boundedInput.buffered()).use { zip ->
                        var count = 0
                        val names = mutableSetOf<String>()
                        while (true) {
                            scope.ensureActive()
                            val entry = zip.nextEntry ?: break
                            if (++count > CreatorFileRules.MAX_ENTRIES) throw CreatorFileException("ZIP은 최대 200개 항목까지 검사합니다.")
                            CreatorFileRules.checkZipEntry(entry.name, 0, 1)
                            if (!names.add(entry.name)) throw CreatorFileException("ZIP에 같은 경로가 중복되어 있습니다.")
                            if (entry.size > CreatorFileRules.MAX_FILE_BYTES) throw CreatorFileException("ZIP 내부 파일이 90MiB를 초과합니다.")
                            val media = !entry.isDirectory && CreatorFileRules.mediaName(entry.name)
                            if (media) room()
                            var scanned = 0L
                            val max = minOf(CreatorFileRules.MAX_FILE_BYTES, remaining())
                            if (media) {
                                val id = UUID.randomUUID().toString()
                                val target = store.file(id)
                                try {
                                    val copied = CreatorFileRules.copy(zip, target, max) { scope.ensureActive() }
                                    scanned = copied.bytes
                                    batch += inspect(target, id, entry.name.substringAfterLast('/'), "$name / ${entry.name}", copied)
                                    added += batch.last()
                                    total += scanned
                                } catch (e: Exception) { target.delete(); throw e }
                            } else {
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    scope.ensureActive()
                                    val n = zip.read(buffer)
                                    if (n < 0) break
                                    scanned += n
                                    if (scanned > max) throw CreatorFileException("ZIP 해제 크기 상한을 초과했습니다.")
                                }
                                total += scanned // 지원하지 않는 항목도 압축 폭탄 상한에 포함한다.
                                if (!entry.isDirectory) notices += "$name / ${entry.name.take(120)}: 지원하지 않는 항목 제외"
                            }
                            CreatorFileRules.checkZipEntry(entry.name, scanned, entry.compressedSize)
                            zip.closeEntry()
                        }
                        if (count == 0) throw CreatorFileException("비어 있거나 올바르지 않은 ZIP입니다.")
                    }
                } else {
                    if (!CreatorFileRules.mediaName(name)) throw CreatorFileException("MP3·M4A·MP4·ZIP만 지원합니다.")
                    val id = UUID.randomUUID().toString()
                    val target = store.file(id)
                    try {
                        val copied = (context.contentResolver.openInputStream(uri) ?: throw CreatorFileException("파일을 열 수 없습니다.")).use {
                            CreatorFileRules.copy(it, target, minOf(CreatorFileRules.MAX_FILE_BYTES, remaining())) { scope.ensureActive() }
                        }
                        batch += inspect(target, id, name, name, copied)
                        added += batch.last()
                        total += copied.bytes
                    } catch (e: Exception) { target.delete(); throw e }
                }
            } catch (e: Exception) {
                // 하나의 ZIP에서 실패하면 그 ZIP에서 나온 초안은 전부 되돌린다. 다른 파일은 유지한다.
                batch.forEach { store.delete(it); added.remove(it); total -= it.bytes }
                if (e is CancellationException) { added.forEach(store::delete); throw e }
                notices += "$name: ${if (e is CreatorFileException) e.message else "손상되었거나 읽을 수 없는 파일입니다."}"
            }
        }
        if (uris.size > CreatorFileRules.MAX_DRAFTS) notices += "한 번에 20개 입력 파일까지 선택할 수 있습니다."
        return CreatorImportResult(added, notices.take(50))
    }

    private fun inspect(file: File, id: String, name: String, source: String, copied: CreatorFileRules.Copied): CreatorDraft {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val video = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty()
            val expected = if (video) "video/mp4" else if (name.endsWith(".mp3", true)) "audio/mpeg" else "audio/mp4"
            if (mime != expected || (video && !name.endsWith(".mp4", true))) throw CreatorFileException("확장자와 실제 미디어 형식이 일치하지 않습니다.")
            val max = if (video) 180_000L else 14_400_000L
            if (ms < 1 || ms > max) throw CreatorFileException(if (video) "쇼츠는 최대 3분입니다." else "오디오는 최대 4시간입니다.")
            file.setReadOnly()
            return CreatorDraft(fileId = id, name = name, source = source, bytes = copied.bytes, sha256 = copied.sha256,
                durationSeconds = ceil(ms / 1000.0).toInt(), isVideo = video,
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0,
                title = name.substringBeforeLast('.').take(100))
        } finally { retriever.release() }
    }
}
