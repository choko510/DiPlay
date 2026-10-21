#!/usr/bin/env bash

set +e
capture_tag="Step4Instrumentation"
capture_id="${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-1}-$$-$(date +%s)"
logcat_file="shared/build/outputs/dsp-benchmark/logcat.txt"
raw_logcat="$(mktemp)"

adb shell log -t "$capture_tag" "BEGIN_$capture_id" >/dev/null 2>&1
./gradlew :shared:connectedDebugAndroidTest --stacktrace
test_result=$?
adb shell log -t "$capture_tag" "END_$capture_id" >/dev/null 2>&1

mkdir -p shared/build/outputs/dsp-benchmark
if adb get-state >/dev/null 2>&1; then
    timeout 20s adb logcat -d -v threadtime -s DspPipelineBenchmark:I "$capture_tag:I" > "$raw_logcat" || true
else
    : > "$raw_logcat"
fi
awk -v begin="BEGIN_$capture_id" -v end="END_$capture_id" '
    index($0, begin) { capturing = 1; started = 1; next }
    index($0, end) { finished = 1; capturing = 0; exit }
    capturing && /DSP_BENCHMARK/ { print }
    END { if (!started || !finished) exit 2 }
' "$raw_logcat" > "$logcat_file"
capture_result=$?
rm -f "$raw_logcat"

if [ "$test_result" -eq 0 ]; then
    if [ "$capture_result" -ne 0 ] || [ ! -s "$logcat_file" ]; then
        echo "DSP benchmark log capture was empty or did not contain this test run"
        exit 1
    fi
fi

exit "$test_result"
