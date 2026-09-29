# Current Work

- Fix CarPlay navigation audio negotiation in a dedicated worktree.
  - Worktree: `C:\Users\eita2\.codex\worktrees\nav-audio-hq\DiPlay`
  - Branch: `fix/carplay-navigation-audio-quality`, based on `origin/main` at `04fdbbd`.
  - Investigation found `type=101/audioType=default` advertises the full 8–32 kHz PCM mask (`0x3fc`) alongside configured 44.1/48 kHz PCM and Opus.
  - The original working tree contains unrelated user changes; they remain untouched.
  - Implemented high-rate-only PCM for default/alert/compatibility output candidates and added `/info` capability plus SETUP negotiation format-bit logs.
  - Focused Kotlin/JUnit suites passed (15 tests) with the Gradle-distributed Kotlin compiler. The documented Gradle task is blocked by baseline unresolved `requestId`/`device` references in `CarPlayController.kt`.
  - Next: finish diff review, commit, push, open the PR, then archive the completed decision/lesson and clear this entry.
