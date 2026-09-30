# CarPlay + YouTube split mode

## Architecture

- `DiPlayMenuActivity` sends an internal action to the existing single-task `CarPlayHostActivity`. The CarPlay controller, media sink and `CarPlayBackgroundSession` remain in the existing host.
- The host places its `TextureView`, touch layer and connection panel together in the left pane. The right pane lazily creates one GeckoView session. The default split is 55% CarPlay and 45% YouTube.
- `GeckoRuntime` is initialized on first use and reused for the process. Closing split mode closes its `GeckoSession` and detaches the view; it does not clear the Gecko storage context.
- Pair Verify's controller ID is preferred. AirPlay `deviceID` and `macAddress` are fallbacks. The host waits for identity resolution before creating a session; it shows an error rather than using a shared unknown profile when no stable identifier is available.
- The Gecko context ID is `diplay_youtube_` plus the first 32 hex characters of SHA-256 over a versioned identity key. Raw iPhone identifiers are used only in memory and are not stored in preferences or written to the new profile logs.
- The YouTube page is loaded at `https://m.youtube.com/`. HTTP and HTTPS navigation is allowed for Google and YouTube redirects; non-web schemes are denied. New-window HTTP(S) links reuse the single session instead of creating tabs. Logs record a page host at most, never a full URL.
- GeckoView owns cookies and web storage. Each iPhone context persists across GeckoSession close/open. Activity recreation re-adopts the active AirPlay session and resolves the same deterministic profile.
- The CarPlay size listener coalesces intermediate layout sizes. If size changes while the existing controller is closing, the host waits for pending layout settlement and starts once at the latest observed size. Existing restart-generation checks remain in place.
- YouTube fullscreen reparents the same GeckoView over the host while leaving the CarPlay pane measured at its split width. Android Back exits fullscreen first, then navigates YouTube history, then exits split mode.
- Gecko load failures and content-process crashes are displayed and retried in the YouTube pane. They do not shut down the CarPlay controller.

HTTP(S) new-window requests with a URI are loaded in the current session. Empty `about:blank` popup flows are not given a separate GeckoSession; verify Google sign-in on the target head unit in case it uses that pattern.

## Real-device verification checklist

Run this on the target arm64 Android head unit with GeckoView 156 and two iPhones with separate Google accounts. These checks require hardware and have not been verified by the automated build.

1. Connect iPhone A and sign in to YouTube account A.
2. Restart DiPlay, reconnect iPhone A, and confirm account A remains signed in.
3. Connect iPhone B and sign in to YouTube account B.
4. Reconnect iPhone A and confirm account A returns.
5. Confirm A and B never share a Google/YouTube session.
6. Play a YouTube video.
7. Seek in a playing video.
8. Enter YouTube fullscreen and return to split mode with Back.
9. Check YouTube and CarPlay audio together.
10. Touch CarPlay controls in the left pane.
11. Touch YouTube controls in the right pane and confirm no CarPlay touch is sent.
12. Enter and exit split mode 20 times.
13. Unplug and reconnect USB CarPlay.
14. Connect using wireless CarPlay.
15. Play continuously for 30–60 minutes.
16. Record memory use during split playback.
17. Record CPU and GPU use during split playback.
18. Check H.264 CarPlay with YouTube playback.
19. Check HEVC CarPlay with YouTube playback.
20. Check navigation prompts during YouTube playback.
21. Check Siri during split playback.
22. Check a phone call during split playback.

Also compare the iPhone's Pair Verify controller ID on wired and wireless connections. The implementation uses that ID first; if the device reports different IDs across transports, that phone will use separate Gecko contexts unless a stable fallback is shared by both connections.
