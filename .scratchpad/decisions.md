# Decisions

## Keep Wi-Fi Direct cleanup strict when system credentials are incomplete

If `createGroup(null)` succeeds but Android omits the system-generated passphrase, the group identity is incomplete. Fail startup without retrying rejected custom credentials or removing a group that cannot be proven to be owned by this attempt.

## Keep connection traces opt-in and app-owned

The debug setting applies from the next connection attempt and writes structured elapsed-time events to bounded app storage. This keeps tracing independent of the foreground activity lifecycle; existing session logs continue to supply throughput and media diagnostics in the same exported report.

## Keep one USBMUX IN request and use bounded recovery

USBMUX now reuses one 32 KiB direct-buffer request across wait timeouts; framing rebuilds up to 64 KiB messages above the USB read layer. On failure, close the wired CSM, USBMUX/session, and VPN/NCM together, then allow three bounded clean retries at 400, 800, and 1,200 ms before surfacing a deep-recovery candidate. USBMUX and NCM remain on separate `UsbDeviceConnection`s because the current interface layout provides no evidence that combining them improves reliability.

## Bind asynchronous startup results to the wired attempt

AirPlay startup success and VPN/NCM transport errors must identify the attachment/connection attempt that produced them before they can cancel a watchdog or tear down/restart the wired stack. Allocate that attempt ID when `openDataPaths()` begins so iAP2 USB session and NCM open failures share the wired retry budget. A service-local cleanup is not sufficient when the controller still owns the iAP2 CSM and USBMUX host.

The attempt check must be atomic with the state mutation or cleanup it protects. A check followed by an unscoped callback or teardown still leaves a check-then-act race.

For the user-visible wired startup interval, keep the watchdog active until the screen stream is opened. The AirPlay `RECORD` event marks its control session active earlier, while the host still shows its Opening panel.

## Keep extra USB diagnostics opt-in and bounded

Use the existing debug-log preference for syslog relay capture and small CarKit write splitting. Limit the capture by time, bytes, and matching-line count, and log only process-category counts so raw phone log contents are not persisted.

## Preserve observed USBMUX receive compatibility

Keep the received TCP `word8` field diagnostic-only because observed iPhone replies omit the host's `0xfeedface` value. The version handshake still validates its response version; do not reject TCP frames on an unconfirmed magic-field rule.

## Keep NCM bulk-IN requests persistent and status polling off

Treat `requestWait` timeout as idle while preserving the queued request; treat a null result, queue failure, or unexpected completion as a terminal NCM transport error. Close the full bridge on terminal errors. Do not run synchronous status-endpoint transfers on the shared connection by default; retain an explicit diagnostic switch for A/B testing.

## Retry only idempotent NDP during NCM startup

Report OUT not-ready results instead of dropping them silently. Before link readiness is proven, use one short OUT timeout for all frames and retry only Neighbor Solicitation/Advertisement frames after CarPlayStartSession, with five short bounded attempts; leave TCP/UDP retransmission to their own protocols.

## Use evidence for NCM link readiness

Track `PRE_CARPLAY_START`, `CARPLAY_START_SENT`, `NCM_LINK_PROBING`, and `NCM_LINK_READY`. Require both inbound IPv6 and a successful outbound IPv6 write before inferring bidirectional readiness; accepted AirPlay control traffic remains an independent strong readiness signal.

## Pair NCM interfaces from CDC Union descriptors

Use each Union descriptor's master/slave interface IDs to pair control and data interfaces. Permit a no-Union fallback only when one control interface and one usable bulk data interface exist. Record all candidates and descriptor evidence; choose the first descriptor-ordered candidate only when the highest evidence is tied.

## Keep Apple USB mode recovery diagnostic-only

Query/guess the current Apple mode and configuration set for diagnostics, but do not toggle modes automatically. The current evidence does not show that repeated mode switches are safe without a USB reset, and Android USB Host does not expose a public device reset primitive.

## Fence and serialize wired teardown

Capture and clear an attempt's resources under the attempt lock, then close them outside it. A separate close mutex serializes concurrent teardown callers without holding the attempt lock across service/session callbacks.

Fence asynchronous USB data-path opens with a generation token and recheck it before publishing the session or NCM bridge. A stale callback closes its own resources and cannot affect the replacement attempt.

## Route CarPlay OEM host UI requests from the common host layer

`CarPlayController` forwards `onHostUiRequested` through its UI listener without selecting an Android Activity. `CarPlayHostActivity` opens `DiPlayMenuActivity` so `shared` does not depend on `common`; the existing `CarPlayBackgroundSession` remains responsible for retaining the controller and media sink while the host UI is covered.

## Scope menu return behavior to the settings root

Treat a menu-origin `DiPlayActivity` as the settings subtree: Back from About returns to Settings, and only Back from the Settings root finishes the Activity to reveal the Menu. When a reconnect starts a new CarPlay host, finish the menu-origin Settings Activity only after the host launch succeeds so repeated setting changes do not accumulate Settings instances.

## Separate navigation output offers from voice and media

Restrict the high-rate-only negotiation fix to stream type 101 (AltAudio). Wired type 101/default and compatibility advertise configured 44.1/48 kHz PCM; wireless type 101/default also advertises Opus, while compatibility stays PCM-only. Keep type 100 MainAudio PCM capabilities broad, and make its Opus and microphone input offers transport-specific. Keep type 102 media unchanged.

Keep the transport flag false by default and set it only when creating the wireless AirPlay session config. This preserves wired callers while making /info capabilities match the active transport.

## Remove BYD outputs without changing RouteGuidance negotiation

Delete the BYD output consumers and their `onIncoming` controller callbacks while retaining the generic iAP2 RouteGuidance advertisement, subscription messages, endpoint registry, and subscription test. The capability is part of protocol negotiation independently of the removed HUD output.

## Bind persistent YouTube storage to a verified iPhone identity

Resolve identity in this order: Pair Verify controller ID, AirPlay device ID, then Wi-Fi MAC. Hash a versioned, source-prefixed key before using it as GeckoView's context ID. Do not store the raw identifier or Google credentials; GeckoView owns the persistent cookie and web storage for each context.

Keep one application-scoped GeckoRuntime and create/close Activity-scoped GeckoSessions per device context. Closing a session must never clear its storage context, so returning to the same iPhone restores its Google login.

## Split CarPlay and YouTube inside the existing host

Keep CarPlayController, AndroidMediaSink, and CarPlayBackgroundSession in CarPlayHostActivity. Put the CarPlay TextureView, touch layer, and connection panel in the same left pane; let GeckoView own the right pane. Defer CarPlay restarts until the latest pane size has settled, and use ABI-specific APKs to preserve all supported architectures without shipping every GeckoView native library to every device.

## Keep restart ownership pending until a new controller is stored

A resize restart remains claimable across Activity destruction until the replacement CarPlay controller is stored. A claimed but not-yet-started Activity releases the claim on destruction; an ownerless handoff can be cancelled by Disconnect and suppresses automatic startup until a fresh host launch. Transfer the stop callback when a new host claims ownership so Disconnect never targets a destroyed Activity.

## Bound Google popups to one same-context GeckoSession

HTTPS new-window requests and `about:blank` can create one temporary GeckoSession using the same iPhone context ID. Return to the primary session on popup close; deny HTTP and external schemes, do not log or persist popup URLs, and restore the primary session's most recent load state.

## Follow GeckoView's unopened popup-session contract

For the pinned GeckoView 156 source, `onNewSession` must return a closed GeckoSession; Gecko opens it on the parent runtime with the generated new-session ID. Attach the closed session before return, never call `open()` in the delegate, and validate each navigation callback independently instead of holding request-pending state.

## Lock a split's profile while allowing verified aliases

Keep the selected context stable for an AirPlaySession when identity evidence overlaps. When Pair Verify later resolves a controller ID, bind that strong hash to the chosen fallback context for the next split, but never resolve a verified controller through weak aliases. Only hashed alias keys and profile hashes are persisted.
## Scope wired media negotiation to stereo LPCM

Wired type 100 media advertises only configured high-rate stereo PCM, and wired `/info` omits type 102 AAC and latency entries. Wireless retains AAC-LC type 102 media with a matching latency type; keep the existing type 101 navigation high-rate negotiation independent.

## Keep navigation routing separate from voice and AAOS

Default mobile navigation-family streams to Full-band `USAGE_MEDIA`/music attributes, but keep System navigation and Legacy music stream user-selectable. Telephony and speech recognition stay on phone/assistant speech attributes. On AAOS, retain the existing bus mapper and its System navigation fallback unless bus mapping is enabled.

## Map feedback from AudioTrack frames to RTP samples

Set the clock base from the RTP sample associated with the first PCM frames accepted by a track. Convert played frames by the effective output/source rate ratio; reset the mapper on track recreation and use elapsed-time estimation only before a track clock is available.

## Bound RTP reordering independently from render buffering

Use short packet/time bounds upstream of the existing decoder queue. Media may wait 30 ms with a 64-packet window; low-latency streams may wait 10 ms with a 32-packet window. Declare gaps on timeout or overflow; do not treat the media render queue as the jitter window.

## Retry failed AudioTrack construction with bounded backoff

Only an initialized track establishes the active output format. Retrying the same format remains possible after getMinBufferSize, builder, attributes, or initialization failure; cap exponential delays at five seconds and reset retry/clock state when a track is released.

## Poll AudioTimestamp for sparse anchors

Use the playback head for frequent progress and query AudioTimestamp after a 250 ms start-up delay, every 500 ms while warming, and every 10 seconds after three successful anchors. Keep independent unsigned frame extenders for the two counters and reset both with their anchor whenever the track generation changes.

## Replay only bounded AAC startup data on decoder fallback

Cache raw AUs with their source sample and presentation time until the decoder produces output or the fallback guard trips. Cap the replay at 100 AUs, 512 KiB, and three seconds, reserving room for the live AU that triggers fallback. If that AU exceeds the reserve or duration window, trim older cached AUs while retaining the live AU; replay by presentation timestamp on the audio worker.

## Preserve feedback NTP behavior until receiver correlation is proven

The repository's NTP clock maps local monotonic time to the phone's synchronized timing domain, but it does not establish whether CarPlay expects feedback's NTP timestamp to anchor the reported sampleTime. Keep the current NTP generation and call out hardware/protocol validation rather than inventing a monotonic-to-NTP correlation.

## Release decoder candidates before AAC fallback

Keep a newly created MediaCodec local until both configure and start succeed. On either failure, best-effort stop and release that candidate before publishing diagnostics or constructing the raw-AAC ADTS fallback; use the same lifecycle for Opus.

## Probe unavailable or stale AudioTimestamp routes sparsely

Use a 250 ms startup delay, 500 ms warm-up, and switch to 10-second probes after five false results or ten warm-up queries. Enter 10-second stable polling only after three advancing frame positions; repeated stale frames also return to probing. A probe only returns to warm-up when its frame position advances.

## Start AAC no-output timing at the first queued access unit

Start the two-second fallback timer only after an AU is actually queued into the raw AAC decoder. Do not charge decoder setup time or input-buffer failures against the startup grace period; configuration failure and bounded-cache overflow remain immediate fallback reasons.

Treat any real raw AAC PCM output as decoder success and clear its compressed startup cache even while AudioTrack is unavailable. Skip PCM copying and normalization until an initialized track exists.

## Recover AudioTrack operation failures on its owner thread

Catch playback, pause, and write exceptions on the audio worker, then release the failed generation and recreate from the retained pending decoder format. Record underrun baselines per initialized track and query the routed device once after playback starts.

Keep operation-failure backoff separate from successful track creation so repeated play/pause/write failures keep increasing the delay. Apply it to every negative nonblocking write result as well as exceptions. Reset it only after five seconds of advancing playback; keep an independent 500 ms watchdog for continuous nonblocking writes that return zero.

## Require advancing timestamps during sparse probes

A probe that returns `true` with a stale extended frame position is still unavailable. Keep the 10-second probe interval until the frame advances, then resume 500 ms warm-up.

## Ignore IME-only CarPlay display changes

During IME visibility and animation, keep the negotiated CarPlay size and discard pending transient TextureView sizes. After IME animation ends, recheck the laid out size after two stable frames so a true rotation or pane resize still renegotiates once.

## Exit Gecko fullscreen before returning the view to the split pane

Any host-initiated fullscreen exit sends `GeckoSession.exitFullScreen()` before reparenting. Popup close exits both the closing session and the restored primary session; the host immediately updates its layout while Gecko's callback completes.

## Avoid retaining a finished AirPlay session in profile selection

Store the connection token as a weak reference and clear the split selection when the active AirPlay session ends or the split exits. Do not clear it unconditionally on Activity destruction because configuration recreation may reuse the same live session.

## Mark Pair Verify identity resolved after final-state processing

Set identity resolution in `finally` after processing Pair Verify state 3, and publish the verified controller ID before the verified flag. A fallback decision cannot observe an incomplete final-state transition.

## Stage and token-fence audio replacement

Bind and validate a new audio stream before changing its owner so failed SETUP leaves the current stream intact. Commit a per-type generation token only after prepare succeeds, then pass that token through engine callbacks, renderer/microphone entries, stop operations, and playback-clock reads; this closes check-then-act races without putting a shared lock on RTP delivery. Derive `/info` output masks and SETUP acceptance from the same transport/type/audioType policy. Keep the last successfully activated state separately from a pending logical owner, and retire it only after the replacement receiver starts; a failed start can then restore that state. Check session closure during the locked commit and read/detach owner state under the same slot lock. Derive Opus microphone PCM frames, encoder configuration, and RTP timestamp increments from the negotiated sample rate. Make sink start success observable to the engine so a failed renderer start does not permanently suppress future retries; record microphone state only when the sink reports a successful start.

## Rate-limit microphone restart and health checks

Keep a monotonic 750 ms next-attempt deadline per audio state so temporary uplink failures do not rebuild AudioRecord and the encoder on every RTP packet. Recheck active same-token uplinks at that cadence; conditionally detach inactive entries under the per-type lock and close them outside it before creating a replacement.
