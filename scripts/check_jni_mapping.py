#!/usr/bin/env python3
"""Verify that optimized app mappings retain the JNI class and constructor."""

from pathlib import Path
import re
import sys


JNI_CLASS = "com.shilapi.xcertplay.transport.LinuxI2cNativeException"
MAPPING_PATHS = (
    Path("mobile/build/outputs/mapping/benchmark/mapping.txt"),
    Path("automotive/build/outputs/mapping/benchmark/mapping.txt"),
)
CONSTRUCTOR = re.compile(
    r"^\s*(?:\d+:\d+:)?void <init>\(int,java\.lang\.String\)(?::.*)? -> <init>$"
)


def verify_mapping(path: Path) -> None:
    if not path.is_file():
        raise RuntimeError(f"Missing optimized mapping: {path}")

    lines = path.read_text(encoding="utf-8").splitlines()
    class_line = f"{JNI_CLASS} -> {JNI_CLASS}:"
    try:
        class_index = lines.index(class_line)
    except ValueError as error:
        raise RuntimeError(f"JNI class was renamed or removed in {path}") from error

    for line in lines[class_index + 1 :]:
        if line.startswith("#"):
            continue
        if line and not line[0].isspace():
            break
        if CONSTRUCTOR.fullmatch(line):
            return

    raise RuntimeError(f"JNI constructor was renamed or removed in {path}")


def main() -> int:
    try:
        for path in MAPPING_PATHS:
            verify_mapping(path)
            print(f"JNI mapping verified: {path}")
    except RuntimeError as error:
        print(error, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
