package com.kimyubin33.audioapp

import android.content.ContentValues
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.media.ExifInterface
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kimyubin33.audioapp.data.creator.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PostDraftInstrumentedTest {
    @Test fun textPostKeepsNonAiLabelRightsAndOptionalAudioAcrossRestarts() {
        val d=CreatorDraft(isPost=true,aiGenerated=false,title="바다",description="가족과 함께\n해변을 걸었다",holder="me",
            rightsConfirmed=true,personConsentConfirmed=true,aiTermsConfirmed=true,previewConfirmed=true,audioContentId="audio_1",audioTitle="Wave")
        assertNull(d.validationMessage())
        val restored=CreatorDraft.fromJson(d.toJson())
        assertTrue(restored.textPost);assertFalse(restored.aiGenerated);assertEquals("audio_1",restored.audioContentId)
        assertTrue(JSONObject(restored.metadata()).getJSONObject("submission").isNull("file"))
        assertNotNull(d.copy(description="").validationMessage())
        assertNotNull(d.copy(rightsConfirmed=false).validationMessage())
        val video=d.copy(isPost=false,isVideo=true,durationSeconds=10,bytes=128,name="a.mp4",sha256="a".repeat(64),audioContentId="")
        assertNotNull(video.copy(shortStyle="").validationMessage())
        assertNull(video.copy(shortStyle="moment").validationMessage())
    }
    @Test fun postPhotoIsReencodedWithoutGpsAndSourceIsPreserved() {
        val base=ApplicationProvider.getApplicationContext<android.content.Context>()
        val sandbox=File(base.cacheDir,"post-photo-"+UUID.randomUUID()).apply{mkdirs()}
        val context=object:ContextWrapper(base){override fun getNoBackupFilesDir()=sandbox}
        val store=CreatorDraftStore(context)
        val source=File(sandbox,"source.jpg")
        val bitmap=Bitmap.createBitmap(120,80,Bitmap.Config.ARGB_8888)
        source.outputStream().use{bitmap.compress(Bitmap.CompressFormat.JPEG,90,it)};bitmap.recycle()
        ExifInterface(source).apply{setAttribute(ExifInterface.TAG_GPS_LATITUDE,"37/1,30/1,0/1");setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF,"N");setAttribute(ExifInterface.TAG_GPS_LONGITUDE,"127/1,0/1,0/1");setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF,"E");setAttribute(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_ROTATE_90.toString());saveAttributes()}
        val uri=base.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,ContentValues().apply{
            put(MediaStore.Images.Media.DISPLAY_NAME,"post-test.jpg");put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");put(MediaStore.Images.Media.IS_PENDING,1)
        })!!
        try {
            base.contentResolver.openOutputStream(uri)!!.use{out->source.inputStream().use{it.copyTo(out)}}
            base.contentResolver.update(uri,ContentValues().apply{put(MediaStore.Images.Media.IS_PENDING,0)},null,null)
            val draft=PostPhotoImporter(context,store).import(uri)
            val output=store.file(draft.fileId!!)
            assertTrue(draft.isPost);assertFalse(draft.aiGenerated);assertEquals(80,draft.width);assertEquals(120,draft.height)
            assertNull(ExifInterface(output).getAttribute(ExifInterface.TAG_GPS_LATITUDE))
            assertNotNull(ExifInterface(source).getAttribute(ExifInterface.TAG_GPS_LATITUDE))
            assertTrue(CreatorFileRules.verify(output,draft.bytes,draft.sha256))
        } finally {base.contentResolver.delete(uri,null,null);sandbox.deleteRecursively()}
    }
}
