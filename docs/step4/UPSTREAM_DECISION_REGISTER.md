# Upstream decision register — staging record

This file preserves the upstream audit provenance required by PR-00. It is **not** the completed EX-08 commit-level adoption register.

## Source provenance

| Artifact | Identity / verified state |
|---|---|
| Controlling v3 plan | `DiPlay_STEP4_v3_2026-10-18.md`, SHA-256 `7e8f60a216ff769434f09ba922b0bd218859a8aaf1c8554ced9ab1127b92ca73` |
| Evidence archive | `DiPlay_STEP4_2026-10-18.zip`, SHA-256 `2d22fd9ebef17a05a91883a9519f271b919474265719d91ab9aa2473a31f9dba` |
| Archive internal manifest | 17 listed file hashes checked against ZIP entry contents; no mismatches |
| Fixed implementation base | `main` SHA `95ba398f95ee104527812cd2b095977d71c51f98` |
| Audit summary | Archive README, Pass1–23 report, implementation addendum, split/DSP source specs, and recovered upstream audit summary |

The attached audit summary describes 1,232 base upstream-only commits (755 ordinary, 477 merge) plus 57 later commits (34 ordinary, 23 merge), for 1,289 total. It reports 79 conflicted files and 385 conflict blocks in its structural comparisons. The archive README explicitly says those checks are structural; they do not prove semantic equivalence of every patch, runtime behavior, or TS10 hardware acceptance.

## PR-00 status

- No upstream source commit or patch is imported by PR-00.
- Commit-by-commit dispositions have not been populated here yet. EX-08 remains a required later documentation stage; this file deliberately does not claim the 1,289 changes have all been semantically reviewed.
- Do not bulk-merge upstream. Later rows must record the source SHA, relevant paths/patch anchors, disposition, source-grounded reason, tests, dependent STEP4 stage, and rollback path.

Use only these disposition labels in the eventual EX-08 register:

| Disposition | Meaning |
|---|---|
| `TAKE_DIRECT` | Exact upstream change is independently confirmed compatible with current fixed main |
| `ADAPT` | Behavior is useful but the fork-specific implementation must be retained or the patch adapted |
| `FORK_SUPERIOR` | Existing fixed-main behavior is retained based on stronger fork-specific evidence |
| `SKIP_OEM` | OEM-specific behavior is outside the target product scope |
| `REJECT_UNSAFE` | Patch would violate ownership, protocol, privacy, compatibility or rollback constraints |
| `NEED_EVIDENCE` | Source evidence is insufficient; record the exact evidence gate and keep behavior unchanged |

The v3 plan and archived source specs are requirements/evidence, not instructions that can override the user's requested fixed-main, sequential implementation workflow.
