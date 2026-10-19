# Upstream Decision Register — Seeded from STEP4 v3

This is the seed register required by PR-00. Its rulings use the attached v3 plan and current fork `main`; it is not a complete per-commit review of all 1,289 upstream-only commits. The full patch/commit mapping, test, rollback, and adoption labels remain an EX-08 deliverable.

| ID / area | Main evidence | Current decision | Reason / gate |
|---|---|---|---|
| Upstream mass merge | Fixed main SHA `95ba398f95ee104527812cd2b095977d71c51f98`; attached audit coverage says function-level review is incomplete | `REJECT` bulk merge | Fork NCM, session ownership, Gecko Split, DSP, and BYD-removal behavior require function-level decisions |
| PR-01 NCM framing | `NcmUsbBridge.drainFrames()` and `Ntb16Codec`; PR-00 boundary characterization | `ADAPT` | Keep the persistent request, diagnostics, and queue bounds; correct optional pad/bounded malformed recovery in PR-01 |
| PR-02 Android 8 cap | Current `minSdk=28`; shared NDK/build configuration | `NOT_APPLICABLE` for API 26/27; `CONDITIONAL` API28+ fallback | Adopt finite buffer-size fallback only after a device-present `queue()==false` reproduction |
| PR-03 first OUT timing | `NcmStartupNdpRetry` retries only NDP; diagnostic profiles expose pre-ready OUT timeout values | `CONDITIONAL` | EX-01 timing and paired A/B evidence must identify the first OUT delay as causal |
| EX-02 Host retry | `CarPlayHostActivity.restartAfterReconnectDelay` currently guards on `restartGeneration` | `ADAPT` after deterministic recovery/second-loss regression | Add recovery/loss epochs without duplicating Controller-owned wired recovery |
| EX-03 watchdog | Main has 8 s slow snapshot / 15 s hard timeout; MAIN/ALT stages are distinct | `PRESERVE` in EX-01; `CONDITIONAL` stage changes | Do not change deadlines/completion criteria until MAIN/ALT and first-frame evidence justifies it |
| EX-04 re-enumeration | No same-node delayed-probe acceptance test in main | `CONDITIONAL` | Only on reproduced same-device enumeration stall; timer must be generation/permission fenced |
| EX-05 StartSession retry | No TS10 proof that SYN-ACK was lost because OUT was not ready | `NO-GO` until exact evidence | Keep OFF; do not add an unconditional second `0x4301` |
| EX-06 status polling | `NcmDiagnosticProfile.AUTO` polls an available status endpoint; debug `NO_STATUS_POLLING` exists | `CONDITIONAL` | Preserve default pending same-device A/B and completion-ownership evidence |
| EX-07 IPv4/scope/peer MAC | Main data path is IPv6/TUN | `CONDITIONAL` | Keep IPv6-only production routing until wrong scope, IPv4 arrival, or stale listener generation is observed |
| PR-04 microphone | Main uses MediaCodec/AudioRecord and per-stream audio ownership; no Concentus-only implementation exists | `ADAPT` | Add generic input only when explicitly requested; never route call/Siri into MEDIA DSP |
| PR-05/06 ViewArea | `AirPlayInfoPlist` advertises configured displays and one area by default; `CarPlayViewAreas` can describe explicit full/split areas; Split remains local | `ADAPT` / `DYNAMIC_UNVERIFIED` | Keep the new declaration opt-in and the legacy plist default; do not send guessed switch payloads until accepted and actual frame dimensions change |
| PR-07 codec/surface | Main has existing decoder/Surface handling; no TS10 codec-key evidence | `CONDITIONAL` | Preserve fallback; vendor low-latency keys remain OFF until capability and device testing |
| DSP-00..05 | Main has stereo Float32 DSP, schema v3, global master disabled by default | `PRESERVE` baseline, `ADAPT` new nodes | Maintain DSP-OFF legacy PCM equivalence and keep new effects disabled until their staged gates pass |
| BYD-only code | v3 plan marks DiLink/HUD/cluster/rhythm as excluded fork-specific behavior | `REJECT` upstream reintroduction | Do not restore vehicle-specific code removed by the fork |

## EX-08 completion status

`PENDING`: reconcile every in-scope upstream commit/file/patch with `TAKE_DIRECT`, `ADAPT`, `FORK_SUPERIOR`, `SKIP_OEM`, `REJECT_UNSAFE`, or `NEED_EVIDENCE`, and record source anchor, test, related PR and rollback. The supplied audit summaries are supporting evidence only; they are not the complete commit ledger.
