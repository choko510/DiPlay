#!/usr/bin/env bash

set -uo pipefail

mkdir -p shared/build/outputs/dsp-benchmark
raw_log="$(mktemp)"
trap 'rm -f "$raw_log"' EXIT
run_id="$(date -u +%Y%m%dT%H%M%S)-$$-${RANDOM}"
start_marker="DSP_CAPTURE_START runId=$run_id"
end_marker="DSP_CAPTURE_END runId=$run_id"

adb shell log -t DspPipelineBenchmark "$start_marker"
start_result=$?
./gradlew :shared:connectedDebugAndroidTest --stacktrace
test_result=$?
adb shell log -t DspPipelineBenchmark "$end_marker"
end_result=$?
timeout 20s adb logcat -d -v threadtime -s DspPipelineBenchmark:I > "$raw_log"
capture_result=$?

if [ "$start_result" -eq 0 ] && [ "$end_result" -eq 0 ] && [ "$capture_result" -eq 0 ]; then
    if awk -v start="$start_marker" -v end="$end_marker" '
        index($0, start) { capturing = 1; saw_start = 1; next }
        index($0, end) { if (capturing) saw_end = 1; capturing = 0; next }
        capturing && index($0, "DSP_BENCHMARK") > 0 { print }
        END { if (!saw_start || !saw_end) exit 1 }
    ' "$raw_log" > shared/build/outputs/dsp-benchmark/logcat.txt; then
        printf 'DSP_CAPTURE runId=%s startSeen=true endSeen=true\n' "$run_id" \
            >> shared/build/outputs/dsp-benchmark/logcat.txt
    else
        echo "DSP benchmark log markers were missing or out of order"
        capture_result=1
    fi
else
    echo "DSP benchmark log capture failed"
    capture_result=1
fi

if [ "$test_result" -eq 0 ]; then
    if [ ! -s shared/build/outputs/dsp-benchmark/logcat.txt ]; then
        echo "DSP benchmark log capture was empty"
        capture_result=1
    elif ! grep -q "DSP_BENCHMARK" shared/build/outputs/dsp-benchmark/logcat.txt; then
        echo "DSP benchmark evidence was not present in this run's log interval"
        capture_result=1
    fi
fi

if [ "$test_result" -ne 0 ]; then
    exit "$test_result"
fi
exit "$capture_result"
