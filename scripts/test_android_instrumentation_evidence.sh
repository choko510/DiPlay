#!/usr/bin/env bash

set -euo pipefail
test_directory=$(mktemp -d)
trap 'rm -rf "$test_directory"' EXIT
valid_log="$test_directory/valid.log"
invalid_log="$test_directory/duplicate.log"

printf '%s\n' \
    'DSP_BENCHMARK case=baseline sampleRate=48000 channels=2' \
    'HARMONIC_ALIAS sampleRate=44100 channels=1 fundamental=1 alias=0.01 ratio=0.01' \
    'HARMONIC_ALIAS sampleRate=44100 channels=2 fundamental=1 alias=0.01 ratio=0.01' \
    'HARMONIC_ALIAS sampleRate=48000 channels=1 fundamental=1 alias=0.01 ratio=0.01' \
    'HARMONIC_ALIAS sampleRate=48000 channels=2 fundamental=1 alias=0.01 ratio=0.01' > "$valid_log"
bash scripts/check_android_instrumentation_evidence.sh "$valid_log"

printf '%s\n' \
    'DSP_BENCHMARK case=baseline sampleRate=48000 channels=2' \
    'HARMONIC_ALIAS sampleRate=44100 channels=1 fundamental=1 alias=0.01 ratio=0.01' \
    'HARMONIC_ALIAS sampleRate=44100 channels=1 fundamental=1 alias=0.01 ratio=0.01' \
    'HARMONIC_ALIAS sampleRate=44100 channels=1 fundamental=1 alias=0.01 ratio=0.01' \
    'HARMONIC_ALIAS sampleRate=48000 channels=1 fundamental=1 alias=0.01 ratio=0.01' > "$invalid_log"
if bash scripts/check_android_instrumentation_evidence.sh "$invalid_log"; then
    echo "Duplicate measurements incorrectly passed as four formats" >&2
    exit 1
fi

printf '%s\n' 'Android instrumentation evidence fixtures passed.'
