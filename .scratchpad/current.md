# Current Work

## Seamless CarPlay + YouTube Split View

- Analysis baseline: `db9853266c71e2351c08b8934f223f7df671fd74`.
- Latest `origin/main`: `95ba398f95ee104527812cd2b095977d71c51f98`, including merged PR #14 and #16; rebase and final verification are in progress.
- Branch: `codex/seamless-split-view-implementation`.
- Read the implementation spec from `C:\Users\eita2\Downloads\DiPlay_Seamless_Split_View_Implementation_Spec_v1.md`.
- Initial baseline: shared 357 tests with one Windows IPv6 loopback permission failure under JDK 25 and JBR 21; common 116 passed.
- PR-01 through PR-08 implementation and stage log are complete. Keep Dynamic ViewArea opt-in/default-off and do not report wire or head-unit success without device evidence.
- Full stage log: `docs/SEAMLESS_SPLIT_IMPLEMENTATION.md`.
