package com.kimyubin33.audioapp

import android.content.ContentValues
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
import com.kimyubin33.audioapp.data.LocalProfile
import com.kimyubin33.audioapp.data.LocalProfileStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion=29)
class ProfileStoreInstrumentedTest {
    @Test fun accountPhotosAreIsolatedPrivateAndStrippedOfGps() {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(base.cacheDir,"profiles-test-"+UUID.randomUUID()).apply { mkdirs() }
        val context=object: ContextWrapper(base) { override fun getNoBackupFilesDir()=root }
        var uri: Uri?=null
        try {
            val store=LocalProfileStore(context)
            store.save(LocalProfile("account-a","Alice"))
            store.save(LocalProfile("account-b","Bob"))
            val source=File(root,"source.jpg")
            val bitmap=Bitmap.createBitmap(80,40,Bitmap.Config.ARGB_8888)
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,90,it) }; bitmap.recycle()
            ExifInterface(source.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION,"6")
                setAttribute(ExifInterface.TAG_GPS_LATITUDE,"37/1,30/1,0/1")
                setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF,"N")
                saveAttributes()
            }
            uri=checkNotNull(base.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME,"profile-test-"+UUID.randomUUID()+".jpg")
                put(MediaStore.MediaColumns.MIME_TYPE,"image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/AudioApp-tests")
                put(MediaStore.MediaColumns.IS_PENDING,1)
            }))
            base.contentResolver.openOutputStream(uri)!!.use { out->source.inputStream().use { it.copyTo(out) } }
            base.contentResolver.update(uri,ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING,0) },null,null)
            val a=store.updateAvatar("account-a",uri)
            val b=store.updateAvatar("account-b",uri)
            val fileA=checkNotNull(store.avatar(a)); val fileB=checkNotNull(store.avatar(b))
            assertNotEquals(fileA.absolutePath,fileB.absolutePath)
            val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
            BitmapFactory.decodeFile(fileA.absolutePath,bounds)
            assertEquals(40,bounds.outWidth); assertEquals(40,bounds.outHeight)
            assertNull(ExifInterface(fileA.absolutePath).getAttribute(ExifInterface.TAG_GPS_LATITUDE))
            assertNotNull(ExifInterface(source.absolutePath).getAttribute(ExifInterface.TAG_GPS_LATITUDE))
            assertEquals(a,LocalProfileStore(context).load("account-a"))
            assertEquals(b,LocalProfileStore(context).load("account-b"))
            assertFalse(File(fileA.parentFile,"profile.json").readText().contains("content://"))
            store.save(a.copy(avatarId=null,name="Alice 2"))
            assertFalse(fileA.exists()); assertTrue(fileB.exists())
            assertEquals("Bob",store.load("account-b").name)
        } finally {
            uri?.let { base.contentResolver.delete(it,null,null) }
            root.deleteRecursively()
        }
    }

    @Test fun malformedProfileNeverBecomesAnotherAccountsProfile() {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(base.cacheDir,"profiles-test-"+UUID.randomUUID()).apply { mkdirs() }
        try {
            val context=object: ContextWrapper(base) { override fun getNoBackupFilesDir()=root }
            val store=LocalProfileStore(context)
            val profile=LocalProfile("account-a","Alice")
            store.save(profile)
            assertThrows(IllegalArgumentException::class.java) { store.save(profile.copy(name="bad\nname")) }
            assertThrows(IllegalArgumentException::class.java) { store.save(profile.copy(avatarId="../../outside")) }
            assertThrows(IllegalArgumentException::class.java) { store.load("../outside") }
            assertEquals(profile,store.load("account-a"))
            assertEquals(LocalProfile("account-b"),store.load("account-b"))
        } finally { root.deleteRecursively() }
    }
}
