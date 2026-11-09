package com.kimyubin33.audioapp.data.memory

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

data class MemoryDraft(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val body: String = "",
    val place: String = "",
    val photoId: String? = null,
    val candidate: String = "",
    val previousBody: String? = null,
) {
    val hasContent: Boolean get() = title.isNotBlank() || body.isNotBlank() || photoId != null
    val canSave: Boolean get() = title.isNotBlank() && (body.isNotBlank() || photoId != null)
}

data class PlaceMemory(
    val id: String, val title: String, val body: String, val place: String,
    val photoId: String?, val savedAt: Long,
)
data class MemoryAlbum(val draft: MemoryDraft = MemoryDraft(), val memories: List<PlaceMemory> = emptyList())

/** 기기 전용 비공개 앨범. 원본 URI·EXIF·현재 좌표는 저장하지 않는다. 모든 호출은 IO 디스패처에서 직렬화한다. */
class MemoryStore(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val root = File(context.noBackupFilesDir, "memory-album").apply { check(mkdirs() || isDirectory) }
    private val index = AtomicFile(File(root, "album.json"))

    fun photo(id: String): File {
        require(ID.matches(id))
        return File(root, "$id.jpg")
    }

    fun load(): MemoryAlbum {
        if (!index.baseFile.exists() && !File(root, "album.json.bak").exists()) return MemoryAlbum()
        val text = index.openRead().use { input ->
            check(input.available() <= 2 * 1024 * 1024) { "앨범 색인이 너무 큽니다." }
            input.reader(Charsets.UTF_8).readText()
        }
        val data = JSONObject(text)
        check(data.getInt("version") == 1) { "지원하지 않는 앨범 버전입니다." }
        val d = data.getJSONObject("draft")
        val draft = MemoryDraft(id(d.getString("id")), d.getString("title").limited(80),
            d.getString("body").limited(2000), d.getString("place").limited(80),
            optionalId(d, "photo"), d.getString("candidate").limited(2000),
            if(d.isNull("previous")) null else d.getString("previous").limited(2000))
        val entries = data.getJSONArray("memories")
        check(entries.length() <= MAX_MEMORIES)
        val memories = (0 until entries.length()).map { n ->
            val m = entries.getJSONObject(n)
            PlaceMemory(id(m.getString("id")),m.getString("title").limited(80),m.getString("body").limited(2000),
                m.getString("place").limited(80),optionalId(m,"photo"),m.getLong("savedAt"))
        }
        check(memories.map { it.id }.distinct().size == memories.size)
        return MemoryAlbum(draft, memories)
    }

    fun save(album: MemoryAlbum) {
        check(album.memories.size <= MAX_MEMORIES)
        val draft = album.draft
        val array = JSONArray()
        album.memories.forEach { m -> array.put(JSONObject().put("id",m.id).put("title",m.title).put("body",m.body)
            .put("place",m.place).put("photo",m.photoId ?: JSONObject.NULL).put("savedAt",m.savedAt)) }
        val data = JSONObject().put("version",1)
            .put("draft",JSONObject().put("id",draft.id).put("title",draft.title).put("body",draft.body)
                .put("place",draft.place).put("photo",draft.photoId ?: JSONObject.NULL).put("candidate",draft.candidate)
                .put("previous",draft.previousBody ?: JSONObject.NULL))
            .put("memories",array)
        val output = index.startWrite()
        try {
            output.write(data.toString().toByteArray(Charsets.UTF_8))
            index.finishWrite(output)
        } catch (e: Exception) { index.failWrite(output); throw e }
        // 색인을 확정한 뒤에만 참조가 사라진 사진을 정리한다.
        val referenced = (album.memories.mapNotNull { it.photoId } + listOfNotNull(draft.photoId)).toSet()
        root.listFiles()?.filter { it.extension == "jpg" && it.nameWithoutExtension !in referenced }?.forEach { it.delete() }
    }

    /** 실제 디코딩 가능한 JPEG/PNG/WebP만 받는다. 방향을 적용하고 다시 인코딩해 GPS 등 메타데이터를 제거한다. */
    fun importPhoto(uri: Uri): String {
        require(uri.scheme == "content") { "사진 선택기에서 사진을 골라 주세요." }
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            while(true) {
                val n = input.read(buffer)
                if(n < 0) break
                require(out.size() + n <= MAX_INPUT_BYTES) { "사진은 10MB 이하로 골라 주세요." }
                out.write(buffer,0,n)
            }
            out.toByteArray()
        } ?: error("사진을 열 수 없습니다.")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        require(bounds.outMimeType in setOf("image/jpeg","image/png","image/webp") &&
            bounds.outWidth > 0 && bounds.outHeight > 0 &&
            bounds.outWidth.toLong() * bounds.outHeight <= 40_000_000) { "40MP 이하 JPEG·PNG·WebP 사진을 골라 주세요." }
        var sample = 1
        while(maxOf(bounds.outWidth,bounds.outHeight) / sample > 1920) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply {
            inSampleSize=sample; inPreferredConfig=Bitmap.Config.ARGB_8888
        }) ?: error("손상된 사진입니다. 다른 사진을 골라 주세요.")
        val orientation = runCatching { ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION,1) }.getOrDefault(1)
        val matrix = Matrix().apply {
            when(orientation) {
                2 -> setScale(-1f,1f)
                3 -> setRotate(180f)
                4 -> setScale(1f,-1f)
                5 -> { setRotate(90f); postScale(-1f,1f) }
                6 -> setRotate(90f)
                7 -> { setRotate(-90f); postScale(-1f,1f) }
                8 -> setRotate(-90f)
            }
        }
        var oriented: Bitmap? = null
        val photoId = UUID.randomUUID().toString()
        val file = photo(photoId)
        try {
            val image = Bitmap.createBitmap(decoded,0,0,decoded.width,decoded.height,matrix,true).also { oriented=it }
            require((root.listFiles()?.filter { it.extension=="jpg" }?.sumOf { it.length() } ?: 0) < 200L*1024*1024) { "앨범 용량이 가득 찼습니다. 추억을 정리한 뒤 다시 골라 주세요." }
            file.outputStream().use { check(image.compress(Bitmap.CompressFormat.JPEG,90,it)) }
            return photoId
        } catch(e: Exception) { file.delete(); throw e }
        finally { if(oriented !== decoded) oriented?.recycle(); decoded.recycle() }
    }

    companion object {
        const val MAX_MEMORIES = 200
        private const val MAX_INPUT_BYTES = 10*1024*1024
        private val ID = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        private fun id(value: String): String = value.also { require(ID.matches(it)) }
        private fun String.limited(max: Int): String = also { require(length<=max) }
        private fun optionalId(json: JSONObject,key: String): String? = if(json.isNull(key)) null else id(json.getString(key))
    }
}
