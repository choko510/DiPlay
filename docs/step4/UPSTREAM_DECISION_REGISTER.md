# PR-00 — Upstream Decision Register Seed

**Implementation baseline:** fork `main` at `95ba398f95ee104527812cd2b095977d71c51f98`. **Comparison only:** upstream `main` at `6d3b8a8ce927cb4b115b8c3881f17619433e93b1`. No open PR branch, implementation, test result, or review was used as evidence.

PR-00 does not import upstream code. The 1,289-commit EX-08 ledger is a later required artifact; this seed records the decisions that constrain implementation. Inventory coverage alone is not semantic review or parity proof.

| Plan item | Main-source decision | Current label |
|---|---|---|
| No bulk merge | Compare discrete source changes with fixed main and preserve each rollback boundary | `MUST` |
| PR-02 Android 8 queue ceiling | All apps use minSdk 28; API 26/27-only queue-size compatibility is outside this build's OS matrix | `NOT_APPLICABLE` |
| PR-02 API 28+ `queue(false)` | Keep the 32 KiB persistent request; add no queue-size fallback without a device-present false-return reproduction | `CONDITIONAL` |
| PR-03 initial OUT timing | Preserve the current startup policy until same-attempt timing shows the first OUT delay is causal | `CONDITIONAL` |
| EX-05 late OUT recovery | No unconditional second `StartSession`; any retry requires a causal trace and bounded A/B | `CONDITIONAL` / `NO_GO` if evidence fails |
| EX-06 status endpoint | Preserve one `requestWait` completion owner; status polling cannot introduce a competing owner | `CONDITIONAL` |
| EX-07 IPv4/scope/peer MAC | Keep the current IPv6/TUN route; require packet and route evidence before production routing changes | `CONDITIONAL` |
| PR-04 microphone | Record only for an explicit matching input request; preserve Phone/Siri/voice stream ownership | `MUST` regression gate |
| PR-05/06 ViewArea | Preserve the legacy single-area declaration and local 55:45 in-app layout; unknown wire commands remain off | `PRESERVE` / `CONDITIONAL` |
| PR-07 codec/surface | Preserve current MediaCodec/Surface ownership; vendor keys require capability and device evidence | `CONDITIONAL` |
| DSP-00..05 | Preserve the existing DSP-off PCM/latency contract and MEDIA-only policy; new processors remain disabled until separately verified | `PRESERVE` / `CONDITIONAL` |
| BYD/OEM-only behavior | Do not restore removed HUD, cluster, rhythm, or OS Split behavior | `REJECT` |
| EX-08 row ledger | Each source commit needs disposition, evidence, mapping, and rollback; duplicates and merge records are not function-level review | `NOT_STARTED` at PR-00 |

Implementation order follows v3: `PR-00 → EX-01 → PR-01 → EX-02 → EX-03/conditional wired gates → PR-04 → PR-06 LOCAL → PR-05 → conditional PR-06 DYNAMIC → PR-07 → isolated DSP stages → PR-08`. EX-01 and PR-01 can be designed independently after PR-00, but production changes still follow the specified gate order. No conditional item is automatically authorized by the baseline.
