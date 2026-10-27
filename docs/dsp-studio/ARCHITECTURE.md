# DSP Studio architecture

## Data and editing model

- `DspAudioProfile` remains the immutable v7 legacy profile, including `custom1`/`custom2`, Factory definitions, impulse-response references, and the shared 15-active-band PEQ/Cabin limit.
- Static Bass Shelf is represented as an internal additional EQ graph filter. The 15-user-band PEQ/Cabin limit stays unchanged; the graph reserves a sixteenth filter only for Static Bass.
- `DspStudioWorkspace` schema v1 stores a complete base-profile snapshot, the four Macro controls, Smart Loudness settings, Dynamic Bass settings, and selected module-preset IDs. A missing workspace resolves from the selected legacy profile with new controls disabled and Macro values at 50.
- `DspStudioSessionCoordinator` keeps committed, draft, and preview workspaces separate and provides A/B, Undo/Redo, Preview, Save, and Cancel. Preview updates runtime output; Save atomically commits the workspace; Cancel restores the committed snapshot. Renderer adoption is reported separately from persistence.
- Workspace and module-preset repositories use independent versioned files and `AtomicFile`. IDs, payloads, references, and the 15-band capacity are checked before use. Unknown future schemas are preserved without overwrite.
- Storage paths reject symlinked directories and primary/backup files. Future-schema AtomicFile backups are preserved, and failed feature-flag persistence restores the prior active workspace snapshot.
- `DspTuningResolver` maps intent to effective runtime values without writing them back to the legacy profile. Bass boost from Macro, Smart Loudness, and Dynamic Bass shares a 6 dB additional-boost budget. Macro values are bounded; 50 is neutral.

## New DSP stages

- Macro Tone carries bass, vocal, air, and relative-width controls. It defaults disabled at 50/50/50/50.
- Smart Loudness supports OFF, calibrated route-bound AUTO, MANUAL estimate, and FIXED amount. AUTO only resolves when a trustworthy reading has the same route token as its calibration; otherwise it reports unavailable and applies no AUTO compensation. PCM level is not treated as playback-volume evidence.
- Dynamic Bass is disabled by default, capped at 3 dB before the shared resolver budget, and uses a low-pass detector, silence gate, attack/release envelope, and linked stereo control. The detector observes the signal at the Dynamic Bass stage before that stage applies its own boost. It is processed after the legacy Convolver stage and before Macro, Smart Loudness, and the final limiter.
- New stages use fixed state and do not allocate in the sample-processing loop. Existing 44.1/48 kHz mono/stereo PCM16 behavior is covered by software suites; target acoustics and performance still need hardware evidence.

## Live renderer and Native ownership

- `DspRuntimeConfigStore` publishes immutable generation snapshots. `DspUpdatePlanner` classifies changes as HOT, WARM, or DEFERRED from changed fields, stream role, current latency, and state-continuity requirements.
- HOT is limited to eligible MEDIA updates on an active matching PCM pipeline. The one-slot Native mailbox carries a validated generation token, editor-owner epoch, renderer-owner token, PCM-format epoch, and hot-context token. Processing and updates share renderer ownership; ACK occurs after a block processes the update.
- Kotlin pending acknowledgements use one-shot compare-and-set ownership. Close, process failure, and stale context rebind invalidate the pending generation, so a late ACK cannot restore ACTIVE status.
- Opt-in LOCAL_WARM is limited to PEQ and Static Bass. Control-thread preparation replays bounded pre-EQ input history into the shadow EQ stage, then crossfades at the existing latency while downstream stateful stages remain active. Headroom changes, stale owners/formats, unsupported roles, and insufficient history defer safely; Cabin Correction and IR stay deferred.
- Renderer-status owner tokens are process-wide and monotonically increasing because the shared runtime store can outlive an `AndroidMediaSink` instance. Retry requests from the audio callback signal the control thread without waiting for the generation-listener monitor.
- Stateful/latency-changing updates retain the existing prepare/reaper path or defer to a new AudioTrack generation. Non-MEDIA roles do not receive the full DSP Hot path.
- Native graph processing keeps existing profile stages and runs Dynamic Bass, Macro, and Smart Loudness before the final limiter. The sample loop does not perform file I/O, logging, Compose callbacks, or dynamic allocation.
- New-track DSP Master OFF preserves the legacy PCM16 bypass. Turning DSP off mid-track retains the established latency behavior; it is not described as a byte-exact zero-latency live bypass.

## UI and integration boundary

- `DspSettingsScreen` is used in FULL and COMPACT_SPLIT presentation. Navigation exposes Studio, EQ, Spatial, Dynamics, and Advanced. Profile and legacy basic controls remain in Studio; Spatial contains width, mono bass, seat alignment, and M/S controls; Dynamics contains Smart Loudness, Dynamic Bass, compressor, multiband, Dynamic EQ, and limiter; Advanced contains headroom, cabin correction, Harmonic, Convolver, and renderer diagnostics.
- `CarPlayHostActivity` embeds the screen in its compact pane while retaining host ownership of the CarPlay video/touch surface. The current instrumentation test mounts the standalone composable and switches presentation values; it does not prove actual 55:45 host geometry, Surface identity, session continuity, or touch behavior.
- `DspProfileRuntime` persists and applies `DspStudioFeatureFlags` to the Workspace UI, Macro/Loudness/Dynamic Bass overlays, HOT updates, and Local Warm. Apply failure restores the previous flag/workspace snapshot. DSP Master OFF, disabled/default-neutral modules, and truthful `DEFERRED` status remain the rollback behavior. PR #52 is not merged pending evidence and hardware review.
- No TS10/K706/iPhone session, target acoustic measurement, 20-cycle CarPlay Split run, CPU/RSS or p99 result, A/V check, Siri/call/navigation pass, or soak result is claimed.
