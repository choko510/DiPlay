#!/usr/bin/env python3
"""Validate the DSP Studio per-ID evidence ledger without inferring results."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[1]
MATRIX = ROOT / "docs/dsp-studio/TEST_MATRIX_DETAILED.md"
LEDGER = ROOT / "docs/dsp-studio/REMAINING_FIX_TRACKER.md"
EXPECTED_COVERAGE = {f"REM-{n:02d}" for n in range(1, 13)} | {
    f"PKG-R{n:02d}" for n in range(13)
}
WORK_PACKAGE_ROWS = [
    ("REM-01", "REQ-001 Non-destructive sound macros", "PARTIAL", "Not recorded", "Not recorded", "common/src/main/java/com/shilapi/xcertplay/DspMacroStudio.kt<br>common/src/main/java/com/shilapi/xcertplay/dsp/DspTuningResolver.kt", "Not package-mapped; aggregate source tested at fe083f4f524fa6ee1038860343e4f100aee8dad6", "./gradlew --no-daemon --max-workers=1 --console=plain :shared:testDebugUnitTest :common:testDebugUnitTest", "Aggregate Shared/Common 1002/1002 at the cited source head; no requirement-level attribution recorded.", "Exact macro-to-assertion and target listening evidence remain open.", "Independent review exists at project level; requirement-level review not recorded.", "PARTIAL: software suites recorded; per-ID acceptance evidence incomplete."),
    ("REM-02", "REQ-002 Studio, PEQ, presets, A/B and preview/save", "PARTIAL", "Not recorded", "Not recorded", "common/src/main/java/com/shilapi/xcertplay/DspSettingsScreen.kt<br>common/src/main/java/com/shilapi/xcertplay/dsp/DspStudioPersistence.kt<br>common/src/test/java/com/shilapi/xcertplay/dsp/DspStudioPersistenceTest.kt", "Not package-mapped; aggregate source tested at fe083f4f524fa6ee1038860343e4f100aee8dad6", "./gradlew --no-daemon --max-workers=1 --console=plain :shared:testDebugUnitTest :common:testDebugUnitTest", "Aggregate Shared/Common 1002/1002; API 35 screen tests recorded in FINAL_IMPLEMENTATION_REPORT.md.", "Level-match measurement, CarPlay host geometry/session behavior, and per-ID mapping remain open.", "Project-level review recorded; requirement-level review not recorded.", "PARTIAL: emulator evidence exists; physical acceptance remains NOT_RUN."),
    ("REM-03", "REQ-003 Smart Loudness", "PARTIAL", "Not recorded", "Not recorded", "common/src/main/java/com/shilapi/xcertplay/dsp/DspVolumeSource.kt<br>common/src/test/java/com/shilapi/xcertplay/dsp/DspVolumeSourceTest.kt", "Not package-mapped; aggregate source tested at fe083f4f524fa6ee1038860343e4f100aee8dad6", "./gradlew --no-daemon --max-workers=1 --console=plain :shared:testDebugUnitTest :common:testDebugUnitTest", "Aggregate Shared/Common 1002/1002; target route/OEM calibration was not run.", "Trusted hardware route calibration and OEM correlation remain NOT_RUN.", "Project-level review recorded; requirement-level review not recorded.", "PARTIAL: software tests only; target calibration NOT_RUN."),
    ("REM-04", "REQ-004 Dynamic Bass 2.0", "PARTIAL", "Not recorded", "Not recorded", "shared/src/main/jni/dsp/dsp_dynamic_bass.h<br>common/src/main/java/com/shilapi/xcertplay/dsp/DspDynamicBassSettingsPane.kt", "Not package-mapped; aggregate source tested at fe083f4f524fa6ee1038860343e4f100aee8dad6", "bash scripts/test_dsp_native.sh", "Native suite and ASan/UBSan are reported PASS in FINAL_IMPLEMENTATION_REPORT.md.", "Target listening, THD, and performance measurements remain NOT_RUN.", "Project-level review recorded; requirement-level review not recorded.", "PARTIAL: software coverage reported; device acoustic/performance gates NOT_RUN."),
    ("REM-05", "REQ-005 Shared gain budget and signal safety", "PARTIAL", "Not recorded", "Not recorded", "common/src/main/java/com/shilapi/xcertplay/dsp/DspTuningResolver.kt<br>shared/src/main/jni/dsp/dsp_engine.h", "Not package-mapped; aggregate source tested at fe083f4f524fa6ee1038860343e4f100aee8dad6", "./gradlew --no-daemon --max-workers=1 --console=plain :shared:testDebugUnitTest :common:testDebugUnitTest", "Aggregate Shared/Common 1002/1002; Native and sanitizer gates reported PASS.", "Hardware true-peak/listening validation and exact per-ID evidence remain open.", "Project-level review recorded; requirement-level review not recorded.", "PARTIAL: software bounds reported; hardware proof NOT_RUN."),
    ("REM-06", "REQ-006 HOT/WARM/DEFERRED and generation ACK", "PARTIAL", "Not recorded", "Not recorded", "shared/src/main/java/com/shilapi/xcertplay/media/dsp/DspUpdatePlanner.kt<br>shared/src/main/java/com/shilapi/xcertplay/media/dsp/DspLiveUpdateController.kt<br>shared/src/test/java/com/shilapi/xcertplay/media/dsp/DspRendererProductionPathTest.kt", "Not package-mapped; aggregate source tested at fe083f4f524fa6ee1038860343e4f100aee8dad6", "./gradlew --no-daemon --max-workers=1 --console=plain :shared:testDebugUnitTest :common:testDebugUnitTest", "Aggregate Shared/Common 1002/1002; Native, sanitizer, and API 35 suites reported PASS.", "Field-level ID mapping, Cabin/IR support boundaries, and target playback remain open.", "Project-level review recorded; requirement-level review not recorded.", "PARTIAL: software suites passed; per-field and device evidence incomplete."),
    ("REM-07", "REQ-007 Live/CarPlay continuity", "PARTIAL", "Not recorded", "Not recorded", "shared/src/main/java/com/shilapi/xcertplay/media/dsp/DspStreamPolicy.kt<br>shared/src/main/java/com/shilapi/xcertplay/media/dsp/DspLatencyPreservingBypass.kt<br>shared/src/androidTest/java/com/shilapi/xcertplay/media/dsp/DspPipelineBenchmarkInstrumentedTest.kt", "Not package-mapped; aggregate source tested at fe083f4f524fa6ee1038860343e4f100aee8dad6", "./gradlew --no-daemon --max-workers=1 --console=plain :shared:testDebugUnitTest :common:testDebugUnitTest", "Aggregate Shared/Common and hosted run #506 reported PASS; target-device tests not run.", "TS10/K706 session, format-change, A/V, and long-run acceptance remain NOT_RUN.", "Project-level review recorded; requirement-level review not recorded.", "PARTIAL: host evidence exists; physical continuity remains NOT_RUN."),
    ("REM-08", "REQ-008 Full/compact UI accessibility and behavior", "PARTIAL", "Not recorded", "Not recorded", "common/src/main/java/com/shilapi/xcertplay/DspSettingsScreen.kt<br>common/src/test/java/com/shilapi/xcertplay/DspSettingsUiContractTest.kt<br>common/src/androidTest/java/com/shilapi/xcertplay/DspSettingsScreenTest.kt", "Not package-mapped; aggregate source tested at fe083f4f524fa6ee1038860343e4f100aee8dad6", "./gradlew --no-daemon --max-workers=1 --console=plain :shared:testDebugUnitTest :common:testDebugUnitTest", "API 35 DspSettingsScreenTest 4/4 is recorded at ce3c87d7; aggregate run #506 passed.", "Actual CarPlay geometry/touch and accessibility-device evidence remain NOT_RUN.", "Project-level UI review recorded; requirement-level review not recorded.", "PARTIAL: emulator UI evidence exists; hardware interaction NOT_RUN."),
    ("REM-09", "REQ-009 Master OFF, MEDIA-only, PCM formats and PEQ capacity", "PARTIAL", "Not recorded", "Not recorded", "shared/src/main/java/com/shilapi/xcertplay/media/dsp/DspStreamPolicy.kt<br>shared/src/test/java/com/shilapi/xcertplay/media/dsp/DspOffLegacyPcmTest.kt<br>shared/src/test/java/com/shilapi/xcertplay/media/dsp/DspLatencyPreservingBypassTest.kt", "Not package-mapped; aggregate source tested at fe083f4f524fa6ee1038860343e4f100aee8dad6", "./gradlew --no-daemon --max-workers=1 --console=plain :shared:testDebugUnitTest :common:testDebugUnitTest", "Aggregate Shared/Common, Native, and JNI gates reported PASS.", "Physical route proof and complete per-ID mapping remain open.", "Project-level review recorded; requirement-level review not recorded.", "PARTIAL: software evidence reported; device proof NOT_RUN."),
    ("REM-10", "REQ-010 v0-v7 profile compatibility and persistence", "PARTIAL", "Not recorded", "Not recorded", "common/src/main/java/com/shilapi/xcertplay/dsp/DspStudioPersistence.kt<br>common/src/main/java/com/shilapi/xcertplay/dsp/DspProfileRepository.kt<br>common/src/test/java/com/shilapi/xcertplay/dsp/DspProfileRuntimeTest.kt", "Not package-mapped; aggregate source tested at fe083f4f524fa6ee1038860343e4f100aee8dad6", "./gradlew --no-daemon --max-workers=1 --console=plain :shared:testDebugUnitTest :common:testDebugUnitTest", "Aggregate Shared/Common 1002/1002; migration/preset suites are reported in FINAL_IMPLEMENTATION_REPORT.md.", "Complete migration/race mapping and final per-ID evidence remain open.", "Project-level review recorded; requirement-level review not recorded.", "PARTIAL: suites reported; exact migration-to-ID artifacts incomplete."),
    ("REM-11", "REQ-011 Final host and hardware acceptance", "PARTIAL", "Not recorded", "Not recorded", "docs/dsp-studio/RUNBOOK.md<br>shared/src/androidTest/java/com/shilapi/xcertplay/media/dsp/DspPipelineBenchmarkInstrumentedTest.kt", "No hardware acceptance commit recorded; hosted run #506 is at 1e4a8962cedcc90fbf505c5f01346408e2842b77", "Hardware commands are listed in docs/dsp-studio/RUNBOOK.md; no device command was executed.", "Run #506 host jobs passed; TS10/K706/iPhone physical acceptance NOT_RUN.", "No target hardware measurements for route, geometry, acoustics, latency, or continuity.", "Final project review remains open; no hardware sign-off recorded.", "PARTIAL: hosted software CI only; physical QA NOT_RUN."),
    ("REM-12", "REQ-012 Reviewable evidence, rollback, and release decision", "IN_PROGRESS", "Not recorded", "Not recorded", "docs/dsp-studio/REMAINING_FIX_TRACKER.md<br>scripts/check_dsp_studio_evidence.py<br>scripts/test_dsp_studio_evidence.py", "PR #66 head changes tracked on codex/pr52-rem-07-evidence; current commit recorded in git, not fixed in this table.", "python scripts/check_dsp_studio_evidence.py; python -m unittest scripts/test_dsp_studio_evidence.py", "Latest recorded check: 170 rows; 0 PASS / 152 PARTIAL / 18 NOT_RUN; 13 fixtures pass.", "Per-ID method/command/result/SHA/artifact completeness and physical acceptance remain open.", "PR #66 review open; independent integration review not recorded.", "IN_PROGRESS: checker validates structure; underlying evidence acceptance remains incomplete."),
    ("PKG-R00", "PKG-00 baseline", "PARTIAL", "Not recorded", "Not recorded", "docs/dsp-studio/BASELINE.md<br>docs/dsp-studio/FINAL_IMPLEMENTATION_REPORT.md", "Not recorded; baseline package report does not name an implementation commit.", "Not applicable; no package-scoped test command recorded.", "FINAL_IMPLEMENTATION_REPORT.md marks baseline captured; no package-scoped result artifact mapped.", "Baseline owner, dependency, review, and QA provenance not fully mapped.", "Package-level reviewer not recorded.", "PARTIAL: summary says captured; package evidence metadata incomplete."),
    ("PKG-R01", "PKG-01 workspace/Macro/resolver", "PARTIAL", "Not recorded; PR #55 author/model is not asserted as owner.", "Not recorded", "common/src/main/java/com/shilapi/xcertplay/dsp/DspStudioPersistence.kt<br>common/src/main/java/com/shilapi/xcertplay/dsp/DspProfileRuntime.kt<br>common/src/main/java/com/shilapi/xcertplay/dsp/DspTuningResolver.kt", "PR #55 head 48c90a09a5f0df95a12fb35e7a17058f1e04def9; package mapping is approximate, not explicit in source ledger.", "Package ledger records DspStudioPersistenceTest; exact command not recorded.", "DspStudioPersistenceTest 11 tests PASS per SUBAGENT_PR_LEDGER.md.", "IR-reference race and per-ID evidence remain open.", "PR-level test review recorded; final package review not separately recorded.", "PARTIAL: focused tests reported; final-head/device evidence open."),
    ("PKG-R02", "PKG-02 Native stages", "PARTIAL", "Not recorded", "Not recorded", "shared/src/main/jni/dsp<br>scripts/test_dsp_native.sh", "No package-specific implementation commit recorded.", "bash scripts/test_dsp_native.sh; ASAN_OPTIONS=detect_leaks=0:halt_on_error=1 UBSAN_OPTIONS=halt_on_error=1 bash scripts/test_dsp_native.sh --sanitize", "Native and ASan/UBSan PASS; three-ABI debug link reported PASS in FINAL_IMPLEMENTATION_REPORT.md.", "LeakSanitizer is unavailable on the reported macOS host; default Linux sanitizer and exact package attribution remain gates.", "Project-level native review recorded; package-specific review not recorded.", "PARTIAL: host sanitizer/build evidence reported; package commit/artifact mapping incomplete."),
    ("PKG-R03", "PKG-03 live update", "PARTIAL", "Not recorded; PR #60 model attribution is not an owner record.", "Not recorded", "shared/src/main/java/com/shilapi/xcertplay/media/dsp/DspUpdatePlanner.kt<br>shared/src/main/java/com/shilapi/xcertplay/media/dsp/DspLiveUpdateController.kt", "PR #60 head 7ff2924610adef5cbee8ae0a5648a60f76abdde1.", "CI run #437; package-specific local command not recorded.", "CI #437 host Native, ASan/UBSan, unit, lint/build, JNI/R8 and API 35 instrumentation PASS per SUBAGENT_PR_LEDGER.md.", "Current PR #52 final-head field coverage and target playback remain open.", "Independent PR #60 review reported no findings; final integration review separate.", "PARTIAL: child CI reported pass; final-head/device proof incomplete."),
    ("PKG-R04", "PKG-04 stores/session", "PARTIAL", "Not recorded; PR #55 author/model is not asserted as owner.", "Not recorded", "common/src/main/java/com/shilapi/xcertplay/dsp/DspProfileRuntime.kt<br>common/src/main/java/com/shilapi/xcertplay/dsp/DspImpulseResponseRepository.kt<br>common/src/test/java/com/shilapi/xcertplay/dsp/DspStudioPersistenceTest.kt", "PR #55 head 48c90a09a5f0df95a12fb35e7a17058f1e04def9; exact package mapping not explicit.", "DspStudioPersistenceTest; exact command not recorded.", "DspStudioPersistenceTest 11 tests PASS per SUBAGENT_PR_LEDGER.md.", "IR-reference race and complete per-ID coverage remain open.", "PR-level tests recorded; final package review not separately recorded.", "PARTIAL: selected store/session coverage reported; IR and final-head evidence open."),
    ("PKG-R05", "PKG-05 Macro UI", "PARTIAL", "Not recorded; PR #54 author/model is not asserted as owner.", "Not recorded", "common/src/main/java/com/shilapi/xcertplay/DspMacroStudio.kt<br>common/src/main/java/com/shilapi/xcertplay/DspSettingsScreen.kt<br>common/src/androidTest/java/com/shilapi/xcertplay/DspSettingsScreenTest.kt", "PR #54 head ac7aa0478d621f1e33b6a3acca663b952522e92f.", "DspSettingsUiContractTest; exact command not recorded.", "DspSettingsUiContractTest PASS; API 35 DspSettingsScreenTest 4/4 recorded at ce3c87d7.", "Final-head and physical host geometry acceptance remain open.", "PR-level review exists; final package review not separately recorded.", "PARTIAL: UI tests reported; physical accessibility/geometry QA open."),
    ("PKG-R06", "PKG-06 Smart Loudness", "PARTIAL", "Not recorded; PR #53 author/model is not asserted as owner.", "Not recorded", "common/src/main/java/com/shilapi/xcertplay/dsp/DspVolumeSource.kt<br>common/src/main/java/com/shilapi/xcertplay/dsp/DspSmartLoudnessPane.kt", "PR #53 head 02d79ba2ccd56992b947fbab2e82b80bfe91769e.", "DspVolumeSourceTest and DspTuningResolverTest; exact command not recorded.", "12 focused tests PASS per SUBAGENT_PR_LEDGER.md.", "Target route calibration and OEM trust validation remain NOT_RUN.", "PR-level review exists; final package review not separately recorded.", "PARTIAL: software tests reported; hardware route QA NOT_RUN."),
    ("PKG-R07", "PKG-07 Dynamic Bass", "PARTIAL", "Not recorded; PR #57/#59 authors are not asserted as owners.", "Not recorded", "shared/src/main/jni/dsp/dsp_dynamic_bass.h<br>common/src/main/java/com/shilapi/xcertplay/dsp/DspDynamicBassSettingsPane.kt<br>scripts/test_dsp_native.sh", "PR #57 head 92febd9ade3fc9d2d88de0d76689558a55a189e3; PR #59 head fdebaa9ad543e07d0f673a7706af59e743ffee92.", "bash scripts/test_dsp_native.sh; sanitizer command recorded in FINAL_IMPLEMENTATION_REPORT.md.", "Native and ASan/UBSan PASS for PR #59; PR #57 Compose instrumentation NOT_RUN per SUBAGENT_PR_LEDGER.md.", "Target acoustic/performance evidence and Compose instrumentation remain open.", "Child PR review recorded at project level; final package review not separately recorded.", "PARTIAL: native software evidence exists; device and UI gate open."),
    ("PKG-R08", "PKG-08 live compatibility", "PARTIAL", "Not recorded; PR #58 author/model is not asserted as owner.", "Not recorded", "shared/src/main/java/com/shilapi/xcertplay/media/dsp/DspLiveUpdateController.kt<br>shared/src/main/java/com/shilapi/xcertplay/media/dsp/DspStreamPolicy.kt<br>shared/src/test/java/com/shilapi/xcertplay/media/dsp/DspRendererProductionPathTest.kt", "PR #58 head 864eb36cc516720e6fe1f7bad3249550eddd1866.", "Targeted renderer/JNI tests and Native/ASan suites; exact commands not recorded.", "Targeted renderer/JNI tests, 3-ABI native build, Native and ASan/UBSan PASS per SUBAGENT_PR_LEDGER.md.", "JNI mapping and TS10/K706 A/V/soak remain NOT_RUN.", "PR-level review recorded; final package review not separately recorded.", "PARTIAL: host evidence reported; JNI mapping and hardware QA open."),
    ("PKG-R09", "PKG-09 PEQ/advanced UI", "PARTIAL", "Not recorded; PR #56 author/model is not asserted as owner.", "Not recorded", "common/src/main/java/com/shilapi/xcertplay/dsp/DspEditorPanes.kt<br>common/src/androidTest/java/com/shilapi/xcertplay/dsp/DspEqEditorGraphTest.kt", "PR #56 head 9c278b8078b2055a61e57b8ebebf572e21c2cccc.", "DspEditorPanesTest; exact command not recorded.", "DspEditorPanesTest 4 tests PASS per SUBAGENT_PR_LEDGER.md.", "Actual head-unit layout/touch and full mapped graph assertions remain open.", "PR-level review recorded; final package review not separately recorded.", "PARTIAL: unit coverage reported; physical UI QA NOT_RUN."),
    ("PKG-R10", "PKG-10 integration", "PARTIAL", "Not recorded", "Child PRs #53–#63 and base update are integrated per FINAL_IMPLEMENTATION_REPORT.md.", "docs/dsp-studio/FINAL_IMPLEMENTATION_REPORT.md<br>docs/dsp-studio/TEST_MATRIX.md<br>.github/workflows/android.yml", "Aggregate tested source fe083f4f524fa6ee1038860343e4f100aee8dad6; hosted documentation head 1e4a8962cedcc90fbf505c5f01346408e2842b77.", "./gradlew --no-daemon --max-workers=1 --console=plain :shared:testDebugUnitTest :common:testDebugUnitTest; hosted run #506.", "Shared/Common 1002/1002 at source SHA; run #506 required jobs passed, with noted skipped jobs.", "Per-ID evidence and hardware gates remain open; run #506 did not cover skipped quick/draft-only jobs.", "Project reviews recorded; current integration review remains open.", "PARTIAL: host/CI gates reported; release readiness false until evidence/hardware closes."),
    ("PKG-R11", "Package definition not recorded (PKG-11)", "NOT_STARTED", "Unassigned", "Not recorded", "No package paths recorded; see docs/dsp-studio/FINAL_IMPLEMENTATION_REPORT.md.", "Not recorded", "Not recorded", "No package-specific test result recorded.", "Package scope, owner, dependencies, implementation, and acceptance are undefined in available records.", "Not started", "Not started"),
    ("PKG-R12", "Package definition not recorded (PKG-12)", "NOT_STARTED", "Unassigned", "Not recorded", "No package paths recorded; see docs/dsp-studio/FINAL_IMPLEMENTATION_REPORT.md.", "Not recorded", "Not recorded", "No package-specific test result recorded.", "Package scope, owner, dependencies, implementation, and acceptance are undefined in available records.", "Not started", "Not started"),
]
EVIDENCE_FIELDS = ("Method", "Command", "Result", "Source SHA", "Artifact")
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
        "`PASS` requires a method, exact command, observed result, full 40-character source SHA, and artifact, with no missing fields. `PARTIAL` lists every blank evidence field and describes the acceptance requirement that remains unproven. `NOT_RUN` requires a runbook and a concrete blocker.", "",
        "| ID | Acceptance case | Status | Method | Command | Result | Source SHA | Artifact | Runbook | Blocking reason | Missing fields | What missing |",
        "|---|---|---|---|---|---|---|---|---|---|---|---|",
    ]
    for id_, acceptance, status_gap in source_rows:
        status, gap = status_gap.split("\t", 1)
        acceptance = acceptance.replace("|", "\\|")
        if status == "NOT_RUN":
            runbook = "[RUNBOOK.md](RUNBOOK.md)"
            blocker = gap.replace("|", "/") or "Dedicated device or acceptance run has not been executed."
            missing = "Method, Command, Result, Source SHA, Artifact"
        else:
            runbook = "[RUNBOOK.md](RUNBOOK.md)"
            blocker = gap.replace("|", "/")
            missing = "Method, Command, Result, Source SHA, Artifact"
        what_missing = f"Acceptance remains unproven: {gap}".replace("|", "/")
        lines.append(f"| {id_} | {acceptance} | {status} |  |  |  |  |  | {runbook} | {blocker} | {missing} | {what_missing} |")
    lines.extend(["", "## Work package tracker", "", "This table tracks execution ownership, dependencies, paths, implementation commits, tests, risks, review, and QA separately from acceptance evidence. `NOT_STARTED` means no implementation/package definition is recorded, `IN_PROGRESS` means tracked work remains active, and `PARTIAL` means some implementation or verification exists but the package is not closed. `Not recorded` means the available project records do not identify that field; no owner, dependency, commit, or test is inferred.", "", "| Work ID | Work package | Status | Owner | Dependencies | Relevant paths | Implementation commit | Test command | Test result | Unresolved risk | Review state | QA state |", "|---|---|---|---|---|---|---|---|---|---|---|---|"])
    for package in WORK_PACKAGE_ROWS:
        lines.append("| " + " | ".join(package) + " |")
    lines.extend(["", "## Running the check", "", "Run `python scripts/check_dsp_studio_evidence.py`. The validator compares IDs and acceptance predicates to the canonical detailed matrix, requires exactly 170 IDs and all 25 REM/PKG coverage IDs, rejects duplicates, validates artifact and runbook references, enforces status-specific evidence fields, and requires PARTIAL rows to describe the unproven acceptance requirement.", ""])
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
    rows = table_rows(LEDGER, "| ID | Acceptance case | Status | Method | Command | Result | Source SHA | Artifact | Runbook | Blocking reason | Missing fields | What missing |")
    if not rows:
        return errors + ["tracker evidence table was not found"]
    entries: dict[str, list[str]] = {}
    for row in rows:
        if len(row) != 12:
            errors.append(f"tracker row has {len(row)} columns, expected 12: {row[:1]}")
            continue
        id_, status = row[0], row[2]
        if id_ in entries:
            errors.append(f"duplicate tracker ID {id_}")
        entries[id_] = row
        fields = dict(zip(("ID", "Acceptance", "Status", *EVIDENCE_FIELDS, "Runbook", "Blocking reason", "Missing fields", "What missing"), row))
        if id_ in canonical and fields["Acceptance"] != canonical[id_]:
            errors.append(f"{id_}: acceptance predicate does not match TEST_MATRIX_DETAILED.md")
        if status not in {"PASS", "PARTIAL", "NOT_RUN"}:
            errors.append(f"{id_}: invalid status {status!r}")
            continue
        if status == "PASS":
            for field in EVIDENCE_FIELDS:
                if not fields[field]:
                    errors.append(f"{id_}: PASS requires {field}")
            if fields["Source SHA"] and not SHA_RE.fullmatch(fields["Source SHA"]):
                errors.append(f"{id_}: PASS source SHA must be a full 40-character commit")
            if fields["Missing fields"]:
                errors.append(f"{id_}: PASS must not list missing fields")
            if fields["Artifact"] and not valid_artifact(fields["Artifact"]):
                errors.append(f"{id_}: PASS artifact must resolve to an existing repo file or direct GitHub Actions run URL")
        elif status == "NOT_RUN":
            if not fields["Runbook"] or not fields["Blocking reason"]:
                errors.append(f"{id_}: NOT_RUN requires runbook and blocking reason")
            elif not valid_runbook(fields["Runbook"]):
                errors.append(f"{id_}: runbook reference does not resolve to an existing repo file")
        else:
            if not fields["Runbook"]:
                errors.append(f"{id_}: PARTIAL requires a runbook reference")
            elif not valid_runbook(fields["Runbook"]):
                errors.append(f"{id_}: runbook reference does not resolve to an existing repo file")
            if not fields["What missing"]:
                errors.append(f"{id_}: PARTIAL requires a description of the unproven acceptance requirement")
            missing = [part.strip() for part in fields["Missing fields"].split(",") if part.strip()]
            for field in missing:
                if field not in EVIDENCE_FIELDS:
                    errors.append(f"{id_}: unknown missing field {field!r}")
                elif fields[field]:
                    errors.append(f"{id_}: {field} is populated but listed as missing")
            expected_missing = [field for field in EVIDENCE_FIELDS if not fields[field]]
            if missing != expected_missing:
                errors.append(f"{id_}: Missing fields must exactly list blank evidence fields")
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
