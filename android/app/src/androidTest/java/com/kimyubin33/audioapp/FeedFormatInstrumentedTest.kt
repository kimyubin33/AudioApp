package com.kimyubin33.audioapp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kimyubin33.audioapp.ui.*
import com.kimyubin33.audioapp.ui.theme.AudioAppTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FeedFormatInstrumentedTest {
    @get:Rule val compose=createComposeRule()
    @Test fun audioOnlyFeedOffersAllAndSubscriptions() {
        var format by mutableStateOf(FeedFormat.All)
        var opened=""
        val audio=HomeItem("audio","River sound","creator",true)
        compose.setContent {AudioAppTheme{Scaffold(contentWindowInsets=WindowInsets.safeDrawing){padding->
            FeedScreen("피드","",listOf(audio,HomeItem("video","Hidden video","creator",false,true),
                HomeItem("post","Hidden post","creator",false,isPost=true)),false,null,false,null,
                {opened=it},{},{},Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
                format=format,onFormat={format=it})
        }}}
        compose.onNodeWithText("전체").assertIsDisplayed()
        compose.onNodeWithText("구독").performClick()
        compose.runOnIdle { assertEquals(FeedFormat.Subscribed,format) }
        compose.onNodeWithText("쇼츠").assertDoesNotExist()
        compose.onNodeWithText("게시글").assertDoesNotExist()
        compose.onNodeWithText("Hidden video").assertDoesNotExist()
        compose.onNodeWithText("Hidden post").assertDoesNotExist()
        compose.onNodeWithText("River sound").performClick()
        compose.runOnIdle { assertEquals("audio",opened) }
    }
    @Test fun drivePanelHasLargePlaybackControlsWithoutLocation() {
        var play=false
        compose.setContent {AudioAppTheme{
            DrivePanel(NearbyUiState(),null,"",false,null,false,{},{},{},{},{},{},{play=true},{},{})
        }}
        compose.onNodeWithText("Drive").assertIsDisplayed()
        compose.onNodeWithText("Anywhere").assertIsDisplayed()
        compose.onNodeWithText("Play").performClick()
        compose.runOnIdle { assertTrue(play) }
        compose.onNodeWithText("Next").assertIsDisplayed()
    }
}
