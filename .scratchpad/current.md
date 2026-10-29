# Current Status — DiPlay DSP Studio PR #52 (2026-10-30)

## Latest source and validation

- PR #52 source/test head is `00ac7453acd325f3523e1e832c01fb2a8375cf68` on `codex/dsp-studio-final`, based on PR #45's unchanged commit `07c0141c186b8e8df76dce097593170ab28b7faf`.
- The supplied re-audit's F-03 was fixed by comparing requested linear targets against effective current linear gain, removing the persisted dB/current-state ambiguity, and retaining active target linear for ramp compatibility. The Native regression reproduced the failure at `53d15588` and passes at `00ac7453`.
- Pull-request run [#589](https://github.com/choko510/DiPlay/actions/runs/40926483922) passed all required jobs at the exact source/test head. Native, ASan/UBSan, Shared/Common, lint, Debug/Benchmark builds, JNI/R8, API 35, host-baseline, and Release verification passed. API 35 passed Shared Release 23/23 and Common Debug 29/29 after retrying one FullHeavyChain wall-clock p99 failure at 63.232 ms vs. 21.332 ms; both attempts are preserved, and the retry does not explain the first tail.
- Local Native DSP and Dynamic EQ HOT suites pass via MSYS2. Local ASan/UBSan is unavailable because libasan/libubsan are missing; hosted sanitizer passed.
- Evidence ledger remains 170 IDs: PASS 2 / PARTIAL 162 / NOT_RUN 6. No acceptance status was promoted.

## Remaining acceptance

- TS10/K706+iPhone audio/USB/video/touch, route-volume correlation, A/V, CPU/RSS/thermal, reconnect cycles and soak remain `NOT_RUN`. No post-F-03 independent review has run. PR #52 remains open and unmerged; PR #45 and `main` are unchanged.
- No source-correctable action remains for F-03. Hardware acceptance and user merge approval are still required; keep PR #52 open and unmerged.
