#!/usr/bin/env bash

is_ci_docs_only_path() {
    case "$1" in
        docs/*|.scratchpad/*|scripts/generate_step4_ex08_inventory.py|*.md) return 0 ;;
        *) return 1 ;;
    esac
}

is_ci_workflow_sensitive_path() {
    case "$1" in
        .github/workflows/*|scripts/android_ci_classifier.sh|scripts/test_android_ci_classifier.sh|\
        scripts/run_android_instrumentation.sh|scripts/test_dsp_native.sh|scripts/check_jni_mapping.py|\
        scripts/check_public_tree.py|scripts/check_android_instrumentation_evidence.sh|\
        scripts/test_android_instrumentation_evidence.sh|\
        shared/src/androidTest/java/com/shilapi/xcertplay/media/dsp/DspPipelineBenchmarkInstrumentedTest.kt)
            return 0
            ;;
        *) return 1 ;;
    esac
}

is_ci_native_path() {
    case "$1" in
        shared/src/main/jni/Android.mk|shared/src/main/jni/Application.mk|\
        shared/src/main/jni/dsp/*|shared/src/main/jni/third_party/kissfft/*|\
        shared/src/test/native/dsp/*|scripts/test_dsp_native.sh)
            return 0
            ;;
        *) return 1 ;;
    esac
}

is_ci_android_mk_link_path() {
    case "$1" in
        shared/src/main/jni/Android.mk|shared/src/main/jni/Application.mk) return 0 ;;
        *) return 1 ;;
    esac
}

is_ci_full_android_baseline() {
    local check_names=",$1,"
    [[ "$check_names" == *",check,"* && "$check_names" == *",android-instrumentation,"* ]]
}
