package com.shilapi.xcertplay

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shilapi.xcertplay.dsp.DspFactoryPresets
import com.shilapi.xcertplay.dsp.DspProfileRuntime
import com.shilapi.xcertplay.dsp.DspStudioRestoreRequest
import com.shilapi.xcertplay.dsp.DspPresetStoreResult
import com.shilapi.xcertplay.dsp.DspRuntimeConfigStore
import com.shilapi.xcertplay.dsp.DspStudioComparisonSide
import com.shilapi.xcertplay.dsp.DspTuningResolver
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.dsp.DspRendererApplyPhase
import com.shilapi.xcertplay.media.dsp.DspRendererApplyStatus
import java.io.File
import java.util.Locale
import kotlin.math.ln
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DspSettingsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactTabScrollPositionsAreIndependentAndRestoredOnReturn() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val filesDirectory = File(context.cacheDir, "dsp-settings-tab-scroll-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val preferences = context.getSharedPreferences(TEST_PREFERENCES_NAME, android.content.Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val runtime = DspProfileRuntime.createForTesting(
            context = context,
            filesDirectory = filesDirectory,
            preferencesName = TEST_PREFERENCES_NAME,
        )
        try {
            restoreCommittedWorkspace(runtime)
            composeRule.setContent {
                MaterialTheme {
                    DspSettingsScreen(
                        runtime = runtime,
                        presentation = DspPresentationMode.COMPACT_SPLIT,
                        onBack = {},
                    )
                }
            }

            composeRule.onNodeWithTag("dsp-tab-scroll-pane").performTouchInput { swipeUp() }
            val scrollPositions = mutableMapOf(
                DspStudioTab.STUDIO to composeRule.onNodeWithTag("dsp-tab-scroll-pane").verticalScrollPosition(),
            )
            assertTrue("Studio tab should scroll", scrollPositions.getValue(DspStudioTab.STUDIO) > 0f)

            val selectedBandDescription = context.getString(R.string.dsp_eq_select_band, 3)
            val expectedGainDb = 3.0
            val tabs = dspStudioTabs()
            val visits = mutableMapOf<DspStudioTab, Int>()
            repeat(100) { index ->
                val tab = tabs[(index + 1) % tabs.size]
                visits[tab] = (visits[tab] ?: 0) + 1
                val firstVisit = tab !in scrollPositions
                val expectedPosition = scrollPositions[tab] ?: 0f
                composeRule.onNodeWithContentDescription(context.getString(tab.labelRes)).performClick()
                assertEquals(
                    "Tab ${tab.name} should begin at its own saved position",
                    expectedPosition,
                    composeRule.onNodeWithTag("dsp-tab-scroll-pane").verticalScrollPosition(),
                )

                if (firstVisit) {
                    when (tab) {
                        DspStudioTab.STUDIO ->
                            composeRule.onNodeWithTag("dsp-tab-scroll-pane").performTouchInput { swipeUp() }
                        DspStudioTab.EQ -> {
                            composeRule.onNodeWithContentDescription(selectedBandDescription)
                                .performScrollTo()
                                .performClick()
                            val gainDescription = context.getString(R.string.dsp_eq_band_gain, 3)
                            composeRule.onNodeWithContentDescription(gainDescription)
                                .performScrollTo()
                                .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                                    setProgress(expectedGainDb.toFloat())
                                }
                            composeRule.waitUntil(timeoutMillis = 5_000) {
                                runtime.editorSession.draftProfile.eqBands[2].gainDb == expectedGainDb
                            }
                            composeRule.onNodeWithTag("dsp-tab-scroll-pane").performTouchInput { swipeUp() }
                        }
                        DspStudioTab.SPATIAL, DspStudioTab.DYNAMICS ->
                            composeRule.onNodeWithTag("dsp-tab-scroll-pane").performTouchInput { swipeUp() }
                        DspStudioTab.ADVANCED -> {
                            composeRule.onNode(isToggleable()).performClick()
                            composeRule.onAllNodesWithText(context.getString(R.string.dsp_auto_headroom))[0]
                                .performScrollTo()
                                .assertIsDisplayed()
                        }
                    }
                    val position = composeRule.onNodeWithTag("dsp-tab-scroll-pane").verticalScrollPosition()
                    assertTrue("${tab.name} tab should scroll", position > 0f)
                    scrollPositions[tab] = position
                } else if (tab == DspStudioTab.EQ) {
                    assertEquals(
                        context.getString(R.string.dsp_eq_selected),
                        composeRule.onNodeWithContentDescription(selectedBandDescription)
                            .fetchSemanticsNode().config[SemanticsProperties.StateDescription],
                    )
                    assertEquals(
                        expectedGainDb,
                        runtime.editorSession.draftProfile.eqBands[2].gainDb,
                        0.05,
                    )
                }
            }
            tabs.forEach { tab -> assertEquals(20, visits[tab]) }
        } finally {
            runtime.closeForTesting()
        }
    }

    @Test
    fun peqGraphDragCommitsOneUndoStepAndFlushesTheFinalDraftValue() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val filesDirectory = File(context.cacheDir, "dsp-eq-graph-undo-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val preferences = context.getSharedPreferences(TEST_PREFERENCES_NAME, android.content.Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val runtime = DspProfileRuntime.createForTesting(
            context = context,
            filesDirectory = filesDirectory,
            preferencesName = TEST_PREFERENCES_NAME,
        )
        try {
            restoreCommittedWorkspace(runtime)
            val studioSession = runtime.createStudioSessionCoordinator()
            val originalBand = runtime.editorSession.draftProfile.eqBands.first()
            val rateHz = runtime.activePlaybackSampleRateHz() ?: 44_100
            val maxFrequencyHz = minOf(20_000.0, rateHz * 0.45)
            val targetHz = if (originalBand.frequencyHz < maxFrequencyHz / 1.5) {
                originalBand.frequencyHz * 1.5
            } else {
                originalBand.frequencyHz / 1.5
            }
            composeRule.setContent {
                MaterialTheme {
                    DspSettingsScreen(
                        runtime = runtime,
                        presentation = DspPresentationMode.COMPACT_SPLIT,
                        onBack = {},
                    )
                }
            }

            composeRule.onNodeWithContentDescription(context.getString(DspStudioTab.EQ.labelRes)).performClick()
            val graph = composeRule.onNodeWithContentDescription(
                context.getString(R.string.dsp_eq_graph_description, maxFrequencyHz.toInt(), rateHz),
            )
            graph.performScrollTo()
            val bounds = graph.fetchSemanticsNode().boundsInRoot
            val density = context.resources.displayMetrics.density
            val plotLeft = 30f * density
            val plotRight = bounds.width - 4f * density
            val plotTop = 4f * density
            val plotBottom = bounds.height - 18f * density
            fun position(frequencyHz: Double, gainDb: Double): Offset = Offset(
                plotLeft + (plotRight - plotLeft) *
                    (ln(frequencyHz / 20.0) / ln(maxFrequencyHz / 20.0)).toFloat(),
                plotTop + (plotBottom - plotTop) * ((18.0 - gainDb) / 36.0).toFloat(),
            )
            graph.performTouchInput {
                down(position(originalBand.frequencyHz, originalBand.gainDb))
                moveTo(position(targetHz, originalBand.gainDb))
                up()
            }
            composeRule.waitUntil(timeoutMillis = 5_000) {
                kotlin.math.abs(runtime.editorSession.draftProfile.eqBands.first().frequencyHz - targetHz) / targetHz < 0.04
            }
            composeRule.waitUntil(timeoutMillis = 5_000) { studioSession.canUndo }
            composeRule.waitUntil(timeoutMillis = 15_000) {
                kotlin.math.abs(runtime.configProvider.snapshot().peqBands.first().frequencyHz - targetHz) / targetHz < 0.04
            }
            val finalDraftHz = runtime.editorSession.draftProfile.eqBands.first().frequencyHz
            assertTrue(kotlin.math.abs(finalDraftHz - targetHz) / targetHz < 0.04)
            assertFalse(
                "No-media preview must not be reported as an active renderer ACK",
                runtime.configProvider.rendererApplyStatus()?.phase == DspRendererApplyPhase.ACTIVE,
            )

            composeRule.onNodeWithContentDescription(context.getString(DspStudioTab.STUDIO.labelRes)).performClick()
            composeRule.onNodeWithText("Undo").performScrollTo().assertIsEnabled().performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                runtime.editorSession.draftProfile.eqBands.first().frequencyHz == originalBand.frequencyHz
            }
            assertFalse(studioSession.canUndo)
        } finally {
            try {
                restoreCommittedWorkspace(runtime)
            } finally {
                runtime.closeForTesting()
                preferences.edit().clear().commit()
                filesDirectory.deleteRecursively()
            }
        }
    }

    private fun SemanticsNodeInteraction.verticalScrollPosition(): Float =
        fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    @Test
    fun macroPreviewSurvivesCompactAndFullLayoutsAndCancelRestoresCommittedState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val filesDirectory = File(context.cacheDir, "dsp-settings-screen-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val preferences = context.getSharedPreferences(TEST_PREFERENCES_NAME, android.content.Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val runtime = DspProfileRuntime.createForTesting(
            context = context,
            filesDirectory = filesDirectory,
            preferencesName = TEST_PREFERENCES_NAME,
        )
        try {
            restoreCommittedWorkspace(runtime)
            val studioSession = runtime.createStudioSessionCoordinator()
            val initialConfig = runtime.configProvider.snapshot()
            val expectedMacroPowerEnabled = !initialConfig.macroTone.enabled
            val presentation = mutableStateOf(DspPresentationMode.COMPACT_SPLIT)

            composeRule.setContent {
                MaterialTheme {
                    DspSettingsScreen(
                        runtime = runtime,
                        presentation = presentation.value,
                        onBack = {},
                    )
                }
            }

            val deferredReason = "PEQ local warm-up is disabled; keeping the active pipeline until the next AudioTrack generation"
            val configStore = runtime.configProvider as DspRuntimeConfigStore
            val generation = runtime.configProvider.versionedSnapshot().generation
            configStore.reportRendererApplyStatus(
                DspRendererApplyStatus(
                    generation = generation,
                    phase = DspRendererApplyPhase.DEFERRED,
                    activeGeneration = 0L,
                    reason = deferredReason,
                ),
            )
            composeRule.onNodeWithContentDescription("Studio, selected").assertExists()
            composeRule.onNodeWithContentDescription("Spatial").performScrollTo().performClick()
            composeRule.onNodeWithContentDescription("Spatial, selected").assertExists()
            composeRule.onNodeWithText(context.getString(R.string.dsp_stereo_width)).assertExists()
            composeRule.onNodeWithContentDescription("Dynamics").performScrollTo().performClick()
            composeRule.onNodeWithContentDescription("Dynamics, selected").assertExists()
            composeRule.onNodeWithText("Dynamic Bass").assertExists()
            val thresholdBeforeDrag = studioSession.preview.baseProfileSnapshot.compressor.thresholdDb
            val thresholdAfterDrag = if (thresholdBeforeDrag < -20.0) -10.0f else -50.0f
            composeRule.onNodeWithContentDescription("Compressor threshold")
                .performScrollTo()
                .performSemanticsAction(SemanticsActions.SetProgress) { setProgress -> setProgress(thresholdAfterDrag) }
            composeRule.waitUntil(timeoutMillis = 5_000) {
                kotlin.math.abs(
                    runtime.editorSession.draftProfile.compressor.thresholdDb - thresholdAfterDrag.toDouble(),
                ) < 0.05
            }
            composeRule.waitUntil(timeoutMillis = 5_000) {
                kotlin.math.abs(
                    studioSession.preview.baseProfileSnapshot.compressor.thresholdDb - thresholdAfterDrag.toDouble(),
                ) < 0.05
            }
            composeRule.onNodeWithContentDescription("Advanced").performScrollTo().performClick()
            composeRule.onNodeWithContentDescription("Advanced, selected").assertExists()
            composeRule.onNodeWithContentDescription("Studio").performClick()
            composeRule.onNodeWithContentDescription("Studio, selected").assertExists()
            composeRule.onNodeWithContentDescription("Level match").assertIsNotEnabled()
            composeRule.onNodeWithText(context.getString(R.string.dsp_renderer_apply_waiting)).assertExists()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithText(deferredReason, substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("Undo").performScrollTo().assertIsEnabled().performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                studioSession.preview.baseProfileSnapshot.compressor.thresholdDb == thresholdBeforeDrag
            }
            assertFalse(studioSession.canUndo)
            composeRule.onNodeWithText("Bass Impact").assertExists()
            composeRule.onNodeWithContentDescription("Macro Power").performClick()
            assertMacroPowerEnabled(expectedMacroPowerEnabled)
            composeRule.waitUntil(timeoutMillis = 15_000) {
                runtime.configProvider.snapshot().macroTone.enabled == expectedMacroPowerEnabled
            }
            val beforeDrag = runtime.configProvider.snapshot()
            composeRule.onNodeWithContentDescription("Bass Impact")
                .performScrollTo()
                .performSemanticsAction(SemanticsActions.SetProgress) { setProgress -> setProgress(80f) }
            composeRule.waitUntil(timeoutMillis = 15_000) {
                runtime.configProvider.snapshot().macroTone.bassGainDb > beforeDrag.macroTone.bassGainDb
            }
            composeRule.onNodeWithText("Undo").performScrollTo().assertIsEnabled().performClick()
            composeRule.waitUntil(timeoutMillis = 15_000) {
                runtime.configProvider.snapshot().macroTone.bassGainDb == beforeDrag.macroTone.bassGainDb
            }
            configStore.reportRendererApplyStatus(
                DspRendererApplyStatus(
                    generation = runtime.configProvider.versionedSnapshot().generation,
                    phase = DspRendererApplyPhase.DEFERRED,
                    activeGeneration = 0L,
                    reason = deferredReason,
                ),
            )

            presentation.value = DspPresentationMode.FULL
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("Studio, selected").assertExists()
            composeRule.onNodeWithText(context.getString(R.string.dsp_save_custom_1)).performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText(context.getString(R.string.dsp_save_custom_2)).performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithContentDescription("Advanced").performClick()
            composeRule.onNodeWithContentDescription("Advanced, selected").assertExists()
            composeRule.onNode(isToggleable()).performClick()
            composeRule.onNodeWithText(context.getString(R.string.dsp_renderer_apply_status)).performScrollTo()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithText(deferredReason, substring = true).fetchSemanticsNodes().isNotEmpty()
            }

            presentation.value = DspPresentationMode.COMPACT_SPLIT
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("Studio").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("Studio, selected").assertExists()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Unsaved DSP Studio draft").assertExists()
            composeRule.onNodeWithText("Preview").performScrollTo().assertIsEnabled().performClick()
            composeRule.waitUntil(timeoutMillis = 15_000) {
                runtime.configProvider.snapshot().macroTone.enabled == !initialConfig.macroTone.enabled
            }
            assertEquals(initialConfig.enabled, runtime.configProvider.snapshot().enabled)

            presentation.value = DspPresentationMode.FULL
            composeRule.waitForIdle()
            composeRule.onNodeWithText("DSP Studio").assertExists()
            assertMacroPowerEnabled(expectedMacroPowerEnabled)

            presentation.value = DspPresentationMode.COMPACT_SPLIT
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("Studio, selected").assertExists()
            assertMacroPowerEnabled(expectedMacroPowerEnabled)
            composeRule.onNodeWithText("Cancel preview").performScrollTo().performClick()
            composeRule.waitUntil(timeoutMillis = 15_000) {
                val restored = runtime.configProvider.snapshot()
                restored.macroTone.enabled == initialConfig.macroTone.enabled &&
                    restored.enabled == initialConfig.enabled
            }
            assertMacroPowerEnabled(initialConfig.macroTone.enabled)
        } finally {
            try {
                restoreCommittedWorkspace(runtime)
            } finally {
                preferences.edit().clear().commit()
                filesDirectory.deleteRecursively()
            }
        }
    }

    @Test
    fun eqFilterTypeChooserShowsLocalizedLabels() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val japaneseConfiguration = Configuration(context.resources.configuration).apply {
            setLocale(Locale.JAPANESE)
        }
        val japaneseContext = context.createConfigurationContext(japaneseConfiguration)
        val filesDirectory = File(context.cacheDir, "dsp-eq-filter-localization-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val preferences = context.getSharedPreferences(TEST_PREFERENCES_NAME, android.content.Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val runtime = DspProfileRuntime.createForTesting(
            context = context,
            filesDirectory = filesDirectory,
            preferencesName = TEST_PREFERENCES_NAME,
        )
        try {
            restoreCommittedWorkspace(runtime)
            composeRule.setContent {
                CompositionLocalProvider(
                    LocalConfiguration provides japaneseConfiguration,
                    LocalResources provides japaneseContext.resources,
                ) {
                    MaterialTheme {
                        DspSettingsScreen(
                            runtime = runtime,
                            presentation = DspPresentationMode.COMPACT_SPLIT,
                            onBack = {},
                        )
                    }
                }
            }

            composeRule.onNodeWithContentDescription(japaneseContext.getString(R.string.dsp_studio_tab_eq))
                .performScrollTo()
                .performClick()
            val filterTypeButton = japaneseContext.getString(
                R.string.dsp_eq_filter_type_summary,
                japaneseContext.getString(R.string.dsp_eq_filter_peak),
            )
            composeRule.onAllNodesWithText(filterTypeButton)[0].performScrollTo().performClick()
            composeRule.onNodeWithText(japaneseContext.getString(R.string.dsp_eq_filter_low_shelf)).assertIsDisplayed()
            assertTrue(composeRule.onAllNodesWithText("LOW_SHELF").fetchSemanticsNodes().isEmpty())
        } finally {
            try {
                restoreCommittedWorkspace(runtime)
            } finally {
                runtime.closeForTesting()
                preferences.edit().clear().commit()
                filesDirectory.deleteRecursively()
            }
        }
    }

    @Test
    fun moduleFactoryResetChangesOnlyTheSelectedModuleDraft() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val filesDirectory = File(context.cacheDir, "dsp-module-factory-reset-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val preferences = context.getSharedPreferences(TEST_PREFERENCES_NAME, android.content.Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val runtime = DspProfileRuntime.createForTesting(
            context = context,
            filesDirectory = filesDirectory,
            preferencesName = TEST_PREFERENCES_NAME,
        )
        try {
            restoreCommittedWorkspace(runtime)
            val studioSession = runtime.createStudioSessionCoordinator()
            val before = studioSession.preview
            val factoryPreset = DspFactoryPresets.modulePresets().first { it.moduleKey == "bass" && it.id == "factory-bass-neutral" }
            val expected = runtime.applyStudioModulePreset(before, factoryPreset)!!.copy(
                selectedModulePresetIds = before.selectedModulePresetIds + ("bass" to factoryPreset.id),
            )

            composeRule.setContent {
                MaterialTheme {
                    DspSettingsScreen(
                        runtime = runtime,
                        presentation = DspPresentationMode.COMPACT_SPLIT,
                        onBack = {},
                    )
                }
            }

            composeRule.onNodeWithText("Sound macros ▾").performScrollTo().performClick()
            composeRule.onNode(hasText("Bass") and hasClickAction()).performClick()
            composeRule.onNodeWithText("Factory reset module").performScrollTo().performClick()
            composeRule.onNodeWithText("Reset module").performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                studioSession.preview.selectedModulePresetIds["bass"] == factoryPreset.id
            }

            assertEquals(expected, studioSession.preview)
        } finally {
            try {
                restoreCommittedWorkspace(runtime)
            } finally {
                runtime.closeForTesting()
                preferences.edit().clear().commit()
                filesDirectory.deleteRecursively()
            }
        }
    }

    @Test
    fun moduleComparisonPreviewsOnlyItsModuleAndKeepsTheDraftUnchanged() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val filesDirectory = File(context.cacheDir, "dsp-module-comparison-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val preferences = context.getSharedPreferences(TEST_PREFERENCES_NAME, android.content.Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val runtime = DspProfileRuntime.createForTesting(
            context = context,
            filesDirectory = filesDirectory,
            preferencesName = TEST_PREFERENCES_NAME,
        )
        try {
            restoreCommittedWorkspace(runtime)
            val studioSession = runtime.createStudioSessionCoordinator()
            val committed = studioSession.compareA()
            studioSession.edit { workspace ->
                workspace.copy(
                    baseProfileSnapshot = workspace.baseProfileSnapshot.copy(
                        bass = workspace.baseProfileSnapshot.bass.copy(enabled = true, gainDb = 8.0),
                        compressor = workspace.baseProfileSnapshot.compressor.copy(thresholdDb = -12.0),
                    ),
                    macros = workspace.macros.copy(enabled = true, bassImpact = 100),
                )
            }
            val draft = studioSession.preview
            assertTrue(draft.baseProfileSnapshot.bass.gainDb != committed.baseProfileSnapshot.bass.gainDb)
            runtime.editorSession.restoreDraft(draft.baseProfileSnapshot)
            assertTrue(runtime.currentStudioFeatureFlags().macroOverlayEnabled)
            val expectedDraftMacroTone = DspTuningResolver.resolve(
                profile = draft.baseProfileSnapshot,
                masterEnabled = runtime.isDspEnabled(),
                macros = draft.macros,
                loudness = draft.smartLoudness,
                volumeReading = runtime.studioVolumeReading(),
                dynamicBass = draft.dynamicBass,
            ).runtimeConfig.macroTone

            composeRule.setContent {
                MaterialTheme {
                    DspSettingsScreen(
                        runtime = runtime,
                        presentation = DspPresentationMode.COMPACT_SPLIT,
                        onBack = {},
                    )
                }
            }

            composeRule.onNodeWithText("Sound macros ▾").performScrollTo().performClick()
            composeRule.onNode(hasText("Bass") and hasClickAction()).performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithText(context.getString(R.string.dsp_module_compare_b))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            val generationBeforeB = runtime.configProvider.versionedSnapshot().generation
            composeRule.onNodeWithText(context.getString(R.string.dsp_module_compare_b))
                .performScrollTo().performClick()
            composeRule.waitUntil(timeoutMillis = 15_000) {
                val snapshot = runtime.configProvider.versionedSnapshot()
                snapshot.generation > generationBeforeB &&
                    runtime.selectedProfile().bass.gainDb == draft.baseProfileSnapshot.bass.gainDb &&
                    snapshot.config.macroTone == expectedDraftMacroTone
            }
            assertEquals(DspStudioComparisonSide.B, studioSession.comparisonSide)
            val draftMacroTone = runtime.configProvider.versionedSnapshot().config.macroTone
            assertEquals(expectedDraftMacroTone, draftMacroTone)
            assertEquals(draft, studioSession.preview)

            val generationBeforeA = runtime.configProvider.versionedSnapshot().generation
            composeRule.onNodeWithText(context.getString(R.string.dsp_module_compare_a))
                .performScrollTo().performClick()
            composeRule.waitUntil(timeoutMillis = 15_000) {
                val snapshot = runtime.configProvider.versionedSnapshot()
                snapshot.generation > generationBeforeA &&
                    runtime.selectedProfile().bass.gainDb == committed.baseProfileSnapshot.bass.gainDb &&
                    snapshot.config.macroTone == draftMacroTone
            }

            assertEquals(DspStudioComparisonSide.A, studioSession.comparisonSide)
            assertEquals(committed.baseProfileSnapshot.bass.gainDb, runtime.selectedProfile().bass.gainDb, 0.0)
            assertEquals(draft.baseProfileSnapshot.compressor.thresholdDb, runtime.selectedProfile().compressor.thresholdDb, 0.0)
            assertEquals(draftMacroTone, runtime.configProvider.snapshot().macroTone)
            assertEquals(draft, studioSession.preview)
        } finally {
            try {
                restoreCommittedWorkspace(runtime)
            } finally {
                runtime.closeForTesting()
                preferences.edit().clear().commit()
                filesDirectory.deleteRecursively()
            }
        }
    }

    @Test
    fun deletingSelectedUserPresetFallsBackToFactoryWithoutChangingOtherDraftValues() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val filesDirectory = File(context.cacheDir, "dsp-module-preset-delete-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val preferences = context.getSharedPreferences(TEST_PREFERENCES_NAME, android.content.Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val runtime = DspProfileRuntime.createForTesting(
            context = context,
            filesDirectory = filesDirectory,
            preferencesName = TEST_PREFERENCES_NAME,
        )
        try {
            restoreCommittedWorkspace(runtime)
            val studioSession = runtime.createStudioSessionCoordinator()
            val presetId = "user_bass_delete"
            studioSession.edit { workspace ->
                workspace.copy(
                    baseProfileSnapshot = workspace.baseProfileSnapshot.copy(
                        bass = workspace.baseProfileSnapshot.bass.copy(enabled = true, gainDb = 8.0),
                    ),
                )
            }
            val saveResult = AtomicReference<DspPresetStoreResult?>()
            runtime.saveModulePresetAsync(studioSession.preview, "bass", presetId, "Night") {
                saveResult.set(it)
            }
            composeRule.waitUntil(timeoutMillis = 5_000) { saveResult.get() != null }
            assertEquals(DspPresetStoreResult.SAVED, saveResult.get())
            studioSession.edit { workspace ->
                workspace.copy(selectedModulePresetIds = workspace.selectedModulePresetIds + ("bass" to presetId))
            }
            val beforeDelete = studioSession.preview
            runtime.editorSession.restoreDraft(beforeDelete.baseProfileSnapshot)

            composeRule.setContent {
                MaterialTheme {
                    DspSettingsScreen(
                        runtime = runtime,
                        presentation = DspPresentationMode.COMPACT_SPLIT,
                        onBack = {},
                    )
                }
            }

            composeRule.onNodeWithText("Sound macros ▾").performScrollTo().performClick()
            composeRule.onNode(hasText("Bass") and hasClickAction()).performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithText("Night · User ▾").fetchSemanticsNodes().isNotEmpty()
            }
            val committedBassGainDb = studioSession.compareA().baseProfileSnapshot.bass.gainDb
            composeRule.onNodeWithText(context.getString(R.string.dsp_module_compare_a))
                .performScrollTo().performClick()
            composeRule.waitUntil(timeoutMillis = 15_000) {
                runtime.selectedProfile().bass.gainDb == committedBassGainDb
            }
            assertEquals(DspStudioComparisonSide.A, studioSession.comparisonSide)

            composeRule.onNodeWithText(context.getString(R.string.dsp_module_preset_delete_action))
                .performScrollTo().performClick()
            composeRule.onNodeWithText("Delete").performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                "bass" !in studioSession.preview.selectedModulePresetIds
            }
            composeRule.waitUntil(timeoutMillis = 15_000) {
                runtime.selectedProfile().bass.gainDb == beforeDelete.baseProfileSnapshot.bass.gainDb
            }
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithText("Custom values · not saved").fetchSemanticsNodes().isNotEmpty()
            }

            assertEquals(DspStudioComparisonSide.DRAFT, studioSession.comparisonSide)
            assertEquals(
                beforeDelete.copy(selectedModulePresetIds = beforeDelete.selectedModulePresetIds - "bass"),
                studioSession.preview,
            )
        } finally {
            try {
                restoreCommittedWorkspace(runtime)
            } finally {
                runtime.closeForTesting()
                preferences.edit().clear().commit()
                filesDirectory.deleteRecursively()
            }
        }
    }

    private fun assertMacroPowerEnabled(expectedEnabled: Boolean) {
        val actualState = composeRule.onNodeWithContentDescription("Macro Power")
            .fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        assertEquals(if (expectedEnabled) "On" else "Off", actualState)
    }

    private fun restoreCommittedWorkspace(runtime: DspProfileRuntime) {
        val request = AtomicReference<DspStudioRestoreRequest?>()
        runtime.requestCommittedStudioWorkspaceRestoreAsync(runtime.isDspEnabled()) { request.set(it) }
        composeRule.waitUntil(timeoutMillis = 5_000) { request.get() != null }
        assertTrue("screen test has no active renderer owner", request.get()?.rendererOwnerToken == null)
    }

    private companion object {
        const val TEST_PREFERENCES_NAME = "dsp_profile_selection_screen_test"
    }
}
