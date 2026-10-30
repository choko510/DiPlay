#!/usr/bin/env bash

set -euo pipefail
log_file=${1:?usage: check_android_instrumentation_evidence.sh LOG_FILE}

if [[ ! -s "$log_file" ]]; then
    echo "DSP instrumentation log is empty" >&2
    exit 1
fi
if ! grep -Fq "DSP_BENCHMARK" "$log_file"; then
    echo "DSP benchmark evidence was not present in logcat" >&2
    exit 1
fi

for format in 44100/1 44100/2 48000/1 48000/2; do
    sample_rate=${format%/*}
    channels=${format#*/}
    if ! grep -Fq "HARMONIC_ALIAS sampleRate=$sample_rate channels=$channels " "$log_file"; then
        echo "Harmonic alias evidence is missing sampleRate=$sample_rate channels=$channels" >&2
        exit 1
    fi
done
