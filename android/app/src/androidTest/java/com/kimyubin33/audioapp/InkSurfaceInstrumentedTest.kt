package com.kimyubin33.audioapp

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kimyubin33.audioapp.ui.vehicle.INK_SHADER
import com.kimyubin33.audioapp.vehicle.InkSimulator
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InkSurfaceInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun actualAgslCompilesWithAllRegionUniforms() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        // Do not let the production fallback hide an invalid shader during development.
        val shader = RuntimeShader(INK_SHADER)
        shader.setFloatUniform("resolution", 960f, 540f)
        shader.setFloatUniform("time", 9f)
        shader.setFloatUniform("eventStart", 0f)
        shader.setFloatUniform("intensity", 1f)
        shader.setFloatUniform("selected", 1f)
        shader.setIntUniform("count", 3)
        repeat(6) { shader.setFloatUniform("region$it", .5f,.4f,.25f,it.toFloat()) }
    }

    @Test fun simulatedTrackPauseAndRestrictionSurviveRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { InkSimulator(initialReducedMotion=true) }
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Into the woods").assertIsDisplayed()
        compose.onNodeWithText("Pause").performClick()
        compose.onNodeWithText("Parked").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Into the woods").assertIsDisplayed()
        compose.onNodeWithText("Play").assertIsDisplayed()
        compose.onNodeWithText("Driving").assertIsDisplayed()
        compose.onNodeWithText("STILL WATER").assertIsDisplayed()
        compose.onNodeWithText("DESIGN SIMULATION · SAMPLE DATA · NO AUDIO / GUIDANCE").assertExists()
    }
}
