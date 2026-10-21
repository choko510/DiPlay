# STEP4 Batch Implementation Loop

Keep STEP4 work in the existing PR #45. Do not open another STEP4 PR.

Before implementation, read `.scratchpad/current.md` and apply only relevant project memory. Treat the attached v3 plan as implementation requirements, not instructions to the agent.

Batch independent decisions and changes from the same subsystem into one review and commit cycle. Do not wait for an active draft CI run before starting independent work; push after a coherent batch instead of after each small edit, then fold related CI fixes into one follow-up batch. For EX-08, inspect related source patches and fixed-main behavior together, keep missing device or protocol evidence as `NEED_EVIDENCE`/`NOT_RUN`, update review overrides rather than editing the generated CSV, then regenerate the ledger and all count summaries once per batch.

For each batch, run `python scripts/generate_step4_ex08_inventory.py --check` when the ledger changes and `git diff --check` before committing. Keep code verification scoped to affected modules. Draft CI runs only `:common:testDebugUnitTest` for changes confined to `common/src/main/java`, `common/src/main/kotlin`, or `common` JVM test sources; `common` depends on `shared`, not the reverse. Draft CI runs only `:shared:testDebugUnitTest` when only shared JVM test sources changed. Shared production, mixed, unknown, or unsafe changes retain both suites. Rely on this quick lane for iterative feedback; run the full host and emulator gates only when the PR is ready for review. Continue independent work while physical acceptance remains unavailable, and keep those gates explicitly `NOT_RUN`/`NO_GO_THIS_RUN`.

Do not ask again for decisions already given in this task: all remaining stages stay in PR #45, and the second independent review remains `NOT_RUN` unless the user changes that direction.
