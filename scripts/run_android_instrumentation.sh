#!/usr/bin/env bash

set +e
./gradlew :shared:connectedDebugAndroidTest --build-cache --stacktrace
test_result=$?

mkdir -p shared/build/outputs/dsp-benchmark
timeout 20s adb logcat -d -v threadtime -s DspPipelineBenchmark:I DspFinalIntegration:I > shared/build/outputs/dsp-benchmark/logcat.txt || true

if [ "$test_result" -eq 0 ]; then
    if [ ! -s shared/build/outputs/dsp-benchmark/logcat.txt ]; then
        echo "DSP benchmark log capture was empty"
        exit 1
    fi
    if ! grep -q "DSP_BENCHMARK" shared/build/outputs/dsp-benchmark/logcat.txt; then
        echo "DSP benchmark evidence was not present in logcat"
        exit 1
    fi
    alias_count="$(grep -c "HARMONIC_ALIAS" shared/build/outputs/dsp-benchmark/logcat.txt || true)"
    if [ "$alias_count" -lt 4 ]; then
        echo "Harmonic alias measurements were not recorded for every sample-rate/channel format"
        exit 1
    fi
fi

exit "$test_result"
