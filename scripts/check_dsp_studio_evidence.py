#!/usr/bin/env python3
"""Validate the DSP Studio per-ID evidence ledger without inferring results."""

from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import datetime
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[1]
MATRIX = ROOT / "docs/dsp-studio/TEST_MATRIX_DETAILED.md"
LEDGER = ROOT / "docs/dsp-studio/REMAINING_FIX_TRACKER.md"
ACTIONS_RUN_REGISTRY = ROOT / "docs/dsp-studio/verified_actions_runs.json"
EXPECTED_COVERAGE = {f"REM-{n:02d}" for n in range(1, 13)} | {
    f"PKG-R{n:02d}" for n in range(13)
}
EXPECTED_WORK_PACKAGE_NAMES = {
    "REM-01": "safe PREAMP HOT ramp",
    "REM-02": "DynamicEQ field/subfield HOT",
    "REM-03": "safe Compressor/Limiter/Seat Trim/Width updates with unsafe DEFER",
    "REM-04": "two-phase Cancel, failure state, retry and race fencing",
    "REM-05": "independent saveable scroll per five tabs",
    "REM-06": "same-source-window transient module A/B level-match",
    "REM-07": "per-ID evidence for 170 acceptance tests",
    "REM-08": "LOCAL_WARM callback cost/overload/perf gate",
    "REM-09": "TS10/K706/iPhone hardware acceptance",
    "REM-10": "OEM/physical volume correlation and Smart Loudness fail-closed",
    "REM-11": "EQ graph mapping/golden/selection/final ACK",
    "REM-12": "full code-head CI gates (not docs-only skipped)",
    "PKG-R00": "PKG-00 baseline",
    "PKG-R01": "Runtime/State",
    "PKG-R02": "Compose UI",
    "PKG-R03": "Graph UI",
    "PKG-R04": "Native HOT/Preamp",
    "PKG-R05": "Native Dynamics",
    "PKG-R06": "Dynamic EQ/Spatial",
    "PKG-R07": "Local Warm performance",
    "PKG-R08": "A/B Level Match",
    "PKG-R09": "Loudness/Bass safety",
    "PKG-R10": "170 evidence",
    "PKG-R11": "CarPlay/device runbook",
    "PKG-R12": "Quality/review/CI",
}
DEFAULT_WORK_PACKAGE_ROWS = [
    (
        work_id,
        name,
        "PARTIAL",
        "Codex direct",
        "Not recorded",
        "Not recorded",
        "Not recorded",
        "Not recorded",
        "Not recorded",
        "Not recorded",
        "Not recorded",
        "Not recorded",
    )
    for work_id, name in EXPECTED_WORK_PACKAGE_NAMES.items()
]
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
GITHUB_ACTIONS_RUN_RE = re.compile(r"^https://github\.com/choko510/DiPlay/actions/runs/(\d+)/?$")
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


def canonical_acceptance_statuses() -> dict[str, str]:
    rows = table_rows(MATRIX, "| ID | Acceptance case | Relevant evidence | Status | Remaining evidence / gap |")
    return {row[0]: row[3] for row in rows if len(row) == 5}


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


def load_actions_run_registry() -> dict[str, dict[str, str]]:
    try:
        payload = json.loads(ACTIONS_RUN_REGISTRY.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    if not isinstance(payload, dict) or payload.get("repository") != "choko510/DiPlay":
        return {}
    runs = payload.get("runs")
    if not isinstance(runs, list):
        return {}
    verified: dict[str, dict[str, str]] = {}
    for run in runs:
        if not isinstance(run, dict):
            continue
        run_id = run.get("id")
        head_sha = run.get("head_sha")
        if (
            isinstance(run_id, int)
            and not isinstance(run_id, bool)
            and isinstance(head_sha, str)
            and SHA_RE.fullmatch(head_sha)
            and run.get("url") == f"https://github.com/choko510/DiPlay/actions/runs/{run_id}"
            and run.get("metadata_source") == f"https://api.github.com/repos/choko510/DiPlay/actions/runs/{run_id}"
            and isinstance(run.get("observed_at"), str)
            and valid_observed_at(run["observed_at"])
            and run.get("status") == "completed"
            and run.get("conclusion") == "success"
        ):
            verified[str(run_id)] = {"head_sha": head_sha.lower()}
    return verified


def valid_artifact(reference: str, source_sha: str) -> bool:
    run_match = GITHUB_ACTIONS_RUN_RE.fullmatch(reference)
    if run_match:
        run = load_actions_run_registry().get(run_match.group(1))
        return bool(run and run["head_sha"] == source_sha.lower())
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
    if LEDGER.exists():
        raise FileExistsError("refusing to overwrite an existing evidence ledger; edit tracked rows explicitly")
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
        "This ledger mirrors all acceptance IDs in [TEST_MATRIX_DETAILED.md](TEST_MATRIX_DETAILED.md). It records evidence independently and does not promote historical suite or CI results. `PASS` is reserved for rows with complete method, command, source, run, artifact, environment, reviewer and timestamp evidence.", "",
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
    lines.extend(["", "## Work package tracker", "", "This table tracks execution ownership, dependencies, paths, implementation commits, tests, risks, review, and QA separately from acceptance evidence. Newly initialized package rows use the current Codex owner and explicit `Not recorded` fields; historical author/model attribution belongs in the PR ledger.", "", "| Work ID | Work package | Status | Owner | Dependencies | Relevant paths | Implementation commit | Test command | Test result | Unresolved risk | Review state | QA state |", "|---|---|---|---|---|---|---|---|---|---|---|---|"])
    for package in DEFAULT_WORK_PACKAGE_ROWS:
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
    canonical_statuses = canonical_acceptance_statuses()
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
        if id_ in canonical_statuses and status != canonical_statuses[id_]:
            errors.append(
                f"{id_}: tracker status {status!r} does not match TEST_MATRIX_DETAILED.md status {canonical_statuses[id_]!r}"
            )
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
            if fields["ArtifactPath/URL"] and not valid_artifact(fields["ArtifactPath/URL"], fields["GitSHA"]):
                errors.append(f"{id_}: artifact must resolve to a repo file or a registry-verified successful Actions run for GitSHA")
        elif status == "NOT_RUN":
            if not fields["Runbook"] or not fields["Blocking reason"]:
                errors.append(f"{id_}: NOT_RUN requires runbook and blocking reason")
            elif not valid_runbook(fields["Runbook"]):
                errors.append(f"{id_}: runbook reference does not resolve to an existing repo file")
            if not fields["RemainingRisk"]:
                errors.append(f"{id_}: NOT_RUN requires RemainingRisk")
            if fields["ArtifactPath/URL"] and not valid_artifact(fields["ArtifactPath/URL"], fields["GitSHA"]):
                errors.append(f"{id_}: artifact must resolve to a repo file or a registry-verified successful Actions run for GitSHA")
        else:
            if not fields["Runbook"]:
                errors.append(f"{id_}: PARTIAL requires a runbook reference")
            elif not valid_runbook(fields["Runbook"]):
                errors.append(f"{id_}: runbook reference does not resolve to an existing repo file")
            if not fields["Unproven predicate"]:
                errors.append(f"{id_}: PARTIAL requires a description of the unproven acceptance predicate")
            if not fields["RemainingRisk"]:
                errors.append(f"{id_}: PARTIAL requires RemainingRisk")
            if fields["ArtifactPath/URL"] and not valid_artifact(fields["ArtifactPath/URL"], fields["GitSHA"]):
                errors.append(f"{id_}: artifact must resolve to a repo file or a registry-verified successful Actions run for GitSHA")
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
        try:
            create_tracker()
        except FileExistsError as error:
            print(str(error), file=sys.stderr)
            return 2
    errors = validate()
    for error in errors:
        print(f"ERROR: {error}", file=sys.stderr)
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
