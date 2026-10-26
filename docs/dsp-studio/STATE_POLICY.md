# DSP Studio State Policy

The saved DSP Studio workspace is the authoritative persisted selection for the Studio runtime. On startup, a valid saved workspace restores its own `profileSourceId` and base profile even if the legacy `selected_profile_id` preference differs. The runtime reconciles that legacy preference to the workspace profile without rewriting the workspace file.

Legacy profile changes remain active-session edits until explicitly saved as a workspace. A process restart restores the last saved workspace. A missing or unreadable workspace falls back to the selected legacy profile, and a future workspace schema remains untouched.

The Studio draft is session-scoped and distinct from the saved workspace. Preview changes the runtime request but does not persist the workspace. Save commits the workspace only after the repository write succeeds; Cancel restores the last committed workspace through a new runtime request.
