# DSP Studio implementation baseline

Date: 2026-10-26 JST

## Initial repository and PR state

| Item | Verified value |
|---|---|
| Repository | choko510/DiPlay |
| Parent PR | [#45](https://github.com/choko510/DiPlay/pull/45), open and Draft |
| Parent PR target | main |
| Parent PR branch | codex/step4-pr-00-test-baseline |
| Parent PR head at inspection | ac4694cf1fbb08e443f53a53158528619ed81940 |
| Parent PR merge base | 95ba398f95ee104527812cd2b095977d71c51f98 |
| Integration branch | codex/dsp-studio-final, created from the verified PR head |
| Initial source changes | None |

This table records the verified starting point, not the final PR status. The previously checked-out local snapshot was 5d2d0c50, seven commits behind the live PR head; it was clean. Work continued from the live PR head without changing the separate /Users/choko/DiPlay checkout.

## Environment

| Component | Value |
|---|---|
| Host | macOS 26.4.1, Apple Silicon arm64 |
| Java | Azul OpenJDK 17.0.8 |
| Gradle wrapper | 9.5.0 |
| Android SDK | /Users/choko/Library/Android/sdk |
| SDK platforms | android-33, android-34, android-35, android-36.1, android-37.0, android-37.1 |
| Build tools | 34.0.0, 36.0.0, 36.1.0 |
| NDK | 26.1.10909125, 27.0.12077973 |
| Android device | adb returned no connected devices |
| Target hardware | TS10/K706 not available in this session |

Gradle does not receive ANDROID_HOME from the shell by default. Commands requiring the SDK use ANDROID_HOME and ANDROID_SDK_ROOT set to the installed SDK path; no local.properties was created.

## Existing DSP architecture observed at the parent head

- DspAudioProfile and DspProfileRepository preserve the schema v7 profile contract, including custom1/custom2 and the future-schema write guard.
- DspProfileRuntime.applyWithImpulse updates the runtime config and persists only the selected profile ID/master enabled preference. The existing screen edits a DspEditorSession draft, but its explicit Apply path uses the runtime API.
- DspRuntimeConfig currently contains the legacy DSP stages only. There are no Macro, Smart Loudness, Dynamic Bass 2.0, DSP Studio workspace, or module-preset models at this head.
- DspSettingsScreen.kt contains the existing controls in one large Compose file, with BASIC/EQ/ADVANCED compact sections and a long full-screen layout. There is no graphical PEQ editor.
- DspRuntimeConfigStore publishes versioned immutable config snapshots. DspLiveUpdateController prepares a whole pipeline for each snapshot. AndroidMediaSink defers a prepared update when the target graph requires state continuity, based on the target graph rather than the changed fields.
- Native DSP is a single C graph. Current order is gain/seat, PEQ, bass, compressor, multiband, Dynamic EQ, stereo spatial, Harmonic, Convolver, limiter. NativeDspProcessor creates a graph via JNI; the process call and JNI engine lease already have lifecycle checks.
- PEQ and approved Cabin notches share a 15-active-band limit. Existing Dynamic EQ has its own five-band limit.
- DSP stream policy and the new-track master-off PCM16 bypass are existing contracts and must remain unchanged.

## Baseline verification

Target SHA for all baseline commands: ac4694cf1fbb08e443f53a53158528619ed81940.

| Command | Result | Evidence / limitation |
|---|---|---|
| bash scripts/test_dsp_native.sh | PASS, exit 0 | Native DSP tests passed. |
| bash scripts/test_dsp_native.sh --sanitize | HOST LIMITATION, exit 134 before assertions | macOS AddressSanitizer reports that detect_leaks is unsupported and aborts. |
| ASAN_OPTIONS=detect_leaks=0:halt_on_error=1 UBSAN_OPTIONS=halt_on_error=1 bash scripts/test_dsp_native.sh --sanitize | PASS, exit 0 | AddressSanitizer and UndefinedBehaviorSanitizer assertions ran; LeakSanitizer coverage is unavailable on this host. |
| ./gradlew --parallel :shared:testDebugUnitTest :common:testDebugUnitTest | BLOCKED_ENV, exit 1 | SDK path was not in the environment. |
| ANDROID_HOME=/Users/choko/Library/Android/sdk ANDROID_SDK_ROOT=/Users/choko/Library/Android/sdk ./gradlew --parallel --console=plain :shared:testDebugUnitTest :common:testDebugUnitTest | PASS, exit 0 | shared 625/625 and common 193/193; 818 total, 0 failures, 0 errors, 0 skipped. Reports: shared/build/reports/tests/testDebugUnitTest/index.html and common/build/reports/tests/testDebugUnitTest/index.html. |
| python3 scripts/check_public_tree.py | PASS, exit 0 | No credential containers or private-key blocks. |
| python3 scripts/generate_step4_ex08_inventory.py --check | PASS, exit 0 | 1,289 unique rows; dispositions ADAPT 11, FORK_SUPERIOR 25, NEED_EVIDENCE 353, REJECT_UNSAFE 709, SKIP_OEM 191. |
| adb devices -l | NOT_RUN on device | No connected emulator or physical device was listed. |

## Known acceptance gates at baseline

No TS10/K706, Android instrumentation device, sound-pressure measurement rig, or user hardware session was available. Hardware-specific volume calibration, listening/THD, latency, CPU/RAM/soak, CarPlay Split identity, Siri/call/navigation, and A/V tests remain NOT_RUN until evidence is collected on target hardware.

## Final implementation state

- Integration PR: [#52](https://github.com/choko510/DiPlay/pull/52), base `codex/step4-pr-00-test-baseline`, stacked on PR #45. The final head SHA and ready-for-review state are verified in live PR metadata; source/test commit and evidence are recorded in [FINAL_IMPLEMENTATION_REPORT.md](FINAL_IMPLEMENTATION_REPORT.md).
- Six Luna sub-PRs (#53–#58) are merged into `codex/dsp-studio-final` only; none targets PR #45 or `main`. See [SUBAGENT_PR_LEDGER.md](SUBAGENT_PR_LEDGER.md) for model, branch, SHA, URL, and integration commit.
- Software implementation is present for Macro, Smart Loudness, Dynamic Bass, Studio workspace/presets, PEQ/advanced UI, and renderer-owned Hot/Warm/Deferred updates. Final-source limitations and evidence are recorded in [TEST_MATRIX.md](TEST_MATRIX.md) and [REVIEW_MATRIX.md](REVIEW_MATRIX.md).
- `DspStudioFeatureFlags` constants are not referenced by production code and are not effective rollout gates. The remaining source-level rollback is DSP Master OFF plus disabled/default-neutral new controls and DEFERRED adoption for unsupported stateful/latency changes.
- The exact detailed specification named in the goal, `DiPlay_PR45_Codex_Final_DSP_Studio_Macro_Loudness_DynamicBass_2026-10-26.md`, was not present in the supplied attachment directory or repository. REQ/PKG statuses therefore refer to the accessible goal and initial test matrix; the final report records this input limitation.
- Android test Kotlin compilation passed for the final screen-test source. Connected instrumentation was blocked by no connected device and host storage exhaustion. No TS10/K706/iPhone, acoustic, CPU/RSS, p99, A/V, call/Siri/navigation, 20-cycle Split, or soak result is claimed. See [RUNBOOK.md](RUNBOOK.md).
