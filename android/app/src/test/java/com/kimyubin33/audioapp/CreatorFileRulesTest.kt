package com.kimyubin33.audioapp

import com.kimyubin33.audioapp.data.creator.CreatorFileException
import com.kimyubin33.audioapp.data.creator.CreatorFileRules
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

class CreatorFileRulesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun rejectsTraversalDuplicateSeparatorsAndCompressionBombs() {
        for (name in listOf("../track.mp3", "/track.mp3", "a/../../track.mp3", "a\\track.mp3", "C:/track.mp3", "a\u0000.mp3")) {
            assertThrows(CreatorFileException::class.java) { CreatorFileRules.checkZipEntry(name, 100, 100) }
        }
        assertThrows(CreatorFileException::class.java) { CreatorFileRules.checkZipEntry("a.mp3", 101_000, 100) }
        assertThrows(CreatorFileException::class.java) { CreatorFileRules.checkZipEntry("a.mp3", CreatorFileRules.MAX_FILE_BYTES + 1, 1000) }
        CreatorFileRules.checkZipEntry("album/한글 트랙.mp3", 1024, 1000)
    }

    @Test fun copyEnforcesActualByteLimitAndRemovesPartialFile() {
        val file = temp.newFile()
        assertThrows(CreatorFileException::class.java) {
            CreatorFileRules.copy(ByteArrayInputStream(ByteArray(101)), file, 100)
        }
        assertFalse(file.exists())
    }

    @Test fun exactHashDetectsChangedBytesEvenWhenSizeIsUnchanged() {
        val file = temp.newFile()
        val original = "abc".toByteArray()
        val copy = CreatorFileRules.copy(ByteArrayInputStream(original), file, 100)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", copy.sha256)
        assertEquals(3, copy.bytes.toInt())
        assertTrue(CreatorFileRules.verify(file, 3, copy.sha256))
        file.writeText("abd")
        assertFalse(CreatorFileRules.verify(file, 3, copy.sha256))
    }

    @Test fun cancellationCleansPartialCopyAndUnsupportedFilesAreNotMedia() {
        val file = temp.newFile()
        assertThrows(IllegalStateException::class.java) {
            CreatorFileRules.copy(ByteArrayInputStream(ByteArray(1000)), file, 2000) { error("cancelled") }
        }
        assertFalse(file.exists())
        assertTrue(CreatorFileRules.mediaName("track.MP3"))
        assertFalse(CreatorFileRules.mediaName("track.mp3.exe"))
        assertFalse(CreatorFileRules.mediaName("nested.zip"))
    }
}
