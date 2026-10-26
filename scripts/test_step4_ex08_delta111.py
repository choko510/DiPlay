#!/usr/bin/env python3
"""Regression checks for the fixed-range EX-08 delta-111 overlay."""

from __future__ import annotations

import csv
import io
import json
import shutil
import tarfile
import tempfile
import unittest
from pathlib import Path

import generate_step4_ex08_inventory as inventory
from step4_ex08_delta111 import (
    BASE_SHA,
    EXPECTED_COMMITS,
    EXPECTED_MERGE_COMMITS,
    EXPECTED_SOURCE_COMMITS,
    HEAD_SHA,
    OverlayError,
    load_and_validate_inputs,
    require_legacy_prefix,
    serialize_rows,
    sha256,
    validate_commit_record,
    validate_review_override,
    validate_source_commits,
)


ROOT = Path(__file__).resolve().parents[1]
OVERLAY_DIR = ROOT / "docs/step4/ex08"
LEDGER = ROOT / "docs/step4/EX08_COMMIT_LEDGER.csv"


def read_patch_map() -> dict[str, bytes]:
    meta = json.loads((OVERLAY_DIR / "upstream_delta_111.meta.json").read_text(encoding="utf-8"))
    with tarfile.open(OVERLAY_DIR / meta["patch_archive"], mode="r:gz") as archive:
        patches = {}
        for member in archive.getmembers():
            source = archive.extractfile(member)
            if source is None:
                raise AssertionError(f"missing test patch: {member.name}")
            patches[member.name] = source.read()
        return patches


def temporary_overlay_copy() -> Path:
    temporary = Path(tempfile.mkdtemp(prefix="ex08-delta111-test-"))
    shutil.copytree(OVERLAY_DIR, temporary / "docs/step4/ex08")
    return temporary


class Delta111InventoryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.meta, cls.commits, cls.overrides = load_and_validate_inputs(ROOT)
        cls.patches = read_patch_map()
        with LEDGER.open(encoding="utf-8", newline="") as source:
            reader = csv.DictReader(source)
            cls.fieldnames = list(reader.fieldnames or [])
            cls.rows = list(reader)
        cls.legacy_rows = cls.rows[:1289]
        cls.overlay_rows = cls.rows[1289:]
        cls.legacy_bytes = serialize_rows(cls.legacy_rows, cls.fieldnames)

    def test_01_legacy_all_fields_order_and_bytes_match_frozen_checksum(self) -> None:
        baseline = json.loads((OVERLAY_DIR / "legacy_1289_baseline.json").read_text(encoding="utf-8"))
        self.assertEqual(1289, len(self.legacy_rows))
        self.assertEqual(baseline["sha256"], sha256(self.legacy_bytes))
        self.assertEqual(self.legacy_bytes, LEDGER.read_bytes()[:len(self.legacy_bytes)])

    def test_02_overlay_has_111_unique_commit_shas(self) -> None:
        shas = [row["commit_sha"] for row in self.overlay_rows]
        self.assertEqual(EXPECTED_COMMITS, len(shas))
        self.assertEqual(EXPECTED_COMMITS, len(set(shas)))

    def test_03_legacy_and_overlay_have_no_overlap_and_total_1400(self) -> None:
        old_shas = {row["commit_sha"] for row in self.legacy_rows}
        new_shas = {row["commit_sha"] for row in self.overlay_rows}
        self.assertFalse(old_shas.intersection(new_shas))
        self.assertEqual(1400, len(old_shas.union(new_shas)))

    def test_04_parent_count_proves_60_source_and_51_merge_commits(self) -> None:
        source = [row for row in self.commits if len(row["parent_shas"]) == 1]
        merges = [row for row in self.commits if len(row["parent_shas"]) > 1]
        self.assertEqual(EXPECTED_SOURCE_COMMITS, len(source))
        self.assertEqual(EXPECTED_MERGE_COMMITS, len(merges))
        self.assertTrue(all(row["is_merge"] for row in merges))

    def test_05_metadata_pins_the_reviewed_upstream_range(self) -> None:
        self.assertEqual(BASE_SHA, self.meta["base_sha"])
        self.assertEqual(HEAD_SHA, self.meta["head_sha"])
        self.assertIn(f"{BASE_SHA}...{HEAD_SHA}", self.meta["compare_url"])

    def test_06_merge_without_parent_is_rejected(self) -> None:
        merge = next(row for row in self.commits if row["is_merge"])
        malformed = dict(merge, parent_shas=[], is_merge=False)
        with self.assertRaises(OverlayError):
            validate_commit_record(merge["local_index"], malformed, self.patches)

    def test_07_need_evidence_requires_missing_evidence(self) -> None:
        override = dict(self.overrides[0], missing_evidence=[])
        with self.assertRaises(OverlayError):
            validate_review_override(override)

    def test_08_adapt_and_fork_superior_require_proof(self) -> None:
        for disposition in ("ADAPT", "FORK_SUPERIOR"):
            override = dict(self.overrides[0], disposition=disposition, proof=[])
            with self.subTest(disposition=disposition), self.assertRaises(OverlayError):
                validate_review_override(override)

    def test_09_patch_hash_detects_different_diff_bytes(self) -> None:
        record = self.commits[0]
        changed_patches = dict(self.patches)
        changed_patches[record["patch_file"]] += b"different diff bytes"
        with self.assertRaises(OverlayError):
            validate_commit_record(1, record, changed_patches)

    def test_10_generator_uses_fixed_inputs_instead_of_latest_upstream_main(self) -> None:
        self.assertEqual("git rev-list --reverse <base>..<head>", self.meta["range_method"])
        self.assertEqual(HEAD_SHA, self.commits[-1]["commit_sha"])
        self.assertNotIn("main", self.meta["range_method"])

    def test_11_csv_regeneration_is_bitwise_deterministic(self) -> None:
        first = serialize_rows(self.rows, self.fieldnames)
        second = serialize_rows(self.rows, self.fieldnames)
        self.assertEqual(first, second)

    def test_12_unknown_status_vocabulary_is_rejected(self) -> None:
        override = dict(self.overrides[0], disposition="MAYBE")
        with self.assertRaises(OverlayError):
            validate_review_override(override)

    def test_13_inserting_overlay_data_before_legacy_rows_is_rejected(self) -> None:
        inserted = self.legacy_bytes[:100] + b"unexpected row\n" + self.legacy_bytes[100:]
        with self.assertRaises(OverlayError):
            require_legacy_prefix(self.legacy_bytes, inserted)

    def test_14_changed_review_overrides_without_digest_update_fail(self) -> None:
        temporary = temporary_overlay_copy()
        self.addCleanup(shutil.rmtree, temporary)
        override_path = temporary / "docs/step4/ex08/review_overrides_111.json"
        overrides = json.loads(override_path.read_text(encoding="utf-8"))
        overrides[0]["decision_reason"] += " edited without review digest"
        override_path.write_text(json.dumps(overrides), encoding="utf-8")
        with self.assertRaisesRegex(OverlayError, "pinned digest"):
            load_and_validate_inputs(temporary)

    def test_15_docs_only_ci_run_keeps_android_runtime_skipped(self) -> None:
        matrix = (ROOT / "docs/step4/TEST_MATRIX.md").read_text(encoding="utf-8")
        gates = (ROOT / "docs/step4/REMAINING_GATES.md").read_text(encoding="utf-8")
        self.assertIn("run #396", matrix)
        self.assertIn("unit/native tests", matrix)
        self.assertIn("were skipped by docs-only classification", matrix)
        self.assertIn("Current #396 intentionally skipped runtime jobs", gates)

    def test_16_changed_source_manifest_without_digest_update_fails(self) -> None:
        temporary = temporary_overlay_copy()
        self.addCleanup(shutil.rmtree, temporary)
        commits_path = temporary / "docs/step4/ex08/upstream_delta_111.commits.jsonl"
        contents = commits_path.read_bytes()
        commits_path.write_bytes(contents.replace(b"local_index", b"local_idx", 1))
        with self.assertRaisesRegex(OverlayError, "metadata digest mismatch"):
            load_and_validate_inputs(temporary)

    def test_17_legacy_prefix_check_is_independent_from_full_csv_header_count(self) -> None:
        require_legacy_prefix(self.legacy_bytes, LEDGER.read_bytes())
        self.assertEqual(1400, len(self.rows))


if __name__ == "__main__":
    unittest.main(verbosity=2)
