# DSP Studio architecture

## Source-of-truth layers

1. The existing DspAudioProfile remains the immutable legacy base profile. Its schema-v7 encoding, Factory definitions, custom1/custom2 meaning, Cabin data, and IR references are preserved.
2. A new committed DSP Studio workspace schema v1 stores a complete base-profile snapshot plus Macro, Smart Loudness, Dynamic Bass, and module-preset selections. Missing workspace means legacy profile defaults with every new stage disabled and every Macro at 50.
3. An editing draft is isolated from the saved workspace. Preview changes runtime output only. Save atomically commits the workspace; Cancel requests a new generation restoring the saved snapshot.
4. DspTuningResolver is the single deterministic owner of intent-to-effective-setting mapping and Gain Budget decisions. It never writes resolved values back into DspAudioProfile.
5. Requested generation/owner epoch and renderer-acknowledged active generation remain distinct. Runtime telemetry is transient and is never persisted.

## DSP invariants

- Macro 50/50/50/50 and all new controls disabled produce the existing graph output. The new stages have fixed storage and no processing-time allocation.
- Macro Tone and Width, Smart Loudness, and Dynamic Bass have independent intent and control values. Their positive gain is bounded in one resolver-owned safety budget. Dynamic Bass detects pre-boost PCM and uses one linked stereo control envelope.
- Loudness follows only a trustworthy, route-specific, explicitly calibrated volume mapping. Unverified volume input is AUTO_UNAVAILABLE; MANUAL and FIXED remain available. PCM level is never a substitute for user playback volume.
- No control thread writes Native graph state. Hot commands are immutable, validated, latest-wins snapshots and are applied on the same renderer-owned thread immediately before processing a block. Acknowledgement is emitted after that block completes.
- Hot/Warm/Deferred classification compares the changed fields. The three new parameter-only stages can be Hot without rebuilding a stateful legacy graph. Filter topology, IR, oversampling, and latency changes stay Warm or Deferred until their lifecycle is proven.
- Master-off on a new AudioTrack retains the legacy bit-exact PCM16 bypass. MEDIA-only policy, existing mid-track latency preservation, sample rates, channels, PCM16 conversion, and PEQ/Cabin capacity remain intact.

## Persistence and presets

- Existing profile schema v7 and custom1/custom2 are read and written under their current rules; no implicit migration or write occurs when a workspace is absent.
- Workspace, module preset, and Macro preset stores use independent versioned schemas and AtomicFile commits. Unknown future schema is preserved without overwrite. IDs and payloads are validated before filesystem access.
- Factory presets are source-defined and immutable. Applying a module preset changes only that module's fields. Macro presets contain only the four Macro values.
- Preview, Save, and renderer activation are separate states. A successful file commit does not imply the audio renderer has adopted that generation.

## Renderer and Native ownership

- DspRuntimeConfigStore remains the generation source. DspUpdatePlanner partitions changes into HOT, WARM, and DEFERRED; WARM candidates continue to use the existing non-audio preparation/reaper lifecycle.
- The Hot mailbox is bounded to one immutable latest snapshot and includes generation, editor owner epoch, renderer owner token, and PCM format epoch. Invalid, stale, non-MEDIA, or closed-owner commands are rejected.
- Native handle create/destroy remain under the existing generation-and-lease registry. Hot application and process are serialized on the renderer worker; C state is not concurrently mutated from JNI callers.
- Audio-thread code performs no allocation, lock wait, disk I/O, Compose callback, or logging loop. Gains are ramped with fixed state. Telemetry is published at a bounded rate from atomics or existing diagnostic snapshots.

## UI and rollout

- FULL and 55:45 Compact Split observe the same coordinator and workspace. CarPlay video/touch Surface and session ownership do not change when the DSP pane or tab changes.
- Studio is the primary screen; EQ, Spatial, Dynamics, and Advanced are separate views. Compact Split uses short navigation controls rather than one long stacked editor.
- All new settings default to disabled/neutral. Features are not marked LIVE until a matching renderer generation has processed a block. No-media, deferred, failed, saved, and preview states remain distinct.
- Experimental rollout flags remain default-off until their unit/native/build/renderer gates pass. AUTO loudness additionally remains unavailable until target-specific volume calibration is evidenced. High-risk IR/latency switching remains Deferred by design without hardware evidence.

## Current source boundaries and package ownership

| Area | Existing ownership | Planned extension |
|---|---|---|
| Profile and screen | common/dsp and DspSettingsScreen.kt | Workspace/coordinator/stores; new composables under dsp/ui |
| Runtime config and resolver | shared/media/dsp and common/dsp | Immutable new config, resolver, planner, Hot command |
| PCM runner | AndroidMediaSink.kt and DspPcmPipeline.kt | Renderer-owned Hot mailbox and post-block ACK; retain existing Warm path |
| Native graph | dsp_engine.c, dsp_graph.c, dsp_jni.c | Fixed new stages, validated Hot command, tests in host and JNI suites |
| Build and evidence | Android.mk, test_dsp_native.sh, check_jni_mapping.py, docs | Update all corresponding surfaces for any C/JNI addition |
