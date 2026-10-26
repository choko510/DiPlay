"""Validation and CSV projection for the fixed EX-08 delta-111 audit overlay."""

from __future__ import annotations

import csv
import hashlib
import io
import json
import re
import tarfile
from pathlib import Path
from typing import Any


BASE_SHA = "6d3b8a8ce927cb4b115b8c3881f17619433e93b1"
HEAD_SHA = "143f4674b60b564a16baae5110b3b90fb7277631"
EXPECTED_COMMITS = 111
EXPECTED_SOURCE_COMMITS = 60
EXPECTED_MERGE_COMMITS = 51
EXPECTED_FINAL_TREE_PATHS = 117
EXPECTED_FINAL_TREE_ADDITIONS = 4357
EXPECTED_FINAL_TREE_DELETIONS = 376
DISPOSITIONS = {"ADAPT", "FORK_SUPERIOR", "SKIP_OEM", "REJECT_UNSAFE", "NEED_EVIDENCE"}
REVIEW_STATES = {"REVIEWED", "NEED_EVIDENCE"}
MERGE_ROLES = {"SOURCE", "DUPLICATE_MERGE", "CONFLICT_RESOLUTION", "OTHER_MERGE"}
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
COMMIT_SHA_RE = re.compile(r"^[0-9a-f]{40}$")


class OverlayError(ValueError):
    """Raised when a checked-in EX-08 overlay input is inconsistent."""


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _read_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise OverlayError(f"cannot read {path}: {error}") from error
    if not isinstance(value, dict):
        raise OverlayError(f"expected a JSON object in {path}")
    return value


def _read_jsonl(data: bytes, path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    try:
        for line_number, line in enumerate(data.decode("utf-8").splitlines(), start=1):
            if line.strip():
                item = json.loads(line)
                if not isinstance(item, dict):
                    raise OverlayError(f"{path}:{line_number}: expected a JSON object")
                rows.append(item)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise OverlayError(f"invalid JSONL in {path}: {error}") from error
    return rows


def validate_commit_record(
    index: int,
    commit: dict[str, Any],
    patch_files: dict[str, bytes],
) -> bool:
    sha_value = commit.get("commit_sha")
    parents = commit.get("parent_shas")
    if commit.get("local_index") != index or not isinstance(sha_value, str) or not COMMIT_SHA_RE.fullmatch(sha_value):
        raise OverlayError(f"invalid delta-111 SHA or order at local index {index}")
    if not isinstance(parents, list) or any(
        not isinstance(parent, str) or not COMMIT_SHA_RE.fullmatch(parent) for parent in parents
    ):
        raise OverlayError(f"invalid parent list for {sha_value}")
    is_merge = len(parents) > 1
    if len(parents) == 0 or commit.get("is_merge") is not is_merge:
        raise OverlayError(f"parent count does not match merge classification for {sha_value}")
    role = commit.get("merge_role")
    if role not in MERGE_ROLES or (not is_merge and role != "SOURCE"):
        raise OverlayError(f"invalid merge role for {sha_value}")
    changed_paths = commit.get("changed_paths")
    if not isinstance(changed_paths, list) or any(not isinstance(path, str) or not path for path in changed_paths):
        raise OverlayError(f"invalid changed paths for {sha_value}")
    patch_file = commit.get("patch_file")
    patch_digest = commit.get("patch_sha256")
    patch_size = commit.get("patch_bytes")
    if not isinstance(patch_file, str) or not SHA256_RE.fullmatch(str(patch_digest)):
        raise OverlayError(f"invalid patch metadata for {sha_value}")
    if not isinstance(patch_size, int) or patch_size < 0:
        raise OverlayError(f"invalid patch size for {sha_value}")
    patch = patch_files.get(patch_file)
    if patch is None or len(patch) != patch_size or sha256(patch) != patch_digest:
        raise OverlayError(f"first-parent patch digest mismatch for {sha_value}")
    if is_merge and patch_size == 0 and role != "DUPLICATE_MERGE":
        raise OverlayError(f"empty first-parent merge must be classified DUPLICATE_MERGE: {sha_value}")
    if not is_merge and role != "SOURCE":
        raise OverlayError(f"non-merge commit must have SOURCE merge_role: {sha_value}")
    return is_merge


def validate_source_commits(
    commits: list[dict[str, Any]],
    patch_files: dict[str, bytes],
    legacy_shas: set[str] | None = None,
) -> tuple[list[str], int]:
    if len(commits) != EXPECTED_COMMITS:
        raise OverlayError(f"expected 111 upstream commits, found {len(commits)}")
    sha_values: list[str] = []
    merge_count = 0
    for index, commit in enumerate(commits, start=1):
        merge_count += int(validate_commit_record(index, commit, patch_files))
        sha_values.append(commit["commit_sha"])
    if len(set(sha_values)) != EXPECTED_COMMITS:
        raise OverlayError("delta-111 commit SHAs are not unique")
    if merge_count != EXPECTED_MERGE_COMMITS:
        raise OverlayError(f"expected 51 Merge commits, found {merge_count}")
    if len(patch_files) != EXPECTED_COMMITS:
        raise OverlayError("patch archive must contain exactly one patch for each source SHA")
    if legacy_shas is not None and set(sha_values).intersection(legacy_shas):
        raise OverlayError("delta-111 SHAs overlap the frozen legacy catalog")
    return sha_values, merge_count


def validate_review_overrides(overrides: list[dict[str, Any]], sha_values: list[str]) -> None:
    override_shas = [row.get("commit_sha") for row in overrides]
    if len(overrides) != EXPECTED_COMMITS or set(override_shas) != set(sha_values) or len(set(override_shas)) != len(overrides):
        raise OverlayError("review overrides must contain exactly one row for each fixed source SHA")
    for override in overrides:
        validate_review_override(override)


def load_and_validate_inputs(
    root: Path,
    legacy_shas: set[str] | None = None,
) -> tuple[dict[str, Any], list[dict[str, Any]], list[dict[str, Any]]]:
    """Load frozen source metadata, patch bytes, and pinned human-review overrides."""
    overlay_dir = root / "docs/step4/ex08"
    meta_path = overlay_dir / "upstream_delta_111.meta.json"
    commits_path = overlay_dir / "upstream_delta_111.commits.jsonl"
    overrides_path = overlay_dir / "review_overrides_111.json"
    meta = _read_json(meta_path)

    if meta.get("schema_version") != 1:
        raise OverlayError("unsupported delta-111 metadata schema_version")
    if meta.get("base_sha") != BASE_SHA or meta.get("head_sha") != HEAD_SHA:
        raise OverlayError("delta-111 metadata does not use the fixed upstream range")
    if meta.get("range_method") != "git rev-list --reverse <base>..<head>":
        raise OverlayError("delta-111 range method changed")
    if meta.get("final_tree_changed_path_count") != EXPECTED_FINAL_TREE_PATHS:
        raise OverlayError("fixed upstream tree path count is not 117")
    if meta.get("final_tree_additions") != EXPECTED_FINAL_TREE_ADDITIONS or meta.get("final_tree_deletions") != EXPECTED_FINAL_TREE_DELETIONS:
        raise OverlayError("fixed upstream final-tree diff must remain +4357/-376")

    commits_bytes = commits_path.read_bytes()
    if sha256(commits_bytes) != meta.get("commits_jsonl_sha256"):
        raise OverlayError("delta-111 commit metadata digest mismatch")
    commits = _read_jsonl(commits_bytes, commits_path)
    if len(commits) != EXPECTED_COMMITS:
        raise OverlayError(f"expected 111 upstream commits, found {len(commits)}")

    overrides_bytes = overrides_path.read_bytes()
    if sha256(overrides_bytes) != meta.get("review_overrides_sha256"):
        raise OverlayError("review_overrides_111.json changed without updating its pinned digest")
    try:
        overrides_value = json.loads(overrides_bytes.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise OverlayError(f"invalid review overrides JSON: {error}") from error
    if not isinstance(overrides_value, list) or any(not isinstance(row, dict) for row in overrides_value):
        raise OverlayError("review_overrides_111.json must be an array of objects")
    overrides = overrides_value

    patch_archive_name = meta.get("patch_archive")
    if not isinstance(patch_archive_name, str) or not patch_archive_name:
        raise OverlayError("delta-111 patch archive name is missing")
    archive_path = overlay_dir / patch_archive_name
    archive_bytes = archive_path.read_bytes()
    if sha256(archive_bytes) != meta.get("patch_archive_sha256"):
        raise OverlayError("delta-111 patch archive digest mismatch")

    sha_values: list[str] = []
    merge_count = 0
    patch_files: dict[str, bytes] = {}
    try:
        with tarfile.open(fileobj=io.BytesIO(archive_bytes), mode="r:gz") as archive:
            for member in archive.getmembers():
                if not member.isfile() or member.name.startswith("/") or ".." in Path(member.name).parts:
                    raise OverlayError("patch archive contains a non-file or unsafe path")
                source = archive.extractfile(member)
                if source is None or member.name in patch_files:
                    raise OverlayError("patch archive has a missing or duplicate entry")
                patch_files[member.name] = source.read()
    except (tarfile.TarError, OSError) as error:
        raise OverlayError(f"invalid delta-111 patch archive: {error}") from error
    sha_values, merge_count = validate_source_commits(commits, patch_files, legacy_shas)
    validate_review_overrides(overrides, sha_values)

    if meta.get("source_commit_count") != EXPECTED_COMMITS:
        raise OverlayError("metadata source commit count does not match the fixed range")
    if meta.get("non_merge_commit_count") != EXPECTED_SOURCE_COMMITS:
        raise OverlayError("metadata must record 60 non-merge source commits")
    if meta.get("merge_commit_count") != EXPECTED_MERGE_COMMITS:
        raise OverlayError("metadata must record 51 merge commits")
    return meta, commits, overrides


def validate_review_override(override: dict[str, Any]) -> None:
    sha_value = override.get("commit_sha")
    if not isinstance(sha_value, str) or not COMMIT_SHA_RE.fullmatch(sha_value):
        raise OverlayError("review override has an invalid commit_sha")
    if override.get("current_review_state") not in REVIEW_STATES:
        raise OverlayError(f"unknown current_review_state for {sha_value}")
    disposition = override.get("disposition")
    if disposition not in DISPOSITIONS:
        raise OverlayError(f"unknown disposition for {sha_value}")
    for field in ("decision_reason", "test_evidence", "related_step4", "rollback_plan", "fork_owner_file"):
        if not isinstance(override.get(field), str) or not override[field].strip():
            raise OverlayError(f"missing {field} for {sha_value}")
    proof = override.get("proof")
    missing = override.get("missing_evidence")
    if not isinstance(proof, list) or any(not isinstance(item, str) or not item.strip() for item in proof):
        raise OverlayError(f"invalid proof list for {sha_value}")
    if not isinstance(missing, list) or any(not isinstance(item, str) or not item.strip() for item in missing):
        raise OverlayError(f"invalid missing_evidence list for {sha_value}")
    if disposition == "NEED_EVIDENCE" and not missing:
        raise OverlayError(f"NEED_EVIDENCE requires a concrete missing_evidence list for {sha_value}")
    if disposition in {"ADAPT", "FORK_SUPERIOR"} and not proof:
        raise OverlayError(f"{disposition} requires code/test proof for {sha_value}")


def build_initial_review_overrides(commits: list[dict[str, Any]]) -> list[dict[str, Any]]:
    overrides: list[dict[str, Any]] = []
    for commit in commits:
        sha_value = commit["commit_sha"]
        subject = commit["subject"].strip()
        paths = ", ".join(commit["changed_paths"][:4]) or "no first-parent paths"
        overrides.append(
            {
                "commit_sha": sha_value,
                "current_review_state": "NEED_EVIDENCE",
                "disposition": "NEED_EVIDENCE",
                "decision_reason": (
                    f"Metadata-only record for '{subject}' ({paths}). The first-parent patch is archived, "
                    "but its behavior has not been semantically compared with fixed main; no code adoption is proposed."
                ),
                "fork_owner_file": "PENDING_SOURCE_REVIEW",
                "proof": [],
                "missing_evidence": [
                    f"Review the first-parent patch for {sha_value} against fixed main and identify the actual owner and behavior.",
                    "Determine whether an existing fork contract conflicts and add a fixed-main regression test before any adaptation.",
                    "Collect target-device evidence for any protocol, timing, audio, display, or OEM-specific claim before adoption.",
                ],
                "related_step4": "EX-08; exact PR/EX mapping pending semantic review",
                "test_evidence": "NOT_RUN_PER_COMMIT: no fixed-main semantic comparison or commit-specific regression has been completed",
                "rollback_plan": (
                    "No upstream patch imported. Any future adaptation must be isolated in a revertible PR #45 commit "
                    "with its matching regression test."
                ),
            },
        )
    return overrides


def build_overlay_rows(
    meta: dict[str, Any],
    commits: list[dict[str, Any]],
    overrides: list[dict[str, Any]],
    fieldnames: list[str],
) -> list[dict[str, str]]:
    overrides_by_sha = {row["commit_sha"]: row for row in overrides}
    rows: list[dict[str, str]] = []
    for commit in commits:
        sha_value = commit["commit_sha"]
        review = overrides_by_sha[sha_value]
        row = {field: "" for field in fieldnames}
        row.update(
            {
                "inventory_group": "upstream-delta-111",
                "source_index": str(commit["local_index"]),
                "commit_sha": sha_value,
                "source_url": commit["source_url"],
                "author_date": commit["author_date"],
                "subject": commit["subject"],
                "is_merge": str(commit["is_merge"]).lower(),
                "changed_paths": ";".join(commit["changed_paths"]),
                "primary_category": "MERGE_STRUCTURE" if commit["is_merge"] else "PENDING_SEMANTIC_REVIEW",
                "patch_sha256": commit["patch_sha256"],
                "patch_archive": f"{meta['patch_archive']}#{commit['patch_file']}",
                "source_review_state": "PARENT_AND_FIRST_PARENT_PATCH_METADATA_ONLY",
                "current_review_state": review["current_review_state"],
                "candidate_note": "Unreviewed overlay record; see docs/step4/EX08_DELTA111_REVIEW.md",
                "disposition": review["disposition"],
                "decision_reason": review["decision_reason"],
                "test_evidence": review["test_evidence"],
                "related_step4": review["related_step4"],
                "rollback_plan": review["rollback_plan"],
            },
        )
        rows.append(row)
    return rows


def serialize_rows(rows: list[dict[str, str]], fieldnames: list[str]) -> bytes:
    stream = io.StringIO(newline="")
    writer = csv.DictWriter(stream, fieldnames=fieldnames, lineterminator="\n")
    writer.writeheader()
    writer.writerows(rows)
    return stream.getvalue().encode("utf-8")


def require_legacy_prefix(legacy_bytes: bytes, combined_bytes: bytes) -> None:
    if not combined_bytes.startswith(legacy_bytes):
        raise OverlayError("the combined EX-08 CSV does not preserve the frozen legacy prefix byte-for-byte")
