package com.kimyubin33.audioapp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kimyubin33.audioapp.data.LibraryEntry
import com.kimyubin33.audioapp.ui.*
import com.kimyubin33.audioapp.ui.theme.AudioAppTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryAccountInstrumentedTest {
    @get:Rule val compose=createComposeRule()
    @Test fun libraryShowsSavedAndHistoryAndAccountOpensFromGuestBadge() {
        var openedAccount=false
        val saved=LibraryEntry("saved-1","Saved story","creator_01",true,false,0)
        val history=LibraryEntry("history-1","Recent story","creator_02",true,false,0)
        compose.setContent {
            AudioAppTheme { Scaffold(contentWindowInsets=WindowInsets.safeDrawing) { padding->
                LibraryScreen(listOf(history),listOf(saved),{}, {openedAccount=true},
                    Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding))
            } }
        }
        compose.onNodeWithText("로그인").assertIsDisplayed()
        compose.onNodeWithText("Saved story").assertIsDisplayed()
        compose.onNodeWithText("Recent story").assertDoesNotExist()
        compose.onNodeWithText("차단 관리").assertDoesNotExist()
        compose.onNodeWithText("나의 추억").assertDoesNotExist()
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Recent story").assertIsDisplayed()
        compose.onNodeWithText("Saved story").assertDoesNotExist()
        compose.onNodeWithText("로그인").performClick()
        compose.runOnIdle { assertTrue(openedAccount) }
    }
}
