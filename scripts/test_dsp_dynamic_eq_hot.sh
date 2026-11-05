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
test_source="$root_dir/shared/src/test/native/dsp/test_dsp_dynamic_eq_hot.c"
compiler="${CC:-cc}"

if ! command -v "$compiler" >/dev/null 2>&1; then
  echo "C compiler not found: $compiler" >&2
  exit 127
fi

build_dir="$(mktemp -d)"
trap 'rm -rf "$build_dir"' EXIT

if [[ "${compiler,,}" == *cl.exe ]]; then
  if [[ $sanitize -eq 1 ]]; then
    echo "MSVC does not support the requested combined ASan/UBSan test mode" >&2
    exit 2
  fi
  windows_build_dir="$(cygpath -m "$build_dir")"
  windows_source_dir="$(cygpath -m "$source_dir")"
  windows_test_source="$(cygpath -m "$test_source")"
  MSYS_NO_PATHCONV=1 "$compiler" /nologo /std:c11 /W4 /WX /O2 \
    "/I$windows_source_dir" \
    "$windows_test_source" \
    "$windows_source_dir/dsp_dynamic_eq.c" \
    "$windows_source_dir/dsp_biquad.c" \
    "/Fo$windows_build_dir/" \
    "/Fe$windows_build_dir/test_dsp_dynamic_eq_hot.exe" \
    /link "/OUT:$windows_build_dir/test_dsp_dynamic_eq_hot.exe"
  "$build_dir/test_dsp_dynamic_eq_hot.exe"
else
  flags=(-std=c11 -Wall -Wextra -Werror -pedantic -g -I"$source_dir")
  if [[ $sanitize -eq 1 ]]; then
    flags+=(-O1 -fsanitize=address,undefined -fno-omit-frame-pointer)
  else
    flags+=(-O2)
  fi

  "$compiler" "${flags[@]}" \
    "$test_source" \
    "$source_dir/dsp_dynamic_eq.c" \
    "$source_dir/dsp_biquad.c" \
    -lm -o "$build_dir/test_dsp_dynamic_eq_hot"

  if [[ $sanitize -eq 1 ]]; then
    ASAN_OPTIONS="${ASAN_OPTIONS:-detect_leaks=1:halt_on_error=1}" \
      UBSAN_OPTIONS="${UBSAN_OPTIONS:-halt_on_error=1}" \
      "$build_dir/test_dsp_dynamic_eq_hot"
  else
    "$build_dir/test_dsp_dynamic_eq_hot"
  fi
fi
