# B07 Adapt Backlog

## Current result

No new upstream commit is ready for `ADAPT`. The B07 rule requires all three items: a required fixed-main behavior is missing, a fixed-main regression fails, and a narrow adaptation can safely address it. None of the 60 source commits satisfies that bar.

The reviewed source dispositions are 2 `FORK_SUPERIOR`, 16 `SKIP_OEM`, 5 source-level `REJECT_UNSAFE`, and 37 `NEED_EVIDENCE`. The remaining 51 `REJECT_UNSAFE` rows are merge wrappers classified as source-feature/PR integration duplicates or branch-sync-only merges; they are not independent adaptation candidates.

## Existing fixed-main behavior

- F-01/F-02: `AirPlayTouchSlots` preserves pointer-to-slot ownership, lifts at the final coordinates, and retries failed UP before reusing a slot. `AirPlayTouchSlotsTest` and `CarPlayTouchMapperTest` cover the paths.
- F-09: `currentAirPlaySession()` filters closed controller/session state, and the Activity recreation test verifies live-session retention and null after close.
- The EX-05 late-USB/StartSession sources (#29/#30) are observation-only candidates for B08. No extra `0x4301` is enabled.

## Evidence-gated follow-up

Other `NEED_EVIDENCE` rows remain in the per-SHA review table. They need a target trace, fixed-main reproduction, explicit UI requirement, or audio/privacy evidence before they can become an adaptation. A row does not become an adaptation solely because the source patch exists or its upstream test passes.
