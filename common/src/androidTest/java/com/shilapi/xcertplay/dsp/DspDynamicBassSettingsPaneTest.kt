package com.shilapi.xcertplay.dsp

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DspDynamicBassSettingsPaneTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun defaultOffSwitchControlsEnabledStateAndBoundedStrength() {
        val settings = mutableStateOf(DspDynamicBassSettings())
        composeRule.setContent {
            MaterialTheme {
                DspDynamicBassSettingsPane(
                    settings = settings.value,
                    onSettingsChange = { settings.value = it },
                )
            }
        }

        composeRule.onNodeWithText("Off · no dynamic bass lift").fetchSemanticsNode()
        composeRule.onNodeWithContentDescription("Dynamic Bass enabled, off")
            .assertIsOff()
            .performClick()

        composeRule.onNodeWithContentDescription("Dynamic Bass enabled, on").assertIsOn()
        composeRule.onNodeWithText("Enabled · follows the input bass level").fetchSemanticsNode()
        composeRule.onNodeWithContentDescription("Strength, 50.0%, range 0.0% to 100.0%")
            .assertIsEnabled()
        composeRule.onNodeWithText("Full-lift threshold").fetchSemanticsNode()
        composeRule.onNodeWithText("No-lift threshold").fetchSemanticsNode()
        composeRule.onNodeWithText("How quickly the detector envelope and requested lift respond to level changes.").fetchSemanticsNode()
    }

    @Test
    fun controlsAreDisabledWhileDynamicBassIsOff() {
        composeRule.setContent {
            MaterialTheme {
                DspDynamicBassSettingsPane(
                    settings = DspDynamicBassSettings(),
                    onSettingsChange = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("Strength, 50.0%, range 0.0% to 100.0%")
            .assertIsNotEnabled()
    }
}
