package com.kimyubin33.audioapp.data.creator

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.text.Normalizer
import java.util.Locale

class CreatorFileException(message: String) : Exception(message)

object CreatorFileRules {
    const val MAX_FILE_BYTES = 90L * 1024 * 1024
    const val MAX_IMPORT_BYTES = 360L * 1024 * 1024
    const val MAX_ARCHIVE_BYTES = 180L * 1024 * 1024
    const val MAX_DRAFTS = 20
    const val MAX_ENTRIES = 200

    fun mediaName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in setOf("mp3", "m4a", "mp4")

    fun zipPathKey(name: String): String = Normalizer.normalize(name.removeSuffix("/"), Normalizer.Form.NFC).lowercase(Locale.ROOT)

    fun checkZipEntry(name: String, size: Long, compressedSize: Long) {
        // ZIP 경로는 표시용으로만 사용한다. 실제 저장 경로는 항상 앱이 만든 UUID다.
        if (name.isEmpty() || name.length > 1024 || name.startsWith("/") || name.contains('\\') ||
            name.contains(':') || name.any { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() } ||
            name.removeSuffix("/").split('/').any { it.isEmpty() || it == ".." || it == "." || it.trim() != it }) throw CreatorFileException("안전하지 않은 ZIP 경로입니다.")
        if (size < 0 || size > MAX_FILE_BYTES || compressedSize < 0 ||
            (size > 0 && (compressedSize == 0L || size > compressedSize.coerceAtLeast(1) * 100))) {
            throw CreatorFileException("ZIP 항목의 크기 또는 압축률이 허용 범위를 벗어났습니다.")
        }
    }

    data class Copied(val bytes: Long, val sha256: String, val crc: Long)

    /** 선언된 파일 크기를 믿지 않고 실제 읽은 바이트에 상한을 적용한다. */
    fun copy(input: InputStream, target: File, maxBytes: Long, checkCancelled: () -> Unit = {}): Copied {
        val digest = MessageDigest.getInstance("SHA-256")
        val crc = CRC32()
        var size = 0L
        try {
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkCancelled()
                    val n = input.read(buffer)
                    if (n < 0) break
                    size += n
                    if (size > maxBytes) throw CreatorFileException("파일 또는 전체 초안의 크기 상한을 초과했습니다.")
                    output.write(buffer, 0, n)
                    digest.update(buffer, 0, n)
                    crc.update(buffer, 0, n)
                }
            }
            checkCancelled()
            if (size == 0L) throw CreatorFileException("빈 파일은 올릴 수 없습니다.")
            return Copied(size, digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }, crc.value)
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }

    fun verify(file: File, bytes: Long, expectedHash: String, checkCancelled: () -> Unit = {}): Boolean {
        if (!file.isFile || file.length() != bytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkCancelled()
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == expectedHash
    }
}
