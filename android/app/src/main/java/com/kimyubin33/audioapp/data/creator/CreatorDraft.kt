package com.kimyubin33.audioapp.data.creator

import org.json.JSONArray
import org.json.JSONObject
import uniffi.audio_core.CreatorContent
import uniffi.audio_core.validateCreatorMetadata
import java.util.UUID

data class CreatorDraft(
    val id: String = UUID.randomUUID().toString(),
    val fileId: String? = null,
    val name: String = "",
    val source: String = "",
    val bytes: Long = 0,
    val sha256: String = "",
    val durationSeconds: Int = 0,
    val isVideo: Boolean = false,
    val isPost: Boolean = false,
    val shortStyle: String = "",
    val aiGenerated: Boolean = true,
    val audioContentId: String = "",
    val audioTitle: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val title: String = "",
    val description: String = "",
    val tags: String = "",
    val aiTool: String = "",
    val holder: String = "",
    val license: String = "original",
    val attribution: String = "",
    val sourceUrl: String = "",
    val rightsConfirmed: Boolean = false,
    val personConsentConfirmed: Boolean = false,
    val aiTermsConfirmed: Boolean = false,
    val previewConfirmed: Boolean = false,
    val placeEnabled: Boolean = false,
    val placeName: String = "",
    val latitude: String = "",
    val longitude: String = "",
    val radius: String = "200",
    val character: String = "",
    val story: String = "",
    val placeConfirmed: Boolean = false,
    val uploadId: String? = null,
    val uploadOwner: String? = null,
    val submittedId: String? = null,
    val remoteId: String? = null,
    val revision: UInt = 1u,
) {
    val textPost: Boolean get() = isPost && bytes == 0L
    val contentType: String get() = if(isPost) { if(textPost) "" else "image/jpeg" } else if (isVideo) "video/mp4" else if (name.endsWith(".mp3", true)) "audio/mpeg" else "audio/mp4"

    fun metadata(): String = JSONObject()
        .put("title", title.trim()).put("kind", if(isPost) "post" else if (isVideo) "short_video" else "audio")
        .put("short_style",shortStyle.takeIf { isVideo && it.isNotBlank() } ?: JSONObject.NULL)
        .put("duration_seconds", durationSeconds).put("ai_generated", aiGenerated)
        .put("ai_tool", aiTool.trim().ifBlank { null } ?: JSONObject.NULL)
        .put("content_type", contentType.ifBlank { null } ?: JSONObject.NULL)
        .put("submission", JSONObject().put("version", 1).put("description", description.trim())
            .put("tags", JSONArray(tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }))
            .put("audio_content_id",audioContentId.ifBlank { null } ?: JSONObject.NULL)
            .put("file", if(textPost) JSONObject.NULL else JSONObject().put("name", name).put("size", bytes).put("sha256", sha256))
            .put("rights", JSONObject().put("holder", holder.trim()).put("license", license)
                .put("attribution", attribution.trim()).put("source_url", sourceUrl.trim())
                .put("distribution_confirmed", rightsConfirmed).put("person_consent_confirmed", personConsentConfirmed)
                .put("ai_terms_confirmed", aiTermsConfirmed))
            .put("location", if (!placeEnabled) JSONObject.NULL else JSONObject()
                .put("name", placeName.trim()).put("latitude", latitude.toDoubleOrNull()?.takeIf { it.isFinite() } ?: JSONObject.NULL)
                .put("longitude", longitude.toDoubleOrNull()?.takeIf { it.isFinite() } ?: JSONObject.NULL)
                .put("radius_meters", radius.toIntOrNull() ?: JSONObject.NULL).put("character", character.trim())
                .put("story", story.trim()).put("public_location_confirmed", placeConfirmed)))
        .toString()

    fun validationMessage(): String? {
        if (submittedId != null) return "이미 검수를 요청한 초안입니다."
        if (title.trim().isEmpty()) return "제목을 입력해 주세요."
        if(isPost && description.trim().isEmpty()) return "게시글 내용을 입력해 주세요."
        if(isVideo && shortStyle !in setOf("story","moment")) return "쇼츠의 Story / Moment를 선택해 주세요."
        if (aiGenerated && aiTool.trim().isEmpty()) return "사용한 AI 도구를 입력해 주세요."
        if (!previewConfirmed) return "파일을 미리 재생하고 최종 파일 확인에 체크해 주세요."
        if (holder.trim().isEmpty()) return "권리자 또는 업로드 권한자의 이름을 입력해 주세요."
        if (license != "original" && attribution.trim().isEmpty()) return "허락·라이선스·퍼블릭 도메인의 근거와 출처를 적어 주세요."
        if (!rightsConfirmed || !personConsentConfirmed || !aiTermsConfirmed) return "저작권·초상/음성·사용한 도구의 약관을 각각 확인해 주세요."
        if (placeEnabled && (!placeConfirmed || placeName.isBlank() || latitude.toDoubleOrNull() == null || longitude.toDoubleOrNull() == null)) {
            return "장소 이름·위도·경도와 공개 장소 확인을 입력해 주세요."
        }
        return try { validateCreatorMetadata(metadata()); null } catch (_: Exception) { "입력 길이·태그 중복·출처 HTTPS 주소·장소 좌표와 반경(50~5,000m)을 확인해 주세요." }
    }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("file_id", fileId ?: JSONObject.NULL).put("source", source)
        .put("audio_title",audioTitle).put("metadata", JSONObject(metadata())).put("width", width).put("height", height)
        .put("preview_confirmed", previewConfirmed).put("upload_id", uploadId ?: JSONObject.NULL)
        .put("upload_owner", uploadOwner ?: JSONObject.NULL).put("submitted_id", submittedId ?: JSONObject.NULL)
        .put("remote_id", remoteId ?: JSONObject.NULL).put("revision", revision.toLong())

    companion object {
        fun fromJson(j: JSONObject): CreatorDraft {
            val m = j.getJSONObject("metadata")
            val s = m.getJSONObject("submission")
            val f = s.optJSONObject("file")
            val r = s.getJSONObject("rights")
            val p = s.optJSONObject("location")
            fun nullable(key: String) = if (j.isNull(key)) null else j.optString(key).ifBlank { null }
            return CreatorDraft(
                id = j.getString("id"), fileId = nullable("file_id"), source = j.optString("source"),
                name = f?.getString("name").orEmpty(), bytes = f?.getLong("size") ?: 0, sha256 = f?.getString("sha256").orEmpty(),
                isPost=m.getString("kind")=="post",shortStyle=if(m.isNull("short_style")) "" else m.optString("short_style"),
                aiGenerated=m.optBoolean("ai_generated",true),audioContentId=if(s.isNull("audio_content_id")) "" else s.optString("audio_content_id"),audioTitle=j.optString("audio_title"),
                durationSeconds = m.getInt("duration_seconds"), isVideo = m.getString("kind") == "short_video",
                width = j.optInt("width"), height = j.optInt("height"), title = m.getString("title"),
                description = s.optString("description"), tags = s.getJSONArray("tags").let { a -> (0 until a.length()).joinToString(", ") { a.getString(it) } },
                aiTool = if (m.isNull("ai_tool")) "" else m.optString("ai_tool"), holder = r.optString("holder"), license = r.optString("license", "original"),
                attribution = r.optString("attribution"), sourceUrl = r.optString("source_url"),
                rightsConfirmed = r.optBoolean("distribution_confirmed"), personConsentConfirmed = r.optBoolean("person_consent_confirmed"),
                aiTermsConfirmed = r.optBoolean("ai_terms_confirmed"), previewConfirmed = j.optBoolean("preview_confirmed"),
                placeEnabled = p != null, placeName = p?.optString("name").orEmpty(), latitude = p?.optString("latitude").orEmpty(),
                longitude = p?.optString("longitude").orEmpty(), radius = p?.optString("radius_meters") ?: "200",
                character = p?.optString("character").orEmpty(), story = p?.optString("story").orEmpty(), placeConfirmed = p?.optBoolean("public_location_confirmed") == true,
                uploadId = nullable("upload_id"), uploadOwner = nullable("upload_owner"), submittedId = nullable("submitted_id"),
                remoteId = nullable("remote_id"), revision = j.optLong("revision", 1).toUInt(),
            )
        }

        fun fromRemote(c: CreatorContent): CreatorDraft? {
            val submission = c.submissionJson ?: return null
            val m = JSONObject().put("title", c.title).put("kind", c.kind).put("duration_seconds", c.durationSeconds.toLong())
                .put("ai_generated",c.aiGenerated).put("short_style",c.shortStyle ?: JSONObject.NULL)
                .put("ai_tool", c.aiTool ?: JSONObject.NULL).put("submission", JSONObject(submission))
            return fromJson(JSONObject().put("id", UUID.randomUUID().toString()).put("metadata", m).put("remote_id", c.id)
                .put("revision", c.revision.toLong()).put("preview_confirmed", true))
                .copy(rightsConfirmed = false, personConsentConfirmed = false, aiTermsConfirmed = false, placeConfirmed = false)
        }
    }
}
