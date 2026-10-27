package com.shilapi.xcertplay.dsp

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
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
            "PEQ + Cabin、Macro + Smart Loudness、その合成の予測EQ応答グラフ。周波数20 Hzから19800 Hz、サンプルレート48000 Hz、ゲイン-18 dBから+18 dBです。Dynamic Bassとステレオ幅は含みません。バンドのハンドルをタップまたはドラッグして編集します。",
            japaneseContext.getString(R.string.dsp_eq_graph_description, 19_800, 48_000),
        )
        assertEquals(
            "Predicted response graph for PEQ + Cabin, Macro + Smart Loudness, and their combined curve. Frequency 20 Hz to 19800 Hz at 48000 Hz sample rate; gain −18 dB to +18 dB. Dynamic Bass and stereo width are not included. Tap or drag a band handle to edit.",
            englishContext.getString(R.string.dsp_eq_graph_description, 19_800, 48_000),
        )
        assertEquals("PEQバンド1を選択", japaneseContext.getString(R.string.dsp_eq_select_band, 1))
        assertEquals("Select PEQ band 1", englishContext.getString(R.string.dsp_eq_select_band, 1))
        assertEquals("DSP Studio を終了しますか？", japaneseContext.getString(R.string.dsp_studio_leave_title))
        assertEquals("Leave DSP Studio?", englishContext.getString(R.string.dsp_studio_leave_title))
        assertEquals("最大持ち上げ量", japaneseContext.getString(R.string.dsp_db_max_lift))
        assertEquals("Maximum lift", englishContext.getString(R.string.dsp_db_max_lift))
        assertEquals("50%", japaneseContext.getString(R.string.dsp_smart_percent, 50))
        assertEquals("50%", englishContext.getString(R.string.dsp_smart_percent, 50))
        val filterLabels = mapOf(
            DspEqType.PEAK to ("ピーク" to "Peak"),
            DspEqType.LOW_SHELF to ("ローシェルフ" to "Low shelf"),
            DspEqType.HIGH_SHELF to ("ハイシェルフ" to "High shelf"),
            DspEqType.HIGH_PASS to ("ハイパス" to "High-pass"),
            DspEqType.LOW_PASS to ("ローパス" to "Low-pass"),
            DspEqType.NOTCH to ("ノッチ" to "Notch"),
            DspEqType.ALL_PASS to ("オールパス" to "All-pass"),
        )
        filterLabels.forEach { (type, labels) ->
            assertEquals(labels.first, japaneseContext.getString(type.filterLabelResource()))
            assertEquals(labels.second, englishContext.getString(type.filterLabelResource()))
        }
    }

    @Test
    fun draggingProductionGraphHandleReportsMappedFrequencyAndGain() {
        val startingBand = DspEqBand(DspEqType.PEAK, frequencyHz = 1_000.0, gainDb = 0.0)
        val profile = mutableStateOf(DspAudioProfile(id = "graph-test", name = "Graph test", eqBands = listOf(startingBand)))
        val callbackProfile = mutableStateOf<DspAudioProfile?>(null)
        val finishedEditCount = mutableStateOf(0)
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
                    onEditFinished = { finishedEditCount.value += 1 },
                )
            }
        }

        val graph = composeRule.onNodeWithContentDescription(
            context.getString(R.string.dsp_eq_graph_description, 20_000, 48_000),
        )
        composeRule.onNodeWithContentDescription(context.getString(R.string.dsp_preamp)).assertExists()
        composeRule.onNodeWithContentDescription(context.getString(R.string.dsp_eq_band_enable, 1)).assertExists()
        composeRule.onNodeWithContentDescription(context.getString(R.string.dsp_eq_select_band, 1)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.dsp_eq_band_frequency, 1)).assertExists()
        composeRule.onNodeWithContentDescription(context.getString(R.string.dsp_eq_band_gain, 1)).assertExists()
        composeRule.onNodeWithContentDescription(
            context.getString(
                R.string.dsp_eq_toggle_sign,
                context.getString(R.string.dsp_eq_band_gain_numeric, 1),
            ),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.dsp_eq_filter_type_summary, context.getString(R.string.dsp_eq_filter_peak)),
        ).assertExists()
        composeRule.onNodeWithText("Playback rate 48000 Hz", substring = true).assertExists()
        composeRule.onNodeWithText(
            context.getString(R.string.dsp_eq_predicted_response, 20_000, 48_000),
        ).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.dsp_eq_curve_peq)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.dsp_eq_curve_tone)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.dsp_eq_curve_combined)).assertExists()
        val bounds = graph.fetchSemanticsNode().boundsInRoot
        val axisLeftPx = 30f * context.resources.displayMetrics.density
        val axisRightPx = 4f * context.resources.displayMetrics.density
        val axisTopPx = 4f * context.resources.displayMetrics.density
        val axisBottomPx = 18f * context.resources.displayMetrics.density
        val plotWidth = bounds.width - axisLeftPx - axisRightPx
        val plotHeight = bounds.height - axisTopPx - axisBottomPx
        graph.performTouchInput {
            val maxFrequencyHz = 20_000.0
            val from = Offset(
                axisLeftPx + plotWidth * (ln(startingBand.frequencyHz / 20.0) / ln(maxFrequencyHz / 20.0)).toFloat(),
                axisTopPx + plotHeight / 2f,
            )
            val to = Offset(
                axisLeftPx + plotWidth * (ln(2_000.0 / 20.0) / ln(maxFrequencyHz / 20.0)).toFloat(),
                axisTopPx + plotHeight * (18.0 - 6.0).toFloat() / 36.0f,
            )
            down(from)
            moveTo(to)
            up()
        }

        composeRule.runOnIdle {
            val edited = requireNotNull(callbackProfile.value).eqBands.single()
            assertEquals(2_000.0, edited.frequencyHz, 1.0)
            assertEquals(6.0, edited.gainDb, 0.1)
            assertEquals(1, finishedEditCount.value)
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
            InstrumentationRegistry.getInstrumentation().targetContext.getString(
                R.string.dsp_eq_graph_description,
                20_000,
                48_000,
            ),
        )
        val bounds = graph.fetchSemanticsNode().boundsInRoot
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val axisLeftPx = 30f * context.resources.displayMetrics.density
        val axisRightPx = 4f * context.resources.displayMetrics.density
        val axisTopPx = 4f * context.resources.displayMetrics.density
        val axisBottomPx = 18f * context.resources.displayMetrics.density
        val plotWidth = bounds.width - axisLeftPx - axisRightPx
        val plotHeight = bounds.height - axisTopPx - axisBottomPx
        graph.performTouchInput {
            val maxFrequencyHz = 20_000.0
            val from = Offset(
                axisLeftPx + plotWidth * (ln(startingBand.frequencyHz / 20.0) / ln(maxFrequencyHz / 20.0)).toFloat(),
                axisTopPx + plotHeight * (18f - startingBand.gainDb.toFloat()) / 36f,
            )
            val to = Offset(
                axisLeftPx + plotWidth * (ln(2_000.0 / 20.0) / ln(maxFrequencyHz / 20.0)).toFloat(),
                axisTopPx + plotHeight / 2f,
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
    fun predictedResponseShowsTheSelectedPlaybackRateAndFrequencyLimit() {
        val sampleRate = mutableStateOf(44_100)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            MaterialTheme {
                DspEqEditorPane(
                    profile = DspAudioProfile(id = "rate-test", name = "Rate test"),
                    sampleRateHz = sampleRate.value,
                    onProfileChange = {},
                    dynamicBassEnabled = true,
                )
            }
        }

        composeRule.onNodeWithText(
            context.getString(R.string.dsp_eq_predicted_response, 19_845, 44_100),
        ).assertExists()
        composeRule.onNodeWithContentDescription(
            context.getString(R.string.dsp_eq_graph_description, 19_845, 44_100),
        ).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.dsp_eq_dynamic_bass_excluded)).assertExists()
        composeRule.runOnIdle { sampleRate.value = 48_000 }
        composeRule.onNodeWithText(
            context.getString(R.string.dsp_eq_predicted_response, 20_000, 48_000),
        ).assertExists()
        composeRule.onNodeWithContentDescription(
            context.getString(R.string.dsp_eq_graph_description, 20_000, 48_000),
        ).assertExists()
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
