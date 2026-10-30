# PR #45 / PR #52 Pre-merge DSP Fix Report

**Updated:** 2026-10-31 (JST)

**PR:** [#45](https://github.com/choko510/DiPlay/pull/45) (`codex/step4-pr-00-test-baseline`)
**Fixed base:** `main@95ba398f95ee104527812cd2b095977d71c51f98`
**Implementation base:** `c6077a1509b662f6754b83ff0bc482ace2612afb` (the PR #52 integration commit)
**Implementation commit:** `4b2a4a4b41992bf5183da3a232e949e369bf49b8`
**PR state:** Draft, open, and unmerged. No changes were pushed to `main`.

## Changes

| Finding | Root cause and fix |
|---|---|
| P1-01 | The DSP `BackHandler` stayed enabled while its Compose pane was `GONE`. It now requires `isVisible && !operationPending`. `dspPaneVisible` continues to follow the host layout state. |
| P1-02 | Custom-profile saves updated the v7 profile and runtime selection but left the committed Studio Workspace unchanged; startup then restored the old Workspace. Custom save now derives its persisted Workspace from the last committed Workspace, replaces only its base profile, and invalidates only preset references affected by changed fields. The current active preview and unsaved Studio module draft remain separate. |
| P2-01 | Host `OPEN_CARPLAY`, YouTube Split, and Activity Back paths could leave DSP without using the Studio confirmation. They now enter the same exit policy as the screen's Back/close controls. The host keeps only the latest exit request and binds it to Activity and session-owner generations. |

## Persistence and state contract

Custom profile and Workspace persistence use a small write-ahead journal. The commit path writes the journal, atomically writes the new Workspace, atomically writes `custom1`/`custom2`, then clears the journal. If a write fails, a rollback journal restores the previous Workspace. If the process stops mid-commit, startup completes the journaled save before loading runtime state. A FutureSchema Workspace or profile is preserved and blocks the save. The DSP master switch remains a separate preference.

The committed Studio session is rebased onto the saved custom profile while retaining unsaved macro, Smart Loudness, Dynamic Bass, and undo history. Those draft fields are not copied to disk by custom-profile save. Studio preview, profile application, custom save, restore, and master changes share runtime generation checks so a stale callback cannot replace a newer state.

For exit requests, a clean and idle Studio leaves immediately. A dirty Studio offers Save, Discard, or Continue. Save exits only after Workspace persistence succeeds. Discard exits only after the committed Workspace restore is accepted by the renderer; failure keeps the draft and displays the existing retry warning. Activity destruction clears queued requests without showing a lifecycle dialog.

## Regression coverage

- P1-01: `hiddenDspLeavesBackWithHostAndPreservesDraft` and `visibleDspRestoresBackHandlingAfterActivityRecreation`; the first test failed before the `isVisible` guard, then both passed on the API 36.1 x86_64 emulator. Host layout visibility also remains correct across 20 DSP/YouTube/CarPlay cycles.
- P1-02: `savingCustomProfileUpdatesPersistedStudioBaseAndSurvivesRuntimeRecreation` reproduced the pre-fix rollback from `custom1` to `flat`. Added coverage for unsaved module draft isolation, FutureSchema preservation, Workspace I/O rollback, newer-preview/custom-save ordering, and journal recovery after simulated process interruption.
- P2-01: Added queue tests for latest-request wins, user cancellation, and stale Activity/session generations, plus an Activity regression showing a confirmed YouTube Split leaves the existing Controller instance intact.
- Added Compose tests for external Open/YouTube confirmation choices and Save/Discard behavior. They compile, but local execution is blocked before test actions: the installed API 36.1 image's Espresso version calls the missing `android.hardware.input.InputManager.getInstance()`. No API 35 image is installed in this worktree.

## Verification

| Check | Result |
|---|---|
| `:common:testDebugUnitTest` | `PASS` — 374 tests, 0 failures/errors/skips. |
| `:common:compileDebugAndroidTestKotlin` | `PASS`. |
| P1-01 focused `:common:connectedDebugAndroidTest` | `PASS` — 2/2 on a temporary API 36.1 x86_64 AVD. |
| `:mobile:lintDebug :automotive:lintDebug` | `PASS`. |
| `:mobile:assembleDebug :automotive:assembleDebug` | `PASS`. Existing NDK metadata/stripping warnings remain. |
| P2-01 Compose interaction tests | `NOT_RUN` — instrumentation setup fails before the test methods because of the API 36.1/Espresso `InputManager.getInstance()` incompatibility. |
| GitHub Actions | [`run #599`](https://github.com/choko510/DiPlay/actions/runs/41076939717) completed successfully on HEAD `91681cc931f1bbdfbb8ade7f5c6135c96b8c5746`. `changes`, EX-08/public-tree checks, Common Draft unit tests, `quick-baseline`, and Draft scope passed; the Shared unit task was `FROM-CACHE`. Host native, ASan/UBSan, JNI library build, lint/app builds, JNI/R8, `host-baseline`, and API 35 instrumentation were skipped by Draft scope. Test report artifact `12988441222` was uploaded. |
| TS10/K706 + iPhone hardware acceptance and release-signed update/rollback | `NOT_RUN` / `BLOCKED_ENV`; no target device or authorized release signing material was available. |
| Independent post-fix review | `NOT_RUN` per the existing direction. |

## Review and merge status

Source and unit-test review found no remaining P1/P2 defect in the requested software paths. The host Activity test verifies the original Controller is retained, and Common tests cover persistence failure, FutureSchema, crash recovery, and async ordering. The Compose interaction run, API 35 instrumentation, physical head-unit checks, release signing, and independent review remain evidence gates.

**Merge decision: `BLOCKED`.** Keep PR #45 Draft and unmerged until the remaining API 35, hardware, signed-update, and review gates are addressed.
