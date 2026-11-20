package com.shilapi.xcertplay

import android.Manifest
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import com.shilapi.xcertplay.dsp.DspEditorSavedState
import com.shilapi.xcertplay.dsp.DspProfileRuntime
import com.shilapi.xcertplay.shared.AppLanguage

class DspSettingsActivity : ComponentActivity() {
    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            DspProfileRuntime.peekInstance()?.speedCompensation?.onLocationPermissionResult()
        }
    private var speedHostAttached = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.localizedContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val runtime = DspProfileRuntime.get(applicationContext)
        savedInstanceState?.getString(DspEditorSavedState.DRAFT_PROFILE)?.let { savedDraft ->
            runtime.decodeProfileDraftFromSavedState(savedDraft)?.let(runtime.editorSession::restoreDraft)
        }
        setContent {
            MaterialTheme(colorScheme = dspColorScheme()) {
                DspSettingsScreen(
                    runtime = runtime,
                    onBack = ::finish,
                    onRequestLocationPermission = ::requestSpeedLocationPermission,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        DspProfileRuntime.peekInstance()?.speedCompensation?.let { speed ->
            if (!speedHostAttached) {
                speedHostAttached = true
                speed.attachHost()
            }
        }
    }

    override fun onStop() {
        // The manual test speed belongs to this screen; never let it drive audio after leaving.
        DspProfileRuntime.peekInstance()?.speedCompensation?.setTestSpeed(null)
        if (speedHostAttached) {
            speedHostAttached = false
            DspProfileRuntime.peekInstance()?.speedCompensation?.detachHost()
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        DiPlayUiFocus.resumed(this, DiPlaySurface.DSP_SETTINGS)
        DspProfileRuntime.peekInstance()?.let { runtime ->
            runtime.editorSession.refreshFromRuntime(runtime.selectedProfile(), runtime.isDspEnabled())
        }
    }

    override fun onStop() {
        DiPlayUiFocus.stopped(this)
        super.onStop()
    }

    override fun onPause() {
        if (isFinishing) {
            // Back to the DiPlay settings screen that opened this editor, without it resuming
            // first: keep it protected across the transition.
            DiPlayUiFocus.announceReturn(this, DiPlaySurface.SETTINGS)
        } else {
            DiPlayUiFocus.paused(this)
        }
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        DspProfileRuntime.peekInstance()?.let { runtime ->
            outState.putString(
                DspEditorSavedState.DRAFT_PROFILE,
                runtime.encodeProfileDraftForSavedState(runtime.editorSession.draftProfile),
            )
        }
        super.onSaveInstanceState(outState)
    }

    private fun requestSpeedLocationPermission() {
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )
    }
}
