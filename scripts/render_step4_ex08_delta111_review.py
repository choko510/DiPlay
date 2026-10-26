#!/usr/bin/env python3
"""Render the human-readable EX-08 delta-111 review from pinned decisions."""

from __future__ import annotations

import argparse
from pathlib import Path

from step4_ex08_delta111 import load_and_validate_inputs, render_review_markdown


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "docs/step4/EX08_DELTA111_REVIEW.md"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true", help="write the rendered review")
    args = parser.parse_args()
    meta, commits, overrides = load_and_validate_inputs(ROOT)
    rendered = render_review_markdown(meta, commits, overrides)
    if args.write:
        OUTPUT.write_text(rendered, encoding="utf-8")
        print(f"updated {OUTPUT.relative_to(ROOT)}")
        return 0
    if not OUTPUT.exists() or OUTPUT.read_text(encoding="utf-8") != rendered:
        print(f"{OUTPUT.relative_to(ROOT)} does not match the pinned per-SHA review data; run with --write")
        return 1
    print(f"{OUTPUT.relative_to(ROOT)} matches {len(commits)} pinned review decisions")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
