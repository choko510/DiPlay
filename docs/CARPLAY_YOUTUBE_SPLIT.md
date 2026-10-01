# CarPlay + YouTube split mode

## Architecture

- `DiPlayMenuActivity` sends an internal action to the existing single-task `CarPlayHostActivity`. The CarPlay controller, media sink and `CarPlayBackgroundSession` remain in the existing host.
- The host places its `TextureView`, touch layer and connection panel together in the left pane. The right pane lazily creates one GeckoView session. The default split is 55% CarPlay and 45% YouTube.
- `GeckoRuntime` is warmed on the UI thread after the split layout has been requested and its first frame has been drawn. The runtime is reused for the process. The `GeckoSession` opens after the CarPlay pane size settles and any required CarPlay restart has started. A normal split exit suspends the popup, marks the primary session inactive and retains it for the lifetime of the Activity. A CarPlay restart that creates a new AirPlaySession hides and suspends the retained browser while the new phone identity is resolved. On split entry the retained view stays hidden until the current iPhone profile resolves. Matching `youtubeContextId` reactivates a healthy suspended session without another `open()` or `loadUri()`; a different context closes it and creates a new session. If an `open()` request arrives while the matching session is already active, it preserves the current session and popup navigation state. An AirPlay session end, primary Gecko crash or Activity destruction closes the session. Gecko's per-profile storage context remains persistent.
- Pair Verify's controller ID is preferred. AirPlay `deviceID` and `macAddress` are fallbacks. Identity resolution waits while Pair Verify is in progress, then permits fallback after its final response; if Pair Verify never reaches a final response, a five-second timeout fallback remains. A late controller ID cannot switch a Google login during the current split lifetime. The chosen context is bound to each hashed identity alias in app preferences, so later splits and app restarts reuse it; distinct iPhone evidence selects a different profile.
- The Gecko context ID is `diplay_youtube_` plus the first 32 hex characters of SHA-256 over a versioned identity key. Preferences hold only hashed identity aliases, the selected profile hash and identity source. A verified controller ID resolves only through its own strong alias; weaker aliases are consulted only when controller ID is unavailable. Raw iPhone identifiers are not persisted or written to logs.
- On Android 13 and earlier, a running-critical trim notification discards a suspended Gecko session when split mode is closed. The Android 14+ trim API does not deliver running-critical levels, and the UI-hidden notification alone does not evict the session. Target-device PSS measurements are still needed before considering an idle-time retention limit.
- The YouTube page is loaded at `https://m.youtube.com/`. HTTPS navigation is allowed for Google and YouTube redirects. HTTP and external schemes are denied. One HTTPS or `about:blank` new-window request can create a temporary, unopened GeckoSession in the same iPhone context. `onNewSession` returns it unopened; GeckoView opens it on the parent runtime using Gecko's new-session ID. The load-request and new-session callbacks each validate policy without requiring matching callback order or identical URLs. An `about:blank` current-window load is allowed only inside the popup. Closing or crashing the popup returns to the primary session without marking a healthy primary page as failed.
- GeckoView owns cookies and web storage. Each iPhone context persists across GeckoSession close/open. Activity recreation re-adopts the active AirPlay session and resolves the same deterministic profile.
- The CarPlay size listener coalesces intermediate layout sizes. Temporary `adjustResize` changes during IME show/hide do not update the negotiated display size; after the IME animation, a stable view size is rechecked so rotation or a real pane resize still applies. If size changes while the existing controller is closing, the host waits for pending layout settlement and starts once at the latest observed size. A pending restart is handed to a recreated host only after the old teardown completes; the destroyed host cannot start a replacement controller. Existing restart-generation checks remain in place. Unknown incoming actions cannot clear restart suppression, and named retry callbacks are removed during teardown.
- YouTube fullscreen reparents the same GeckoView over the host while leaving the CarPlay pane measured at its split width. Back, Loading/Error, profile changes, and popup close signal Gecko to exit fullscreen before the view returns to the split pane. Android Back then navigates YouTube history, then exits split mode.
- Gecko load failures and primary content-process crashes are displayed and retried in the YouTube pane. A crashed session is closed, and retry reopens the same deterministic profile context. A popup crash closes only that popup and restores the primary page state. Failures do not shut down the CarPlay controller. Gecko internal debug logging is explicitly disabled. `configurationChanged` is forwarded for night mode and `orientationChanged(newConfig.orientation)` for screen orientation.
- GeckoView keeps its default `SurfaceView` backend for rendering performance. The browser is inserted below the loading/error overlay. If a status must be shown during fullscreen, fullscreen is exited first so the overlay and Reload toolbar are visible and clickable in the split pane.

## Performance instrumentation and builds

Debuggable builds and the opt-in benchmark variant emit Android trace sections for split entry/layout/exit, profile resolution, Gecko runtime creation/warm-up/session open/page ready, CarPlay resize/teardown/restart, and warm-session reopen. Async duration spans are available on Android 10 and later. `diplay.gecko.page_ready` ends at `ProgressDelegate.onPageStop`; `diplay.split.total` ends after GeckoView reports a first contentful paint or a first composite following a paint reset, once its primary or popup session is active and its view is visible, or after a visible load error is shown. If no visible paint arrives within 10 seconds of activation, `diplay.split.total.paint_missing` is recorded and the duration span closes without counting success. `diplay.split.carplay_restart` ends when the first main-video output buffer is submitted to the render surface, or on connection failure/end; this is a submission milestone, not proof that the display presented the frame. A cumulative counter line is written on split exit in debug and benchmark builds; it includes controller starts/closes, split entries/exits, runtime and session creations, session closes, YouTube `loadUri()` calls, display restarts, profile resolutions, profile-alias preference writes, warm reopens, paint-status resets, first composites/contentful paints, visible-paint completions and timeouts. These counters are process-local and reset when the app process restarts.

Install `:mobile:assembleBenchmark` or `:automotive:assembleBenchmark` for an optimized, non-debuggable, profileable APK signed with the local debug key. Its benchmark manifest opts the tracer in without making the APK debuggable. Release optimization is enabled separately. CI builds both benchmark variants as an R8 regression check; no production signing credentials are required.

For a comparable manual run, use the same head unit and iPhone and record: already-connected CarPlay to first split; split to full CarPlay to split; 20 split enter/exit cycles; video playback; keyboard show/hide ten times; popup login; Activity recreation; H.264 and HEVC; wired and wireless connections. Capture Perfetto traces and record first/warm split duration, exit duration, restart/session/load counts, frame timing, main/Gecko process PSS, GC, CPU and thermal state. No target-device before/after measurements are available from this checkout; do not treat build success or the reuse counters as a latency or memory benchmark.

## Dynamic ViewArea and decoder status

Dynamic ViewArea switching is not implemented. The current `/info` advertises one ViewArea and `SETUP` enables `viewAreas`, but the repository contains no verified in-session switch command or payload, protocol trace or real-device exchange. The existing full CarPlay restart remains the display-size fallback.

Adaptive MediaCodec resizing is also not enabled. The decoder is configured for the negotiated dimensions, and there is no validated runtime ViewArea change or bounded maximum encoded size to configure. Keep the current decoder/restart behavior until those inputs are established; no CarPlay wire command should be inferred from a name.

## Measurement status

| Metric | Before | After |
| --- | --- | --- |
| First split duration | Not measured | Not measured |
| Warm split duration | Not measured | Not measured |
| CarPlay controller starts/closes | Not measured | Debug counter available; hardware run pending |
| GeckoSession creations and YouTube loads | Not measured | Debug counters available; hardware run pending |
| Main and Gecko process PSS / CPU / frame timing | Not measured | Not measured |

The warm-reopen code path keeps one primary GeckoSession per Activity and same-profile AirPlay session, but its PSS cost and latency change still require measurement on the target device. A Baseline Profile/Macrobenchmark module has not been added because the split journey depends on an attached CarPlay session and target hardware.

## Real-device verification checklist

Run this on the target arm64 Android head unit with GeckoView 156 and two iPhones with separate Google accounts. These checks require hardware and have not been verified by the automated build.

1. Connect iPhone A and sign in to YouTube account A.
2. Restart DiPlay, reconnect iPhone A, and confirm account A remains signed in.
3. Connect iPhone B and sign in to YouTube account B.
4. Reconnect iPhone A and confirm account A returns.
5. Confirm A and B never share a Google/YouTube session.
6. Play a YouTube video.
7. Seek in a playing video.
8. Open Google sign-in from a popup link and complete login.
9. Exercise a Google sign-in flow that begins with an empty `about:blank` popup.
10. Confirm a second popup is denied while one popup is open.
11. Enter YouTube fullscreen and return to split mode with Back.
12. Check YouTube and CarPlay audio together.
13. Touch CarPlay controls in the left pane.
14. Touch YouTube controls in the right pane and confirm no CarPlay touch is sent.
15. Enter and exit split mode 20 times.
16. Resize or rotate the head unit repeatedly during split entry and confirm one settled CarPlay restart.
17. Recreate the Activity while split mode is open and confirm one CarPlay controller remains active.
18. Change the device language or UI mode while CarPlay is connected and confirm the session is adopted.
19. Force-stop or crash the Gecko content process, reload, and confirm the same YouTube profile returns.
20. Deny network access temporarily, then restore it and reload YouTube without interrupting CarPlay.
21. Unplug and reconnect USB CarPlay while split mode is open.
22. Connect using wireless CarPlay and repeat the profile-isolation checks.
23. Play continuously for 30–60 minutes.
24. Record memory use during split playback.
25. Record CPU and GPU use during split playback.
26. Check H.264 CarPlay with YouTube playback.
27. Check HEVC CarPlay with YouTube playback.
28. Check navigation prompts during YouTube playback.
29. Check Siri during split playback.
30. Check a phone call during split playback.
31. Confirm closing split mode preserves Google login on the next split entry.
32. Repeat profile switching with two distinct Google accounts and verify no cross-account content appears.
33. Record first and second split-open duration, video frame drops, and thermal behavior.
34. Install the APK built from the latest PR #12 head and repeat the iPhone A/B login checks.
35. Sign in through a popup opened directly at an HTTPS Google URL.
36. Confirm a successful sign-in flow that starts at `about:blank` redirects to Google in the same popup context.
37. Confirm Google closing its popup with `window.close()` returns to the primary YouTube session.
38. Force a popup content-process crash and verify the healthy primary session does not show an error state.
39. Cause a popup network failure, verify the primary page remains healthy, then reload and continue.
40. Confirm the status overlay appears above GeckoView and the Reload toolbar button remains clickable.
41. Rotate or change orientation while a popup is open and confirm Gecko content follows the new orientation.
42. Compare the first split startup time with later opens, including Gecko runtime warm-up and page-ready timestamps.
43. Confirm a late controller ID does not change the selected account during an open split; close and reopen split to check the next identity resolution.
44. Confirm an unknown incoming Activity action does not clear a pending restart suppression or restart CarPlay.
45. On Google login and search fields, show and hide the software keyboard ten times; confirm transient `adjustResize` changes do not restart CarPlay. After the keyboard closes, confirm a genuine rotation or pane resize still applies.
46. Exit split and reopen it for the same iPhone; confirm GeckoSession creations and `loadUri()` calls both remain at zero for the warm reopen.
47. Exit split, end the AirPlay session, and reconnect; confirm the old GeckoSession is closed and a new identity resolves before YouTube is activated.
48. After switching iPhones, enter split and confirm the previous phone's page stays hidden until the new identity resolves.
49. Reopen split for the same iPhone after the pane-size CarPlay restart; confirm the new AirPlaySession resolves to the same `youtubeContextId` and the warm path creates no GeckoSession or YouTube load.
50. With the Google popup open, repeat same-profile resolution and confirm the popup remains current, its Back state is unchanged, and it can still close back to primary.
51. Install the optimized benchmark APK, confirm it is non-debuggable/profileable, and run a Linux I2C native failure; verify `FindClass()`/constructor lookup still maps to `I2cTransportException`.
52. Show the keyboard, background DiPlay before it closes, then resume with the keyboard hidden; verify IME suppression clears and a real pane resize applies once.

Also compare the iPhone's Pair Verify controller ID on wired and wireless connections. The implementation uses that ID first; if the device reports different IDs across transports, that phone will use separate Gecko contexts unless a stable fallback is shared by both connections.

When Pair Verify never completes, the fallback `deviceID` or Wi-Fi MAC is supplied by the AirPlay peer and is not equivalent to a verified controller ID. Validate those fallback identifiers on the target wired and wireless paths; separate iPhones with identical fallback identifiers cannot be reliably distinguished until Pair Verify completes.
