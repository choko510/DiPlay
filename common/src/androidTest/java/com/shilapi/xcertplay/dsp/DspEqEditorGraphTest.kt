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
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.dsp.DspEqBand
import com.shilapi.xcertplay.media.dsp.DspEqType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
            "PEQ + Cabin、Macro + Smart Loudness、その合成の静的な線形近似による予測EQ応答グラフ。周波数20 Hzから19800 Hz、サンプルレート48000 Hz、ゲイン-18 dBから+18 dBです。Dynamic Bass、ステレオ幅、M/S、Convolver、Harmonic、Seat処理は含みません。バンドのハンドルをタップまたはドラッグして編集します。",
            japaneseContext.getString(R.string.dsp_eq_graph_description, 19_800, 48_000),
        )
        assertEquals(
            "Predicted linear-approximation response graph for PEQ + Cabin, Macro + Smart Loudness, and their combined curve. Frequency 20 Hz to 19800 Hz at 48000 Hz sample rate; gain −18 dB to +18 dB. Dynamic Bass, stereo width, Mid/Side, Convolver, Harmonic and Seat processing are not included. Tap or drag a band handle to edit.",
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
    fun realPointerDragMapsTheFourRequiredLogFrequencyHandlesWithinThreePercent() {
        val targetsHz = listOf(20.0, 200.0, 2_000.0, 10_000.0)
        val profile = mutableStateOf(
            DspAudioProfile(
                id = "frequency-mapping-test",
                name = "Frequency mapping test",
                eqBands = listOf(DspEqBand(DspEqType.PEAK, frequencyHz = 30.0, gainDb = 0.0)),
            ),
        )
        val editedProfile = mutableStateOf<DspAudioProfile?>(null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            MaterialTheme {
                DspEqEditorPane(
                    profile = profile.value,
                    sampleRateHz = 48_000,
                    onProfileChange = { editedProfile.value = it; profile.value = it },
                )
            }
        }
        val graph = composeRule.onNodeWithContentDescription(
            context.getString(R.string.dsp_eq_graph_description, 20_000, 48_000),
        )
        val plot = graphPlot(graph, context.resources.displayMetrics.density)

        targetsHz.forEach { targetHz ->
            val sourceHz = if (targetHz == 20.0) 30.0 else targetHz * 1.2
            profile.value = profile.value.copy(
                eqBands = listOf(profile.value.eqBands.single().copy(frequencyHz = sourceHz, gainDb = 0.0)),
            )
            composeRule.waitForIdle()
            val from = plot.position(sourceHz, 0.0)
            val to = plot.position(targetHz, 0.0)
            graph.performTouchInput {
                down(from)
                moveTo(to)
                up()
            }
            composeRule.runOnIdle {
                val actualHz = requireNotNull(editedProfile.value).eqBands.single().frequencyHz
                val relativeError = kotlin.math.abs(actualHz - targetHz) / targetHz
                assertTrue("$targetHz Hz mapped to $actualHz Hz", relativeError <= 0.03)
            }
        }
    }

    @Test
    fun realPointerDragClampsGainToBothPlotEnds() {
        val profile = mutableStateOf(
            DspAudioProfile(
                id = "gain-limits-test",
                name = "Gain limits test",
                eqBands = listOf(DspEqBand(DspEqType.PEAK, frequencyHz = 1_000.0, gainDb = 0.0)),
            ),
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            MaterialTheme {
                DspEqEditorPane(profile = profile.value, sampleRateHz = 48_000, onProfileChange = { profile.value = it })
            }
        }
        val graph = composeRule.onNodeWithContentDescription(
            context.getString(R.string.dsp_eq_graph_description, 20_000, 48_000),
        )
        val plot = graphPlot(graph, context.resources.displayMetrics.density)

        listOf(18.0, 0.0, -18.0).forEach { gainDb ->
            val current = profile.value.eqBands.single()
            graph.performTouchInput {
                down(plot.position(current.frequencyHz, current.gainDb))
                moveTo(plot.position(current.frequencyHz, gainDb))
                up()
            }
            composeRule.runOnIdle { assertEquals(gainDb, profile.value.eqBands.single().gainDb, 0.15) }
        }
    }

    @Test
    fun gainDragChangesOnlyPeakAndShelvesAndPreservesHighPassLowPassAndNotchGain() {
        val types = listOf(
            DspEqType.PEAK,
            DspEqType.LOW_SHELF,
            DspEqType.HIGH_SHELF,
            DspEqType.HIGH_PASS,
            DspEqType.LOW_PASS,
            DspEqType.NOTCH,
        )
        val profile = mutableStateOf(
            DspAudioProfile(
                id = "filter-gain-contract-test",
                name = "Filter gain contract test",
                eqBands = listOf(DspEqBand(DspEqType.PEAK, frequencyHz = 1_000.0, gainDb = 3.0)),
            ),
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            MaterialTheme {
                DspEqEditorPane(profile = profile.value, sampleRateHz = 48_000, onProfileChange = { profile.value = it })
            }
        }
        val graph = composeRule.onNodeWithContentDescription(
            context.getString(R.string.dsp_eq_graph_description, 20_000, 48_000),
        )
        val plot = graphPlot(graph, context.resources.displayMetrics.density)

        types.forEach { type ->
            profile.value = profile.value.copy(eqBands = listOf(DspEqBand(type, 1_000.0, gainDb = 3.0)))
            composeRule.waitForIdle()
            graph.performTouchInput {
                down(plot.position(1_000.0, 3.0))
                moveTo(plot.position(1_000.0, -10.0))
                up()
            }
            val actualGain = profile.value.eqBands.single().gainDb
            if (type in setOf(DspEqType.PEAK, DspEqType.LOW_SHELF, DspEqType.HIGH_SHELF)) {
                assertEquals("$type gain should be editable", -10.0, actualGain, 0.15)
            } else {
                assertEquals("$type gain should be invariant", 3.0, actualGain, 0.0)
            }
        }
    }

    @Test
    fun overlappingHandlesPreferTheSelectedBandAcrossRecomposition() {
        val overlap = DspEqBand(DspEqType.PEAK, frequencyHz = 1_000.0, gainDb = 0.0)
        val profile = mutableStateOf(
            DspAudioProfile(id = "overlap-test", name = "Overlap test", eqBands = listOf(overlap, overlap)),
        )
        val bassEnabled = mutableStateOf(false)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            MaterialTheme {
                DspEqEditorPane(
                    profile = profile.value,
                    sampleRateHz = 48_000,
                    onProfileChange = { profile.value = it },
                    dynamicBassEnabled = bassEnabled.value,
                )
            }
        }
        val bandOne = composeRule.onNodeWithContentDescription(context.getString(R.string.dsp_eq_select_band, 1))
        val bandTwo = composeRule.onNodeWithContentDescription(context.getString(R.string.dsp_eq_select_band, 2))
        bandTwo.performClick()
        val graph = composeRule.onNodeWithContentDescription(
            context.getString(R.string.dsp_eq_graph_description, 20_000, 48_000),
        )
        var plot = graphPlot(graph, context.resources.displayMetrics.density)
        graph.performTouchInput {
            val center = plot.position(1_000.0, 0.0)
            down(center)
            up()
        }
        composeRule.runOnIdle {
            assertEquals(context.getString(R.string.dsp_eq_selected), bandTwo.fetchSemanticsNode().config[
                androidx.compose.ui.semantics.SemanticsProperties.StateDescription,
            ])
        }

        bassEnabled.value = true
        composeRule.waitForIdle()
        plot = graphPlot(graph, context.resources.displayMetrics.density)
        graph.performTouchInput {
            val from = plot.position(1_000.0, 0.0)
            down(from)
            moveTo(plot.position(2_000.0, 0.0))
            up()
        }
        composeRule.runOnIdle {
            assertEquals(1_000.0, profile.value.eqBands[0].frequencyHz, 0.0)
            assertTrue(profile.value.eqBands[1].frequencyHz > 1_500.0)
            assertEquals(context.getString(R.string.dsp_eq_not_selected), bandOne.fetchSemanticsNode().config[
                androidx.compose.ui.semantics.SemanticsProperties.StateDescription,
            ])
        }
    }

    @Test
    fun cancelingAnActivePointerDragFinishesTheSingleEditTransaction() {
        val startingBand = DspEqBand(DspEqType.PEAK, frequencyHz = 1_000.0, gainDb = 0.0)
        val profile = mutableStateOf(DspAudioProfile(id = "cancel-test", name = "Cancel test", eqBands = listOf(startingBand)))
        val finishedEditCount = mutableStateOf(0)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            MaterialTheme {
                DspEqEditorPane(
                    profile = profile.value,
                    sampleRateHz = 48_000,
                    onProfileChange = { profile.value = it },
                    onEditFinished = { finishedEditCount.value += 1 },
                )
            }
        }
        val graph = composeRule.onNodeWithContentDescription(
            context.getString(R.string.dsp_eq_graph_description, 20_000, 48_000),
        )
        val plot = graphPlot(graph, context.resources.displayMetrics.density)
        graph.performTouchInput {
            down(plot.position(1_000.0, 0.0))
            moveTo(plot.position(2_000.0, 6.0))
            cancel()
        }
        composeRule.runOnIdle {
            assertEquals(1, finishedEditCount.value)
            assertTrue(profile.value.eqBands.single().frequencyHz > startingBand.frequencyHz)
        }
    }

    @Test
    fun switchingAwayDuringActiveDragFinishesTheSingleEditTransaction() {
        val startingBand = DspEqBand(DspEqType.PEAK, frequencyHz = 1_000.0, gainDb = 0.0)
        val profile = mutableStateOf(DspAudioProfile(id = "tab-switch-test", name = "Tab switch test", eqBands = listOf(startingBand)))
        val graphVisible = mutableStateOf(true)
        val finishedEditCount = mutableStateOf(0)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            MaterialTheme {
                if (graphVisible.value) {
                    DspEqEditorPane(
                        profile = profile.value,
                        sampleRateHz = 48_000,
                        onProfileChange = { edited ->
                            profile.value = edited
                            graphVisible.value = false
                        },
                        onEditFinished = { finishedEditCount.value += 1 },
                    )
                } else {
                    androidx.compose.material3.Text("EQ tab switched")
                }
            }
        }
        val graph = composeRule.onNodeWithContentDescription(
            context.getString(R.string.dsp_eq_graph_description, 20_000, 48_000),
        )
        val plot = graphPlot(graph, context.resources.displayMetrics.density)
        graph.performTouchInput {
            down(plot.position(1_000.0, 0.0))
            moveTo(plot.position(2_000.0, 6.0))
        }
        composeRule.onNodeWithText("EQ tab switched").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, finishedEditCount.value) }
    }

    @Test
    fun addingSecondPointerEndsDragAndFlushesOneEdit() {
        val startingBand = DspEqBand(DspEqType.PEAK, frequencyHz = 1_000.0, gainDb = 0.0)
        val profile = mutableStateOf(DspAudioProfile(id = "multi-pointer-test", name = "Multi pointer test", eqBands = listOf(startingBand)))
        val finishedEditCount = mutableStateOf(0)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            MaterialTheme {
                DspEqEditorPane(
                    profile = profile.value,
                    sampleRateHz = 48_000,
                    onProfileChange = { profile.value = it },
                    onEditFinished = { finishedEditCount.value += 1 },
                )
            }
        }
        val graph = composeRule.onNodeWithContentDescription(
            context.getString(R.string.dsp_eq_graph_description, 20_000, 48_000),
        )
        val plot = graphPlot(graph, context.resources.displayMetrics.density)
        val start = plot.position(1_000.0, 0.0)
        val dragValue = plot.position(1_500.0, 4.0)
        val ignoredValue = plot.position(3_000.0, 10.0)
        graph.performTouchInput {
            down(0, start)
            moveTo(0, dragValue)
            down(1, Offset(start.x + 12f, start.y))
            moveTo(0, ignoredValue)
            up(0)
            up(1)
        }
        composeRule.runOnIdle {
            assertEquals(1, finishedEditCount.value)
            assertTrue(profile.value.eqBands.single().frequencyHz < 2_500.0)
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

    private fun graphPlot(graph: androidx.compose.ui.test.SemanticsNodeInteraction, density: Float): GraphPlot {
        val bounds = graph.fetchSemanticsNode().boundsInRoot
        return GraphPlot(
            left = 30f * density,
            right = bounds.width - 4f * density,
            top = 4f * density,
            bottom = bounds.height - 18f * density,
        )
    }

    private data class GraphPlot(val left: Float, val right: Float, val top: Float, val bottom: Float) {
        fun position(frequencyHz: Double, gainDb: Double): Offset {
            val x = left + (right - left) * (ln(frequencyHz / 20.0) / ln(20_000.0 / 20.0)).toFloat()
            val y = top + (bottom - top) * ((18.0 - gainDb) / 36.0).toFloat()
            return Offset(x, y)
        }
    }
}
