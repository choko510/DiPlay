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
