# Lessons

## Do not remove a Wi-Fi Direct group from partial identity

When Android omits a system-generated passphrase, the group may be unusable and still unconfirmed for cleanup. Test that the fallback sends a null configuration and that cleanup skips a group without complete identity.

## Wired CarPlay "Opening" is a multi-stage interval

The host labels `RunningControl` as "Opening CarPlay", but that status is emitted before wired iAP2 identification and MFi authentication. The panel disappears when the first AirPlay screen stream is opened, before the first video frame is rendered. Diagnose a long display using timestamp gaps between `STEP iap2/wired`, iAP2 progress, AirPlay SETUP, and screen-stream logs; the label alone does not identify the slow operation. Optional syslog capture and 256-byte CarKit write splitting are now gated by the debug setting; their cost remains unmeasured when enabled.

## Classify transport exceptions by operation context first

Kotlin nested exception subclasses such as `IphoneUsbException.Protocol` and `.TimedOut` may be reused across USBMUX, Lockdown plist/TLS, and iAP2 operations. Check explicit operation markers in the cause chain before generic exception subtype fallbacks; use the subtype only when context is absent.

## Treat USB request wait timeouts as pending operations

For `UsbRequest.queue(ByteBuffer)`, a wait timeout does not return the queued request. Keep its buffer untouched until that request completes; read the advanced position only after `requestWait` returns the same request. Register a raw USB session before a blocking handshake so concurrent teardown can close it even before a higher-level host is published.

## Scope callbacks and failures to the active wired attempt

An old AirPlay session callback can race detach and a new attach unless its listener is generation-scoped. Likewise, closing a VPN/NCM attachment does not close controller-owned iAP2/USBMUX resources unless the transport error is propagated back to the controller.

An attempt token check must guard the actual state mutation or teardown, not just precede it. Otherwise an old callback can pass its check, pause during cleanup/reconnect, and resume against the new session.

## Correlate USB permission results with their request

Filter permission broadcasts by a unique request ID, expected USB device, and current phase; a matching vendor/product ID alone does not distinguish a stale result from a newer attempt.

## Keep the startup watchdog through screen setup

AirPlay `RECORD` reports a live control session before the screen stream is set up. If the recovery goal is to prevent the visible Opening panel from hanging, cancel the watchdog at screen-stream open rather than at `RECORD`; first-frame delivery can remain outside that threshold.

## Keep menu-origin settings navigation scoped to its back stack

Treat About as a child of Settings: Back returns to Settings, while Back from the Settings root finishes the menu-origin Activity. After a reconnect launches the CarPlay host, finish that Settings Activity so repeated changes do not build up stale instances. Ordinary DiPlay launches keep their existing settings-to-home navigation.
## Compare advertised and negotiated audio formats

The `/info` output mask and the SETUP `audioFormat` bit are the two sides of format negotiation. Log both with stream type and audioType so a real-device report can show which advertised candidate was selected; keep receiver decode support separate from the formats offered for output. Apply a negotiation change to the specific AirPlay stream type under investigation so it does not narrow MainAudio by assumption.

## Distinguish USB request timeout from error

For `UsbDeviceConnection.requestWait(timeout)`, `TimeoutException` means no request completed before the deadline and leaves the request pending. A null return means an error. Keep these paths separate in both lifecycle state and diagnostics.

## Keep link-layer retries narrower than network retransmission

NCM bulk OUT may be not-ready before CarPlayStartSession. Use short single attempts for all traffic until readiness is proven, retry only Neighbor Solicitation/Advertisement frames during startup with a strict attempt and delay bound, and do not retry TCP or UDP frames after an ambiguous USB result.

## Apply wired retry policy from the first data-path open

Allocate the wired attempt before opening the iAP2 USB session. Route USB/NCM open and claim failures through the same bounded retry policy as runStack failures so a pre-stack error cannot fall back to the host's long generic reconnect delay.

## Preserve pending USB cancellation across repeated failure handling

USB read error paths can mark the same request failed in both the low-level state transition and the session error handler. Keep the pending-cancel flag sticky until close so idempotent failure handling cannot lose the queued request cleanup.

## Match Robolectric helpers to the installed API

The current Robolectric `RuntimeEnvironment.getApplication()` method is not generic. Let Kotlin infer the application type instead of adding a type argument in tests.

## Require bidirectional proof before NCM readiness

An inbound NCM IPv6 packet proves Bulk IN only. Wait for a successful IPv6 Bulk OUT as well before declaring the NCM link ready; accepted AirPlay control traffic can establish readiness independently.

## Infer Apple mode from configurations, not GET_MODE bytes

The usbmuxd GET_MODE reply is diagnostic input; the mode guess comes from the advertised configuration set. Do not infer that setting mode 1 and then mode 4 creates a safe reset.

## Recheck asynchronous USB opens before publishing

USB session creation can finish after a manual reconnect or shutdown. Bind each open to a generation and close any session/bridge returned for a stale generation before reporting success or failure.

## Keep iAP2 capability negotiation separate from an output consumer

When deleting an application-specific RouteGuidance consumer, keep the generic iAP2 advertisement, subscription endpoints, and regression test. Remove only its callback hookup and let the transport's existing default handler discard frames.

## Pin GeckoView to its tested stable artifact and matching toolchain

GeckoView 156.0.20260921121718 resolves from Mozilla Maven, requires compile SDK 37.1 and brings Kotlin 2.4.10 metadata. This project uses Kotlin Gradle plugin 2.4.20 and Java 17 compatibility; do not suppress the compiler's metadata-version check or force an older Kotlin runtime.

`GeckoSessionSettings.Builder.contextId` partitions cookies and web storage, and GeckoView retains data per context after a session closes. A deterministic context ID is sufficient for returning to the same device; app-owned Google credentials or a raw-ID profile index are unnecessary.

## Preserve all native ABIs while controlling GeckoView package size

The GeckoView AAR includes arm64-v8a, armeabi-v7a and x86_64 native libraries. A universal APK exceeds 570 MB in this build; ABI-specific outputs preserve the existing support while avoiding unrelated libraries in each download.

## Treat a restart handoff claim as provisional until publication

An Activity may claim a resize restart and then wait on permission or transport readiness. Keep the handoff pending until `CarPlayBackgroundSession.store()` publishes the replacement controller; if the Activity is destroyed first, return ownership to the next host. Transfer its stop callback at claim time.

## Treat Gecko's new-session URI as policy input, not a load command

The pinned GeckoView 156 source asserts that the returned session is unopened, then opens it on the parent runtime with its generated session ID. A closed GeckoSession can be attached to GeckoView before return. `onLoadRequest` and `onNewSession` should each validate policy independently; callback order and URL equality are not reliable gate state.

## Delay UI-thread runtime creation until after the first split frame

`View.postOnAnimation` runs before that frame's traversal. A second animation callback lets the split's first traversal draw before the synchronous portion of Gecko warm-up can consume UI time. Keep both callbacks named and cancel them on split exit and Activity teardown.
## Preserve AAC bytes unless RFC 3640 is fully validated

Do not classify AAC as RFC 3640 from the leading length field alone. Validate 16-bit alignment, header bounds, nonzero AU sizes, and exact aggregate data length; otherwise pass the complete payload through as raw AAC.

## Keep the playback clock in the source sample domain

AudioTrack reports output frames, while CarPlay feedback uses source RTP samples. Keep both rates in the immutable clock snapshot, map from the first accepted PCM frame, handle 32-bit head/sample wraps, and reset the base when recreating a track.

## Retry peripheral audio setup without suppressing the stream forever

Do not use a last-attempted AudioTrack format as a permanent duplicate guard. Treat a format as active only after the track reaches `STATE_INITIALIZED`; rate-limit retries with bounded backoff, and keep the pending format so temporary HAL failures can recover during the stream.

## Enforce replay limits after adding the live fallback AU

A reserve covers ordinary packet sizes but cannot guarantee a hard byte or duration cap by itself. When a live AU trips the AAC fallback, merge it with the cached AUs and evict older cached entries until all replay limits hold while keeping the live AU.

## Treat AudioTimestamp availability and usefulness separately

`getTimestamp() == true` is not enough to trust a vendor clock. Require advancing extended frame positions, stop frequent warm-up polling when the route stays unavailable or stale, and keep playback-head progress available while probing sparsely.

## Count RTP duplicates before reorder depth

A duplicate still has positive sequence distance from the next expected packet. Check pending/delivered membership before incrementing `reordered`, then test duplicate arrivals against an already pending out-of-order packet.

## Separate decoded output from rendered PCM

If MediaCodec emits real PCM while AudioTrack setup is failing, that still disproves an AAC no-output failure. Clear the compressed startup cache then, and avoid copying/normalizing PCM until an initialized output track is available.

## Keep recovery backoff across successful construction

A track that builds but fails immediately on the next HAL operation is not a recovery success. Track operation failures separately from builder failures, and only reset operation backoff after sustained advancing playback; bound zero-write waits by elapsed time.

## Treat nonblocking zero writes as backpressure until they stall

`WRITE_NON_BLOCKING` may return zero temporarily. Measure the continuous zero interval rather than retry count, then release and back off if no frame is accepted within the stall bound.

## Keep IME resize separate from negotiated CarPlay size

With `adjustResize`, filtering only surface callbacks is insufficient if a debounce is already pending. Cancel pending size work as soon as IME animation begins, update the restart handoff with the stable size, and refresh the settled view after IME animation ends. Retain `adjustResize` so login fields remain usable.

## Recheck privacy-sensitive logs after merging main

Later main changes can reintroduce raw controller or device identifiers into debug logs even when the PR branch had sanitized them. Search logging expressions after each merge and log availability or hashed identifiers instead.

## Keep audio setup preparation separate from ownership commit

Bind both UDP sockets with no receive workers first, then atomically publish the new generation and only start its workers while it is still current. Keep the previous activated owner available until that start succeeds, and make closed-session commit plus teardown owner reads linearize under the per-type slot lock. Exercise A→B→C and prepare/close interleavings with latches; token checks at the sink and map-removal boundary catch races that an engine-only owner check misses.

## Keep microphone Opus frames in the negotiated sample domain

For 20 ms Opus uplink frames, derive samples and PCM bytes from the negotiated sample rate: 16 kHz is 320 samples/640 mono S16 bytes, 24 kHz is 480/960, and 48 kHz is 960/1920. Configure MediaCodec with that same rate and advance RTP timestamps by the same sample count; 960 samples is not a rate-independent frame size.

## Propagate renderer and microphone start outcomes

If a sink swallows a synchronous renderer-start failure, the engine can mark an absent renderer as started and drop every later RTP packet. Return success only after the worker has started; likewise mark microphone state started only after its uplink is active, and test that the next RTP retries after a failed renderer start.

## Keep microphone recovery independent from downlink startup

After the downlink renderer succeeds, microphone startup still needs its own retry path. Rate-limit retries and liveness checks with a monotonic deadline, and replace an inactive same-token uplink so a capture thread that exits cannot leave a permanent dead entry.

## Resolve the current iPhone before reactivating a warm YouTube view

A retained GeckoView can still display the prior account while inactive. Hide it and keep its session inactive as split mode opens; re-show it only after the current connection's profile is resolved. Same-profile reuse must not call `open()` or `loadUri()` again.

## Keep IME resize suppression through layout settling

Suppress pending display-size work once at the IME edge, then use the existing two stable frames to distinguish a real pane/orientation change. Clearing suppression at animation end can let the keyboard's final transient size restart CarPlay.

## Revalidate profile identity across a CarPlay controller restart

The AirPlaySession object changes during a full display restart even when the same iPhone reconnects. Hide and suspend the retained GeckoSession until identity resolution; only the resolved `youtubeContextId` decides whether it can be reused.

## Keep JNI upcall names in the shared library's consumer rules

Native `FindClass()` and `GetMethodID()` string lookups are invisible to R8. Preserve the exact class name and constructor in the module that owns the JNI code so all consuming apps receive the rule.

## Measure the rendered path with a profileable non-debuggable APK

R8 success is a packaging check. Use a non-debuggable profileable variant for runtime measurements, keep tracing opt-in for that variant, and treat page-load completion separately from the first visible paint or decoded CarPlay frame.

## Reconcile IME suppression after Activity resume

If an Activity misses the keyboard hide animation while backgrounded, read current insets on resume/focus and restart the stable-frame settle check when the keyboard is hidden.

## Keep the previous phone's browser suspended during reconnect

A display restart can replace the AirPlaySession while split mode remains visible. Retain the GeckoSession temporarily, but hide its view and keep it inactive until the new session resolves; the resolved profile context, not the session object's identity, decides reuse.

## Profile optimized benchmark builds instead of making them debuggable

Use release-derived R8 settings, a debug signature and `<profileable android:shell="true" />`. Enable app trace sections through variant-specific metadata so release-like timing isn't measured under debugger instrumentation.

## Verify JNI symbol retention from the optimized mapping

Native string lookups are invisible to R8. Ship a targeted consumer rule from the native-owning library, then inspect both optimized app mappings for the unchanged class name and constructor descriptor; a successful R8 build alone doesn't exercise the JNI failure path.

## Do not time a UI or transport milestone at request submission

Use Gecko's content paint/compositor callbacks after activation for split presentation and the decoder's first main-output submission for CarPlay recovery. Keep page-load stop and controller `start()` request as separate earlier milestones.

## Suppress stale Gecko UI callbacks during profile revalidation

Gecko can deliver page-stop and process-crash callbacks after a session becomes inactive. Session identity alone does not protect the host UI; also gate on controller lifecycle and activation, cache valid current-session state, and republish it only after successful reactivation.

## Make paint timeout a terminal result

If a paint timeout closes a trace but leaves its pending flag set, later callbacks or Activity resume can count both timeout and success or schedule repeated timeouts. Store completion, timeout and cancellation as mutually exclusive measurement outcomes; clear the Back affordance explicitly when destroying its controller.

When Gecko reports paint-status reset during `setActive(false)`, retain it through profile-resolution timeout and warm reopen. Associate it with a suspension generation instead of clearing a boolean on measurement end; a later suspension must observe its own reset before primary paint is accepted.
