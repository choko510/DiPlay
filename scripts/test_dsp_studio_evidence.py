#!/usr/bin/env python3
"""Small contract fixtures for the DSP Studio evidence ledger validator."""

from __future__ import annotations

import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path

from scripts import check_dsp_studio_evidence as checker


class EvidenceLedgerContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.original = checker.LEDGER
        self.addCleanup(setattr, checker, "LEDGER", self.original)
        self.ledger = Path(self.temp.name) / "tracker.md"
        self.ledger.write_text(self.original.read_text(encoding="utf-8"), encoding="utf-8")
        checker.LEDGER = self.ledger
        self.original_actions_registry = checker.ACTIONS_RUN_REGISTRY
        self.addCleanup(setattr, checker, "ACTIONS_RUN_REGISTRY", self.original_actions_registry)
        self.actions_registry = Path(self.temp.name) / "verified_actions_runs.json"
        self.actions_registry.write_text(json.dumps({
            "repository": "choko510/DiPlay",
            "runs": [
                {
                    "id": 40686737568,
                    "url": "https://github.com/choko510/DiPlay/actions/runs/40686737568",
                    "metadata_source": "https://api.github.com/repos/choko510/DiPlay/actions/runs/40686737568",
                    "status": "completed",
                    "conclusion": "success",
                    "head_sha": "7aa7ba41a1c4f3b97c44d0f305a72276219e7847",
                    "observed_at": "2026-10-28T12:26:43Z",
                },
                {
                    "id": 40670466020,
                    "url": "https://github.com/choko510/DiPlay/actions/runs/40670466020",
                    "metadata_source": "https://api.github.com/repos/choko510/DiPlay/actions/runs/40670466020",
                    "status": "completed",
                    "conclusion": "success",
                    "head_sha": "e4f6c379f8d75f738a0c0859847c1a194b890e3a",
                    "observed_at": "2026-10-28T10:27:59Z",
                },
                {
                    "id": 40667529246,
                    "url": "https://github.com/choko510/DiPlay/actions/runs/40667529246",
                    "metadata_source": "https://api.github.com/repos/choko510/DiPlay/actions/runs/40667529246",
                    "status": "completed",
                    "conclusion": "success",
                    "head_sha": "8cfce9139cac481e0879fc2dcbb3558b9761d90a",
                    "observed_at": "2026-10-28T10:27:59Z",
                },
                {
                    "id": 123,
                    "url": "https://github.com/choko510/DiPlay/actions/runs/123",
                    "metadata_source": "https://api.github.com/repos/choko510/DiPlay/actions/runs/123",
                    "status": "completed",
                    "conclusion": "success",
                    "head_sha": "0123456789abcdef0123456789abcdef01234567",
                    "observed_at": "2026-10-28T00:00:00Z",
                },
                {
                    "id": 40631709640,
                    "url": "https://github.com/choko510/DiPlay/actions/runs/40631709640",
                    "metadata_source": "https://api.github.com/repos/choko510/DiPlay/actions/runs/40631709640",
                    "status": "completed",
                    "conclusion": "success",
                    "head_sha": "b3b1b595773758200464ee2da1e0958d6f4496c6",
                    "observed_at": "2026-10-28T04:27:23Z",
                },
                {
                    "id": 40625689851,
                    "url": "https://github.com/choko510/DiPlay/actions/runs/40625689851",
                    "metadata_source": "https://api.github.com/repos/choko510/DiPlay/actions/runs/40625689851",
                    "status": "completed",
                    "conclusion": "success",
                    "head_sha": "bd381429f6c8db3707c28928a705de5890a253d6",
                    "observed_at": "2026-10-28T03:28:48Z",
                },
            ],
        }), encoding="utf-8")
        checker.ACTIONS_RUN_REGISTRY = self.actions_registry

    def errors(self) -> list[str]:
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            return checker.validate()

    def update_row(self, row_id: str, updates: dict[str, str]) -> None:
        lines = self.ledger.read_text(encoding="utf-8").splitlines()
        header = checker.split_table_cells(next(line for line in lines if line.strip() == checker.TRACKER_HEADER))
        for index, line in enumerate(lines):
            cells = checker.split_table_cells(line) if line.startswith("|") else []
            if cells and cells[0] == row_id:
                for column, value in updates.items():
                    cells[header.index(column)] = value
                lines[index] = "| " + " | ".join(cells) + " |"
                break
        else:
            self.fail(f"fixture row {row_id} was not found")
        self.ledger.write_text("\n".join(lines) + "\n", encoding="utf-8")

    def complete_pass_values(self, artifact: str = "docs/dsp-studio/RUNBOOK.md") -> dict[str, str]:
        return {
            "Status": "PASS",
            "TestClass.Method": "ExampleTest.test_acceptance_case",
            "Assertion(s)": "assertTrue(result.accepted)",
            "ExecutionCommand": "python -m unittest example_test.py",
            "TestType": "unit",
            "GitSHA": "0123456789abcdef0123456789abcdef01234567",
            "CI_Run": "https://github.com/choko510/DiPlay/actions/runs/123",
            "ArtifactPath/URL": artifact,
            "Result": "PASS: assertion succeeded",
            "Environment(API/ABI/OS/device)": "host x86_64 Linux",
            "Reviewer": "fixture-reviewer",
            "LastObservedAt": "2026-10-28T12:00:00Z",
            "RemainingRisk": "None recorded",
            "Missing evidence": "",
            "Unproven predicate": "",
        }

    def test_current_ledger_is_consistent(self) -> None:
        self.assertEqual([], self.errors())

    def test_tracker_status_must_match_canonical_matrix(self) -> None:
        self.update_row("PH02-T02", {"Status": "PARTIAL"})
        self.assertTrue(any("does not match TEST_MATRIX_DETAILED.md status 'PASS'" in error for error in self.errors()))

    def test_partial_requires_explicit_missing_fields(self) -> None:
        self.update_row("PH01-T09", {"Missing evidence": ""})
        self.assertTrue(any("Missing evidence must exactly list blank evidence fields" in error for error in self.errors()))

    def test_not_run_requires_blocker_and_runbook(self) -> None:
        text = self.ledger.read_text(encoding="utf-8")
        lines = text.splitlines()
        for index, line in enumerate(lines):
            if line.startswith("| PH02-T05 |"):
                cells = checker.split_table_cells(line)
                header = checker.split_table_cells(next(item for item in lines if item.strip() == checker.TRACKER_HEADER))
                cells[header.index("Runbook")] = " "
                cells[header.index("Blocking reason")] = " "
                lines[index] = "| " + " | ".join(cells) + " |"
                break
        self.ledger.write_text("\n".join(lines) + "\n", encoding="utf-8")
        self.assertTrue(any("NOT_RUN requires runbook and blocking reason" in error for error in self.errors()))

    def test_pass_requires_all_evidence_fields(self) -> None:
        self.update_row("PH01-T09", {"Status": "PASS", "GitSHA": "bad-sha"})
        errors = self.errors()
        self.assertTrue(any("PASS requires TestClass.Method" in error for error in errors))
        self.assertTrue(any("PASS requires Assertion(s)" in error for error in errors))
        self.assertTrue(any("GitSHA must be a full 40-character commit" in error for error in errors))

    def test_pass_requires_each_schema_evidence_field(self) -> None:
        initial = self.ledger.read_text(encoding="utf-8")
        for field in checker.EVIDENCE_FIELDS:
            self.ledger.write_text(initial, encoding="utf-8")
            values = self.complete_pass_values()
            values[field] = ""
            self.update_row("PH01-T09", values)
            self.assertTrue(any(f"PASS requires {field}" in error for error in self.errors()), field)

    def test_pass_requires_artifact_reference(self) -> None:
        values = self.complete_pass_values(artifact="")
        self.update_row("PH01-T09", values)
        self.assertTrue(any("PASS requires ArtifactPath/URL" in error for error in self.errors()))

    def test_pass_rejects_nonexistent_artifact(self) -> None:
        self.update_row("PH01-T09", self.complete_pass_values(artifact="docs/dsp-studio/missing-artifact.xml"))
        self.assertTrue(any("artifact must resolve" in error for error in self.errors()))

    def test_pass_rejects_missing_fields_list(self) -> None:
        values = self.complete_pass_values()
        values["Missing evidence"] = "TestClass.Method"
        self.update_row("PH01-T09", values)
        errors = self.errors()
        self.assertTrue(any("PASS must not list missing evidence" in error for error in errors))
        self.assertFalse(any("PASS artifact must resolve" in error for error in errors))

    def test_pass_rejects_actions_run_url_for_another_repo(self) -> None:
        self.update_row("PH01-T09", self.complete_pass_values(artifact="https://github.com/acme/repo/actions/runs/123"))
        self.assertTrue(any("artifact must resolve" in error for error in self.errors()))

    def test_pass_accepts_actions_run_url_for_this_repo(self) -> None:
        self.update_row("PH02-T02", self.complete_pass_values(artifact="https://github.com/choko510/DiPlay/actions/runs/123"))
        self.assertEqual([], self.errors())

    def test_actions_run_artifact_rejects_unregistered_run(self) -> None:
        self.update_row("PH01-T09", self.complete_pass_values(artifact="https://github.com/choko510/DiPlay/actions/runs/999"))
        self.assertTrue(any("registry-verified successful Actions run" in error for error in self.errors()))

    def test_actions_run_artifact_rejects_sha_mismatch(self) -> None:
        values = self.complete_pass_values(artifact="https://github.com/choko510/DiPlay/actions/runs/123")
        values["GitSHA"] = "abcdef0123456789abcdef0123456789abcdef01"
        self.update_row("PH01-T09", values)
        self.assertTrue(any("registry-verified successful Actions run" in error for error in self.errors()))

    def test_actions_run_artifact_rejects_incomplete_or_unsuccessful_run(self) -> None:
        for status, conclusion in (("in_progress", None), ("completed", "failure")):
            self.actions_registry.write_text(json.dumps({
                "repository": "choko510/DiPlay",
                "runs": [{
                    "id": 123,
                    "url": "https://github.com/choko510/DiPlay/actions/runs/123",
                    "metadata_source": "https://api.github.com/repos/choko510/DiPlay/actions/runs/123",
                    "status": status,
                    "conclusion": conclusion,
                    "head_sha": "0123456789abcdef0123456789abcdef01234567",
                    "observed_at": "2026-10-28T00:00:00Z",
                }],
            }), encoding="utf-8")
            self.update_row("PH01-T09", self.complete_pass_values(artifact="https://github.com/choko510/DiPlay/actions/runs/123"))
            self.assertTrue(any("registry-verified successful Actions run" in error for error in self.errors()))

    def test_populated_artifacts_are_validated_for_partial_and_not_run(self) -> None:
        invalid = "https://github.com/choko510/DiPlay/actions/runs/999"
        self.update_row("PH01-T09", {"ArtifactPath/URL": invalid})
        self.update_row("PH02-T04", {"ArtifactPath/URL": invalid})
        errors = self.errors()
        self.assertTrue(any("PH01-T09: artifact must resolve" in error for error in errors))
        self.assertTrue(any("PH02-T04: artifact must resolve" in error for error in errors))

    def test_partial_rejects_invalid_runbook_reference(self) -> None:
        self.update_row("PH01-T09", {"Runbook": "[missing](missing-runbook.md)"})
        self.assertTrue(any("runbook reference does not resolve" in error for error in self.errors()))

    def test_acceptance_predicate_must_match_canonical_matrix(self) -> None:
        self.update_row("PH01-T09", {"AcceptancePredicate": "different acceptance predicate"})
        self.assertTrue(any("acceptance predicate does not match" in error for error in self.errors()))

    def test_partial_with_complete_evidence_requires_what_missing(self) -> None:
        values = self.complete_pass_values()
        values["Status"] = "PARTIAL"
        values["Unproven predicate"] = ""
        self.update_row("PH01-T09", values)
        self.assertTrue(any("PARTIAL requires a description of the unproven acceptance predicate" in error for error in self.errors()))

    def test_partial_with_complete_evidence_and_what_missing_is_valid(self) -> None:
        values = self.complete_pass_values()
        values["Status"] = "PARTIAL"
        values["RemainingRisk"] = "UI recreation path remains untested."
        values["Unproven predicate"] = "The UI recreation path is not asserted by this test."
        self.update_row("PH01-T09", values)
        self.assertEqual([], self.errors())

    def test_pass_requires_remaining_risk_field(self) -> None:
        values = self.complete_pass_values()
        values["RemainingRisk"] = ""
        self.update_row("PH01-T09", values)
        self.assertTrue(any("PASS requires RemainingRisk" in error for error in self.errors()))

    def test_pass_rejects_timezone_free_last_observed_at(self) -> None:
        values = self.complete_pass_values()
        values["LastObservedAt"] = "2026-10-28T12:00:00"
        self.update_row("PH01-T09", values)
        self.assertTrue(any("LastObservedAt must be an ISO-8601 timestamp with timezone" in error for error in self.errors()))

    def test_not_run_rejects_invalid_runbook_reference(self) -> None:
        self.update_row("PH02-T04", {"Runbook": "https://example.invalid/runbook", "Blocking reason": "No device session executed."})
        self.assertTrue(any("runbook reference does not resolve" in error for error in self.errors()))

    def test_partial_missing_evidence_lists_all_blank_fields(self) -> None:
        self.update_row("PH01-T09", {"Missing evidence": "TestClass.Method, Assertion(s)"})
        errors = self.errors()
        self.assertTrue(any("Missing evidence must exactly list blank evidence fields" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
