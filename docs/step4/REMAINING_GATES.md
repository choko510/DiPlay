# Remaining STEP4 Gates

Status snapshot for PR #45 at source HEAD `ac4694cf1fbb08e443f53a53158528619ed81940` (2026-10-26). The PR is open and Draft, based on `main@95ba398f95ee104527812cd2b095977d71c51f98`. This file records open evidence and release gates; it does not authorize changing PR state or merging.

## Current implementation and evidence boundary

The source batch at `550ec3a065a13d6fb690f5d1ceee243461a23db4` includes pre-RECORD TCP-close recovery, bounded incremental RTSP parsing (64 KiB header / 4 MiB body), a four-session control-connection limit, duplicate-signature filtering for DSP Split measure logs, Scaffold inset padding outside the scroll viewport, and pane-side measurement logging. Test-only commit `8ba8b2296806c10c64ea1add8b06b4258b033372` stabilizes the decoder-retirement test hook under worker interrupts.

Hosted run #395 on `8ba8b2296806c10c64ea1add8b06b4258b033372` passed the Draft unit lane, classifier, EX-08 inventory, and Draft scope. Hosted run #396 on current HEAD `ac4694cf1fbb08e443f53a53158528619ed81940` passed its docs-only static checks and Draft scope; Android setup requiring Gradle, unit/native, build/lint/JNI, and instrumentation jobs were skipped. Local current-worktree checks passed the classifier fixtures and EX-08 `--check`. None of these runs is a physical-device or release pass.

User status for C-00 is `USER_CONFIRMED_RESOLVED`. Keep its regression coverage; do not reopen its UI design or infer target measurements from that confirmation. Previously recorded `NOT_RUN` device measurements remain `NOT_RUN`.

## Open gates

| Gate | Status | Required evidence to close |
|---|---|---|
| B01 / EX-08 delta-111 overlay | `NOT_RUN` | Fixed upstream range, all 111 immutable SHAs and patch metadata, 60 source / 51 Merge classification, 117 final-tree paths, zero overlap with the legacy 1,289 SHA set, exact preservation of the frozen legacy rows, generated `--check`, and review/test artifacts. |
| F8-01 pre-RECORD disconnect on supported hardware | `BLOCKED_DEVICE` | Reproduce MAIN and ALT screen SETUP followed by TCP close before RECORD on supported iPhone hardware; show bounded recovery, current-attempt ownership, and no duplicate/stale session. Existing state-model tests are not iPhone evidence. |
| F8-02 RTSP receive bound compatibility | `BLOCKED_DEVICE` | Exercise fragmented/coalesced normal RTSP traffic and reconnects with a supported iPhone, including near-cap bodies; confirm the 64 KiB header / 4 MiB body policy does not reject valid traffic. Parser unit tests do not establish device compatibility. |
| F8-03 concurrent TCP connection pressure | `BLOCKED_DEVICE` | Reconnect/abnormal-input exercise demonstrates the four-session cap, permit release on every close/error path, and recovery of a valid session afterward. |
| DSP Split C-00 | `USER_CONFIRMED_RESOLVED` | Preserve regression coverage. This status does not supply the previously missing device-measurement log or imply a new hardware pass. |
| DSP Split device layout and measurement diagnostics | `BLOCKED_DEVICE` | On SH-M27, verify inset visibility, scroll boundaries, and measured ComposeView specs during normal Split rendering while CarPlay remains active. Keep this separate from C-00's user-confirmed status. |
| Full host/API 35 review lane | `NOT_RUN` | Run the required host/native, lint/build, JNI/R8 and API 35 instrumentation gates on the intended review-ready source SHA. Current #396 intentionally skipped runtime jobs. |
| TS10/K706 + supported iPhone acceptance / G8 | `BLOCKED_DEVICE` / `NO_GO_THIS_RUN` | Complete the specified connection, audio, MAIN/ALT, Split, Surface, DSP, soak and rollback matrix with exact device/OS, APK hash, redacted logs and assertions. SH M20 evidence remains `PARTIAL` and outside the target class. |
| Release signing, update and installed rollback | `BLOCKED_ENV` | Configure authorized release signing in the approved environment, then record signed artifact hash, install/update behavior, preserved state and rollback. Debug signing is not a substitute. |
| Independent second review | `NOT_RUN` | Remains unrequested per user direction; keep the gate open. |

## Rules for updates

- Change a gate to a pass only with evidence for its exact source SHA and stated test level.
- Record skipped CI jobs as `SKIP`, not as test passes.
- Keep user confirmation, source/model tests, emulator acceptance, physical-device acceptance, signing and independent review distinct.
- Keep dynamic ViewArea, vendor codec, additional StartSession retry, and any other conditional feature `NO_GO_THIS_RUN` until its specific prerequisites are measured.
- Do not remove a row until its gate is actually resolved; preserve old failures and their later repair evidence.
