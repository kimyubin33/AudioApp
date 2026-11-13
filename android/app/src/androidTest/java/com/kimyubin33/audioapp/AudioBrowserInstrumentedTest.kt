@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.kimyubin33.audioapp

import android.content.ComponentName
import androidx.media3.session.*
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.util.concurrent.ListenableFuture
import com.kimyubin33.audioapp.data.RadioCatalog
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AudioBrowserInstrumentedTest {
    @Test fun carBrowserDiscoversOnlyAudioRootsAndRejectsCallerUrls() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        lateinit var connection: ListenableFuture<MediaBrowser>
        instrumentation.runOnMainSync {
            connection=MediaBrowser.Builder(context,SessionToken(context,ComponentName(context,PlaybackService::class.java))).buildAsync()
        }
        val browser=connection.get(15,TimeUnit.SECONDS)
        try {
            lateinit var root: ListenableFuture<LibraryResult<androidx.media3.common.MediaItem>>
            instrumentation.runOnMainSync { root=browser.getLibraryRoot(null) }
            assertEquals(RadioCatalog.ROOT,root.get(5,TimeUnit.SECONDS).value?.mediaId)
            lateinit var children: ListenableFuture<LibraryResult<com.google.common.collect.ImmutableList<androidx.media3.common.MediaItem>>>
            instrumentation.runOnMainSync { children=browser.getChildren(RadioCatalog.ROOT,0,Int.MAX_VALUE,null) }
            assertEquals(listOf(RadioCatalog.MIX,RadioCatalog.ALL,RadioCatalog.FOLLOWING,RadioCatalog.SAVED),
                children.get(5,TimeUnit.SECONDS).value?.map{it.mediaId})
            instrumentation.runOnMainSync { root=browser.getItem("https://untrusted.example/audio.mp3") }
            assertEquals(SessionError.ERROR_BAD_VALUE,root.get(5,TimeUnit.SECONDS).resultCode)
        } finally { instrumentation.runOnMainSync { browser.release() } }
    }
}
