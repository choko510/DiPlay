# Backlog

- Keep the wired startup watchdog armed through `SCREEN_STREAM_OPENED`; `AIRPLAY_SESSION_ACTIVE` at `RECORD` is earlier than the host's visible startup completion.
- Make attempt validation atomic with AirPlay session-state mutation and with failure/watchdog teardown so stale work cannot act on a later attempt.
