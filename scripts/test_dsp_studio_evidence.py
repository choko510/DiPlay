#!/usr/bin/env python3
"""Small contract fixtures for the DSP Studio evidence ledger validator."""

from __future__ import annotations

import contextlib
import io
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

    def errors(self) -> list[str]:
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            return checker.validate()

    def test_current_ledger_is_consistent(self) -> None:
        self.assertEqual([], self.errors())

    def test_partial_requires_explicit_missing_fields(self) -> None:
        text = self.ledger.read_text(encoding="utf-8")
        lines = text.splitlines()
        for index, line in enumerate(lines):
            if line.startswith("| PH01-T01 |"):
                cells = line.split("|")
                cells[11] = " "
                lines[index] = "|".join(cells)
                break
        self.ledger.write_text("\n".join(lines) + "\n", encoding="utf-8")
        self.assertTrue(any("PARTIAL requires an explicit missing-fields list" in error for error in self.errors()))

    def test_not_run_requires_blocker_and_runbook(self) -> None:
        text = self.ledger.read_text(encoding="utf-8")
        lines = text.splitlines()
        for index, line in enumerate(lines):
            if line.startswith("| PH02-T04 |"):
                cells = line.split("|")
                cells[9] = " "
                cells[10] = " "
                lines[index] = "|".join(cells)
                break
        self.ledger.write_text("\n".join(lines) + "\n", encoding="utf-8")
        self.assertTrue(any("NOT_RUN requires runbook and blocking reason" in error for error in self.errors()))

    def test_pass_requires_all_evidence_fields(self) -> None:
        text = self.ledger.read_text(encoding="utf-8")
        lines = text.splitlines()
        for index, line in enumerate(lines):
            if line.startswith("| PH01-T01 |"):
                cells = line.split("|")
                cells[3] = " PASS "
                cells[7] = " bad-sha "
                lines[index] = "|".join(cells)
                break
        self.ledger.write_text("\n".join(lines) + "\n", encoding="utf-8")
        errors = self.errors()
        self.assertTrue(any("PASS requires Method" in error for error in errors))
        self.assertTrue(any("PASS source SHA must be a full 40-character commit" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
