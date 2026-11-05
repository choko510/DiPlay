#!/usr/bin/env bash

set +e
if ! adb logcat -c; then
    echo "Could not clear logcat before instrumentation; refusing to reuse old DSP evidence"
    exit 1
fi

./gradlew -PsharedAndroidTestBuildType=release :shared:connectedReleaseAndroidTest --build-cache --stacktrace
shared_test_result=$?

./gradlew :common:connectedDebugAndroidTest --build-cache --stacktrace
common_test_result=$?

test_result=0
if [ "$shared_test_result" -ne 0 ] || [ "$common_test_result" -ne 0 ]; then
    test_result=1
fi

mkdir -p shared/build/outputs/dsp-benchmark
if command -v timeout >/dev/null 2>&1; then
    timeout 20s adb logcat -d -v threadtime -s DspPipelineBenchmark:I DspFinalIntegration:I > shared/build/outputs/dsp-benchmark/logcat.txt || true
else
    adb logcat -d -v threadtime -s DspPipelineBenchmark:I DspFinalIntegration:I > shared/build/outputs/dsp-benchmark/logcat.txt || true
fi

if [ "$test_result" -eq 0 ]; then
    if ! bash scripts/check_android_instrumentation_evidence.sh shared/build/outputs/dsp-benchmark/logcat.txt; then
        exit 1
    fi
fi

exit "$test_result"
