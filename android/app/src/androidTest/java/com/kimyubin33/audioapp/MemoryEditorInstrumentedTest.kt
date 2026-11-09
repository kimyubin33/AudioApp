package com.kimyubin33.audioapp

import android.app.Application
import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.material3.Scaffold
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kimyubin33.audioapp.ui.*
import com.kimyubin33.audioapp.ui.theme.AudioAppTheme
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MemoryEditorInstrumentedTest {
    @get:Rule val compose=createComposeRule()

    @Test fun candidateComparisonUndoSaveAndReopenPreserveOnlyChosenVersion() {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(base.cacheDir,"memory-ui-test-"+UUID.randomUUID()).apply { mkdirs() }
        val application=object: Application() {
            init { attachBaseContext(base) }
            override fun getNoBackupFilesDir(): File = root
            override fun getApplicationContext(): Context = this
        }
        lateinit var model: MemoryViewModel
        val models=ViewModelStore()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            model=MemoryViewModel(application)
            models.put("album",model)
        }
        try {
            compose.waitUntil(10_000) { model.state.value.loaded }
            var savedId: String?=null
            var undoClicks=0
            var saveClicks=0
            val restoration=StateRestorationTester(compose)
            restoration.setContent {
                val scope=rememberCoroutineScope()
                AudioAppTheme {
                    val state by model.state.collectAsState()
                    // 실제 MainActivity와 같은 시스템 안전 영역. API 37의 제스처 바에 버튼이 가려지지 않아야 한다.
                    Scaffold(contentWindowInsets=WindowInsets.safeDrawing) { padding ->
                        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                            MemoryEditorContent(state,null,onBack={},onPhoto={},onEdit=model::edit,onApply=model::applyCandidate,
                                onUndo={undoClicks++; model.undoCandidate()},onSave={saveClicks++; scope.launch { savedId=model.saveMemory() }},
                                onRetry=model::reload,onRetrySave=model::flush)
                        }
                    }
                }
            }
            compose.onNode(hasSetTextAction() and hasText("추억의 제목")).performTextInput("바다에서의 하루")
            compose.onNode(hasSetTextAction() and hasText("그날의 이야기")).performTextInput("원래 기억")
            compose.onNodeWithText("AI로 꾸며보기 · 선택").performScrollTo().performClick()
            compose.onNode(hasSetTextAction() and hasText("가져온 글 · 비교용 후보")).performScrollTo().performTextInput("다른 분위기의 글")
            compose.onNode(hasSetTextAction() and hasText("가져온 글 · 비교용 후보")).performImeAction()
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithText("이 버전 사용").performScrollTo().assertIsEnabled().performClick()
            compose.runOnIdle {
                assertEquals("다른 분위기의 글",model.state.value.album.draft.body)
                assertEquals("원래 기억",model.state.value.album.draft.previousBody)
            }
            compose.onNodeWithText("이전 글로 되돌리기").performScrollTo().assertIsEnabled().assertIsDisplayed()
            compose.waitForIdle()
            compose.onNodeWithText("이전 글로 되돌리기").performClick()
            val hierarchy=compose.onRoot().printToString()
            compose.runOnIdle { assertEquals("Undo callback was not dispatched: "+hierarchy.takeLast(6500),1,undoClicks) }
            compose.waitUntil(5_000) { model.state.value.album.draft.body=="원래 기억" }
            compose.runOnIdle { assertNull(model.state.value.album.draft.previousBody) }
            compose.onNodeWithText("Save Moment").assertIsEnabled().assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals("Save callback was not dispatched",1,saveClicks) }
            compose.waitUntil(10_000) { savedId!=null }
            compose.runOnIdle {
                assertEquals(1,model.state.value.album.memories.size)
                assertEquals("원래 기억",model.state.value.album.memories.single().body)
                assertFalse(model.state.value.album.draft.hasContent)
            }
            val restored=com.kimyubin33.audioapp.data.memory.MemoryStore(application).load()
            assertEquals(savedId,restored.memories.single().id)
            assertEquals("원래 기억",restored.memories.single().body)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { models.clear() }
            root.deleteRecursively()
        }
    }
}
