package com.kimyubin33.audioapp.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.AtomicFile
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class LocalProfile(val userId: String, val name: String = "", val avatarId: String? = null)

/** 서버가 확인한 계정 ID별 기기 프로필. 사진/이름은 서버나 백업으로 전송하지 않는다. IO에서만 호출한다. */
class LocalProfileStore(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val root = File(context.noBackupFilesDir, "profiles")

    private fun directory(userId: String): File {
        require(Regex("[A-Za-z0-9_-]{1,64}").matches(userId))
        val key = MessageDigest.getInstance("SHA-256").digest(userId.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(root, key)
    }

    fun deleteAccount(userId: String) {
        val dir = directory(userId)
        if (dir.exists()) check(dir.deleteRecursively())
    }

    fun avatar(profile: LocalProfile): File? = profile.avatarId?.let { id ->
        require(UUID_RE.matches(id))
        File(directory(profile.userId), "$id.jpg")
    }

    fun load(userId: String): LocalProfile {
        val file = AtomicFile(File(directory(userId), "profile.json"))
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return LocalProfile(userId)
        val raw = file.openRead().use { input ->
            require(input.available() <= 16 * 1024)
            input.reader(Charsets.UTF_8).readText()
        }
        val json = JSONObject(raw)
        require(json.getInt("version") == 1 && json.getString("userId") == userId)
        val name = json.getString("name")
        require(validName(name))
        val avatar = if (json.isNull("avatar")) null else json.getString("avatar").also { require(UUID_RE.matches(it)) }
        return LocalProfile(userId, name, avatar)
    }

    fun save(profile: LocalProfile) {
        require(validName(profile.name))
        profile.avatarId?.let { require(UUID_RE.matches(it) && avatar(profile)?.isFile == true) }
        val dir = directory(profile.userId).apply { check(mkdirs() || isDirectory) }
        val data = JSONObject().put("version", 1).put("userId", profile.userId)
            .put("name", profile.name.trim()).put("avatar", profile.avatarId ?: JSONObject.NULL).toString()
        val index = AtomicFile(File(dir, "profile.json"))
        val output = index.startWrite()
        try {
            output.write(data.toByteArray(Charsets.UTF_8))
            index.finishWrite(output)
        } catch (e: Exception) { index.failWrite(output); throw e }
        dir.listFiles()?.filter { it.extension == "jpg" && it.nameWithoutExtension != profile.avatarId }?.forEach { it.delete() }
    }

    /** 선택된 파일만 읽고 방향 보정·중앙 크롭 후 재인코딩한다. EXIF/GPS와 원본 URI는 보관하지 않는다. */
    fun updateAvatar(userId: String, uri: Uri): LocalProfile {
        require(uri.scheme == "content") { "사진 선택기에서 골라 주세요." }
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                require(out.size() + size <= 10 * 1024 * 1024) { "사진은 10MB 이하로 골라 주세요." }
                out.write(buffer, 0, size)
            }
            out.toByteArray()
        } ?: error("사진을 열지 못했습니다.")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outMimeType in setOf("image/jpeg", "image/png", "image/webp") &&
            bounds.outWidth > 0 && bounds.outHeight > 0 &&
            bounds.outWidth.toLong() * bounds.outHeight <= 40_000_000) { "JPG·PNG·WebP 사진을 골라 주세요." }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: error("사진을 읽지 못했습니다.")
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
        }.getOrDefault(1)
        val matrix = Matrix().apply {
            when (orientation) {
                2 -> setScale(-1f, 1f)
                3 -> setRotate(180f)
                4 -> setScale(1f, -1f)
                5 -> { setRotate(90f); postScale(-1f, 1f) }
                6 -> setRotate(90f)
                7 -> { setRotate(-90f); postScale(-1f, 1f) }
                8 -> setRotate(-90f)
            }
        }
        val bitmaps = mutableSetOf(decoded)
        val profile = load(userId)
        val id = UUID.randomUUID().toString()
        val dir = directory(userId).apply { check(mkdirs() || isDirectory) }
        val file = File(dir, "$id.jpg")
        try {
            val oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also(bitmaps::add)
            val side = minOf(oriented.width, oriented.height)
            val cropped = Bitmap.createBitmap(oriented, (oriented.width-side)/2, (oriented.height-side)/2, side, side).also(bitmaps::add)
            val dimension = minOf(512, side)
            val image = Bitmap.createScaledBitmap(cropped, dimension, dimension, true).also(bitmaps::add)
            file.outputStream().use { check(image.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            val updated = profile.copy(avatarId = id)
            save(updated)
            return updated
        } catch (e: Exception) { file.delete(); throw e }
        finally { bitmaps.forEach { it.recycle() } }
    }

    companion object {
        private val UUID_RE = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        fun validName(value: String): Boolean = value.length <= 40 && value.none { it.isISOControl() }
    }
}
