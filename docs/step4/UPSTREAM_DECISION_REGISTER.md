# Upstream decision register

## Scope and status

- Fork implementation base: `choko510/DiPlay` main `95ba398f95ee104527812cd2b095977d71c51f98`.
- Comparison source fixed by the accepted v3 plan: `shihabal3amri/DiPlay` `6d3b8a8ce927cb4b115b8c3881f17619433e93b1`.
- The plan indexes 1,232 prior upstream-only commits plus a 57-commit delta, for 1,289 total. Its audit reports 79 conflicting files / 385 conflict blocks. Those are source-document figures, not proof that all 1,289 commits have been semantically reviewed in this implementation branch.
- PR-00 establishes the decision format and source boundaries. Per-commit review is the separate EX-08 deliverable and remains `NOT_RUN` until its ledger is populated and checked against the fixed source.
- No upstream branch is merged or used as the runtime source for this PR.

## Decision vocabulary

| Decision | Meaning |
|---|---|
| `TAKE_DIRECT` | Exact upstream behavior is verified against current fork dependencies and tests; no ownership or contract conflict. |
| `ADAPT` | Preserve the intent but modify the implementation to match fork APIs, ownership, fallbacks, and current tests. |
| `FORK_SUPERIOR` | Current fork behavior is intentionally retained because it better protects a verified contract. |
| `SKIP_OEM` | OEM-specific or removed vehicle integration is not brought back. |
| `REJECT_UNSAFE` | The change risks protocol correctness, resource ownership, user privacy, or security and is explicitly rejected. |
| `NEED_EVIDENCE` | A device/protocol symptom or behavior is required before deciding. No code is copied on a hypothesis. |

`NOT_APPLICABLE` is an applicability annotation, not an EX-08 commit disposition. The per-commit ledger uses only the six required decisions above; API26/27-only work is recorded as out of scope because fixed main has minSdk28.

## Initial high-level decisions from v3

| Topic | Initial decision | Required evidence / constraint |
|---|---|---|
| NCM 512-byte boundary and short pad | `ADAPT` | Preserve the existing persistent USB request; add bounded receive framing and test pad/no-pad cases. |
| NCM corruption recovery | `ADAPT` | Finite resynchronization only; retain typed failure and bounded memory. |
| Recovered Host session versus stale retry | `ADAPT` | Test a successful recovery before timer expiry and a later second loss independently. |
| API26/27 16 KiB queue workaround | `NOT_APPLICABLE` | Fixed main minSdk is 28. |
| API28+ queue-failure fallback | `NEED_EVIDENCE` | Require device-present queue failure and prove single request ownership. |
| First bulk-OUT timeout or delayed recovery | `NEED_EVIDENCE` | Record same-attempt SYN/TUN/SYN-ACK/OUT/accept sequence first. |
| USB status endpoint ownership | `NEED_EVIDENCE` | Preserve one `requestWait` owner unless a measured need and unified dispatcher are proven. |
| IPv4, IPv6 scope, peer MAC and accept-loop changes | `NEED_EVIDENCE` | Do not infer a device cause from source capability gaps alone. |
| BYD HUD/cluster/rhythm integrations | `SKIP_OEM` | Keep removed OEM-only outputs absent. |
| GeckoView per-iPhone storage and background session | `FORK_SUPERIOR` / preserve | Keep hashed identity contexts and existing application-scoped runtime. |
| Dynamic ViewArea wire protocol | `ADAPT` only after acceptance | Keep the existing response and local fallback until iPhone acknowledgement and dimensions are observed. |
| Software Concentus Opus substitution | `REJECT_UNSAFE` for this pass | Current fork uses MediaCodec and has different route/clock/lifecycle contracts. |

## EX-08 row schema

The later EX-08 ledger must contain one unique row per upstream-only commit and, where needed, one related patch/file anchor. Required fields:

`commit_sha`, `parent_shas`, `commit_kind`, `source_paths`, `source_anchor`, `fork_paths`, `fork_anchor`, `decision`, `reason`, `evidence_class`, `test_ids`, `dependent_stage`, `rollback`, `review_state`.

`CODE_DIFF_HEURISTIC_ONLY` is provenance, not a current semantic decision. Empty/missing evidence stays `NEED_EVIDENCE` or `NOT_RUN`; it is never converted to `TAKE_DIRECT` by a path match or row count.
