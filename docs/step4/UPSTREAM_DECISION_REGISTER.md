# STEP4 upstream decision register

This register is based on fixed fork main `95ba398f95ee104527812cd2b095977d71c51f98` and v3. It records decisions, not bulk-merge authorization or parity claims. The 1,289 per-commit semantic ledger is EX-08 work and is not complete here.

| ID | Main evidence | Decision before implementation | Status |
|---|---|---|---|
| MERGE-ALL | v3 §1/§9 documents 79 conflicting files and 385 conflict blocks; fork has transport, Gecko/Split and DSP owners absent from a clean transplant. | Reject upstream bulk merge. Compare and adapt one semantic change at a time after fixed-main source review. | `REJECT_UNSAFE` |
| PR-02-ANDROID8 | `shared/build.gradle` and app Gradle files set `minSdk = 28`. | API 26/27 queue cap is unreachable in the shipped app. Retain the PR-02 history; consider only a reproduced API 28+ `queue() == false` fallback. | `NOT_APPLICABLE` for API 26/27; fallback `CONDITIONAL` |
| PR-03 / EX-05 | `NcmDiagnosticProfile.kt`; `NcmStartupNdpRetry.kt`; `NcmLinkReadiness.kt`; `NcmUsbBridge.send()` | Preserve the current 100 ms pre-ready OUT timeout and NDP-only bounded retry. Do not add TCP or iAP2 retries until EX-01 links a repeatable failure to that layer. | `CONDITIONAL / NO-GO pending observation` |
| EX-06 | `NcmUsbBridge` has one persistent async bulk-IN request and `AUTO` polls an available status endpoint using the current synchronous path; debug profile `NO_STATUS_POLLING` exists. | Do not add a second `UsbRequest` or change polling before same-device A/B evidence; a second completion owner can conflict with `requestWait()`. | `NOT_RUN` A/B |
| EX-07 | `Ipv6NcmBridge.kt`; `CarPlayVpnService.kt` | Keep current IPv6 address, peer-MAC and listener behavior while EX-01 separates packet, write and accept evidence. | `CONDITIONAL` |
| PR-04 | `CarPlayMediaEngine.kt`; `MicrophoneUplink.kt`; audio tests | Preserve current Siri/phone routes, microphone lifecycle and `input=false` veto; classify the exact request before changing capture. | `MUST`, implementation pending |
| PR-05 | `AirPlayConfig.kt`; `AirPlayInfoPlist.kt`; `AirPlaySession.kt`; `AirPlayInfoPlistTest.defaultDisplayIncludesFullViewAndSafeAreas` | Keep the legacy single ViewArea by default. Do not guess a dynamic wire payload; a send result is not an ACK or frame-size change. | `MUST` model / hardware acceptance `NOT_RUN` |
| PR-06 LOCAL / DYNAMIC | `CarPlayHostActivity.kt`; `DisplayResizeCoordinator.kt`; `SplitLayoutState.kt`; `AndroidMediaSink.kt` | Preserve current restart recovery. Implement and verify local pane scaling separately; enable a remote dynamic-area switch only after the phone accepts it and output dimensions are observed. | `LOCAL MUST`; `DYNAMIC CONDITIONAL` |
| PR-07 | `AndroidMediaSink.VideoDecoder`; current codec/output format callbacks | Keep Surface/codec optimization conditional on reproduced capability or lifecycle failure. | `CONDITIONAL` |
| DSP-00..05 | Existing DSP graph, profile repository, renderer policy and `DspOffLegacyPcmTest` | Preserve the existing master-off PCM and MEDIA-only rules. Add disabled modules to the prepared graph one stage at a time; never infer cabin response from music alone. | `MUST`, DSP-00 baseline in progress |
| EX-08 | Companion audit archive and v3 §5/§R-06 | Populate per-commit provenance, final-tree semantics, tests and rollback only after validating each source/merge/revert against fixed main. | `NOT_RUN` in PR-00 |

Each decision must later be extended with upstream SHA, final source anchor, fork behavior, chosen/rejected action, tests, feature default and rollback. No row in this scaffold is an adoption/parity proof.
