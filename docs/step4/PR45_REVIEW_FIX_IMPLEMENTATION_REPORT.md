# PR #45 Review Fix Implementation Report

**Updated:** 2026-10-28 (JST)

**PR:** [#45](https://github.com/choko510/DiPlay/pull/45)

**Branch:** `codex/step4-pr-00-test-baseline`

**Base:** `main@95ba398f95ee104527812cd2b095977d71c51f98`

**Start HEAD:** `a99066f6133c2fe6f5258a2515a89986b75e042e`

**Latest source/test HEAD:** `c8a1ab0bd9126b7bc6f11e28e2fa6fa2b8cea78c`

PR #45 remains open and Draft. No merge, close, or ready-for-review action was performed. The concurrent PR-branch commit `d140f6e` (opt-in iPhone USB auto-launch) arrived during this work and was preserved in merge commit `184c02a`; its code and tests pass with this batch.

| Gate | Result | Evidence / limit |
|---|---|---|
| Code | `CODE_COMPLETE` for the software review-fix scope | USB reconnect, DSP retirement, and decoder release changes below have regression coverage. The explicitly listed device-only rows remain open. |
| Local host checks | `PASS` | Shared 663/663 and Common 230/230 unit tests; mobile/automotive Debug lint, Debug builds, and Benchmark builds passed. The final combined host gate passed at `184c02a`; the additional test-only commit `c8a1ab0` passed the full Common suite. |
| GitHub CI | `CI_VERIFIED` | Current head run [#527](https://github.com/choko510/DiPlay/actions/runs/40531665594) passed `check`, host-baseline, API 35 instrumentation, and Draft scope. `quick-baseline` was skipped because full host-baseline ran. JUnit artifacts are listed below. |
| Device acceptance | `BLOCKED_DEVICE` | Only an API 36.1 x86_64 emulator was attached. TS10/K706 and iPhone acceptance remains untested. |
| Release | `NO_GO_THIS_RUN` | Device, signed update/rollback, and independent review gates remain incomplete. |

## Commits

| Commit | Change |
|---|---|
| `5a29318` | Reproduce wired USB attach on an existing Host; this regression failed against the pre-fix path. |
| `01b6c5b` | Resume wired startup on a verified attach intent while preserving existing owner/readiness guards. |
| `68a18aa` | Add measured asynchronous DSP graph retirement, fail-closed renderer status, and bounded video decoder recovery with regression tests. |
| `d140f6e` | Concurrent PR-branch update adding opt-in iPhone USB auto-launch; preserved unchanged. |
| `184c02a` | Merge the concurrent PR-branch update without rewriting either history. |
| `c8a1ab0` | Cover active-controller and shutdown-time USB attach deduplication. |

The implementation report and test matrix are committed after the source/test head; their documentation commit ID is recorded in the final response and the PR branch history.

## Phase 00 — Baseline and current HEAD

- The initial GitHub PR snapshot matched start HEAD `a99066f`, base `main@95ba398`, and the local PR branch. At that point the latest run was #505: static checks passed while host-baseline and API 35 instrumentation were skipped.
- During implementation, GitHub advanced the same PR branch to `d140f6e`. The branch was fetched and merged without rebasing or force-pushing; the new opt-in iPhone USB auto-launch behavior was preserved.
- The current source/test head is `c8a1ab0`. The PR is still Draft. No upstream-ledger row or disposition was changed.

## Phase 01 — USB-01 wired attach recovery

**Confirmed defect:** a verified internal `USB_DEVICE_ATTACHED` delivered to an existing wired Host cleared restart suppression, then fell through to `CarPlayHostLayoutCommand.NONE` without re-evaluating startup.

`CarPlayHostActivity.onNewIntent()` now consumes and sanitizes the existing single-use internal action first. Wired attach calls `maybeStartCarPlay()`, retaining its controller/background-owner, shutdown, display, VPN, permission, menu, and handshake guards. Wireless-to-wired switching remains behind the existing shutdown flow and a per-Activity one-shot gate. An attach received during a non-process shutdown queues one coalesced resume/switch callback and starts the new Host after the old controller and sink teardown completes. A process-exit shutdown logs a blocked reason and does not revive the process.

`CarPlayUsbAttachActivity` CH341/current-device/permission validation, exported trampoline policy, internal token consumption, NCM setup, and wired startup protocol were not changed. Logs contain event/result and generation fields only; no serial, MAC, or payload data is added.

**Tests:** `verifiedUsbAttachReevaluatesStartForAnExistingWiredHost`, `verifiedUsbAttachWaitsForVpnReadinessBeforeStarting`, `verifiedWiredAttachKeepsAnExistingControllerAndDoesNotStartAnother`, `wiredUsbAttachDuringShutdownQueuesOneResumeAfterTeardown`, `wirelessUsbAttachDuringShutdownSwitchesToWiredOnlyOnceAfterTeardown`, plus existing token, spoofing, CH341, permission, stale owner, and reconnect-gate tests. The first wired regression failed against the pre-fix branch, then passed after the fix. A USB attach specifically paired with a different live background-session owner has no new end-to-end test; the production `adoptBackgroundSession()`/owner-epoch guards remain in place and existing stale-owner callbacks pass.

## Phases 02–03 — AUDIO-01 transition measurement and safe retirement

PCM format transitions now record `audioOwnerEpoch`, `trackGeneration`, `dspConfigGeneration`, format/encoding, audio thread ID/name, prepare duration, reaper request duration, AudioTrack creation duration, first-write delay, and fallback reason. The event is emitted once on the first write for a transition. Native destroy duration and reaper thread identity are emitted when retirement completes. Diagnostic callbacks are isolated with `runCatching`; no PCM is recorded and no histogram or per-block string work is added.

On format change, the active graph is detached from `AudioRenderer` and retired through its existing `DspLiveUpdateController` and `DspPipelineReaper`. Pending candidates and shutdown graphs use the same reaper. Native graph preparation remains synchronous on format changes so startup Convolver state is installed before the first decoded PCM; this batch moves old graph destruction off that path and does not claim target-device latency improvement.

If native close fails, the owning renderer becomes permanently update-blocked. Its active graph remains in use, pending candidates are retired, later setting changes continue to display the blocked status, and a released old renderer cannot overwrite a new renderer's status. A new renderer can publish fresh ACTIVE status only after its own graph is prepared. DSP master OFF stays byte-exact legacy PCM16 with zero added latency; MEDIA-only role policy, crossfade, seat delay, clock handling, and DSP algorithms remain unchanged.

**Tests:** `decoderFormatChangeDoesNotWaitForOldNativeCloseOnTheAudioOwner` delays close and verifies format preparation returns while the reaper owns the old graph, and checks timing/thread diagnostic fields. Existing A→B→A stale-candidate, in-flight fade cancellation, publication linearization, AAC/Opus startup Convolver, non-MEDIA, and DSP-OFF PCM16 matrix tests pass.

## Phase 04 — VIDEO-01 MediaCodec and Surface recovery

`VideoDecoderRecoveryStatus` adds typed `RUNNING`, `DETACHING`, `RELEASE_BLOCKED`, `RETRYING_SAFE_RELEASE`, `RECOVERED`, and `SESSION_RESTART_REQUIRED` states with stream generation, decoder owner ID, pending ACK count, exception class, last-frame age, blocked duration, and retry count. Diagnostics contain no codec pointer or media payload.

Release retries run only on the existing video worker, at most twice per codec with a 25 ms delay. A successful retry completes the existing Surface ACK. After persistent failure, the decoder and Surface owner remain retained, `surfaceReleaseSafe` stays false, replacement is denied, and the diagnostic requests an app restart. The eight-generation Surface retirement backpressure and existing MAIN/ALT owner checks are unchanged.

**Tests:** `transientCodecReleaseFailureRetriesOnItsWorkerAndCompletesSurfaceRetirement` and `codecReleaseFailureRetainsItsSurfaceAndBlocksDecoderReplacement`, plus existing surface-gate and stale stream-callback suites. Persistent failure does not release a Surface or admit a replacement decoder.

## Phase 05 — DSP-01 failed retirement

The current renderer no longer loses an old pipeline's retirement failure when a format change replaces its live-update controller. The failure is tied to the renderer epoch and retired pipeline/config generation, disables further adoption for that renderer, retires a pending candidate, and keeps `updatesBlocked` status current as settings change. It is not cleared by master OFF/ON or a profile update. A fresh renderer is the recovery boundary; no unsafe handle is marked released or reused.

Both FULL and COMPACT_SPLIT use the existing retirement status and now instruct the user to restart DiPlay before applying changes.

**Tests:** existing `failedNativeCloseIsReportedAndBlocksFurtherAdoption`, new `oldFormatRetirementFailureBlocksUpdatesUntilANewRendererOwnsTheGraph`, and `lateRetirementFailureFromReleasedRendererDoesNotBlockTheNewOwner`.

## Phase 06 — Cross-subsystem ownership review

The updated [OWNERSHIP_MAP.md](OWNERSHIP_MAP.md) records the USB token/start guards, VPN attachment generations, Host `restartGeneration`/`connectionEpoch`/owner epoch, MAIN/ALT stream generation and Surface ACK, and AudioRenderer/AudioTrack/DSP pipeline ownership. Existing NCM, VPN accepted-session, RTSP bounds, Split, touch, and session-generation regression suites were retained and passed. No NCM payload, StartSession count, profile, VPN routing, RTP limit, or benchmark threshold changed.

## Phase 07 — Verification and evidence

The current-head GitHub workflow-sensitive run is #527. `changes`, `check`, `host-baseline`, `android-instrumentation`, and Draft scope all passed. The `check` job passed host native DSP, ASan/UBSan, unit tests, lint, Debug/Benchmark builds, JNI/R8 checks, and test-report upload. The Android instrumentation job ran on API 35. The separate `quick-baseline` and nested draft-unit/JNI-build steps were skipped because the full check/host-baseline covered those tasks.

Local verification on Windows:

- `:shared:testDebugUnitTest :common:testDebugUnitTest --parallel --build-cache --stacktrace` — 663 shared + 230 common local tests, 0 failures/errors/skips after the final USB regression test was added. Current-head GitHub artifact 12747761188 was opened: 161 JUnit XML files contain 893 tests with 0 failures/errors/skips.
- `:mobile:lintDebug :automotive:lintDebug :mobile:assembleDebug :automotive:assembleDebug :mobile:assembleBenchmark :automotive:assembleBenchmark` — passed. No new lint warning was reported in the changed source. Existing NDK 28.2 missing-`source.properties` and dependency strip warnings remain; the build completed with the configured NDK 27 path.
- `scripts/run_android_instrumentation.sh` — 19 Shared Release instrumentation tests and 1 Common Debug instrumentation test passed on API 36.1 x86_64. GitHub run #527 passed the same 19 + 1 split on API 35. Its instrumentation artifact 12747252715 was opened: 2 XML files contain 20 tests with 0 failures/errors/skips. These remain emulator results, not physical-device acceptance.
- `scripts/test_dsp_native.sh` — passed with MSYS2 GCC. `scripts/test_dsp_native.sh --sanitize` was attempted but could not link because this host's MSYS2 installation lacks `libasan` and `libubsan`.
- `scripts/check_jni_mapping.py`, `scripts/test_android_ci_classifier.sh`, `scripts/test_android_instrumentation_evidence.sh`, `scripts/test_step4_ex08_delta111.py` (19/19), `scripts/generate_step4_ex08_inventory.py --check` (1,400 unique SHAs), and `scripts/check_public_tree.py` — passed. The Windows checkout uses `core.autocrlf=true`; the byte-frozen EX-08 checks passed after temporary LF normalization of the ledger/manifest/override files, then those files were restored without content changes.

## Remaining gates

- `HW-01`–`HW-10`, `HW-12`, and `HW-13` remain `BLOCKED_DEVICE`; no TS10/K706 or supported iPhone was attached. The API 36.1 emulator does not substitute for USB, Surface, audio-acoustic, or soak acceptance.
- `HW-11` signed update/rollback remains `BLOCKED_ENV`; no release signing credentials or release APK were used.
- Local API 35 instrumentation is `NOT_RUN` because an API 35 emulator image is unavailable. Hosted API 35 instrumentation passed in run #527.
- Independent second review remains `NOT_RUN` per prior user direction.
- Keep G8 / release decision `NO_GO_THIS_RUN`. Do not merge or mark this PR ready for review without the remaining gates.
