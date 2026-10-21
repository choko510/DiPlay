#!/usr/bin/env python3
"""Classify a pull request's Android CI lane; uncertainty selects full verification."""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request


STATIC_PREFIXES = ("docs/", ".scratchpad/", ".github/workflows/")
STATIC_ROOT_FILES = {".gitignore"}
RUNTIME_PREFIXES = ("shared/", "common/", "mobile/", "automotive/", "scripts/", "gradle/")
RUNTIME_ROOT_FILES = {
    "build.gradle.kts",
    "settings.gradle.kts",
    "gradle.properties",
    "gradlew",
    "gradlew.bat",
}
NATIVE_PREFIXES = ("shared/src/main/jni/", "shared/src/test/native/")
NATIVE_FILES = {"scripts/test_dsp_native.sh"}


def classify_paths(paths: list[str], draft: bool) -> dict[str, bool]:
    normalized = [path.replace("\\", "/").removeprefix("./") for path in paths]
    if not normalized:
        return full_verification(safe=False)

    static_only = all(
        path.startswith(STATIC_PREFIXES)
        or path in STATIC_ROOT_FILES
        or path.lower().endswith(".md")
        for path in normalized
    )
    if static_only:
        return {
            "full_host": False,
            "quick": False,
            "native": False,
            "instrumentation": False,
            "safe": True,
        }

    if any(
        not path.startswith(RUNTIME_PREFIXES)
        and path not in RUNTIME_ROOT_FILES
        and not path.lower().endswith(".md")
        for path in normalized
    ):
        return full_verification(safe=False)

    native_changed = any(
        path.startswith(NATIVE_PREFIXES) or path in NATIVE_FILES for path in normalized
    )
    if draft:
        return {
            "full_host": False,
            "quick": True,
            "native": native_changed,
            "instrumentation": False,
            "safe": True,
        }

    return {
        "full_host": True,
        "quick": False,
        "native": True,
        "instrumentation": True,
        "safe": True,
    }


def full_verification(safe: bool) -> dict[str, bool]:
    return {
        "full_host": True,
        "quick": False,
        "native": True,
        "instrumentation": True,
        "safe": safe,
    }


def compare_url(repository: str, head_repository: str, base_sha: str, head_sha: str) -> str:
    base_ref = base_sha
    head_ref = head_sha
    if head_repository.lower() != repository.lower():
        base_owner = repository.split("/", maxsplit=1)[0]
        head_owner = head_repository.split("/", maxsplit=1)[0]
        base_ref = f"{base_owner}:{base_sha}"
        head_ref = f"{head_owner}:{head_sha}"
    base_head = urllib.parse.quote(f"{base_ref}...{head_ref}", safe=":.")
    return f"https://api.github.com/repos/{repository}/compare/{base_head}?per_page=100"


def fetch_changed_paths(
    repository: str,
    head_repository: str,
    base_sha: str,
    head_sha: str,
    token: str,
) -> list[str]:
    url = compare_url(repository, head_repository, base_sha, head_sha)
    request = urllib.request.Request(
        url,
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "User-Agent": "DiPlay-Step4-CI-classifier",
            "X-GitHub-Api-Version": "2026-03-10",
        },
    )
    with urllib.request.urlopen(request, timeout=20) as response:
        comparison = json.load(response)

    files = comparison.get("files")
    if not isinstance(files, list) or len(files) >= 100:
        raise ValueError("changed-file list is missing or at the compare API limit")
    if any(
        not isinstance(entry, dict) or not isinstance(entry.get("filename"), str)
        for entry in files
    ):
        raise ValueError("changed-file list contains an invalid entry")
    return [entry["filename"] for entry in files]


def classify_event(event_name: str, event: dict, repository: str, token: str) -> dict[str, bool]:
    if event_name != "pull_request":
        return full_verification(safe=True)

    pull_request = event.get("pull_request")
    if not isinstance(pull_request, dict):
        return full_verification(safe=False)
    base_sha = (pull_request.get("base") or {}).get("sha")
    head_sha = (pull_request.get("head") or {}).get("sha")
    head_repository = (pull_request.get("head") or {}).get("repo") or {}
    head_repository_name = head_repository.get("full_name")
    if not base_sha or not head_sha or not head_repository_name or not repository or not token:
        return full_verification(safe=False)

    try:
        paths = fetch_changed_paths(repository, head_repository_name, base_sha, head_sha, token)
    except (OSError, TimeoutError, ValueError, urllib.error.URLError, json.JSONDecodeError):
        return full_verification(safe=False)
    return classify_paths(paths, draft=bool(pull_request.get("draft")))


def main() -> int:
    try:
        with open(os.environ["GITHUB_EVENT_PATH"], encoding="utf-8") as event_file:
            event = json.load(event_file)
        result = classify_event(
            event_name=os.environ.get("GITHUB_EVENT_NAME", ""),
            event=event,
            repository=os.environ.get("GITHUB_REPOSITORY", ""),
            token=os.environ.get("GH_TOKEN", ""),
        )
    except (KeyError, OSError, json.JSONDecodeError):
        result = full_verification(safe=False)

    output = os.environ.get("GITHUB_OUTPUT")
    lines = [f"{name}={str(value).lower()}" for name, value in result.items()]
    if output:
        with open(output, "a", encoding="utf-8") as output_file:
            output_file.write("\n".join(lines) + "\n")
    print("Android CI lane: " + ", ".join(lines))
    return 0


if __name__ == "__main__":
    sys.exit(main())
