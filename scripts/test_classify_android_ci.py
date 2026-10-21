#!/usr/bin/env python3
import unittest
from unittest.mock import patch

from classify_android_ci import classify_event, classify_paths, compare_url


class ClassifyAndroidCiTest(unittest.TestCase):
    def test_same_repository_compare_uses_base_and_head_shas(self):
        url = compare_url(
            "choko510/DiPlay",
            "choko510/DiPlay",
            "base-sha",
            "head-sha",
        )

        self.assertEqual(
            "https://api.github.com/repos/choko510/DiPlay/compare/base-sha...head-sha?per_page=100",
            url,
        )

    def test_fork_compare_qualifies_the_head_owner(self):
        url = compare_url(
            "choko510/DiPlay",
            "contributor/DiPlay",
            "base-sha",
            "head-sha",
        )

        self.assertEqual(
            "https://api.github.com/repos/choko510/DiPlay/compare/choko510:base-sha...contributor:head-sha?per_page=100",
            url,
        )

    def test_docs_and_workflow_only_changes_use_static_lane(self):
        result = classify_paths(
            [
                "docs/step4/BASELINE.md",
                ".scratchpad/current.md",
                ".github/workflows/android.yml",
                ".gitignore",
            ],
            draft=True,
        )

        self.assertEqual(
            {
                "full_host": False,
                "quick": False,
                "native": False,
                "instrumentation": False,
                "safe": True,
            },
            result,
        )

    def test_draft_kotlin_change_uses_unit_lane_without_native_or_emulator(self):
        result = classify_paths(["common/src/main/java/com/example/Host.kt"], draft=True)

        self.assertTrue(result["quick"])
        self.assertFalse(result["full_host"])
        self.assertFalse(result["native"])
        self.assertFalse(result["instrumentation"])

    def test_draft_native_change_adds_native_suite(self):
        result = classify_paths(["shared/src/main/jni/dsp/dsp_engine.c"], draft=True)

        self.assertTrue(result["quick"])
        self.assertTrue(result["native"])
        self.assertFalse(result["full_host"])
        self.assertFalse(result["instrumentation"])

    def test_native_test_script_change_runs_native_suite(self):
        result = classify_paths(["scripts/test_dsp_native.sh"], draft=True)

        self.assertTrue(result["native"])

    def test_ready_source_change_runs_full_host_and_emulator_lanes(self):
        result = classify_paths(["shared/src/main/java/com/example/Transport.kt"], draft=False)

        self.assertTrue(result["full_host"])
        self.assertFalse(result["quick"])
        self.assertTrue(result["native"])
        self.assertTrue(result["instrumentation"])

    def test_unknown_paths_are_verified_instead_of_skipped(self):
        result = classify_paths(["new/unknown/path.bin"], draft=True)

        self.assertTrue(result["full_host"])
        self.assertFalse(result["quick"])
        self.assertTrue(result["native"])
        self.assertTrue(result["instrumentation"])
        self.assertFalse(result["safe"])

    def test_missing_path_list_falls_back_to_full_verification(self):
        result = classify_paths([], draft=True)

        self.assertTrue(result["full_host"])
        self.assertFalse(result["quick"])
        self.assertTrue(result["native"])
        self.assertTrue(result["instrumentation"])
        self.assertFalse(result["safe"])

    def test_compare_api_error_falls_back_to_full_verification(self):
        event = {
            "pull_request": {
                "draft": True,
                "base": {"sha": "base-sha"},
                "head": {"sha": "head-sha", "repo": {"full_name": "choko510/DiPlay"}},
            },
        }
        with patch("classify_android_ci.fetch_changed_paths", side_effect=TimeoutError):
            result = classify_event("pull_request", event, "choko510/DiPlay", "example-token")

        self.assertTrue(result["full_host"])
        self.assertFalse(result["quick"])
        self.assertTrue(result["native"])
        self.assertTrue(result["instrumentation"])
        self.assertFalse(result["safe"])


if __name__ == "__main__":
    unittest.main()
