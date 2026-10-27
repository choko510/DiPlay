#!/usr/bin/env python3
"""Validate the DSP Studio per-ID evidence ledger without inferring results."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MATRIX = ROOT / "docs/dsp-studio/TEST_MATRIX_DETAILED.md"
LEDGER = ROOT / "docs/dsp-studio/REMAINING_FIX_TRACKER.md"
EXPECTED_COVERAGE = {f"REM-{n:02d}" for n in range(1, 13)} | {
    f"PKG-R{n:02d}" for n in range(13)
}
EVIDENCE_FIELDS = ("Method", "Command", "Result", "Source SHA", "Artifact")
SHA_RE = re.compile(r"^[0-9a-f]{40}$", re.IGNORECASE)


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
        cells = [cell.strip().replace("\\|", "|") for cell in line.strip().strip("|").split("|")]
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
        "`PASS` requires a method, exact command, observed result, full 40-character source SHA, and artifact. `PARTIAL` names every missing evidence field. `NOT_RUN` requires a runbook and a concrete blocker.", "",
        "| ID | Acceptance case | Status | Method | Command | Result | Source SHA | Artifact | Runbook | Blocking reason | Missing fields |",
        "|---|---|---|---|---|---|---|---|---|---|---|",
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
        lines.append(f"| {id_} | {acceptance} | {status} |  |  |  |  |  | {runbook} | {blocker} | {missing} |")
    lines.extend(["", "## REM / package coverage", "", "Coverage below is a traceability index to the existing requirement/package summaries. It is not a test result or completion claim.", "", "| Coverage ID | Requirement / package | Disposition | Evidence boundary |", "|---|---|---|---|"])
    for n in range(1, 13):
        lines.append(f"| REM-{n:02d} | REQ-{n:03d} | PARTIAL | Requirement summary only; acceptance details and evidence remain in the 170-row ledger above. |")
    for n in range(13):
        pkg = f"PKG-{n:02d}"
        lines.append(f"| PKG-R{n:02d} | {pkg} | PARTIAL | Package summary only; no per-ID PASS inferred from prior CI. |")
    lines.extend(["", "## Running the check", "", "Run `python scripts/check_dsp_studio_evidence.py`. The validator compares the ledger IDs to the canonical detailed matrix, requires exactly 170 IDs and all 25 REM/PKG coverage IDs, rejects duplicates, and enforces status-specific evidence fields.", ""])
    LEDGER.write_text("\n".join(lines), encoding="utf-8")


def validate() -> list[str]:
    errors: list[str] = []
    ids = matrix_ids()
    if len(ids) != 170:
        errors.append(f"canonical matrix ID count is {len(ids)}, expected 170")
    errors.extend(validate_matrix_counts(ids))
    rows = table_rows(LEDGER, "| ID | Acceptance case | Status | Method | Command | Result | Source SHA | Artifact | Runbook | Blocking reason | Missing fields |")
    if not rows:
        return errors + ["tracker evidence table was not found"]
    entries: dict[str, list[str]] = {}
    for row in rows:
        if len(row) != 11:
            errors.append(f"tracker row has {len(row)} columns, expected 11: {row[:1]}")
            continue
        id_, status = row[0], row[2]
        if id_ in entries:
            errors.append(f"duplicate tracker ID {id_}")
        entries[id_] = row
        fields = dict(zip(("ID", "Acceptance", "Status", *EVIDENCE_FIELDS, "Runbook", "Blocking reason", "Missing fields"), row))
        if status not in {"PASS", "PARTIAL", "NOT_RUN"}:
            errors.append(f"{id_}: invalid status {status!r}")
            continue
        if status == "PASS":
            for field in EVIDENCE_FIELDS:
                if not fields[field]:
                    errors.append(f"{id_}: PASS requires {field}")
            if fields["Source SHA"] and not SHA_RE.fullmatch(fields["Source SHA"]):
                errors.append(f"{id_}: PASS source SHA must be a full 40-character commit")
        elif status == "NOT_RUN":
            if not fields["Runbook"] or not fields["Blocking reason"]:
                errors.append(f"{id_}: NOT_RUN requires runbook and blocking reason")
        else:
            missing = [part.strip() for part in fields["Missing fields"].split(",") if part.strip()]
            if not missing:
                errors.append(f"{id_}: PARTIAL requires an explicit missing-fields list")
            for field in missing:
                if field not in EVIDENCE_FIELDS:
                    errors.append(f"{id_}: unknown missing field {field!r}")
                elif fields[field]:
                    errors.append(f"{id_}: {field} is populated but listed as missing")
    expected = ids
    actual = set(entries)
    if actual != expected:
        errors.append(f"ledger ID mismatch: missing={len(expected-actual)} extra={len(actual-expected)}")
    coverage = table_rows(LEDGER, "| Coverage ID | Requirement / package | Disposition | Evidence boundary |")
    coverage_ids = {row[0] for row in coverage if row}
    if len(coverage_ids) != len(coverage):
        errors.append("duplicate REM/PKG coverage ID")
    if coverage_ids != EXPECTED_COVERAGE:
        errors.append(f"REM/PKG coverage mismatch: missing={sorted(EXPECTED_COVERAGE-coverage_ids)} extra={sorted(coverage_ids-EXPECTED_COVERAGE)}")
    statuses = {status: sum(1 for row in entries.values() if len(row) > 2 and row[2] == status) for status in ("PASS", "PARTIAL", "NOT_RUN")}
    print(f"DSP Studio evidence ledger: {len(entries)} rows; PASS={statuses['PASS']} PARTIAL={statuses['PARTIAL']} NOT_RUN={statuses['NOT_RUN']}; REM/PKG coverage={len(coverage_ids)}/25")
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
