#!/usr/bin/env python3
"""Build a transparent EX-08 inventory from the pinned audit catalog.

The generated rows are a tracking inventory. NEED_EVIDENCE rows are not
semantic sign-offs, and this script never reads or applies source patches.
On shallow checkouts it fetches the pinned audit branch, then reads only the
immutable audit commit recorded below.
"""

from __future__ import annotations

import argparse
import csv
import gzip
import io
import json
import subprocess
import sys
import tarfile
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
AUDIT_COMMIT = "a69a4acb6d50116f4855bebbb94b16047c36c466"
AUDIT_BRANCH = "audit/upstream-57-delta-pass16-20261018"
BASE_CATALOG = "upstream-audit-1232/commit_reviews_refined.jsonl.gz"
BASE_CANDIDATES = "upstream-audit-1232/reviewed_candidates_stage2.csv"
DELTA_BUNDLE = "upstream-audit-1232/incremental-20261018/all_57_patches_and_pass16_evidence.tar.gz"
DELTA_REVIEW = "pass15/all_57_delta_review_matrix.csv"
OUTPUT = ROOT / "docs/step4/EX08_COMMIT_LEDGER.csv"
_audit_commit_ready = False
EXPECTED_BASE_COUNT = 1232
EXPECTED_DELTA_COUNT = 57
EX02_ADAPTED_SHA = {
    "b6c2db846cdcd81f33e34e9b6af34aaf279c9267",
    "fd004ac51eafbede846660d3bc290601b9170978",
}
BASE_DECISION_OVERRIDES = {
    "515bc9ece28ecb913cfaa0891f4a1f85917ce392": "ADAPT",
    "700fde7ebf99015914097ebfe1f1f3dde9f3dde3": "SKIP_OEM",
    "71391aeb74a1f6e64aa3e4bd8cf4c5bc046d60ae": "REJECT_UNSAFE",
}
BASE_REVIEW_OVERRIDES = {
    "bcc5013aeb9331ddf8dc639146369c72f9f3686d": {
        "disposition": "NEED_EVIDENCE",
        "reason": "The candidate combines USB configuration switching with authentication and hotspot behavior. Its USB mode/re-enumeration portion is conditional; do not port the multi-purpose upstream commit without the same-node trace and a split review.",
        "test": "NOT_RUN_TARGET: same-device USB mode transition, permission loop, and authentication/hotspot regression",
        "related": "EX-04 same-node USB re-enumeration; WIRELESS-EX authentication/hotspot behavior",
        "rollback": "No patch imported; any mode-recheck work must be isolated from authentication and hotspot code and reverted independently.",
        "review_state": "PRIMARY_AGENT_CANDIDATE_GATE_REVIEW_PENDING_DEVICE_TRACE",
    },
    "5e8a6f9a14f771db23cafd2f9de8f34eaeb515d7": {
        "disposition": "NEED_EVIDENCE",
        "reason": "The patch requests CarPlay mode again when the same USB node appears to retain a DiPlay-started session. EX-04 remains conditional, and the report explicitly says in-car acceptance is pending; the fork has no same-node delayed-probe evidence.",
        "test": "PASS_STATIC: current main reuses the compatible USB configuration; NOT_RUN_TARGET: same-node mode request, attach, and permission fencing",
        "related": "EX-04 re-enumeration; PR-03/EX-05 wired-start causal trace",
        "rollback": "No patch imported; keep current USB selection until same-node traces justify a generation-fenced, single-attempt recheck.",
        "review_state": "PRIMARY_AGENT_CANDIDATE_GATE_REVIEW_PENDING_DEVICE_TRACE",
    },
    "f53661384e532db194887f19f2381d68d31ea2cf": {
        "disposition": "NEED_EVIDENCE",
        "reason": "The upstream fallback requests H.264 when no hardware HEVC decoder is found. This is a codec-selection and quality/performance tradeoff; no TS10/K706 report shows the fork selecting a software HEVC decoder or underrunning music.",
        "test": "NOT_RUN_TARGET: codec capability, negotiated format, first frame, decode load, and A/V sync",
        "related": "PR-07 codec/decoder evidence gate",
        "rollback": "No patch imported; retain the existing codec negotiation until a target-specific decoder failure is reproduced.",
        "review_state": "PRIMARY_AGENT_CANDIDATE_GATE_REVIEW_PENDING_CODEC_EVIDENCE",
    },
    "dfd7c3d6d3e251e2fa0ee54c584af31f8ec9077b": {
        "disposition": "REJECT_UNSAFE",
        "reason": "MediaClockTrim changes AudioTrack playback rate, which can shift pitch and interact with DSP/A-V timing. The v3 plan explicitly keeps music clock correction OFF until target drift is measured.",
        "test": "NOT_RUN_BY_DESIGN: no target clock-drift trace, pitch/THD measurement, or A/V synchronization result",
        "related": "AUDIO-EX clock drift (OFF); DSP-05 A/V synchronization gate",
        "rollback": "No patch imported; preserve the current media clock and DSP latency baseline.",
        "review_state": "PRIMARY_AGENT_PLAN_GATE_VERIFIED",
    },
    "7d991396161416c71bfaa7b83379efeb12c90ab1": {
        "disposition": "NEED_EVIDENCE",
        "reason": "The change adds a separate persisted wired music-buffer preference and migrates the old shared value. It may change latency and user settings; no fork-specific buffer/A-V evidence supports this migration.",
        "test": "NOT_RUN_FORK: legacy preference migration, wired/wireless selection, interruptions, and audio latency",
        "related": "PR-04 audio regression; AUDIO-EX buffer policy; DSP-05 A/V sync",
        "rollback": "No patch imported; preserve the existing preference key until migration and playback behavior can be reverted without losing saved values.",
        "review_state": "PRIMARY_AGENT_CANDIDATE_GATE_REVIEW_PENDING_AUDIO_TESTS",
    },
    "764e0ba1b05064d4e9a75f93fb585c4afcffbb39": {
        "disposition": "FORK_SUPERIOR",
        "reason": "This change caps USB reads only on Android versions below 9. Fixed main has minSdk 28, so the API 26/27 branch is unreachable; v3 marks this Android 8-only cap not applicable.",
        "test": "PASS_STATIC: all fixed-main application modules use minSdk 28; API 26/27 is not an eligible test target",
        "related": "PR-02 Android 8 queue-cap candidate: NOT_APPLICABLE; API 28+ queue-false fallback remains conditional",
        "rollback": "No change needed; keep the minSdk 28 policy and do not add an unreachable legacy cap.",
        "review_state": "PRIMARY_AGENT_FIXED_MAIN_AND_PLAN_COMPARISON",
    },
    "ab6eb73bd90f483003b961cc13f0cb1f2a1ab596": {
        "disposition": "NEED_EVIDENCE",
        "reason": "This PR478 merge mixes the NTB optional-pad repair with UsbRequest queue sizing. PR-01 has adapted the NCM boundary behavior to fixed main's persistent-read ownership, but its explicit queue(false) fallback remains conditional; minSdk 28 excludes the API 26/27 ceiling and no device-present API 28+ queue rejection has been captured.",
        "test": "PASS_STATIC: fixed main common/mobile/automotive minSdk is 28; PASS_PR45: NcmUsbBridgeFixtureTest covers exact-boundary padded/unpadded NTBs, next-NTH16 preservation, fragmented reads, and sync/async paths; NOT_RUN_TARGET: API 28+ device-present UsbRequest.queue()==false",
        "related": "PR-01 adapted NCM optional-pad/framing behavior; PR-02 API 26/27 cap NOT_APPLICABLE at minSdk 28; API 28+ queue(false) fallback remains CONDITIONAL",
        "rollback": "No UsbRequest queue policy was imported. Keep the PR-01 framing fix isolated; any future queue fallback must be one reversible PR-45 change gated by a device-present queue(false) reproduction.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEW_PENDING_FORK_TESTS",
    },
    "564f8d3f343b92478a7c10726110045f301b1bb5": {
        "disposition": "FORK_SUPERIOR",
        "reason": "This commit adds the Android 7.x queue(ByteBuffer, length) and blocking requestWait shim plus a completion-reaper thread. Fixed main has minSdk 28, so the API 24/25 reaper branch is unreachable and API 26+ already provides the timed calls used by main; the legacy compatibility layer is outside the supported matrix.",
        "test": "PASS_STATIC: origin/main common/mobile/automotive minSdk is 28 and main uses the API 26+ USB request path; API 24/25 is not an eligible target",
        "related": "PR-02 legacy USB API compatibility: NOT_APPLICABLE below minSdk 28; PR-01 NCM framing behavior is reviewed separately",
        "rollback": "No change needed; retain fixed-main minSdk 28 and avoid the unreachable API 24/25 completion reaper.",
        "review_state": "PRIMARY_AGENT_FIXED_MAIN_AND_PLAN_COMPARISON",
    },
    "8ccaa1e212c096ac4de468d15a04fd64625d2257": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This Android 7.1 support merge explicitly lowers minSdk from 28 to 25 and bundles a wide compatibility change with BYD-specific cluster/vehicle behavior. The fixed STEP4 baseline requires minSdk 28 and preserves the fork's BYD removals; the unvalidated aggregate must not be imported.",
        "test": "PASS_STATIC: fixed main common/mobile/automotive minSdk is 28; v3 excludes Android 7/8-only paths and quarantines BYD/OEM behavior; NOT_RUN_TARGET: API 25-27 vehicle acceptance",
        "related": "PR-02 platform gate: retain minSdk 28; PR-08 BYD/OEM quarantine; review any API 28+ USB candidate commit separately",
        "rollback": "No merge imported; preserve minSdk 28 and current fork-only behavior. Any independently justified API 28+ fix must be split into a small PR-45 change with its own rollback.",
        "review_state": "PRIMARY_AGENT_PLAN_GATE_VERIFIED",
    },
    "422a7d8e6368b406c8a958c5877bdd562bf6baf8": {
        "disposition": "NEED_EVIDENCE",
        "reason": "The PR495 ladder retries explicit UsbRequest queue rejection from the capped size down to 2 KiB and expands session diagnostics with nested causes/stack frames. API 26/27 is excluded by minSdk 28, but the API 28+ fallback has no device-present queue(false) reproduction and the extra failure detail needs a separate privacy review.",
        "test": "PASS_STATIC: fixed-main app minSdk is 28; NOT_RUN_TARGET: API 28+ device-present queue(false), queue/wait ownership, and request teardown; NOT_RUN_PRIVACY: cause-chain message/frame redaction for identifiers and payload-like values",
        "related": "PR-02 API 28+ UsbRequest.queue(false) compatibility gate; EX-01 diagnostic privacy; separate from PR-01 NCM framing",
        "rollback": "No queue ladder or cause-chain logging was imported; if justified later, split them into independently tested and revertible changes while retaining fixed-main request ownership.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEW_PENDING_SPLIT_REVIEW",
    },
    "48ef3b46b13c816a47312923bb61de7c8fb6444c": {
        "disposition": "NEED_EVIDENCE",
        "reason": "This integration commit combines the NTB optional-pad fix, the PR495 queue retry ladder, and expanded throwable diagnostics. PR-01 already adapts the NCM boundary repair; the API 28+ queue(false) behavior lacks a device-present reproduction, and the cause-chain logging is outside the reviewed NCM change and needs separate privacy/source review.",
        "test": "PASS_PR45: NcmUsbBridgeFixtureTest covers the adapted exact-boundary/padding behavior; PASS_STATIC: fixed-main app minSdk is 28; NOT_RUN_TARGET: device-present API 28+ queue(false); NOT_RUN_PRIVACY: nested throwable diagnostics",
        "related": "PR-01 NCM framing adapted; PR-02 queue(false) fallback CONDITIONAL; EX-01 diagnostic privacy",
        "rollback": "Do not import the combined queue/logging behavior. Keep PR-01 framing isolated; future queue or diagnostic changes require separate evidence-backed PR-45 commits and independent rollback.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEW_PENDING_SPLIT_REVIEW",
    },
    "885dffb499d549fee5d0e848fc1314cad78c3efa": {
        "disposition": "NEED_EVIDENCE",
        "reason": "This commit mixes two wireless behavior changes (derive the requested P2P name from the supplied credential and preserve a video-rendered session when the optional iAP2 tunnel is absent) with USBMUX endpoint/failure diagnostics. The source report is device-specific, no fork tests accompany the commit, and the diagnostic fields need a separate privacy review.",
        "test": "NOT_RUN_FORK: API 29+ vendor WifiP2pConfig behavior, rendered-frame-before-tunnel handoff, and USBMUX endpoint diagnostic privacy",
        "related": "WIRELESS-EX Wi-Fi Direct and wireless handoff; EX-01 USB diagnostic privacy; separate from wired PR-01/EX-05 behavior",
        "rollback": "No aggregate change imported; keep existing wireless/USB behavior. Any reproduced issue must be split into one wireless fix or one redacted diagnostic change with an independent rollback.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEW_PENDING_SPLIT_REVIEW",
    },
    "f7af3286ea28b73e7219e50f9aa4fa3128de9eec": {
        "disposition": "NEED_EVIDENCE",
        "reason": "The bounded trailer rule applies to USBMUX TCP replies, not CDC-NCM NTB padding. The fork uses a separate USBMUX parser and has no captured payload-bearing trailer showing this condition.",
        "test": "NOT_RUN_FORK_USBMUX: payload trailer, split completion, following-frame preservation, and malformed-length corpus",
        "related": "USBMUX-EX parser candidate; PR-01 changes CDC-NCM only",
        "rollback": "No patch imported; keep the current USBMUX framing until a bounded acceptance rule is backed by captures and parser tests.",
        "review_state": "PRIMARY_AGENT_CANDIDATE_GATE_REVIEW_PENDING_PROTOCOL_EVIDENCE",
    },
    "5fc7a7af55674960f9e7219595027e0b6a3abbf8": {
        "disposition": "NEED_EVIDENCE",
        "reason": "This commit bundles USBMUX trailer parsing, a software-decoder SurfaceView fallback, and RFCOMM handling. The changes span separate transport, PR-07 video, and wireless paths and cannot be taken as one unit.",
        "test": "NOT_RUN_FORK: USBMUX boundary corpus, codec/Surface device matrix, and RFCOMM reconnect cases",
        "related": "USBMUX-EX; PR-07 codec/Surface; WIRELESS-EX RFCOMM",
        "rollback": "No patch imported; split any future evidence-backed change by subsystem and retain an independent rollback for each.",
        "review_state": "PRIMARY_AGENT_CANDIDATE_GATE_REVIEW_PENDING_SPLIT_REVIEW",
    },
    "9647d6896424e4b05cf4fe4ea5399806ed73b58f": {
        "disposition": "NEED_EVIDENCE",
        "reason": "The patch starts microphone capture before downlink audio and retries empty reads. It was reported on a different Android 7.1 head unit; the fork's MediaCodec route needs voice-message, Siri, call, and teardown evidence before behavior changes.",
        "test": "NOT_RUN_TARGET: voice message without downlink, empty AudioRecord recovery, permission denial, and call/Siri echo",
        "related": "PR-04 microphone routing; AUDIO-EX mic no-read recovery",
        "rollback": "No patch imported; preserve current capture ownership until an isolated MediaEngine change passes the voice-route matrix.",
        "review_state": "PRIMARY_AGENT_CANDIDATE_GATE_REVIEW_PENDING_AUDIO_DEVICE_TESTS",
    },
    "817ff3fa2023234f6e26f7568dc00641e7eb1c0c": {
        "disposition": "NEED_EVIDENCE",
        "reason": "The commit is multi-purpose and includes call RTP clock/timing changes alongside USB accessibility and wired-start updates. The fork's 16/24 kHz timestamp math and the separate wired causal gate have not been measured.",
        "test": "NOT_RUN_FORK: 16/24 kHz call RTP timestamps, USB prompt filtering, and same-attempt wired-start trace",
        "related": "PR-04 audio regression; AUDIO-EX RTP clock; PR-03/EX-05 wired-start gate",
        "rollback": "No patch imported; split any future audio and wired fixes into independent commits with separate rollback.",
        "review_state": "PRIMARY_AGENT_CANDIDATE_GATE_REVIEW_PENDING_SPLIT_REVIEW",
    },
    "e3b6c58c86595df8b9c4d0e7275eb7d87e2d7c26": {
        "disposition": "NEED_EVIDENCE",
        "reason": "The patch adds queue recovery and pause/recovery reports to the upstream AudioRenderer. The fork's renderer ownership and report lifecycle differ; confirm whether its current diagnostics miss these states before importing the queue reset or extra events.",
        "test": "NOT_RUN_FORK: queue-empty recovery, rebuffer pause/recovery ownership, report bounds, and audio-thread overhead",
        "related": "PR-04 audio regression; AUDIO-EX rebuffer diagnostics",
        "rollback": "No patch imported; keep the current audio queue and diagnostics until a focused reproduction supports each isolated change.",
        "review_state": "PRIMARY_AGENT_CANDIDATE_GATE_REVIEW_PENDING_AUDIO_TESTS",
    },
    "0abc07128b8cb784f0576c759d019c0167857bb6": {
        "disposition": "NEED_EVIDENCE",
        "reason": "This change caps queue-full and rebuffer reports at five per renderer. It reduces event volume but also discards later reports; confirm the fork's event ownership and useful failure window before changing its report policy.",
        "test": "NOT_RUN_FORK: report cap, repeated rebuffer cycles, and audio-thread logging overhead",
        "related": "AUDIO-EX rebuffer diagnostics; PR-04 audio regression",
        "rollback": "No patch imported; retain the current report policy until a focused event-volume test supports a reversible cap.",
        "review_state": "PRIMARY_AGENT_CANDIDATE_GATE_REVIEW_PENDING_AUDIO_TESTS",
    },
    "2c808c078ba9dec55ea238254939dd0677c7ecf9": {
        "disposition": "NEED_EVIDENCE",
        "reason": "The change adds Android's attached USB-device inventory to wired diagnostics. The exact descriptor fields need privacy review and the behavior is untested on the fork; no device identifiers should be logged by assumption.",
        "test": "NOT_RUN_FORK: absent iPhone, unrelated USB device, descriptor redaction, and log-once bounds",
        "related": "EX-01 USB-enumeration diagnostics; privacy review",
        "rollback": "No patch imported; do not log device inventory until allowed fields are explicitly redacted and tested.",
        "review_state": "PRIMARY_AGENT_CANDIDATE_GATE_REVIEW_PENDING_PRIVACY_REVIEW",
    },
    "07a737f49abcc9ac5b025e4490c4c0b7650b4b9e": {
        "disposition": "NEED_EVIDENCE",
        "reason": "This diagnostic commit logs every Android-listed USB device, including VID:PID and /dev/bus/usb node paths, when the iPhone is absent. Its own test confirms the diagnostic redactor leaves the sample device list unchanged, which exceeds EX-01's aggregate wired-path reporting until the exact allowlist and retention are reviewed.",
        "test": "SOURCE_TESTS: upstream verifies USB-stick VID:PID/node output and that DiagnosticRedactor leaves it unchanged; NOT_RUN_FORK: privacy/export/retention review for supported head-unit device inventories",
        "related": "EX-01 aggregate wired diagnostics and privacy redaction; separate from PR-01 NCM framing and EX-04 re-enumeration",
        "rollback": "No device-list diagnostic imported; keep aggregate connection status. Any future detail needs an allowlisted, redacted field set and a one-change rollback.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEW_PENDING_PRIVACY_REVIEW",
    },
    "d0556a2f6699c9c8061bf13d35fa231f8feb9516": {
        "disposition": "NEED_EVIDENCE",
        "reason": "The SurfaceView fallback targets a reported FFmpeg Codec2 decoder on an Android 17 Raspberry Pi build. It changes window/Surface ownership and can interfere with the fork's local Split UI; no target decoder failure is evidenced.",
        "test": "NOT_RUN_TARGET: codec identity, Surface handoff, local split/rotation, and first-frame recovery",
        "related": "PR-07 codec/Surface gate; PR-06 LOCAL split interaction",
        "rollback": "No patch imported; retain the current TextureView/Surface ownership until a target decoder reproduces the rendering failure.",
        "review_state": "PRIMARY_AGENT_CANDIDATE_GATE_REVIEW_PENDING_CODEC_EVIDENCE",
    },
    "7d974f42f7b6c13b1f91a3a74e76831f2d247ec1": {
        "disposition": "NEED_EVIDENCE",
        "reason": "The 100 ms cap is for audio decoder input acquisition after one drain/retry. The upstream report is a DiLink background-screen underrun; shortening the wait can drop audio on this fork and needs route-specific evidence.",
        "test": "NOT_RUN_FORK: decoder backpressure, background playback, packet loss, and underrun comparison",
        "related": "PR-04 audio regression; AUDIO-EX decoder backpressure",
        "rollback": "No patch imported; preserve current decoder wait until a bounded fork test demonstrates the 500 ms path causes loss.",
        "review_state": "PRIMARY_AGENT_CANDIDATE_GATE_REVIEW_PENDING_AUDIO_TESTS",
    },
    "31b8dfcd9b11496b02b868b898deb37d0f04c0b0": {
        "disposition": "NEED_EVIDENCE",
        "reason": "Existing Wi-Fi/Same-LAN is a new wireless connection mode requiring external-router credentials and alternate network ownership. It is outside the wired STEP4 path and the audit labels device validation pending.",
        "test": "NOT_RUN_TARGET: external-router pairing, route ownership, reconnect, and authentication",
        "related": "WIRELESS-EX Existing Wi-Fi/Same LAN; no wired PR mapping",
        "rollback": "No patch imported; preserve the current Bluetooth/Direct setup until wireless-mode behavior is separately approved and testable.",
        "review_state": "PRIMARY_AGENT_CANDIDATE_GATE_REVIEW_PENDING_WIRELESS_TESTS",
    },
    "5a5b53287a4e2f53aa089dff05f95c76db8922ad": {
        "disposition": "ADAPT",
        "reason": "The upstream change recovers a following valid NTB after malformed NCM data and bounds resynchronization. PR-01 adapts this behavior to the fork's persistent UsbRequest, typed send results, diagnostic profiles, and exact optional-pad boundary handling; it does not copy the upstream wall-clock resync policy.",
        "test": "PASS_LOCAL: NcmUsbBridgeFixtureTest covers malformed NDP/header recovery, false NTH, unknown NTH32, 1 MiB resync ceiling, 64/512-byte pad boundaries, and preservation of the next NTB",
        "related": "PR-01 NCM framing/recovery; PR-00 test fixtures; PR-45 commit bc08b6b",
        "rollback": "Revert PR-45 commit bc08b6b if the framing/recovery fixture suite regresses; retain the fork's persistent request and diagnostics.",
        "review_state": "PRIMARY_AGENT_BEHAVIOR_MAPPING_AND_LOCAL_TEST",
    },
    "1c93dc9b055bf7a9e8f93d91ed0da780476301bf": {
        "disposition": "NEED_EVIDENCE",
        "reason": "The USBMUX change drains and preserves a read that completes between timeout and cancel, addressing a Telechips-specific report. The fork has no same-device timed-read trace, and changing timeout ownership can affect close/cancel races.",
        "test": "NOT_RUN_TARGET: timed USBMUX completion race, cancel result, empty completion, teardown, and next-read recovery",
        "related": "USBMUX-EX read/cancel lifecycle; distinct from PR-01 NCM receive recovery",
        "rollback": "No patch imported; if reproduced, isolate timed-read recovery in IphoneUsbHost with a deterministic race test and one-commit rollback.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEW_PENDING_USB_RACE_REPRO",
    },
    "59c2652592ce92d2fb22e4554858deb0bbaa4656": {
        "disposition": "NEED_EVIDENCE",
        "reason": "The patch uses an existing local ADB trust to grant USB permission through a shell app_process when Android 7.1–9 has no permission activity. API 28 is within fixed main's minSdk, but no TS10 permission-UI failure is evidenced and the shell grant needs explicit security/lifecycle review.",
        "test": "NOT_RUN_TARGET: Android 9 missing permission UI, ADB trust and revocation, grant fencing, and USB detach lifecycle",
        "related": "PR-00 USB permission fixtures; EX-01/04 permission and re-enumeration lifecycle; security review",
        "rollback": "No grant fallback imported; retain the standard permission request until a supported device reproduces the missing-UI case and the ADB path is reviewed.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEW_PENDING_PERMISSION_SECURITY",
    },
    "19649861dab5d65727f195f2d6b7762a0d141005": {
        "disposition": "NEED_EVIDENCE",
        "reason": "The commit combines a USBMUX VERSION-after-trailer parser exception with a home-card transport label. The parser fix targets one iOS 27 capture and needs a byte-exact fork fixture; the separate UI label needs session/selection lifecycle validation.",
        "test": "NOT_RUN_FORK: captured nested-trailer VERSION response, all transfer splits, following-frame preservation, and home-card session selection",
        "related": "USBMUX-EX trailer framing; home-card transport/UI candidate, separate from PR-01 NCM",
        "rollback": "No patch imported; split parser and UI changes, then add capture-driven tests before adopting either behavior.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEW_PENDING_SPLIT_REVIEW",
    },
    "9f9bc1b36e227650754f095c783a1040fd19be3e": {
        "disposition": "REJECT_UNSAFE",
        "reason": "The early USBMUX workaround clears the entire receive buffer after a VERSION reply without validating the remainder as a bounded trailer. That can discard a following valid frame; later upstream work replaces it with a framed parser, and fixed main has a strict incremental decoder.",
        "test": "PASS_STATIC: fixed-main UsbMuxFrameDecoder rejects malformed framing; no unvalidated remainder-discard path is imported",
        "related": "USBMUX-EX VERSION handshake framing; PR-01 CDC-NCM padding is separate",
        "rollback": "No patch imported; retain the fixed-main decoder and do not restore unconditional handshake-buffer clearing.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_UNSAFE_REMAINDER_DISCARD",
    },
    "3d0411d5f2a2b08ed27942ce7103676d5b3e532d": {
        "disposition": "NEED_EVIDENCE",
        "reason": "The upstream decoder permits specific bounded USBMUX control-reply trailers and adds split-transfer tests, but fixed main has a different strict parser and no captured iOS 27 trailer fixture. Directly replacing its parser would change malformed-stream behavior.",
        "test": "NOT_RUN_FORK: exact VERSION/SYN-ACK trailer capture, every transfer split, frame ordering, and malformed-length rejection",
        "related": "USBMUX-EX control-reply framing; separate from PR-01 NCM NTB recovery",
        "rollback": "No parser replacement imported; if reproduced, adapt only the validated bounded trailer cases behind a focused UsbMuxFrameDecoder test.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEW_PENDING_PROTOCOL_CAPTURE",
    },
    "c11e298d8195be5c18cfa942d91b5be591a2c482": {
        "disposition": "NEED_EVIDENCE",
        "reason": "This commit adds a bounded USBMUX parser exception for a four-byte trailer after VERSION or a captured-magic TCP SYN-ACK and validates the following TCP header/ports. Its tests synthesize split transfers, but fixed main already has a strict UsbMuxFrameDecoder and no TS10/K706/iPhone capture establishes this trailer on the supported path; this is separate from CDC-NCM padding.",
        "test": "PASS_STATIC: origin/main uses UsbMuxFrameDecoder with bounded frame lengths; SOURCE_TESTS: upstream synthetic split/padding cases; NOT_RUN_FORK: real supported-device trailer capture, malformed-length corpus, and following-frame preservation",
        "related": "USBMUX-EX control-reply framing; PR-01 CDC-NCM padding is separate",
        "rollback": "No parser code imported; retain fixed-main framing. Any future adapter must be isolated and pass capture-derived split, malformed-length, and following-frame tests.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEW_PENDING_PROTOCOL_CAPTURE",
    },
    "5f59f7780eb66ead8b3e63fd3626f6cacfa27b0c": {
        "disposition": "NEED_EVIDENCE",
        "reason": "This follow-up broadens the four-byte trailer exception from VERSION/SYN-ACK to payload-bearing TCP replies with a valid header and nonzero ports. The added all-split tests are synthetic; no supported-device capture confirms the trailer, and the wider acceptance rule could hide a malformed declared length if copied without the exact boundary evidence.",
        "test": "SOURCE_TESTS: upstream synthetic payload-bearing and every-transfer-split cases; NOT_RUN_FORK_USBMUX: real payload-reply trailer capture, malformed lengths, header-like payload bytes, and next-frame preservation",
        "related": "USBMUX-EX payload-reply framing; distinct from PR-01 CDC-NCM recovery",
        "rollback": "No parser code imported; retain fixed-main framing. If a capture proves the trailer, adapt only the exact observed response class in a separately revertible change.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEW_PENDING_PROTOCOL_CAPTURE",
    },
    "4bccb39f7b866497d289cc26c4ce324585156e93": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This merge bundles SurfaceView/video-surface ownership, live-picture controls, USBMUX trailer parsing, UsbRequest size retries, NCM framing, and Bluetooth RFCOMM recovery across different gates. The components need separate decisions and the reported platform-specific behavior is not TS10/K706 evidence; v3 forbids importing the aggregate as one change.",
        "test": "PASS_STATIC: source inventory spans PR-01 NCM, PR-02 queue, USBMUX-EX, PR-07 Surface, and WIRELESS-EX RFCOMM; NOT_RUN_TARGET: corresponding USB/iPhone/codec/RFCOMM device matrices",
        "related": "Reject aggregate merge; evaluate constituent behavior separately under PR-01, PR-02, USBMUX-EX, PR-07, and WIRELESS-EX",
        "rollback": "No aggregate merge imported; any independently justified component must be a separate PR-45 change with its own tests and one-commit rollback.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_AGGREGATE_REJECTED",
    },
    "bc7aa5e92917747e7cea5e4eee9670397b9c869c": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This sync merge has 166 changed paths and 425 diff hunks spanning BYD features, NCM/UsbRequest, Wi-Fi P2P, microphone, Surface, and side-panel/UI code. It is not an atomic STEP4 feature and cannot be safely transplanted as one commit; v3 forbids upstream bulk merges and requires retaining the fixed-main BYD removals.",
        "test": "PASS_STATIC: source diff confirms multiple unrelated transport/media/OEM/UI areas; origin/main retains minSdk 28 and BYD quarantine; NOT_RUN_PER_COMMIT: isolated behavior tests for the individual upstream changes",
        "related": "Reject aggregate sync merge; review underlying commits separately under PR-01/02, PR-04/06/07, wireless, and BYD/OEM quarantine",
        "rollback": "No merge imported; keep fixed-main code and evaluate only independently supported constituent commits in revertible PR-45 changes.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_AGGREGATE_REJECTED",
    },
    "946487526d677e04b8b97d630e03154e1ac0453a": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This merge syncs a wide upstream-main snapshot into a repair branch, spanning BYD HUD, API 25 USB completion reaping, NCM, Wi-Fi P2P, audio-focus/microphone, and UI/settings code. Importing the merge would bypass the v3 per-feature gates and could restore BYD/OEM behavior removed from fixed main.",
        "test": "PASS_STATIC: source diff spans USB/NCM, Android 7 reaper, BYD, Wi-Fi, audio, and settings; fixed main minSdk is 28 and quarantines BYD; NOT_RUN_PER_COMMIT: constituent changes require separate tests/evidence",
        "related": "Reject aggregate sync merge; review underlying commits separately under PR-01/02, PR-04, EX-06/07, PR-05/06, and BYD/OEM quarantine",
        "rollback": "No merge imported; preserve fixed-main minSdk and feature set. Only split, evidence-backed changes may enter PR-45 with individual rollback.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_AGGREGATE_REJECTED",
    },
    "e96bc43aac174dde5f9c81c86296f52967d308ed": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This merge syncs a wide upstream-main snapshot into a separate boot-repair branch, with the same broad BYD HUD, API 25 USB read, NCM, Wi-Fi P2P, audio, and UI/settings scope. The aggregate is not a discrete STEP4 change and conflicts with the fixed-main minSdk/BYD boundaries.",
        "test": "PASS_STATIC: source diff spans USB/NCM, Android 7 reaper, BYD, Wi-Fi, audio, and settings; fixed main minSdk is 28 and quarantines BYD; NOT_RUN_PER_COMMIT: constituent changes require separate tests/evidence",
        "related": "Reject aggregate sync merge; review underlying commits separately under PR-01/02, PR-04, EX-06/07, PR-05/06, and BYD/OEM quarantine",
        "rollback": "No merge imported; preserve fixed-main minSdk and feature set. Only split, evidence-backed changes may enter PR-45 with individual rollback.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_AGGREGATE_REJECTED",
    },
    "7df2dfa274669376174ebb1e83e5a0af44a22d46": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This merge syncs upstream main into a small-window repair branch but still carries a broad set of BYD HUD, API 25 USB read, NCM, Wi-Fi, audio, and UI/settings changes, with additional branch-resolution differences. The merge is not safe to import as one unit under v3's no-bulk-merge and BYD quarantine rules.",
        "test": "PASS_STATIC: source diff spans USB/NCM, Android 7 reaper, BYD, Wi-Fi, audio, and settings; fixed main minSdk is 28 and quarantines BYD; NOT_RUN_PER_COMMIT: constituent changes require separate tests/evidence",
        "related": "Reject aggregate sync merge; review underlying commits separately under PR-01/02, PR-04, EX-06/07, PR-05/06, and BYD/OEM quarantine",
        "rollback": "No merge imported; preserve fixed-main minSdk and feature set. Only split, evidence-backed changes may enter PR-45 with individual rollback.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_AGGREGATE_REJECTED",
    },
    "2ed938e67a5f245a36a665733bb69d8807091587": {
        "disposition": "NEED_EVIDENCE",
        "reason": "This merge combines a nested four-byte USBMUX trailer exception before an exact VERSION reply with a wired home-card title/Wi-Fi-hint change and translations. The parser tests use synthetic input, while the home-card helper does not exercise fixed-main session and saved-transport lifecycle; the mixed change has no complete STEP4 mapping.",
        "test": "SOURCE_TESTS: upstream synthetic nested-trailer split tests and HomeCardTransportTest; NOT_RUN_FORK: supported-device VERSION-after-trailer capture, malformed framing, and home-card session/settings lifecycle",
        "related": "USBMUX-EX nested VERSION trailer; separate home-card transport label/content candidate; not PR-01 NCM padding",
        "rollback": "No code or translations imported; preserve fixed-main parser and UI. Any future work must split the capture-gated parser from home-card UI and validate each independently.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEW_PENDING_SPLIT_REVIEW",
    },
    "4e97b53423764fdc8d8632e66178f63bdef2cbbe": {
        "disposition": "NEED_EVIDENCE",
        "reason": "The patch digest is identical to commit 3d0411d5, so this row carries no distinct code change. Its shared USBMUX trailer behavior remains unverified against fixed main and the iOS 27 capture.",
        "test": "NOT_RUN_FORK: duplicate patch of 3d0411d; same capture-driven protocol tests are required once",
        "related": "USBMUX-EX duplicate source occurrence of 3d0411d5f2a2b08ed27942ce7103676d5b3e532d",
        "rollback": "No patch imported; track the shared behavior once and keep both upstream commits out of the fork history.",
        "review_state": "PRIMARY_AGENT_DUPLICATE_PATCH_REVIEW_PENDING_PROTOCOL_CAPTURE",
    },
    "f9c2b335203d594d3bc2fb9eaf4f386dd2c1a967": {
        "disposition": "FORK_SUPERIOR",
        "reason": "The patch adds an AccessibilityService that reads active-window text, checks 'always allow', and auto-clicks Android's USB permission dialog. Fixed main instead uses UsbManager.requestPermission and has no AccessibilityService; this preserves explicit per-device consent and avoids broad UI access. V3's low-priority candidate concerns missing-UI retries/de-duplication, not auto-approval.",
        "test": "PASS_STATIC: origin/main has no UsbAutoConfirmService and requests USB permission through UsbManager; Accessibility auto-approval was not added",
        "related": "PR-00 USB permission fixtures; EX-04 permission/generation fencing; preserve explicit Android consent",
        "rollback": "No change needed; retain the fixed-main per-device USB permission request and do not add an auto-click service.",
        "review_state": "PRIMARY_AGENT_FIXED_MAIN_PERMISSION_COMPARISON",
    },
    "dcd971e8778bb975cfc06e0c5c89f56b2b70eb85": {
        "disposition": "FORK_SUPERIOR",
        "reason": "This PR merge adds the same AccessibilityService auto-confirm path. Fixed main preserves the standard per-device USB permission prompt, and v3 only calls for retry/de-duplication when that UI is missing; it does not approve auto-clicking permission dialogs.",
        "test": "PASS_STATIC: origin/main requests USB permission through UsbManager and has no accessibility grant service",
        "related": "EX-04 missing-UI retry/de-duplication candidate; preserve PR-00 per-device consent flow",
        "rollback": "No change needed; retain the fixed-main permission path and keep this accessibility feature out.",
        "review_state": "PRIMARY_AGENT_FIXED_MAIN_PERMISSION_COMPARISON",
    },
    "0a305b4440ebda0b635b7e2f5badd69301d77339": {
        "disposition": "FORK_SUPERIOR",
        "reason": "The merge adds an Android 7.1-9 fallback that uses an already-approved ADB shell and hidden IUsbManager.grantDevicePermission to grant the iPhone device without the standard system prompt when its permission Activity is absent. Fixed main retains per-device UsbManager.requestPermission; v3's missing-UI candidate covers retry count/de-duplication, not a shell grant that changes consent semantics.",
        "test": "PASS_STATIC: origin/main requests per-device consent through UsbManager and contains no ADB grant tool; NOT_RUN_TARGET: API 28 missing-UI reproduction; NOT_RUN_SECURITY: ADB trust/revocation and silent grant lifecycle",
        "related": "PR-00 USB permission fixtures; EX-04 permission retry/de-duplication only; preserve explicit per-device consent",
        "rollback": "No grant fallback imported; keep fixed-main UsbManager permission flow and do not add hidden-API/ADB authorization without an explicit security review and target reproduction.",
        "review_state": "PRIMARY_AGENT_FIXED_MAIN_PERMISSION_COMPARISON",
    },
    "adad43e69124d471a1770a4cf0bd54b57e53e8c8": {
        "disposition": "NEED_EVIDENCE",
        "reason": "This commit combines the USB permission auto-click service with HomeScreenMonitor/overlay auto-hide behavior. The system permission action and floating-map lifecycle require separate consent, permission, and Surface/owner tests; the aggregate feature is not an EX-04 retry fix.",
        "test": "NOT_RUN_FORK: permission-dialog opt-in/misfire cases plus home/foreground transition and map-surface lifecycle",
        "related": "PR-00/EX-04 USB permission lifecycle; PR-06 LOCAL/overlay lifecycle candidate",
        "rollback": "No combined commit imported; any future permission and overlay changes must be split and independently revertible.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEW_PENDING_SPLIT_SECURITY_TESTS",
    },
    "dd05f5a9b43a6807ff78013203db140402a57c6f": {
        "disposition": "NEED_EVIDENCE",
        "reason": "The patch mixes center-map overlay flicker/keyframe changes with HomeScreenMonitor filtering and USB AccessibilityService edits. Fixed main's overlay lifecycle differs from the upstream side-panel/card path, so none of these should be cherry-picked as a permission-only fix.",
        "test": "NOT_RUN_FORK: overlay first-frame/recovery, host start/stop, accessibility foreground filtering, and mirror attach",
        "related": "EX-01 UI lifecycle diagnostics; PR-06 LOCAL display/overlay interaction; USB permission security",
        "rollback": "No patch imported; split any reproduced overlay and permission fixes into independent changes with separate tests.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEW_PENDING_SPLIT_REVIEW",
    },
    "ed9f6367c491b5cf274abe5e2f66ded5071e091c": {
        "disposition": "FORK_SUPERIOR",
        "reason": "This merge packages the same AccessibilityService auto-confirm feature and refinements. Fixed main keeps the explicit UsbManager.requestPermission flow and does not enable the accessibility automation; no missing-UI reproduction justifies replacing that consent path.",
        "test": "PASS_STATIC: origin/main uses UsbManager.requestPermission and contains no UsbAutoConfirmService; no automation is imported",
        "related": "PR-00 USB permission fixtures; EX-04 retries/de-duplication only; no auto-approval mapping",
        "rollback": "No change needed; keep the fixed-main permission flow and do not merge the accessibility branch.",
        "review_state": "PRIMARY_AGENT_FIXED_MAIN_PERMISSION_COMPARISON",
    },
    "4d7904b8f198f78eb0521ba65b0698a75301327b": {
        "disposition": "NEED_EVIDENCE",
        "reason": "The one-click setup still enables accessibility automation and also changes the saved wireless setting to USB when an iPhone attachment arrives. That persistent transport switch can surprise users and must be compared with the fork's explicit connection selection before any adoption.",
        "test": "NOT_RUN_FORK: accessibility opt-in/ADB grant, USB attachment with wireless selected, saved preference, and Activity recreation",
        "related": "PR-00 USB permissions; EX-04 attach lifecycle; WIRELESS-EX connection preference ownership",
        "rollback": "No patch imported; preserve the saved wireless preference and require a separate opt-in flow for any future permission helper.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEW_PENDING_PERMISSION_AND_SETTINGS_TESTS",
    },
    "8633aae3e0a357622b7b44f0ead12c1da74627aa": {
        "disposition": "NEED_EVIDENCE",
        "reason": "The patch combines cancellable USB setup with release-signing configuration changes that remove the debug fallback. The USB lifecycle needs device evidence, and the signing change has not passed the fork's release/update/signature gate.",
        "test": "PASS_LOCAL: prior Debug/Benchmark CI; NOT_RUN: signed release, update, rollback, and missing-keystore build behavior",
        "related": "PR-00 USB permission setup; PR-02 platform lifecycle; release signature/update gate",
        "rollback": "No patch imported; preserve current signing behavior and evaluate permission changes separately from release configuration.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEW_PENDING_RELEASE_AND_DEVICE_TESTS",
    },
    "949e1eddbc29b159ef97a49b3c00d1047b1b2180": {
        "disposition": "NEED_EVIDENCE",
        "reason": "This non-empty merge integrates USB permission automation with hotspot cleanup and VPN-consent lifecycle changes. These are separate ownership paths; the merge cannot substitute for targeted USB prompt and session-owner tests.",
        "test": "NOT_RUN_FORK: USB permission opt-in, hotspot teardown, VPN consent re-entry, and attachment lifecycle",
        "related": "EX-08 merge provenance; PR-00/EX-04 USB permission; wired/wireless owner lifecycle",
        "rollback": "No merge imported; separate constituent fixes by permission, hotspot, and consent ownership before any adoption.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEW_PENDING_SPLIT_LIFECYCLE_TESTS",
    },
    "29572a2cba33ede85dd5355a36508af76be1898e": {
        "disposition": "NEED_EVIDENCE",
        "reason": "The merge reproduces the same USB permission activation and attach-fix patch digest as 949e1ed. It has no independent implementation evidence and retains the accessibility-consent and transport-ownership risks.",
        "test": "NOT_RUN_FORK: duplicate of merge patch 949e1ed; same USB prompt, consent, and attach-lifecycle tests are required once",
        "related": "EX-08 duplicate merge provenance; PR-00 USB permission; EX-04 attachment lifecycle",
        "rollback": "No merge imported; track the duplicate source occurrence without applying either merge commit.",
        "review_state": "PRIMARY_AGENT_DUPLICATE_PATCH_REVIEW_PENDING_SECURITY_TESTS",
    },
}
DELTA_DECISION_OVERRIDES = {
    "4aee9fa705b20c0947a5da1e5c132328a6878f58": {
        "disposition": "NEED_EVIDENCE",
        "reason": (
            "The patch combines Bluetooth adapter reacquisition and persistent failure diagnostics with an Aliyun Maven mirror. "
            "The mirror is absent from fixed main; do not cherry-pick this mixed commit. Wireless benefit and diagnostic privacy "
            "still need fork-specific review."
        ),
        "test": "PASS_STATIC: fixed main has no Aliyun Maven repository; NOT_RUN_TARGET: Bluetooth adapter/radio A/B",
        "related": "EX-01 diagnostics; WIRELESS-EX Bluetooth acquisition; build provenance",
        "rollback": "No patch imported; any future adapter/diagnostic change must be split from repository configuration and reverted independently.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEW_PENDING_TARGET_A_B",
    },
    "68a360982ad9da41bdd66ba0d2fd2a16624ce823": {
        "disposition": "FORK_SUPERIOR",
        "reason": "This commit only removes the Aliyun Maven mirror introduced by its predecessor; fixed main already uses no Aliyun repository and keeps the canonical Google/Maven Central sources.",
        "test": "PASS_STATIC: git grep origin/main confirms no Aliyun Maven repository",
        "related": "PR-00 build baseline and dependency provenance",
        "rollback": "No change needed; keep the fixed-main repository configuration.",
        "review_state": "PRIMARY_AGENT_FIXED_MAIN_COMPARISON",
    },
    "b274c86f0ed1e4faccae9b477f69f5c1e7110bf2": {
        "disposition": "NEED_EVIDENCE",
        "reason": "The patch changes Bluetooth radio-state handling and removes persisted failure details; it may improve privacy, but adapter behavior and wireless startup effects have no fork-specific target evidence.",
        "test": "NOT_RUN_TARGET: API 28+ radio transitions, permission state, and wireless reconnect",
        "related": "EX-01 diagnostics; WIRELESS-EX Bluetooth state",
        "rollback": "No patch imported; preserve current Bluetooth startup policy until a targeted test demonstrates a regression and a reversible change.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEW_PENDING_TARGET_A_B",
    },
    "ab15f847596896db7e6dcea832078f86a6a1655d": {
        "disposition": "NEED_EVIDENCE",
        "reason": "The upstream waits up to 3 seconds for Bluetooth STATE_TURNING_ON. This changes wireless startup timing and needs API 28+ device evidence before it can be mapped to the fork's connection lifecycle.",
        "test": "NOT_RUN_TARGET: Bluetooth turning-on wait, permission, and startup timeout A/B",
        "related": "WIRELESS-EX Bluetooth startup; EX-01 timing diagnostics",
        "rollback": "No patch imported; if adopted later, isolate the wait policy in one revertible commit and retain the current timeout fallback.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEW_PENDING_TARGET_A_B",
    },
    "ab324016a6187b8ec5d1b7ff43a6776279d26ab5": {
        "disposition": "NEED_EVIDENCE",
        "reason": "The commit combines a 10-second USB mode recheck with BYD guidance PCM attenuation. EX-04 remains conditional on same-node device evidence, while the audio change is separately device-specific; the aggregate commit must not be imported.",
        "test": "PASS_LOCAL: upstream re-enumeration policy fixture only; NOT_RUN_TARGET: same-node USB probe and BYD/generic guidance level",
        "related": "EX-04 same-node re-enumeration; AUDIO-EX navigation PCM gain (separate scope)",
        "rollback": "No patch imported; any future EX-04 timer and audio gain work must be separate PR-45 commits with independent rollback.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEW_PENDING_TARGET_EVIDENCE",
    },
    "89810087ebaaec49467b8875568451c3edd29f91": {
        "disposition": "SKIP_OEM",
        "reason": "The diagnostic addition reads BYD wheel-speed output and reports NMEA/wheel-speed availability for a DiLink-specific failure. The v3 plan excludes BYD vehicle integration from this generic TS10 STEP4.",
        "test": "NOT_APPLICABLE_TO_GENERIC_TS10_STEP4; do not expose BYD vehicle telemetry in this change",
        "related": "BYD vehicle-data exclusion; WIRELESS-EX remains separate",
        "rollback": "No patch imported; retain the fork's BYD vehicle-data removal.",
        "review_state": "PRIMARY_AGENT_PLAN_EXCLUSION_VERIFIED",
    },
    "1d3cd00f85ace81f1c795cd79d63d74d76537fe9": {
        "disposition": "NEED_EVIDENCE",
        "reason": "This settings-menu fix changes save validation, page reveal, and hashed wireless settings state. It is outside the wired/DSP changes already validated and needs fork-specific UI/lifecycle regression coverage before porting.",
        "test": "NOT_RUN_FORK_UI: unused hotspot save, transport toggle, and activity recreation cases",
        "related": "WIRELESS-EX settings persistence; CarPlay host menu lifecycle",
        "rollback": "No patch imported; preserve existing menu behavior until a focused change can be reverted without touching connection policy.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEW_PENDING_FORK_TESTS",
    },
    "940c362a0ee7196a785727a653cd1bffa3dfef1b": {
        "disposition": "NEED_EVIDENCE",
        "reason": "This follow-up adds the wireless-enable flag to the saved hotspot signature. The behavior is coherent with the prior menu fix, but both commits must be validated together against the fork's transport ownership and settings lifecycle.",
        "test": "NOT_RUN_FORK_UI: USB-only save versus switching to wireless with unset hotspot",
        "related": "WIRELESS-EX settings persistence; CarPlay host menu lifecycle",
        "rollback": "No patch imported; any future signature change must include the preceding validation fix and be reverted as one isolated feature.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEW_PENDING_FORK_TESTS",
    },
    "4791f5d09c5cf6876a129ecb6de038f97b608769": {
        "disposition": "FORK_SUPERIOR",
        "reason": "The upstream fix is specific to Concentus SoftwareOpusEncoder. Fixed main has no SoftwareOpusEncoder or Concentus references and uses its existing microphone/MediaCodec route; v3 explicitly forbids direct transplantation of this software-encoder workaround.",
        "test": "PASS_STATIC: git grep origin/main finds no Concentus/SoftwareOpusEncoder path; upstream-only encoder tests are not applicable",
        "related": "PR-04 microphone route; AUDIO-EX Opus clock/encoder candidates",
        "rollback": "No change needed; do not add a parallel Concentus encoder path.",
        "review_state": "PRIMARY_AGENT_FIXED_MAIN_COMPARISON",
    },
    "c9c9c77fd09d0750845817cde07ec4e50f49096c": {
        "disposition": "NEED_EVIDENCE",
        "reason": "The patch adds manual interface selection and VLAN/tether detection for a reported CS11 hotspot. Route selection can affect which network carries CarPlay traffic and the behavior is not evidenced on TS10/K706.",
        "test": "NOT_RUN_TARGET: tether interface discovery, route isolation, reconnect, and privacy checks on supported head units",
        "related": "WIRELESS-EX hotspot/VLAN interface policy; EX-07 remains IPv6-only",
        "rollback": "No patch imported; any later support must be opt-in, route-fenced, and isolated behind a revertible wireless-only change.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEW_PENDING_TARGET_EVIDENCE",
    },
    "5745c1b91c86723d4e2688f6cacab784e9245c86": {
        "disposition": "NEED_EVIDENCE",
        "reason": "The patch changes retry/UI behavior after Android 13 refuses Wi-Fi Direct beside a car hotspot. This is a wireless-only failure family and has no connection to the validated wired recovery path.",
        "test": "NOT_RUN_TARGET: Android 13 refusal, prompt frequency, retry termination, and hotspot coexistence",
        "related": "WIRELESS-EX Wi-Fi Direct refusal",
        "rollback": "No patch imported; future retry/UI handling must be isolated from the wired controller and reversible independently.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEW_PENDING_TARGET_EVIDENCE",
    },
    "6270e992b8c3a33243da39647b01b7dd9dbd1201": {
        "disposition": "NEED_EVIDENCE",
        "reason": "The patch adds a second wired-start watchdog for an accepted start request with no AirPlay session. It overlaps the fork's 8/15-second watchdog semantics and needs the exact same-attempt timeout trace required by EX-05 before adoption.",
        "test": "PASS_STATIC: current watchdog policy retained; NOT_RUN_TARGET: same-attempt accepted-start/no-session trace and bounded retry A/B",
        "related": "PR-03 first OUT timing; EX-03 watchdog semantics; EX-05 conditional StartSession retry",
        "rollback": "No patch imported; keep the existing watchdog and retry policy; any future change must be a separate commit with a single-gate revert.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEW_PENDING_CAUSAL_DEVICE_TRACE",
    },
    "0794a45737fd0856b9cd59a8dfe5c93a2b220c3e": {
        "disposition": "FORK_SUPERIOR",
        "reason": "This commit removes BYD_MULTI_SUPPORT_SPLIT_MODE from the manifest; fixed main already has no such flag, matching the v3 exclusion of BYD OS split behavior.",
        "test": "PASS_STATIC: git grep origin/main confirms BYD_MULTI_SUPPORT_SPLIT_MODE is absent",
        "related": "PR-05/06 preserve local GeckoView split; BYD OS split is excluded",
        "rollback": "No change needed; retain the fixed-main manifest without the BYD-only metadata.",
        "review_state": "PRIMARY_AGENT_FIXED_MAIN_COMPARISON",
    },
    "f7af3286ea28b73e7219e50f9aa4fa3128de9eec": {
        "disposition": "NEED_EVIDENCE",
        "reason": "This is a bounded USBMUX trailer parser change, a separate protocol from the NCM NTB parser changed in PR-01. It needs captured USBMUX frame evidence and boundary tests before changing the fork parser.",
        "test": "NOT_RUN_FORK_USBMUX: payload-bearing reply trailer, split completion, next-frame preservation, and malformed lengths",
        "related": "USBMUX-EX parser candidate; PR-01 applies to CDC-NCM only",
        "rollback": "No patch imported; keep current USBMUX framing until a dedicated parser fix has a bounded four-byte acceptance rule and revertible test suite.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEW_PENDING_PROTOCOL_EVIDENCE",
    },
    "5fc7a7af55674960f9e7219595027e0b6a3abbf8": {
        "disposition": "NEED_EVIDENCE",
        "reason": "The multi-purpose commit bundles USBMUX trailer parsing, software-decoder SurfaceView fallback, and RFCOMM stall handling. These affect separate protocol, PR-07 video, and wireless paths and cannot be accepted as one change.",
        "test": "NOT_RUN_FORK: USBMUX frame corpus, codec/Surface device matrix, and RFCOMM disconnect/reconnect cases",
        "related": "USBMUX-EX; PR-07 codec/Surface; WIRELESS-EX RFCOMM",
        "rollback": "No patch imported; require one isolated commit per evidence-backed subsystem, each with its own rollback.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEW_PENDING_SPLIT_REVIEW",
    },
    "dfd7c3d6d3e251e2fa0ee54c584af31f8ec9077b": {
        "disposition": "REJECT_UNSAFE",
        "reason": "The clock-trim patch changes media playback rate through AudioTrack, which can alter pitch and interact with DSP/A-V timing. The v3 plan explicitly keeps music clock drift correction OFF until measured target evidence exists.",
        "test": "NOT_RUN_BY_DESIGN: no target drift trace, pitch/THD, or A/V synchronization measurements",
        "related": "AUDIO-EX clock drift (OFF); DSP-05 A/V synchronization gate",
        "rollback": "No patch imported; preserve the current untrimmed media clock and DSP latency baseline.",
        "review_state": "PRIMARY_AGENT_PLAN_GATE_VERIFIED",
    },
    "7d991396161416c71bfaa7b83379efeb12c90ab1": {
        "disposition": "NEED_EVIDENCE",
        "reason": "The change adds a separate persisted wired music-buffer setting while migrating the shared value. It can alter playback latency and existing user preferences, so fork-specific migration and A/V evidence are required.",
        "test": "NOT_RUN_FORK: legacy preference migration, wired/wireless session selection, latency, and interruption recovery",
        "related": "PR-04 audio regression; AUDIO-EX buffer policy; DSP-05 A/V sync",
        "rollback": "No patch imported; do not change persisted buffer keys until migration can be reverted without losing the prior user value.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEW_PENDING_FORK_TESTS",
    },
    "9647d6896424e4b05cf4fe4ea5399806ed73b58": {
        "disposition": "NEED_EVIDENCE",
        "reason": "The patch opens microphone capture before downlink audio and restarts after empty reads. This changes voice-message, Siri, and call routing and needs the exact PR-04 permission and echo regression on the fork's MediaCodec path.",
        "test": "NOT_RUN_TARGET: voice message without downlink, empty AudioRecord recovery, Siri/call echo, and permission denial",
        "related": "PR-04 microphone routing; AUDIO-EX mic no-read recovery",
        "rollback": "No patch imported; keep current capture start/stop ownership until an isolated MediaEngine change passes the voice-route matrix.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEW_PENDING_AUDIO_DEVICE_TESTS",
    },
    "817ff3fa2023234f6e26f7568dc00641e7eb1c0c": {
        "disposition": "NEED_EVIDENCE",
        "reason": "The commit bundles call-audio timing and RTP clock changes with USB accessibility and wired-start adjustments. Its cross-subsystem scope prevents direct adoption; call-specific clock measurements and wired causal evidence are both missing.",
        "test": "NOT_RUN_FORK: 16/24 kHz call RTP timestamps, USB prompt filtering, and same-attempt wired-start trace",
        "related": "PR-04 audio regression; AUDIO-EX RTP clock; PR-03/EX-05 wired-start gate",
        "rollback": "No patch imported; split any future audio and wired fixes into independent commits with separate rollback.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEW_PENDING_SPLIT_REVIEW",
    },
    "0abc07128b8cb784f0576c759d019c0167857bb6": {
        "disposition": "NEED_EVIDENCE",
        "reason": "The change caps queue-full and rebuffer reports at five per renderer. It reduces diagnostic volume but changes which events survive in the report; confirm the fork's event ownership and useful failure window first.",
        "test": "NOT_RUN_FORK: report bounds, multiple rebuffer cycles, and audio-thread logging overhead",
        "related": "AUDIO-EX rebuffer diagnostics; PR-04 audio regression",
        "rollback": "No patch imported; keep the existing report policy until a focused event-volume test can validate a reversible cap.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEW_PENDING_FORK_TESTS",
    },
    "2c808c078ba9dec55ea238254939dd0677c7ecf9": {
        "disposition": "NEED_EVIDENCE",
        "reason": "The change adds Android USB device inventory to wired diagnostics. Device descriptors may include identifying data, so the log fields need privacy review and target evidence before adding them to the fork report.",
        "test": "NOT_RUN_FORK: absent iPhone, unrelated USB device, descriptor redaction, and log-once bounds",
        "related": "EX-01 diagnostics; USB permission/device-enumeration diagnostics",
        "rollback": "No patch imported; do not log device inventory until identifiers are explicitly redacted and tested.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEW_PENDING_PRIVACY_REVIEW",
    },
    "49bf3bc2b011cb5ee7c7f4da9b99b18cbd3a23a2": {
        "disposition": "REJECT_UNSAFE",
        "reason": "The release-preparation commit changes app version/build metadata and lowers the minimum Android version to 7.0 while replacing release documentation and website content. Fixed main is minSdk 28; this aggregate release commit is incompatible with the approved baseline and unrelated to STEP4.",
        "test": "PASS_STATIC: fixed main minSdk is 28; no release/version/website artifacts imported",
        "related": "PR-00 fixed baseline; PR-02 API 26/27 is NOT_APPLICABLE",
        "rollback": "No patch imported; retain fixed-main minSdk/version and release assets.",
        "review_state": "PRIMARY_AGENT_FIXED_MAIN_COMPARISON",
    },
    "cea429afb4d8c46983ca39e00a2be3c0ff071d84": {
        "disposition": "NEED_EVIDENCE",
        "reason": "This commit changes localized call/Siri/media-key descriptions and the BYD navigation guide, but adds no implementation. The text describes an experimental DiLink key-control path whose behavior is not part of PR-04 microphone classification and has no target acceptance evidence.",
        "test": "NOT_RUN_TARGET: BYD key routing, call answer/reject, Siri, media controls, and locale string-format validation",
        "related": "EX-08 user-facing content audit; PR-04 is microphone routing only; BYD key controls remain separate",
        "rollback": "No strings or guide text imported; if later accepted, keep content edits separate from runtime key-routing changes.",
        "review_state": "PRIMARY_AGENT_CONTENT_REVIEW_PENDING_FEATURE_EVIDENCE",
    },
    "cacac6d68804ebbfdba444925d1b9088d02b4fff": {
        "disposition": "NEED_EVIDENCE",
        "reason": "The commit adds Italian as an application locale and translates a broad set of upstream settings, including features absent from fixed main. Italian support is outside the STEP4 runtime scope; adding only this commit would expose an incomplete or misleading locale surface.",
        "test": "NOT_RUN: no Italian resource completeness, placeholder-format, locale selection, or supported-feature audit",
        "related": "EX-08 localization scope; no runtime STEP4 PR mapping",
        "rollback": "No locale code or resources imported; any future locale addition must be a complete, separately revertible resource change.",
        "review_state": "PRIMARY_AGENT_CONTENT_REVIEW_PENDING_LOCALE_SCOPE",
    },
    "e18f20f8030c2ddc899519a9121f2acd1cc9b4ad": {
        "disposition": "NEED_EVIDENCE",
        "reason": "The commit changes localized rotation-setting text to explain that an iPhone screenshot includes the full square frame. It does not alter display behavior, and the fork has no iPhone acceptance evidence for this user-facing claim.",
        "test": "NOT_RUN_TARGET: rotated output and iPhone screenshot framing; translations are not a runtime test",
        "related": "EX-08 display documentation; PR-06 LOCAL changes pane sizing only and do not enable rotation",
        "rollback": "No text imported; preserve existing locale copy until the described behavior is confirmed on the supported path.",
        "review_state": "PRIMARY_AGENT_CONTENT_REVIEW_PENDING_DISPLAY_EVIDENCE",
    },
    "28388bbf179d78ae46b35103403c57e1c44aa330": {
        "disposition": "NEED_EVIDENCE",
        "reason": "The commit updates Italian resource names for upstream settings and edits upstream AGENTS.md localization guidance. Fixed main has no Italian locale, and these resource keys cover features beyond STEP4; there is no complete localization or feature-parity review to import them safely.",
        "test": "NOT_RUN: Italian resource-key completeness and placeholder validation; no runtime code changed",
        "related": "EX-08 localization/content scope; no runtime STEP4 PR mapping",
        "rollback": "No upstream resources or repository guidance imported; any later locale work stays isolated from implementation changes.",
        "review_state": "PRIMARY_AGENT_CONTENT_REVIEW_PENDING_LOCALE_SCOPE",
    },
    "aefc702d55ddffd0d935af92279a23e501bd173d": {
        "disposition": "NEED_EVIDENCE",
        "reason": "This is a documentation-only edit to the BYD navigation guide that removes implementation-history details. It does not change runtime behavior, and the guide's BYD-specific content must be reconciled with the fork's intentional BYD removals before copying.",
        "test": "NOT_APPLICABLE_DOCS_ONLY; current fork documentation parity has not been reviewed",
        "related": "EX-08 BYD documentation scope; BYD product behavior is excluded",
        "rollback": "No documentation imported; preserve current fork guide until an explicit documentation update is in scope.",
        "review_state": "PRIMARY_AGENT_CONTENT_REVIEW_PENDING_FORK_DOCS",
    },
    "447bddc4835810c25249b28b8d2d959e1886d4f7": {
        "disposition": "NEED_EVIDENCE",
        "reason": "The commit adds Italian strings for newer settings, including steering-wheel Siri controls and other features not present in fixed main. Resource-only adoption would leave the fork without a verified, complete Italian UI for those controls.",
        "test": "NOT_RUN: Italian resource-key and placeholder coverage against fixed-main settings",
        "related": "EX-08 localization scope; no runtime STEP4 PR mapping",
        "rollback": "No translation resources imported; keep future locale work separate and complete.",
        "review_state": "PRIMARY_AGENT_CONTENT_REVIEW_PENDING_LOCALE_SCOPE",
    },
    "8567ea3ed13c1eeaa00a08392271eeb2ac7db8cb": {
        "disposition": "REJECT_UNSAFE",
        "reason": "The release notes explicitly say DiPlay installs on Android 7.0, while fixed main has minSdk 28, and also describe several upstream-only recovery/features. Copying this release-note section would misstate the fork's supported platform and behavior.",
        "test": "PASS_STATIC: fixed main minSdk is 28; release-note claims do not match this fork",
        "related": "PR-00 fixed baseline; PR-02 API 26/27 is NOT_APPLICABLE",
        "rollback": "No release text imported; retain fork-specific platform and behavior documentation.",
        "review_state": "PRIMARY_AGENT_FIXED_MAIN_COMPARISON",
    },
    "2a4d9f6193f257d1c91acd3e59b8fb89fc81dfa9": {
        "disposition": "SKIP_OEM",
        "reason": "The commit only documents and formats BYD split-screen manifest metadata. Fixed main intentionally has no BYD OS split declaration, and v3 excludes this OEM split path while retaining the local GeckoView split model.",
        "test": "NOT_APPLICABLE_TO_GENERIC_TS10_STEP4; no BYD OS split metadata should be added",
        "related": "BYD OS split exclusion; PR-05/06 local GeckoView split is independent",
        "rollback": "No manifest metadata imported; keep the fixed-main manifest without BYD split flags.",
        "review_state": "PRIMARY_AGENT_PLAN_EXCLUSION_VERIFIED",
    },
    "8880fcf3b448716e85f385972500e0367c0561a8": {
        "disposition": "NEED_EVIDENCE",
        "reason": "The translation commit mixes BYD car-rhythm controls with generic hotspot, launcher, and settings strings. Fixed main has no Italian locale, and the mixed resource set cannot be safely separated or treated as runtime parity without a complete locale/key audit.",
        "test": "NOT_RUN: Italian resource completeness, BYD string exclusion, and placeholder validation",
        "related": "EX-08 localization scope; BYD ambient-rhythm strings are excluded",
        "rollback": "No translated resources imported; any accepted locale must omit unsupported BYD controls and cover all live fixed-main keys.",
        "review_state": "PRIMARY_AGENT_CONTENT_REVIEW_PENDING_LOCALE_SCOPE",
    },
    "b9ed71d4f45bfda7e43cde8d3a4c4a6879db0e3a": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This release-note update repeats an Android 7.0 support claim despite fixed main minSdk 28 and documents multiple upstream-only features. Importing it would present unsupported platform and feature claims as fork behavior.",
        "test": "PASS_STATIC: fixed main minSdk is 28; release-note feature parity is absent",
        "related": "PR-00 fixed baseline; PR-02 API 26/27 is NOT_APPLICABLE; EX-08 release-document audit",
        "rollback": "No release text imported; retain current fork release documentation.",
        "review_state": "PRIMARY_AGENT_FIXED_MAIN_COMPARISON",
    },
    "788d2bc26340313ce61b322205e3d9d61ab7f02d": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This non-empty merge imports a broad upstream mainline set spanning hotspot routes, wireless startup, USB re-enumeration, audio, Bluetooth, BYD behavior, and UI/resources. V3 forbids aggregate upstream merges; constituent commits require separate decisions.",
        "test": "NOT_APPLICABLE_AGGREGATE_MERGE; no merge or constituent patch was imported",
        "related": "EX-08 merge provenance; assess constituent commits separately; do not merge upstream main",
        "rollback": "No merge imported; no rollback action. Any future accepted change must be an isolated PR-45 commit.",
        "review_state": "PRIMARY_AGENT_NO_BULK_MERGE_POLICY_VERIFIED",
    },
    "f4f73aa34c2566814218a4b6ad3d9732e863fe3c": {
        "disposition": "NEED_EVIDENCE",
        "reason": "These release notes report both a wired-start watchdog whose in-car acceptance is explicitly pending and a BYD cluster handback. The text cannot be imported as proof of either behavior on the fixed fork.",
        "test": "NOT_RUN_TARGET: same-attempt wired-start/no-session trace and BYD cluster handback; release text is not test evidence",
        "related": "PR-03/EX-03/EX-05 wired-start gates; BYD cluster behavior is excluded",
        "rollback": "No release text imported; keep the current no-go and NOT_RUN documentation until each runtime gate has independent evidence.",
        "review_state": "PRIMARY_AGENT_CONTENT_REVIEW_PENDING_RUNTIME_EVIDENCE",
    },
    "f83fbda534bd8f6ef748053d5c2bdaed3754fee6": {
        "disposition": "SKIP_OEM",
        "reason": "The release notes cover DiLink 7.0 BYD OS split and BYD ambient music-rhythm lighting. Both OEM behaviors are explicitly excluded by v3; the notes do not establish a generic STEP4 behavior.",
        "test": "NOT_APPLICABLE_TO_GENERIC_TS10_STEP4; BYD split and rhythm-lighting paths remain excluded",
        "related": "BYD OS split and ambient-rhythm exclusions; PR-05/06 local split remains separate",
        "rollback": "No BYD release notes or behavior imported; preserve fixed-main BYD removals.",
        "review_state": "PRIMARY_AGENT_PLAN_EXCLUSION_VERIFIED",
    },
}
FIELDNAMES = [
    "inventory_group",
    "source_index",
    "commit_sha",
    "source_url",
    "author_date",
    "subject",
    "is_merge",
    "changed_paths",
    "primary_category",
    "candidate_features",
    "candidate_actions",
    "candidate_evidence",
    "candidate_risks",
    "patch_sha256",
    "patch_archive",
    "source_review_state",
    "current_review_state",
    "candidate_note",
    "disposition",
    "decision_reason",
    "test_evidence",
    "related_step4",
    "rollback_plan",
]
BASE_CATEGORY_MAPPINGS = {
    "AUDIO_BUFFER": "PR-04 audio regression; AUDIO-EX buffer/clock candidates",
    "AUDIO_CALL_MIC": "PR-04 microphone/call routing; AUDIO-EX candidates",
    "AUDIO_FOCUS": "PR-04 audio ownership and focus regression",
    "BYD_NAV": "BYD-only behavior excluded by v3; no generic STEP4 PR",
    "CONNECT_SESSION": "EX-01/02/03 connection lifecycle; candidate gate requires source review",
    "DIAGNOSTICS": "EX-01/03 diagnostics; privacy and event-ownership review required",
    "DOC_TRANSLATION": "EX-08 documentation/localization scope; no runtime STEP4 PR identified",
    "EXTERNAL_APP": "EX-08 app-integration scope; map only after source review",
    "LOCALIZATION": "EX-08 localization scope; no runtime STEP4 PR identified",
    "NMEA_VEHICLE_DIAGNOSTICS": "BYD vehicle-data exclusion; no generic STEP4 PR",
    "PERFORMANCE": "EX-01 target-cost gate or DSP-05 performance gate; source mapping pending",
    "PLATFORM": "PR-02 platform support gate; current minSdk is 28",
    "REVERT_MAINTENANCE": "EX-08 upstream provenance and rollback audit",
    "SECURITY": "PR-00/EX-01 privacy and diagnostics review",
    "TEST_BUILD": "PR-00 build/test baseline; no runtime adoption implied",
    "UI_SETTINGS": "EX-08 settings-scope review; assign PR-04/05/06 only after path review",
    "USBMUX": "USBMUX-EX parser candidate; separate from PR-01 CDC-NCM work",
    "USB_NCM": "PR-01 NCM; PR-02/03 and EX-05 only where the exact gate matches",
    "USB_PERMISSION": "EX-01/04 USB permission and re-enumeration lifecycle",
    "VIDEO_DECODER": "PR-07 codec/decoder evidence gate",
    "VIDEO_DISPLAY": "PR-05/06 ViewArea and local display behavior; dynamic changes remain gated",
    "WIFI": "WIRELESS-EX; outside the validated wired path until target evidence exists",
    "WIRED_MODE": "EX-01/03 wired timing and PR-03/EX-05 causal gates",
    "WIRED_START_RETRY": "PR-03 first OUT timing; EX-02/03/05 retry and watchdog gates",
}
DELTA_FAMILY_MAPPINGS = {
    "DOC_RELEASE_METADATA": "EX-08 release-document scope; no runtime STEP4 PR identified",
    "LOCALIZATION": "EX-08 localization scope; no runtime STEP4 PR identified",
    "MERGE_STRUCTURE": "EX-08 merge provenance; assess constituent commits separately; no direct merge",
    "MIC_OPUS": "PR-04 microphone route; software Concentus path is not the fork's MediaCodec path",
    "NMEA_VEHICLE_DIAGNOSTICS": "BYD vehicle-data exclusion; no generic STEP4 PR",
    "WIRED_START_RETRY": "PR-03 first OUT timing; EX-02/03/05 retry and watchdog gates",
    "WIRELESS_BLUETOOTH_ANDROID8": "WIRELESS-EX Bluetooth; API 26/27 is outside minSdk 28, API 28+ needs target evidence",
    "WIRELESS_HOTSPOT": "WIRELESS-EX hotspot/routing; outside the validated wired path until target evidence exists",
    "BYD_OEM": "BYD-specific behavior excluded by v3; no generic STEP4 PR",
    "BYD_SPLIT_MANIFEST": "BYD OS split is excluded; PR-05/06 retain the fork's local split model",
}
REQUIRED_ROW_FIELDS = (
    "source_url",
    "commit_sha",
    "patch_sha256",
    "current_review_state",
    "decision_reason",
    "test_evidence",
    "related_step4",
    "rollback_plan",
)


def git_blob(path: str) -> bytes:
    global _audit_commit_ready
    if not _audit_commit_ready:
        try:
            subprocess.check_call(
                ["git", "cat-file", "-e", f"{AUDIT_COMMIT}^{{commit}}"],
                cwd=ROOT,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
        except subprocess.CalledProcessError:
            remote_ref = f"refs/remotes/origin/{AUDIT_BRANCH}"
            subprocess.check_call(
                [
                    "git",
                    "fetch",
                    "--no-tags",
                    "origin",
                    f"refs/heads/{AUDIT_BRANCH}:{remote_ref}",
                ],
                cwd=ROOT,
            )
            fetched_sha = subprocess.check_output(
                ["git", "rev-parse", remote_ref],
                cwd=ROOT,
                text=True,
            ).strip()
            if fetched_sha != AUDIT_COMMIT:
                raise RuntimeError(
                    f"Pinned audit ref moved: expected {AUDIT_COMMIT}, fetched {fetched_sha}"
                )
            subprocess.check_call(
                ["git", "cat-file", "-e", f"{AUDIT_COMMIT}^{{commit}}"],
                cwd=ROOT,
                stdout=subprocess.DEVNULL,
            )
        _audit_commit_ready = True
    return subprocess.check_output(["git", "show", f"{AUDIT_COMMIT}:{path}"], cwd=ROOT)


def read_base_candidates() -> dict[str, list[dict[str, str]]]:
    contents = git_blob(BASE_CANDIDATES).decode("utf-8-sig")
    candidates: dict[str, list[dict[str, str]]] = {}
    for record in csv.DictReader(io.StringIO(contents)):
        for prefix in record.get("Related SHA prefixes", "").split(";"):
            prefix = prefix.strip().lower()
            if len(prefix) < 8 or any(character not in "0123456789abcdef" for character in prefix):
                continue
            candidates.setdefault(prefix, []).append(record)
    return candidates


def candidate_records_for(sha: str, candidates: dict[str, list[dict[str, str]]]) -> list[dict[str, str]]:
    return [record for prefix, records in candidates.items() if sha.startswith(prefix) for record in records]


def read_base_rows(candidates: dict[str, list[dict[str, str]]]) -> list[dict[str, str]]:
    contents = gzip.decompress(git_blob(BASE_CATALOG)).decode("utf-8-sig")
    rows = []
    for line in contents.splitlines():
        if not line.strip():
            continue
        record = json.loads(line)
        sha = record["sha"]
        matched_candidates = candidate_records_for(sha, candidates)
        if sha == "515bc9ece28ecb913cfaa0891f4a1f85917ce392":
            disposition = "ADAPT"
            reason = (
                "PR-01 adapts the optional short-pad/framing behavior and local NCM fixtures pass. "
                "The Android 8 queue cap from this multi-purpose commit is not included: minSdk 28 makes API 26/27 unreachable."
            )
            test = "PASS_LOCAL: NcmUsbBridgeFixtureTest; API 26/27 queue cap NOT_APPLICABLE at minSdk28"
            related = "PR-01 commit bc08b6b; PR-02 API26/27 portion NOT_APPLICABLE"
            rollback = "Revert PR-45 commit bc08b6b if NCM framing regression tests fail; no API26/27 cap is imported."
        elif sha == "700fde7ebf99015914097ebfe1f1f3dde9f3dde3":
            disposition = "SKIP_OEM"
            reason = "The upstream change is a BYD instrument-cluster feature; v3 explicitly preserves the fork's BYD removal."
            test = "NOT_APPLICABLE_TO_GENERIC_TS10_STEP4"
            related = "BYD exclusion in docs/step4/UPSTREAM_DECISION_REGISTER.md"
            rollback = "No upstream patch imported; preserve the fork's BYD removal."
        elif sha == "71391aeb74a1f6e64aa3e4bd8cf4c5bc046d60ae":
            disposition = "REJECT_UNSAFE"
            reason = "Direct adoption would lower minSdk from 28 to 24 and expand the supported matrix without the requested device validation."
            test = "NOT_RUN_API24; current minSdk is 28"
            related = "PR-02 platform gate; Android 7 support requires a separate product decision"
            rollback = "No minSdk or source change imported; no rollback action."
        elif matched_candidates:
            disposition = "NEED_EVIDENCE"
            feature_names = "; ".join(sorted({item.get("Feature", "") for item in matched_candidates}))
            proposed_actions = "; ".join(sorted({item.get("Proposed action", "") for item in matched_candidates}))
            reason = (
                f"The stage-two candidate inventory proposes {proposed_actions} for {feature_names}, but the "
                "source catalog remains heuristic-only and this candidate has no completed semantic/runtime review."
            )
            test = "NOT_RUN_PER_COMMIT; candidate risk/test note is retained in this row"
            related = (
                f"Candidate only: {feature_names}; "
                f"{BASE_CATEGORY_MAPPINGS.get(record.get('primary_code_category', ''), 'EX-08 source category mapping pending')}; "
                "final STEP4 mapping pending semantic review"
            )
            rollback = "No upstream patch imported; keep the current fork path until commit-specific evidence supports a revertible ADAPT."
        else:
            disposition = "NEED_EVIDENCE"
            reason = (
                "Pinned catalog classifies by automated path/diff heuristics; this commit has no completed "
                "semantic sign-off in the source record. Do not infer parity or adoption."
            )
            test = "NOT_RUN_PER_COMMIT"
            category = record.get("primary_code_category", "")
            related = (
                f"EX-08 candidate category {category}: "
                f"{BASE_CATEGORY_MAPPINGS.get(category, 'no category-specific STEP4 mapping')}; "
                "final mapping pending semantic review"
            )
            rollback = "No upstream patch imported; a future ADAPT/TAKE must be isolated in a revertible PR-45 commit."
        review_override = BASE_REVIEW_OVERRIDES.get(sha)
        if review_override:
            disposition = review_override["disposition"]
            reason = review_override["reason"]
            test = review_override["test"]
            related = review_override["related"]
            rollback = review_override["rollback"]
        rows.append(
            {
                "inventory_group": "upstream-base-1232",
                "source_index": str(record.get("index", "")),
                "commit_sha": sha,
                "source_url": record.get("url", ""),
                "author_date": record.get("author_date", ""),
                "subject": record.get("title", ""),
                "is_merge": str(bool(record.get("is_merge"))).lower(),
                "changed_paths": ";".join(path.get("path", "") for path in record.get("changed_paths", [])),
                "primary_category": record.get("primary_code_category", ""),
                "candidate_features": "; ".join(sorted({item.get("Feature", "") for item in matched_candidates})),
                "candidate_actions": "; ".join(sorted({item.get("Proposed action", "") for item in matched_candidates})),
                "candidate_evidence": " | ".join(item.get("Evidence and fork contrast", "") for item in matched_candidates),
                "candidate_risks": " | ".join(item.get("Risks", "") for item in matched_candidates),
                "patch_sha256": record.get("patch_sha256", ""),
                "patch_archive": record.get("patch_archive", ""),
                "source_review_state": "{}; {}".format(
                    record.get("review_level", "UNKNOWN"),
                    record.get("semantic_review_status", "UNKNOWN"),
                ),
                "current_review_state": (
                    review_override["review_state"]
                    if review_override
                    else "PRIMARY_AGENT_TARGETED_SOURCE_REVIEW"
                    if sha in {
                        "515bc9ece28ecb913cfaa0891f4a1f85917ce392",
                        "700fde7ebf99015914097ebfe1f1f3dde9f3dde3",
                        "71391aeb74a1f6e64aa3e4bd8cf4c5bc046d60ae",
                    }
                    else "PENDING_SEMANTIC_REVIEW"
                ),
                "candidate_note": "",
                "disposition": BASE_DECISION_OVERRIDES.get(sha, disposition),
                "decision_reason": reason,
                "test_evidence": test,
                "related_step4": related,
                "rollback_plan": rollback,
            }
        )
    return rows


def read_delta_rows() -> list[dict[str, str]]:
    bundle = git_blob(DELTA_BUNDLE)
    with tarfile.open(fileobj=io.BytesIO(bundle), mode="r:gz") as archive:
        entry = archive.extractfile(DELTA_REVIEW)
        if entry is None:
            raise RuntimeError(f"Missing delta review table: {DELTA_REVIEW}")
        contents = entry.read().decode("utf-8-sig")
    rows = []
    for record in csv.DictReader(io.StringIO(contents)):
        sha = record["sha"]
        is_merge = record.get("is_merge", "").lower() == "true"
        family = record.get("family", "")
        patch_bytes = int(record.get("patch_bytes") or 0)
        if sha in EX02_ADAPTED_SHA:
            disposition = "ADAPT"
            reason = (
                "Pass15 documents the paired recovery/second-loss behavior; PR-45 adapts both epochs "
                "and the local ReconnectAfterLossGateTest exercises the paired behavior."
            )
            test = "PASS_LOCAL: ReconnectAfterLossGateTest"
            related = "EX-02; PR-45 commit 3d091bf"
            rollback = "Revert PR-45 stage commit 3d091bf if the deterministic retry-gate regression fails."
        elif family in {"BYD_OEM", "BYD_SPLIT_MANIFEST"}:
            disposition = "SKIP_OEM"
            reason = "The pinned STEP4 plan excludes BYD-only cluster, DiLink, and rhythm-lighting behavior."
            test = "NOT_APPLICABLE_TO_GENERIC_TS10_STEP4"
            related = "BYD exclusion in docs/step4/UPSTREAM_DECISION_REGISTER.md"
            rollback = "No upstream patch imported; preserve the fork's BYD removals."
        elif is_merge and patch_bytes == 0:
            disposition = "REJECT_UNSAFE"
            reason = "The archived remerge diff is empty; do not cherry-pick an aggregate merge. Review constituent commits individually."
            test = "NOT_APPLICABLE_EMPTY_REMERGE_DIFF"
            related = "EX-08 merge-provenance review"
            rollback = "No merge imported; no rollback action."
        else:
            disposition = "NEED_EVIDENCE"
            reason = (
                "Pass15 archives and classifies this delta, but the required device/functional evidence or "
                "commit-specific semantic review is incomplete."
            )
            test = "NOT_RUN_PER_COMMIT"
            related = (
                f"EX-08 candidate family {family}: "
                f"{DELTA_FAMILY_MAPPINGS.get(family, 'family-to-step mapping pending review')}; "
                "final mapping pending semantic review"
            )
            rollback = "No upstream patch imported; future ADAPT/TAKE requires an isolated revertible PR-45 commit."
        review_override = DELTA_DECISION_OVERRIDES.get(sha)
        if review_override:
            disposition = review_override["disposition"]
            reason = review_override["reason"]
            test = review_override["test"]
            related = review_override["related"]
            rollback = review_override["rollback"]
        rows.append(
            {
                "inventory_group": "upstream-delta-57",
                "source_index": record.get("index", ""),
                "commit_sha": sha,
                "source_url": f"https://github.com/shihabal3amri/DiPlay/commit/{sha}",
                "author_date": record.get("author_time", ""),
                "subject": record.get("subject", ""),
                "is_merge": str(is_merge).lower(),
                "changed_paths": record.get("changed_paths", ""),
                "primary_category": family,
                "candidate_features": "",
                "candidate_actions": "",
                "candidate_evidence": "",
                "candidate_risks": "",
                "patch_sha256": record.get("patch_sha256", ""),
                "patch_archive": record.get("patch_archive", ""),
                "source_review_state": "{}; {}".format(
                    record.get("status", "UNKNOWN"),
                    record.get("review_state", "UNKNOWN"),
                ),
                "current_review_state": (
                    review_override["review_state"]
                    if review_override
                    else "PRIMARY_AGENT_BEHAVIOR_MAPPING_AND_LOCAL_TEST"
                    if sha in EX02_ADAPTED_SHA
                    else "PLAN_EXCLUSION_VERIFIED"
                    if family in {"BYD_OEM", "BYD_SPLIT_MANIFEST"}
                    else "PATCH_METADATA_AND_NO_DIRECT_MERGE_REVIEWED"
                    if is_merge and patch_bytes == 0
                    else "PENDING_SEMANTIC_REVIEW"
                ),
                "candidate_note": record.get("step4_disposition", ""),
                "disposition": disposition,
                "decision_reason": reason,
                "test_evidence": test,
                "related_step4": related,
                "rollback_plan": rollback,
            }
        )
    return rows


def build_rows() -> list[dict[str, str]]:
    base = read_base_rows(read_base_candidates())
    delta = read_delta_rows()
    if len(base) != EXPECTED_BASE_COUNT or len(delta) != EXPECTED_DELTA_COUNT:
        raise RuntimeError(f"Unexpected source counts: base={len(base)} delta={len(delta)}")
    rows = base + delta
    shas = [row["commit_sha"] for row in rows]
    if len(rows) != 1289 or len(set(shas)) != len(rows):
        raise RuntimeError(f"Expected 1,289 unique commits, found {len(rows)} rows and {len(set(shas))} unique SHAs")
    missing = [
        f"{row['commit_sha']}:{field}"
        for row in rows
        for field in REQUIRED_ROW_FIELDS
        if not row.get(field, "").strip()
    ]
    if missing:
        raise RuntimeError(f"Required EX-08 ledger fields are empty: {', '.join(missing[:10])}")
    if any("UNMAPPED" in row["related_step4"] for row in rows):
        raise RuntimeError("EX-08 ledger contains an unmapped row; use an explicit provisional family mapping")
    return rows


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="validate sources and generated ledger without writing")
    args = parser.parse_args()
    try:
        rows = build_rows()
    except (KeyError, OSError, subprocess.CalledProcessError, tarfile.TarError, ValueError) as error:
        print(f"EX-08 inventory source error: {error}", file=sys.stderr)
        return 1
    counts = Counter(row["disposition"] for row in rows)
    print(f"Source audit commit: {AUDIT_COMMIT}")
    print(f"Inventory rows: {len(rows)} (1232 base + 57 delta, all unique)")
    print(f"Current dispositions: {dict(sorted(counts.items()))}")
    print("Note: NEED_EVIDENCE is an explicit pending decision, not semantic review or parity proof.")
    if args.check:
        if not OUTPUT.is_file():
            print(f"Generated EX-08 ledger is missing: {OUTPUT.relative_to(ROOT)}", file=sys.stderr)
            return 1
        with OUTPUT.open("r", encoding="utf-8", newline="") as stream:
            generated_rows = list(csv.DictReader(stream))
        if generated_rows != rows:
            print(
                f"Generated EX-08 ledger is stale: regenerate {OUTPUT.relative_to(ROOT)}",
                file=sys.stderr,
            )
            return 1
        for row in rows[:2] + rows[-2:]:
            print(f"{row['inventory_group']} {row['source_index']} {row['commit_sha']} {row['disposition']} {row['subject']}")
        return 0
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    with OUTPUT.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=FIELDNAMES, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)
    print(f"Wrote {OUTPUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
