package com.kimyubin33.audioapp

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kimyubin33.audioapp.data.LocalProfile
import com.kimyubin33.audioapp.data.LocalProfileStore
import com.kimyubin33.audioapp.data.PublishedPolicy
import com.kimyubin33.audioapp.ui.StartupScreen
import com.kimyubin33.audioapp.ui.theme.AudioAppTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReleaseReadinessInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun connectionFailureOffersRetryAndReturnsToLoading() {
        val failed = mutableStateOf(true)
        var retries = 0
        compose.setContent { AudioAppTheme { StartupScreen(failed.value, { retries++; failed.value = false }) } }
        compose.onNodeWithText("오디오에 연결하지 못했습니다.").assertIsDisplayed()
        compose.onNodeWithText("다시 연결").performClick()
        compose.onNodeWithText("오디오 연결 중").assertIsDisplayed()
        compose.onNodeWithText("다시 연결").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun incompleteOrUnsafePolicyLinksCannotEnableConsent() {
        val policy = JSONObject().put("ready", true).put("version", "2026-11-v1")
            .put("terms_url", "https://policies.test/terms")
            .put("privacy_url", "https://policies.test/privacy")
            .put("creator_terms_url", "https://policies.test/creator")
        assertTrue(PublishedPolicy.parse(policy.toString()).ready)
        for (bad in listOf("http://policies.test/terms", "javascript:alert(1)", "https://user:pass@policies.test/terms", "")) {
            assertFalse(PublishedPolicy.parse(JSONObject(policy.toString()).put("terms_url", bad).toString()).ready)
        }
        assertFalse(PublishedPolicy.parse(JSONObject(policy.toString()).put("ready", false).toString()).ready)
        policy.remove("privacy_url")
        assertFalse(PublishedPolicy.parse(policy.toString()).ready)
    }

    @Test fun deletingLocalProfilePreservesOtherAccountAndIsRetryable() {
        val store = LocalProfileStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val first = "delete-test-" + UUID.randomUUID()
        val second = "keep-test-" + UUID.randomUUID()
        try {
            store.save(LocalProfile(first, "Delete"))
            store.save(LocalProfile(second, "Keep"))
            store.deleteAccount(first)
            store.deleteAccount(first)
            assertNotEquals("Delete", store.load(first).name)
            assertEquals("Keep", store.load(second).name)
        } finally {
            store.deleteAccount(first)
            store.deleteAccount(second)
        }
    }
}
