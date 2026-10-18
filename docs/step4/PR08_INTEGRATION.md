# PR-08 Integration and staged release gate

## Decision

`NOT_RELEASE_READY`. The local integration gates pass, but the required TS10/K706 + iPhone run, rollback rehearsal, release/update validation, and independent second review have not been performed. Do not treat the connected emulator run as CarPlay hardware acceptance.

## Local integration evidence

| Gate | Result | Evidence |
|---|---|---|
| Shared/common unit tests | `PASS_LOCAL` | shared 515, common 144; zero failures, errors, or skips |
| Mobile/automotive lint | `PASS_LOCAL` | `:mobile:lintDebug`, `:automotive:lintDebug` |
| Debug and optimized APKs | `PASS_LOCAL` | both modules `assembleDebug` and `assembleBenchmark` |
| R8 JNI mapping | `PASS_LOCAL` | `scripts/check_jni_mapping.py` verified mobile and automotive mappings |
| Public source tree and whitespace | `PASS_LOCAL` | `scripts/check_public_tree.py`, `git diff --check` |
| Android emulator instrumentation | `PASS_EMULATOR` | API 36.1 x86_64, 16/16 existing DSP smoke/benchmark tests; this does not exercise CarPlay USB, iPhone, ViewArea, Gecko login, or Surface recovery |
| Host-native DSP and sanitizer scripts | `NOT_RUN` | No Windows C compiler; WSL launch previously failed with `HCS_E_SERVICE_NOT_AVAILABLE` |

## Feature and evidence disposition

| Area | Disposition |
|---|---|
| PR-06 LOCAL | Local geometry/policy pass; TS10/iPhone 20-cycle Controller/Session/stream continuity and touch/lifecycle checks `NOT_RUN` |
| PR-05 ViewArea | Opt-in model and legacy single-area fallback pass locally; iPhone `/info` acceptance and event-channel A/B `NOT_RUN` |
| PR-06 DYNAMIC | `NO_GO_PENDING_IPHONE_EVIDENCE`; no guessed command sent, flag stays OFF |
| PR-07 video/Surface | `NO_GO_PENDING_DEVICE_EVIDENCE`; generic decoder path retained, no vendor key or last-frame hold |
| PR-06 GECKO | Existing profile/context behavior preserved; physical Google A/B login and owner-transfer checks `NOT_RUN` |
| EX-04, PR-02/03, EX-05/06/07 | Conditional no-go decisions remain as recorded in the upstream register and test matrix |
| DSP additions | Existing default-OFF policy retained; native host/sanitizer evidence remains `NOT_RUN` |
| BYD/OEM quarantine | Public-tree check passes; full package/dependency and release manifest audit remains open |

## Release blockers

- TS10/K706 + iPhone: wired and wireless behavior, manual disconnect/reconnect, app-kill recovery, intermittent USB, Siri/phone/navigation/audio, YouTube coexistence, and 5–30 minute soak.
- Split: 20 full↔55%↔full cycles with unchanged Controller/Session/stream IDs; safe-area and letterbox touch, rotation, IME, resize storms, Surface recreation, and background-owner transfer.
- ViewArea and Gecko: capture the phone's `/info` response; prove profile A/B login isolation and warm reuse; do not enable Dynamic without a confirmed command, acknowledgement, and changed frame dimensions.
- Video/DSP: record codec capabilities, output format/crop, first-frame timing, Surface lifecycle, native DSP host/sanitizer results, and any performance budget before adopting conditional recovery or new DSP behavior.
- Rollback rehearsal, application signing/update, settings persistence/migration, and staged release monitoring.

## Rollback and defaults

LOCAL retains the legacy full-restart fallback if its transform cannot be applied. Multi-ViewArea advertisement requires an explicit valid configuration; the ordinary `/info` remains single-area. Dynamic ViewArea, hold-last-frame, and new DSP nodes remain OFF. Existing Gecko profile contexts and background session ownership remain unchanged.

Reassess `RELEASE_READY` only after the device, rollback, and update gates above have evidence. Do not infer hardware or release success from these local builds.
