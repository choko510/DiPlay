#!/usr/bin/env python3
"""Apply the B07 per-SHA decisions to the fixed EX-08 delta-111 overlay."""

from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path

from step4_ex08_delta111 import load_and_validate_inputs, render_review_markdown


ROOT = Path(__file__).resolve().parents[1]
OVERLAY = ROOT / "docs/step4/ex08"


def reviewed_inputs() -> tuple[list[dict], list[dict]]:
    meta = json.loads((OVERLAY / "upstream_delta_111.meta.json").read_text(encoding="utf-8"))
    commits = [
        json.loads(line)
        for line in (OVERLAY / "upstream_delta_111.commits.jsonl").read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    return meta, commits


def decisions(commits: list[dict]) -> list[dict]:
    categories: dict[int, str] = {}
    by_index = {row["local_index"]: row for row in commits}

    def assign(indexes: list[int], category: str) -> None:
        categories.update({index: category for index in indexes})

    assign([1, 2, 3, 40], "GEELY_CLUSTER")
    assign([4], "HID_TOUCH")
    assign([5], "WIRELESS_BLUETOOTH")
    assign([6], "WIRELESS_P2P")
    assign([7, 8], "WIRELESS_CAR_AP_CREDENTIALS")
    assign([9], "UI_TOUCH_ARBITRATION")
    assign([10, 13], "UI_HOTSPOT_STATE")
    assign([11], "UI_LOCALIZATION")
    assign([12], "UI_DISPLAY_SUMMARY")
    assign([14], "SETTINGS_BACKUP")
    assign([15], "UI_SETUP_GUIDE")
    assign([16], "UI_LAUNCHER_BUBBLE")
    assign([17], "WIRELESS_API28_P2P")
    assign([18, 19, 28], "BYD_RHYTHM")
    assign([20], "UI_FRAME_RATE")
    assign([21], "WIRELESS_TETHER_STATE")
    assign([22], "SESSION_RECREATION")
    assign([23], "SETTINGS_SERVER_LIFECYCLE")
    assign([24], "UI_SETTINGS_SEARCH")
    assign([25], "UI_UPDATE_RESUME")
    assign([26], "WIRELESS_INTERFACE_SELECTION")
    assign([27], "BYD_DILINK_CLUSTER")
    assign([29, 30], "WIRED_STARTSESSION")
    assign([31], "AUDIO_MIC_CLOCK")
    assign([32], "UI_HEVC_LABEL")
    assign([33], "AUDIO_MEDIA_KEY_DIAGNOSTIC")
    assign([34], "WIRELESS_BONJOUR_DNS")
    assign([35], "AUDIO_CLOCK_TRIM_TEST")
    assign([36], "AUDIO_CLOCK_TRIM")
    assign([37], "AUDIO_MIC_DIAGNOSTICS")
    assign([38], "AUDIO_MODE_OWNERSHIP")
    assign([39], "BYD_VEHICLE_TELEMETRY")
    assign([42], "OEM_CLUSTER_LOCALIZATION")
    assign([47], "BYD_CLUSTER_MUSIC")
    assign([49], "MIXED_BUNDLE_BYD_CLUSTER_AND_UI")
    assign([50], "BYD_CLUSTER_MUSIC_TEST")
    assign([51], "SETTINGS_BACKUP_LIFECYCLE")
    assign([52, 53], "VEHICLE_KEY_LEARNING")
    assign([54], "UI_PHYSICAL_SIZE_STEPS")
    assign([55], "UI_PHYSICAL_SIZE_SUMMARY")
    assign([56], "UI_PHYSICAL_SIZE_SLIDER")
    assign([57], "TCP_LIVENESS_API28_TEST")
    assign([58], "TCP_LIVENESS_PRIVATE_API")
    assign([59], "CLUSTER_MUSIC_ARTWORK")
    assign([60, 63], "BYD_CLUSTER_SPEED_CARD")
    assign([61], "UI_LAUNCHER_BUBBLE_ICON")
    assign([62], "UI_AMBIENT_NOTE_LAYOUT")
    assign([64, 65], "BYD_NETWORK_ADB_STATUS")
    assign([110], "UPSTREAM_RELEASE_NOTES")

    skip_oem = {1, 2, 3, 18, 19, 27, 28, 39, 40, 42, 47, 50, 60, 63, 64, 65}
    reject = {7, 8, 49, 58, 110}
    fork_superior = {4, 22}
    branch_sync = {41, 43, 44, 45, 46, 48, 104, 106, 108}
    merge_sources = {
        66:[21], 67:[26], 68:[36], 69:[31], 70:[19], 71:[24], 72:[25], 73:[23], 74:[11],
        75:[14], 76:[22], 77:[6], 78:[16], 79:[20], 80:[18], 81:[13], 82:[17], 83:[5], 84:[15],
        85:[12], 86:[9], 87:[4], 88:[8], 89:[30], 90:[28], 91:[38], 92:[32], 93:[33], 94:[34],
        95:[37], 96:[39], 97:[52, 53], 98:[58], 99:[65], 100:[42], 101:[61], 102:[62],
        103:[47, 49, 50], 105:[54, 55, 56], 107:[59, 60, 63], 109:[1, 2, 3, 40, 42], 111:[110],
    }
    merge_indexes = {row["local_index"] for row in commits if row["is_merge"]}
    source_indexes = {row["local_index"] for row in commits if not row["is_merge"]}
    if branch_sync | set(merge_sources) != merge_indexes or set(categories) != source_indexes:
        raise ValueError("B07 index decisions do not cover the pinned commit topology")

    owners = {
        "HID_TOUCH":"shared/src/main/java/com/shilapi/xcertplay/airplay/AirPlayTouchSlots.kt",
        "SESSION_RECREATION":"shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt",
        "WIRED_STARTSESSION":"shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt",
        "WIRELESS_BLUETOOTH":"shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt",
        "WIRELESS_P2P":"shared/src/main/java/com/shilapi/xcertplay/network/WifiP2pGroupManager.kt",
        "WIRELESS_CAR_AP_CREDENTIALS":"shared/src/main/java/com/shilapi/xcertplay/network/ManualHotspotManager.kt",
        "WIRELESS_API28_P2P":"shared/src/main/java/com/shilapi/xcertplay/network/WifiP2pGroupManager.kt",
        "WIRELESS_TETHER_STATE":"shared/src/main/java/com/shilapi/xcertplay/network/ManualHotspotManager.kt",
        "WIRELESS_INTERFACE_SELECTION":"shared/src/main/java/com/shilapi/xcertplay/network/ManualHotspotManager.kt",
        "WIRELESS_BONJOUR_DNS":"shared/src/main/java/com/shilapi/xcertplay/network/CarPlayBonjour.kt",
        "AUDIO_MIC_CLOCK":"shared/src/main/java/com/shilapi/xcertplay/media/MicrophoneUplink.kt",
        "AUDIO_MIC_DIAGNOSTICS":"shared/src/main/java/com/shilapi/xcertplay/media/MicrophoneUplink.kt",
        "AUDIO_MODE_OWNERSHIP":"shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt",
        "TCP_LIVENESS_API28_TEST":"shared/src/main/java/com/shilapi/xcertplay/network/TcpLiveness.kt",
        "TCP_LIVENESS_PRIVATE_API":"shared/src/main/java/com/shilapi/xcertplay/network/TcpLiveness.kt",
        "UI_FRAME_RATE":"common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt",
        "UI_PHYSICAL_SIZE_STEPS":"shared/src/main/java/com/shilapi/xcertplay/airplay/AirPlayDisplaySettings.kt",
        "UI_PHYSICAL_SIZE_SUMMARY":"common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt",
        "UI_PHYSICAL_SIZE_SLIDER":"common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt",
        "UI_HEVC_LABEL":"common/src/main/res/values-en/host_strings.xml",
        "SETTINGS_BACKUP":"common/src/main/assets/settings-backup.html",
        "SETTINGS_BACKUP_LIFECYCLE":"common/src/main/assets/settings-backup.html",
        "UI_HOTSPOT_STATE":"common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt",
        "UI_TOUCH_ARBITRATION":"common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt",
        "UI_DISPLAY_SUMMARY":"common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt",
        "UI_LOCALIZATION":"common/src/main/assets/settings-backup.html",
        "UI_SETUP_GUIDE":"common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt",
        "UI_LAUNCHER_BUBBLE":"common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt",
        "UI_LAUNCHER_BUBBLE_ICON":"common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt",
        "UI_SETTINGS_SEARCH":"common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt",
        "UI_UPDATE_RESUME":"common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt",
        "SETTINGS_SERVER_LIFECYCLE":"common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt",
        "AUDIO_MEDIA_KEY_DIAGNOSTIC":"shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt",
        "AUDIO_CLOCK_TRIM_TEST":"shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt",
        "AUDIO_CLOCK_TRIM":"shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt",
        "VEHICLE_KEY_LEARNING":"common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt",
        "CLUSTER_MUSIC_ARTWORK":"common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt",
        "UI_AMBIENT_NOTE_LAYOUT":"common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt",
        "GEELY_CLUSTER":"docs/step4/UPSTREAM_DECISION_REGISTER.md",
        "BYD_RHYTHM":"docs/step4/UPSTREAM_DECISION_REGISTER.md",
        "BYD_DILINK_CLUSTER":"docs/step4/UPSTREAM_DECISION_REGISTER.md",
        "BYD_VEHICLE_TELEMETRY":"docs/step4/UPSTREAM_DECISION_REGISTER.md",
        "OEM_CLUSTER_LOCALIZATION":"docs/step4/UPSTREAM_DECISION_REGISTER.md",
        "BYD_CLUSTER_MUSIC":"docs/step4/UPSTREAM_DECISION_REGISTER.md",
        "BYD_CLUSTER_MUSIC_TEST":"docs/step4/UPSTREAM_DECISION_REGISTER.md",
        "BYD_CLUSTER_SPEED_CARD":"docs/step4/UPSTREAM_DECISION_REGISTER.md",
        "BYD_NETWORK_ADB_STATUS":"docs/step4/UPSTREAM_DECISION_REGISTER.md",
        "MIXED_BUNDLE_BYD_CLUSTER_AND_UI":"docs/step4/UPSTREAM_DECISION_REGISTER.md",
        "UPSTREAM_RELEASE_NOTES":"docs/step4/UPSTREAM_DECISION_REGISTER.md",
    }
    next_evidence = {
        "WIRELESS_BLUETOOTH":"On TS10/K706 API 28+ capture adapter state transitions and prove one retry after TURNING_ON without overlap; add a retry-generation regression.",
        "WIRELESS_P2P":"Test delayed, failed, and post-cancel requestGroupInfo callbacks during retained-group reclaim on API 28/29/35.",
        "UI_TOUCH_ARBITRATION":"Reproduce a Settings swipe over CarPlay and assert pointer ownership plus exactly one terminal UP/CANCEL.",
        "UI_HOTSPOT_STATE":"Test menu close/reopen and Activity recreation after a hotspot change; assert current SSID/status.",
        "UI_LOCALIZATION":"Confirm the locale is supported and render backup/code pages including translations and fallbacks.",
        "UI_DISPLAY_SUMMARY":"Add localized summary assertions for display, requested size, and saved value after recreation.",
        "SETTINGS_BACKUP":"Round-trip supported settings and verify secrets/pairing data remain excluded.",
        "UI_SETUP_GUIDE":"Add an unknown-vehicle fixture and assert only advertised capabilities appear across supported locales.",
        "UI_LAUNCHER_BUBBLE":"Measure bubble bounds at supported resolutions/densities with system insets and add a positioning regression.",
        "WIRELESS_API28_P2P":"Run API 28/29 permission, connection, and teardown tests on target hardware without replacing the local-hotspot path.",
        "UI_FRAME_RATE":"Test Settings rendering/persistence and verify reopened value matches phone-advertised maxFPS.",
        "WIRELESS_TETHER_STATE":"Test unknown, denied, off, and on states on API 28/29/35; prove manual join never maps unknown to false-off.",
        "SETTINGS_SERVER_LIFECYCLE":"Reproduce fixed-main shutdown ordering; test listener/socket close, worker termination, and late callback rejection without blocking UI.",
        "UI_SETTINGS_SEARCH":"Test actual Settings search/index behavior for slider titles and localized labels.",
        "UI_UPDATE_RESUME":"Test background update completion, resume, dismissal, and repeated resume without duplicate notices.",
        "WIRELESS_INTERFACE_SELECTION":"Add mobile/VPN/Wi-Fi Direct/hotspot interface fixtures; confirm chosen route on TS10/K706.",
        "WIRED_STARTSESSION":"B08 must correlate same-attempt StartSession, exact full NCM OUT, SYN, SYN-ACK TUN/OUT, accept, RECORD, SETUP, first frame, and teardown on TS10/K706; no extra 0x4301 before evidence and watchdog design.",
        "AUDIO_MIC_CLOCK":"Add payload-free monotonic capture/read/write/RTP timestamps and validate clock domain/drift on TS10/K706 without changing RTP increments.",
        "UI_HEVC_LABEL":"If search discoverability is required, add HEVC/H.265 search/localization tests; do not change codec negotiation for a title-only patch.",
        "AUDIO_MEDIA_KEY_DIAGNOSTIC":"Define diagnostic value, consent, retention, and redaction excluding key content/device identity.",
        "WIRELESS_BONJOUR_DNS":"Measure Bonjour discovery-to-probe on target; if delayed, test formatting without hostname lookup.",
        "AUDIO_CLOCK_TRIM_TEST":"Approve a clock-trim requirement and provide sustained/short-burst A/V evidence before testing an unadopted controller.",
        "AUDIO_CLOCK_TRIM":"Measure drift, burst recovery, pitch, and A/V sync on TS10/K706 before considering an opt-in controller.",
        "AUDIO_MIC_DIAGNOSTICS":"Use payload-free target capture tests and document consent/retention; prove bounded fields contain no samples or identifiers.",
        "AUDIO_MODE_OWNERSHIP":"Measure mode/route before/during/after call, Siri, navigation; prove teardown removes only DiPlay's request.",
        "VEHICLE_KEY_LEARNING":"Confirm requirement and test input delivery, pass-through, lifecycle, privacy, and TV-remote coexistence on TS10/K706.",
        "UI_PHYSICAL_SIZE_STEPS":"Confirm 5 mm precision requirement; test snap/persistence and supported-iPhone physical size/safeArea.",
        "UI_PHYSICAL_SIZE_SUMMARY":"Test saved-size display/restoration in supported locales without changing physical-width or Split behavior.",
        "UI_PHYSICAL_SIZE_SLIDER":"Test range, snap, persistence, and advertised AirPlay value before moving settings ownership.",
        "TCP_LIVENESS_API28_TEST":"Add API 28 coverage proving only Socket.keepAlive is enabled and AirPlaySession retains the socket; test API 29+ separately.",
        "CLUSTER_MUSIC_ARTWORK":"Identify a supported generic cluster output owner; then test artwork ordering, late results, and fade lifecycle.",
        "UI_LAUNCHER_BUBBLE_ICON":"Confirm requested icon treatment and render contrast across densities and light/dark backgrounds.",
        "UI_AMBIENT_NOTE_LAYOUT":"Provide a screenshot/accessibility-boundary reproduction and deterministic layout assertion.",
        "SETTINGS_BACKUP_LIFECYCLE":"Show a fixed-main reproduction or requirement; test stop closes listener/socket and rejects late callbacks.",
    }
    related = {category: f"EX-08 B07; {category.replace('_', ' ').lower()}" for category in set(categories.values())}
    related.update({
        "HID_TOUCH":"F-01/F-02; fixed-main HID touch ownership",
        "SESSION_RECREATION":"F-09; fixed-main Activity/session ownership",
        "WIRED_STARTSESSION":"F-03/F-04; observation-only B08 gate",
        "AUDIO_MIC_CLOCK":"B06/F-12; microphone capture/RTP clock",
        "AUDIO_MIC_DIAGNOSTICS":"B06/F-12; microphone silence diagnostics",
        "TCP_LIVENESS_API28_TEST":"F-13; API 28 public-API boundary",
        "TCP_LIVENESS_PRIVATE_API":"F-13; private API reflection rejected",
    })
    fixed_facts = {
        "GEELY_CLUSTER":"This is a Geely/Lynk cluster projection and handoff. Fixed main has no compatible vehicle-specific cluster sink; generic CarPlay display and local Split are separate.",
        "BYD_RHYTHM":"This depends on BYD car-rhythm output/signatures. Fixed main has no BYD rhythm owner or generic signal for this behavior.",
        "BYD_DILINK_CLUSTER":"This targets the DiLink 3 dashboard-map stream/layout. Fixed main has no DiLink cluster owner; local Split is a different surface.",
        "BYD_VEHICLE_TELEMETRY":"This reports car wheel-speed telemetry. Fixed main has no generic vehicle-data service or wheel-speed input.",
        "OEM_CLUSTER_LOCALIZATION":"These strings label an OEM dashboard-map shape control that is absent from fixed main's generic display and local Split settings.",
        "BYD_CLUSTER_MUSIC":"This changes the BYD cluster music card. Fixed main has no BYD cluster-music sink.",
        "BYD_CLUSTER_MUSIC_TEST":"This test covers a BYD cluster-song gate, not an existing fixed-main CarPlay behavior.",
        "BYD_CLUSTER_SPEED_CARD":"This changes an OEM speedometer/album card. Fixed main has no matching cluster canvas or vehicle-speed owner.",
        "BYD_NETWORK_ADB_STATUS":"This belongs to BYD vehicle-data/network-ADB settings, which are absent from fixed main.",
        "MIXED_BUNDLE_BYD_CLUSTER_AND_UI":"The source combines a BYD cluster-music default, Italian strings, and an Activity-owner change. These owners need separate evidence; importing the aggregate revives excluded BYD behavior.",
        "UPSTREAM_RELEASE_NOTES":"These notes describe upstream merges rather than the fixed-main fork or PR #45; reusing them would claim changes not adopted or verified here.",
        "HID_TOUCH":"Fixed main already has stable pointer-to-HID slot mapping, last-coordinate release, and failed-UP retry before slot reuse.",
        "SESSION_RECREATION":"The Activity restores the live background session; currentAirPlaySession filters a closed controller/session.",
        "WIRED_STARTSESSION":"Existing generation-owned startup recovery and the 15 s hard watchdog remain authoritative; NCM distinguishes full Sent from NotReady/Failed.",
        "WIRELESS_INTERFACE_SELECTION":"The local-hotspot interface filter selects the head-unit AP, not the manual carrier-search owner.",
        "WIRELESS_BONJOUR_DNS":"Fixed main uses numeric hostAddress on Bonjour startup; no target measurement shows a remaining reverse-DNS delay.",
        "UI_FRAME_RATE":"A frame-rate control and maxFPS serialization exist; the Settings presentation lacks a dedicated UI assertion.",
        "UI_HEVC_LABEL":"The fixed-main codec option already says HEVC (H.265); a search-title regression is absent.",
        "UI_PHYSICAL_SIZE_STEPS":"Physical width persists and model tests exist, but fixed main uses 50 mm steps while the source requests 5 mm.",
        "UI_PHYSICAL_SIZE_SUMMARY":"Fixed main persists selected CarPlay size; localized screen restoration is not tested.",
        "UI_PHYSICAL_SIZE_SLIDER":"A physical-length slider and persistence already exist in the host; equivalence with the upstream screen owner is unproven.",
        "TCP_LIVENESS_API28_TEST":"TcpLiveness returns before descriptor tuning below API 29, but no local API 28 regression pins this boundary.",
        "TCP_LIVENESS_PRIVATE_API":"Fixed main uses public descriptor APIs on API 29+ and returns before descriptor access on older Android.",
        "CLUSTER_MUSIC_ARTWORK":"Fixed main has no generic cluster music-card owner; this is separate from CarPlay Split and DSP.",
    }

    output = []
    for commit in commits:
        index, sha = commit["local_index"], commit["commit_sha"]
        is_merge = commit["is_merge"]
        linked_indexes: list[int] = []
        if is_merge:
            relationship = "BRANCH_SYNC_ONLY" if index in branch_sync else (
                "SOURCE_DOCS_INTEGRATION_DUPLICATE" if index == 111 else "SOURCE_FEATURE_INTEGRATION_DUPLICATE"
            )
            linked_indexes = [] if index in branch_sync else merge_sources[index]
            category = "MERGE_BRANCH_SYNC" if index in branch_sync else "MERGE_SOURCE_INTEGRATION"
            disposition = "REJECT_UNSAFE"
            sources = ", ".join(f"#{i} {by_index[i]['subject']}" for i in linked_indexes)
            reason = (
                f"Merge #{index} is branch synchronization ({commit['subject']}); its first-parent patch imports broad history, not one isolated behavior."
                if index in branch_sync else
                f"Merge #{index} integrates source commit(s) {sources}; the wrapper is not an independent change and must not be cherry-picked with its source."
            )
            summary = (
                f"Parent topology classifies this as branch-sync-only. First-parent patch: {commit['patch_bytes']} bytes, SHA-256 {commit['patch_sha256']}."
                if index in branch_sync else
                f"Parent topology and first-parent patch classify this as an integration duplicate ({commit['patch_bytes']} bytes; SHA-256 {commit['patch_sha256']})."
            )
            owner = "docs/step4/ex08/upstream_delta_111.commits.jsonl"
            missing = []
            related_step4 = (
                "EX-08 B07; branch synchronization only" if index in branch_sync else
                f"EX-08 B07; duplicate integration of source index {', '.join(map(str, linked_indexes))}"
            )
            test_evidence = f"PASS_STATIC: parent list and first-parent patch hash pinned; merge fate={relationship}. No merge-specific runtime behavior is claimed."
            proof = [f"[upstream merge]({commit['source_url']})"]
            proof += [f"[source #{i}: {by_index[i]['commit_sha'][:12]}]({by_index[i]['source_url']})" for i in linked_indexes]
            proof.append("[pinned parent/patch manifest](../../docs/step4/ex08/upstream_delta_111.commits.jsonl)")
        else:
            category = categories[index]
            disposition = "SKIP_OEM" if index in skip_oem else "REJECT_UNSAFE" if index in reject else "FORK_SUPERIOR" if index in fork_superior else "NEED_EVIDENCE"
            owner = owners.get(category, "docs/step4/UPSTREAM_DECISION_REGISTER.md")
            summary = fixed_facts.get(category, f"The source adds {commit['subject']}; the adjacent fixed-main owner is {owner}. No fixed-main failure or approved requirement is established for this behavior.")
            if index == 7:
                summary = "Adds ADB-backed import for the car-owned hotspot configuration. Fixed main only supports user-entered credentials for joining; import is a separate permission and credential owner."
            elif index == 8:
                summary = "Suppresses the warning before writing imported hotspot details back to the car; this couples readback to a write and can disrupt other devices."
            elif index == 49:
                summary = "Mixes BYD cluster-music defaults, Italian strings, and an ensureGateOpen Activity-owner change; separate owners make the aggregate unsafe to import."
            elif index == 58:
                summary = "Uses reflection on a non-SDK socket file-descriptor method; fixed main returns before descriptor access below API 29 and uses public APIs above it."
            elif index == 110:
                summary = "Upstream release notes do not describe the fixed-main fork or PR #45; reuse would claim changes not adopted or verified here."
            reason = f"Reviewed source #{index} and its pinned first-parent patch; selected {disposition} under the B07 evidence policy."
            missing = [next_evidence[category]] if disposition == "NEED_EVIDENCE" else []
            related_step4 = related[category]
            test_evidence = "PASS_STATIC: fixed-main owner and pinned first-parent patch reviewed; no fixed-main failing test is claimed."
            proof = [f"[upstream commit and first-parent patch]({commit['source_url']})"]
            proof.append(f"[fixed-main owner: {owner}](../../{owner})" if (ROOT / owner).is_file() else f"[owner at reviewed upstream SHA: {owner}]({commit['source_url']})")
            if index == 4:
                proof += [
                    "[AirPlayTouchSlots regression](../../shared/src/test/java/com/shilapi/xcertplay/airplay/AirPlayTouchSlotsTest.kt)",
                    "[Android pointer-ID regression](../../shared/src/test/java/com/shilapi/xcertplay/media/CarPlayTouchMapperTest.kt)",
                ]
                test_evidence = "PASS_LOCAL: shared suite 646/646 at 686516f5; stable slot mapping, last-coordinate lift, pointer-index changes, and failed-UP resend are asserted."
            elif index == 22:
                proof.append("[Activity/session regression](../../common/src/test/java/com/shilapi/xcertplay/CarPlayHostActivityLocalScaleTest.kt)")
                test_evidence = "PASS_LOCAL: common/shared 849/849 at ae63a365; Activity retains the live session and currentAirPlaySession() returns null after close."
            relationship = ""
        output.append({
            "commit_sha":sha, "current_review_state":"REVIEWED", "primary_category":category,
            "disposition":disposition, "decision_reason":reason, "fixed_main_summary":summary,
            "fork_owner_file":owner, "proof":proof, "missing_evidence":missing,
            "related_step4":related_step4, "test_evidence":test_evidence,
            "rollback_plan":("No upstream patch imported. If later evidence justifies adaptation, isolate it in one PR #45 commit with a regression test and revert that commit."
                if not is_merge and disposition not in {"SKIP_OEM", "REJECT_UNSAFE"} else
                "No upstream patch imported; retain fixed-main behavior. Do not cherry-pick this merge wrapper."
                if is_merge else "No upstream patch imported; preserve fixed-main behavior and the v3 OEM exclusion."),
            "merge_relationship":relationship, "merge_source_indexes":linked_indexes,
        })
    return output


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true", help="write B07 review overrides and report")
    args = parser.parse_args()
    meta, commits = reviewed_inputs()
    reviews = decisions(commits)
    overrides_path = OVERLAY / "review_overrides_111.json"
    encoded = (json.dumps(reviews, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8")
    if args.write:
        overrides_path.write_bytes(encoded)
        meta["review_overrides_sha256"] = hashlib.sha256(encoded).hexdigest()
        meta["review_state_policy"] = "B07 per-SHA fixed-main review recorded; NEED_EVIDENCE rows identify concrete next evidence."
        meta["merge_role_policy"] = "B07 classifies all merges as source-feature/PR integration duplicates or branch-sync-only; no merge wrapper is independently adopted."
        (OVERLAY / "upstream_delta_111.meta.json").write_text(json.dumps(meta, ensure_ascii=False, sort_keys=True, indent=2) + "\n", encoding="utf-8")
        report = render_review_markdown(meta, commits, reviews)
        (ROOT / "docs/step4/EX08_DELTA111_REVIEW.md").write_text(report, encoding="utf-8")
        print(dict(Counter(row["disposition"] for row in reviews)))
        return 0
    load_and_validate_inputs(ROOT)
    expected = overrides_path.read_bytes()
    if expected != encoded:
        raise SystemExit("review overrides differ from this B07 decision source; run with --write")
    report = render_review_markdown(meta, commits, reviews)
    report_path = ROOT / "docs/step4/EX08_DELTA111_REVIEW.md"
    if not report_path.exists() or report_path.read_text(encoding="utf-8") != report:
        raise SystemExit("review report differs from pinned decisions; run with --write")
    print(f"validated {len(reviews)} per-SHA reviews; {sum(bool(row['merge_relationship']) for row in reviews)} merges classified")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
