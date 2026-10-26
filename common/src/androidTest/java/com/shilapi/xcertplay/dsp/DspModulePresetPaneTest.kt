package com.shilapi.xcertplay.dsp

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DspModulePresetPaneTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun selectingAndApplyingFactoryPresetIsScopedToItsModule() {
        val selectedModule = mutableStateOf("macros")
        val selectedPresetId = mutableStateOf<String?>(null)
        val presets = mutableStateOf(DspFactoryPresets.modulePresets().filter { it.moduleKey == "macros" })
        val appliedPreset = mutableStateOf<DspModulePreset?>(null)

        composeRule.setContent {
            MaterialTheme {
                DspModulePresetPane(
                    moduleKey = selectedModule.value,
                    moduleKeys = listOf("macros", "bass"),
                    presets = presets.value,
                    selectedPresetId = selectedPresetId.value,
                    enabled = true,
                    onModuleKeyChange = { key ->
                        selectedModule.value = key
                        selectedPresetId.value = null
                        presets.value = DspFactoryPresets.modulePresets().filter { it.moduleKey == key }
                    },
                    onPresetSelect = { selectedPresetId.value = it },
                    onApply = { appliedPreset.value = it },
                    onSave = {},
                    onDelete = {},
                    onFactoryReset = {},
                )
            }
        }

        composeRule.onNodeWithText("Sound macros ▾").performClick()
        composeRule.onNodeWithText("Bass").performClick()
        composeRule.onNodeWithText("Neutral · Factory ▾").performClick()
        composeRule.onNodeWithText("Neutral · Factory").performClick()
        composeRule.onNodeWithText("Apply preset to draft").performClick()

        assertEquals("bass", appliedPreset.value?.moduleKey)
        assertEquals("factory-bass-neutral", appliedPreset.value?.id)
    }

    @Test
    fun userPresetSaveReturnsTheEnteredName() {
        val savedName = mutableStateOf("")
        val factoryPreset = DspModulePreset(
            id = "factory-macro-neutral",
            moduleKey = "macros",
            name = "Neutral",
            payload = JSONObject(),
            factory = true,
        )
        composeRule.setContent {
            MaterialTheme {
                DspModulePresetPane(
                    moduleKey = "macros",
                    moduleKeys = listOf("macros"),
                    presets = listOf(factoryPreset),
                    selectedPresetId = factoryPreset.id,
                    enabled = true,
                    onModuleKeyChange = {},
                    onPresetSelect = {},
                    onApply = {},
                    onSave = { savedName.value = it },
                    onDelete = {},
                    onFactoryReset = {},
                )
            }
        }

        composeRule.onNodeWithText("Save as User preset").performClick()
        composeRule.onNodeWithText("Preset name").performTextInput("Night drive")
        composeRule.onNodeWithText("Save").performClick()

        assertEquals("Night drive", savedName.value)
    }

    @Test
    fun userPresetDeleteRequiresConfirmation() {
        val deletedId = mutableStateOf<String?>(null)
        val factoryPreset = DspFactoryPresets.modulePresets().first { it.moduleKey == "bass" }
        val userPreset = DspModulePreset(
            id = "user_bass_night",
            moduleKey = "bass",
            name = "Night",
            payload = JSONObject().put("enabled", true).put("gainDb", 2.0).put("frequencyHz", 80.0),
        )

        composeRule.setContent {
            MaterialTheme {
                DspModulePresetPane(
                    moduleKey = "bass",
                    moduleKeys = listOf("bass"),
                    presets = listOf(factoryPreset, userPreset),
                    selectedPresetId = userPreset.id,
                    enabled = true,
                    onModuleKeyChange = {},
                    onPresetSelect = {},
                    onApply = {},
                    onSave = {},
                    onDelete = { deletedId.value = it },
                    onFactoryReset = {},
                )
            }
        }

        composeRule.onNodeWithText("Delete user preset").performClick()
        assertEquals(null, deletedId.value)
        composeRule.onNodeWithText("Delete user preset?").assertExists()
        composeRule.onNodeWithText("Delete").performClick()

        assertEquals(userPreset.id, deletedId.value)
    }

    @Test
    fun factoryResetRequiresConfirmation() {
        val reset = mutableStateOf(false)
        val factoryPreset = DspFactoryPresets.modulePresets().first { it.moduleKey == "bass" }

        composeRule.setContent {
            MaterialTheme {
                DspModulePresetPane(
                    moduleKey = "bass",
                    moduleKeys = listOf("bass"),
                    presets = listOf(factoryPreset),
                    selectedPresetId = factoryPreset.id,
                    enabled = true,
                    onModuleKeyChange = {},
                    onPresetSelect = {},
                    onApply = {},
                    onSave = {},
                    onDelete = {},
                    onFactoryReset = { reset.value = true },
                )
            }
        }

        composeRule.onNodeWithText("Factory reset module").performClick()
        assertEquals(false, reset.value)
        composeRule.onNodeWithText("Reset module").performClick()

        assertEquals(true, reset.value)
    }
}
