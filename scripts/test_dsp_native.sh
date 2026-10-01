#!/usr/bin/env bash
set -euo pipefail

sanitize=0
if [[ "${1:-}" == "--sanitize" ]]; then
  sanitize=1
elif [[ $# -gt 0 ]]; then
  echo "usage: $0 [--sanitize]" >&2
  exit 2
fi

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source_dir="$root_dir/shared/src/main/jni/dsp"
test_source="$root_dir/shared/src/test/native/dsp/test_dsp_engine.c"
compiler="${CC:-cc}"

if ! command -v "$compiler" >/dev/null 2>&1; then
  echo "C compiler not found: $compiler" >&2
  exit 127
fi

build_dir="$(mktemp -d)"
trap 'rm -rf "$build_dir"' EXIT

flags=(-std=c11 -Wall -Wextra -Werror -pedantic -g -I"$source_dir" -I"$root_dir/shared/src/main/jni/third_party/kissfft")
if [[ $sanitize -eq 1 ]]; then
  flags+=(-O1 -fsanitize=address,undefined -fno-omit-frame-pointer)
else
  flags+=(-O2)
fi

"$compiler" "${flags[@]}" \
  "$test_source" \
  "$source_dir/dsp_engine.c" \
  "$source_dir/dsp_graph.c" \
  "$source_dir/dsp_biquad.c" \
  "$source_dir/dsp_dynamics.c" \
  "$source_dir/dsp_multiband.c" \
  "$source_dir/dsp_spatial.c" \
  "$source_dir/dsp_convolver.c" \
  "$source_dir/dsp_gain.c" \
  "$source_dir/dsp_meter.c" \
  "$root_dir/shared/src/main/jni/third_party/kissfft/kiss_fft.c" \
  "$root_dir/shared/src/main/jni/third_party/kissfft/kiss_fftr.c" \
  -lm -o "$build_dir/test_dsp_engine"

if [[ $sanitize -eq 1 ]]; then
  ASAN_OPTIONS="${ASAN_OPTIONS:-detect_leaks=1:halt_on_error=1}" \
    UBSAN_OPTIONS="${UBSAN_OPTIONS:-halt_on_error=1}" \
    "$build_dir/test_dsp_engine"
else
  "$build_dir/test_dsp_engine"
fi
