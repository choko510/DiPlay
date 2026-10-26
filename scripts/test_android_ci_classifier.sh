#!/usr/bin/env bash

set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$(dirname "${BASH_SOURCE[0]}")/android_ci_classifier.sh"

assert_classification() {
    local expected="$1"
    local changed_paths="$2"
    local actual
    actual=$(ci_classify_changed_paths "$changed_paths")
    if [[ "$actual" != "$expected" ]]; then
        printf 'Expected classification: %s\nActual classification:   %s\n' "$expected" "$actual" >&2
        exit 1
    fi
}

# CI-01: docs-only paths run the static lane without product runtime work.
assert_classification 'false false none none false false' $'docs/step4/TEST_MATRIX.md\tmodified\n.scratchpad/current.md\tmodified'
is_ci_docs_only_path "scripts/generate_step4_ex08_delta111_source.py"
is_ci_docs_only_path "scripts/step4_ex08_delta111.py"
is_ci_docs_only_path "scripts/test_step4_ex08_delta111.py"
! is_ci_docs_only_path "scripts/test_android_ci_classifier.sh"

# CI-02/03: source changes choose the affected modules; standalone tests may be class-filtered.
assert_classification 'true false both all false false' $'shared/src/main/java/com/shilapi/xcertplay/airplay/AirPlaySession.kt\tmodified'
assert_classification 'true false common all false false' $'common/src/main/java/com/shilapi/xcertplay/DspSettingsScreen.kt\tmodified'
assert_classification 'true false shared shared|com.shilapi.xcertplay.transport.WiredStartupTimingOverheadTest false false' \
    $'shared/src/test/java/com/shilapi/xcertplay/transport/WiredStartupTimingOverheadTest.kt\tadded'
assert_classification 'true false common common|com.shilapi.xcertplay.DspSettingsScreenTest false false' \
    $'common/src/test/kotlin/com/shilapi/xcertplay/DspSettingsScreenTest.kt\tmodified'
assert_classification 'true false shared all false false' \
    $'shared/src/test/java/com/shilapi/xcertplay/transport/WiredStartupFixtures.kt\tadded'
assert_classification 'true false both all false false' \
    $'shared/src/main/java/com/shilapi/xcertplay/airplay/AirPlaySession.kt\tmodified\ncommon/src/main/java/com/shilapi/xcertplay/DspSettingsScreen.kt\tmodified'

# CI-04/05: native sources run native tests; Android.mk/Application.mk also require ABI link work.
assert_classification 'true true both all false false' $'shared/src/main/jni/dsp/dsp_engine.c\tmodified'
assert_classification 'true true both all false true' $'shared/src/main/jni/Android.mk\tmodified'
assert_classification 'true true both all false true' $'shared/src/main/jni/Application.mk\tmodified'

# CI-06: benchmark and workflow helpers always require the workflow-sensitive lane.
assert_classification 'true false both all true false' \
    $'shared/src/androidTest/java/com/shilapi/xcertplay/media/dsp/DspPipelineBenchmarkInstrumentedTest.kt\tmodified'
is_ci_workflow_sensitive_path ".github/workflows/android.yml"
is_ci_workflow_sensitive_path "scripts/run_android_instrumentation.sh"
is_ci_workflow_sensitive_path "scripts/android_ci_classifier.sh"
is_ci_workflow_sensitive_path "scripts/test_android_ci_classifier.sh"
is_ci_workflow_sensitive_path "scripts/check_android_instrumentation_evidence.sh"
! is_ci_workflow_sensitive_path "shared/src/main/java/com/shilapi/xcertplay/airplay/AirPlaySession.kt"
[[ "$(ci_draft_runtime_skip_policy true true true true)" == 'true true' ]]
[[ "$(ci_draft_runtime_skip_policy true true true false)" == 'false false' ]]
[[ "$(ci_draft_runtime_skip_policy false true true false)" == 'true true' ]]

# CI-07: missing or malformed compare metadata selects the safe, full verification scope.
ci_compare_file_list_requires_full 0
ci_compare_file_list_requires_full 1 && exit 1
ci_compare_file_list_requires_full 299 && exit 1
ci_compare_file_list_requires_full 300
ci_compare_file_list_requires_full 301
ci_compare_file_list_requires_full invalid
[[ "$(ci_android_full_scope)" == 'true true both all true true' ]]
fallback_output=$(mktemp)
trap 'rm -f "$fallback_output"' EXIT
ci_write_safe_full_android_outputs "$fallback_output"
for expected in \
    'host=true' 'full_host=true' 'quick=true' 'quick_scope=both' \
    'quick_test_filters=all' 'native=true' 'instrumentation=true' \
    'workflow_sensitive=true' 'android_mk=true' 'safe=false'; do
    grep -Fxq "$expected" "$fallback_output"
done
fallback_branch_count=$(grep -c 'ci_write_safe_full_android_outputs "$GITHUB_OUTPUT"' "$repo_root/.github/workflows/android.yml")
[[ "$fallback_branch_count" == 6 ]]

# CI-08: incomplete compare file lists and unknown paths do not take a narrow lane.
assert_classification 'true false both all false false' $'scripts/new-unknown-check.py\tadded'
[[ "$(ci_android_full_scope)" == 'true true both all true true' ]]
ci_commit_history=$(awk 'BEGIN { for (i = 1; i <= 101; i++) printf "commit-%03d\n", i }')
ci_commit_window=$(ci_limit_recent_commit_window "$ci_commit_history")
[[ "$(wc -l <<< "$ci_commit_window" | tr -d ' ')" == 100 ]]
[[ "$(head -n 1 <<< "$ci_commit_window")" == 'commit-001' ]]
[[ "$(tail -n 1 <<< "$ci_commit_window")" == 'commit-100' ]]
! grep -Fxq 'commit-101' <<< "$ci_commit_window"
ci_baseline_not_found_requires_full ""
! ci_baseline_not_found_requires_full '9f499afb393fa8e574f1021e8c269eb0f9fb396d'

# CI-09: a docs-only commit after source work is not a source-test result; classify the full diff.
assert_classification 'true false both all false false' \
    $'docs/step4/REMAINING_GATES.md\tmodified\nshared/src/main/java/com/shilapi/xcertplay/airplay/AirPlaySession.kt\tmodified'

# CI-10: a successful Draft check/quick-baseline is not a full Android baseline or release verification.
is_ci_full_android_baseline "check,android-instrumentation,host-baseline"
! is_ci_full_android_baseline "check,quick-baseline"
! is_ci_full_android_baseline "check"
grep -Fq 'DRAFT_ONLY_NOT_RELEASE_READY' "$repo_root/.github/workflows/android.yml"

printf '%s\n' 'Android CI classifier fixtures passed.'
