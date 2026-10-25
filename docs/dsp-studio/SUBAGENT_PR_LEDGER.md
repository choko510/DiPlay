# DSP Studio Luna subagent PR ledger

All implementation sub-PRs target codex/dsp-studio-final. Each agent owns one isolated worktree, one branch, and one Draft PR. No child PR may target PR #45's branch or main.

| Task | Required model | Branch | PR URL | Commit SHA | Files owned | Tests / review | Integration result |
|---|---|---|---|---|---|---|---|
| preset-workspace | gpt-6-luna | codex/dsp-studio-preset-workspace | Pending | Pending | Workspace and module-preset storage after core contract | Pending | NOT_STARTED |
| macro-ui | gpt-6-luna | codex/dsp-studio-macro-ui | Pending | Pending | Studio Macro Compose components and UI tests | Pending | NOT_STARTED |
| smart-loudness | gpt-6-luna | codex/dsp-studio-smart-loudness | Pending | Pending | Volume source/controller and loudness settings UI after shared contract | Pending | NOT_STARTED |
| dynamic-bass | gpt-6-luna | codex/dsp-studio-dynamic-bass | Pending | Pending | Dynamic Bass DSP module and native tests after Native interface contract | Pending | NOT_STARTED |
| eq-advanced-ui | gpt-6-luna | codex/dsp-studio-eq-advanced-ui | Pending | Pending | PEQ graph and DSP module editor composables | Pending | NOT_STARTED |
| live-compat | gpt-6-luna | codex/dsp-studio-live-compat | Pending | Pending | Field-aware legacy Hot/Warm planner after renderer contract | Pending | NOT_STARTED |

## Integration constraints

- The base model, workspace API, Hot command, Native ABI, and shared headroom contract must be pushed before delegating dependent changes.
- Agents do not modify files outside their ownership. Conflicting shared files remain with the orchestrator.
- The orchestrator reviews each child diff, runs its tests, checks merge conflicts, and only then integrates it into codex/dsp-studio-final.
- Final rows must contain the actual agent model, PR URL, branch, commit SHA, test command/results, and merge-to-integration status. No row may claim a merge to PR #45 or main.
