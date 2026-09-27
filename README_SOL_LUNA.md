# Sol + Luna Codex drop-in

This bundle is designed to be extracted directly into the root of an existing repository.

## What it does

- Root coordinator: `gpt-6-sol` at `xhigh`
- Generic subagents: `gpt-6-luna` at `max`
- Planning / architecture / hard diagnosis / final review: Sol
- Implementation: Luna Max
- Repository scouting and verification: Luna xhigh
- Multi-agent V2 enabled
- Approximate parallel capacity: one root + six workers

The workflow is described in `AGENTS.md`.
Please be sure to read .scratchpad/README.md.

## Installation

Extract the ZIP at the repository root so the result looks like:

```text
repo/
├─ AGENTS.md
├─ README_SOL_LUNA.md
└─ .codex/
   ├─ config.toml
   └─ agents/
      ├─ sol_architect.toml
      ├─ sol_reviewer.toml
      ├─ luna_scout.toml
      ├─ luna_implementer.toml
      └─ luna_tester.toml
```

Then open/run Codex from that repository as usual.

## Existing AGENTS.md or .codex/config.toml

If your repository already has either file, do not blindly discard project-specific rules. Merge those rules into the supplied files. Codex behavior depends heavily on repository-specific build/test commands, protected files, architecture constraints, and style conventions.

## Why Luna Max?

GPT-5.6 Luna officially supports `max` reasoning effort. The implementation worker uses it because coding slices can still contain many local choices even after Sol has made the architecture decisions. Scout/test roles use `xhigh` to reduce unnecessary latency while keeping strong reasoning.

## Runtime compatibility note

Recent Codex versions support project-local agents and per-agent model/effort configuration, but there have also been reports where some app/extension builds temporarily ignore custom-role model settings or inherit the parent model. This bundle therefore does not depend solely on named-role resolution: `[agents].default_subagent_model` is also pinned to Luna and the default subagent effort is `max`.

If a particular Codex build still shows children running on the parent model, update the Codex CLI/app/extension before changing the workflow files.

## Token-saving defaults

This bundle also applies context-economy defaults:

- `model_verbosity = "low"` to reduce unnecessary prose while keeping reasoning effort high.
- concise reasoning summaries rather than detailed summaries.
- auto-compaction at roughly 220k active tokens (Codex may clamp this for smaller model contexts).
- post-turn compaction at 80% of the usable context window.
- `tool_output_token_limit = 8000` so giant command/tool outputs do not dominate model-visible history.
- AGENTS.md rules requiring targeted file reads, filtered logs, compact handoffs, diff-first review, and a narrow-to-broad test funnel.

The important part is behavioral: do not rely on truncation as a substitute for targeted commands. Narrow the query or command first, then retain only the evidence needed by the next agent.

## Multi-agent backend compatibility

This bundle intentionally does **not** force `features.multi_agent_v2`. Sol-capable Codex releases may choose a backend from the model catalog, and forcing V2 has had regressions around custom agent/model selection in some releases. The project relies on `[agents]`, named role files, and `default_subagent_model = "gpt-5.6-luna"` instead.
