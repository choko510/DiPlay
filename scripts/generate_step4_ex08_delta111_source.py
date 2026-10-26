#!/usr/bin/env python3
"""Capture the immutable source metadata and first-parent patches for EX-08 delta-111."""

from __future__ import annotations

import gzip
import hashlib
import io
import json
import subprocess
import tarfile
from pathlib import Path
from typing import Any

from step4_ex08_delta111 import (
    BASE_SHA,
    EXPECTED_COMMITS,
    EXPECTED_FINAL_TREE_PATHS,
    EXPECTED_MERGE_COMMITS,
    EXPECTED_SOURCE_COMMITS,
    HEAD_SHA,
    build_initial_review_overrides,
)


ROOT = Path(__file__).resolve().parents[1]
OVERLAY_DIR = ROOT / "docs/step4/ex08"
BASE_REF = "refs/ex08/upstream-delta-111/base"
HEAD_REF = "refs/ex08/upstream-delta-111/head"
COMMITS_NAME = "upstream_delta_111.commits.jsonl"
PATCH_ARCHIVE_NAME = "upstream_delta_111_patches.tar.gz"
OVERRIDES_NAME = "review_overrides_111.json"
META_NAME = "upstream_delta_111.meta.json"


def git_bytes(*args: str) -> bytes:
    return subprocess.check_output(["git", *args], cwd=ROOT)


def git_text(*args: str) -> str:
    return git_bytes(*args).decode("utf-8").strip()


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def canonical_json(value: Any) -> bytes:
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8")


def capture_commit(sha_value: str, local_index: int) -> tuple[dict[str, Any], bytes]:
    parents = git_text("show", "-s", "--format=%P", sha_value).split()
    if not parents:
        raise RuntimeError(f"commit has no parent in the selected range: {sha_value}")
    subject = git_text("show", "-s", "--format=%s", sha_value)
    first_parent = parents[0]
    raw_paths = git_bytes(
        "-c", "core.quotePath=false", "diff", "--name-only", "-z", "--no-renames", first_parent, sha_value, "--",
    )
    changed_paths = sorted(path.decode("utf-8") for path in raw_paths.split(b"\0") if path)
    patch = git_bytes(
        "-c", "core.quotePath=false",
        "-c", "diff.indentHeuristic=false",
        "-c", "diff.algorithm=myers",
        "diff", "--binary", "--full-index", "--no-ext-diff", "--no-renames", "--no-color", "--unified=3",
        "--src-prefix=a/", "--dst-prefix=b/", first_parent, sha_value, "--",
    )
    is_merge = len(parents) > 1
    role = "SOURCE" if not is_merge else "DUPLICATE_MERGE" if not patch else "OTHER_MERGE"
    tree_sha = git_text("show", "-s", "--format=%T", sha_value)
    record = {
        "author_date": git_text("show", "-s", "--format=%aI", sha_value),
        "changed_paths": changed_paths,
        "commit_sha": sha_value,
        "committer_date": git_text("show", "-s", "--format=%cI", sha_value),
        "is_merge": is_merge,
        "local_index": local_index,
        "merge_role": role,
        "parent_shas": parents,
        "patch_bytes": len(patch),
        "patch_file": f"patches/{sha_value}.patch",
        "patch_sha256": digest(patch),
        "source_url": f"https://github.com/shihabal3amri/DiPlay/commit/{sha_value}",
        "subject": subject,
        "tree_sha": tree_sha,
    }
    return record, patch


def deterministic_patch_archive(patches: list[tuple[str, bytes]]) -> bytes:
    output = io.BytesIO()
    with gzip.GzipFile(filename="", fileobj=output, mode="wb", mtime=0) as compressed:
        with tarfile.open(fileobj=compressed, mode="w") as archive:
            for name, patch in patches:
                info = tarfile.TarInfo(name)
                info.size = len(patch)
                info.mtime = 0
                info.uid = 0
                info.gid = 0
                info.uname = ""
                info.gname = ""
                info.mode = 0o644
                archive.addfile(info, io.BytesIO(patch))
    return output.getvalue()


def capture() -> None:
    for name in (COMMITS_NAME, PATCH_ARCHIVE_NAME, OVERRIDES_NAME, META_NAME):
        if (OVERLAY_DIR / name).exists():
            raise RuntimeError(f"refusing to overwrite frozen source input: docs/step4/ex08/{name}")
    base = git_text("rev-parse", f"{BASE_REF}^{{commit}}")
    head = git_text("rev-parse", f"{HEAD_REF}^{{commit}}")
    if base != BASE_SHA or head != HEAD_SHA:
        raise RuntimeError(f"fixed refs resolve incorrectly: base={base} head={head}")
    if git_text("merge-base", base, head) != base:
        raise RuntimeError("fixed upstream base is not the merge base of the selected range")

    shas = git_text("rev-list", "--reverse", f"{base}..{head}").splitlines()
    if len(shas) != EXPECTED_COMMITS:
        raise RuntimeError(f"fixed upstream range contains {len(shas)} commits, expected 111")
    final_paths = git_bytes("diff", "--name-only", "-z", base, head, "--")
    final_path_count = len([path for path in final_paths.split(b"\0") if path])
    if final_path_count != EXPECTED_FINAL_TREE_PATHS:
        raise RuntimeError(f"fixed upstream range changes {final_path_count} final-tree paths, expected 117")
    numstat_lines = git_bytes("diff", "--numstat", base, head, "--").decode("utf-8").splitlines()
    additions = sum(int(line.split("\t", 2)[0]) for line in numstat_lines if line.split("\t", 2)[0].isdigit())
    deletions = sum(int(line.split("\t", 2)[1]) for line in numstat_lines if line.split("\t", 2)[1].isdigit())
    if (additions, deletions) != (4357, 376):
        raise RuntimeError(f"fixed upstream range changes +{additions}/-{deletions}, expected +4357/-376")

    records: list[dict[str, Any]] = []
    patches: list[tuple[str, bytes]] = []
    merge_count = 0
    for index, sha_value in enumerate(shas, start=1):
        record, patch = capture_commit(sha_value, index)
        records.append(record)
        patches.append((record["patch_file"], patch))
        merge_count += int(record["is_merge"])
    source_count = len(records) - merge_count
    if source_count != EXPECTED_SOURCE_COMMITS or merge_count != EXPECTED_MERGE_COMMITS:
        raise RuntimeError(f"unexpected parent counts: source={source_count} merge={merge_count}")

    commits_bytes = b"".join(
        (json.dumps(record, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")
        for record in records
    )
    archive_bytes = deterministic_patch_archive(patches)
    overrides = build_initial_review_overrides(records)
    overrides_bytes = canonical_json(overrides)
    meta = {
        "base_sha": BASE_SHA,
        "compare_url": f"https://github.com/shihabal3amri/DiPlay/compare/{BASE_SHA}...{HEAD_SHA}",
        "commits_jsonl": COMMITS_NAME,
        "commits_jsonl_sha256": digest(commits_bytes),
        "final_tree_changed_path_count": final_path_count,
        "final_tree_additions": additions,
        "final_tree_deletions": deletions,
        "head_sha": HEAD_SHA,
        "merge_commit_count": merge_count,
        "non_merge_commit_count": source_count,
        "patch_archive": PATCH_ARCHIVE_NAME,
        "patch_archive_sha256": digest(archive_bytes),
        "patch_sha256_method": (
            "SHA-256 of exact stdout bytes from git -c core.quotePath=false -c diff.indentHeuristic=false "
            "-c diff.algorithm=myers diff --binary --full-index --no-ext-diff --no-renames --no-color "
            "--unified=3 --src-prefix=a/ --dst-prefix=b/ <first-parent> <commit> --; merge commits use first parent only"
        ),
        "range_method": "git rev-list --reverse <base>..<head>",
        "review_overrides_file": OVERRIDES_NAME,
        "review_overrides_sha256": digest(overrides_bytes),
        "schema_version": 1,
        "source_commit_count": len(records),
        "source_repository": "shihabal3amri/DiPlay",
        "capture_git_version": git_text("--version"),
        "merge_role_policy": "Non-merges are SOURCE; empty first-parent merge patch is DUPLICATE_MERGE; other merges remain OTHER_MERGE pending semantic review.",
        "review_state_policy": "All initial rows are NEED_EVIDENCE until commit-specific fixed-main semantic review is recorded.",
        "github_compare": {
            "status": "ahead",
            "ahead_by": EXPECTED_COMMITS,
            "behind_by": 0,
            "total_commits": EXPECTED_COMMITS,
            "final_tree_changed_path_count": EXPECTED_FINAL_TREE_PATHS,
            "source": "GitHub compare API checked against the fixed source range on 2026-10-26",
        },
    }
    OVERLAY_DIR.mkdir(parents=True, exist_ok=True)
    (OVERLAY_DIR / COMMITS_NAME).write_bytes(commits_bytes)
    (OVERLAY_DIR / PATCH_ARCHIVE_NAME).write_bytes(archive_bytes)
    (OVERLAY_DIR / OVERRIDES_NAME).write_bytes(overrides_bytes)
    (OVERLAY_DIR / META_NAME).write_bytes(canonical_json(meta))
    print(
        f"Captured {len(records)} commits ({source_count} source, {merge_count} Merge), "
        f"{final_path_count} final-tree paths; patches {len(archive_bytes)} bytes"
    )


if __name__ == "__main__":
    capture()
