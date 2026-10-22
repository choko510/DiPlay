#!/usr/bin/env bash

set +e
if ! adb logcat -c; then
    echo "Could not clear logcat before instrumentation; refusing to reuse old DSP evidence"
    exit 1
fi

./gradlew :shared:connectedDebugAndroidTest :common:connectedDebugAndroidTest --build-cache --stacktrace
test_result=$?

mkdir -p shared/build/outputs/dsp-benchmark
timeout 20s adb logcat -d -v threadtime -s DspPipelineBenchmark:I DspFinalIntegration:I > shared/build/outputs/dsp-benchmark/logcat.txt || true

if [ "$test_result" -eq 0 ]; then
    if ! bash scripts/check_android_instrumentation_evidence.sh shared/build/outputs/dsp-benchmark/logcat.txt; then
        exit 1
    fi
fi

exit "$test_result"
