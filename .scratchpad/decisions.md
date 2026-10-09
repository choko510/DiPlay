# Decisions

## Run emulator instrumentation beside host verification

Keep native/unit/lint/build verification and Android instrumentation in independent GitHub Actions jobs so emulator boot and connected tests overlap the longer host Gradle job. Install the API 35 system image only in the instrumentation job and give each job a distinct report-artifact name; keep every existing gate enabled.

## Keep DSP latency and fallback aligned to one AudioTrack generation

Prepare the initial codec graph on the audio renderer worker before its first PCM write. Once an AudioTrack accepts PCM, `trackGenerationLatencyFrames` is the source of truth for both playback-clock correction and output alignment; maintain a preallocated delayed-dry ring so a failed graph or a sample-rate-mismatched IR replacement can preserve the same content timeline. Keep desired DSP config separate from the effective graph for format-incompatible Convolvers. Rebuilds that would start an active Convolver or long-state dynamics graph without history defer to the next AudioTrack generation. Cancel and retire an in-flight fade when decoder format changes.

## Make the global DSP switch the only runtime enable source

The persisted global master setting controls runtime enablement. Keep `profile.enabled` for old profile-schema compatibility, but do not couple it to the master switch or let a master toggle apply the editor's uncommitted draft.

## Disable only rate-invalid Dynamic EQ bands at prepare time

Keep the universal editor ceiling at 19.8 kHz. When a legacy profile's enabled band is at or above `sampleRate * 0.45`, disable that band in the prepared graph and retain the other DSP stages; keep the saved profile unchanged so the band can work again at a compatible rate.

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

## Keep NCM bulk-IN persistent and drain an available status endpoint

Treat `requestWait` timeout as idle while preserving the queued request; treat a null result, queue failure, or unexpected completion as terminal for the current NCM connection. Let the controller's existing clean retry reopen the connection when the device remains present. Poll and drain the CDC status endpoint by default whenever one is available, but do not gate NCM or AirPlay startup on a `NETWORK_CONNECTION` notification. Keep `NO_STATUS_POLLING` as a debug-only comparator.

## Separate NCM packet proof from CarPlay startup milestones

Classify NCM directionality only from observed inbound and successful outbound IPv6 proof. The readiness phase may also be advanced by accepted AirPlay control traffic, so report that signal, screen-stream opening, and the overall attempt outcome separately. Keep short result fields before long descriptor summaries so bounded log export retains them.

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

## Keep DSP semantic role separate from Android audio routing

Classify each stream once when its `AudioRenderer` is created, deriving the Android route through `AudioChannelMapper` and the DSP role from CarPlay metadata. Only the `MEDIA` role may use the full DSP chain, even when navigation audio is routed through `USAGE_MEDIA`; snapshot DSP config per renderer and default provider failures to the disabled legacy path.

## Keep canonical DSP conversion behind explicit opt-in

Expose only the immutable runtime config and snapshot provider across `shared` and `common`; keep PCM math and processors internal. Build the reusable 512-frame Float32 pipeline only for enabled semantic media, preserve the existing PCM16 path when disabled, and recreate pipeline state when the decoder sample rate or channel count changes.

## Keep the native core portable and failure-isolated

Keep DSP math independent of JNI and Android logging, allocate engine state during preparation, and validate opaque generation handles before dereferencing engine memory. DSP library load/create/process errors disable the renderer pipeline and route the current PCM block through the legacy path.

## Prepare RBJ filters off the audio path and retain double coefficients

Calculate normalized RBJ coefficients in double precision when preparing a format/config snapshot, then copy at most 15 sections into the native graph. Process float samples/states with TDFII and no per-block allocation. Auto Headroom scans the product of PEQ and optional static-bass responses at the same 1024 log-spaced frequencies, then applies its reduction to preamp before EQ. It uses active compressor makeup and stereo width now; PR-DSP-07 connects the static-bass profile response to the estimator and audio stage.

## Link compressor and limiter gains across stereo and keep the limiter last

The compressor uses one peak envelope from `max(abs(L), abs(R))`, applies one 4:1-capable gain to both channels, and adds makeup after the compression curve. The final sample-peak limiter clamps immediately whenever a frame exceeds its threshold, then releases gain only while the frame stays below threshold. This keeps linked image, zero lookahead, and a hard output ceiling without adding true-peak oversampling.

## Keep stereo and mono bass processing inside the DSP graph

Apply the static low shelf to each channel after PEQ and before compression. Apply linked M/S width and optional mono-bass high-pass after compression; mono input bypasses both stereo operations. The limiter remains after spatial processing so width expansion cannot exceed the final sample ceiling. Auto Headroom uses the same static low-shelf settings.

## Keep DSP profile I/O out of the audio config provider

Persist profiles as schema-versioned JSON under `filesDir/dsp/profiles/` with `AtomicFile`; keep only the global enable flag and selected profile ID in SharedPreferences. The common runtime loads storage before publishing the config store, then `DspConfigProvider.snapshot()` only reads an `AtomicReference`. Each `AudioRenderer` retains its own immutable snapshot, so a profile change affects new streams without mutating an active renderer. Never overwrite a file whose schema version is newer than this app.

## Keep three-band dynamics internal and profile-versioned

Split Low/Mid/High with complementary LR4 filters made from two cascaded second-order Butterworth stages per branch, using 120 Hz and 2.5 kHz defaults. Each band's compressor links Left/Right with one peak detector; mono stays mono. Keep the whole stage disabled by default. Persist the new settings as profile schema v2 and migrate v1 profiles with Multiband disabled so older apps preserve v2 files as future schema.

## Keep Dynamic EQ gain updates stable and bounded

Use at most five band-pass detector plus static peaking-filter pairs. Each band selects CUT or BOOST around its threshold and clamps to its configured maximum. Smooth the mix between dry and the fixed, stable filter output over 16 samples; never interpolate changing biquad coefficients. Keep the stage disabled by default, include active boost limits in Auto Headroom, and store the fields under profile schema v3.

## Prepare live DSP snapshots off the audio worker

The config provider publishes an immutable generation with each snapshot. Its observer only queues preparation on the dedicated control executor; the audio worker adopts a ready pipeline through an atomic slot and crossfades PCM16 for 20 ms. Adopt only when sample rate, channel count, and algorithmic latency match. Defer latency-changing convolver profiles to the next renderer, and retire replaced pipelines on the control thread after the fade.

## Stage and token-fence audio replacement

Bind and validate a new audio stream before changing its owner so failed SETUP leaves the current stream intact. Commit a per-type generation token only after prepare succeeds, then pass that token through engine callbacks, renderer/microphone entries, stop operations, and playback-clock reads; this closes check-then-act races without putting a shared lock on RTP delivery. Derive `/info` output masks and SETUP acceptance from the same transport/type/audioType policy. Keep the last successfully activated state separately from a pending logical owner, and retire it only after the replacement receiver starts; a failed start can then restore that state. Check session closure during the locked commit and read/detach owner state under the same slot lock. Derive Opus microphone PCM frames, encoder configuration, and RTP timestamp increments from the negotiated sample rate. Make sink start success observable to the engine so a failed renderer start does not permanently suppress future retries; record microphone state only when the sink reports a successful start.

## Rate-limit microphone restart and health checks

Keep a monotonic 750 ms next-attempt deadline per audio state so temporary uplink failures do not rebuild AudioRecord and the encoder on every RTP packet. Recheck active same-token uplinks at that cadence; conditionally detach inactive entries under the per-type lock and close them outside it before creating a replacement.

## Keep DSP timing bounded and optimize only after repeated measurements

Record native processing and the full PCM pipeline in separate fixed-width histograms with no per-block collection or sorting. Expose cumulative percentiles only in the existing periodic audio stats log. Preserve the 512-frame chunk and portable 128-frame convolver partition when repeated 44.1/48 kHz Mono/Stereo benchmark runs meet the light/heavy p99 budgets; do not add SIMD or math changes without a stable measured hotspot.

## Preserve DSP latency behavior when reviewing newer main

When synchronizing after origin/main advanced through PR #13, resolve audio conflicts while preserving PR #14's DSP path and convolver clock correction. The incoming `AudioPlaybackClock` version removes algorithmic-latency compensation, and the main snapshot omits the unmerged PR #14 DSP files. Keep the compatible submitted-frame callback update; PR #15's renderer lifecycle hardening is already integrated on this branch.
## Keep split-mode Gecko reuse Activity-scoped and identity-gated

On a normal split exit, close any popup, leave the primary `GeckoSession` inactive, and retain it only for the current Activity. On re-entry, keep its view hidden until the current AirPlay profile resolves; reuse without reopening or reloading only when the profile context matches and the primary session remains open and healthy. Destroy it on profile change, AirPlay end, crash, shutdown or Activity destruction.

## Keep CarPlay resize on the verified restart fallback

Advertise the existing single ViewArea but do not invent a runtime switch payload. Keep the full controller restart path until the command, dimensions and decoder behavior are verified. Enable R8 through the AGP 9.3 optimization DSL and use a non-debuggable, profileable benchmark build signed with the debug key; opt in trace collection through its benchmark manifest metadata.

## Revalidate profile identity before discarding a warm GeckoSession

A replacement AirPlaySession does not by itself mean the iPhone profile changed. Hide and suspend the retained browser, resolve the new `youtubeContextId`, then reuse on a match or close/create on a mismatch. For an already active same-profile `open()`, preserve the current session (including a popup) and do not republish primary-session navigation state.

## Keep JNI string lookups in shared consumer rules

The Linux I2C native bridge resolves `LinuxI2cNativeException` and its constructor by name. Preserve only that class and `(int, String)` constructor in the shared AAR's consumer rules so both apps retain the JNI ABI without broad app keep rules.

## Measure presentation and restart completion at their user-visible endpoints

Keep page network readiness separate from visible Gecko paint. End split presentation after a visible primary/popup FCP or a post-reset composite, and end CarPlay restart after the first main video output is submitted for rendering. Release-like profiling builds must be non-debuggable and profileable.

## Reuse across AirPlaySession replacement only after profile resolution

Do not tie GeckoSession lifetime to AirPlaySession object identity. During split-mode controller restarts, hide/deactivate the existing browser and resolve the replacement session's profile; keep the old GeckoSession only for a matching `youtubeContextId`, otherwise let `open()` replace it.

## Separate active same-profile open from suspended warm reopen

An active `open()` request targets the currently selected GeckoSession, which may be a Google popup. Preserve that session and its UI state. Only the explicit SUSPENDED lifecycle path reactivates the primary session and increments the warm-reopen counter.

## Opt into benchmark tracing without making the app debuggable

Benchmark build types inherit release, use debug signing and are profileable with shell access. A variant-only manifest metadata flag enables custom tracing; production release stays non-debuggable with tracing disabled.

## Keep warm-reopen instrumentation aligned with actual activation

Start the warm-reopen span around `GeckoSession.setActive(true)` after the view has been made visible, not during identity resolution or an inactive call. Keep Gecko page-load stop, visible Gecko paint and first main CarPlay output submission as separate endpoints.

## Gate Gecko UI updates on the current active presentation

Keep progress and navigation state from the current suspended session internally, but publish to the Activity only when the controller is active and the callback's session is still current. Publish the latest cached state once after `setActive(true)` succeeds. Treat MediaCodec `releaseOutputBuffer(true)` as submission to a render surface, and close a missing-paint trace as a diagnostic timeout rather than success.

## Keep Gecko paint outcomes terminal and memory trims specific

Treat paint completion, timeout and cancellation as mutually exclusive terminal outcomes for one activation measurement. On Android versions that deliver `TRIM_MEMORY_RUNNING_CRITICAL`, evict a suspended session only for running-critical levels below `TRIM_MEMORY_UI_HIDDEN`; Android 14 and later do not deliver the running levels, and UI-hidden alone keeps the warm session.

## Preserve paint-reset evidence through warm activation

Tag each primary-session `onPaintStatusReset()` with the current suspension generation. A measurement ending must not erase session evidence; a new distinct suspension advances the generation so older reset events cannot authorize its paint. Cold session creation starts a fresh generation. Start the timeout deadline on first activation and carry its absolute elapsed-realtime value across pause/resume and repeated same-profile opens.

## Keep wired NCM experiments isolated and debug-only

Persist one NCM diagnostic profile only in debuggable builds. `AUTO` polls the status endpoint when available and keeps the 100 ms pre-ready OUT timeout; `NO_STATUS_POLLING` isolates the previous off behavior. Timeout, sync Bulk IN and forced function selection profiles inherit status polling so future timeout comparisons keep it enabled. Do not combine profiles or add CDC-NCM control requests without new A/B evidence.

## Keep local MFi identity outside tracked source

Let debug builds receive accessory identity through `DIPLAY_AUTH_ASSETS_DIR`. Allow only the expected identity and certificate assets, and reject every other key-like file from APK asset inputs. Never copy those local assets into the worktree.

## Include both APK signing schemes for mobile debug distribution

Explicitly configure the mobile debug signing config for v1 and v2 so OEM installers that depend on JAR signatures can parse the APK. Verify the resulting artifact with `apksigner`; the manifest minSdk can affect which scheme is reported as applicable.
