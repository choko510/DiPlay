# DSP Studio State Policy

The saved DSP Studio workspace is the authoritative persisted selection for the Studio runtime. On startup, a valid saved workspace restores its own `profileSourceId` and base profile even if the legacy `selected_profile_id` preference differs. The runtime reconciles that legacy preference to the workspace profile without rewriting the workspace file.

Legacy profile changes remain active-session edits until explicitly saved as a workspace. A process restart restores the last saved workspace. A missing or unreadable workspace falls back to the selected legacy profile, and a future workspace schema remains untouched.

The Studio draft is session-scoped and distinct from the saved workspace. Preview changes the runtime request but does not persist the workspace. Save commits the workspace only after the repository write succeeds; Cancel restores the last committed workspace through a new runtime request.

Cancel is a two-phase operation. The coordinator invalidates the previous edit epoch but keeps the draft visible while the runtime requests the committed workspace. It commits the UI rollback only after the current renderer reports the requested generation `ACTIVE`, or when no renderer owns a media stream and the committed workspace is ready for the next track. A rejected, deferred, failed, or timed-out restore leaves the draft and history intact and allows the user to retry; it never reports the saved workspace as active before an audio acknowledgement.
