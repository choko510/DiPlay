#!/usr/bin/env bash

is_ci_docs_only_path() {
    case "$1" in
        docs/*|.scratchpad/*|scripts/generate_step4_ex08_inventory.py|\
        scripts/generate_step4_ex08_delta111_source.py|scripts/step4_ex08_delta111.py|\
        scripts/test_step4_ex08_delta111.py|*.md) return 0 ;;
        *) return 1 ;;
    esac
}

is_ci_workflow_sensitive_path() {
    case "$1" in
        .github/workflows/*|scripts/android_ci_classifier.sh|scripts/test_android_ci_classifier.sh|\
        scripts/run_android_instrumentation.sh|scripts/test_dsp_native.sh|scripts/check_jni_mapping.py|\
        scripts/check_public_tree.py|scripts/check_android_instrumentation_evidence.sh|\
        scripts/test_android_instrumentation_evidence.sh|\
        shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt|\
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

ci_android_full_scope() {
    printf '%s\n' 'true true both all true true'
}

ci_baseline_not_found_requires_full() {
    [[ -z "${1:-}" ]]
}

ci_limit_recent_commit_window() {
    local commit_history=${1:-}
    [[ -n "$commit_history" ]] || return 0
    awk 'NR <= 100 { print }' <<< "$commit_history"
}

ci_compare_file_list_requires_full() {
    local file_count=${1:-}
    [[ "$file_count" =~ ^[0-9]+$ ]] || return 0
    (( file_count == 0 || file_count >= 300 ))
}

ci_write_safe_full_android_outputs() {
    local output_file=${1:?output file required}
    printf '%s\n' \
        'host=true' \
        'full_host=true' \
        'quick=true' \
        'quick_scope=both' \
        'quick_test_filters=all' \
        'native=true' \
        'instrumentation=true' \
        'workflow_sensitive=true' \
        'android_mk=true' \
        'safe=false' >> "$output_file"
}

ci_draft_runtime_skip_policy() {
    local is_draft=${1:-false}
    local host=${2:-false}
    local instrumentation=${3:-false}
    local workflow_sensitive=${4:-false}
    local full_host=$host
    if [[ "$is_draft" == "true" && "$workflow_sensitive" != "true" ]]; then
        full_host=false
        instrumentation=false
    fi
    printf '%s %s\n' "$full_host" "$instrumentation"
}

ci_classify_changed_paths() {
    local changed_paths=${1:-}
    local path status test_root test_class
    local runtime=false
    local native=false
    local unscoped_runtime=false
    local workflow_sensitive=false
    local android_mk=false
    local test_modules=,
    local scoped_test_filters=
    local test_only_changes=true

    while IFS=$'\t' read -r path status; do
        [[ -n "${path:-}" ]] || continue
        if is_ci_docs_only_path "$path"; then
            continue
        fi
        if is_ci_workflow_sensitive_path "$path"; then
            workflow_sensitive=true
        fi
        case "$path" in
            shared/src/test/java/*|shared/src/test/kotlin/*)
                runtime=true
                if [[ "$test_modules" != *",shared,"* ]]; then
                    test_modules="${test_modules}shared,"
                fi
                if [[ ( "$status" == "added" || "$status" == "modified" ) && "${path##*/}" =~ Test(s|Case)?\.(kt|java)$ ]]; then
                    test_root=shared/src/test/java/
                    if [[ "$path" == shared/src/test/kotlin/* ]]; then
                        test_root=shared/src/test/kotlin/
                    fi
                    test_class=${path#"$test_root"}
                    test_class=${test_class%.*}
                    test_class=${test_class//\//.}
                    scoped_test_filters="${scoped_test_filters}shared|${test_class},"
                else
                    test_only_changes=false
                fi ;;
            common/src/main/java/*|common/src/main/kotlin/*)
                runtime=true
                test_only_changes=false
                if [[ "$test_modules" != *",common,"* ]]; then
                    test_modules="${test_modules}common,"
                fi ;;
            common/src/test/java/*|common/src/test/kotlin/*)
                runtime=true
                if [[ "$test_modules" != *",common,"* ]]; then
                    test_modules="${test_modules}common,"
                fi
                if [[ ( "$status" == "added" || "$status" == "modified" ) && "${path##*/}" =~ Test(s|Case)?\.(kt|java)$ ]]; then
                    test_root=common/src/test/java/
                    if [[ "$path" == common/src/test/kotlin/* ]]; then
                        test_root=common/src/test/kotlin/
                    fi
                    test_class=${path#"$test_root"}
                    test_class=${test_class%.*}
                    test_class=${test_class//\//.}
                    scoped_test_filters="${scoped_test_filters}common|${test_class},"
                else
                    test_only_changes=false
                fi ;;
            *)
                runtime=true
                test_only_changes=false
                unscoped_runtime=true ;;
        esac
        if is_ci_native_path "$path"; then
            runtime=true
            native=true
        fi
        if is_ci_android_mk_link_path "$path"; then
            runtime=true
            native=true
            android_mk=true
        fi
    done <<< "$changed_paths"

    local test_scope=none
    if [[ "$runtime" == "true" ]]; then
        if [[ "$unscoped_runtime" == "true" ]]; then
            test_scope=both
        else
            case "$test_modules" in
                *,shared,common,*|*,common,shared,*) test_scope=both ;;
                *,shared,*) test_scope=shared ;;
                *,common,*) test_scope=common ;;
                *) test_scope=both ;;
            esac
        fi
    fi
    local test_filters=all
    if [[ "$runtime" != "true" ]]; then
        test_filters=none
    elif [[ "$test_only_changes" == "true" && -n "$scoped_test_filters" ]]; then
        test_filters=${scoped_test_filters%,}
    fi
    printf '%s %s %s %s %s %s\n' \
        "$runtime" "$native" "$test_scope" "$test_filters" "$workflow_sensitive" "$android_mk"
}
