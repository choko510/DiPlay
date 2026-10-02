#!/usr/bin/env bash

set +e
./gradlew :shared:connectedDebugAndroidTest --stacktrace
test_result=$?

mkdir -p shared/build/outputs/dsp-benchmark
timeout 20s adb logcat -d -v threadtime -s DspPipelineBenchmark:I > shared/build/outputs/dsp-benchmark/logcat.txt || true

if [ "$test_result" -eq 0 ]; then
    if [ ! -s shared/build/outputs/dsp-benchmark/logcat.txt ]; then
        echo "DSP benchmark log capture was empty"
        exit 1
    fi
    if ! grep -q "DSP_BENCHMARK" shared/build/outputs/dsp-benchmark/logcat.txt; then
        echo "DSP benchmark evidence was not present in logcat"
        exit 1
    fi
fi

exit "$test_result"
