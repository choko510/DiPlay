# Current Work — DiPlay STEP4 (2026-10-23)

> Active status snapshot for [PR #45](https://github.com/choko510/DiPlay/pull/45) (draft), **not release approval**. The original full notes are archived [here](archive/2026-10-23-current-before-consolidation.md). Verify newer PR commits and CI results before relying on this snapshot.

## Scope and invariants

- STEP4 work stays in PR #45, branch `codex/step4-pr-00-test-baseline`; do **not** push to `main`, bulk-merge upstream or open another STEP4 PR.
- Fixed fork baseline: `95ba398f95ee104527812cd2b095977d71c51f98`. The external STEP4 v3 specification was recorded with SHA-256 `7e8f60a216ff769434f09ba922b0bd218859a8aaf1c8554ced9ab1127b92ca73`; obtain/verify that artifact separately if necessary.
- DSP master OFF **at a new track** must retain the byte-exact, zero-latency legacy PCM16 path. DSP switched OFF mid-track retains the existing latency until a new track; this is **not** a byte-exact live OFF.
- The second independent v3 review is **NOT_RUN**. A passed audit-only/draft quick lane cannot approve full host, Android instrumentation, target hardware or G8 release gates.

## Last documented implementation / checks

- Source-confirmed 4AI review follow-ups W-01…06, AI2-R03/R09, AI3-01…08, AI4-NEW-01 and AI4-SEC-BASE-01 have been fixed or classified. Notable fixes cover bounded NCM recovery, endpoint-size padding, MAIN/ALT and touch ownership, microphone empty-read backoff, Logcat redaction, Seat/fractional delay/harmonic gain, and deferred DSP graph adoption/reaper-thread close. Includes in-host 55:45 CarPlay/DSP Split editor.
- **Local test snapshot:** 583 shared + 186 common, zero failures/errors/skips. **PR run #374** passed public-tree/EX-08 checks, native host/sanitizer and draft unit suites. The draft lane **skipped** lint, build, JNI and instrumentation; local MSYS2 ASan/UBSan linking is unavailable. A documentation-only commit does not rerun product tests.
- Hosted API 35 DSP p99 instrumentation previously failed intermittently (#362/#364/#365/#366); local API 36.1 passing is not a replacement for hosted API 35 or physical TS10/K706 measurements. Thread-priority/benchmark follow-ups require their own applicable full gate.
- The generated EX-08 audit snapshot has **1,289** unique rows (1,232 base + 57 delta): 11 `ADAPT`, 25 `FORK_SUPERIOR`, 191 `SKIP_OEM`, 709 `REJECT_UNSAFE`, 353 `NEED_EVIDENCE`. The original heuristic source state is provenance, **not** an unresolved current-review field. Recheck generated output using `scripts/generate_step4_ex08_inventory.py --check` before quoting counts after edits.

## Device evidence — PARTIAL

- User-supplied 2026-10-22 **SH M20 + iPhone 17 Pro Max (iOS 27.0.1)** session proved bidirectional NCM, AirPlay control/events and screen-open; display/audio, reconnection and two Split attempts were reported successful. It ended in a physical/user disconnect. EX01-06, PR06L-04 and U75 remain **PARTIAL**, not acceptance.
- SH M20 is **outside TS10/K706 target scope**. Exact IPv6→TUN counters, 20-cycle Split/Surface continuity, rotation/safeArea/HID, Siri/call/AEC/AGC, target DSP p99/CPU/THD/A-V, soak, signing/rollback and final release gates remain `NOT_RUN`/`NO_GO_THIS_RUN` as applicable.

## Next work (ordered)

1. Close or classify **AI4-CI-NEW-01/02**, **AI4-EX08-NEW-01**, **AI4-TEST-NEW-01** and policy-dependent **AI4-SYS-BASE-01**, distinguishing source fixes from unrun evidence.
2. Verify CI's skipped scope and run the required full Android/host/JNI/instrumentation gates, including an interpretable API 35 p99 result. Preserve workflow-sensitive baseline rules.
3. Obtain same-device NCM `AUTO`/`NO_STATUS_POLLING` A/B measurements; do not modify 5→6 or add CDC-NCM control requests on existing data. Reproduce a queue/wait failure with USB still present before changing request recreation.
4. Consult the referenced plan for **Stage3 timing-only monotonic wired diagnostics** and a **versioned 111-commit EX-08 overlay** that preserves the original 1,289 rows. Keep EX05 second StartSession disabled until device timing warrants redesign.
5. Run the TS10/K706+iPhone acceptance matrix and review the remaining evidence-gated upstream decisions (including Pass15 delta rows 4/24/28/44). `ROTATION_SQUARE_CANVAS` remains `NO_GO_THIS_RUN`.

See [backlog](backlog.md) for acceptance items, [decisions](decisions.md) for rationale, [lessons](lessons.md) for pitfalls, [quirks](quirks.md) for environments and [optimization](optimization.md) for measurements.
