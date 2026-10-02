#!/usr/bin/env python3
"""Verify that optimized app mappings retain the JNI class and constructor."""

from pathlib import Path
import re
import sys


JNI_CLASSES = {
    "com.shilapi.xcertplay.transport.LinuxI2cNativeException": {"<init>"},
    "com.shilapi.xcertplay.media.dsp.NativeDspJni": {
        "nativeCreate",
        "nativeProcess",
        "nativeReset",
        "nativeGetLatencyFrames",
        "nativeGetDiagnostics",
        "nativeDestroy",
    },
}
MAPPING_PATHS = (
    Path("mobile/build/outputs/mapping/benchmark/mapping.txt"),
    Path("automotive/build/outputs/mapping/benchmark/mapping.txt"),
)
def class_members(lines: list[str], class_name: str) -> list[str]:
    class_line = f"{class_name} -> {class_name}:"
    try:
        class_index = lines.index(class_line)
    except ValueError as error:
        raise RuntimeError(f"JNI class was renamed or removed: {class_name}") from error

    members = []
    for line in lines[class_index + 1 :]:
        if line.startswith("#"):
            continue
        if line and not line[0].isspace():
            break
        if line.strip():
            members.append(line.strip())
    return members


def verify_mapping(path: Path) -> None:
    if not path.is_file():
        raise RuntimeError(f"Missing optimized mapping: {path}")

    lines = path.read_text(encoding="utf-8").splitlines()
    seeds_path = path.with_name("seeds.txt")
    seed_lines = seeds_path.read_text(encoding="utf-8").splitlines() if seeds_path.is_file() else []
    for class_name, expected_methods in JNI_CLASSES.items():
        members = class_members(lines, class_name)
        mapped_methods = set()
        for member in members:
            if "(" not in member or " -> " not in member:
                continue
            signature, mapped_name = member.rsplit(" -> ", 1)
            method_name = signature.split("(", 1)[0].split()[-1]
            if method_name == mapped_name:
                mapped_methods.add(method_name)
        seeded_methods = set()
        class_prefix = f"{class_name}:"
        for line in seed_lines:
            if line.startswith(class_prefix) and "(" in line:
                signature = line[len(class_prefix) :].split("(", 1)[0].split()
                if signature:
                    seeded_methods.add(signature[-1])
        missing = expected_methods - (mapped_methods | seeded_methods)
        if missing:
            names = ", ".join(sorted(missing))
            raise RuntimeError(f"JNI member names were renamed or removed in {path}: {names}")


def main() -> int:
    try:
        for path in MAPPING_PATHS:
            verify_mapping(path)
            print(f"JNI mappings verified: {path}")
    except RuntimeError as error:
        print(error, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
