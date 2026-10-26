# EX-08 Delta-111 Review Record

## Frozen upstream range

- Repository: `shihabal3amri/DiPlay`
- Base: `6d3b8a8ce927cb4b115b8c3881f17619433e93b1`
- Head: `143f4674b60b564a16baae5110b3b90fb7277631`
- GitHub compare: 111 commits ahead, 0 behind, 117 changed final-tree paths, +4,357 / -376 lines.
- Commit topology: 60 non-merges and 51 merges, verified from immutable parent lists.
- Patch provenance: one archived first-parent binary diff per commit, with SHA-256 and byte count pinned in the commit manifest and archive metadata.

The 111 SHAs are disjoint from the frozen legacy 1,289-row set. They are appended as a separate overlay; the original 1,289 serialized CSV rows remain a byte-identical prefix. The generated ledger now has 1,400 unique rows.

## Current per-commit state

All 111 overlay rows are metadata-only `NEED_EVIDENCE` decisions. No per-commit semantic comparison with fixed main, adoption, or regression proof is claimed. All merge commits retain an explicit merge role; non-empty first-parent merge patches are conservatively marked `OTHER_MERGE` pending review. The archive preserves source material for later review but does not establish that an upstream change is safe or needed.

The original 1,289 rows retain their prior decisions and evidence unchanged. Combined current dispositions are 11 `ADAPT`, 25 `FORK_SUPERIOR`, 191 `SKIP_OEM`, 709 `REJECT_UNSAFE`, and 464 `NEED_EVIDENCE` (the former 353 plus these 111 pending rows).

## Review focus from the 2026-10-26 findings

These are review topics for subsequent STEP4 batches, not per-SHA mappings. No row in the new overlay is assigned to a topic without a semantic review.

| Topic | Current source-level handling / gate |
|---|---|
| F8-01: MAIN/ALT SETUP followed by TCP close before RECORD | The close path routes to the existing wired transport recovery policy after screen setup. State-model tests cover before/after SETUP, before/after RECORD, and stale watchdog attempts. Supported-iPhone behavior remains `BLOCKED_DEVICE`. |
| F8-02: RTSP receive allocation and size bounds | Incremental parser uses 64 KiB header and 4 MiB body limits; fragmented/coalesced messages and oversized declarations have unit coverage. Supported-device compatibility remains `BLOCKED_DEVICE`. |
| F8-03: simultaneous control connections | Active control sessions are capped at four and excess sockets are closed. Socket permit release and valid-session recovery under reconnect traffic remain `BLOCKED_DEVICE`. |
| DSP Split repeated unbounded-height diagnostics | Duplicate signatures no longer build a log line or emit Logcat output; only a newly retained signature is formatted and logged. Signature history remains bounded to 64, with up to 16 included in a report. SH-M27 rendering remains `BLOCKED_DEVICE`. |
| DSP Split Scaffold inset and scroll order | Inset padding is applied before the scroll modifier. Scroll/inset behavior on SH-M27 remains `BLOCKED_DEVICE`. |
| DSP Split ComposeView measurement fidelity | The existing pane calculates the child MeasureSpecs, passes those exact values to `child.measure()`, then logs the child's measured dimensions. Direct `ComposeView.onMeasure()` override is unavailable because the class is final in this dependency version. SH-M27 confirmation remains `BLOCKED_DEVICE`. |
| F-01..F-15 from the integrated review | Preserve as later review checks: fixed HID pointer-slot ownership; UP coordinates; delayed USB OUT versus the existing 15 s timeout; evidence for any additional `0x4301`; eight-port `NcmTrafficCounter`; VLAN/IPv6 extension-header classification; first full USB OUT monotonic timing; stale old-attachment accept; Activity recreation with a closed session; `AudioManager` mode restoration; music clock drift; microphone silence/ownership/RTP clock; API 28 TCP-liveness reflection; the legacy generator count; and CI success versus runtime-test coverage. No new overlay SHA-to-topic mapping is asserted here. |

## Validation boundary

The overlay validator checks fixed range metadata, SHA/order/uniqueness, parent-derived merge counts, patch archive entries and digests, review-override completeness, and byte preservation of the legacy CSV prefix. The 17 regression tests and generated-ledger `--check` establish inventory consistency only. They do not complete semantic review, device compatibility, API 35/full-host verification, signing, or release acceptance.
