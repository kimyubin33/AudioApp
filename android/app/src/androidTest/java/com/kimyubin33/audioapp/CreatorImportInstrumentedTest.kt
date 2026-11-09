package com.kimyubin33.audioapp

import android.content.ContentValues
import android.content.ContextWrapper
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.kimyubin33.audioapp.data.creator.CreatorDraft
import com.kimyubin33.audioapp.data.creator.CreatorDraftStore
import com.kimyubin33.audioapp.data.creator.CreatorFileImporter
import com.kimyubin33.audioapp.data.creator.CreatorFileRules
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 실제 Android 코덱·ContentResolver·ZIP·UniFFI를 함께 검증한다. 서비스 DB나 사용자 초안은 쓰지 않는다. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class CreatorImportInstrumentedTest {
    @Test fun incompleteDraftRestoresAndNativeValidationRejectsMissingRightsOrBadCoordinates() {
        val draft = CreatorDraft(name = "test.m4a", bytes = 100, sha256 = "a".repeat(64), durationSeconds = 2,
            title = "AI 오디오", aiTool = "test", holder = "tester", rightsConfirmed = true,
            personConsentConfirmed = true, aiTermsConfirmed = true, previewConfirmed = true)
        val restored = CreatorDraft.fromJson(draft.toJson())
        assertNull(restored.validationMessage())
        assertNotNull(restored.copy(rightsConfirmed = false).validationMessage())
        assertNotNull(restored.copy(placeEnabled = true, placeName = "공원", latitude = "91", longitude = "127", placeConfirmed = true).validationMessage())
        val incomplete = draft.copy(placeEnabled = true, latitude = "NaN", longitude = "")
        assertNotNull(CreatorDraft.fromJson(incomplete.toJson()).validationMessage())
        assertNull(restored.copy(attribution = "첫째 줄\n둘째 줄").validationMessage())
    }

    @Test fun importsMultipleRealMediaArchivesAndRejectsTraversalWithoutLosingOtherFiles() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(base.cacheDir, "creator-test-" + UUID.randomUUID()).apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getNoBackupFilesDir(): File = sandbox }
        val store = CreatorDraftStore(context)
        val uris = mutableListOf<Uri>()
        fun publish(file: File, name: String, mime: String): Uri {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AudioApp-tests")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = checkNotNull(base.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
            uris += uri
            base.contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            base.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        }
        fun archive(name: String, entries: List<String>, media: File): File =
            File(sandbox, name).also { zip ->
                ZipOutputStream(zip.outputStream()).use { out ->
                    entries.forEach { path -> out.putNextEntry(ZipEntry(path)); media.inputStream().use { it.copyTo(out) }; out.closeEntry() }
                }
            }
        try {
            val media = File(sandbox, "fixture.m4a")
            encodeSilentAudio(media)
            val zip1 = publish(archive("one.zip", listOf("album/first.m4a", "album/second.m4a"), media), "audioapp-one.zip", "application/zip")
            val zip2 = publish(archive("two.zip", listOf("third.m4a"), media), "audioapp-two.zip", "application/zip")
            val bad = publish(archive("bad.zip", listOf("../escape.m4a"), media), "audioapp-bad.zip", "application/zip")
            val result = CreatorFileImporter(context, store).import(listOf(zip1, zip2, bad), emptyList()) {}
            assertEquals(3, result.drafts.size)
            assertTrue(result.notices.isNotEmpty())
            val expected = MessageDigest.getInstance("SHA-256").digest(media.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
            result.drafts.forEach { d ->
                assertFalse(d.isVideo)
                assertTrue(d.durationSeconds in 1..3)
                assertEquals(expected, d.sha256)
                assertTrue(CreatorFileRules.verify(store.file(d.fileId!!), d.bytes, expected))
            }
            assertFalse(File(sandbox, "escape.m4a").exists())
            store.save(result.drafts)
            assertEquals(result.drafts.map { it.sha256 }, store.load().map { it.sha256 })
        } finally {
            uris.forEach { base.contentResolver.delete(it, null, null) }
            sandbox.deleteRecursively()
        }
    }

    private fun encodeSilentAudio(file: File) {
        val codec = MediaCodec.createEncoderByType("audio/mp4a-latm")
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            codec.configure(MediaFormat.createAudioFormat("audio/mp4a-latm", 44100, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 64000)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            var sent = 0
            var inputDone = false
            var outputDone = false
            var track = -1
            val info = MediaCodec.BufferInfo()
            val deadline = System.nanoTime() + 20_000_000_000L
            while (!outputDone) {
                check(System.nanoTime() < deadline) { "AAC fixture encoder timeout" }
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = checkNotNull(codec.getInputBuffer(index))
                        buffer.clear()
                        val count = minOf(1024, 44100 - sent, buffer.remaining() / 2)
                        buffer.put(ByteArray(count * 2))
                        val pts = sent * 1_000_000L / 44100
                        sent += count
                        inputDone = sent >= 44100
                        codec.queueInputBuffer(index, 0, count * 2, pts, if (inputDone) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    }
                }
                when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start(); started = true }
                    else -> if (index >= 0) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0) {
                            check(started)
                            val buffer = checkNotNull(codec.getOutputBuffer(index))
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buffer, info)
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            }
        } finally {
            codec.stop(); codec.release()
            if (started) muxer.stop()
            muxer.release()
        }
    }
}
