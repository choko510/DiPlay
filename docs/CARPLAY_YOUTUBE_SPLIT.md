# CarPlay + YouTube split mode

## Architecture

- `DiPlayMenuActivity` sends an internal action to the existing single-task `CarPlayHostActivity`. The CarPlay controller, media sink and `CarPlayBackgroundSession` remain in the existing host.
- The host places its `TextureView`, touch layer and connection panel together in the left pane. The right pane lazily creates one GeckoView session. The default split is 55% CarPlay and 45% YouTube.
- `GeckoRuntime` is warmed only after split mode is requested and reused for the process. The `GeckoSession` opens after the CarPlay pane size settles and any required CarPlay restart has started. Closing split mode closes its sessions and detaches the view; it does not clear the Gecko storage context.
- Pair Verify's controller ID is preferred. AirPlay `deviceID` and `macAddress` are fallbacks. The host waits for identity resolution before creating a session; it shows an error rather than using a shared unknown profile when no stable identifier is available.
- The Gecko context ID is `diplay_youtube_` plus the first 32 hex characters of SHA-256 over a versioned identity key. Raw iPhone identifiers are used only in memory and are not stored in preferences or written to the new profile logs.
- The YouTube page is loaded at `https://m.youtube.com/`. HTTPS navigation is allowed for Google and YouTube redirects. HTTP and external schemes are denied. One HTTPS or `about:blank` new-window request can open a temporary GeckoSession in the same iPhone context; closing it returns to the parent session. Logs record a page host at most, never a full URL.
- GeckoView owns cookies and web storage. Each iPhone context persists across GeckoSession close/open. Activity recreation re-adopts the active AirPlay session and resolves the same deterministic profile.
- The CarPlay size listener coalesces intermediate layout sizes. If size changes while the existing controller is closing, the host waits for pending layout settlement and starts once at the latest observed size. A pending restart is handed to a recreated host only after the old teardown completes; the destroyed host cannot start a replacement controller. Existing restart-generation checks remain in place.
- YouTube fullscreen reparents the same GeckoView over the host while leaving the CarPlay pane measured at its split width. Android Back exits fullscreen first, then navigates YouTube history, then exits split mode.
- Gecko load failures and content-process crashes are displayed and retried in the YouTube pane. A crashed session is closed, and retry reopens the same deterministic profile context. They do not shut down the CarPlay controller. Gecko internal debug logging is explicitly disabled, and configuration changes are forwarded to the runtime.

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

Also compare the iPhone's Pair Verify controller ID on wired and wireless connections. The implementation uses that ID first; if the device reports different IDs across transports, that phone will use separate Gecko contexts unless a stable fallback is shared by both connections.
