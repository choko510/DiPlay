package com.shilapi.xcertplay.dsp

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.dsp.DspEqBand
import com.shilapi.xcertplay.media.dsp.DspEqType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import kotlin.math.ln

@RunWith(AndroidJUnit4::class)
class DspEqEditorGraphTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun accessibilityResourcesResolveInJapaneseAndEnglish() {
        val baseContext = InstrumentationRegistry.getInstrumentation().targetContext
        val japaneseContext = baseContext.createConfigurationContext(
            Configuration(baseContext.resources.configuration).apply { setLocale(Locale.JAPANESE) },
        )
        val englishContext = baseContext.createConfigurationContext(
            Configuration(baseContext.resources.configuration).apply { setLocale(Locale.ENGLISH) },
        )

        assertEquals("プリアンプ", japaneseContext.getString(R.string.dsp_preamp))
        assertEquals("Preamp", englishContext.getString(R.string.dsp_preamp))
        assertEquals("PEQバンド 1の周波数", japaneseContext.getString(R.string.dsp_eq_band_frequency, 1))
        assertEquals("PEQ band 1 frequency", englishContext.getString(R.string.dsp_eq_band_frequency, 1))
        assertEquals("PEQバンド 1のゲイン", japaneseContext.getString(R.string.dsp_eq_band_gain, 1))
        assertEquals("PEQ band 1 gain", englishContext.getString(R.string.dsp_eq_band_gain, 1))
        assertEquals(
            "イコライザーの周波数特性グラフ。バンドのハンドルをタップまたはドラッグして周波数とゲインを編集します。",
            japaneseContext.getString(R.string.dsp_eq_graph_description),
        )
        assertEquals(
            "Equalizer frequency response graph. Tap or drag a band handle to edit frequency and gain.",
            englishContext.getString(R.string.dsp_eq_graph_description),
        )
    }

    @Test
    fun draggingProductionGraphHandleReportsMappedFrequencyAndGain() {
        val startingBand = DspEqBand(DspEqType.PEAK, frequencyHz = 1_000.0, gainDb = 0.0)
        val profile = mutableStateOf(DspAudioProfile(id = "graph-test", name = "Graph test", eqBands = listOf(startingBand)))
        val callbackProfile = mutableStateOf<DspAudioProfile?>(null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        composeRule.setContent {
            MaterialTheme {
                DspEqEditorPane(
                    profile = profile.value,
                    sampleRateHz = 48_000,
                    onProfileChange = {
                        callbackProfile.value = it
                        profile.value = it
                    },
                )
            }
        }

        val graph = composeRule.onNodeWithContentDescription(
            context.getString(R.string.dsp_eq_graph_description),
        )
        composeRule.onNodeWithContentDescription(context.getString(R.string.dsp_preamp)).assertExists()
        composeRule.onNodeWithContentDescription(context.getString(R.string.dsp_eq_band_enable, 1)).assertExists()
        composeRule.onNodeWithContentDescription(context.getString(R.string.dsp_eq_band_frequency, 1)).assertExists()
        composeRule.onNodeWithContentDescription(context.getString(R.string.dsp_eq_band_gain, 1)).assertExists()
        composeRule.onNodeWithContentDescription(
            context.getString(
                R.string.dsp_eq_toggle_sign,
                context.getString(R.string.dsp_eq_band_gain_numeric, 1),
            ),
        ).assertExists()
        composeRule.onNodeWithText(
            context.getString(R.string.dsp_eq_filter_type_summary, context.getString(R.string.dsp_eq_filter_peak)),
        ).assertExists()
        composeRule.onNodeWithText("Playback rate 48000 Hz", substring = true).assertExists()
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
                    sampleRateHz = 48_000,
                    onProfileChange = {
                        callbackProfile.value = it
                        profile.value = it
                    },
                )
            }
        }

        val graph = composeRule.onNodeWithContentDescription(
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.dsp_eq_graph_description),
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

    @Test
    fun unknownPlaybackRateIsShownAsPreviewFallback() {
        composeRule.setContent {
            MaterialTheme {
                DspEqEditorPane(
                    profile = DspAudioProfile(id = "unknown-rate-test", name = "Unknown rate test"),
                    onProfileChange = {},
                )
            }
        }

        composeRule.onNodeWithText("Playback rate unknown; preview at 44100 Hz", substring = true).assertExists()
    }
}
