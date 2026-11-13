package com.kimyubin33.audioapp.data.creator

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** Validate the complete ZIP before opening any media. Names never become output paths. */
object CreatorZipArchive {
    data class Entry(val name: String, val size: Long, val packed: Long, val crc: Long,
                     val offset: Long, val flags: Int, val method: Int) {
        val directory get() = name.endsWith("/")
    }
    private fun invalid(): Nothing = throw CreatorFileException("ZIP 구조·체크섬 또는 항목 정보가 손상되었습니다.")
    private fun ByteArray.u16(at: Int) = ByteBuffer.wrap(this, at, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
    private fun ByteArray.u32(at: Int) = ByteBuffer.wrap(this, at, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
    private fun RandomAccessFile.bytes(offset: Long, count: Int): ByteArray {
        if (offset < 0 || count < 0 || offset + count > length()) invalid()
        seek(offset)
        return ByteArray(count).also(::readFully)
    }
    private fun checkExtra(bytes: ByteArray) {
        var pos = 0
        while (pos < bytes.size) {
            if (pos + 4 > bytes.size) invalid()
            val id = bytes.u16(pos)
            val end = pos + 4 + bytes.u16(pos + 2)
            if (end > bytes.size || id == 1) invalid() // ZIP64 is deliberately unsupported.
            pos = end
        }
    }
    private fun decode(bytes: ByteArray) = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

    fun entries(file: File, checkCancelled: () -> Unit = {}): List<Entry> = RandomAccessFile(file, "r").use { input ->
        if (input.length() !in 22..CreatorFileRules.MAX_ARCHIVE_BYTES) invalid()
        val tailStart = maxOf(0, input.length() - 65557)
        val tail = input.bytes(tailStart, (input.length() - tailStart).toInt())
        val end = (tail.size - 22 downTo 0).firstOrNull {
            tail.u32(it) == 0x06054b50L && it + 22 + tail.u16(it + 20) == tail.size
        } ?: invalid()
        val count = tail.u16(end + 10)
        val centralSize = tail.u32(end + 12)
        val centralOffset = tail.u32(end + 16)
        if (tail.u16(end + 4) != 0 || tail.u16(end + 6) != 0 || count != tail.u16(end + 8) ||
            count !in 1..CreatorFileRules.MAX_ENTRIES || centralSize > 2 * 1024 * 1024 ||
            centralOffset + centralSize != tailStart + end) invalid()
        val central = input.bytes(centralOffset, centralSize.toInt())
        val result = mutableListOf<Entry>()
        val names = mutableSetOf<String>()
        var pos = 0
        var total = 0L
        repeat(count) {
            checkCancelled()
            if (pos + 46 > central.size || central.u32(pos) != 0x02014b50L) invalid()
            val nameSize = central.u16(pos + 28)
            val next = pos + 46 + nameSize + central.u16(pos + 30) + central.u16(pos + 32)
            if (next > central.size || central.u16(pos + 34) != 0) invalid()
            val name = decode(central.copyOfRange(pos + 46, pos + 46 + nameSize))
            checkExtra(central.copyOfRange(pos + 46 + nameSize, pos + 46 + nameSize + central.u16(pos + 30)))
            val entry = Entry(name, central.u32(pos + 24), central.u32(pos + 20), central.u32(pos + 16),
                central.u32(pos + 42), central.u16(pos + 8), central.u16(pos + 10))
            CreatorFileRules.checkZipEntry(name, entry.size, entry.packed)
            if (!names.add(CreatorFileRules.zipPathKey(name))) throw CreatorFileException("ZIP에 중복 경로가 있습니다.")
            val unixType = ((central.u32(pos + 38) shr 16) and 0xf000).toInt()
            if (entry.flags and 0x080e.inv() != 0 || entry.method !in listOf(0, 8) ||
                unixType !in listOf(0, 0x8000, 0x4000) || entry.offset == 0xffffffffL ||
                entry.packed == 0xffffffffL || (entry.directory && entry.size != 0L) ||
                (unixType == 0x4000 && !entry.directory)) invalid()
            total += entry.size
            if (total > CreatorFileRules.MAX_IMPORT_BYTES) throw CreatorFileException("ZIP 전체 해제 크기는 360MiB 이하입니다.")
            result += entry
            pos = next
        }
        if (pos != central.size) invalid()
        val ranges = mutableListOf<LongRange>()
        for (entry in result) {
            checkCancelled()
            val header = input.bytes(entry.offset, 30)
            if (header.u32(0) != 0x04034b50L || header.u16(6) != entry.flags || header.u16(8) != entry.method) invalid()
            val nameSize = header.u16(26)
            if (decode(input.bytes(entry.offset + 30, nameSize)) != entry.name) invalid()
            checkExtra(input.bytes(entry.offset + 30 + nameSize, header.u16(28)))
            val start = entry.offset + 30 + nameSize + header.u16(28)
            var stop = start + entry.packed
            if (stop > centralOffset) invalid()
            if (entry.flags and 8 == 0) {
                if (header.u32(14) != entry.crc || header.u32(18) != entry.packed || header.u32(22) != entry.size) invalid()
            } else {
                val unsigned = input.bytes(stop, 12)
                val signed = if (stop + 16 <= centralOffset) input.bytes(stop, 16) else null
                stop += when {
                    signed != null && signed.u32(0) == 0x08074b50L && signed.u32(4) == entry.crc &&
                        signed.u32(8) == entry.packed && signed.u32(12) == entry.size -> 16
                    unsigned.u32(0) == entry.crc && unsigned.u32(4) == entry.packed && unsigned.u32(8) == entry.size -> 12
                    else -> invalid()
                }
            }
            if (stop > centralOffset || ranges.any { entry.offset <= it.last && stop > it.first }) invalid()
            ranges += entry.offset until stop
        }
        result
    }

    /** Read every entry, including skipped files, with actual size/CRC checks and cancellation. */
    fun visit(file: File, entries: List<Entry>, checkCancelled: () -> Unit, consume: (Entry, InputStream) -> Unit) {
        ZipFile(file, Charsets.UTF_8).use { zip ->
            for (entry in entries) {
                checkCancelled()
                val item = zip.getEntry(entry.name) ?: invalid()
                zip.getInputStream(item).use { raw ->
                    val crc = CRC32()
                    var size = 0L
                    val checked = object : InputStream() {
                        override fun read(): Int {
                            val b = ByteArray(1)
                            return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 255
                        }
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            checkCancelled()
                            val n = raw.read(b, off, len)
                            if (n > 0) {
                                size += n
                                if (size > entry.size || size > CreatorFileRules.MAX_FILE_BYTES) invalid()
                                crc.update(b, off, n)
                            }
                            return n
                        }
                    }
                    consume(entry, checked)
                    // Ensure a callback cannot skip validation by returning before EOF.
                    val buffer = ByteArray(64 * 1024)
                    while (checked.read(buffer) >= 0) { /* bounded drain */ }
                    checkCancelled()
                    if (size != entry.size || crc.value != entry.crc) invalid()
                }
            }
        }
    }
}
