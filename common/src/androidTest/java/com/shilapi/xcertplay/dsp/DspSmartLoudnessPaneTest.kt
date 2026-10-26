package com.shilapi.xcertplay.dsp

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DspSmartLoudnessPaneTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun autoRemainsUnavailableWithoutValidatedPlaybackVolumeInput() {
        composeRule.setContent {
            MaterialTheme {
                DspSmartLoudnessPane(
                    settings = DspSmartLoudnessSettings(mode = DspSmartLoudnessMode.AUTO_CALIBRATED),
                    volumeReading = DspVolumeReading(),
                    onSettingsChange = {},
                )
            }
        }

        composeRule.onNodeWithText(
            "AUTO_UNAVAILABLE · playback-volume input is unvalidated for this route; use MANUAL or FIXED",
        ).assertExists()
        composeRule.onNodeWithText("AUTO", substring = true).assertIsNotEnabled()
        composeRule.onNodeWithText("MANUAL", substring = true).assertIsEnabled()
        composeRule.onNodeWithText("FIXED", substring = true).assertIsEnabled()
    }
}
