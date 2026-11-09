package com.kimyubin33.audioapp.data.creator

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import java.io.File

/** 원본과 초안은 백업되지 않는 앱 전용 저장소에 둔다. 서버 토큰/업로드 URL은 저장하지 않는다. */
class CreatorDraftStore(context: Context) {
    private val root = File(context.noBackupFilesDir, "creator-drafts").apply { mkdirs() }
    private val manifest = AtomicFile(File(root, "drafts.json"))
    private val uuid = Regex("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")

    fun file(id: String): File {
        require(uuid.matches(id))
        return File(root, id)
    }

    fun load(): List<CreatorDraft> {
        if (!manifest.baseFile.exists()) return emptyList()
        return manifest.openRead().use { input ->
            val raw = input.readBytes()
            require(raw.size <= 1024 * 1024)
            val a = JSONArray(raw.toString(Charsets.UTF_8))
            require(a.length() <= CreatorFileRules.MAX_DRAFTS)
            (0 until a.length()).map { CreatorDraft.fromJson(a.getJSONObject(it)) }.also { drafts ->
                require(drafts.all { uuid.matches(it.id) && (it.fileId == null || uuid.matches(it.fileId)) })
            }
        }
    }

    fun save(drafts: List<CreatorDraft>) {
        val stream = manifest.startWrite()
        try {
            stream.write(JSONArray(drafts.map { it.toJson() }).toString().toByteArray(Charsets.UTF_8))
            manifest.finishWrite(stream)
        } catch (e: Exception) {
            manifest.failWrite(stream)
            throw e
        }
    }

    fun delete(draft: CreatorDraft) { draft.fileId?.let { file(it).delete() } }

    fun clearOrphans(drafts: List<CreatorDraft>) {
        val keep = drafts.mapNotNull { it.fileId }.toSet()
        root.listFiles()?.filter { uuid.matches(it.name) && it.name !in keep }?.forEach { it.delete() }
    }
}
