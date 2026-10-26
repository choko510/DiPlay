package com.shilapi.xcertplay.dsp

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shilapi.xcertplay.media.dsp.DspEqBand
import com.shilapi.xcertplay.media.dsp.DspEqType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ln

@RunWith(AndroidJUnit4::class)
class DspEqEditorGraphTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun draggingProductionGraphHandleReportsMappedFrequencyAndGain() {
        val startingBand = DspEqBand(DspEqType.PEAK, frequencyHz = 1_000.0, gainDb = 0.0)
        val profile = mutableStateOf(DspAudioProfile(id = "graph-test", name = "Graph test", eqBands = listOf(startingBand)))
        val callbackProfile = mutableStateOf<DspAudioProfile?>(null)

        composeRule.setContent {
            MaterialTheme {
                DspEqEditorPane(
                    profile = profile.value,
                    onProfileChange = {
                        callbackProfile.value = it
                        profile.value = it
                    },
                )
            }
        }

        val graph = composeRule.onNodeWithContentDescription(
            "Equalizer frequency response graph. Tap or drag a band handle to edit frequency and gain.",
        )
        val bounds = graph.fetchSemanticsNode().boundsInRoot
        graph.performTouchInput {
            val maxFrequencyHz = 20_000.0
            val from = Offset(
                bounds.width * (ln(startingBand.frequencyHz / 20.0) / ln(maxFrequencyHz / 20.0)).toFloat(),
                bounds.height / 2f,
            )
            val to = Offset(
                bounds.width * (ln(2_000.0 / 20.0) / ln(maxFrequencyHz / 20.0)).toFloat(),
                bounds.height * (18.0 - 6.0).toFloat() / 36.0f,
            )
            down(from)
            moveTo(to)
            up()
        }

        composeRule.runOnIdle {
            val edited = requireNotNull(callbackProfile.value).eqBands.single()
            assertEquals(2_000.0, edited.frequencyHz, 1.0)
            assertEquals(6.0, edited.gainDb, 0.1)
        }
    }

    @Test
    fun draggingHighPassHandleChangesFrequencyButPreservesInapplicableGain() {
        val startingBand = DspEqBand(DspEqType.HIGH_PASS, frequencyHz = 1_000.0, gainDb = 3.0)
        val profile = mutableStateOf(DspAudioProfile(id = "graph-type-test", name = "Graph type test", eqBands = listOf(startingBand)))
        val callbackProfile = mutableStateOf<DspAudioProfile?>(null)

        composeRule.setContent {
            MaterialTheme {
                DspEqEditorPane(
                    profile = profile.value,
                    onProfileChange = {
                        callbackProfile.value = it
                        profile.value = it
                    },
                )
            }
        }

        val graph = composeRule.onNodeWithContentDescription(
            "Equalizer frequency response graph. Tap or drag a band handle to edit frequency and gain.",
        )
        val bounds = graph.fetchSemanticsNode().boundsInRoot
        graph.performTouchInput {
            val maxFrequencyHz = 20_000.0
            val from = Offset(
                bounds.width * (ln(startingBand.frequencyHz / 20.0) / ln(maxFrequencyHz / 20.0)).toFloat(),
                bounds.height * (18f - startingBand.gainDb.toFloat()) / 36f,
            )
            val to = Offset(
                bounds.width * (ln(2_000.0 / 20.0) / ln(maxFrequencyHz / 20.0)).toFloat(),
                bounds.height / 2f,
            )
            down(from)
            moveTo(to)
            up()
        }

        composeRule.runOnIdle {
            val edited = requireNotNull(callbackProfile.value).eqBands.single()
            assertEquals(2_000.0, edited.frequencyHz, 1.0)
            assertEquals(startingBand.gainDb, edited.gainDb, 0.0)
        }
    }
}
