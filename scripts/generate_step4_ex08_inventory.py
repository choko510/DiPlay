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
    "3001bdebffc9d65f006cf011d664e3822fe0a20f": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This 24-path change combines BYD DiLink 5.1 cluster-theme monitoring/presentation with generic LocalOnlyHotspot and TCP-liveness changes. Fixed main intentionally has no DiLink51Cluster classes; v3 quarantines BYD/OEM behavior and keeps WIRELESS-EX separate from the wired P0 path, so the aggregate is not a safe unit to port.",
        "test": "PASS_STATIC: diff adds DiLink51Cluster/ClusterMapPresentation, PACKAGE_USAGE_STATS, hotspot-manager and TCP-liveness changes; fixed main has no DiLink51ClusterLayout/monitor; SOURCE_TESTS: upstream cluster/hotspot tests; NOT_RUN_TARGET: TS10/K706 cluster and supported-hotspot route/A-B acceptance",
        "related": "Reject BYD/OEM plus WIRELESS-EX aggregate; preserve fixed-main BYD removal; no PR-01/EX-02 wired-path mapping",
        "rollback": "No patch imported; any future generic hotspot fix must be split under WIRELESS-EX and independently measured without restoring BYD cluster code.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_BYD_WIRELESS_AGGREGATE",
    },
    "d87152ca9f165368151bd0f3a44cad2c130d1520": {
        "disposition": "SKIP_OEM",
        "reason": "The patch removes a BYD-only cluster marker preview from the BYD navigation settings and guide. Fixed main has no CarPlayClusterDisplay or ClusterMarkerPreview and the v3 plan preserves the BYD cluster removal; there is no generic ViewArea or local Split behavior to adapt.",
        "test": "PASS_STATIC: changed paths are ClusterMarkerPreview, BYD marker controls, and docs/BYD_NAVIGATION.md; fixed main contains no cluster marker settings or preview",
        "related": "BYD/OEM quarantine; no generic PR-05/06 ViewArea or PR-06 LOCAL Split mapping",
        "rollback": "No change needed; retain fixed-main's removal of BYD cluster preview and marker controls.",
        "review_state": "PRIMARY_AGENT_PLAN_EXCLUSION_VERIFIED",
    },
    "ac75c94c25052388e9feb9fb879797fdf9534f49": {
        "disposition": "SKIP_OEM",
        "reason": "This change calibrates the car marker and map viewport to measured DiLink 5.1 cluster safe areas and BYD-specific steering-wheel modes. Fixed main deliberately removed BYD cluster projection; this geometry has no generic TS10/K706 mapping.",
        "test": "SOURCE_TESTS: upstream cluster layout/marker cases; NOT_RUN_OEM: no supported TS10/K706 cluster map or BYD navigation acceptance",
        "related": "BYD/OEM quarantine; no generic PR-05/06 ViewArea or local split mapping",
        "rollback": "Do not restore BYD cluster UI, settings, or layout code; preserve fixed-main removal.",
        "review_state": "PRIMARY_AGENT_PLAN_EXCLUSION_VERIFIED",
    },
    "c3faa630a488a7b7f4bc8706c829ce2f140d8337": {
        "disposition": "SKIP_OEM",
        "reason": "This is a user-facing wording change for BYD dashboard/cluster navigation settings and its BYD guide. It does not change generic CarPlay output; the vehicle-specific control surface is intentionally absent from fixed main.",
        "test": "PASS_STATIC: source paths are the BYD navigation activity/guide; fixed main has no BYD dashboard/cluster settings surface",
        "related": "BYD/OEM quarantine; no generic STEP4 runtime mapping",
        "rollback": "No strings or BYD navigation copy imported; retain the fixed-main generic UI.",
        "review_state": "PRIMARY_AGENT_PLAN_EXCLUSION_VERIFIED",
    },
    "11bc01c6e28e21d79772f9dbeadc5e947b146006": {
        "disposition": "SKIP_OEM",
        "reason": "The commit adds a persisted map-versus-turn-card choice for the BYD instrument-cluster alt screen and reuses BYD marker positioning. This vehicle-specific cluster content protocol is outside the fixed-main generic CarPlay/Split path.",
        "test": "SOURCE_TESTS: upstream cluster-content state/alt-screen cases; NOT_RUN_OEM: supported BYD cluster rendering and steering-wheel selection",
        "related": "BYD/OEM quarantine; no PR-05 ViewArea wire or PR-06 LOCAL Split mapping",
        "rollback": "No BYD alt-screen preference or content request imported; keep fixed-main display negotiation.",
        "review_state": "PRIMARY_AGENT_PLAN_EXCLUSION_VERIFIED",
    },
    "49d3f927991a1bb0ab0718bed1aa3a3296b84069": {
        "disposition": "SKIP_OEM",
        "reason": "This merge adds localized BYD dashboard map/turn-card settings and strings to a language branch. The controls refer to an OEM cluster stream removed from fixed main and do not provide a generic locale or STEP4 implementation.",
        "test": "PASS_STATIC: merge paths are BYD cluster preferences and translations; fixed main contains no corresponding dashboard content control",
        "related": "BYD/OEM quarantine and localization scope; no generic PR-05/06 mapping",
        "rollback": "No BYD cluster settings/resources imported; maintain the fixed-main locale surface.",
        "review_state": "PRIMARY_AGENT_PLAN_EXCLUSION_VERIFIED",
    },
    "9e0b8687d8d7deb5753bf870753e84a6a4a0f69e": {
        "disposition": "SKIP_OEM",
        "reason": "This merge implements BYD dashboard map/turn-card output and its localized settings. It is OEM cluster functionality, not the fork's local GeckoView Split or generic CarPlay ViewArea path.",
        "test": "SOURCE_TESTS: upstream cluster-content tests; NOT_RUN_OEM: no supported BYD cluster acceptance on the fixed fork",
        "related": "BYD/OEM quarantine; do not map to PR-05 ViewArea payloads or PR-06 LOCAL",
        "rollback": "No cluster content export or related settings imported; retain fixed-main display behavior.",
        "review_state": "PRIMARY_AGENT_PLAN_EXCLUSION_VERIFIED",
    },
    "aebe93adfde510497fcefc98acec3288c280bc2c": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This 51-path feature merge combines BYD cluster-map/UsageStats access with generic local-hotspot setup and other connection changes. The OEM projection and generic wireless setup require separate gates; the aggregate must not be imported to obtain either behavior.",
        "test": "PASS_STATIC: source diff includes BYD cluster display/permission and hotspot manager paths; NOT_RUN_PER_COMMIT: BYD cluster and fork hotspot/route regression tests",
        "related": "Reject aggregate merge; BYD/OEM quarantine is separate from WIRELESS-EX hotspot and fixed-main local-hotspot behavior",
        "rollback": "No aggregate merge imported; preserve the BYD removal and fixed-main hotspot path. Any generic hotspot change must be isolated and independently reversible.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_AGGREGATE_REJECTED",
    },
    "04294209449de38df4bb8e3affb18793e88bb82f": {
        "disposition": "NEED_EVIDENCE",
        "reason": "The patch polls resource configuration while CarPlay is visible because some BYD builds change day/night mode without a callback. This adds recurring UI-thread work to a general Activity for an OEM-specific report and lacks target evidence that fixed-main supported head units exhibit the same callback failure.",
        "test": "SOURCE_TESTS: upstream poll/refresh tests; NOT_RUN_FORK: supported-head-unit day/night transition, poll overhead, and activity lifecycle cancellation",
        "related": "EX-08 display lifecycle; PR-06/PR-08 UI regression; BYD symptom not established for fixed-main targets",
        "rollback": "No polling behavior imported; if reproduced, use a bounded lifecycle-owned workaround with a test for stop/destroy cancellation.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEW_PENDING_TARGET_EVIDENCE",
    },
    "da43081925547dc75bde5995bbf55a27c4e26cc5": {
        "disposition": "SKIP_OEM",
        "reason": "This feature sends iAP2 song/play-pause data to a BYD dashboard music card via ADB and BYD vehicle permissions. It is OEM-only output removed from the fixed fork and has no generic CarPlay media-key mapping.",
        "test": "PASS_STATIC: implementation is under BYD output settings/navigation and requires the BYD ADB vehicle-service path; NOT_RUN_OEM: no supported BYD dashboard music acceptance",
        "related": "BYD/OEM quarantine; keep generic CarPlay media keys and MEDIA DSP separate",
        "rollback": "Do not restore BYD ADB music-card output; retain the fixed-main media-key path.",
        "review_state": "PRIMARY_AGENT_PLAN_EXCLUSION_VERIFIED",
    },
    "bcc5013aeb9331ddf8dc639146369c72f9f3686d": {
        "disposition": "NEED_EVIDENCE",
        "reason": "The candidate combines USB configuration switching with authentication and hotspot behavior. Its USB mode/re-enumeration portion is conditional; do not port the multi-purpose upstream commit without the same-node trace and a split review.",
        "test": "NOT_RUN_TARGET: same-device USB mode transition, permission loop, and authentication/hotspot regression",
        "related": "EX-04 same-node USB re-enumeration; WIRELESS-EX authentication/hotspot behavior",
        "rollback": "No patch imported; any mode-recheck work must be isolated from authentication and hotspot code and reverted independently.",
        "review_state": "PRIMARY_AGENT_CANDIDATE_GATE_REVIEW_PENDING_DEVICE_TRACE",
    },
    "c4db31a6c454d9963cf0de9a4a0b6a6d521eac6a": {
        "disposition": "ADAPT",
        "reason": "The narrow constructor change gates MANUAL hotspot validation on WIRELESS transport. Fixed main supplies persisted hotspot settings to CarPlayRuntimeConfig for both transports, but a wired session does not start the hotspot and should not depend on its credentials. No wireless route or hotspot behavior is changed.",
        "test": "PASS_LOCAL: ManualHotspotConfigTest confirms WIRED accepts a blank hotspot SSID and WIRELESS still rejects it; PASS_GITHUB: run #168 host unit-test suite",
        "related": "PR-08 integrated wired-config regression; WIRELESS-EX route and hotspot behavior remains separate",
        "rollback": "Revert the transport-qualified constructor check and its two tests together; no hotspot manager, route, or retry behavior is changed.",
        "review_state": "PRIMARY_AGENT_ADAPTED_WIRED_CONFIG_BOUNDARY",
    },
    "732b1016d5f835f1f4ff5e145e96d355336f9785": {
        "disposition": "ADAPT",
        "reason": "The patch SHA is identical to merge c4db31a6c454d9963cf0de9a4a0b6a6d521eac6a. The fixed-main transport-specific constructor adaptation covers this source occurrence too; represent the duplicate source row without adding a second code change.",
        "test": "PASS_LOCAL: ManualHotspotConfigTest confirms WIRED accepts a blank hotspot SSID and WIRELESS still rejects it; PASS_GITHUB: run #168 host unit-test suite",
        "related": "Duplicate patch row for c4db31a6c454d9963cf0de9a4a0b6a6d521eac6a; one PR-08 wired-config adaptation; no separate WIRELESS-EX route change",
        "rollback": "No duplicate implementation imported. Revert the single transport-qualified constructor check and its tests from commit 5840d72 if they regress.",
        "review_state": "PRIMARY_AGENT_ADAPTED_DUPLICATE_PATCH_ONCE",
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
        "disposition": "FORK_SUPERIOR",
        "reason": "PR478 combines the NCM optional-pad repair with Android 8 USB-request queue sizing. PR-01 already adapts the NCM framing fix to fixed-main persistent-read ownership; v3 excludes the API 26/27 ceiling because all fixed-main apps use minSdk 28, and the API 28+ queue(false) fallback remains conditional without a device-present reproduction. The adapted fork path is the applicable implementation.",
        "test": "PASS_STATIC: origin/main common/mobile/automotive minSdk is 28; PASS_PR45: NcmUsbBridgeFixtureTest covers exact-boundary padded/unpadded NTBs, next-NTH16 preservation, fragmented reads, and sync/async paths; NOT_RUN_TARGET: API 28+ device-present UsbRequest.queue()==false",
        "related": "PR-01 adapted NCM framing; PR-02 API 26/27 ceiling NOT_APPLICABLE at minSdk 28; API 28+ queue(false) fallback remains CONDITIONAL",
        "rollback": "No queue-sizing policy imported; retain PR-01's isolated NCM adaptation and keep any future queue fallback behind its own device-present reproduction and reversible PR-45 change.",
        "review_state": "PRIMARY_AGENT_FIXED_MAIN_AND_PLAN_COMPARISON",
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
        "disposition": "REJECT_UNSAFE",
        "reason": "The PR495 merge couples an unverified API 28+ UsbRequest.queue(false) retry ladder with new cause-chain and stack-frame text in session diagnostics. These have separate PR-02 device-reproduction and EX-01 privacy gates; the combined commit cannot be accepted as one STEP4 change.",
        "test": "PASS_STATIC: fixed-main minSdk is 28 and the merge changes both queue retry policy and session diagnostic text; NOT_RUN_TARGET: API 28+ device-present queue(false), queue/wait ownership, and request teardown; NOT_RUN_PRIVACY: cause-chain message/frame redaction for identifiers and payload-like values",
        "related": "Reject aggregate import; keep PR-02 API 28+ queue(false) conditional and evaluate EX-01 diagnostics separately",
        "rollback": "No queue ladder or cause-chain logging imported. Retain fixed-main request ownership; any accepted queue or diagnostic change must be isolated, tested, and independently revertible.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_AGGREGATE_REJECTED",
    },
    "48ef3b46b13c816a47312923bb61de7c8fb6444c": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This integration commit combines the NCM optional-pad change with the unverified PR495 queue retry ladder and expanded throwable diagnostics. PR-01 has already adapted the NCM framing fix; the remaining queue and logging behaviors have separate device/privacy gates and cannot be safely imported as this aggregate.",
        "test": "PASS_PR45: NcmUsbBridgeFixtureTest covers the adapted exact-boundary/padding behavior; PASS_STATIC: fixed-main app minSdk is 28; NOT_RUN_TARGET: device-present API 28+ queue(false); NOT_RUN_PRIVACY: nested throwable diagnostics",
        "related": "Reject aggregate import; PR-01 NCM framing is adapted; PR-02 queue(false) remains conditional; evaluate EX-01 diagnostic privacy separately",
        "rollback": "Do not import the combined queue/logging behavior. Keep PR-01 framing isolated; any future queue or diagnostic change must be separately supported and revertible.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_AGGREGATE_REJECTED",
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
        "disposition": "REJECT_UNSAFE",
        "reason": "This commit combines an issue-specific USBMUX trailer exception, a SurfaceView fallback for a reported WUTONG S311_ICA Android 9 display, and separate RFCOMM stall handling. Its source capture and SurfaceView report do not establish the other behaviors for fixed-main TS10/K706 targets, and v3 gates USBMUX, PR-07 video, and wireless recovery independently; reject the supplied aggregate.",
        "test": "SOURCE_DIFF: USBMUX frame parser, SurfaceView ownership, and RFCOMM transport are separate changes; SOURCE_TESTS: upstream synthetic parser and SurfaceView cases; NOT_RUN_TARGET: supported-device USBMUX capture, codec/surface matrix, and RFCOMM reconnect cases",
        "related": "Reject aggregate import; retain USBMUX-EX as capture-gated, PR-07 as codec/device-gated, and RFCOMM under WIRELESS-EX",
        "rollback": "No aggregate patch imported. Any later accepted USBMUX, SurfaceView, or RFCOMM change must be isolated and independently reversible.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_AGGREGATE_REJECTED",
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
    "f77298cc39150f7e69ca1f2a5a62e48a4da77bff": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This 37-path current-main sync into a safe-area repair branch mixes BYD/DiLink cluster UI, AccessibilityService USB auto-confirm, diagnostic-provider export, and menu/safe-area changes. It is not a single STEP4 behavior and would restore OEM/permission paths outside the fixed-main scope.",
        "test": "PASS_STATIC: source diff includes BYD/DiLink and UsbAutoConfirmService plus diagnostic export and UI; fixed main omits auto-confirm and BYD features; NOT_RUN_PER_COMMIT: permission, report privacy, and safe-area lifecycle regressions",
        "related": "Reject aggregate sync; PR-00 consent, EX-01 diagnostics, PR-05/06 safe-area/UI, and BYD/OEM quarantine remain separate",
        "rollback": "No merge imported; preserve fixed-main permissions and UI. Any separate feature change must have its own acceptance and rollback.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_AGGREGATE_REJECTED",
    },
    "e26e54acfd866350c2eafae2ccc187a4c8c02857": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This 36-path current-main sync into a multi-window repair branch mixes BYD/DiLink code, AccessibilityService USB auto-confirm, diagnostic export, and independent display-bar refresh changes. The aggregate spans OEM, permission, and UI ownership behavior beyond a discrete STEP4 gate.",
        "test": "PASS_STATIC: source diff includes BYD/DiLink, UsbAutoConfirmService, diagnostic provider, and multi-window UI; fixed main has no accessibility auto-grant or BYD support; NOT_RUN_PER_COMMIT: permission, report privacy, and multi-window display lifecycle",
        "related": "Reject aggregate sync; PR-00 consent, EX-01 diagnostics, PR-05/06 display lifecycle, and BYD/OEM quarantine are separate",
        "rollback": "No merge imported; retain fixed-main consent and display behavior. Any future candidate must be isolated and independently reversible.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_AGGREGATE_REJECTED",
    },
    "a3efba959fe056094110c450721b6b1600fc0da0": {
        "disposition": "SKIP_OEM",
        "reason": "This 253-path consolidation restores DiLink 4 cluster routing and optional OEM map restoration, including BydOemClusterNavi and vehicle-specific output. Fixed main deliberately removed BYD/OEM behavior; the merge also carries unrelated changes that must not be credited to a generic STEP4 feature.",
        "test": "PASS_STATIC: source diff includes DiLink4ClusterDisplay, BydOemClusterNavi, BYD package/manifest paths, and OEM route restoration; NOT_RUN_OEM: no generic supported-model evidence",
        "related": "BYD/OEM quarantine; no generic PR-01–PR-08 mapping",
        "rollback": "Do not restore BYD cluster/navigation code; preserve the fixed-main removal and review any non-OEM constituent separately.",
        "review_state": "PRIMARY_AGENT_PLAN_EXCLUSION_VERIFIED",
    },
    "2385c08f15cf05822e01cefbb79050d0389b5c48": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This 72-path upstream-main sync into experimental call-control work has 130 diff hunks spanning BYD, USB AccessibilityService auto-confirm, LocalAdb, USB permission setup, and CarPlay controller/audio behavior. The aggregate would change consent and phone/audio behavior outside the ordered STEP4 gates.",
        "test": "PASS_STATIC: patch contains UsbAutoConfirmService, LocalAdb, BYD, and call/controller files; fixed main preserves UsbManager consent and BYD quarantine; NOT_RUN_PER_COMMIT: call/permission/accessibility device matrix",
        "related": "Reject aggregate sync; PR-00 USB consent, PR-04 call/microphone routes, and BYD/OEM quarantine are separate gates",
        "rollback": "No merge imported; preserve fixed-main permission and call/audio paths. Any accepted constituent must be isolated and independently reversible.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_AGGREGATE_REJECTED",
    },
    "0e236008816f70856b833a9c9a5e134a9046ae4d": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This 72-path upstream-main sync into modular-settings extraction has 130 diff hunks across BYD, USB AccessibilityService auto-confirm, LocalAdb, permission setup, settings, and controller behavior. It is a broad integration rather than a single STEP4 change and conflicts with fixed-main consent/OEM boundaries.",
        "test": "PASS_STATIC: patch contains UsbAutoConfirmService, LocalAdb, BYD, and settings/controller files; fixed main preserves UsbManager consent and BYD quarantine; NOT_RUN_PER_COMMIT: permission, accessibility, and settings lifecycle cases",
        "related": "Reject aggregate sync; PR-00 permission flow, settings lifecycle, and BYD/OEM quarantine must be evaluated separately",
        "rollback": "No merge imported; keep fixed-main settings and permission behavior. Any future accepted component must be isolated and independently reversible.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_AGGREGATE_REJECTED",
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
        "disposition": "FORK_SUPERIOR",
        "reason": "The upstream patch cancels and drains a timed-out USBMUX read to recover bytes that arrive in the timeout/cancel race. Fixed main avoids that cancellation window: Iap2UsbSession.read leaves the request queued on TimeoutException, and the next call awaits the same request, allowing a late completion to deliver its bytes without requeueing.",
        "test": "PASS_LOCAL: PersistentUsbReadRequestStateTest.timeoutKeepsSameRequestQueuedAndDoesNotRequestCancellation; PASS_STATIC: Iap2UsbSession.read returns on timeout without canceling or requeueing and accepts the same request on the next completion; NOT_RUN_TARGET: no supported-head-unit trace confirms the reported timing",
        "related": "USBMUX_READ_COMPLETION_RACE: fixed-main persistent request ownership; distinct from PR-01 NCM receive recovery and USBMUX trailer parsing",
        "rollback": "No change needed; retain same-request ownership across wait timeouts and cancel only through close/failure ownership. Revisit only if a supported-device trace shows the queued request is invalidated after timeout.",
        "review_state": "PRIMARY_AGENT_FIXED_MAIN_TIMEOUT_OWNER_COMPARISON",
    },
    "59c2652592ce92d2fb22e4554858deb0bbaa4656": {
        "disposition": "REJECT_UNSAFE",
        "reason": "The helper invokes IUsbManager.grantDevicePermission through an existing local-ADB shell trust when the USB permission activity is unavailable. Only API 28 overlaps fixed main's minSdk, and v3's USB_PERMISSION_UI_MISSING candidate allows bounded re-request/de-duplication, not an ADB grant that bypasses per-device system consent.",
        "test": "PASS_STATIC: IphoneUsbAdbGrant uses app_process to call grantDevicePermission after ADB pairing; fixed main uses UsbManager.requestPermission; SOURCE_TESTS: upstream helper/parser tests do not validate user consent; NOT_RUN_TARGET: API 28 missing permission-UI reproducer",
        "related": "Reject permission bypass; PR-00 retains per-device consent; USB_PERMISSION_UI_MISSING is limited to bounded re-request/de-duplication; API 25-27 is NOT_APPLICABLE at minSdk 28",
        "rollback": "No ADB grant helper imported; keep the explicit UsbManager request and do not add a fallback until the plan's consent boundary and a supported-device failure path justify one.",
        "review_state": "PRIMARY_AGENT_REJECTED_PERMISSION_BYPASS",
    },
    "19649861dab5d65727f195f2d6b7762a0d141005": {
        "disposition": "REJECT_UNSAFE",
        "reason": "The commit bundles a USBMUX parser exception for a reported iOS 27 VERSION-after-trailer frame with a persisted-setting-derived home-card transport title, translations, release-note claims, and UI state changes. Fixed main uses a strict UsbMuxFrameDecoder and derives connection behavior from explicit selection; the combined change would loosen framing and couple UI to persisted transport without target evidence.",
        "test": "PASS_STATIC: diff changes UsbMuxFrameBuffer and DiPlayActivity/HomeCardTransport/resources; fixed main has a strict UsbMuxFrameDecoder and no HomeCardTransport; SOURCE_TESTS: upstream synthetic transfer-split cases; NOT_RUN_TARGET: supported-device VERSION-after-trailer capture and live home-card session transition",
        "related": "Reject mixed USBMUX-EX trailer and home-card UI commit; PR-01 CDC-NCM padding is separate",
        "rollback": "No parser exception or home-card state imported; retain fixed-main framing and connection-selection ownership. Any later trailer adaptation must be independently tested from a capture-derived fixture.",
        "review_state": "PRIMARY_AGENT_PATCH_REJECTED_MIXED_USBMUX_UI",
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
    "7dabe889ed0d093af28f8d7fb9c9d187f3ecc576": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This connection-triage merge combines NCM pad/resync changes, an older-iOS iAP2 message retry, raw USB mode/configuration diagnostics, and AccessibilityService USB auto-confirm. PR-01 already adapts the bounded NCM behavior; importing the aggregate would alter consent and diagnostic/protocol behavior outside that validated change, with no supported-device evidence.",
        "test": "PASS_PR45: NcmUsbBridgeFixtureTest covers bounded pad/resync behavior; PASS_STATIC: origin/main uses UsbManager.requestPermission and has no UsbAutoConfirmService; NOT_RUN_TARGET: older-iOS identification, USB mode rejection, and wireless/device cases",
        "related": "Reject aggregate; PR-01 NCM adaptation, USB permission consent, EX-01 diagnostic privacy, and older-iOS iAP2 behavior require separate decisions",
        "rollback": "No aggregate imported; keep the tested PR-01 framing change and fixed-main permission flow. Any other component must be independently supported and revertible.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_AGGREGATE_REJECTED",
    },
    "946487526d677e04b8b97d630e03154e1ac0453a": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This 47-path upstream-main sync into a repair branch spans BYD HUD, API 25 USB completion reaping, NCM, Wi-Fi P2P, audio-focus/microphone, and UI/settings code. Importing the merge would bypass v3 per-feature gates and could restore BYD/OEM behavior removed from fixed main.",
        "test": "PASS_STATIC: source diff spans USB/NCM, Android 7 reaper, BYD, Wi-Fi, audio, and settings; fixed main minSdk is 28 and quarantines BYD; NOT_RUN_PER_COMMIT: constituent changes require separate tests/evidence",
        "related": "Reject aggregate sync merge; review underlying commits separately under PR-01/02, PR-04, EX-06/07, PR-05/06, and BYD/OEM quarantine",
        "rollback": "No merge imported; preserve fixed-main minSdk and feature set. Only split, evidence-backed changes may enter PR-45 with individual rollback.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_AGGREGATE_REJECTED",
    },
    "e96bc43aac174dde5f9c81c86296f52967d308ed": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This 47-path upstream-main sync into a boot-repair branch spans BYD HUD, API 25 USB completion reaping, NCM, Wi-Fi P2P, audio, and UI/settings code. The aggregate is not a discrete STEP4 change and conflicts with fixed-main minSdk/BYD boundaries.",
        "test": "PASS_STATIC: source diff spans USB/NCM, Android 7 reaper, BYD, Wi-Fi, audio, and settings; fixed main minSdk is 28 and quarantines BYD; NOT_RUN_PER_COMMIT: constituent changes require separate tests/evidence",
        "related": "Reject aggregate sync merge; review underlying commits separately under PR-01/02, PR-04, EX-06/07, PR-05/06, and BYD/OEM quarantine",
        "rollback": "No merge imported; preserve fixed-main minSdk and feature set. Only split, evidence-backed changes may enter PR-45 with individual rollback.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_AGGREGATE_REJECTED",
    },
    "7df2dfa274669376174ebb1e83e5a0af44a22d46": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This 47-path upstream-main sync into a small-window repair branch carries BYD HUD, API 25 USB reads, NCM, Wi-Fi, audio, and settings changes plus branch-specific conflict resolution. It cannot be imported as one unit under v3's no-bulk-merge and BYD quarantine rules.",
        "test": "PASS_STATIC: source diff spans USB/NCM, Android 7 reaper, BYD, Wi-Fi, audio, and settings; fixed main minSdk is 28 and quarantines BYD; NOT_RUN_PER_COMMIT: constituent changes require separate tests/evidence",
        "related": "Reject aggregate sync merge; review underlying commits separately under PR-01/02, PR-04, EX-06/07, PR-05/06, and BYD/OEM quarantine",
        "rollback": "No merge imported; preserve fixed-main minSdk and feature set. Only split, evidence-backed changes may enter PR-45 with individual rollback.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_AGGREGATE_REJECTED",
    },
    "a53c6c09861e95d909a76af8be9d2d75a205643a": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This 88-path review-batch merge has 248 diff hunks across BYD, USB/NCM, API 25 reads, Wi-Fi, microphone/audio, and application UI, and records a conflict in DiPlayActivity. The combined branch snapshot is not one supported STEP4 change.",
        "test": "PASS_STATIC: source diff confirms multiple transport/media/OEM/UI families and a conflict-resolved activity; NOT_RUN_PER_COMMIT: each component's fork/device regression evidence",
        "related": "Reject aggregate integration merge; review constituent behavior under PR-01/02, PR-04, EX-06/07, PR-05/06, and BYD/OEM quarantine",
        "rollback": "No aggregate merge imported; retain fixed-main sources and accept only isolated evidence-backed commits with independent rollback.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_AGGREGATE_REJECTED",
    },
    "47c0d1889b12a689003a6e53f8f191fbcdbe3cb3": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This 114-path integration merge contains 328 diff hunks and five conflict resolutions across the prior repair, BYD, USB/NCM, API 25 reads, Wi-Fi, audio, settings, and UI. It is a cumulative branch integration, not an atomic candidate for fixed main.",
        "test": "PASS_STATIC: source diff confirms multiple transport/media/OEM/UI families and five merge conflicts; NOT_RUN_PER_COMMIT: each component's fork/device regression evidence",
        "related": "Reject aggregate integration merge; review constituent behavior under PR-01/02, PR-04, EX-06/07, PR-05/06, and BYD/OEM quarantine",
        "rollback": "No aggregate merge imported; retain fixed-main sources and accept only isolated evidence-backed commits with independent rollback.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_AGGREGATE_REJECTED",
    },
    "ecd24c0d8de7b103af8a15c6b60dca430a5531f9": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This 306-path current-main merge has 658 diff hunks across BYD, NCM/UsbRequest, USBMUX, Android 7, Wi-Fi, microphone/audio, and SurfaceView behavior. It is a broad branch integration with no single STEP4 acceptance boundary; direct adoption would violate v3's no-bulk-merge and fork-preservation rules.",
        "test": "PASS_STATIC: source diff spans transport, OEM, wireless, media, and video-surface areas; fixed main retains minSdk 28 and BYD quarantine; NOT_RUN_PER_COMMIT: isolated regressions for each constituent change",
        "related": "Reject aggregate merge; review constituent work separately under PR-01/02, PR-04, PR-05/06/07, USBMUX-EX, wireless, and BYD/OEM quarantine",
        "rollback": "No merge imported; retain fixed-main code. Only an independently accepted feature may enter PR-45 with its own tests and rollback.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_AGGREGATE_REJECTED",
    },
    "9e845b250e267626b695f98cf9d2e5c9eb05ff6f": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This 310-path current-main integration has 661 hunks spanning BYD, USB/NCM/USBMUX, Android 7, Wi-Fi, audio, and SurfaceView changes. It combines unrelated branches and conflict resolutions rather than one feature; importing it as a unit would bypass the v3 per-feature gates.",
        "test": "PASS_STATIC: source diff spans transport, OEM, wireless, media, and video-surface areas; fixed main retains minSdk 28 and BYD quarantine; NOT_RUN_PER_COMMIT: isolated regressions for each constituent change",
        "related": "Reject aggregate merge; review constituent work separately under PR-01/02, PR-04, PR-05/06/07, USBMUX-EX, wireless, and BYD/OEM quarantine",
        "rollback": "No merge imported; retain fixed-main code. Only an independently accepted feature may enter PR-45 with its own tests and rollback.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_AGGREGATE_REJECTED",
    },
    "3a22e6ce6e1e9dfee8a0a8c695e8bd9676fc454a": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This 193-path current-main repair merge spans BYD, API 25 USB reads/NCM, Wi-Fi, microphone/audio, and Surface behavior. The aggregate mixes platform support and independent product paths; v3 explicitly fixes minSdk 28 and forbids bulk integration.",
        "test": "PASS_STATIC: source diff spans transport, OEM, wireless, media, and video-surface areas; fixed main retains minSdk 28 and BYD quarantine; NOT_RUN_PER_COMMIT: isolated regressions for each constituent change",
        "related": "Reject aggregate merge; review constituent work separately under PR-01/02, PR-04, PR-05/06/07, wireless, and BYD/OEM quarantine",
        "rollback": "No merge imported; preserve fixed-main minSdk and code. Any supported constituent must be isolated and independently revertible.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_AGGREGATE_REJECTED",
    },
    "662eaf324c195ac93b3c51ff7840658bac45e3d7": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This 299-path current-main merge has 651 diff hunks across BYD, NCM/UsbRequest, USBMUX, Android 7, Wi-Fi, microphone/audio, and SurfaceView behavior. The integration snapshot is too broad to accept under STEP4's ordered feature gates.",
        "test": "PASS_STATIC: source diff spans transport, OEM, wireless, media, and video-surface areas; fixed main retains minSdk 28 and BYD quarantine; NOT_RUN_PER_COMMIT: isolated regressions for each constituent change",
        "related": "Reject aggregate merge; review constituent work separately under PR-01/02, PR-04, PR-05/06/07, USBMUX-EX, wireless, and BYD/OEM quarantine",
        "rollback": "No merge imported; retain fixed-main code. Only an independently accepted feature may enter PR-45 with its own tests and rollback.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_AGGREGATE_REJECTED",
    },
    "65a4fdbeb8c233f1f41f07475c1c3da708d9bd9a": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This 301-path current-main merge has 653 diff hunks spanning BYD, NCM/UsbRequest, USBMUX, Android 7, Wi-Fi, audio, and SurfaceView behavior. It is a cross-branch integration snapshot rather than an atomic feature and cannot bypass the v3 ordering and rollback gates.",
        "test": "PASS_STATIC: source diff spans transport, OEM, wireless, media, and video-surface areas; fixed main retains minSdk 28 and BYD quarantine; NOT_RUN_PER_COMMIT: isolated regressions for each constituent change",
        "related": "Reject aggregate merge; review constituent work separately under PR-01/02, PR-04, PR-05/06/07, USBMUX-EX, wireless, and BYD/OEM quarantine",
        "rollback": "No merge imported; retain fixed-main code. Only an independently accepted feature may enter PR-45 with its own tests and rollback.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_AGGREGATE_REJECTED",
    },
    "1af2bb6f0680099dff80323c37a34d7601c80094": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This 304-path current-main integration has 654 diff hunks across BYD, NCM/UsbRequest, USBMUX, Android 7, Wi-Fi, audio, and SurfaceView behavior. The aggregate merge conflicts with the fixed-main BYD/minSdk boundary and collapses independent STEP4 gates.",
        "test": "PASS_STATIC: source diff spans transport, OEM, wireless, media, and video-surface areas; fixed main retains minSdk 28 and BYD quarantine; NOT_RUN_PER_COMMIT: isolated regressions for each constituent change",
        "related": "Reject aggregate merge; review constituent work separately under PR-01/02, PR-04, PR-05/06/07, USBMUX-EX, wireless, and BYD/OEM quarantine",
        "rollback": "No merge imported; retain fixed-main code. Any supported constituent must be isolated, tested, and independently revertible.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_AGGREGATE_REJECTED",
    },
    "c6561c8fcef1f4264112fc88a7d6ad3cd553a04a": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This 56-path upstream-main merge into a cluster-music feature branch combines BYD cluster/music output, NCM write-timeout handling, USBMUX, microphone/audio, and settings changes. V3 quarantines BYD cluster outputs and keeps the transport/audio stages isolated; the merged snapshot is not a safe STEP4 unit.",
        "test": "PASS_STATIC: source diff spans BYD cluster music, NCM, USBMUX, microphone/audio, and settings; NOT_RUN_TARGET: cluster output, USB transport, and audio A/V acceptance on TS10/K706",
        "related": "Reject aggregate merge; BYD/OEM quarantine, PR-01 NCM, PR-04 audio, and USBMUX-EX require separate decisions",
        "rollback": "No aggregate merge imported; preserve fixed-main BYD removal and transport/audio behavior. Any independently supported fix must be isolated and revertible.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_AGGREGATE_REJECTED",
    },
    "70da505363b85fffe1dc0438be877943c8e00a35": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This 118-path origin/main sync into a swipe-menu branch contains BYD routes, NCM, USBMUX, microphone/audio, and wide UI/settings/resource changes. It is a cross-domain snapshot rather than one selected STEP4 feature and would reintroduce unreviewed OEM behavior.",
        "test": "PASS_STATIC: source diff spans transport, BYD, audio, and settings/resources; NOT_RUN_PER_COMMIT: individual UI, NCM, USBMUX, and media regression evidence",
        "related": "Reject aggregate merge; evaluate any constituent separately under PR-01, PR-04, USBMUX-EX, PR-05/06, and BYD/OEM quarantine",
        "rollback": "No merge imported; retain fixed-main behavior. Any future accepted piece must be a separate, independently revertible change.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_AGGREGATE_REJECTED",
    },
    "23e99a73c7860548a225ea979f744a30eecdf919": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This 118-path origin/main sync into a PR656 fix branch contains BYD routes, NCM, USBMUX, microphone/audio, and wide UI/settings/resource changes, with branch-specific resolution differences from the swipe-menu merge. It is not an atomic STEP4 change and would reintroduce unreviewed OEM behavior.",
        "test": "PASS_STATIC: source diff spans transport, BYD, audio, and settings/resources; NOT_RUN_PER_COMMIT: individual UI, NCM, USBMUX, and media regression evidence",
        "related": "Reject aggregate merge; evaluate any constituent separately under PR-01, PR-04, USBMUX-EX, PR-05/06, and BYD/OEM quarantine",
        "rollback": "No merge imported; retain fixed-main behavior. Any future accepted piece must be a separate, independently revertible change.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_AGGREGATE_REJECTED",
    },
    "090ae80a52efaaeefc78f1317e20c33a172c8f67": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This 55-path integration of ten upstream PRs spans BYD, NCM, Wi-Fi P2P, microphone/audio, and USBMUX-facing controller code. The 116-hunk multi-PR snapshot does not preserve the v3 stage boundaries, so it cannot be imported as one unit.",
        "test": "SOURCE_REVIEW: patch integrates separate BYD, NCM, wireless, and audio changes; NOT_RUN_FORK: constituent transport, phone-route, and target hardware matrices",
        "related": "Reject aggregate merge; review constituent behavior separately under PR-01/02, PR-04, wireless, and BYD/OEM quarantine",
        "rollback": "No aggregate import; preserve fixed-main feature boundaries and only accept isolated evidence-backed changes with independent rollback.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_AGGREGATE_REJECTED",
    },
    "ca33101d52598686b7763dce8c8396eb1ea369b9": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This 84-path main-branch merge has 187 diff hunks spanning BYD, API 25 USB reads, NCM/USBMUX, Wi-Fi, audio/microphone, diagnostics, and video. The broad synchronization snapshot crosses unrelated fixed-main ownership and OEM boundaries.",
        "test": "SOURCE_REVIEW: patch includes multiple transport, wireless, media, and BYD/OEM subsystems; NOT_RUN_FORK: feature-specific behavior and supported-device acceptance",
        "related": "Reject aggregate sync; review USB/NCM, USBMUX, PR-04 audio, wireless, video, and BYD/OEM changes under separate gates",
        "rollback": "No merge imported; retain fixed-main code and evaluate only isolated, evidence-backed components in PR-45.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_AGGREGATE_REJECTED",
    },
    "daf6e843e64be61f5460db992910fa00b7e65e97": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This 21-path integration of the side-panel and USB parser branches combines ViewArea/side-panel UI, BYD code, and USBMUX framing. A synthetic parser test and UI branch cannot be transplanted together as a single STEP4 implementation unit.",
        "test": "SOURCE_REVIEW: patch spans side-panel/ViewArea, BYD, and USBMUX parser/test files; NOT_RUN_FORK: supported-device USBMUX capture and integrated Split/side-panel lifecycle matrix",
        "related": "Reject aggregate merge; ViewArea/side-panel under PR-05/06, parser under USBMUX-EX, and BYD code under OEM quarantine",
        "rollback": "No merge imported; preserve fixed-main UI and parser. Any future change must be split and independently reversible.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_AGGREGATE_REJECTED",
    },
    "d185bc67a1d25f8e206c68eb6d5e3da63efa0aac": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This 56-path final-main merge carries experimental call behavior, USBMUX parsing, ViewArea/side-panel UI, Wi-Fi, and BYD/OEM features. Preserving the call opt-in does not make the aggregate safe for the fixed fork or satisfy the separate PR-05/06 and USBMUX gates.",
        "test": "SOURCE_REVIEW: patch contains multiple transport, ViewArea, side-panel, wireless, and BYD/OEM changes; NOT_RUN_FORK: call opt-in and target ViewArea/USBMUX acceptance",
        "related": "Reject aggregate merge; PR-04 call/audio, PR-05/06 ViewArea, USBMUX-EX, wireless, and BYD/OEM quarantine remain separate",
        "rollback": "No merge imported; keep fixed-main defaults and feature flags. Any future accepted component must be an isolated reversible change.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_AGGREGATE_REJECTED",
    },
    "747089ea68cf4bed8a87bee681ac7af27faea32f": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This 50-path release-notes branch sync contains live BYD, API 25 USB read, NCM/USBMUX, Wi-Fi, microphone, and video changes in addition to release text. It is not documentation-only and cannot be treated as a safe release-note merge.",
        "test": "SOURCE_REVIEW: changed paths include production transport/media/OEM code and locale/resources; NOT_RUN_FORK: component-specific regression and target release validation",
        "related": "Reject aggregate merge; review PR-01/02, PR-04, USBMUX-EX, wireless, video, and BYD/OEM areas separately",
        "rollback": "No merge imported; retain fixed-main source and release documentation. Any accepted runtime change needs its own rollback.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_AGGREGATE_REJECTED",
    },
    "f9ee55f6d0841901f500d71769b47bc069411719": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This 51-path upstream-main merge combines BYD, Android 7 USB reads, USBMUX, Wi-Fi, microphone/audio, and navigation gain handling. The aggregate crosses the minSdk, OEM, transport, and audio timing gates; navigation gain changes also require the dedicated audio/clock evidence.",
        "test": "SOURCE_REVIEW: patch spans USB/NCM, USBMUX, wireless, microphone, BYD, and navigation audio; NOT_RUN_FORK: separate transport, mic/call, and navigation gain measurements",
        "related": "Reject aggregate merge; PR-01/02 USB, USBMUX-EX, PR-04/AUDIO-EX, wireless, and BYD/OEM quarantine are independent",
        "rollback": "No merge imported; preserve fixed-main minSdk, BYD removal, and audio gain defaults. Accept only separately measured, revertible changes.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_AGGREGATE_REJECTED",
    },
    "292b2dd7d05166e714eddc2fc245fe1d5cbf48b5": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This 173-path connection-integration merge combines NCM framing, USB host, buffered audio, microphone/Opus, audio focus, and CarPlay orchestration changes. Its 141-hunk snapshot is not one STEP4 unit, and v3 requires these transport/audio gates to remain separate; PR-01 already adapted the isolated NCM boundary behavior.",
        "test": "PASS_STATIC: source diff spans transport, NCM, microphone, audio focus, and session ownership; NOT_RUN_PER_COMMIT: isolated USB/audio/phone-route regressions for each constituent change",
        "related": "Reject aggregate merge; review NCM under PR-01, queue behavior under PR-02, audio/microphone under PR-04/AUDIO-EX, and session changes under their individual gates",
        "rollback": "No merge imported; preserve the fixed-main transport and media paths. Any accepted constituent must be a separately tested and reversible PR-45 change.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_AGGREGATE_REJECTED",
    },
    "6aced77d0be6c081d26fd158ecb607d162d37e09": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This 32-path merge combines two-second wired-start steps, USB AccessibilityService auto-confirm, NCM diagnostics, call/microphone audio, and BYD route behavior. The shorter watchdog conflicts with v3's 8/15-second preserve gate, auto-confirm changes permission semantics, and the remaining features require separate evidence.",
        "test": "PASS_STATIC: fixed main preserves 8/15-second watchdogs and UsbManager.requestPermission; source diff includes the two-second steps and UsbAutoConfirmService; NOT_RUN_PER_COMMIT: target audio/USB/controller regression matrix",
        "related": "Reject aggregate merge; EX-03/PR-03 watchdog preserve, PR-00 permission consent, PR-01 NCM, PR-04 audio, and BYD quarantine are separate decisions",
        "rollback": "No merge imported; keep fixed-main watchdog, permission, transport, and BYD behavior. Any future isolated change needs its own evidence and one-commit rollback.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_AGGREGATE_REJECTED",
    },
    "2ed938e67a5f245a36a665733bb69d8807091587": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This merge combines the USBMUX nested VERSION-trailer exception with a separate wired home-card title, Wi-Fi hint, and translations. The upstream synthetic split tests do not exercise the fixed-main parser or home-card session/settings lifecycle, so the multi-area merge is not an atomic STEP4 change.",
        "test": "SOURCE_DIFF: parser and home-card/resource changes are separate; SOURCE_TESTS: upstream synthetic nested-trailer split tests and HomeCardTransportTest; NOT_RUN_FORK: supported-device VERSION-after-trailer capture, malformed framing, and home-card session/settings lifecycle",
        "related": "Reject aggregate import; keep the USBMUX-EX parser candidate capture-gated and review home-card UI separately",
        "rollback": "No code or translations imported; preserve fixed-main parser and UI. Any future USBMUX or home-card change must be split, validated, and independently reversible.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_AGGREGATE_REJECTED",
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
    "cc724a326fe7101cc22891df086b678ea6bc4090": {
        "disposition": "FORK_SUPERIOR",
        "reason": "This row's patch digest is identical to 0a305b4440ebda0b635b7e2f5badd69301d77339, so it carries no distinct code change. The shared patch uses an existing ADB shell trust to invoke IUsbManager.grantDevicePermission when the system UI is unavailable; fixed main retains explicit UsbManager.requestPermission, while v3 only permits missing-UI retry/de-duplication.",
        "test": "PASS_STATIC: identical patch digest to the reviewed 0a305b ADB-grant merge; origin/main has no ADB grant path; NOT_RUN_TARGET: API 28 missing-UI reproduction; NOT_RUN_SECURITY: ADB trust/revocation and silent grant lifecycle",
        "related": "Duplicate patch occurrence of 0a305b4440ebda0b635b7e2f5badd69301d77339; PR-00 per-device consent and EX-04 retry/de-duplication only",
        "rollback": "No grant fallback imported; keep the fixed-main UsbManager prompt and track the shared patch once rather than repeating it in the fork history.",
        "review_state": "PRIMARY_AGENT_DUPLICATE_PATCH_FIXED_MAIN_PERMISSION_COMPARISON",
    },
    "b99042da99ca00eaa893f787ec75f316b583e59b": {
        "disposition": "FORK_SUPERIOR",
        "reason": "The patch broadens UsbAutoConfirmService to click Android 10+ USB prompts that name the app without saying USB and adds BYD/AOSP accessibility-settings deep links. It still auto-grants device access through AccessibilityService, and adds BYD-specific settings; fixed main preserves explicit UsbManager consent and the v3 candidate permits retry/de-duplication only.",
        "test": "PASS_STATIC: origin/main has no UsbAutoConfirmService and requests USB permission through UsbManager; v3 keeps BYD/OEM code quarantined; NOT_RUN_TARGET: Android 10+ permission wording, accessibility auto-click, and BYD settings screens",
        "related": "PR-00 per-device USB consent; EX-04 missing-UI retry/de-duplication only; BYD/OEM quarantine",
        "rollback": "No change needed; retain fixed-main UsbManager.requestPermission and BYD removal. Do not add AccessibilityService auto-grant without a separately approved security design.",
        "review_state": "PRIMARY_AGENT_FIXED_MAIN_PERMISSION_COMPARISON",
    },
    "ddb6b70dc2666146101ff140baf89f4289cb2c46": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This merge combines AccessibilityService USB auto-confirm (even though limited to DiPlay system dialogs) with a new diagnostic-report FileProvider/export flow. The permission action changes fixed main's explicit per-device consent, while report content/storage needs its own privacy review; the aggregate is outside the approved USB retry/de-duplication scope.",
        "test": "PASS_STATIC: origin/main uses UsbManager.requestPermission and has no UsbAutoConfirmService; SOURCE_TESTS: upstream service/report tests exist; NOT_RUN_FORK: accidental prompt matching, device-detach timing, and private report export/retention",
        "related": "PR-00 USB permission consent; EX-04 missing-UI retry/de-duplication only; EX-01 diagnostic report privacy",
        "rollback": "No merge imported; retain explicit permission UI and existing report handling. Any future report-export adaptation must be separate from permission automation and independently reversible.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_AGGREGATE_REJECTED",
    },
    "00b432b4ec07234c92315b4fd134d97c83205723": {
        "disposition": "NEED_EVIDENCE",
        "reason": "This change coordinates ADB writes to Android's enabled_accessibility_services setting while enabling an upstream WheelKeyService. Fixed main has neither WheelKeyService nor UsbPermissionSetup, and v3 does not define wheel-key support; adding global accessibility/ADB state requires a separate product and target review.",
        "test": "SOURCE_TESTS: upstream tests exercise service-list merge/restore; PASS_STATIC: origin/main contains no WheelKeyService or UsbPermissionSetup; NOT_RUN_TARGET: ADB trust, accessibility-list preservation, and wheel-key event mapping on supported heads",
        "related": "No direct STEP4 mapping; separate wheel-key/accessibility feature, not PR-00 USB grant or EX-04 re-enumeration",
        "rollback": "No ADB service-list changes imported; any future wheel support must be opt-in, preserve other services, and be independently reversible.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEW_PENDING_SCOPE_EVIDENCE",
    },
    "559cc81577882bb18009043697948d4ccd566fa1": {
        "disposition": "NEED_EVIDENCE",
        "reason": "This wheel-key merge adds ADB restoration from Activity startup and rewrites the global accessibility service list, with lifecycle and settings UI changes. The fixed main has no wheel service, and v3 does not request this feature or establish the target's ADB/accessibility policy.",
        "test": "SOURCE_TESTS: upstream covers service-list parsing and restore cases; PASS_STATIC: origin/main has no WheelKeyService; NOT_RUN_TARGET: app restart, ADB trust, other accessibility services, and real steering-wheel key behavior",
        "related": "No direct STEP4 mapping; optional wheel-key/ADB feature remains outside PR-00/EX-04 USB permission handling",
        "rollback": "No accessibility/ADB behavior imported; require a separate feature decision and target matrix before any wheel-service change.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEW_PENDING_SCOPE_EVIDENCE",
    },
    "74569fe680a9e6363e0a8c2e3829c48cffd0091a": {
        "disposition": "FORK_SUPERIOR",
        "reason": "This test-only patch adjusts Windows shell invocation and Robolectric AtomicFile rename shadows for upstream UsbPermissionSetup/ADB-report tests; fixed main does not contain those production classes and its existing main-based suites already pass on the Windows host.",
        "test": "PASS_LOCAL: fixed-main baseline :shared:testDebugUnitTest 453 and :common:testDebugUnitTest 136 passed on Windows 11 x64; the upstream UsbPermissionSetup test target is absent from origin/main",
        "related": "PR-00 fixed-main test baseline; no runtime STEP4 mapping",
        "rollback": "No change needed; retain fixed-main test fixtures. Port a focused host workaround only if a corresponding failure reproduces in the fork.",
        "review_state": "PRIMARY_AGENT_FIXED_MAIN_COMPARISON",
    },
    "97a9358f9611567d2291ed63b3b87f6cd33d8557": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This main-branch sync into a review branch carries BYD/DiLink behavior, UsbAutoConfirmService, and broad CarPlay UI/controller changes. The aggregate would reintroduce OEM code and accessibility-based USB authorization that fixed main deliberately omits.",
        "test": "PASS_STATIC: source diff contains BYD/DiLink and UsbAutoConfirmService; fixed main has minSdk 28, BYD quarantine, and standard UsbManager permission; NOT_RUN_PER_COMMIT: branch-specific UI and permission cases",
        "related": "Reject aggregate sync; preserve PR-00 consent, BYD/OEM quarantine, and fixed-main controller/UI ownership",
        "rollback": "No merge imported; retain fixed-main UI and permission flow. Any independently approved change must be separated and reversible.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_AGGREGATE_REJECTED",
    },
    "2e83296d1e5aefeeda86078d60a5706c539712ba": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This main-branch sync into review/pr193 combines BYD/DiLink, UsbAutoConfirmService, and broad CarPlay controller/UI changes, with a DiPlayActivity merge conflict. It is not an atomic STEP4 change and would bypass the fixed-main BYD and consent boundaries.",
        "test": "PASS_STATIC: source diff contains BYD/DiLink, UsbAutoConfirmService, and a conflict-resolved Activity; fixed main keeps per-device UsbManager permission; NOT_RUN_PER_COMMIT: UI and consent regression cases",
        "related": "Reject aggregate sync; preserve PR-00 consent, PR-06 UI ownership, and BYD/OEM quarantine",
        "rollback": "No merge imported; retain fixed-main source. Any approved UI or permission change must be split into a separate revertible change.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_AGGREGATE_REJECTED",
    },
    "3b2c4f29bc0c02aa405910c23227571bb2b9a6b5": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This origin/main sync into review/pr181 includes BYD/DiLink behavior, UsbAutoConfirmService, and broad controller/UI changes, with a CarPlayHostActivity conflict resolution. The branch snapshot is broader than the candidate issue and conflicts with fixed-main OEM and consent policy.",
        "test": "PASS_STATIC: source diff contains BYD/DiLink and UsbAutoConfirmService; fixed main preserves explicit USB permission and BYD removal; NOT_RUN_PER_COMMIT: Activity conflict/UI and accessibility permission cases",
        "related": "Reject aggregate sync; review any UI or permission candidate separately under PR-00/06 and BYD/OEM quarantine",
        "rollback": "No merge imported; retain fixed-main permission, Activity, and OEM behavior. Any accepted change must be isolated and independently reversible.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_AGGREGATE_REJECTED",
    },
    "a8a4a7d9d46ad55783fa92c00c5a0f37ecb8ec3b": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This origin/main sync into review/pr179 includes BYD/DiLink and UsbAutoConfirmService changes plus broad CarPlay controller/UI updates with a CarPlayHostActivity conflict. It is a multi-domain integration snapshot rather than an atomic STEP4 change.",
        "test": "PASS_STATIC: source diff contains BYD/DiLink and UsbAutoConfirmService; fixed main preserves explicit USB permission and BYD removal; NOT_RUN_PER_COMMIT: Activity conflict/UI and accessibility permission cases",
        "related": "Reject aggregate sync; preserve PR-00 permission consent, PR-06 UI ownership, and BYD/OEM quarantine",
        "rollback": "No merge imported; retain fixed-main permission, Activity, and OEM behavior. Any accepted change must be isolated and independently reversible.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_AGGREGATE_REJECTED",
    },
    "358b8cb646b9a2b593c878bf05cce6a6a6e4aaef": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This origin/main sync into review/pr178 includes BYD/DiLink behavior, UsbAutoConfirmService, and broad CarPlay controller/UI changes with a CarPlayHostActivity conflict. The merge violates the no-bulk-merge rule and would reintroduce OEM and auto-permission behavior absent from fixed main.",
        "test": "PASS_STATIC: source diff contains BYD/DiLink, UsbAutoConfirmService, and Activity conflict resolution; fixed main keeps minSdk 28 and standard USB consent; NOT_RUN_PER_COMMIT: separate UI/permission cases",
        "related": "Reject aggregate sync; preserve PR-00 consent, PR-06 UI lifecycle, and BYD/OEM quarantine",
        "rollback": "No merge imported; retain fixed-main code. Any later accepted subsystem change must be split and separately reversible.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_AGGREGATE_REJECTED",
    },
    "adad43e69124d471a1770a4cf0bd54b57e53e8c8": {
        "disposition": "REJECT_UNSAFE",
        "reason": "The accessibility service reads system window content, matches generic DiPlay/CarPlay plus USB/iPhone text, checks the persistent Always allow control, and clicks the USB confirmation. It also broadens the device filter to the Apple VID and couples auto-hide state to the permission service. Fixed main and v3 retain explicit per-device UsbManager consent; this is not an EX-04 retry fix.",
        "test": "PASS_STATIC: patch defines an exported BIND_ACCESSIBILITY_SERVICE with window-content access and auto-clicks Always allow; origin/main uses UsbManager.requestPermission and has no UsbAutoConfirmService; NOT_RUN_BY_DESIGN: no automatic consent behavior is tested or enabled",
        "related": "Reject accessibility-based USB auto-grant; preserve PR-00 per-device consent. HomeScreenMonitor/overlay auto-hide is a separate PR-06 scope and is not imported.",
        "rollback": "No service, settings, or Apple VID-only filter imported; keep UsbManager.requestPermission and the fixed-main device identity checks.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_UNSAFE_PERMISSION_AUTOMATION",
    },
    "dd05f5a9b43a6807ff78013203db140402a57c6f": {
        "disposition": "REJECT_UNSAFE",
        "reason": "The purported overlay fix also changes HomeScreenMonitor, modifies AccessibilityService foreground filtering, and carries the USB auto-confirm service that checks Always allow and clicks system permission. This combines overlay/Surface lifecycle, foreground ownership, ADB timing, and automatic USB consent; fixed main preserves explicit per-device permission and has a different overlay owner.",
        "test": "PASS_STATIC: diff changes CenterMapOverlay, AndroidMediaSink recovery, HomeScreenMonitor, and UsbAutoConfirmService; fixed main has no auto-confirm service and requests USB permission through UsbManager; NOT_RUN_BY_DESIGN: no accessibility auto-grant behavior is tested or enabled",
        "related": "Reject mixed overlay and auto-consent patch; PR-00 USB consent and PR-06 LOCAL overlay lifecycle remain separate; not an EX-04 retry fix",
        "rollback": "No patch imported; retain fixed-main consent and overlay ownership. Any later reproduced frame/keyframe issue must be isolated without AccessibilityService USB auto-confirm.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_UNSAFE_PERMISSION_AND_OVERLAY_MIX",
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
        "disposition": "REJECT_UNSAFE",
        "reason": "The change adds a local-ADB/Accessibility path to auto-enable USB permission confirmation and saves wirelessEnabled=false on iPhone USB attach, silently changing the user's persisted connection choice. Fixed main preserves explicit per-device UsbManager consent and explicit transport settings; the convenience setup does not justify either behavior change.",
        "test": "PASS_STATIC: source enables UsbAutoConfirmService through local ADB and changes the saved wireless preference in onCreate/onNewIntent; origin/main has no auto-confirm service and uses UsbManager.requestPermission; SOURCE_TESTS: upstream transport-switch assertion exists; NOT_RUN_BY_DESIGN: no automatic permission or preference-changing behavior is tested or enabled",
        "related": "Reject USB permission and persisted-transport automation; PR-00 explicit consent, EX-04 attach lifecycle, and WIRELESS-EX connection choice are separate; not an EX-04 retry fix",
        "rollback": "No ADB/accessibility grant or preference switch imported; retain explicit USB consent and the saved transport selection. Any future attach recovery must be opt-in and must not rewrite the wireless preference.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_UNSAFE_PERMISSION_AND_PREFERENCE_SWITCH",
    },
    "8633aae3e0a357622b7b44f0ead12c1da74627aa": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This 12-path change combines local-ADB permission setup and AccessibilityService USB auto-confirm with USB attach transport switching, overlay/mirror behavior, and release-signing changes. Fixed main keeps explicit per-device UsbManager consent and a local release-signing fallback; v3 gates USB lifecycle and release/rollback independently, so this aggregate cannot be taken as a cancellable permission fix.",
        "test": "PASS_STATIC: source diff adds UsbPermissionSetup/UsbAutoConfirmService, rewrites wired attach behavior, changes CenterMapOverlay/AndroidMediaSink, and removes the release debug-signing fallback; PR-45 Debug/Benchmark checks do not cover signed release/update behavior; NOT_RUN_BY_DESIGN: no ADB auto-grant",
        "related": "Reject mixed consent, attach, overlay, and signing changes; PR-00 USB permission, EX-04 attach lifecycle, PR-06 overlay, and PR-08 release-signature gates remain separate",
        "rollback": "No patch imported; preserve per-device USB consent and current signing fallback until the independent PR-08 release/update gate passes.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_UNSAFE_RELEASE_PERMISSION_MIX",
    },
    "949e1eddbc29b159ef97a49b3c00d1047b1b2180": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This merge combines local-ADB permission setup that enables UsbAutoConfirmService with hotspot cleanup and VPN-consent lifecycle changes. The accessibility service auto-clicks the system USB permission flow, while the hotspot/VPN updates change other owners; fixed main retains per-device UsbManager consent and v3 permits only bounded retry/de-duplication.",
        "test": "PASS_STATIC: source adds ADB shell grant flow, AccessibilityService USB auto-confirm, hotspot teardown, and VPN consent state; origin/main uses UsbManager.requestPermission; NOT_RUN_BY_DESIGN: no auto-confirm or ADB grant behavior is tested or enabled",
        "related": "Reject mixed consent/hotspot/VPN merge; PR-00 USB permission, EX-04 attach lifecycle, and WIRELESS-EX hotspot ownership remain separate",
        "rollback": "No ADB, accessibility auto-grant, hotspot cleanup, or VPN consent changes imported; keep fixed-main ownership and explicit USB permission.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_UNSAFE_PERMISSION_LIFECYCLE_MIX",
    },
    "29572a2cba33ede85dd5355a36508af76be1898e": {
        "disposition": "REJECT_UNSAFE",
        "reason": "The patch digest is identical to merge 949e1ed and carries the same ADB/Accessibility USB auto-confirm, hotspot cleanup, and VPN-consent lifecycle changes. Reject both source occurrences together; fixed main preserves explicit per-device consent.",
        "test": "PASS_STATIC: duplicate patch SHA matches 949e1ed and contains the same UsbAutoConfirmService and ADB permission setup; no implementation imported",
        "related": "Duplicate rejected merge row for 949e1ed; PR-00 consent and EX-04 attach lifecycle remain independent",
        "rollback": "No duplicate merge imported; preserve fixed-main USB permission and lifecycle behavior.",
        "review_state": "PRIMARY_AGENT_DUPLICATE_PATCH_REJECTED_UNSAFE",
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
