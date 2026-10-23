# Decisions

## Keep EX-08 provenance separate from current decisions

Preserve the upstream catalog original source_review_state as provenance. Treat generated current_review_state and disposition as the present decision layer; NEED_EVIDENCE means a documented requirement remains unresolved, not semantic proof or parity. Report current totals from the generated ledger, never from stale catalog-state counts.

## Keep documentation-only and workflow-only PR checks lightweight

For pull requests, always run public-tree and EX-08 inventory checks. Compare host verification against the latest successful `host-baseline` marker and emulator instrumentation against the latest successful full `check` plus `android-instrumentation` run. Changes limited to `docs/`, `.scratchpad/`, `.github/workflows/`, Markdown files, or the exact audit-only `scripts/generate_step4_ex08_inventory.py` may skip product runtime checks; the inventory `--check` still runs on every PR. Unknown paths, missing/failed baselines, large diffs, and API errors require the relevant verification path. Pushes and manual runs use the full suite.

## Cache Gradle task outputs only within the same pull request

Allow Gradle's local build cache to be written for same-repository pull requests, where GitHub scopes those cache entries to the PR merge ref. Keep cache writes disabled for fork pull requests, and enable `--build-cache` in both Android Gradle invocations so unchanged task outputs can be reused on later commits.

## Enable Gradle parallel execution in full CI checks

Pass `--parallel` only to the full CI Gradle task set. The previous no-flag full multi-project run spent 8m58s in Gradle; keep the option out of global developer settings, and separate its benefit from warmed task-cache hits when comparing runs.

## Defer emulator instrumentation while a PR is draft

Keep public-tree and EX-08 checks on every PR update. For code-changing draft updates, always run shared/common unit tests; run native DSP and ASan/UBSan only when DSP C, KissFFT, native test files, or their test script changed since the latest successful quick baseline. Defer lint, app builds, JNI, and connected instrumentation until `ready_for_review`. A successful draft quick-check marker is separate from the full host baseline, so documentation-only follow-ups can skip it without satisfying the full-review gate. Pushes, manual runs, and review-ready source updates run the full suite; unknown paths, missing baselines, large diffs, and API errors also fall back to full verification.

## Use the minimal Android SDK for draft unit tests

Draft unit tests require API 37.0/37.1 and Build Tools 36.0.0, but their task graph has no external native build or ADB task. Omit platform-tools and NDK 27 from this path; full host and emulator verification continue to install their full SDK packages.

## Verify profile migration with Android AtomicFile

Keep the JVM schema-migration tests for broad version coverage and exercise a schema-v3 profile through the platform `AtomicFile` in `common` instrumentation. This establishes Android file-write/read behavior and default-off fields; it does not establish an installed-release rollback or production-signed APK upgrade.

## Validate manual-hotspot settings only for wireless transport

`CarPlayRuntimeConfig` carries saved wireless settings into both transports. Require a manual hotspot SSID only for `WIRELESS`; a wired session does not start that hotspot. Keep the wireless credentials, route, and hotspot-manager behavior unchanged, and cover both sides of the validation boundary in a pure unit test.

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

## Keep WIRED_PATH_V1 separate from ConnectionTraceEvent

Use the existing debug-log opt-in for one wired-attempt recorder. Store only fixed atomic counters and first monotonic timestamps; keep `wiredStartupAttempt`, random controller run ID, and VPN `attachGeneration` distinct. Append the fixed `WIRED_PATH_V1` line through the bounded trace writer without adding stages/details to the existing trace-event schema. Sealing drops late events; a generation mismatch increments only `stale_callback`.

## Keep all remaining STEP4 stages in PR #45

Per the user's latest instruction, fold every remaining STEP4 stage into the existing PR #45. Preserve the specification's dependency order through sequential commits and validation records; do not open additional STEP4 PRs. The second independent review remains `NOT_RUN` by user direction.

## Keep conditional transport changes evidence-gated

Do not alter watchdog deadlines/completion, USB queue fallback, initial NCM OUT timing, StartSession retry, status polling, or IPv4/scope/listener behavior without the corresponding device evidence in the v3 plan. EX-01 already records the needed per-stage wired milestones; this run has no TS10/iPhone trigger trace, so record the conditional branches as `NO_GO_THIS_RUN` or `NOT_RUN` and preserve existing behavior.

## Gate microphone capture on an explicit request policy

Keep `input=false` as a hard microphone veto while allowing the audio downlink to continue. Accept capture only on MainAudio with an advertised voice input type, valid peer port, enabled microphone permission, and mono negotiated format; reuse the existing owner-scoped uplink so an active recorder is not started twice.

## Keep LOCAL Split resizing inside Android

While the current owner has a Controller, valid Surface, and known remote dimensions, preserve the negotiated AirPlay display size and fit a centered content frame into the current pane. Put the TextureView and touch overlay in the same fitted frame so overlay-local coordinates remain normalized to the rendered content and the letterbox bars receive no CarPlay touches. Keep the existing restart path when the local-session preconditions fail or an explicit settings change requests a new controller.

## Add DSP as a mutually exclusive Host Split mode

Keep Full, YouTube Split, and DSP Split in the existing `CarPlayHostActivity` with one 55% CarPlay pane and one exclusive 45% right pane. DSP layout changes are local viewport causes and must never enter remote resize/restart handling; when an active session cannot be fitted safely, keep the requested mode pending over Full. Share one `DspEditorSession` from the application `DspProfileRuntime` so the Activity and Host ComposeView do not fork draft or runtime ownership.

## Fetch the EX-08 source by immutable commit

The inventory uses the pinned audit tree at `a69a4acb6d50116f4855bebbb94b16047c36c466`; fetch that SHA directly in shallow CI checkouts and verify the fetched SHA. Do not depend on a mutable audit branch name remaining on the remote.

## Keep multiple ViewAreas explicit and wire-switching disabled

Preserve the legacy one-area `/info` output when `AirPlayDisplayConfig.viewAreas` is null. A pure validated Full/Split model may serialize two areas under the same main display only when explicitly supplied; each safe rectangle must be contained in its view rectangle, explicit view-area boundaries must align to even pixels, and Split creation rejects a safe rectangle with no overlap. The application leaves the model unset until the target iPhone accepts the declaration. Do not add unknown `updateViewArea`/`requestViewArea` commands without captured acceptance and frame-size evidence.

## Keep DYNAMIC ViewArea and vendor codec changes gated

PR-06 DYNAMIC requires a phone-accepted command/acknowledgement and same-session frame-size evidence; without it keep LOCAL as the split path and send no guessed command. Preserve fixed main's generic Android R+ `KEY_LOW_LATENCY` request when the selected codec reports support; add no PR-07 vendor/device-specific codec or Surface tuning until a TS10/K706 capability or failure trace identifies a tested change.

## Preserve the DSP-OFF baseline before extending the graph

Keep `DspProfileRepository` schema v3 and its migration/future-schema behavior, the single global DSP master as the runtime enable source, MEDIA-only full processing, and the byte-for-byte PCM16 OFF path with zero track latency. New nodes must remain disabled by default and prepare all buffers/coefficients outside the audio thread.

## Align seat timing by explicit near speaker and preserve graph history

Convert the measured distance difference (cm) to at most 5 ms of delay on the user-selected near channel; never infer left/right from RHD. Keep independent L/R trim and polarity in the same preallocated native stage. Include the maximum seat delay in reported graph latency, Auto Headroom for positive trim, and defer changes to active stateful delay graphs until a new track generation.

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

## Keep USBMUX request ownership through a timed wait

Treat `requestWait(timeout)` as a wait expiry, not a completed or cancelled request. Keep the same queued request and buffer for the next wait; cancel only when the session closes or a terminal failure takes ownership. The current `PersistentUsbReadRequestState` and `Iap2UsbSession.read` already deliver a late completion through that owner.

## Keep local MFi identity outside tracked source

Let debug builds receive accessory identity through `DIPLAY_AUTH_ASSETS_DIR`. Allow only the expected identity and certificate assets, and reject every other key-like file from APK asset inputs. Never copy those local assets into the worktree.

## Include both APK signing schemes for mobile debug distribution

Explicitly configure the mobile debug signing config for v1 and v2 so OEM installers that depend on JAR signatures can parse the APK. Verify the resulting artifact with `apksigner`; the manifest minSdk can affect which scheme is reported as applicable.

## Freeze main behavior before STEP4 corrections

PR-00 changes only tests and baseline/ownership/decision documentation. Keep the observed 512-byte no-pad NCM stall as a passing characterization until PR-01 changes the parser and flips the expected result; do not silently convert a failing behavior into an accepted baseline.

## Integrate Advanced M/S by replacing legacy width controls only when enabled

Keep the existing Stereo Width/Mono Bass path unchanged while Advanced M/S is off. On explicit first enable, fold those persisted settings into the new low/high Side-width values, then use one LR4 Mid/Side stage; v4 profiles migrate to schema v5 with Advanced M/S off. Defer stateful crossover and Side-compressor changes to the next track generation.

## Cabin correction requires external measurements and explicit approval

Accept only CSV responses with declared noise-floor/clipping metadata, 20–300 Hz coverage, near-1/6-octave spacing, and at least 12 dB margin above the floor. Suggest at most three bounded static PEQ cuts, keep them off until user approval, and reuse existing PEQ slots; do not estimate the cabin from program audio or add a dynamic tracking stage without calibrated continuous input.

## Harmonic effects use bounded 2× FIR oversampling and report the dry-matched latency

Share one 2× oversampled waveshaper across the Exciter and Virtual Bass branches, use fixed 9/17/33-tap Blackman FIR presets, and delay dry audio by the matching 4/8/16 frames. Keep the effects off by default, bound each branch to 0.5, include worst-case additive gain in Auto Headroom, retain the final limiter, and defer active filter-state changes until the next track generation.

## Batch remaining STEP4 work in the existing PR

Keep all remaining STEP4 stages in PR #45 and update related EX-08 decisions, generated inventory counts, and documentation in one batch. When an upstream commit mixes features with independent gates, reject that aggregate as supplied while leaving its narrow constituent candidates pending until their own evidence is available. Use the draft quick lane for iterative code feedback and reserve the full host/emulator lane for `ready_for_review`; missing hardware/protocol evidence remains `NOT_RUN` or `NEED_EVIDENCE`. The second independent review stays `NOT_RUN` per the user's direction.

For the Wi-Fi source batch, reject mixed hotspot/BYD and wireless/USBMUX commits at the commit boundary. Keep isolated wireless location, security fallback, diagnostics, local-ADB, address-family, and Android 9 channel candidates at `NEED_EVIDENCE` until their consent, permission, API, and supported-head-unit gates are established; upstream device results are not substitutes for TS10/K706 evidence.

Reject upstream `origin/main` integration commits independently of their WIFI heuristic label. Review PR-164 BYD ADB/automatic-hotspot integrations as mixed-gate aggregates and evaluate any generic wireless constituent only from an isolated source commit.

Reject wireless diagnostics/recovery aggregates when they combine behavior changes and logging with independent rollback gates. Reject default-on call audio-focus yielding under the v3 default-off policy; leave Same-LAN, Wi-Fi scan pause, Bluetooth suspension/audio, and hotspot repair candidates at `NEED_EVIDENCE` until their network, privacy, permission, and call-path gates close.

Reject upstream `main` merges into the upstream main or a review branch even when the inventory heuristic labels them WIFI. Their changed paths are not a semantic implementation batch; inspect constituent feature commits separately.

Classify BYD dashboard-map, battery, wheel-speed, cluster-song, DiLink overlay, and DiLink4 projection source rows as `SKIP_OEM` when their actual consumer is a BYD/DiLink output. Keep shared day/night polling as `NEED_EVIDENCE` until its lifecycle is reproduced on the supported head unit, and reject the 52-path launcher/map integration as an aggregate.

Keep DiLink cluster Activity, ADB mirroring, turn-card, dashboard-map, and HUD media-line changes at `SKIP_OEM`; reject origin/main and conflict-resolution merges that bundle those paths with unrelated upstream settings. Do not map vendor cluster stream ownership to PR-05/06 generic display acceptance.

Reject broad upstream release merges that bundle wireless, Android TV/platform, HUD, and map-scaling changes. Keep isolated BYD casting, picture-control, virtual-cluster, and dashboard-content rows at `SKIP_OEM` when their sink is the DiLink surface.

Keep ADB wheel-service restoration and call-write cleanup at `NEED_EVIDENCE` until system authorization and iAP2 call refusal/hangup traces are recorded. Keep shared cluster waiting/fade and decoder-scale changes at `NEED_EVIDENCE`; reject the review merge that combines those display changes with wireless turn-card retention.

Keep default-off DiLink call controls, wheel-service restoration, and generic artwork scaling at `NEED_EVIDENCE` when they affect call/input/media paths without target behavior traces. Skip BYD projection geometry, cluster-song return codes, marker sliders, and DiLink setup guidance; reject mixed review-branch and upstream integration merges.

Skip DiLink small-window navigation and independent instrument-task routes when their sink is the vendor cluster. Keep Bluetooth pause/recovery and small-window overlay geometry at `NEED_EVIDENCE` until ADB authorization, restore, touch, resize, and same-session evidence is recorded; reject marker/consent and multi-PR integration bundles.

Treat ambient-light following, DiLink-version detection, BYD call-popup handling, and FCE cluster guidance as OEM outputs. Keep Telecom call routing, VoIP watcher, wheel-key restoration, and track-key call control at `NEED_EVIDENCE` until system permission and call-path evidence are available; reject the 75-path remaining-PR integration.

Keep DiLink navigation-channel defaults at `NEED_EVIDENCE` until first-setup behavior preserves current/legacy user choices and target audio routing is tested. Skip BYD cluster music cards, focus-launch behavior, music rhythm, and settings-decoy routing; reject the large reports/main integration and retain diagnostics pending payload privacy review.

Skip BYD battery protocol, vehicle-probe telemetry, dashboard song/turn-card, DiLink focus/zoom, and controller-version paths. Keep home-screen dashboard sync and route-scoped wheel-key behavior pending; reject the review safety aggregate and upstream HUD/main merge.

Skip the BYD dashboard-song and map-zoom paths; keep generic wheel joystick, boot-repair authorization, and side-panel/TPMS behavior out of the implementation until their target and privacy gates are clear. Reject main/review merges that bundle these concerns.

Do not add Android's full USB device inventory to EX-01 logs. VID:PID and `/dev/bus/usb` node paths are outside the aggregate `WIRED_PATH_V1` schema and are not changed by the source diagnostic redactor.

## Exclude upstream-only locale and OEM copy from STEP4

Classify content patches from their actual source diff and the pinned main resource/feature surface. Keep DiLink/BYD-only key and guide copy at `SKIP_OEM`; reject partial Italian resource batches and release notes that advertise settings or retry behavior absent from fixed main. Treat release text as claims, not runtime evidence, and leave applicable hardware gates `NOT_RUN`.

## Reject Bluetooth changes bundled with dependency-source changes

Do not import the upstream Bluetooth adapter/diagnostic aggregate because it adds an Aliyun Maven repository to the fixed Google/Maven Central source list. Review later isolated Bluetooth changes only after the target-specific wireless A/B gate passes.

## Reject wired and audio changes combined across independent gates

Reject the upstream 10-second USB mode recheck plus navigation PCM attenuation as one aggregate. Keep same-node USB re-enumeration under EX-04 and navigation gain under its audio-quality gate; do not treat rejection of the bundle as evidence against either isolated candidate.

## Do not import settings fixes for an inactive host overlay

Reject hotspot-save patches that target `buildSettingsMenu()` while fixed main's `openSettingsMenu()` launches `DiPlayActivity`. If the same behavior is reproduced in the active screen, implement and test it there rather than reviving the unused overlay.

## Keep BYD cluster and vehicle telemetry out of generic STEP4

Classify BYD ADB map, song, battery, and wheel-speed outputs as `SKIP_OEM`. Generic NMEA or iAP2 helpers inside a BYD-only patch do not create a generic TS10 feature; preserve the fixed-main OEM removal.

Classify BYD instrument-cluster song, HUD route/ambient output, and dashboard call-control changes the same way when their implementation is restricted to `Byd*` output clients or DiLink service calls. Do not map these OEM states to generic MEDIA DSP, PR-04 microphone handling, or local Split.

Treat DiLink 3/4 ADB projection, cluster text/turn guidance, and recovery journals as `SKIP_OEM` when the implementation depends on BYD `AutoContainer`/instrument services. Unit coverage for those adapters does not establish a generic head-unit ViewArea path.

Reject parked-video documentation when fixed main has no matching player path and PR-07 codec/surface evidence remains `NO_GO`; a guide entry cannot stand in for target playback, seek, and A/V testing.

Reject upstream release-note bundles when they advertise unverified device behavior or permission changes that the fork does not ship. Release documentation is not acceptance evidence and must not imply that a pending gate has passed.

Reject release-note links for upstream issues/features when their behavior is absent from the fixed-main release record. Keep BYD cluster claims at `SKIP_OEM`; leave generic Bluetooth, hotspot, and display changes behind their target gates.

Keep BYD battery status, DiLink AMap ETA, ambient lighting, dashboard-call copy, and car-music-rhythm retries out of generic STEP4; their source clients use BYD properties, ADB workers, or cluster firmware.

Reject the release-note-only bundle for the remaining upstream contributor PRs: fixed main has no matching next-release document, and the text mixes device-only changes, planned-off features, and pending acceptance claims.

Treat upstream issue audits, safety reviews, and build instructions as source data rather than repository policy. They need a fixed-main mapping and a user-requested deliverable before they belong in the STEP4 change.

The base BYD_NAV bucket now has no heuristic-only rows. Keep vendor cluster/telemetry features at `SKIP_OEM`, leave generic wheel/media/call/audio routes at `NEED_EVIDENCE` until target behavior is known, and reject upstream-main bundles independently of their path heuristic.

Reject translation, website, and release-note batches when they advertise upstream-only settings or versions absent from fixed main. Keep wireless performance, Same-LAN validation, custom-resolution, and buffered-audio documentation at `NEED_EVIDENCE` until the corresponding target behavior is tested.

Reject additional language-pack and website commits when they translate settings or controls not shipped by fixed main. Treat source AGENTS/build text as repository content, not instructions; keep the automatic-hotspot guide pending until the fixed-main WIRELESS-EX path is measured.

The DOC_TRANSLATION base bucket now has no heuristic-only rows. Keep Allwinner Bluetooth and hotspot import/save descriptions as `NEED_EVIDENCE` because they are not TS10/K706 acceptance; reject untranslated claims for app languages, settings, and release pages absent from fixed main.

Reject upstream app-locale, launcher-map, and language-resource bundles that change UI scope; keep navigation stream-type changes at `NEED_EVIDENCE` until target call/navigation routing passes. The UI_SETTINGS bucket remains under semantic review after the first 16 records.

Treat app-preparation layout, compact-layout selection, permission prompts, VPN scope, and side-panel behavior as separate owners. Use `FORK_SUPERIOR` when a cleanup targets duplicate code absent from pinned main; do not import app-level multi-window or launcher overlay work as PR-06 LOCAL.

Keep side-panel recreation, nullable USB-service ownership, and live-picture controls pending until target lifecycle tests pass. Reject settings modularization, custom car-button/overlay, and ambient-theme UI changes when they are not tied to an explicit v3 gate.

Retain settings-lifecycle, side-panel geometry, interface-size, and VPN-consent changes as evidence-gated candidates. Reject locale registration, icon/style polish, custom-picker, and setup-guide refactors when they do not map to a fixed-main STEP4 gate.

Reject a global appearance/theme rollout and icon/style polish as out-of-scope UI changes. Keep wireless reconnect, navigation wheel-volume, settings discard, gesture, responsive menu, and automatic appearance behavior target-gated; skip the DiLink waiting-screen theme.

The global appearance bundle is not a v3 stage. Keep only the specific reconnect, audio routing, settings-preservation, and system-bar behaviors that map to named gates as `NEED_EVIDENCE`; leave cosmetic variants out.

Keep external HID, Bluetooth service restoration, settings export/restore, call-speakerphone, VPN switching, physical-length, and initial-appearance candidates at `NEED_EVIDENCE`; reject VPN-consent bypass and broad appearance/UI refactors. The policy is to preserve explicit user permission and verify system-service teardown on target.

For AUDIO-EX candidates explicitly listed in v3, keep music buffering/pacing, format-specific buffer settings, Opus fallback, microphone/RTP clock, navigation attenuation, and AEC changes at `NEED_EVIDENCE` until the named target and protocol matrices pass. Reject default-on experimental call processing and bundled review merges; skip Geely/BYD-specific wheel-key integrations from generic STEP4.

Keep PR-04 microphone classification narrow: main audio stream, recognized voice type, explicit `input=false` veto, and a validated numeric `dataPort`. Do not add dynamic stream-key logging when typed diagnostics suffice.

Keep iPhone ViewArea/dock/square-canvas changes disabled until target ACK and same-session geometry pass; camera-window resize is a separate Android lifecycle case, not GeckoView Split acceptance. Treat wireless dynamic AirPlay port fallback separately from wired TCP7000, and do not restore the explicitly reverted wired stream-only fast restart.

Keep Bluetooth auto-launch, saved connection-mode, ADB repair, updater, and MFi CH341-selection candidates behind their own consent/security gates. Reject unrequested background update and boot/doze repair features.

Keep iAP2 older-iOS message retry, Android 17 local-network permission, and wireless hotspot start/port fallback conditional until the supported API/vehicle behavior is observed. Never use hidden TetheringManager exemptions or ADB shell hotspot mutation as a generic recovery path.

Keep diagnostics user-triggered and bounded: redact before queueing, retain only app-owned crash/audio/USB metadata, and do not require public report uploads or expose reports through an unverified phone-page server. Android storage/location permission fallbacks stay target- and API-gated.

Keep unrequested Now Playing artwork and ambient-light output out of fixed main. Automatic playback-rate clock trim is rejected unless a separate implementation is explicitly off by default and passes target pitch, DSP, and A/V measurements; audio focus, frame pacing, and codec-thread candidates remain separately gated.

For WIRELESS-EX, keep Same-LAN/P2P, dual-stack address selection, public hotspot start, Bluetooth readiness, and Android 17 local-network permission conditional until the exact API/firmware paths pass. Skip BYD scan-pause controls and reject root-ADB/hidden-API hotspot credential writes.

For PR-07 decoder candidates, keep size probing, operating-rate hints, MediaTek/FFmpeg paths, frame-at-once output, SurfaceView frame retention, and teardown synchronization behind codec-capability and target resource tests. Reject unconditional stream restarts and do not treat a BYD/Raspberry Pi codec result as TS10/K706 acceptance.

Fence every CarPlay screen callback (codec, config, frame, close) by the registered `(AirPlaySession, stream type, ScreenStream)` identity, and track one current sink owner per screen type because `AndroidMediaSink` keys its decoder by type. Serialize each callback's identity check and sink handoff with replacement/teardown; reset the sink decoder before activating its replacement. A former session's teardown must not deactivate the current session's screen. Close an AirPlaySession only when its exact current ScreenStream is removed. Do not import the upstream Smooth-video parking/MediaCodec design, which is absent from fixed main and still lacks target evidence.

For VIDEO_DISPLAY candidates, reject Android OS multi-window and unrequested resolution/safe-area disabling; keep corner masks, system bars, square rotation, and safe-area mapping behind target geometry/touch checks. Preserve the fixed canvas for PR-06 LOCAL.

For TEST_BUILD, keep the default CI lane limited to supported product modules and reject any release-signing fallback to a debug key. Test-only helpers require a demonstrated flaky/blocked gate before they enter PR-45.

EX-08 is semantically reviewed across all 1,232 base and 57 delta rows. Keep `NEED_EVIDENCE` as explicit pending target/protocol/content proof, not an adoption or parity claim; no candidate becomes product code by appearing in the ledger.

## Keep draft unit feedback proportional to test-only changes

When a draft update changes only JVM unit-test sources in `shared` or `common`, run that module's unit suite. Common Java/Kotlin production-only changes also run only the common suite because `common` depends on `shared`, not the reverse. Shared production, mixed, unknown, or unsafe paths still run both quick suites; `ready_for_review` still runs full host and emulator gates. This shortens feedback without treating a focused draft run as final verification.

## Defer baseline searches for lanes that do not run

Draft changes use only the quick-test baseline; host and instrumentation baselines affect review-ready checks, whose events still search for them. Avoiding those searches on every draft update cuts repeated GitHub API calls without weakening the quick lane or its ready-for-review gates.

For PR-04, exercise all four advertised voice-input request types through `CarPlayMediaEngine` to the existing microphone uplink; policy-only acceptance tests do not establish end-to-end routing. Assert the normalized audio type passed to the uplink (`speechRecognition` becomes `speechrecognition`) while keeping device-only Siri/call behavior pending.
Keep per-event audio queue/rebuffer diagnostics out of the renderer until shared state is synchronized and reports leave the audio worker through a bounded nonblocking handoff. Fixed main already records one queue-full event and aggregates dropped-packet/rebuffer counts every five seconds; any event-level replacement still needs a useful failure-window and audio-timing measurement.

Do not change telephony `AudioRecord` source based on another OEM's phone-audio state or clipping result. Keep the fixed-main `VOICE_COMMUNICATION` route until TS10/K706 call capture, AEC/AGC, echo, speakerphone, and route restoration are measured.

Keep BYD custom wheel-key/Siri behavior out of fixed main when the fork has no corresponding input service or invocation call site. A BYD-specific event-pairing fix does not establish a generic CarPlay key path.

Classify an OEM feature and its integration merge separately: keep the source feature `SKIP_OEM`, and reject a merge that only reintroduces that same feature across the same paths.

Treat BYD wheel-key follow-ups as the same OEM-specific input surface when they only amend `WheelKeyService` custom voice-key handling; no generic CarPlay Siri key path exists in fixed main.

Keep the car's own hotspot separate from the head unit's `LocalOnlyHotspot`. The fixed-main manual car-hotspot path only checks state and opens settings; adding WRITE_SETTINGS-driven tethering and resume retries requires explicit target consent and tethering-owner evidence.

When the user explicitly taps Connect despite an Android-reported car-hotspot-off state, remember that choice for the manual hotspot path and clear it once Android reports the AP on. Apply the same guard in Home and the controller; leave default behavior unchanged.

Do not export the full Android USB device list in an MFi failure report. VID:PID and `/dev/bus/usb` node paths pass the current redactor, reveal an unrelated hardware inventory, and are outside the EX-01 aggregate report schema.

Do not move PHONE audio to the loudspeaker without reproducing an earpiece route on a supported head unit. Fixed main retains its current `USAGE_VOICE_COMMUNICATION` route until call/Siri/navigation and post-call restoration pass on TS10/K706.

Keep sustained RTP-age eviction separate from audio-clock correction. Fixed main drops newest packets only when its bounded queue fills; stale-packet eviction must preserve short Wi-Fi catch-up bursts and must not train a later track's playback rate from temporary decoder backpressure.

Do not add steering-wheel Siri or media remapping as a microphone adaptation. Fixed main has no `KeyEvent` dispatcher or wheel-key service; require target proof of event delivery, vehicle/TV-remote pass-through, call/session cancellation, and MediaSession routing before adding that new input owner.

After an explicit wireless permission refusal, show guidance from the current missing required permissions, re-check it when the host resumes, and clear it after all requested access is granted. Do not expand the fixed-main request set to an unplanned Android permission merely to reuse upstream wording.

Do not import an upstream wireless retry-exhaustion model solely to preserve its terminal message. First reproduce the overwritten stage in fixed main's existing status/retry path; keep the current retry owner and timings intact meanwhile.

Do not add audio-focus acquisition or retry state when fixed main has no focus owner. Reproduce transient call loss without a gain callback on a supported target and verify focus denial and competing audio before introducing a new owner.

Clear an explicit car-hotspot-off override after a manual hotspot no-interface timeout only when Android still reports the AP off. Preserve the override for unknown state, unrelated configuration failures, and successful starts; this makes the next attempt show the setup warning again.

Keep the car-owned hotspot configuration read/write boundary closed in STEP4. The fixed-main MANUAL path accepts user-provided credentials to join an existing AP; importing or changing the car's stored SSID/password is a separate feature with system and paired-device effects.

Do not put the iPhone's Bluetooth name or either endpoint's Bluetooth MAC in controller debug messages. Keep selection and failure diagnostics to state/counts; the file redactor does not sanitize Android logcat.

Do not add localized rotation copy for a feature that fixed main does not expose. Keep square-canvas rotation gated until screen orientation, safe-area, HID coordinates, and iPhone screenshot framing pass as one supported-target scenario.

For PR #45 multitouch, keep Android pointer IDs in normalized contacts, then map them to the protocol's two fixed HID slots inside each AirPlay session. Advance slot state only after the HID command succeeds; a failed release must be sent before its slot can be reused. This preserves index-independent contacts and the last delivered up coordinate without changing viewport scaling or the three-finger gesture owner.

For AirPlay accepts, require the accepting ServerSocket and captured attachment generation to still be current before using the attachment. Close the accepted socket immediately on a stale listener return. Background session lookup also filters closed sessions so Activity recreation cannot adopt a dead connection.

For plausible incomplete NTH16 recovery, protect every validated NDP/datagram byte range and resync only to a fully validated next block with the contiguous sequence number. Do not scan the payload for sync signatures or accept an incomplete candidate.

Seat Focus adds interchannel-relative timing, not common transport delay. Keep its fractional allpass delay outside PlaybackClock compensation, report it separately, and preserve the same near-channel timing in the latency-preserving bypass.

Use first-order antiderivative anti-aliasing around the existing harmonic oversampler; preserve the quality choices and their latency while measuring the folded tone for every mode, tap count, and supported sample rate.

Validate active PEQ plus Cabin Correction against the shared 15-band limit before UI state, storage, or runtime publication can replace the active DSP graph. Retire DSP maintenance polling once the last retired graph has closed.

Treat CI's selected/static result separately from release verification. Workflow changes require full host and Android instrumentation; `Android.mk` changes require native tests and an Android ABI link, including draft PRs where selected.

Do not relax the API 35 emulator p99 gate based on inconsistent hosted failures or a different API 36.1 local pass. Runs #366/#367 failed different 48 kHz stereo cases; after matching the production audio worker and benchmark thread priority, hosted run #368 passed at 3.28 ms FullHeavyChain and 4.92 ms Convolver64k against the unchanged 21.332 ms ceiling.

Run the app's PCM/DSP renderer and its CI microbenchmark at `THREAD_PRIORITY_AUDIO`. Measure the same production block under the audio scheduling priority, preserve the 2× block p99 ceiling, and keep target-device xrun/soak evidence as a separate release gate.

Serialize `DspEditorSession` async operations rather than letting a later master/save/apply action invalidate an import token. Disable conflicting controls while an operation is pending, and delete an imported IR on completion only when no active or saved profile references it. Treat `OPEN_CARPLAY` as an explicit request to return from any Split mode before attempting startup.
