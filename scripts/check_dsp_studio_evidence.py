#!/usr/bin/env python3
"""Validate the DSP Studio per-ID evidence ledger without inferring results."""

from __future__ import annotations

import argparse
import re
import sys
from datetime import datetime
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[1]
MATRIX = ROOT / "docs/dsp-studio/TEST_MATRIX_DETAILED.md"
LEDGER = ROOT / "docs/dsp-studio/REMAINING_FIX_TRACKER.md"
EXPECTED_COVERAGE = {f"REM-{n:02d}" for n in range(1, 13)} | {
    f"PKG-R{n:02d}" for n in range(13)
}
WORK_PACKAGE_ROWS = [
    ("REM-01", "safe PREAMP HOT ramp", "PARTIAL", "Not recorded", "Not recorded", "shared/src/main/java/com/shilapi/xcertplay/media/dsp/DspUpdatePlanner.kt<br>shared/src/main/jni/dsp/dsp_engine.h<br>shared/src/main/jni/dsp/dsp_macro.h", "Not package-mapped; aggregate source tested at fe083f4f524fa6ee1038860343e4f100aee8dad6", "./gradlew --no-daemon --max-workers=1 --console=plain :shared:testDebugUnitTest :common:testDebugUnitTest; bash scripts/test_dsp_native.sh", "Aggregate unit suites 1002/1002 and Native tests reported PASS at the cited source head; safe PREAMP HOT ramp has no mapped result.", "PREAMP remains unsupported/DEFERRED in the reported live-update policy; a safe HOT ramp is not verified.", "Project-level Native/live-update reviews recorded; REM-specific review not recorded.", "PARTIAL: adjacent safety paths tested, requested PREAMP ramp not verified."),
    ("REM-02", "DynamicEQ field/subfield HOT", "PARTIAL", "Not recorded", "Not recorded", "shared/src/main/java/com/shilapi/xcertplay/media/dsp/DspDynamicEqConfig.kt<br>shared/src/main/java/com/shilapi/xcertplay/media/dsp/DspUpdatePlanner.kt<br>shared/src/test/java/com/shilapi/xcertplay/media/dsp/DspDynamicEqConfigTest.kt", "Not package-mapped; aggregate source tested at fe083f4f524fa6ee1038860343e4f100aee8dad6", "./gradlew --no-daemon --max-workers=1 --console=plain :shared:testDebugUnitTest", "Aggregate Shared tests are included in 1002/1002; field/subfield HOT assertion result is not mapped.", "Field-level DynamicEQ HOT behavior and rollback/race evidence remain incomplete.", "Project-level live-update review recorded; field-specific review not recorded.", "PARTIAL: broad unit gate passed; DynamicEQ field/subfield HOT remains unverified."),
    ("REM-03", "safe Compressor/Limiter/Seat Trim/Width updates with unsafe DEFER", "PARTIAL", "Not recorded", "Not recorded", "shared/src/main/java/com/shilapi/xcertplay/media/dsp/DspDynamicsConfig.kt<br>shared/src/main/java/com/shilapi/xcertplay/media/dsp/DspSeatAlignmentConfig.kt<br>shared/src/main/java/com/shilapi/xcertplay/media/dsp/DspSpatialConfig.kt<br>shared/src/main/java/com/shilapi/xcertplay/media/dsp/DspUpdatePlanner.kt", "Not package-mapped; aggregate source tested at fe083f4f524fa6ee1038860343e4f100aee8dad6", "./gradlew --no-daemon --max-workers=1 --console=plain :shared:testDebugUnitTest", "Aggregate Shared tests included in 1002/1002; exact safe/unsafe field mapping is not package-attributed.", "Complete safe HOT field set and explicit unsafe DEFER cases need one-to-one evidence.", "Project-level Native and Kotlin/JNI reviews recorded; requirement-specific review not recorded.", "PARTIAL: software coverage reported; full field-level QA incomplete."),
    ("REM-04", "two-phase Cancel, failure state, retry and race fencing", "PARTIAL", "Not recorded", "Not recorded", "common/src/main/java/com/shilapi/xcertplay/dsp/DspEditorSession.kt<br>common/src/main/java/com/shilapi/xcertplay/dsp/DspProfileRuntime.kt<br>shared/src/main/java/com/shilapi/xcertplay/media/dsp/DspLiveUpdateController.kt", "Not package-mapped; aggregate source tested at fe083f4f524fa6ee1038860343e4f100aee8dad6", "./gradlew --no-daemon --max-workers=1 --console=plain :shared:testDebugUnitTest :common:testDebugUnitTest", "Aggregate unit suites 1002/1002; runtime rollback and renderer race regressions reported PASS.", "Complete Cancel/retry/failure state matrix and per-ID evidence remain open.", "Project-level Kotlin/JNI and runtime-flag reviews recorded; REM-specific review not recorded.", "PARTIAL: core regression coverage reported; complete two-phase matrix incomplete."),
    ("REM-05", "independent saveable scroll per five tabs", "PARTIAL", "Not recorded", "Not recorded", "common/src/main/java/com/shilapi/xcertplay/DspSettingsScreen.kt<br>common/src/test/java/com/shilapi/xcertplay/DspSettingsUiContractTest.kt<br>common/src/androidTest/java/com/shilapi/xcertplay/DspSettingsScreenTest.kt", "Not package-mapped; aggregate source tested at fe083f4f524fa6ee1038860343e4f100aee8dad6", "./gradlew --no-daemon --max-workers=1 --console=plain :common:testDebugUnitTest", "Five-tab UI and screen tests are reported; independent scroll save/restore per tab has no mapped result.", "Independent scroll state persistence and restoration across all five tabs remain unverified.", "Project-level UI review recorded; scroll-specific review not recorded.", "PARTIAL: navigation exists; saveable independent scroll QA incomplete."),
    ("REM-06", "same-source-window transient module A/B level-match", "PARTIAL", "Not recorded", "Not recorded", "common/src/main/java/com/shilapi/xcertplay/dsp/DspEditorSession.kt<br>common/src/main/java/com/shilapi/xcertplay/DspSettingsScreen.kt<br>common/src/androidTest/java/com/shilapi/xcertplay/DspSettingsScreenTest.kt", "No package-specific commit recorded; aggregate source tested at fe083f4f524fa6ee1038860343e4f100aee8dad6", "API 35 DspSettingsScreenTest class-filter command is recorded in FINAL_IMPLEMENTATION_REPORT.md; same-source level-match execution not run.", "Bass-only A/B and draft preservation passed 4/4 at ce3c87d7; same-source measurement window is unavailable.", "Level-match measurement and same-source window remain unavailable; no gain is written.", "Project-level UI review recorded; level-match-specific review not recorded.", "PARTIAL: scoped A/B works; requested measurement-based level match is unavailable."),
    ("REM-07", "per-ID evidence for 170 acceptance tests", "IN_PROGRESS", "Not recorded", "Per-ID evidence depends on each acceptance package; no sequencing recorded.", "docs/dsp-studio/TEST_MATRIX_DETAILED.md<br>docs/dsp-studio/REMAINING_FIX_TRACKER.md<br>scripts/check_dsp_studio_evidence.py<br>scripts/test_dsp_studio_evidence.py", "PR #66 tooling baseline 914b092a2734b7570d3a23867385908fca7e26ed; package-table update is currently in progress.", "python scripts/check_dsp_studio_evidence.py; python -m unittest scripts/test_dsp_studio_evidence.py", "170 rows; 0 PASS / 152 PARTIAL / 18 NOT_RUN; 13 fixture tests passed.", "Per-ID method, command, result, SHA, artifact, and acceptance subcase evidence remain incomplete.", "Evidence-ledger review gate remains open in REVIEW_MATRIX.md.", "IN_PROGRESS: schema and guard checks exist; acceptance evidence itself remains partial/unrun."),
    ("REM-08", "LOCAL_WARM callback cost/overload/perf gate", "PARTIAL", "Not recorded", "Not recorded", "shared/src/main/java/com/shilapi/xcertplay/media/dsp/DspUpdatePlanner.kt<br>shared/src/test/java/com/shilapi/xcertplay/media/dsp/DspUpdatePlannerTest.kt<br>shared/src/test/java/com/shilapi/xcertplay/media/dsp/DspRendererProductionPathTest.kt<br>shared/src/main/jni/dsp/dsp_graph.h", "PR #65 head 7785b113f462c42809442bf4bf466d9bb41acbb7 added PEQ LOCAL_WARM; PR #52 integration extends Static Bass at fe083f4f524fa6ee1038860343e4f100aee8dad6.", "./gradlew --no-daemon --max-workers=1 --console=plain :shared:testDebugUnitTest :common:testDebugUnitTest; bash scripts/test_dsp_native.sh", "Native/renderer tests and Shared/Common suites reported PASS; target callback-cost, overload and device performance gate is not recorded as passed.", "Device p99/CPU/RSS, callback overload, and long-run performance evidence remain open.", "Independent live-update/audio reviews recorded; performance gate review remains open.", "PARTIAL: function tests reported; target performance acceptance NOT_RUN."),
    ("REM-09", "TS10/K706/iPhone hardware acceptance", "NOT_STARTED", "Not recorded", "Integrated PR #52 build and PR #45 baseline comparison are specified in RUNBOOK.md.", "docs/dsp-studio/RUNBOOK.md<br>docs/dsp-studio/REMEDIATION_SPEC.md<br>docs/dsp-studio/REVIEW_MATRIX.md", "No hardware acceptance commit recorded.", "Hardware commands are listed in docs/dsp-studio/RUNBOOK.md; no device command has been executed.", "Runbook defines the required sequence; no TS10/K706/iPhone results exist.", "No physical measurements for CarPlay, audio, volume, latency, performance, A/V, or soak.", "REVIEW_MATRIX.md and FINAL_IMPLEMENTATION_REPORT.md keep hardware gates NOT_RUN.", "NOT_STARTED: no target-device session/evidence bundle recorded."),
    ("REM-10", "OEM/physical volume correlation and Smart Loudness fail-closed", "PARTIAL", "Not recorded", "Not recorded", "common/src/main/java/com/shilapi/xcertplay/dsp/DspVolumeSource.kt<br>common/src/main/java/com/shilapi/xcertplay/dsp/AndroidPlaybackVolumeMonitor.kt<br>common/src/main/java/com/shilapi/xcertplay/dsp/DspSmartLoudnessPane.kt<br>shared/src/main/jni/dsp/dsp_smart_loudness.h", "PR #53 head 02d79ba2ccd56992b947fbab2e82b80bfe91769e; package-specific hardware correlation commit not recorded.", "DspVolumeSourceTest and DspTuningResolverTest; exact local command not recorded in SUBAGENT_PR_LEDGER.md.", "12 focused tests PASS per SUBAGENT_PR_LEDGER.md; physical route/OEM correlation NOT_RUN.", "OEM-specific volume correlation and physical AUTO trust proof remain open; fail-closed behavior must remain default.", "Project-level runtime/loudness review recorded; OEM-specific review not recorded.", "PARTIAL: fail-closed software path tested; physical correlation NOT_RUN."),
    ("REM-11", "EQ graph mapping/golden/selection/final ACK", "PARTIAL", "Not recorded", "Not recorded", "shared/src/main/java/com/shilapi/xcertplay/media/dsp/DspEqDesigner.kt<br>common/src/main/java/com/shilapi/xcertplay/dsp/DspEditorPanes.kt<br>common/src/androidTest/java/com/shilapi/xcertplay/dsp/DspEqEditorGraphTest.kt", "PR #56 head 9c278b8078b2055a61e57b8ebebf572e21c2cccc; final-ACK package commit not separately mapped.", "DspEditorPanesTest; API 35 DspEqEditorGraphTest evidence is recorded in the implementation/test reports.", "DspEditorPanesTest 4 tests PASS per SUBAGENT_PR_LEDGER.md; frequency tolerance/golden/selection/final ACK remain unmapped.", "Exact curve-to-parameter mapping, golden comparison, overlap selection, and final audio ACK remain open.", "Project-level UI/audio review recorded; final-ACK-specific review not recorded.", "PARTIAL: graph interaction exists; full golden and final-ACK QA incomplete."),
    ("REM-12", "full code-head CI gates (not docs-only skipped)", "PARTIAL", "Not recorded", "Integrated PR #52 source head; source commit is the required CI subject.", ".github/workflows/android.yml<br>docs/dsp-studio/CI_FAILURE_INVESTIGATION.md<br>docs/dsp-studio/TEST_MATRIX.md<br>docs/dsp-studio/FINAL_IMPLEMENTATION_REPORT.md", "Code source fe083f4f524fa6ee1038860343e4f100aee8dad6 was tested locally; hosted run #506 is documentation head 1e4a8962cedcc90fbf505c5f01346408e2842b77.", "./gradlew --no-daemon --max-workers=1 --console=plain :shared:testDebugUnitTest :common:testDebugUnitTest; hosted run #506.", "Local code-head unit/native/API35 gates reported PASS; run #506 passed required jobs but was docs-only and skipped quick/draft-only steps.", "A full hosted CI run directly on the current code commit, including skipped lanes, is still needed.", "REVIEW_MATRIX.md states CI green at docs head; code-head completeness remains open.", "PARTIAL: local gates passed; requested full code-head hosted gate not evidenced."),
    ("PKG-R00", "PKG-00 baseline", "PARTIAL", "Not recorded", "Not recorded", "docs/dsp-studio/BASELINE.md<br>docs/dsp-studio/FINAL_IMPLEMENTATION_REPORT.md", "Not recorded; baseline package report does not name an implementation commit.", "Not applicable; no package-scoped test command recorded.", "FINAL_IMPLEMENTATION_REPORT.md marks baseline captured; no package-scoped result artifact mapped.", "Baseline owner, dependency, review, and QA provenance not fully mapped.", "Package-level reviewer not recorded.", "PARTIAL: summary says captured; package evidence metadata incomplete."),
    ("PKG-R01", "Runtime/State", "PARTIAL", "Not recorded", "FIX-01/05 coordination is required by the spec; package-specific sequencing not recorded.", "common/src/main/java/com/shilapi/xcertplay/dsp/DspProfileRuntime.kt<br>common/src/main/java/com/shilapi/xcertplay/dsp/DspEditorSession.kt<br>common/src/main/java/com/shilapi/xcertplay/dsp/DspStudioPersistence.kt<br>common/src/test/java/com/shilapi/xcertplay/dsp/DspProfileRuntimeTest.kt", "No package-specific implementation commit recorded; aggregate source tested at fe083f4f524fa6ee1038860343e4f100aee8dad6.", "./gradlew --no-daemon --max-workers=1 --console=plain :shared:testDebugUnitTest :common:testDebugUnitTest", "Aggregate Shared/Common 1002/1002; runtime recreation, rollback and state regression cases are reported in FINAL_IMPLEMENTATION_REPORT.md.", "Complete two-phase preview/cancel, failure, recreation and per-ID state evidence remain open.", "Project-level Kotlin/JNI and rollback reviews recorded; package-specific review not recorded.", "PARTIAL: runtime/state implementation and tests exist; acceptance mapping incomplete."),
    ("PKG-R02", "Compose UI", "PARTIAL", "Not recorded; PR #54 model attribution is not asserted as owner.", "Runtime/State UI contract; no further package ordering recorded.", "common/src/main/java/com/shilapi/xcertplay/DspSettingsScreen.kt<br>common/src/main/java/com/shilapi/xcertplay/DspMacroStudio.kt<br>common/src/androidTest/java/com/shilapi/xcertplay/DspSettingsScreenTest.kt", "PR #54 head ac7aa0478d621f1e33b6a3acca663b952522e92f.", "DspSettingsUiContractTest; exact command not recorded.", "UI contract test PASS; API 35 DspSettingsScreenTest 4/4 recorded at ce3c87d7.", "Per-tab saveable scroll and physical CarPlay geometry/touch remain open.", "PR-level UI review exists; package-specific scroll review not recorded.", "PARTIAL: emulator UI checks exist; physical acceptance and scroll persistence unverified."),
    ("PKG-R03", "Graph UI", "PARTIAL", "Not recorded; PR #56 model attribution is not asserted as owner.", "Compose UI scaffold; package-specific ordering not recorded.", "common/src/main/java/com/shilapi/xcertplay/dsp/DspEditorPanes.kt<br>shared/src/main/java/com/shilapi/xcertplay/media/dsp/DspEqDesigner.kt<br>common/src/androidTest/java/com/shilapi/xcertplay/dsp/DspEqEditorGraphTest.kt", "PR #56 head 9c278b8078b2055a61e57b8ebebf572e21c2cccc.", "DspEditorPanesTest; exact command not recorded.", "DspEditorPanesTest 4 tests PASS per SUBAGENT_PR_LEDGER.md; frequency/golden/selection/final ACK map remains partial.", "Exact frequency tolerance, golden agreement, overlap selection and final audio ACK remain open.", "PR-level editor review exists; graph acceptance review incomplete.", "PARTIAL: graph editing exists; full mapped graph QA incomplete."),
    ("PKG-R04", "Native HOT/Preamp", "PARTIAL", "Not recorded", "Native API/ABI contract and Runtime/State ownership per REMEDIATION_SPEC.md.", "shared/src/main/jni/dsp/dsp_engine.h<br>shared/src/main/jni/dsp/dsp_macro.h<br>shared/src/main/java/com/shilapi/xcertplay/media/dsp/DspUpdatePlanner.kt<br>shared/src/test/java/com/shilapi/xcertplay/media/dsp/DspRendererProductionPathTest.kt", "No single package-specific commit recorded; current source tested at fe083f4f524fa6ee1038860343e4f100aee8dad6.", "bash scripts/test_dsp_native.sh; ./gradlew --no-daemon --max-workers=1 --console=plain :shared:testDebugUnitTest", "Native and aggregate Shared tests reported PASS; safe PREAMP HOT ramp is not evidenced and remains DEFERRED.", "Preamp ramp, hot-path headroom and exact field/subfield generation behavior require direct evidence.", "Native/audio and Kotlin/JNI review tracks recorded; package-specific preamp review not recorded.", "PARTIAL: Native HOT infrastructure tested; requested safe PREAMP ramp not verified."),
    ("PKG-R05", "Native Dynamics", "PARTIAL", "Not recorded", "Native HOT/Preamp graph/API integration; dependency order not otherwise recorded.", "shared/src/main/jni/dsp/dsp_dynamics.h<br>shared/src/main/jni/dsp/dsp_engine.h<br>shared/src/test/native/dsp/test_dsp_engine.c", "No package-specific implementation commit recorded; aggregate source tested at fe083f4f524fa6ee1038860343e4f100aee8dad6.", "bash scripts/test_dsp_native.sh; ASAN_OPTIONS=detect_leaks=0:halt_on_error=1 UBSAN_OPTIONS=halt_on_error=1 bash scripts/test_dsp_native.sh --sanitize", "Native and ASan/UBSan reported PASS; package-specific result mapping is not recorded.", "Target signal quality, overload/performance, and full field evidence remain open.", "Project-level Native review recorded; package-specific review not recorded.", "PARTIAL: Native test evidence exists; target and field-specific QA incomplete."),
    ("PKG-R06", "Dynamic EQ/Spatial", "PARTIAL", "Not recorded", "Native Dynamics stages and shared resolver budget; exact sequence not recorded.", "shared/src/main/jni/dsp/dsp_dynamic_eq.h<br>shared/src/main/jni/dsp/dsp_spatial.h<br>shared/src/test/java/com/shilapi/xcertplay/media/dsp/DspDynamicEqConfigTest.kt<br>shared/src/test/java/com/shilapi/xcertplay/media/dsp/DspAdvancedMsConfigTest.kt", "No package-specific implementation commit recorded; aggregate source tested at fe083f4f524fa6ee1038860343e4f100aee8dad6.", "./gradlew --no-daemon --max-workers=1 --console=plain :shared:testDebugUnitTest", "Aggregate Shared suite passed within 1002/1002; DynamicEQ field/subfield HOT and spatial path are not separately attributed.", "REM-02 HOT semantics and complete spatial/phase/seat coverage remain open.", "Project-level Native and integration reviews recorded; package-specific review not recorded.", "PARTIAL: component tests reported; full Dynamic EQ/Spatial acceptance incomplete."),
    ("PKG-R07", "Local Warm performance", "PARTIAL", "Not recorded; PR #65 attribution is not asserted as an owner.", "Native HOT/Preamp and Native Dynamics graph ownership; no further sequencing recorded.", "shared/src/main/java/com/shilapi/xcertplay/media/dsp/DspUpdatePlanner.kt<br>shared/src/test/java/com/shilapi/xcertplay/media/dsp/DspUpdatePlannerTest.kt<br>shared/src/test/java/com/shilapi/xcertplay/media/dsp/DspRendererProductionPathTest.kt<br>shared/src/main/jni/dsp/dsp_graph.h", "PR #65 head 7785b113f462c42809442bf4bf466d9bb41acbb7; PR #52 extends the shared EQ warm path to Static Bass.", "./gradlew --no-daemon --max-workers=1 --console=plain :shared:testDebugUnitTest :common:testDebugUnitTest; bash scripts/test_dsp_native.sh", "PR #65 records unit/native/sanitizer/API 35 evidence; target callback-cost/performance results are not recorded.", "Device callback cost, overload, p99/CPU/RSS and long-run warm performance remain open.", "Project-level live-update/audio reviews recorded; performance-specific review open.", "PARTIAL: functionality tested; performance acceptance NOT_RUN."),
    ("PKG-R08", "A/B Level Match", "PARTIAL", "Not recorded; PR #55 model attribution is not asserted as owner.", "Runtime/State draft and renderer preview ownership; no further sequence recorded.", "common/src/main/java/com/shilapi/xcertplay/dsp/DspEditorSession.kt<br>common/src/main/java/com/shilapi/xcertplay/DspSettingsScreen.kt<br>common/src/androidTest/java/com/shilapi/xcertplay/DspSettingsScreenTest.kt", "PR #55 head 48c90a09a5f0df95a12fb35e7a17058f1e04def9 includes store/session work; A/B-specific integration commit not recorded.", "API 35 DspSettingsScreenTest command recorded in FINAL_IMPLEMENTATION_REPORT.md; level-match measurement command not run.", "Bass-only A/B and draft preservation passed 4/4 at ce3c87d7; level matching explicitly unavailable without same-source window.", "Same-source-window level measurement and Compressor-specific status mapping remain open.", "Project-level UI/data review recorded; level-match review not recorded.", "PARTIAL: scoped A/B tested; measurement-based Level Match not verified."),
    ("PKG-R09", "Loudness/Bass safety", "PARTIAL", "Not recorded; PR #53/#59 attribution is not asserted as ownership.", "Runtime volume monitor, resolver budget and Native stages; exact sequence not recorded.", "common/src/main/java/com/shilapi/xcertplay/dsp/DspVolumeSource.kt<br>common/src/main/java/com/shilapi/xcertplay/dsp/DspTuningResolver.kt<br>shared/src/main/jni/dsp/dsp_smart_loudness.h<br>shared/src/main/jni/dsp/dsp_dynamic_bass.h", "PR #53 head 02d79ba2ccd56992b947fbab2e82b80bfe91769e; PR #59 head fdebaa9ad543e07d0f673a7706af59e743ffee92.", "DspVolumeSourceTest/DspTuningResolverTest (exact focused command not recorded); bash scripts/test_dsp_native.sh.", "12 focused loudness tests and Native/ASan-UBSan bass tests reported PASS; physical volume correlation NOT_RUN.", "OEM route correlation, true-peak/acoustic proof, and target safety/performance remain open.", "Project-level runtime, Native and audio reviews recorded; package-specific review not recorded.", "PARTIAL: fail-closed and bounded software paths tested; physical safety acceptance NOT_RUN."),
    ("PKG-R10", "170 evidence", "IN_PROGRESS", "Not recorded", "All REM work packages contribute acceptance evidence; no package ordering recorded.", "docs/dsp-studio/TEST_MATRIX_DETAILED.md<br>docs/dsp-studio/REMAINING_FIX_TRACKER.md<br>scripts/check_dsp_studio_evidence.py<br>scripts/test_dsp_studio_evidence.py", "PR #66 evidence-tooling baseline 914b092a2734b7570d3a23867385908fca7e26ed; current tracker update is active.", "python scripts/check_dsp_studio_evidence.py; python -m unittest scripts/test_dsp_studio_evidence.py", "170 rows, 0 PASS / 152 PARTIAL / 18 NOT_RUN; 13 fixture tests pass.", "Per-ID method/command/result/source SHA/artifact and unproven acceptance subcases remain incomplete.", "Evidence-ledger review gate remains open in REVIEW_MATRIX.md.", "IN_PROGRESS: validator and work tracker exist; underlying 170 acceptance evidence is incomplete."),
    ("PKG-R11", "CarPlay/device runbook", "PARTIAL", "Not recorded", "Integrated PR #52 candidate and PR #45 baseline comparison, as specified by RUNBOOK.md.", "docs/dsp-studio/RUNBOOK.md<br>docs/dsp-studio/REMEDIATION_SPEC.md<br>docs/dsp-studio/REVIEW_MATRIX.md", "No package-specific commit recorded; runbook exists in the PR #52 integrated tree.", "Hardware procedure commands are in docs/dsp-studio/RUNBOOK.md; no device command has been executed.", "Runbook defines TS10/K706/iPhone workflow; device results are explicitly absent and remain NOT_RUN.", "No physical measurements for CarPlay geometry/session/Surface/touch, route calibration, acoustics, A/V, performance, or soak.", "REVIEW_MATRIX.md records hardware gaps as NOT_RUN; no hardware sign-off recorded.", "PARTIAL: runbook is defined; target-hardware gates have not run."),
    ("PKG-R12", "Quality/review/CI", "PARTIAL", "Not recorded", "PR #52 integrated source and the four independent review tracks in REMEDIATION_SPEC.md/REVIEW_MATRIX.md.", "docs/dsp-studio/REVIEW_MATRIX.md<br>docs/dsp-studio/FINAL_IMPLEMENTATION_REPORT.md<br>docs/dsp-studio/TEST_MATRIX.md<br>.github/workflows/android.yml<br>common/src/main/java/com/shilapi/xcertplay/dsp/DspProfileRuntime.kt", "Integrated source tested at fe083f4f524fa6ee1038860343e4f100aee8dad6; hosted documentation head 1e4a8962cedcc90fbf505c5f01346408e2842b77.", "./gradlew --no-daemon --max-workers=1 --console=plain :shared:testDebugUnitTest :common:testDebugUnitTest; bash scripts/test_dsp_native.sh; hosted run #506.", "Shared/Common 1002/1002, Native/sanitizer and rollback regression gates reported PASS; run #506 required jobs passed.", "P1 per-ID evidence gate and hardware gates remain open; MERGE_READY=false.", "REVIEW_MATRIX.md records four independent review tracks and follow-ups; project disposition remains open for evidence/hardware.", "PARTIAL: software rollback and integration QA recorded; PR #52 remains open/unmerged pending evidence and hardware."),
]
WORK_PACKAGE_OWNER_OVERRIDES = {
    "REM-01": "Luna /root/preamp_hot",
    "REM-05": "Luna /root/tab_scroll",
    "REM-07": "Luna /root/evidence_ledger",
    "PKG-R02": "Luna /root/tab_scroll",
    "PKG-R04": "Luna /root/preamp_hot",
    "PKG-R10": "Luna /root/evidence_ledger",
}
WORK_PACKAGE_ROWS = [
    (row[0], row[1], row[2], WORK_PACKAGE_OWNER_OVERRIDES.get(row[0], "/root (pending work)"), *row[4:])
    for row in WORK_PACKAGE_ROWS
]
EXPECTED_WORK_PACKAGE_NAMES = {row[0]: row[1] for row in WORK_PACKAGE_ROWS}
EVIDENCE_FIELDS = (
    "TestClass.Method",
    "Assertion(s)",
    "ExecutionCommand",
    "TestType",
    "GitSHA",
    "CI_Run",
    "ArtifactPath/URL",
    "Result",
    "Environment(API/ABI/OS/device)",
    "Reviewer",
    "LastObservedAt",
)
TRACKER_COLUMNS = (
    "ID",
    "AcceptancePredicate",
    "Status",
    *EVIDENCE_FIELDS,
    "RemainingRisk",
    "Runbook",
    "Blocking reason",
    "Missing evidence",
    "Unproven predicate",
)
TRACKER_HEADER = "| " + " | ".join(TRACKER_COLUMNS) + " |"
SHA_RE = re.compile(r"^[0-9a-f]{40}$", re.IGNORECASE)
GITHUB_ACTIONS_RUN_RE = re.compile(r"^https://github\.com/choko510/DiPlay/actions/runs/\d+/?$")
MARKDOWN_LINK_RE = re.compile(r"^\[[^\]]+\]\(([^)]+)\)$")


def split_table_cells(line: str) -> list[str]:
    cells: list[str] = []
    cell: list[str] = []
    escaped = False
    for char in line.strip():
        if char == "|" and not escaped:
            cells.append("".join(cell).strip())
            cell = []
        else:
            cell.append(char)
        if char == "\\" and not escaped:
            escaped = True
        else:
            escaped = False
    cells.append("".join(cell).strip())
    if cells and not cells[0]:
        cells.pop(0)
    if cells and not cells[-1]:
        cells.pop()
    return [cell.replace("\\|", "|") for cell in cells]


def table_rows(path: Path, header: str) -> list[list[str]]:
    rows: list[list[str]] = []
    active = False
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip() == header:
            active = True
            continue
        if not active:
            continue
        if not line.startswith("|"):
            if rows:
                break
            continue
        cells = split_table_cells(line)
        if cells and set(cells[0]) <= {"-", ":"}:
            continue
        rows.append(cells)
    return rows


def matrix_ids() -> set[str]:
    ids = set()
    for line in MATRIX.read_text(encoding="utf-8").splitlines():
        match = re.match(r"\|\s*((?:PH\d{2}-T\d{2})|(?:T-[A-Z]+-\d{2})|(?:T-PRE-\d{2})|(?:T-REG-\d{2})|(?:T-SAFE-\d{2})|(?:T-UI-\d{2}))\s*\|", line)
        if match:
            ids.add(match.group(1))
    return ids


def validate_matrix_counts(ids: set[str]) -> list[str]:
    ph_count = sum(id_.startswith("PH") for id_ in ids)
    t_count = sum(id_.startswith("T-") for id_ in ids)
    errors = []
    if ph_count != 60:
        errors.append(f"canonical PH-ID count is {ph_count}, expected 60")
    if t_count != 110:
        errors.append(f"canonical T-ID count is {t_count}, expected 110")
    return errors


def canonical_acceptance_cases() -> dict[str, str]:
    rows = table_rows(MATRIX, "| ID | Acceptance case | Relevant evidence | Status | Remaining evidence / gap |")
    return {row[0]: row[1] for row in rows if len(row) == 5}


def repo_file_exists(path_text: str, *, relative_to: Path) -> bool:
    path = PurePosixPath(path_text)
    if path.is_absolute() or ".." in path.parts or ":" in path_text:
        return False
    candidate = relative_to.joinpath(*path.parts)
    try:
        resolved = candidate.resolve(strict=True)
        resolved.relative_to(ROOT.resolve())
    except (OSError, RuntimeError, ValueError):
        return False
    return resolved.is_file()


def valid_artifact(reference: str) -> bool:
    if GITHUB_ACTIONS_RUN_RE.fullmatch(reference):
        return True
    return repo_file_exists(reference, relative_to=ROOT)


def valid_runbook(reference: str) -> bool:
    match = MARKDOWN_LINK_RE.fullmatch(reference)
    target = match.group(1) if match else reference
    if target.startswith(("http://", "https://", "mailto:")):
        return False
    target = target.split("#", 1)[0].split("?", 1)[0]
    return bool(target) and repo_file_exists(target, relative_to=MATRIX.parent)


def valid_observed_at(value: str) -> bool:
    try:
        timestamp = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return False
    return timestamp.tzinfo is not None


def create_tracker() -> None:
    source_rows: list[tuple[str, str, str]] = []
    pattern = re.compile(r"^\|\s*((?:PH\d{2}-T\d{2})|(?:T-[A-Z]+-\d{2})|(?:T-PRE-\d{2})|(?:T-REG-\d{2})|(?:T-SAFE-\d{2})|(?:T-UI-\d{2}))\s*\|\s*(.*?)\s*\|\s*(.*?)\s*\|\s*(PARTIAL|NOT_RUN|PASS|FAIL)\s*\|\s*(.*?)\s*\|")
    for line in MATRIX.read_text(encoding="utf-8").splitlines():
        match = pattern.match(line)
        if match:
            id_, acceptance, _, old_status, gap = match.groups()
            status = "NOT_RUN" if old_status == "NOT_RUN" else "PARTIAL"
            source_rows.append((id_, acceptance.replace("\\|", "|"), status + "\t" + gap))
    if len(source_rows) != 170:
        raise ValueError(f"expected 170 canonical rows, parsed {len(source_rows)}")
    lines = [
        "# DSP Studio remaining fix and evidence tracker", "",
        "This ledger mirrors all acceptance IDs in [TEST_MATRIX_DETAILED.md](TEST_MATRIX_DETAILED.md). It records evidence independently and does not promote historical suite or CI results. No row is `PASS` because this branch has no per-ID evidence bundle tied to its source SHA.", "",
        "Counts: **170 acceptance rows** (60 PH-ID + 110 T-ID). Status counts are calculated by `python scripts/check_dsp_studio_evidence.py`.", "",
        "## Evidence ledger", "",
        "`PASS` requires every test, assertion, command, type, source SHA, CI run, artifact, result, environment, reviewer, and observation-time field. `PARTIAL` lists every missing evidence field and describes the unproven predicate. `NOT_RUN` requires a valid runbook reference and blocking reason. `RemainingRisk` stays explicit for every row.", "",
        "| " + " | ".join(TRACKER_COLUMNS) + " |",
        "|" + "|".join("---" for _ in TRACKER_COLUMNS) + "|",
    ]
    for id_, acceptance, status_gap in source_rows:
        status, gap = status_gap.split("\t", 1)
        acceptance = acceptance.replace("|", "\\|")
        safe_gap = gap.replace("|", "/")
        runbook = "[RUNBOOK.md](RUNBOOK.md)"
        blocker = safe_gap or "Dedicated device or acceptance run has not been executed."
        missing = ", ".join(EVIDENCE_FIELDS)
        unproven = f"Not run: {safe_gap}" if status == "NOT_RUN" else f"Not directly demonstrated: {safe_gap}"
        values = [
            id_, acceptance, status, *("" for _ in EVIDENCE_FIELDS), safe_gap,
            runbook, blocker, missing, unproven,
        ]
        lines.append("| " + " | ".join(values) + " |")
    lines.extend(["", "## Work package tracker", "", "This table tracks execution ownership, dependencies, paths, implementation commits, tests, risks, review, and QA separately from acceptance evidence. `NOT_STARTED` means no implementation/package definition is recorded, `IN_PROGRESS` means tracked work remains active, and `PARTIAL` means some implementation or verification exists but the package is not closed. Owners show current task assignments: `/root (pending work)` is the primary owner unless an explicit subtask owner is listed. Historical PR author/model attribution is not inferred. `Not recorded` marks unavailable dependencies, commits, commands, results, or reviews.", "", "| Work ID | Work package | Status | Owner | Dependencies | Relevant paths | Implementation commit | Test command | Test result | Unresolved risk | Review state | QA state |", "|---|---|---|---|---|---|---|---|---|---|---|---|"])
    for package in WORK_PACKAGE_ROWS:
        lines.append("| " + " | ".join(package) + " |")
    lines.extend(["", "## Running the check", "", "Run `python scripts/check_dsp_studio_evidence.py`. The validator compares IDs and acceptance predicates to the canonical detailed matrix, requires exactly 170 IDs and all 25 REM/PKG coverage IDs, rejects duplicates, checks all §12.2 evidence columns, validates artifact and runbook references, and enforces PASS/PARTIAL/NOT_RUN contracts.", ""])
    LEDGER.write_text("\n".join(lines), encoding="utf-8")


def validate() -> list[str]:
    errors: list[str] = []
    ids = matrix_ids()
    if len(ids) != 170:
        errors.append(f"canonical matrix ID count is {len(ids)}, expected 170")
    errors.extend(validate_matrix_counts(ids))
    canonical = canonical_acceptance_cases()
    if set(canonical) != ids:
        errors.append("canonical acceptance predicate rows do not match canonical IDs")
    rows = table_rows(LEDGER, TRACKER_HEADER)
    if not rows:
        return errors + ["tracker evidence table was not found"]
    entries: dict[str, list[str]] = {}
    for row in rows:
        if len(row) != len(TRACKER_COLUMNS):
            errors.append(f"tracker row has {len(row)} columns, expected {len(TRACKER_COLUMNS)}: {row[:1]}")
            continue
        id_, status = row[0], row[2]
        if id_ in entries:
            errors.append(f"duplicate tracker ID {id_}")
        entries[id_] = row
        fields = dict(zip(TRACKER_COLUMNS, row))
        if id_ in canonical and fields["AcceptancePredicate"] != canonical[id_]:
            errors.append(f"{id_}: acceptance predicate does not match TEST_MATRIX_DETAILED.md")
        if status not in {"PASS", "PARTIAL", "NOT_RUN"}:
            errors.append(f"{id_}: invalid status {status!r}")
            continue
        if status == "PASS":
            for field in EVIDENCE_FIELDS:
                if not fields[field]:
                    errors.append(f"{id_}: PASS requires {field}")
            if fields["GitSHA"] and not SHA_RE.fullmatch(fields["GitSHA"]):
                errors.append(f"{id_}: GitSHA must be a full 40-character commit")
            if not valid_observed_at(fields["LastObservedAt"]):
                errors.append(f"{id_}: LastObservedAt must be an ISO-8601 timestamp with timezone")
            if fields["Missing evidence"]:
                errors.append(f"{id_}: PASS must not list missing evidence")
            if fields["Unproven predicate"]:
                errors.append(f"{id_}: PASS must not list an unproven predicate")
            if fields["RemainingRisk"] == "":
                errors.append(f"{id_}: PASS requires RemainingRisk or an explicit no-risk value")
            if fields["ArtifactPath/URL"] and not valid_artifact(fields["ArtifactPath/URL"]):
                errors.append(f"{id_}: PASS artifact must resolve to an existing repo file or direct GitHub Actions run URL")
        elif status == "NOT_RUN":
            if not fields["Runbook"] or not fields["Blocking reason"]:
                errors.append(f"{id_}: NOT_RUN requires runbook and blocking reason")
            elif not valid_runbook(fields["Runbook"]):
                errors.append(f"{id_}: runbook reference does not resolve to an existing repo file")
            if not fields["RemainingRisk"]:
                errors.append(f"{id_}: NOT_RUN requires RemainingRisk")
        else:
            if not fields["Runbook"]:
                errors.append(f"{id_}: PARTIAL requires a runbook reference")
            elif not valid_runbook(fields["Runbook"]):
                errors.append(f"{id_}: runbook reference does not resolve to an existing repo file")
            if not fields["Unproven predicate"]:
                errors.append(f"{id_}: PARTIAL requires a description of the unproven acceptance predicate")
            if not fields["RemainingRisk"]:
                errors.append(f"{id_}: PARTIAL requires RemainingRisk")
            missing = [part.strip() for part in fields["Missing evidence"].split(",") if part.strip()]
            for field in missing:
                if field not in EVIDENCE_FIELDS:
                    errors.append(f"{id_}: unknown missing evidence field {field!r}")
                elif fields[field]:
                    errors.append(f"{id_}: {field} is populated but listed as missing evidence")
            expected_missing = [field for field in EVIDENCE_FIELDS if not fields[field]]
            if missing != expected_missing:
                errors.append(f"{id_}: Missing evidence must exactly list blank evidence fields")
    expected = ids
    actual = set(entries)
    if actual != expected:
        errors.append(f"ledger ID mismatch: missing={len(expected-actual)} extra={len(actual-expected)}")
    coverage = table_rows(LEDGER, "| Work ID | Work package | Status | Owner | Dependencies | Relevant paths | Implementation commit | Test command | Test result | Unresolved risk | Review state | QA state |")
    coverage_ids = {row[0] for row in coverage if row}
    if len(coverage_ids) != len(coverage):
        errors.append("duplicate REM/PKG work package ID")
    for row in coverage:
        if len(row) != 12:
            errors.append(f"work package row has {len(row)} columns, expected 12: {row[:1]}")
            continue
        if row[2] not in {"NOT_STARTED", "IN_PROGRESS", "PARTIAL"}:
            errors.append(f"{row[0]}: invalid work package status {row[2]!r}")
        if EXPECTED_WORK_PACKAGE_NAMES.get(row[0]) != row[1]:
            errors.append(f"{row[0]}: work package name does not match the tracked scope")
        if any(not cell for cell in row):
            errors.append(f"{row[0]}: work package fields must be populated or explicitly marked not recorded")
    if coverage_ids != EXPECTED_COVERAGE:
        errors.append(f"REM/PKG work package coverage mismatch: missing={sorted(EXPECTED_COVERAGE-coverage_ids)} extra={sorted(coverage_ids-EXPECTED_COVERAGE)}")
    statuses = {status: sum(1 for row in entries.values() if len(row) > 2 and row[2] == status) for status in ("PASS", "PARTIAL", "NOT_RUN")}
    print(f"DSP Studio evidence ledger: {len(entries)} rows; PASS={statuses['PASS']} PARTIAL={statuses['PARTIAL']} NOT_RUN={statuses['NOT_RUN']}; work packages={len(coverage_ids)}/25")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write-tracker", action="store_true", help="regenerate tracker from the canonical matrix")
    args = parser.parse_args()
    if args.write_tracker:
        create_tracker()
    errors = validate()
    for error in errors:
        print(f"ERROR: {error}", file=sys.stderr)
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
