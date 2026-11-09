package com.kimyubin33.audioapp

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.kimyubin33.audioapp.data.memory.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion=29)
class MemoryAlbumInstrumentedTest {
    private fun sandbox(base: Context,root: File): Context = object: ContextWrapper(base) {
        override fun getNoBackupFilesDir(): File = root
    }
    private fun publish(base: Context,file: File): Uri {
        val values=ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,"album-test-"+UUID.randomUUID()+".jpg")
            put(MediaStore.MediaColumns.MIME_TYPE,"image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/AudioApp-tests")
            put(MediaStore.MediaColumns.IS_PENDING,1)
        }
        val uri=checkNotNull(base.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
        base.contentResolver.openOutputStream(uri)!!.use { out->file.inputStream().use { it.copyTo(out) } }
        base.contentResolver.update(uri,ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING,0) },null,null)
        return uri
    }

    @Test fun photoImportAppliesOrientationStripsGpsAndRestoresDraftAndPrivateAlbum() {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(base.cacheDir,"memory-test-"+UUID.randomUUID()).apply { mkdirs() }
        var uri: Uri?=null
        try {
            val source=File(root,"source.jpg")
            val bitmap=Bitmap.createBitmap(80,40,Bitmap.Config.ARGB_8888)
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,90,it) }
            bitmap.recycle()
            ExifInterface(source.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION,"6")
                setAttribute(ExifInterface.TAG_GPS_LATITUDE,"37/1,30/1,0/1")
                setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF,"N")
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE,"127/1,0/1,0/1")
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF,"E")
                saveAttributes()
            }
            uri=publish(base,source)
            val store=MemoryStore(sandbox(base,root))
            val photo=store.importPhoto(uri)
            val output=store.photo(photo)
            val info=BitmapFactory.Options().apply { inJustDecodeBounds=true }
            BitmapFactory.decodeFile(output.absolutePath,info)
            assertEquals(40,info.outWidth)
            assertEquals(80,info.outHeight)
            val exif=ExifInterface(output.absolutePath)
            assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
            assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE))
            // 다시 인코딩한 픽셀이 이미 정방향이다. EXIF 없는 JPEG는 0(undefined) 또는 1(normal)이다.
            assertTrue(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,0) in 0..1)
            assertNotNull(ExifInterface(source.absolutePath).getAttribute(ExifInterface.TAG_GPS_LATITUDE))

            val draft=MemoryDraft(title="그날의 바다",body="원래 기억",place="공개 해변",photoId=photo,
                candidate="비교 중인 표현",previousBody="되돌릴 글")
            val album=MemoryAlbum(draft)
            store.save(album)
            assertEquals(album,MemoryStore(sandbox(base,root)).load())
            val memory=PlaceMemory(draft.id,draft.title,draft.body,draft.place,photo,123L)
            val saved=MemoryAlbum(memories=listOf(memory))
            store.save(saved)
            assertEquals(saved,store.load())
            assertTrue(output.exists())
            store.save(saved.copy(memories=emptyList()))
            assertFalse(output.exists())
            assertTrue(source.exists())
        } finally {
            uri?.let { base.contentResolver.delete(it,null,null) }
            root.deleteRecursively()
        }
    }

    @Test fun corruptIndexAndTraversalAreRejectedWithoutErasingExistingData() {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(base.cacheDir,"memory-test-"+UUID.randomUUID()).apply { mkdirs() }
        try {
            val store=MemoryStore(sandbox(base,root))
            val album=MemoryAlbum(MemoryDraft(title="소중한 글",body="유지할 기억"))
            store.save(album)
            val index=File(root,"memory-album/album.json")
            val corrupt=JSONObject(index.readText()).apply { getJSONObject("draft").put("photo","../../outside") }.toString()
            index.writeText(corrupt)
            assertThrows(IllegalArgumentException::class.java) { store.load() }
            assertEquals(corrupt,index.readText())
            assertThrows(IllegalArgumentException::class.java) { store.photo("../outside") }
        } finally { root.deleteRecursively() }
    }

    @Test fun oversizedPhotoIsRejectedAndExistingAlbumIsUnchanged() {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(base.cacheDir,"memory-test-"+UUID.randomUUID()).apply { mkdirs() }
        var uri: Uri?=null
        try {
            val store=MemoryStore(sandbox(base,root))
            val album=MemoryAlbum(MemoryDraft(title="기존 초안",body="이 글은 그대로"))
            store.save(album)
            val large=File(root,"too-large.jpg")
            large.outputStream().use { out-> repeat(321) { out.write(ByteArray(32768)) } }
            uri=publish(base,large)
            assertThrows(IllegalArgumentException::class.java) { store.importPhoto(uri) }
            assertEquals(album,store.load())
        } finally {
            uri?.let { base.contentResolver.delete(it,null,null) }
            root.deleteRecursively()
        }
    }
}
