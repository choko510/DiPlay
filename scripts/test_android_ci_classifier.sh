#!/usr/bin/env bash

set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/android_ci_classifier.sh"

is_ci_docs_only_path "docs/step4/TEST_MATRIX.md"
is_ci_docs_only_path ".scratchpad/current.md"
! is_ci_docs_only_path "scripts/test_android_ci_classifier.sh"

is_ci_workflow_sensitive_path ".github/workflows/android.yml"
is_ci_workflow_sensitive_path "scripts/run_android_instrumentation.sh"
is_ci_workflow_sensitive_path "scripts/android_ci_classifier.sh"
is_ci_workflow_sensitive_path "scripts/check_android_instrumentation_evidence.sh"
! is_ci_workflow_sensitive_path "shared/src/main/java/com/shilapi/xcertplay/airplay/AirPlaySession.kt"

is_ci_native_path "shared/src/main/jni/Android.mk"
is_ci_android_mk_link_path "shared/src/main/jni/Android.mk"
is_ci_native_path "shared/src/main/jni/Application.mk"
is_ci_android_mk_link_path "shared/src/main/jni/Application.mk"
is_ci_native_path "shared/src/main/jni/dsp/dsp_engine.c"
! is_ci_android_mk_link_path "shared/src/main/jni/dsp/dsp_engine.c"
! is_ci_native_path "shared/src/main/java/com/shilapi/xcertplay/airplay/AirPlaySession.kt"

is_ci_full_android_baseline "check,android-instrumentation,host-baseline"
! is_ci_full_android_baseline "check,quick-baseline"

printf '%s\n' 'Android CI classifier fixtures passed.'
