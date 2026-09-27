# Codex token-saving cheatsheet

This repository is configured for **Sol decisions + Luna execution**. The biggest savings come from avoiding duplicate context, not from lowering reasoning effort.

## High-impact habits

1. **Search, then read ranges.** Use `rg`/symbol search and `sed` ranges instead of whole-file dumps.
2. **Send contracts, not transcripts.** A Luna worker needs goal + ownership + required behavior + constraints + acceptance tests, not Sol's entire reasoning history.
3. **Scout once, reuse evidence.** Do not ask several agents to rediscover the same call sites.
4. **Diff-first review.** Start with `git diff --stat` and changed hunks, then expand only where contracts cross file boundaries.
5. **Filter noisy logs.** Keep the failing section and exit status; successful logs can be summarized in one line.
6. **Test narrow -> broad.** Focused regression test first, full suite only at meaningful checkpoints.
7. **Keep agents bounded.** One clear slice per Luna agent prevents unrelated repository exploration.
8. **Compact state, not knowledge.** Before/after compaction preserve decisions, changed files, tests, open risks, and the next action.

## Project defaults

```toml
model_verbosity = "low"
model_reasoning_summary = "concise"
model_auto_compact_token_limit = 220000
model_auto_compact_token_limit_scope = "total"
model_post_turn_compact_threshold_percent = 80
tool_output_token_limit = 8000
```

If an 8k tool-output cap truncates genuinely necessary evidence, rerun a **narrower** command first. Raise the cap only when the data itself cannot be narrowed safely.
