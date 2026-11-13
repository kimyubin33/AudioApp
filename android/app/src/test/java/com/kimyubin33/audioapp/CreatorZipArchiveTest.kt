package com.kimyubin33.audioapp

import com.kimyubin33.audioapp.data.creator.CreatorFileException
import com.kimyubin33.audioapp.data.creator.CreatorFileRules
import com.kimyubin33.audioapp.data.creator.CreatorZipArchive
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CreatorZipArchiveTest {
    @get:Rule val temp = TemporaryFolder()
    private val audio = ByteArray(128) { it.toByte() }

    private fun archive(entries: List<Pair<String, ByteArray>> = listOf("folder/a.mp3" to audio), stored: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                val e = ZipEntry(name)
                if (stored) {
                    e.method = ZipEntry.STORED
                    e.size = bytes.size.toLong()
                    e.compressedSize = bytes.size.toLong()
                    e.crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(e); zip.write(bytes); zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
    private fun file(bytes: ByteArray): File = temp.newFile().apply { writeBytes(bytes) }
    private fun ByteArray.view() = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
    private fun central(bytes: ByteArray): Int = (0..bytes.size - 4).first { bytes.view().getInt(it) == 0x02014b50 }
    private fun rejected(bytes: ByteArray) {
        assertThrows(Exception::class.java) {
            val f = file(bytes)
            CreatorZipArchive.visit(f, CreatorZipArchive.entries(f), {}) { _, _ -> }
        }
    }

    @Test fun storedAndDeflatedWithDataDescriptorRoundTripAndSkipNestedArchives() {
        for (stored in listOf(false, true)) {
            val f = file(archive(listOf("folder/" to byteArrayOf(), "folder/a.mp3" to audio, "nested.zip" to audio), stored))
            val entries = CreatorZipArchive.entries(f)
            val extracted = mutableListOf<ByteArray>()
            CreatorZipArchive.visit(f, entries, {}) { e, input ->
                if (CreatorFileRules.mediaName(e.name)) extracted += input.readBytes()
            }
            assertEquals(3, entries.size)
            assertEquals(1, extracted.size)
            assertArrayEquals(audio, extracted.single())
        }
    }

    @Test fun rejectsTruncatedEndLocalNameAndLocalSizeMismatch() {
        val good = archive(stored = true)
        rejected(good.copyOf(good.size - 22))
        rejected(good.copyOf().apply { this[30] = 'X'.code.toByte() })
        rejected(good.copyOf().apply { view().putInt(22, audio.size + 1) })
        // Deflated data descriptor must agree with the central directory.
        val deflated = archive()
        val descriptor = (0..deflated.size - 4).first { deflated.view().getInt(it) == 0x08074b50 }
        rejected(deflated.copyOf().apply { this[descriptor + 4] = (this[descriptor + 4].toInt() xor 1).toByte() })
    }

    @Test fun crcChecksIncludeSkippedFilesAndCallbacksCannotSkipValidation() {
        for (name in listOf("a.mp3", "notes.txt")) {
            val broken = archive(listOf(name to audio), stored = true)
            broken[30 + name.length + 10] = (broken[30 + name.length + 10].toInt() xor 1).toByte()
            rejected(broken) // callback does not read anything; the validator still drains/checks it.
        }
    }

    @Test fun rejectsCanonicalDuplicatesTraversalAndInvisiblePathCharacters() {
        for (names in listOf(
            listOf("a.mp3", "A.mp3"), listOf("é.mp3", "e\u0301.mp3"),
            listOf("a/", "a"), listOf("../a.mp3"), listOf("a//b.mp3"), listOf(" a.mp3"), listOf("a\u202e.mp3")
        )) rejected(archive(names.map { it to if (it.endsWith("/")) byteArrayOf() else audio }))
    }

    @Test fun rejectsEncryptedLinksZip64AndTooManyEntries() {
        val good = archive(stored = true)
        val c = central(good)
        rejected(good.copyOf().apply { view().putShort(c + 8, 1) })
        rejected(good.copyOf().apply { view().putInt(c + 38, 0xa000 shl 16) })
        rejected(good.copyOf().apply { view().putInt(c + 24, -1) })
        rejected(archive((0..200).map { "n$it.txt" to byteArrayOf() }, stored = true))
    }

    @Test fun totalExpandedLimitCountsUnsupportedEntriesBeforeDecompression() {
        val data = archive((0..4).map { "n$it.txt" to audio }, stored = true)
        for (i in 0..data.size - 46) if (data.view().getInt(i) == 0x02014b50) {
            data.view().putInt(i + 20, 1024 * 1024)
            data.view().putInt(i + 24, 80 * 1024 * 1024)
        }
        assertThrows(CreatorFileException::class.java) { CreatorZipArchive.entries(file(data)) }
    }

    @Test fun cancellationDuringReadStopsAndRemovesPartialCopy() {
        val f = file(archive(stored = true))
        val target = temp.newFile()
        var checks = 0
        assertThrows(CancellationException::class.java) {
            CreatorZipArchive.visit(f, CreatorZipArchive.entries(f), { if (++checks >= 3) throw CancellationException() }) { _, input ->
                CreatorFileRules.copy(input, target, 1024)
            }
        }
        assertFalse(target.exists())
        assertTrue(f.exists())
    }
}
