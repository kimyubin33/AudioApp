package com.kimyubin33.audioapp

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kimyubin33.audioapp.ui.ReportDialog
import com.kimyubin33.audioapp.ui.ReportsScreen
import com.kimyubin33.audioapp.ui.theme.AudioAppTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.audio_core.ReportReason

@RunWith(AndroidJUnit4::class)
class ReportsInstrumentedTest {
    @get:Rule val compose=createComposeRule()

    @Test fun creatorReportKeepsTargetAndRequiresExplanationForOther() {
        var result:Triple<ReportReason,String?,Boolean>?=null
        compose.setContent { AudioAppTheme { ReportDialog(false,{}, { reason,detail,creator -> result=Triple(reason,detail,creator) }) } }
        compose.onNodeWithText("크리에이터").performClick()
        compose.onNodeWithText("기타",useUnmergedTree=true).performScrollTo().performClick()
        compose.onNodeWithText("신고").assertIsNotEnabled()
        compose.onNodeWithText("설명 (필수)").performScrollTo().performTextInput("음성 사칭 의심")
        compose.onNodeWithText("신고").performClick()
        compose.runOnIdle { assertEquals(Triple(ReportReason.OTHER,"음성 사칭 의심",true),result) }
    }

    @Test fun ownReportsShowsResolutionAndLoadsNextPage() {
        val calls=mutableListOf<String?>()
        compose.setContent { AudioAppTheme { ReportsScreen("viewer",{}, {},loadPage={ cursor ->
            calls.add(cursor)
            if(cursor==null) """{"items":[{"id":"r1","title":"첫 오디오","reason":"copyright","status":"resolved","resolution":"공개를 중단했습니다.","created_at":1}],"next_cursor":"1.r1"}"""
            else """{"items":[{"id":"r2","creator_id":"creator_two","reason":"other","status":"reviewing","resolution":"확인 중입니다.","created_at":1}],"next_cursor":null}"""
        }) } }
        compose.waitUntil(5000){compose.onAllNodesWithText("첫 오디오").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("공개를 중단했습니다.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("더 보기").performScrollTo().performClick()
        compose.waitUntil(5000){calls.size==2}
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("creator_two"))
        compose.onNodeWithText("creator_two").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(null,"1.r1"),calls) }
    }
}
