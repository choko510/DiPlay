#!/usr/bin/env python3
"""Classify a pull request's Android CI lane; uncertainty selects full verification."""

from __future__ import annotations

import json
import os
import sys
import urllib.error
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
        and path not in STATIC_ROOT_FILES
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


def pull_request_files_url(repository: str, pull_number: int) -> str:
    return f"https://api.github.com/repos/{repository}/pulls/{pull_number}/files?per_page=100&page=1"


def fetch_changed_paths(
    repository: str,
    pull_number: int,
    token: str,
) -> list[str]:
    url = pull_request_files_url(repository, pull_number)
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
        files = json.load(response)

    if not isinstance(files, list) or len(files) >= 100:
        raise ValueError("pull-request file list is missing or at the page limit")
    if any(
        not isinstance(entry, dict) or not isinstance(entry.get("filename"), str)
        for entry in files
    ):
        raise ValueError("pull-request file list contains an invalid entry")
    return [entry["filename"] for entry in files]


def classify_event(event_name: str, event: dict, repository: str, token: str) -> dict[str, bool]:
    if event_name != "pull_request":
        return full_verification(safe=True)

    pull_request = event.get("pull_request")
    if not isinstance(pull_request, dict):
        print("::warning::Android CI classifier has no pull_request event object; running full verification")
        return full_verification(safe=False)
    pull_number = pull_request.get("number") or event.get("number")
    if not isinstance(pull_number, int) or pull_number <= 0 or not repository or not token:
        print("::warning::Android CI classifier is missing pull number or read token; running full verification")
        return full_verification(safe=False)

    try:
        paths = fetch_changed_paths(repository, pull_number, token)
    except (OSError, TimeoutError, ValueError, urllib.error.URLError, json.JSONDecodeError) as error:
        status = f" HTTP {error.code}" if isinstance(error, urllib.error.HTTPError) else ""
        print(
            "::warning::Android CI pull-request file listing failed "
            f"({type(error).__name__}{status}); running full verification"
        )
        return full_verification(safe=False)
    result = classify_paths(paths, draft=bool(pull_request.get("draft")))
    if not result["safe"]:
        print(
            "::warning::Android CI pull-request file list is empty, too large, or contains an unclassified path; "
            "running full verification"
        )
    return result


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
        print("::warning::Android CI event payload is unreadable; running full verification")
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
