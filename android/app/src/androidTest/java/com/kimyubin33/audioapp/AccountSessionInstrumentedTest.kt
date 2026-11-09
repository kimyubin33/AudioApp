package com.kimyubin33.audioapp

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kimyubin33.audioapp.data.LocalProfile
import com.kimyubin33.audioapp.data.LocalProfileStore
import com.kimyubin33.audioapp.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AccountSessionInstrumentedTest {
    private class Source: AccountSource {
        override val changes=MutableStateFlow(0L)
        var current: String?="account-a"
        var blockNext: CompletableDeferred<Unit>?=null
        val entered=CompletableDeferred<Unit>()
        override suspend fun hasSession()=current!=null
        override suspend fun identity(): AccountIdentity {
            val captured=checkNotNull(current)
            val gate=blockNext
            blockNext=null
            if(gate!=null) { entered.complete(Unit); withContext(NonCancellable) { gate.await() } }
            return AccountIdentity(captured)
        }
        fun select(id: String?) { current=id; changes.value++ }
        override suspend fun signOut() { select(null) }
        override suspend fun developerSignIn() { select("account-a") }
    }

    @Test fun sessionChangeClearsPreviousProfileAndRejectsStaleEditorWrites() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val base=instrumentation.targetContext
        val root=File(base.cacheDir,"account-test-"+UUID.randomUUID()).apply { mkdirs() }
        val app=object: Application() {
            init { attachBaseContext(base) }
            override fun getNoBackupFilesDir()=root
            override fun getApplicationContext(): Context=this
        }
        val store=LocalProfileStore(app)
        store.save(LocalProfile("account-a","Alice"))
        store.save(LocalProfile("account-b","Bob"))
        val source=Source()
        val models=ViewModelStore()
        lateinit var model: AccountViewModel
        val release=CompletableDeferred<Unit>()
        instrumentation.runOnMainSync { model=AccountViewModel(app,source); models.put("account",model) }
        try {
            withTimeout(10_000) { model.state.first { it.userId=="account-a" && !it.loading } }
            assertEquals("Alice",model.state.value.name)
            instrumentation.runOnMainSync { source.blockNext=release; model.refresh() }
            withTimeout(10_000) { source.entered.await() }
            instrumentation.runOnMainSync { source.select("account-b") }
            withTimeout(10_000) { model.state.first { it.userId==null } }
            release.complete(Unit)
            withTimeout(10_000) { model.state.first { it.userId=="account-b" && !it.loading } }
            assertEquals("Bob",model.state.value.name)
            instrumentation.runOnMainSync { model.saveName("Wrong account","account-a") { fail("Stale editor was applied") } }
            assertEquals("Bob",store.load("account-b").name)
            val saved=CompletableDeferred<Unit>()
            instrumentation.runOnMainSync { model.saveName("Bee","account-b") { saved.complete(Unit) } }
            withTimeout(10_000) { saved.await() }
            assertEquals("Bee",store.load("account-b").name)
            instrumentation.runOnMainSync { model.signOut() }
            withTimeout(10_000) { model.state.first { !it.loading && !it.hasSession && it.userId==null } }
            assertNull(model.state.value.avatar)
            assertNull(model.state.value.profile)
        } finally {
            release.complete(Unit)
            instrumentation.runOnMainSync { models.clear() }
            root.deleteRecursively()
        }
    }
}
