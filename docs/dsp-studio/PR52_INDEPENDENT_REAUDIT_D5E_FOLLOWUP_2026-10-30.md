# PR #52 Independent Re-audit d5e Follow-up — 2026-10-30

## Scope and repository state

- Audit snapshot: PR #52 head d5e792b1918be28e76b2378c05b583b796d264c4; source/test head 00ac7453acd325f3523e1e832c01fb2a8375cf68.
- PR #45's base branch advanced externally to 2f60aa9ac3034bcf68f61ed534772adb24999585. That tip was merged into PR #52 only; the PR #45 branch and main ref were not written by this task.
- Current integrated PR #52 source/test head is 2a6c04399012aff2b42c0d77f2675930104a2084, with R-01 code/test changes in 14537700afdfdd1a8b128734942fbb1b9b9eb332.

## Follow-up base conflict sync — 2026-10-30

After this audit response, the PR base branch advanced again to `9ea41f8e5f5f9a0bc11eede5d2bac1adce3efd7c`, and GitHub reported PR #52 as not mergeable. I merged that base into the PR #52 head with merge commit `471d025aa501b2d37f3e6cfd8cb8f9e44c80af82`; no writes were made to PR #45 or `main`.

The two conflicts were resolved as follows:

- `.scratchpad/current.md`: kept the active PR #52 status and carried forward the updated base SHA and new validation state.
- `shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt`: retained PR #52's playback-route/renderer owner tokens and retry count together with the base's bounded close-wait and nanosecond timing constants.

GitHub now reports `mergeable=true`. Local Shared/Common tests pass 1120/1120; `:mobile:assembleBenchmark` and `:automotive:assembleBenchmark` pass; `scripts/check_jni_mapping.py` passes. The local Native runner could not locate a C compiler, so hosted run [#41007508604](https://github.com/choko510/DiPlay/actions/runs/41007508604) (#596) is the authoritative Native/ASan/UBSan check for this head. It completed successfully at `471d025aa501b2d37f3e6cfd8cb8f9e44c80af82`, including Shared/Common unit tests, lint, Debug/Benchmark builds, JNI/R8, Android instrumentation, host-baseline, and Release verification. Artifacts: [unit/build checks](https://api.github.com/repos/choko510/DiPlay/actions/artifacts/12956512138), [Android instrumentation](https://api.github.com/repos/choko510/DiPlay/actions/artifacts/12955977749). The PR remains open and unmerged; the 170-ID evidence ledger and hardware-only NOT_RUN items are unchanged.

## R-01 / P2 — Retire a pending protective ramp with its owner and generation

The supplied re-audit identified that a Studio HOT command can start a protective attenuation ramp and leave the HOT command queued, before any active ACK token is set. The prior owner-change cancellation only examined active ACKs, so the pending ramp could continue after its queue entry was discarded.

The engine now records the protective ramp's generation, editor epoch, renderer token, PCM-format epoch, and HOT context. Before processing PCM, it cancels the ramp at its current effective linear gain if any of those fences no longer match, or if a newer control generation supersedes it. The stale command is discarded without ACK or DEFER. A fresh current command starts its own bounded protective ramp before ACK.

Regression tests cover renderer-owner change, PCM-format epoch change, generation supersede, sample continuity, fresh-command re-protection, and final ACK ownership. The owner-rebind regression failed before the fix because the old ramp continued through the new owner's PCM; the local Native DSP and Dynamic EQ HOT suites pass after the fix.

## R-02 / P2 — Surface recovery retry

This path remains safely deferred. Surface recovery is an internal test hook (surfaceRecoveryEnabledForTest, initially false) and is not enabled by the production sink. No production Surface-recovery failure is asserted. Do not enable it for product use until the asynchronous SENT / failed / stale result controls a bounded, owner-fenced retry policy and tests cover each result.

## R-03 / P3 — Preview runbook accuracy

The DSP Studio runbook now states that Studio workspace edits live-preview while DSP Master is ON; the explicit Preview action reapplies the current workspace, Cancel restores the committed workspace, and Save persists it. The former silent-until-Preview instruction contradicted DspSettingsScreen's updateStudioFeatureWorkspace path.

## R-04 / design note — Publish generation before queue visibility

HOT and LOCAL_WARM enqueue paths now release-publish latest generation metadata before release-publishing the queue write index. A consumer that observes the new slot therefore cannot read the preceding generation fence. The reviewed AndroidMediaSink path serializes enqueue and PCM processing on its renderer worker; this does not claim multi-producer support.

## G-01 — FullHeavyChain p99 variability

Three API 35 Shared Release attempts were run at the same source/test head 00ac7453acd325f3523e1e832c01fb2a8375cf68, with the wall-clock threshold unchanged: the first failed at 63.232 ms versus 21.332 ms (thread CPU p99 1.640 ms), and two failed-job reruns passed. Both outcomes are preserved. These runs establish variability on that source head but do not identify the cause; no physical TS10/K706 performance comparison was available.

## Verification at the integrated head

Local scripts/test_dsp_native.sh passes the Native DSP and Dynamic EQ HOT suites after the R-01 fix. The evidence validator passes 170 IDs / 25 packages; its fixtures pass 24/24. Hosted PR workflow [#40937236189](https://github.com/choko510/DiPlay/actions/runs/40937236189) (#592) passed at integrated source/test HEAD 2a6c04399012aff2b42c0d77f2675930104a2084 after synchronizing PR #45's current base into PR #52. Native, ASan/UBSan, Shared/Common, lint, Debug/Benchmark builds, JNI/R8, API 35 instrumentation, host-baseline and Release verification passed. API 35 passed Shared Release 23/23 and Common Debug 29/29. Artifacts: [instrumentation](https://api.github.com/repos/choko510/DiPlay/actions/artifacts/12926530895), [check reports](https://api.github.com/repos/choko510/DiPlay/actions/artifacts/12926526125).

For G-01, run #589 produced three API 35 Shared Release attempts at source/test head 00ac7453: the first exceeded wall-clock p99, and two same-head reruns passed. Run #592 also passed the current integrated source. No threshold was raised; the first tail's cause remains unproven and no physical-device comparison was available.

## Remaining gates

The 170-ID evidence ledger remains PASS 2 / PARTIAL 162 / NOT_RUN 6. TS10/K706/iPhone audio, USB/video/touch, route-volume correlation, A/V, CPU/RSS/thermal, reconnect and soak remain NOT_RUN. No post-R-01 independent review has run. PR #52 remains open and unmerged; user merge approval has not been given.
