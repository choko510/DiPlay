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
    "9a1cd52e4e0907aacfc66f9babaa77c3e764ad97": {
        "disposition": "SKIP_OEM",
        "reason": "This change adjusts reconnect behavior for BYD dashboard cluster-map presentation and position controls. Fixed main has no cluster-map presentation surface, and v3 preserves the BYD/OEM removal rather than mapping it to generic CarPlay ViewAreas or local GeckoView Split.",
        "test": "PASS_STATIC: patch changes only DiPlayActivity cluster-map setting/reconnect handling; fixed main has no CarPlayClusterDisplay or ClusterMapPresentation code; NOT_RUN_OEM: BYD cluster output",
        "related": "BYD/OEM cluster-map exclusion; no generic PR-05/06 ViewArea or PR-06 LOCAL mapping",
        "rollback": "No cluster-map reconnect or position setting imported; retain the fixed-main generic display path.",
        "review_state": "PRIMARY_AGENT_PLAN_EXCLUSION_VERIFIED",
    },
    "9edcf527cebd877fba9daa5a0c7bc7afd1efbb23": {
        "disposition": "SKIP_OEM",
        "reason": "This integration commit reconciles BYD DiLink 5.1 cluster marker/layout behavior with a vendor-specific cluster stream. Fixed main has none of the DiLink51Cluster or CarPlayClusterDisplay paths, and this is separate from generic PR-05 ViewAreas and local PR-06 Split.",
        "test": "PASS_STATIC: changed paths include DiLink51ClusterLayout, CarPlayClusterDisplay, and docs/BYD_NAVIGATION.md; fixed main has no corresponding cluster classes; NOT_RUN_OEM: DiLink 5.1 stream",
        "related": "BYD/OEM quarantine; no generic PR-05/06 display mapping",
        "rollback": "No BYD cluster geometry, marker, or stream handling imported; preserve fixed-main cluster removal.",
        "review_state": "PRIMARY_AGENT_PLAN_EXCLUSION_VERIFIED",
    },
    "049e396cffab292b87d0aa8fcacbec36c6384ea7": {
        "disposition": "SKIP_OEM",
        "reason": "This change sends BYD dashboard map pause/navigation mode through local ADB and BYD-specific cluster APIs. It has no generic CarPlay ViewArea or in-app Split behavior, and fixed main intentionally removes the BYD cluster integration.",
        "test": "PASS_STATIC: patch adds LocalAdb, BydClusterMapPause, and BydClusterNaviMode paths; fixed main has none; NOT_RUN_OEM: BYD dashboard map output",
        "related": "BYD/OEM cluster output exclusion; no PR-05 wire or PR-06 LOCAL mapping",
        "rollback": "No ADB cluster command or BYD navigation setting imported; retain the fixed-main cluster removal.",
        "review_state": "PRIMARY_AGENT_PLAN_EXCLUSION_VERIFIED",
    },
    "c49cfba4bfe3506c17789bc0090695c3c18d7861": {
        "disposition": "SKIP_OEM",
        "reason": "This feature reads the BYD car battery through autoservice/ADB and reports it to the iPhone as vehicle status. It is BYD-specific telemetry, outside the generic CarPlay path, and fixed main removes the BYD ADB integration.",
        "test": "PASS_STATIC: patch adds BydBattery/BydAdbShell and changes BYD vehicle status clients; fixed main has no BydBattery path; NOT_RUN_OEM: supported BYD telemetry",
        "related": "BYD/OEM vehicle telemetry exclusion; no generic STEP4 PR mapping",
        "rollback": "No BYD vehicle-status or ADB telemetry path imported; preserve fixed-main transport behavior.",
        "review_state": "PRIMARY_AGENT_PLAN_EXCLUSION_VERIFIED",
    },
    "640001ff5002e5a4775ad89c6b19384693813aa2": {
        "disposition": "SKIP_OEM",
        "reason": "The wheel-speed source reads BYD autoservice identifiers through ADB and sends its samples to the iPhone for tunnel dead reckoning. The added generic NMEA helpers are only wired to this BYD-specific source, which v3 excludes from the supported generic path.",
        "test": "PASS_STATIC: BydWheelSpeed uses BYD autoservice IDs and BydAdbShell; fixed main has no BydWheelSpeed or VehicleSpeedNmea path; NOT_RUN_OEM: BYD wheel-speed reporting",
        "related": "BYD/OEM wheel-speed telemetry exclusion; no generic navigation or PR-04 microphone mapping",
        "rollback": "No BYD ADB source or PASCD vehicle-speed path imported; retain fixed-main location reporting.",
        "review_state": "PRIMARY_AGENT_PLAN_EXCLUSION_VERIFIED",
    },
    "fd31793338d4e417c1f605fb7b7d5346580d85cb": {
        "disposition": "SKIP_OEM",
        "reason": "This fix clears song state for the BYD instrument-cluster music card. The changed BydClusterSong consumer and tests are OEM cluster output, not generic CarPlay MEDIA or the fork's MEDIA DSP path.",
        "test": "PASS_STATIC: patch changes only BydClusterSong state and its test; fixed main has no BYD cluster-song path; NOT_RUN_OEM: dashboard music-card output",
        "related": "BYD/OEM dashboard media output exclusion; preserve generic CarPlay media keys and MEDIA DSP",
        "rollback": "No BYD cluster-song state or output imported; keep the fixed-main media path.",
        "review_state": "PRIMARY_AGENT_PLAN_EXCLUSION_VERIFIED",
    },
    "931fd70e80f7214939f4417a06fbd852dd276908": {
        "disposition": "SKIP_OEM",
        "reason": "This patch gives the BYD HUD/cluster guidance overlay a longer route-expiry window than its windshield HUD. The changed BydHudRouteState and BydNavigationOutputs are BYD-only consumers that fixed main removed.",
        "test": "PASS_STATIC: patch changes only BydHudRouteState/BydNavigationOutputs; fixed main has neither path; upstream tests cover BYD overlay timing only",
        "related": "BYD/OEM HUD and cluster overlay exclusion; no generic PR-05/06 display mapping",
        "rollback": "No BYD route overlay state or timer policy imported; retain fixed-main navigation/display behavior.",
        "review_state": "PRIMARY_AGENT_PLAN_EXCLUSION_VERIFIED",
    },
    "06cfd49acb473fca28add02bfc9f682f02a5f3ae": {
        "disposition": "SKIP_OEM",
        "reason": "This change preserves artist metadata for the BYD dashboard music card's incremental title updates. BydClusterSong and its tests are not part of the fixed-main MEDIA or DSP path.",
        "test": "PASS_STATIC: patch changes BydClusterSong and BydClusterSongTest only; fixed main has no BYD cluster-song output",
        "related": "BYD/OEM dashboard media output exclusion; preserve generic CarPlay media and MEDIA DSP",
        "rollback": "No BYD cluster-song metadata handling imported; keep fixed-main MEDIA behavior.",
        "review_state": "PRIMARY_AGENT_PLAN_EXCLUSION_VERIFIED",
    },
    "4083993ba01132e7a2eae923311e49a6f9bd932b": {
        "disposition": "SKIP_OEM",
        "reason": "This review-integration commit applies the same incremental artist-preservation change to BydClusterSong for the BYD dashboard card. It has no generic media or DSP mapping in fixed main.",
        "test": "PASS_STATIC: diff is limited to BydClusterSong and its BYD-specific test; fixed main lacks the cluster-song path",
        "related": "BYD/OEM dashboard media output exclusion; no PR-04/DSP adaptation",
        "rollback": "No BYD cluster-song update imported; retain generic MEDIA/DSP behavior.",
        "review_state": "PRIMARY_AGENT_PLAN_EXCLUSION_VERIFIED",
    },
    "a13af569e90869ae007131c3b07577c5d73c6622": {
        "disposition": "SKIP_OEM",
        "reason": "This follow-up parameterizes the same BydHudRouteState expiry for a BYD cluster overlay while preserving the windshield HUD timeout. The class and output are absent from fixed main.",
        "test": "PASS_STATIC: patch only parameterizes BydHudRouteState timing; fixed main has no BYD HUD/cluster route consumer",
        "related": "BYD/OEM HUD expiry; no generic PR-05/06 display mapping",
        "rollback": "No BYD overlay timeout change imported; preserve fixed-main display behavior.",
        "review_state": "PRIMARY_AGENT_PLAN_EXCLUSION_VERIFIED",
    },
    "f6b54ea48ed26e8dac054fbcf7733525494147ab": {
        "disposition": "SKIP_OEM",
        "reason": "This commit repeats the BYD dashboard song-card artist-preservation change in BydClusterSong. The feature is outside the fixed-main generic CarPlay media path.",
        "test": "PASS_STATIC: patch changes BydClusterSong and its BYD-only test; no fixed-main dashboard song consumer exists",
        "related": "BYD/OEM dashboard media output exclusion; preserve generic MEDIA/DSP path",
        "rollback": "No BYD song-card state imported; keep fixed-main MEDIA behavior.",
        "review_state": "PRIMARY_AGENT_PLAN_EXCLUSION_VERIFIED",
    },
    "57466432d16f3e53fa0c3986a29a096425a3e2cb": {
        "disposition": "SKIP_OEM",
        "reason": "This patch changes failure handling for the BYD instrument-cluster call overlay, where individual vendor writes can fail while other BYD fields still work. It does not implement generic microphone or call routing for the fork.",
        "test": "PASS_STATIC: changed path is BydCarPlayCall and the documentation identifies BYD autoservice output; fixed main has no such class; NOT_RUN_OEM: BYD call overlay",
        "related": "BYD/OEM call-control exclusion; PR-04 remains generic microphone-request routing only",
        "rollback": "No BYD ADB call-overlay write policy imported; preserve fixed-main call/microphone routing.",
        "review_state": "PRIMARY_AGENT_PLAN_EXCLUSION_VERIFIED",
    },
    "8f8fe0c4c8106d1e45a30cf87c96c50f85133dea": {
        "disposition": "SKIP_OEM",
        "reason": "This change avoids an in-call audio-status write that switches the amplifier on a BYD DiLink 3 head unit. The affected BydCarPlayCall ADB path is OEM-specific and absent from fixed main.",
        "test": "PASS_STATIC: diff edits the BYD call-control tool and guide; fixed main has no BydCarPlayCall; NOT_RUN_OEM: supported BYD call/audio behavior",
        "related": "BYD/OEM call/audio routing exclusion; no PR-04 microphone path mapping",
        "rollback": "No BYD call-status write or audio routing code imported; preserve fixed-main audio and call behavior.",
        "review_state": "PRIMARY_AGENT_PLAN_EXCLUSION_VERIFIED",
    },
    "48db5750c324b22d76257e3c77bc09dea3d2a55d": {
        "disposition": "SKIP_OEM",
        "reason": "This test-timing adjustment is scoped to the BYD dashboard call watcher and its lifecycle test. Fixed main has no BYD call overlay or watcher to adjust.",
        "test": "PASS_STATIC: patch changes BydCarPlayCall and its BYD lifecycle test only; no fixed-main call watcher exists",
        "related": "BYD/OEM call-control exclusion; no generic PR-04 microphone or call mapping",
        "rollback": "No BYD call watcher change imported; retain fixed-main lifecycle behavior.",
        "review_state": "PRIMARY_AGENT_PLAN_EXCLUSION_VERIFIED",
    },
    "d983d0842a59c20781157cc8c93f4eb01bf8f490": {
        "disposition": "SKIP_OEM",
        "reason": "This change avoids starting a retry loop for the optional BYD SOME/IP HUD when its gateway is absent. The service and retry are OEM-only and are not in fixed main.",
        "test": "PASS_STATIC: patch adds a package-manager gate to BydHudBridge; fixed main has no BYD SOME/IP service; NOT_RUN_OEM: BYD gateway behavior",
        "related": "BYD/OEM SOME/IP HUD exclusion; no generic PR-05/06 display mapping",
        "rollback": "No BYD SOME/IP bridge or retry loop imported; preserve the fixed-main OEM removal.",
        "review_state": "PRIMARY_AGENT_PLAN_EXCLUSION_VERIFIED",
    },
    "98d43944c5c28e2a5a484c49070dcdd97722b993": {
        "disposition": "SKIP_OEM",
        "reason": "This merge repeats the BYD SOME/IP HUD gateway check and retry suppression. The whole path is an OEM cluster integration absent from fixed main.",
        "test": "PASS_STATIC: merge diff only adds the BydHudBridge package check and test; fixed main has no such bridge",
        "related": "BYD/OEM SOME/IP HUD exclusion; no generic STEP4 mapping",
        "rollback": "No BYD SOME/IP HUD code imported; retain fixed-main feature removal.",
        "review_state": "PRIMARY_AGENT_PLAN_EXCLUSION_VERIFIED",
    },
    "5cb6887d52a1e21c5aec718a50bf9a5ef965aa92": {
        "disposition": "SKIP_OEM",
        "reason": "This patch verifies bounded lyrics and firmware support for BydStandaloneHudOutput on DiLink 4. The output is an OEM dashboard feature removed from fixed main, not generic CarPlay media or MEDIA DSP.",
        "test": "PASS_STATIC: patch changes BydStandaloneHudOutput/BydStandaloneSession and DILINK4_CLUSTER docs; fixed main has no such output path",
        "related": "BYD/OEM standalone HUD output exclusion; preserve generic MEDIA and DSP policy",
        "rollback": "No BYD standalone HUD or lyrics feature imported; retain the fixed-main media path.",
        "review_state": "PRIMARY_AGENT_PLAN_EXCLUSION_VERIFIED",
    },
    "5ac9bc59e88158bb1b6cf81410ac6c114a24eace": {
        "disposition": "SKIP_OEM",
        "reason": "This change sends DiLink 3 cluster text fields and creates a BYD projection display through local ADB. The source explicitly requires a BYD vehicle/firmware path, excluded by v3 and absent from fixed main.",
        "test": "PASS_STATIC: patch adds BydAmapAdapter/BydClusterBridge and DiLink projection commands; fixed main has none; NOT_RUN_OEM: DiLink 3 cluster rendering",
        "related": "BYD/OEM projection and vehicle-status exclusion; no PR-05 wire or PR-06 LOCAL mapping",
        "rollback": "No ADB display command, cluster text, or projection surface imported; preserve fixed-main BYD removal.",
        "review_state": "PRIMARY_AGENT_PLAN_EXCLUSION_VERIFIED",
    },
    "aa818a024766f3aff636a9043a4721d3e4232343": {
        "disposition": "SKIP_OEM",
        "reason": "This recovery change manages interrupted DiLink 3 cluster modes and compensates failed BYD projection setup. It uses the vendor cluster controls and is unrelated to the fork's local GeckoView Split or generic display lifecycle.",
        "test": "PASS_STATIC: patch changes BydClusterBridge/DiLink3ClusterModeSession recovery and tests; fixed main has no BYD cluster-mode path; NOT_RUN_OEM: vehicle recovery",
        "related": "BYD/OEM DiLink 3 cluster-mode exclusion; PR-05/06 local display remains separate",
        "rollback": "No BYD cluster-mode journal or recovery commands imported; retain fixed-main display behavior.",
        "review_state": "PRIMARY_AGENT_PLAN_EXCLUSION_VERIFIED",
    },
    "dd92dc4a3ccc0adf138823493dbd4a48708f84d8": {
        "disposition": "SKIP_OEM",
        "reason": "The patch documents vehicle retesting for DiLink 3 cluster projection and adjusts the BYD mode recovery path. It has no generic CarPlay or STEP4 mapping in fixed main.",
        "test": "PASS_STATIC: changed paths are BYD cluster-mode documentation/code/tests; fixed main contains no DiLink 3 projection path",
        "related": "BYD/OEM projection and mode-recovery exclusion; no PR-05/06 generic display mapping",
        "rollback": "No DiLink 3 recovery adjustment imported; preserve fixed-main OEM removal.",
        "review_state": "PRIMARY_AGENT_PLAN_EXCLUSION_VERIFIED",
    },
    "3b6443fb79d98f3c49640ea9ec0c402007ee52d1": {
        "disposition": "SKIP_OEM",
        "reason": "This journal fix retains recovery evidence for DiLink 3 BYD projection settings when SharedPreferences commits fail. The state and output are OEM-specific and absent from fixed main.",
        "test": "PASS_STATIC: patch changes BydDiLink3ClusterOutput/DiLink3ClusterRecoveryJournal only; fixed main has no matching classes",
        "related": "BYD/OEM cluster recovery exclusion; no generic PR-05/06 mapping",
        "rollback": "No BYD recovery journal or projection control imported; keep the fixed-main split/display path.",
        "review_state": "PRIMARY_AGENT_PLAN_EXCLUSION_VERIFIED",
    },
    "c37dba87bf4ea95e672ce62a1b28b6a6c4e50eea": {
        "disposition": "SKIP_OEM",
        "reason": "This patch validates BYD DiLink 3 projection entry stages and compensates partial vendor-mode changes. The vehicle-specific ADB projection path is excluded and has no generic STEP4 mapping.",
        "test": "PASS_STATIC: source diff is BydAmapAdapter/BydDiLink3ClusterOutput/DiLink3ClusterModeSession and tests; fixed main has no BYD projection implementation",
        "related": "BYD/OEM cluster-mode exclusion; keep PR-05/06 scoped to the fork's generic local display behavior",
        "rollback": "No BYD projection state machine or vendor commands imported; preserve fixed-main cluster removal.",
        "review_state": "PRIMARY_AGENT_PLAN_EXCLUSION_VERIFIED",
    },
    "43cac1d9aecd212781b659a9f6b7427efdf01688": {
        "disposition": "SKIP_OEM",
        "reason": "This documentation-only patch records battery/range/charging validation for a BYD head unit and its iPhone vehicle-status output. Fixed main has no BYD vehicle-status path, and the report does not establish a generic STEP4 behavior.",
        "test": "PASS_STATIC: patch changes docs/BYD_NAVIGATION.md only; fixed main has no BYD vehicle-status feature",
        "related": "BYD/OEM vehicle telemetry documentation exclusion; no generic STEP4 PR mapping",
        "rollback": "No BYD validation claim or vehicle-status documentation imported; preserve the fixed-main documentation surface.",
        "review_state": "PRIMARY_AGENT_PLAN_EXCLUSION_VERIFIED",
    },
    "f8727d18e410a509b83d33a4f6aa6e5234b86b77": {
        "disposition": "SKIP_OEM",
        "reason": "This patch adjusts only the upstream BydVehicleDataSettingsTest fixture to authorize a BYD vehicle-data validation path. Fixed main has no corresponding BYD settings or product path, and the change adds no generic STEP4 test behavior.",
        "test": "PASS_STATIC: changed path is common/src/test/.../BydVehicleDataSettingsTest.kt only; fixed main contains no BYD vehicle-data settings feature",
        "related": "BYD/OEM test fixture exclusion; no generic STEP4 mapping",
        "rollback": "No BYD test fixture or product surface imported; retain fixed-main test scope.",
        "review_state": "PRIMARY_AGENT_PLAN_EXCLUSION_VERIFIED",
    },
    "99746af030fb7681d21b1d17fd0e208f1cbed5ab": {
        "disposition": "SKIP_OEM",
        "reason": "This documentation records DiLink 3 windshield-HUD and cluster projection results for a specific BYD vehicle. Fixed main has no BYD HUD/cluster output, and the vehicle report does not establish generic display acceptance.",
        "test": "PASS_STATIC: patch changes docs/BYD_NAVIGATION.md only; fixed main has no BYD HUD/cluster output",
        "related": "BYD/OEM HUD/cluster documentation exclusion; no PR-05/06 generic display mapping",
        "rollback": "No BYD vehicle-validation claims imported; preserve fixed-main documentation.",
        "review_state": "PRIMARY_AGENT_PLAN_EXCLUSION_VERIFIED",
    },
    "dbf7d264f64c283cf653d9ffe237357f1d7ede26": {
        "disposition": "SKIP_OEM",
        "reason": "This follow-up reads a BYD DiLink 5 battery protocol property to feed the upstream BydBattery adapter. The vehicle property and output are OEM-specific and absent from fixed main.",
        "test": "PASS_STATIC: patch changes BydBattery and its test only; fixed main has no BydBattery path; NOT_RUN_OEM: Tang DiLink 5 battery status",
        "related": "BYD/OEM battery telemetry exclusion; no generic CarPlay vehicle-status mapping",
        "rollback": "No BYD property lookup or battery telemetry imported; preserve fixed-main transport behavior.",
        "review_state": "PRIMARY_AGENT_PLAN_EXCLUSION_VERIFIED",
    },
    "9916b2127545385e9171b3a4e52a8859a5ac6284": {
        "disposition": "SKIP_OEM",
        "reason": "This merge integrates the BYD DiLink 5 battery-property fallback into BydBattery. It is an OEM vehicle-status output absent from fixed main, not a generic iAP2 or STEP4 implementation.",
        "test": "PASS_STATIC: merge changes BydBattery and its BYD protocol test only; fixed main has no such source or consumer",
        "related": "BYD/OEM battery telemetry exclusion; no generic STEP4 mapping",
        "rollback": "No BYD battery fallback or vehicle-status output imported; keep fixed-main behavior.",
        "review_state": "PRIMARY_AGENT_PLAN_EXCLUSION_VERIFIED",
    },
    "12d33a8f9c6ccbf5b0c3b1bb06c107e8c143466a": {
        "disposition": "SKIP_OEM",
        "reason": "This documentation describes a guarded BYD dashboard-call ADB output and its release-test requirements. The call overlay and vehicle controls are excluded from fixed main and do not map to PR-04 microphone request handling.",
        "test": "PASS_STATIC: patch changes docs/BYD_NAVIGATION.md only; fixed main has no BYD dashboard-call controls",
        "related": "BYD/OEM call-control documentation exclusion; PR-04 remains microphone routing only",
        "rollback": "No BYD call-control claim or documentation imported; preserve fixed-main call and audio paths.",
        "review_state": "PRIMARY_AGENT_PLAN_EXCLUSION_VERIFIED",
    },
    "0e54b3ea9705bcdfe603beaa2c8093c33378ef71": {
        "disposition": "SKIP_OEM",
        "reason": "This change formats ETA_TEXT for the DiLink 3 cluster adapter and a BYD instrument CAN path. It is vendor-specific cluster guidance, not the fork's generic PR-05 ViewArea or PR-04 microphone path.",
        "test": "PASS_STATIC: patch changes BydAmapAdapter and its BYD test only; fixed main has no BYD cluster adapter; NOT_RUN_OEM: DiLink 3 ETA rendering",
        "related": "BYD/OEM cluster-guidance exclusion; no generic PR-05/06 display mapping",
        "rollback": "No BYD ETA text or instrument CAN output imported; preserve fixed-main CarPlay display behavior.",
        "review_state": "PRIMARY_AGENT_PLAN_EXCLUSION_VERIFIED",
    },
    "6e21cf1505cd7b4f1d1757b48c4183989607705d": {
        "disposition": "SKIP_OEM",
        "reason": "This merge applies the DiLink 3 ETA_TEXT formatting to the upstream BYD AMap cluster adapter. The adapter and instrument output are excluded from fixed main.",
        "test": "PASS_STATIC: merge changes BydAmapAdapter and its BYD test; fixed main has no such classes",
        "related": "BYD/OEM cluster-guidance exclusion; no generic PR-05/06 display mapping",
        "rollback": "No BYD AMap adapter or ETA output imported; retain fixed-main display behavior.",
        "review_state": "PRIMARY_AGENT_PLAN_EXCLUSION_VERIFIED",
    },
    "33b9c798936f979a2f9bec68d6cdc05eafe3ce2b": {
        "disposition": "SKIP_OEM",
        "reason": "This patch parses BYD DiLink ambient-light worker output after a firmware hotfix banner. The client/worker and lighting controls are OEM-only and absent from fixed main.",
        "test": "PASS_STATIC: changed paths are BydAmbientLightClient/Worker and policy tests; fixed main has no BYD lighting bridge; NOT_RUN_OEM: DiLink 5 firmware",
        "related": "BYD/OEM ambient-light output exclusion; no generic audio/DSP mapping",
        "rollback": "No BYD ambient-light worker or protocol handling imported; preserve generic MEDIA DSP and fixed-main lighting scope.",
        "review_state": "PRIMARY_AGENT_PLAN_EXCLUSION_VERIFIED",
    },
    "b78c5b04c415b9cebbf64f0139a5d13f5e9a6c2f": {
        "disposition": "SKIP_OEM",
        "reason": "This merge applies the DiLink hotfix-banner parsing to BydAmbientLightClient/Worker. The entire bridge is BYD-specific and absent from fixed main.",
        "test": "PASS_STATIC: merge changes BydAmbientLightClient/Worker and policy tests only; no fixed-main consumer exists",
        "related": "BYD/OEM ambient-light output exclusion; no generic PR-04/DSP mapping",
        "rollback": "No BYD ambient-light parsing or worker code imported; retain the fixed-main feature removal.",
        "review_state": "PRIMARY_AGENT_PLAN_EXCLUSION_VERIFIED",
    },
    "55c4649d2c7bf32767e518e4a6ab0e47d6c2a453": {
        "disposition": "SKIP_OEM",
        "reason": "This retry change restarts BydCarMusicRhythm after helper failures while CarPlay music plays. The ambient-light/music-rhythm feature is a BYD vehicle integration excluded by v3 and absent from fixed main.",
        "test": "PASS_STATIC: patch changes BydCarMusicRhythm only; fixed main has no BYD rhythm bridge; NOT_RUN_OEM: supported BYD lighting behavior",
        "related": "BYD/OEM ambient-rhythm exclusion; keep MEDIA DSP and music playback separate",
        "rollback": "No BYD music-rhythm retry or lighting command imported; preserve fixed-main media behavior.",
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
    "c752ed5a6e7263748efc4a8bea286e4a857bed31": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This documentation-only patch claims an upstream parked-video player with seeking and app-video playback. Fixed main has no parked-video entry point or BYD_NAVIGATION guide, and PR-07 remains NO_GO pending codec/surface and target evidence; importing the claim would misstate fork behavior.",
        "test": "PASS_STATIC: patch changes docs/BYD_NAVIGATION.md only and fixed main has no parked-video player entry point; NOT_RUN_TARGET: parked playback, seek, codec/surface, and A/V sync",
        "related": "EX-08 unsupported video documentation; PR-07 remains gated on codec/surface and target evidence",
        "rollback": "No parked-video documentation or product claim imported; keep PR-07 NO_GO until target behavior is demonstrated and accepted.",
        "review_state": "PRIMARY_AGENT_FIXED_MAIN_AND_PLAN_REVIEW",
    },
    "dde983c0b66672d10a018fbf9f8f799bbad49f7a": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This link-only release-note patch advertises a status-bar/reversing-camera session-resize fix that is explicitly pending in-car acceptance. Fixed main has no upstream RELEASE-NOTES-NEXT file, and PR-06 LOCAL evidence does not establish this upstream behavior on the fork's target head units.",
        "test": "PASS_STATIC: patch only links an entry in docs/RELEASE-NOTES-NEXT.md; NOT_RUN_TARGET: reversing-camera/status-bar transition and same-session A/V behavior",
        "related": "EX-08 release-document scope; PR-06 LOCAL layout and device lifecycle gates remain separate",
        "rollback": "No upstream release link or behavior claim imported; retain fixed-main display documentation until the target lifecycle is verified.",
        "review_state": "PRIMARY_AGENT_RELEASE_DOCS_FIXED_MAIN_REJECT",
    },
    "b3618ae543f94133d767ceb0f51c57b341165b3b": {
        "disposition": "SKIP_OEM",
        "reason": "The release-note link documents a DiLink 4 dashboard cluster-map launch fix and marks vehicle acceptance pending. V3 excludes the BYD cluster path; fixed main has no corresponding cluster projection or release-note file.",
        "test": "PASS_STATIC: patch changes only a docs/RELEASE-NOTES-NEXT.md link for a DiLink 4 cluster-map feature; fixed main has no BYD cluster path",
        "related": "BYD/OEM cluster-map exclusion; no PR-05 wire or PR-06 LOCAL mapping",
        "rollback": "No BYD cluster-map release claim imported; preserve the fixed-main local split and generic display behavior.",
        "review_state": "PRIMARY_AGENT_PLAN_EXCLUSION_VERIFIED",
    },
    "5b392c0cd44ab531d6009c81e95c548c0b7b8494": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This release-note link describes ADB-assisted automatic hotspot startup diagnostics on DiLink 3. Fixed main has no matching release-note entry or validated ADB startup behavior, and hotspot routing is outside the wired evidence already accepted in STEP4.",
        "test": "PASS_STATIC: patch only links docs/RELEASE-NOTES-NEXT.md; NOT_RUN_TARGET: DiLink 3 hotspot command inventory, ADB authorization, and route behavior",
        "related": "EX-08 release-document scope; WIRELESS-EX hotspot/ADB behavior remains target-gated",
        "rollback": "No upstream hotspot diagnostic claim imported; retain fixed-main permission and hotspot behavior.",
        "review_state": "PRIMARY_AGENT_RELEASE_DOCS_FIXED_MAIN_REJECT",
    },
    "9cf1bc800128e463586afd477133f0daf61b0127": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This release-note link claims a Bluetooth permission compatibility workaround for Xiaomi/MIUI and says the old permission is now requested on every Android version, with acceptance still pending. Fixed main has no matching release-note file; importing the claim would overstate unverified wireless/permission behavior.",
        "test": "PASS_STATIC: patch only links docs/RELEASE-NOTES-NEXT.md; NOT_RUN_TARGET: Android 12 MIUI permission prompt and Bluetooth/RFCOMM connection",
        "related": "EX-08 release-document scope; WIRELESS-EX Bluetooth permission compatibility remains target-gated",
        "rollback": "No permission or compatibility claim imported; preserve fixed-main consent behavior until the supported device path is verified.",
        "review_state": "PRIMARY_AGENT_RELEASE_DOCS_FIXED_MAIN_REJECT",
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
        "disposition": "REJECT_UNSAFE",
        "reason": "The USB-wait diagnostic logs every Android-listed USB device as VID:PID and /dev/bus/usb node path, including unrelated devices. This adds a fingerprintable device inventory outside EX-01's aggregate WIRED_PATH_V1 schema; reject the patch as supplied.",
        "test": "SOURCE_DIFF: reads UsbManager.deviceList and formats VID:PID/node paths; SOURCE_TESTS: the sample USB list is emitted unchanged by DiagnosticRedactor; NOT_RUN_FORK: approved field allowlist, retention, and supported-head-unit inventory review",
        "related": "Reject unredacted device inventory; retain EX-01 aggregate diagnostics and its privacy schema",
        "rollback": "No device-list diagnostic imported; keep aggregate connection status. Any future USB diagnostic must use an approved, redacted field set and a separate rollback.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_UNREDACTED_USB_INVENTORY",
    },
    "07a737f49abcc9ac5b025e4490c4c0b7650b4b9e": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This PR830 merge applies the same USB-wait inventory logging: every Android-listed device is recorded as VID:PID and a /dev/bus/usb node path, including unrelated devices. Its test confirms these values pass through DiagnosticRedactor unchanged, which exceeds EX-01's aggregate WIRED_PATH_V1 schema.",
        "test": "SOURCE_DIFF: same controller/USB-host/test paths as the underlying inventory commit; SOURCE_TESTS: the sample USB list is emitted unchanged by DiagnosticRedactor; NOT_RUN_FORK: approved field allowlist, retention, and supported-head-unit inventory review",
        "related": "Reject USB inventory logging; preserve EX-01 aggregate diagnostics and evaluate any redacted field set separately",
        "rollback": "No device-list diagnostic imported; keep aggregate connection status and privacy rules. Any later detail must be allowlisted, redacted, tested, and independently reversible.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_UNREDACTED_USB_INVENTORY_MERGE",
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
    "1004fb9e5542acb6c94a168eff4ff1158c490ec7": {
        "disposition": "REJECT_UNSAFE",
        "reason": "These release notes bundle a USB-driver detach workaround, an ADB VPN authorization path, Bluetooth RFCOMM handling, and an audio decoder wait, while explicitly leaving in-car acceptance pending. The ADB permission path conflicts with explicit-consent policy, and the other claims have separate gates; the whole release-note bundle is not valid for fixed main.",
        "test": "PASS_STATIC: patch changes docs/RELEASE-NOTES-NEXT.md only; fixed main has no matching release-note entry; NOT_RUN_TARGET: USB, Bluetooth, audio, and VPN behavior acceptance",
        "related": "Reject unsupported release claims; PR-00 consent, PR-01/EX-04 USB, WIRELESS-EX Bluetooth, and PR-04/AUDIO gates remain separate",
        "rollback": "No upstream release text or ADB VPN permission claim imported; document only individually verified fixed-main behavior.",
        "review_state": "PRIMARY_AGENT_RELEASE_DOCS_FIXED_MAIN_REJECT",
    },
    "0ad6773b0425486058b1493857286a50123814c7": {
        "disposition": "REJECT_UNSAFE",
        "reason": "The release-note patch advertises wheel-key restoration, hotspot-name restrictions, experimental USB navigation audio, icon resizing, and BYD call keys; these are mixed upstream changes with independent transport, audio, UI, and OEM gates. Fixed main has no matching next-release notes, so the bundle would overstate fork behavior.",
        "test": "PASS_STATIC: patch changes docs/RELEASE-NOTES-NEXT.md only; fixed main has no matching release-note entry; BYD call keys are excluded and other features need their own tests",
        "related": "Reject mixed release claims; WIRELESS-EX, AUDIO-EX, BYD/OEM, and generic UI candidates remain separate",
        "rollback": "No upstream release text imported; maintain fork-specific documentation and keep each feature behind its own acceptance gate.",
        "review_state": "PRIMARY_AGENT_RELEASE_DOCS_FIXED_MAIN_REJECT",
    },
    "ea06f10f7ccdb19b85f832bfb31c5ca644e48859": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This documentation-only commit links the upstream release-note claims to PR #622, but the referenced changes are not present as a verified fixed-main release. Importing the link would imply that upstream PR behavior is included in this fork.",
        "test": "PASS_STATIC: patch only changes links in docs/RELEASE-NOTES-NEXT.md; fixed main has no such release-note file or PR #622 feature claim",
        "related": "EX-08 release-document scope; assess runtime constituents under their own STEP4 gates",
        "rollback": "No upstream PR links or release claims imported; keep the fork release documentation tied to its verified commits.",
        "review_state": "PRIMARY_AGENT_RELEASE_DOCS_FIXED_MAIN_REJECT",
    },
    "a8249fbd2df8b0c6bdc8d5d6fa40592c978e0fb8": {
        "disposition": "REJECT_UNSAFE",
        "reason": "The release-note bundle claims a wheel-key retry, an accepted USBMUX timeout recovery, and playback-overrun diagnostics. Those paths have separate target/privacy gates, and the text marks the wired fixes as pending in-car acceptance; fixed main has no corresponding release entry.",
        "test": "PASS_STATIC: patch changes docs/RELEASE-NOTES-NEXT.md only and labels wired acceptance pending; NOT_RUN_TARGET: USBMUX late completion, key-service retry, and A/V drift",
        "related": "Reject unverified release claims; EX-01/02/03, PR-01, WIRELESS-EX, and DSP A/V gates are independent",
        "rollback": "No release text imported; retain the fixed-main USBMUX owner and do not state unvalidated recovery or audio guarantees.",
        "review_state": "PRIMARY_AGENT_RELEASE_DOCS_FIXED_MAIN_REJECT",
    },
    "d09ea24fe42344e25fe1fe627ebf1f51641ef171": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This link-only release-note commit points at upstream PR #625 for wheel-key and USBMUX recovery claims that are not validated as fixed-main release behavior. It changes no runtime code and is outside the fork's verified release record.",
        "test": "PASS_STATIC: patch only links docs/RELEASE-NOTES-NEXT.md to PR #625; no product behavior is tested by the documentation change",
        "related": "EX-08 release-document scope; leave USBMUX/WIRELESS/AUDIO candidates to their individual gates",
        "rollback": "No upstream PR link imported; keep the release notes aligned to fixed-main verified behavior.",
        "review_state": "PRIMARY_AGENT_RELEASE_DOCS_FIXED_MAIN_REJECT",
    },
    "dde983c0b66672d10a018fbf8f799bbad49f7a": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This link-only release-note patch advertises a status-bar/reversing-camera session-resize fix that is explicitly pending in-car acceptance. Fixed main has no upstream RELEASE-NOTES-NEXT file, and PR-06 LOCAL evidence does not establish this behavior on target head units.",
        "test": "PASS_STATIC: patch only links an entry in docs/RELEASE-NOTES-NEXT.md; NOT_RUN_TARGET: reversing-camera/status-bar transition and same-session A/V behavior",
        "related": "EX-08 release-document scope; PR-06 LOCAL layout and device lifecycle gates remain separate",
        "rollback": "No upstream release link or behavior claim imported; retain fixed-main display documentation until the target lifecycle is verified.",
        "review_state": "PRIMARY_AGENT_RELEASE_DOCS_FIXED_MAIN_REJECT",
    },
    "b3618ae543f94133d767ceb0f51c57b341165b3b": {
        "disposition": "SKIP_OEM",
        "reason": "The release-note link documents a DiLink 4 dashboard cluster-map launch fix and marks vehicle acceptance pending. V3 excludes the BYD cluster path; fixed main has no corresponding cluster projection or release-note file.",
        "test": "PASS_STATIC: patch changes only a docs/RELEASE-NOTES-NEXT.md link for a DiLink 4 cluster-map feature; fixed main has no BYD cluster path",
        "related": "BYD/OEM cluster-map exclusion; no PR-05 wire or PR-06 LOCAL mapping",
        "rollback": "No BYD cluster-map release claim imported; preserve the fixed-main local split and generic display behavior.",
        "review_state": "PRIMARY_AGENT_PLAN_EXCLUSION_VERIFIED",
    },
    "5b392c0cd44ab531d6009c81e95c548c0b7b8494": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This release-note link describes ADB-assisted automatic hotspot startup diagnostics on DiLink 3. Fixed main has no matching release-note entry or validated ADB startup behavior, and hotspot routing is outside the wired evidence accepted in STEP4.",
        "test": "PASS_STATIC: patch only links docs/RELEASE-NOTES-NEXT.md; NOT_RUN_TARGET: DiLink 3 hotspot command inventory, ADB authorization, and route behavior",
        "related": "EX-08 release-document scope; WIRELESS-EX hotspot/ADB behavior remains target-gated",
        "rollback": "No upstream hotspot diagnostic claim imported; retain fixed-main permission and hotspot behavior.",
        "review_state": "PRIMARY_AGENT_RELEASE_DOCS_FIXED_MAIN_REJECT",
    },
    "9cf1bc800128e463586afd477133f0daf61b0127": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This release-note link claims a Bluetooth permission workaround for Xiaomi/MIUI and says the old permission is requested on every Android version, with acceptance still pending. Fixed main has no matching release-note file; importing the claim would overstate unverified wireless/permission behavior.",
        "test": "PASS_STATIC: patch only links docs/RELEASE-NOTES-NEXT.md; NOT_RUN_TARGET: Android 12 MIUI permission prompt and Bluetooth/RFCOMM connection",
        "related": "EX-08 release-document scope; WIRELESS-EX Bluetooth permission compatibility remains target-gated",
        "rollback": "No permission or compatibility claim imported; preserve fixed-main consent behavior until the supported device path is verified.",
        "review_state": "PRIMARY_AGENT_RELEASE_DOCS_FIXED_MAIN_REJECT",
    },
    "9dba92661a2dba3f1e823552f5b44e554c03e77d": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This release-note entry reports a DiLink 5.1 floating-map gesture fix and vehicle testing that are not part of fixed main. The patch changes documentation only and cannot establish that the fork has the same map or gesture implementation.",
        "test": "PASS_STATIC: docs/RELEASE-NOTES-NEXT.md only; NOT_RUN_TARGET: supported-head-unit floating-map gesture behavior",
        "related": "EX-08 release-document scope; PR-05/06 display and touch gates remain separate",
        "rollback": "No upstream gesture claim imported; keep fixed-main display documentation aligned with implemented behavior.",
        "review_state": "PRIMARY_AGENT_RELEASE_DOCS_FIXED_MAIN_REJECT",
    },
    "a60ef9a23cca1574c20b33ddd17140ca4e7f7aab": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This next-update release note bundles NCM/queue changes, Android 17 permissions, hotspot retries, an ADB VPN authorization workaround, and audio changes. The ADB path conflicts with fixed-main consent, and the other claims each have separate pending gates.",
        "test": "PASS_STATIC: diff only adds docs/RELEASE-NOTES-NEXT.md; NOT_RUN_PER_FEATURE: USB, permission, wireless, and audio acceptance",
        "related": "EX-08 release-document rejection; PR-00/01/02, EX-04, WIRELESS-EX, and AUDIO gates are independent",
        "rollback": "No bundled release claims imported; preserve fixed-main consent and document features only after their individual gates pass.",
        "review_state": "PRIMARY_AGENT_RELEASE_DOCS_FIXED_MAIN_REJECT",
    },
    "a8c2786c4cfb881a183fc85f059189457e739972": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This release-note update combines settings search, display appearance, video buffering, BYD side-panel/turn-card, and ambient-light claims. Several are OEM-specific or pending target acceptance, and fixed main has no matching release-note file.",
        "test": "PASS_STATIC: documentation-only diff; NOT_RUN_PER_FEATURE: video/A-V, BYD display/lighting, and settings behavior",
        "related": "EX-08 release-document scope; PR-05/06/07, BYD/OEM quarantine, and AUDIO/DSP gates remain distinct",
        "rollback": "No combined upstream release text imported; keep claims tied to verified fixed-main behavior.",
        "review_state": "PRIMARY_AGENT_RELEASE_DOCS_FIXED_MAIN_REJECT",
    },
    "1e2265c34eaab1cc80e2ed57b871453578845a24": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This release-note change groups many contributor PRs, including wireless Bluetooth, BYD call/cluster controls, decoder settings, update install, and backup behavior. It is an upstream feature bundle, not a fixed-main release record.",
        "test": "PASS_STATIC: docs/RELEASE-NOTES-NEXT.md only; fixed main has no corresponding release file; NOT_RUN_PER_FEATURE: referenced device-specific claims",
        "related": "EX-08 release-document rejection; each runtime feature remains under its individual PR/EX gate",
        "rollback": "No contributor release section imported; retain fixed-main settings, consent, and OEM boundaries.",
        "review_state": "PRIMARY_AGENT_RELEASE_DOCS_FIXED_MAIN_REJECT",
    },
    "4f4357af5837ddd8badb277979fe8a304f467004": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This merge copies the same multi-feature contributor release notes into the upstream documentation branch. The bundle does not establish which behaviors fixed main implements and must not be treated as fork parity.",
        "test": "PASS_STATIC: merge diff is docs/RELEASE-NOTES-NEXT.md only; no fixed-main release or runtime changes",
        "related": "EX-08 release-document merge rejection; source features remain separately gated",
        "rollback": "No merged release text imported; keep fork-specific documentation.",
        "review_state": "PRIMARY_AGENT_RELEASE_DOCS_FIXED_MAIN_REJECT",
    },
    "3c8b7c51383984dad8d5ec5ff2ee8d410d507901": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This #644 release-note update aggregates wired-start timing and unrelated fixes in upstream next-release text. It provides no source implementation or target acceptance for the matching PR-03/EX-03 gates.",
        "test": "PASS_STATIC: patch changes docs/RELEASE-NOTES-NEXT.md only; NOT_RUN_TARGET: wired-start timing and listed feature acceptance",
        "related": "EX-08 release-document scope; PR-03/EX-03/EX-05 causal gates remain open",
        "rollback": "No upstream release text imported; preserve the fixed-main watchdog and connection behavior claims.",
        "review_state": "PRIMARY_AGENT_RELEASE_DOCS_FIXED_MAIN_REJECT",
    },
    "3d9d6acc05d119f15f38c22af309fac104d3fdbe": {
        "disposition": "REJECT_UNSAFE",
        "reason": "These release notes include an Android 7.1–9 ADB permission-grant workaround and multiple wireless/UI features. The hidden grant conflicts with fixed-main explicit consent, and the legacy platform claim is below minSdk 28.",
        "test": "PASS_STATIC: docs-only diff; fixed main minSdk is 28 and requests UsbManager consent; NOT_RUN_TARGET: wireless feature claims",
        "related": "EX-08 release-document rejection; PR-00 consent, PR-02 platform, and WIRELESS-EX remain separate",
        "rollback": "No ADB grant or unsupported platform release text imported; keep fixed-main minSdk and explicit permission flow.",
        "review_state": "PRIMARY_AGENT_RELEASE_DOCS_FIXED_MAIN_REJECT",
    },
    "23b4c179cdcd551a0d2d0d1f19320f12db9533be": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This release-note update bundles Android 17 local-network permission, Bluetooth recovery, hotspot UI, and other contributor changes, some explicitly pending acceptance. It is upstream release text, not fixed-main evidence.",
        "test": "PASS_STATIC: docs/RELEASE-NOTES-NEXT.md only; NOT_RUN_TARGET: Android 17, wireless Bluetooth, and hotspot acceptance",
        "related": "EX-08 release-document scope; WIRELESS-EX and platform changes need independent evidence",
        "rollback": "No upstream release notes imported; only document behavior verified on the fixed fork.",
        "review_state": "PRIMARY_AGENT_RELEASE_DOCS_FIXED_MAIN_REJECT",
    },
    "0919997b8645b825e4370866d66d455678d2b9da": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This merge carries the same Android 17 and wireless contributor release notes into upstream docs. The text includes unverified platform and connection behavior absent from the fixed-main release record.",
        "test": "PASS_STATIC: merge diff changes docs/RELEASE-NOTES-NEXT.md only; no fixed-main implementation or acceptance change",
        "related": "EX-08 release-document merge rejection; WIRELESS-EX and platform changes remain gated",
        "rollback": "No merge release text imported; preserve fork-specific support statements.",
        "review_state": "PRIMARY_AGENT_RELEASE_DOCS_FIXED_MAIN_REJECT",
    },
    "c574e92f50938f08a1804314403c5d59d8c81792": {
        "disposition": "REJECT_UNSAFE",
        "reason": "These notes claim automatic hotspot startup on Android 8–10 and additional Bluetooth/UI/audio behavior absent from the fixed-main release record. The legacy-platform and target behavior are not validated for this fork.",
        "test": "PASS_STATIC: docs-only diff; fixed main minSdk is 28 and has no corresponding release-note entry; NOT_RUN_TARGET: hotspot/Bluetooth acceptance",
        "related": "EX-08 release-document scope; PR-02 platform and WIRELESS-EX hotspot gates",
        "rollback": "No Android 8–10 or automatic-hotspot release claims imported; retain fixed-main support documentation.",
        "review_state": "PRIMARY_AGENT_RELEASE_DOCS_FIXED_MAIN_REJECT",
    },
    "3f554012a5a60817ef18e3e4fcdb2ff9ed8a7069": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This merge repeats upstream Android 8–10 hotspot and wireless release-note claims. Fixed main supports minSdk 28 and does not include the release document or its full target-tested feature bundle.",
        "test": "PASS_STATIC: merge diff changes docs/RELEASE-NOTES-NEXT.md only; fixed-main minSdk is 28",
        "related": "EX-08 release-document merge rejection; PR-02 and WIRELESS-EX are separate",
        "rollback": "No merged release text imported; keep fork-specific platform and behavior claims.",
        "review_state": "PRIMARY_AGENT_RELEASE_DOCS_FIXED_MAIN_REJECT",
    },
    "4bf5e0df3ef9608688c20875fad1bfb59e94ca16": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This release-note update expands Android 8–10 car-hotspot support and adds BYD ambient-light behavior with other features. The legacy platform and OEM claims are not accepted fixed-main behavior.",
        "test": "PASS_STATIC: docs-only diff; fixed main has minSdk 28 and no BYD ambient-light bridge; NOT_RUN_TARGET: hotspot and lighting acceptance",
        "related": "EX-08 release-document scope; PR-02/WIRELESS-EX and BYD/OEM exclusion",
        "rollback": "No unsupported hotspot or BYD lighting claims imported; retain fixed-main support and OEM boundaries.",
        "review_state": "PRIMARY_AGENT_RELEASE_DOCS_FIXED_MAIN_REJECT",
    },
    "f0f1eadca5cb114b8fd469eb6b89f38a0615392f": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This merge integrates the same upstream release notes for Android 8–10 hotspot setup, BYD lighting, and related features; release text is not implementation or target acceptance evidence for fixed main.",
        "test": "PASS_STATIC: merge diff changes docs/RELEASE-NOTES-NEXT.md only; no fixed-main feature or acceptance changes",
        "related": "EX-08 release-document merge rejection; WIRELESS-EX and BYD/OEM remain independent",
        "rollback": "No merged release notes imported; maintain fork-specific documentation.",
        "review_state": "PRIMARY_AGENT_RELEASE_DOCS_FIXED_MAIN_REJECT",
    },
    "a30bde6ced956a92704ad5b93990f3aa243df7e8": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This release-note entry bundles a car-hotspot dialog, BYD voice-key behavior, and music-clock correction. V3 excludes BYD controls and keeps clock correction off pending measurement; fixed main does not ship the bundle.",
        "test": "PASS_STATIC: documentation-only patch; NOT_RUN_TARGET: hotspot, BYD voice-key, and clock-drift behavior",
        "related": "EX-08 release-document scope; WIRELESS-EX, BYD/OEM, and AUDIO-EX clock gates",
        "rollback": "No release claims imported; retain BYD exclusion and keep clock correction disabled.",
        "review_state": "PRIMARY_AGENT_RELEASE_DOCS_FIXED_MAIN_REJECT",
    },
    "bc2bdb3e71b12df8e1dd37f009af02017c268c02": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This merge repeats the car-hotspot, BYD voice-key, and music-clock release claims. Fixed main has not accepted those behaviors as a release, and v3 gates OEM/audio changes separately.",
        "test": "PASS_STATIC: merge diff changes docs/RELEASE-NOTES-NEXT.md only; no corresponding fixed-main runtime or acceptance change",
        "related": "EX-08 release-document merge rejection; WIRELESS-EX, BYD/OEM, and AUDIO-EX remain separate",
        "rollback": "No merged release text imported; preserve fixed-main feature and clock policy.",
        "review_state": "PRIMARY_AGENT_RELEASE_DOCS_FIXED_MAIN_REJECT",
    },
    "4cffec1e754d6ece2377c2dd7a791679ddafa0bb": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This release note bundles BYD call-audio routing with USB diagnostic inventory that logs `vid:pid node` identifiers. The call path is OEM-specific, and those identifiers are outside EX-01's approved privacy schema.",
        "test": "PASS_STATIC: documentation-only patch; USB inventory fields are not redacted by fixed main; NOT_RUN_TARGET: call routing",
        "related": "EX-01 privacy gate and BYD/OEM call exclusion; PR-04 remains microphone request handling",
        "rollback": "No call-audio or USB-inventory release claims imported; preserve fixed-main privacy and call behavior.",
        "review_state": "PRIMARY_AGENT_RELEASE_DOCS_PRIVACY_REJECT",
    },
    "53dbeba1bc99f2e65e46e28bb7c2a837227a64bc": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This merge carries the same BYD call-audio and unredacted USB device-inventory release notes. The text advertises OEM behavior and identifiers excluded by EX-01; it adds no fixed-main code.",
        "test": "PASS_STATIC: merge changes release notes only; `vid:pid node` fields remain outside the fixed-main diagnostic schema",
        "related": "EX-01 privacy gate and BYD/OEM call exclusion; no PR-04 microphone mapping",
        "rollback": "No merged release text imported; preserve fixed-main diagnostics and OEM scope.",
        "review_state": "PRIMARY_AGENT_RELEASE_DOCS_PRIVACY_REJECT",
    },
    "70b70d348382ba8fe63cabc389f02ed60552eee7": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This release-note bundle describes multiple connection, Bluetooth, hotspot, and diagnostic changes across unrelated PRs. Some claims remain acceptance-gated; the documentation cannot establish their behavior on fixed main.",
        "test": "PASS_STATIC: patch populates docs/RELEASE-NOTES-NEXT.md only; NOT_RUN_PER_FEATURE: referenced wireless, permission, and connection changes",
        "related": "EX-08 release-document scope; evaluate each runtime candidate under its own STEP4 gate",
        "rollback": "No bundled upstream release section imported; tie documentation to individually verified fork behavior.",
        "review_state": "PRIMARY_AGENT_RELEASE_DOCS_FIXED_MAIN_REJECT",
    },
    "4a564dff77ea5ac937c89f82434ce1c19e23a88f": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This release-note entry bundles hotspot-state messaging with launching CarPlay from BYD's DiYou desktop. The BYD launch path is excluded and the combined text is not a fixed-main behavior record.",
        "test": "PASS_STATIC: docs-only diff; fixed main has no DiYou/ByD launch path; NOT_RUN_TARGET: hotspot state behavior",
        "related": "EX-08 release-document scope; BYD/OEM and WIRELESS-EX gates remain separate",
        "rollback": "No BYD launch or hotspot release claim imported; preserve the fixed-main activity and hotspot path.",
        "review_state": "PRIMARY_AGENT_RELEASE_DOCS_FIXED_MAIN_REJECT",
    },
    "b485ff6cb3a6acdbba125ca2b626a27a1801ce06": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This merge copies the mixed upstream next-release notes for connection, hotspot, BYD, and other features. The release text is not evidence that fixed main contains or has accepted those changes.",
        "test": "PASS_STATIC: merge changes docs/RELEASE-NOTES-NEXT.md only; no fixed-main implementation or acceptance change",
        "related": "EX-08 release-document merge rejection; runtime candidates remain individually gated",
        "rollback": "No merged release text imported; retain fork-specific documentation.",
        "review_state": "PRIMARY_AGENT_RELEASE_DOCS_FIXED_MAIN_REJECT",
    },
    "df8fc54bd7177dff1cd6d9938ccaaba98d4f9506": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This release note advertises USB-wait diagnostics containing Android USB `vid:pid` and device-node paths. The identifiers are not redacted by fixed main and exceed EX-01's approved `WIRED_PATH_V1` schema.",
        "test": "PASS_STATIC: release-note text adds `vid:pid node` fields; fixed-main redaction leaves them unchanged; no runtime code change",
        "related": "EX-01 privacy gate; USB diagnostics must remain aggregate and PII-free",
        "rollback": "No USB device-inventory logging claim imported; preserve the `WIRED_PATH_V1` privacy boundary.",
        "review_state": "PRIMARY_AGENT_RELEASE_DOCS_PRIVACY_REJECT",
    },
    "1b1ac5f27986ce83b6d17fdd8c4d1be852fbbb70": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This merge carries the upstream USB-inventory diagnostic release claim, including `vid:pid` and device-node paths that survive the source redactor. It conflicts with EX-01's PII-free aggregate schema.",
        "test": "PASS_STATIC: merge changes release notes only; diagnostic identifiers remain outside the fixed-main redaction schema",
        "related": "EX-01 privacy gate and USB diagnostic schema; no direct product implementation",
        "rollback": "No release claim or device-inventory logging imported; retain the privacy-safe fixed-main schema.",
        "review_state": "PRIMARY_AGENT_RELEASE_DOCS_PRIVACY_REJECT",
    },
}
BASE_REVIEW_OVERRIDES.update({
    "45563135a3d05a17315a84f57434fdb5dfa43a5f": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This document requests fresh reports for unresolved upstream 0.2.10 issues. It is an upstream support workflow, not a fixed-main diagnostics feature or required STEP4 artifact.",
        "test": "PASS_STATIC: patch changes version-specific report/release documentation only; no code or test path changed",
        "related": "EX-08 upstream support-document scope; diagnostic features remain separately reviewed under EX-01",
        "rollback": "No upstream issue-report instructions imported; retain fixed-main diagnostics and export behavior.",
        "review_state": "PRIMARY_AGENT_DOCS_OUT_OF_SCOPE_FIXED_MAIN",
    },
    "5d170cc4a38074abf3e48fce15a5609681501e5b": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This document records an upstream October 3 issue/PR safety review against an upstream main SHA. It is historical audit material, not fixed-main behavior or STEP4 acceptance evidence.",
        "test": "PASS_STATIC: documentation-only diff names upstream main/review state; no fixed-main code changed",
        "related": "EX-08 audit provenance; do not import upstream review instructions or conclusions as fork policy",
        "rollback": "No upstream safety-review report imported; keep fixed-main governance and source baseline.",
        "review_state": "PRIMARY_AGENT_UPSTREAM_AUDIT_DOC_REJECT",
    },
    "bb6dd37640f4d65348962607c9f74712aaa20d5b": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This report records an upstream audit of issues, closures, and release validation. It describes upstream repository activity rather than a fixed-main implementation and is not a STEP4 product artifact.",
        "test": "PASS_STATIC: documentation-only diff; no fixed-main source or test path changed",
        "related": "EX-08 upstream audit provenance; source report is context only, not fork parity evidence",
        "rollback": "No upstream issue-audit report imported; preserve fixed-main documentation.",
        "review_state": "PRIMARY_AGENT_UPSTREAM_AUDIT_DOC_REJECT",
    },
    "d623af930dd639bd650bb7bbd4bdd224c44e6229": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This document distinguishes historical upstream review results from publication preflight on an upstream safety branch. It is workflow history, not fixed-main implementation or STEP4 acceptance evidence.",
        "test": "PASS_STATIC: docs-only change references upstream review branches/commits; no fixed-main runtime or test changed",
        "related": "EX-08 audit provenance; upstream publication status is not a fixed-main release decision",
        "rollback": "No upstream preflight report imported; keep fixed-main release status independent.",
        "review_state": "PRIMARY_AGENT_UPSTREAM_AUDIT_DOC_REJECT",
    },
    "1eb2ae64b75c9c77999365562f65f4e751f71e0d": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This documentation updates validation and publication statements for an upstream integration branch and named upstream PR merges. It does not test the fixed-main tree or provide fixed-main acceptance.",
        "test": "PASS_STATIC: documentation-only patch; referenced merged heads/authorization belong to the upstream audit branch",
        "related": "EX-08 audit provenance; no runtime STEP4 mapping",
        "rollback": "No upstream publication/validation record imported; keep the fork's evidence ledger.",
        "review_state": "PRIMARY_AGENT_UPSTREAM_AUDIT_DOC_REJECT",
    },
    "76a9ca9779802d67bfe7c4fc93ba2978d13fc2dd": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This document records an upstream 0.2.11 release validation, APK, signing, and test results. Those artifacts and counts are not evidence for the fixed-main STEP4 branch.",
        "test": "PASS_STATIC: release-validation documentation only; no fixed-main release/signing artifact changed",
        "related": "EX-08 release-document scope; use CI/device evidence from the pinned fork only",
        "rollback": "No upstream release results or version claims imported; preserve fork-specific release status.",
        "review_state": "PRIMARY_AGENT_RELEASE_DOCS_FIXED_MAIN_REJECT",
    },
    "e678301d6e57188239eab45472cfa7694ef7f6bd": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This installation guide changes the advertised upstream APK to 0.2.12 and adds same-LAN hotspot instructions. Those release/update and wireless claims are outside the fixed-main STEP4 change set.",
        "test": "PASS_STATIC: docs/INSTALL.md only; no fixed-main 0.2.12 release entry; NOT_RUN_TARGET: same-LAN hotspot setup",
        "related": "EX-08 installation-document scope; WIRELESS-EX and PR-08 update/signature checks remain separate",
        "rollback": "No upstream installer/version or same-LAN instructions imported; keep fixed-main installation documentation.",
        "review_state": "PRIMARY_AGENT_RELEASE_DOCS_FIXED_MAIN_REJECT",
    },
    "2fc876e578eba3905a5b873e3c2dbd74f498433a": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This 0.2.12 release-note document summarizes fixes and review corrections for upstream features. It has no corresponding fixed-main release implementation or acceptance record.",
        "test": "PASS_STATIC: docs/RELEASE-NOTES-0.2.12.md only; no product source or fixed-main test changed",
        "related": "EX-08 release-document scope; underlying USB/wireless/audio features remain separately gated",
        "rollback": "No upstream 0.2.12 release notes imported; retain fork-specific changelog and limitations.",
        "review_state": "PRIMARY_AGENT_RELEASE_DOCS_FIXED_MAIN_REJECT",
    },
    "434a9b3716fd832e7100b1fde42da6fba9f90434": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This document records an upstream 0.2.13 APK build, compatibility, and signing status. Those release checks were not run for the fixed-main STEP4 branch and cannot be presented as its evidence.",
        "test": "PASS_STATIC: validation/release-note docs only; no fixed-main release or signature artifact changed",
        "related": "EX-08 release evidence scope; PR-08 signature/update rehearsal remains NOT_RUN for target",
        "rollback": "No upstream release-validation claims imported; report only fork-specific CI and device results.",
        "review_state": "PRIMARY_AGENT_RELEASE_DOCS_FIXED_MAIN_REJECT",
    },
    "afda80bdbbe6b5527fd498b6afe880fbb6f90b8c": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This patch changes only an upstream build guide and toolchain instructions; it is outside the STEP4 implementation and was not reconciled with the pinned fork build configuration.",
        "test": "PASS_STATIC: docs/BUILD.md only; no fixed-main Gradle/SDK configuration changed",
        "related": "EX-08 build-document scope; retain fixed-main Gradle/SDK and CI configuration",
        "rollback": "No upstream build instructions imported; keep the fixed-main build procedure.",
        "review_state": "PRIMARY_AGENT_DOCS_OUT_OF_SCOPE_FIXED_MAIN",
    },
    "f47f780e478218fb2651921b847138ab18abf886": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This contributor-update document adds an automatic GitHub update check, diagnostic-report export guidance, and other upstream feature claims not established on fixed main. It also changes privacy/install copy without a fork-specific review.",
        "test": "PASS_STATIC: docs/INSTALL.md, docs/PRIVACY.md, and docs/RELEASE-NOTES-NEXT.md only; no product code changed",
        "related": "EX-08 release/privacy documentation scope; PR-08 update and EX-01 export behavior remain independent gates",
        "rollback": "No upstream update/export/privacy claims imported; keep fork-specific documentation.",
        "review_state": "PRIMARY_AGENT_RELEASE_DOCS_FIXED_MAIN_REJECT",
    },
    "09399b14805195bb027bfbc1ef2f1482e59625f5": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This merge repeats upstream contributor-release, update-check, report-export, and privacy documentation changes. It adds no corresponding fixed-main implementation or acceptance evidence.",
        "test": "PASS_STATIC: merge changes documentation/site resources only; fixed-main runtime unchanged",
        "related": "EX-08 documentation merge rejection; PR-08 update and EX-01 diagnostic-export behavior remain separate",
        "rollback": "No upstream release/privacy text imported; preserve fixed-main user-facing claims.",
        "review_state": "PRIMARY_AGENT_RELEASE_DOCS_FIXED_MAIN_REJECT",
    },
})

DELTA_DECISION_OVERRIDES = {
    "4aee9fa705b20c0947a5da1e5c132328a6878f58": {
        "disposition": "REJECT_UNSAFE",
        "reason": (
            "The patch combines Bluetooth adapter reacquisition and persistent failure diagnostics with a new Aliyun Maven repository. "
            "Fixed main uses only Google and Maven Central; changing dependency provenance is not justified by this wireless fix, "
            "so the aggregate commit is not safe to import. Later isolated Bluetooth patches remain separate evidence candidates."
        ),
        "test": "PASS_STATIC: patch adds Aliyun to settings.gradle.kts while fixed main uses google() and mavenCentral(); NOT_RUN_TARGET: Bluetooth adapter/radio A/B",
        "related": "Reject mixed dependency-source and Bluetooth aggregate; WIRELESS-EX Bluetooth acquisition and EX-01 diagnostics remain separate",
        "rollback": "No repository or Bluetooth change imported; retain fixed-main dependency sources and evaluate any later isolated adapter change against target evidence.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_MIXED_REPOSITORY_REJECTED",
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
        "disposition": "REJECT_UNSAFE",
        "reason": "The patch combines an EX-04 10-second USB mode recheck with an unrelated change from AudioTrack gain to in-place PCM navigation attenuation. The v3 plan gates same-node re-enumeration on device evidence, and the audio path has its own quality/performance gate; reject the aggregate as supplied without disproving either isolated candidate.",
        "test": "PASS_STATIC: patch changes CarPlayController re-enumeration and AndroidMediaSink/NavigationAudioGain PCM processing; upstream policy tests do not exercise fixed-main ownership or target audio; NOT_RUN_TARGET: same-node USB mode transition and navigation-level A/B",
        "related": "Reject mixed EX-04 and AUDIO-EX aggregate; keep re-enumeration conditional and navigation PCM gain separately gated",
        "rollback": "No patch imported; any later EX-04 timer must be generation/permission fenced and independently revertible, and any navigation gain change must have its own media-quality rollback.",
        "review_state": "PRIMARY_AGENT_PATCH_REVIEWED_MIXED_GATE_REJECTED",
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
        "disposition": "REJECT_UNSAFE",
        "reason": "The patch changes validation in the legacy CarPlayHostActivity settings overlay. In fixed main, buildSettingsMenu has no caller; openSettingsMenu launches DiPlayActivity instead, so this patch cannot change the active settings flow and its upstream UI tests exercise an unreachable path.",
        "test": "PASS_STATIC: fixed-main call graph has no buildSettingsMenu caller and saveSettingsAndReconnect is called only by that overlay; openSettingsMenu routes through showDiPlayHome to DiPlayActivity",
        "related": "Reject inactive host-menu patch; any unused-hotspot save fix must target the active DiPlayActivity settings flow",
        "rollback": "No legacy overlay change imported; retain the fixed-main DiPlayActivity settings route and test any future fix on that active surface.",
        "review_state": "PRIMARY_AGENT_FIXED_MAIN_CALLPATH_REVIEW",
    },
    "940c362a0ee7196a785727a653cd1bffa3dfef1b": {
        "disposition": "REJECT_UNSAFE",
        "reason": "This follow-up extends the same inactive CarPlayHostActivity overlay signature with the wireless-enable flag. Fixed main has no call path to that menu, so it cannot enforce hotspot validation on the active DiPlayActivity settings surface.",
        "test": "PASS_STATIC: fixed-main openSettingsMenu launches DiPlayActivity while buildSettingsMenu remains uncalled; the upstream toggle tests exercise only the inactive overlay",
        "related": "Reject inactive host-menu follow-up; no active WIRELESS-EX settings behavior is established by this patch",
        "rollback": "No legacy overlay signature change imported; retain the fixed-main settings route and require an active-screen test for any future validation fix.",
        "review_state": "PRIMARY_AGENT_FIXED_MAIN_CALLPATH_REVIEW",
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
        "disposition": "SKIP_OEM",
        "reason": "The patch updates translated descriptions for DiLink 3 call/Siri/media-key behavior and the BYD navigation guide. Fixed main has neither the call-control UI/key implementation nor docs/BYD_NAVIGATION.md; v3 preserves the fork's BYD/OEM removal, and PR-04 covers microphone requests rather than call/key routing.",
        "test": "PASS_STATIC: changed files are translated strings and docs/BYD_NAVIGATION.md; fixed main has no call-control setting or BYD guide; NOT_RUN_OEM: DiLink 3 key behavior",
        "related": "BYD/OEM content exclusion; no PR-04 microphone or generic STEP4 mapping",
        "rollback": "No strings or BYD guide imported; keep fixed-main UI, key routing, and documentation.",
        "review_state": "PRIMARY_AGENT_PLAN_EXCLUSION_VERIFIED",
    },
    "cacac6d68804ebbfdba444925d1b9088d02b4fff": {
        "disposition": "REJECT_UNSAFE",
        "reason": "The commit adds Italian to the app-language registry and a broad resource set that includes upstream-only settings. Fixed main supports system, English, and Japanese and has no values-it resources; this unrequested locale change is outside STEP4 and cannot be safely adopted as a partial translation batch.",
        "test": "PASS_STATIC: fixed-main AppLanguage accepts only system/en/ja and has no values-it directory; PATCH_DIFF: adds Italian selection and translations for upstream-only settings; no STEP4 runtime change",
        "related": "EX-08 localization scope; no runtime STEP4 PR mapping",
        "rollback": "No Italian locale or resources imported; any future localization work must be requested and complete across the fixed-main UI.",
        "review_state": "PRIMARY_AGENT_FIXED_MAIN_SCOPE_REVIEW",
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
        "disposition": "REJECT_UNSAFE",
        "reason": "This follow-up adds Italian strings for upstream settings and changes upstream AGENTS.md localization wording. Fixed main has no Italian locale, and the strings describe features outside STEP4; neither a partial locale nor upstream repository guidance belongs in the fixed-main implementation.",
        "test": "PASS_STATIC: fixed main has no values-it resources or Italian locale entry; patch changes Italian resources and upstream AGENTS.md only; no runtime STEP4 behavior",
        "related": "EX-08 localization/content scope; no runtime STEP4 PR mapping",
        "rollback": "No Italian resources or upstream guidance imported; keep localization work separate from STEP4 implementation.",
        "review_state": "PRIMARY_AGENT_FIXED_MAIN_SCOPE_REVIEW",
    },
    "aefc702d55ddffd0d935af92279a23e501bd173d": {
        "disposition": "SKIP_OEM",
        "reason": "The patch is a documentation-only edit to the BYD navigation guide. Fixed main intentionally has no BYD guide or cluster/key product surface, and v3 excludes this OEM behavior from the generic STEP4 path.",
        "test": "PASS_STATIC: patch changes docs/BYD_NAVIGATION.md only; fixed main has no such guide; NOT_APPLICABLE_TO_GENERIC_TS10_STEP4",
        "related": "EX-08 BYD documentation scope; BYD product behavior is excluded",
        "rollback": "No BYD documentation imported; preserve the fixed-main documentation surface.",
        "review_state": "PRIMARY_AGENT_PLAN_EXCLUSION_VERIFIED",
    },
    "447bddc4835810c25249b28b8d2d959e1886d4f7": {
        "disposition": "REJECT_UNSAFE",
        "reason": "The patch adds Italian strings for upstream Siri/media-wheel and display settings, but fixed main has no Italian locale and no matching DiLink key-control implementation. Importing only these resources would add an incomplete, misleading localization surface outside STEP4.",
        "test": "PASS_STATIC: fixed main has no values-it directory or matching wheel Siri/media setting keys; patch is resource-only",
        "related": "EX-08 localization scope; no runtime STEP4 PR mapping",
        "rollback": "No Italian resource strings imported; keep future locale work separate and complete.",
        "review_state": "PRIMARY_AGENT_FIXED_MAIN_SCOPE_REVIEW",
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
        "disposition": "REJECT_UNSAFE",
        "reason": "The Italian resource patch mixes BYD ambient-rhythm text with hotspot-credential import/write and launcher-bubble settings. Fixed main has no Italian locale or matching settings; importing this string set would advertise unsupported OEM and configuration behavior outside STEP4.",
        "test": "PASS_STATIC: fixed main has no values-it resources or matching ambient-rhythm, hotspot-import, and launcher-bubble setting keys; patch is resource-only",
        "related": "EX-08 localization scope; BYD ambient-rhythm strings are excluded",
        "rollback": "No Italian resources imported; any future locale must omit unsupported controls and cover all live fixed-main keys.",
        "review_state": "PRIMARY_AGENT_FIXED_MAIN_SCOPE_REVIEW",
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
        "disposition": "REJECT_UNSAFE",
        "reason": "The release-note commit claims a new 30-second wired-start retry while explicitly marking in-car acceptance pending, and also describes BYD cluster handback. Fixed main keeps the existing 8/15-second watchdog and excludes BYD cluster behavior, so importing these notes would claim unverified behavior for the fork.",
        "test": "PASS_STATIC: release-note diff only; fixed-main watchdog remains 8/15 seconds and has no BYD cluster path; NOT_RUN_TARGET: retry acceptance",
        "related": "PR-03/EX-03/EX-05 wired-start gates; BYD cluster behavior is excluded",
        "rollback": "No release text imported; preserve the fixed-main watchdog behavior and BYD removal.",
        "review_state": "PRIMARY_AGENT_FIXED_MAIN_AND_PLAN_REVIEW",
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
