# Lessons

- A test that constructs a new runtime in the same JVM can verify persistence restoration and preview discard, but it does not prove Android process-death or Activity lifecycle behavior. Keep those acceptance rows partial until an OS-level test exists.

- Compact navigation can expose five destinations in a narrow Split pane with a horizontally scrollable tab row; Compose UI tests should call `performScrollTo()` before clicking off-screen tabs. Compile Android-test Kotlin separately from connected instrumentation, since compilation alone is not interaction evidence.

For graph-level detector tests, vary upstream processing while feeding identical PCM, then compare detector state and audio output separately. The detector should remain stable even when the processed output changes.

## Topic guide

These cumulative lessons include general rules followed by individual incident findings. Check applicability to the current code.

- For DSP Studio persistence, share the existing v7 profile codec for the embedded base snapshot instead of maintaining a second serializer. Keep editable preview state in memory until an explicit workspace save succeeds; preset application should map only the owning module's fields.

- **USB / NCM:** [readiness proof](#require-bidirectional-proof-before-ncm-readiness), [request timeout](#treat-usb-request-wait-timeouts-as-pending-operations), [NDP](#separate-ndp-accounting-from-startup-retries).
- **DSP / audio:** [DSP eligibility](#do-not-infer-dsp-eligibility-from-android-usage), [buffer validation](#validate-audio-buffers-with-frames-channels-and-encoding-together), [live DSP](#exercise-live-dsp-through-the-production-renderer-boundary).
- **AirPlay / Split:** [IME resize](#keep-ime-resize-separate-from-negotiated-carplay-size), [phone identity](#revalidate-profile-identity-across-a-carplay-controller-restart).
- **CI / release security:** [JNI symbol retention](#verify-jni-symbol-retention-from-the-optimized-mapping), [MFi identity](#bundle-local-mfi-identity-only-through-an-explicit-input), [APK signature](#verify-the-apk-signature-schemes-explicitly).

## General CI / review lessons

For faster pull request feedback, keep public-tree and generated-ledger checks on every update, run a small native/unit-test lane on draft code, and compare it against a dedicated quick baseline. Keep lint, package builds, JNI verification, and emulator instrumentation in the full review-ready lane. Separate quick, host, and instrumentation baselines so skipped work never counts as a full pass; default unknown paths, missing baselines, and non-PR events to full verification.

For long-lived draft PRs, run unit tests and host native checks on each code update, then defer the expensive lint/build/JNI/emulator matrix until `ready_for_review`. Explicitly include that pull-request activity type when restricting event types, and cancel superseded runs so stale commits do not hold the latest feedback behind them.

Validate transport-specific settings only for the active transport. A wired runtime can carry saved wireless preferences, but must not require unused hotspot credentials; retain the wireless validation and test both configurations.

When a repository tool only updates a reviewed inventory and its dedicated validator runs on every PR, an exact path allowlist can keep those review iterations off the Android build path. Do not generalize that exemption to other `scripts/` changes.

For generated audit files, make CI compare the checked-in output with the rows rebuilt from review overrides; source-catalog validation alone does not catch a stale generated ledger.

When using `gh api` query parameters with `-f`, pass `--method GET` explicitly; otherwise the CLI switches to POST. Keep this explicit for read-only pagination and filter requests so an HTTP 404 cannot silently route every PR through expensive fallback checks.

The Gradle setup action defaults non-default branches to read-only caching. Inspect its run summary; when repeated same-PR builds need task outputs, allow writes only to that PR's merge-ref cache and keep fork PRs read-only.

When measuring a CI scheduling change, compare runs with similar task-cache hit counts. A warm-cache pass can make a build much faster while obscuring whether parallel scheduling helped.

For optional physical-control patches, verify the fixed-main key-event owner and every call site before assessing only the mapping logic. Unit tests for key codes cannot prove OEM delivery, vehicle pass-through, or coexistence with TV remotes and Android MediaSession.

Permission-refusal text should derive from the exact required-permission set for the active transport and refresh after the user returns from Settings. Test partial grants and the transition back to a neutral stage; do not infer a new runtime permission from copy-only guidance.

An upstream retry-state type is not evidence that fixed main needs the same state machine. Reproduce a terminal-stage overwrite on the active baseline before importing retry flags, budgets, or typed failure owners.

## Parallelize slow emulator checks in CI

When connected Android tests run after unit tests, lint, and app builds in the same job, their setup and execution extend the PR's critical path. Run the emulator job independently, keep SDK image installation with that job, and use unique artifact names; compare whole-workflow elapsed time from Action logs to verify the gain.

## Keep recovery crossfades on the delayed-dry timeline

When a DSP path with nonzero latency fails, use the latency-preserving bypass's delayed Float32 output as the old side of a recovery fade. Check the renderer's production clock and PCM writes against the same track-generation latency source; a separate test-only latency field can hide the mismatch.

## Defer graph rebuilds when processor history cannot migrate

Latency equality alone does not make a replacement graph safe. Do not discard Convolver FIR history or long dynamics envelopes during an active stream; keep the prepared update pending for a fresh AudioTrack generation and test that it is adopted before that generation accepts PCM.

## Exercise live DSP through the production renderer boundary

An internal `AudioTrack.write` adapter allows unit tests to submit LPCM RTP and decoded codec PCM through `AudioRenderer` without a device track. Use latches for worker preparation and format-change races, and verify the first accepted source-sample anchor, generation adoption, fail-open output, and track-generation reset.

## Measure the cold large-block call without test-framework work inside the counter

Allocate the source and prepared graph first, start allocation counting immediately before the first 1,537-frame stereo call, and read the counter before making any test assertions. Keep the full supported PCM16 output and float scratch preallocated, and use an explicit Float copy loop in the identity processor so the measured path avoids bulk-buffer helpers.

## Crossfade canonical floats before PCM16 conversion

Convolver and DSP graph swaps should blend the two pipeline Float32 outputs, then dither and quantize the mixed signal once. Converting each candidate to PCM16 and blending bytes gives an unnecessarily quantized transition.

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

## Do not infer DSP eligibility from Android usage

Navigation may intentionally use the media route. Keep stream semantics explicit in one classification snapshot and base full-DSP eligibility on that role rather than on the selected Android route.

## Validate audio buffers with frames, channels and encoding together

Compute complete frames from the source encoding before decoding, then require exactly `frames × channels × sizeof(float)` at the processor boundary. Drop and count incomplete trailing frames, keep conversion scratch buffers across chunks, and retain independent dither state so decoder chunk splits do not change output.

## Pass direct-buffer positions into JNI explicitly

JNI direct-buffer addresses point at the allocation base. Send position and remaining-byte counts with the block, validate both capacities and reject overlapping input/output ranges before calling the core, then advance Kotlin buffer positions only after native success.

For combined filter headroom, multiply each section's magnitude at each shared scan frequency before selecting the peak; summing independent band boosts overstates filters at different frequencies. A notch can produce exact zero at a scan point, which is valid and must not invalidate the config. RBJ shelves use fixed S=1 and ignore Q in coefficient calculation.

For a sample-peak limiter with release, recovery may advance only while the stereo-linked frame stays under the ceiling. If release advances on a repeated over-threshold DC sample, it can raise gain between identical samples and exceed the advertised threshold; clamp gain to threshold/peak on every over-threshold frame.

Validate profile numbers and enum names while decoding, then construct the same validated runtime config used by DSP preparation. Check the stored schema before `AtomicFile.startWrite`; a generic parse fallback must not turn an unsupported future profile into a file the older app overwrites.

M/S width must bypass mono buffers rather than synthesizing a second channel. For stereo, use a single linked transform; apply mono-bass HPF to the Side signal, not to Left and Right independently.

For a 3-band LR4 splitter, cascade identical Butterworth sections in each Low, Mid, and High branch and keep independent state for both channels. Measure recombination through steady sine response and impulse energy; chunk comparisons catch state/order mistakes. Cap the highest crossover at 3.5 kHz so the configuration remains below 0.45 Nyquist even at the minimum supported 8 kHz rate.

Every compressor band should reuse the established stereo-linked detector and reset path. Use the largest enabled positive band makeup for conservative static headroom, and increment the persisted profile schema when adding a field so earlier versions cannot silently erase it.

For Dynamic EQ, process a band-pass detector and fixed-gain peaking path continuously, but change only a bounded dry/filter blend every 16 frames. This keeps detector/filter state continuous, avoids unstable coefficient interpolation, and makes arbitrary audio chunk splits deterministic when the control countdown is part of band state.

For live DSP changes, capture one immutable generation, prepare the full pipeline away from audio processing, and let the renderer worker adopt only the newest compatible candidate. Crossfade the two PCM16 outputs through one existing `writePcm` call; hold latency-changing graphs for the next renderer and close retired native state only after the fade completes.

## Keep audio setup preparation separate from ownership commit

Bind both UDP sockets with no receive workers first, then atomically publish the new generation and only start its workers while it is still current. Keep the previous activated owner available until that start succeeds, and make closed-session commit plus teardown owner reads linearize under the per-type slot lock. Exercise A→B→C and prepare/close interleavings with latches; token checks at the sink and map-removal boundary catch races that an engine-only owner check misses.

## Keep microphone Opus frames in the negotiated sample domain

For 20 ms Opus uplink frames, derive samples and PCM bytes from the negotiated sample rate: 16 kHz is 320 samples/640 mono S16 bytes, 24 kHz is 480/960, and 48 kHz is 960/1920. Configure MediaCodec with that same rate and advance RTP timestamps by the same sample count; 960 samples is not a rate-independent frame size.

## Propagate renderer and microphone start outcomes

If a sink swallows a synchronous renderer-start failure, the engine can mark an absent renderer as started and drop every later RTP packet. Return success only after the worker has started; likewise mark microphone state started only after its uplink is active, and test that the next RTP retries after a failed renderer start.

## Keep microphone recovery independent from downlink startup

After the downlink renderer succeeds, microphone startup still needs its own retry path. Rate-limit retries and liveness checks with a monotonic deadline, and replace an inactive same-token uplink so a capture thread that exits cannot leave a permanent dead entry.

## Isolate Windows AtomicFile rename behavior in profile migration tests

When Robolectric runs on Windows, Android `AtomicFile.finishWrite()` relies on `File.renameTo()` replacing an existing base file, which Windows does not guarantee. Keep production persistence on `AtomicFile`; inject a deterministic writer only into schema-migration tests that need to verify replacement contents, and inspect the failing assertion before changing migration defaults.
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

## Separate NDP accounting from startup retries

For diagnostic classification, resolve ICMPv6 through bounded extension-header parsing and distinguish RS/RA/NS/NA (133–136). Keep ambiguous Bulk OUT retries limited to NS/NA (135/136); packet typing and counters must not silently change retry behavior.

## Bundle local MFi identity only through an explicit input

Use the external `DIPLAY_AUTH_ASSETS_DIR` input for a local-only debug build, and gate packaging with an exact two-file allowlist. Generated APKs may contain those user-requested assets; keep them out of source, commits, and logs.

If the input arrives inside an APK, verify that each allowlisted ZIP entry occurs exactly once before extracting it. After packaging, verify the asset entries and v1/v2 signatures in every ABI APK, then remove the temporary extracted copies.

## Verify the APK signature schemes explicitly

Use `apksigner verify --verbose --min-sdk-version 23` when confirming v1 compatibility on an APK whose manifest minSdk is 28 or higher; the default report can show v1 as not applicable while v2 remains valid. Check the exact final APK file rather than assuming Gradle's successful package task alone resolved an installer error.

## Keep NCM evidence distinct from readiness state

The NCM readiness phase can be advanced by an AirPlay control milestone, so `NCM_LINK_READY` alone does not prove bidirectional NCM traffic. Derive the NCM result from explicit RX/TX proof and log AirPlay acceptance and screen opening separately. Put summary fields before descriptor details because diagnostic export truncates long lines.

## Treat CDC status notifications as observations, not startup gates

The 2026-10-09 trace recorded both `NETWORK_CONNECTION` connected and disconnected values while successful screen startup also occurred during intervals with no new status packet. Poll/drain the endpoint when present, but do not wait for a connected notification before NCM or AirPlay startup. In the same report, request queue/wait errors coincided with physical USB disconnects; only add request-level recovery if the error reproduces while the device remains present.

When Gecko reports paint-status reset during `setActive(false)`, retain it through profile-resolution timeout and warm reopen. Associate it with a suspension generation instead of clearing a boolean on measurement end; a later suspension must observe its own reset before primary paint is accepted.

For NCM integration tests, pure `Ntb16Codec` tests do not exercise `NcmUsbBridge.recv()` buffering or `requestWait` ownership. Use test-only Robolectric shadows for the real connection/endpoint/request boundary and a scripted completion dispatcher; label known-defect characterization so its green result is not mistaken for functional acceptance.

For packet diagnostics, have a pure parser return a packed classification value and keep address/port validation states distinct from “not checked.” Exercise raw-IP and Ethernet entry points with boundary cases plus a fixed-seed arbitrary-buffer loop; use a recorder test for concurrent increments, generation fencing, saturation and sealing. Keep TUN I/O outcome tests on the real bridge with injected streams so Robolectric socket descriptors do not determine the test result.

For NCM receive framing, resolve optional short-pad length from the actual Bulk IN endpoint packet size, deliver a complete block without waiting for a pad byte, and preserve the next NTH16 signature. Validate candidate NTH/NDP structures before resync and cap discarded garbage so corrupt input cannot trigger an unbounded scan.

For delayed Host recovery, track restart, connection, background-owner, and loss epochs separately. A recovered session consumes only its matching pending ticket; a later loss gets a new ticket, while ownership transfer, active sessions, resize/menu, shutdown, and controller-managed recovery fence old work.

For AirPlay microphone setup, distinguish advertised input capability from an actual MainAudio capture request. Treat explicit `input=false` as authoritative, validate the peer port without narrowing overflow, and keep capture mono to match the advertised input formats; leave normal downlink setup intact when capture is declined.

For local CarPlay pane resizing, resize the view that owns the SurfaceTexture and its gesture overlay together while preserving the remote aspect ratio. Child-local touch coordinates then match the rendered area, letterbox taps never enter CarPlay, and the background Controller keeps its original negotiated dimensions.

For multi-area `/info` changes, keep the legacy single-area serializer as the default and model each area's view/safe geometry against the full display coordinate space. Scale every area's insets with the display canvas, preserve display UUID/physical dimensions, and keep dynamic switch commands separate from declaration support.

For DSP graph extensions, keep the disabled MEDIA path as a byte-for-byte PCM16 bypass with zero added latency. Prepare scratch buffers, delay lines, filters and interpolation tables before audio processing; the render thread should only operate on preallocated state.

For a stereo seat-alignment delay, the selected near channel receives the distance-derived delay while the far channel remains at zero; report the maximum relative delay for the playback clock and sum it with downstream graph latency. Defer replacing an active delay ring until a track-generation boundary so the new buffer does not start without history.

For USBMUX reads, a timed `requestWait` can leave the same `UsbRequest` pending. Reusing that owner for the next wait preserves a late completion without the cancel/drain race; keep cancellation in the close/failure path and test the lifecycle state separately from NCM.

For two-band M/S processing, use matched cascaded Butterworth LP4/HP4 filters so the bands recombine with flat magnitude. Apply the same crossover state to Mid and Side, treat Mid as all center-panned content, and include the maximum positive band gain plus width in Auto Headroom; skip the old Width/Mono Bass stage only while the new module is active.

For imported cabin correction, persist only a digest and measurement summary with the user-approved PEQ cuts, not the raw measurement file. Combine enabled cabin cuts with active PEQ bands before preparing the existing coefficient array, enforce the shared 15-band limit, and classify missing or poor-quality measurements as `CALIBRATION_UNAVAILABLE`.

For 2× harmonic processing, zero-stuff each input into a fixed even-sized phase ring, use matched normalized half-band FIRs around the waveshaper, and keep a separate preallocated state per channel/branch. The two linear-phase FIR group delays sum to `(tapCount - 1) / 2` input frames; use that same delay for the dry path and add it to the playback-clock latency.

For long STEP4 audits, group related upstream commits by subsystem and update their review overrides, generated CSV, and count summaries together. Keep unresolved physical or protocol requirements explicit instead of reopening the same implementation or CI loop for each inventory row.

For a multi-area upstream commit, review the actual patch and fixed-main gate separately from its individual component candidates. It can be rejected as an aggregate without treating a useful constituent patch as disproven; keep that constituent's own row `NEED_EVIDENCE` until its target-specific gate passes.

For privacy review, check both the formatter and redactor behavior. A test showing `redact(log) == log` confirms the device identifiers or paths remain in the emitted diagnostic; do not infer that calling the redactor makes those fields safe.

For EX-08 content rows, compare the archived patch with fixed-main locale registration, resource keys, and runtime support. A translation or release note does not prove the described feature; finalize clear scope mismatches while keeping generic device claims pending without target evidence.

For a fixed upstream range containing merges, classify every merge from its parent topology and first-parent patch, then link source-integration duplicates to source indexes. A complete SHA inventory is not a semantic review unless every row also records the fixed-main owner, concrete disposition reason, and the evidence needed for any pending decision.

When an upstream runtime patch also edits repository configuration, inspect dependency-source changes as part of the aggregate. A later commit that removes an unapproved mirror can support rejecting the earlier aggregate without claiming the separate runtime behavior is disproven.

For a commit that combines USB re-enumeration and audio PCM processing, classify the actual patch as a cross-gate aggregate. Preserve each narrow candidate's separate device, audio-quality, and rollback evidence requirements.

Before porting an upstream UI fix, verify that the modified view builder has a call site in fixed main. An upstream test for an unreachable activity panel is not evidence that the active settings screen needs the same fix.

When a BYD-only adapter contributes a generic helper, review the actual call chain before classifying the helper as independently portable. If the only producer/consumer is an OEM ADB or cluster path that fixed main removed, the aggregate remains `SKIP_OEM`.

For BYD cluster/HUD source rows, inspect the adapter's actual sink (ADB autoservice, SOME/IP HUD, or BYD instrument device). A generic state parser under a `Byd*` consumer does not establish support for generic CarPlay, MEDIA DSP, microphone, or local Split features.

For DiLink projection changes, identify the vendor command/service, display lifetime, and projection surface from the source patch. ADB success or UI tests do not transfer that OEM-specific display contract to the fork's generic Android pane or iPhone ViewArea path.

Documentation and test-only commits can receive a final `SKIP_OEM` decision when they solely describe or test a removed BYD integration; they do not need to remain heuristic-only once the product path and fixed-main exclusion are confirmed.

Classify user-facing video claims against fixed-main entry points and the PR-07 acceptance gate. If only upstream documentation changes, keep the unsupported playback claim out until the decoder/surface path is implemented and tested.

When release-note commits collect several upstream fixes, inspect every claim against fixed-main code and its target gate. A pending acceptance note or an upstream PR link does not establish that the fork implements the behavior.

Link-only edits to upstream release notes still change user-facing claims. Validate the referenced behavior and release scope before carrying the link into the fork; a source PR URL is not parity evidence.

For upstream release-note bundles, the changed path and linked PR list can cover many unrelated features; inspect the claims, then keep device, consent, and release gates separate rather than treating the notes as an implementation batch.

Source-repository safety reviews or build docs can contain imperative wording, but that does not make them instructions for the fork. Record only conclusions supported by the pinned tree and the user's requested scope.

For wireless source audits, preserve the target model, Android API, permission path, and radio behavior that produced the evidence. A successful test on another head unit or phone does not close a fixed-main TS10/K706 gate.

For a feature that changes transport, focus, routing, or persisted network state, inspect its default value as well as its opt-in control. A visible toggle does not make a default-on behavior a safe opt-in rollout.

For microphone requests, distinguish `audioType` classification from an actual capture request: check the main stream, explicit `input=false`, and a typed valid data port. Unit coverage does not establish voice-message, Siri, call, AEC, or RTP behavior on a supported head unit.

Keep Android camera-window resizing, same-session in-app Split, and iPhone ViewArea changes as separate test cases. A local Activity resize or simulator response does not prove the phone accepted multiple ViewAreas or that the GeckoView Split lifecycle is stable.

For startup recovery, distinguish saved ADB authorization rechecks from new grants, and distinguish the dynamic wireless AirPlay port from wired TCP7000. A recovery path that needs hidden-API exemptions or changes the wired listener must stay out until its own failure and consent case is reproduced.

For diagnostics, validate queue bounds and shutdown flush order as well as each field's sensitivity. A local phone-page endpoint or visible Downloads fallback changes who can reach a report, even when payloads and credentials are excluded.

For media-performance candidates, separate packet arrival pacing, decoder backlog, playback-clock trim, and Android audio-focus behavior. Synthetic drift results or another car's measurements do not establish target pitch, DSP, or A/V behavior.

For Wi-Fi candidates, separate mDNS address publication, hotspot readiness, Wi-Fi Direct channel choice, and Bluetooth handoff. A BYD-only scan-pause result or another head unit's hotspot setup does not establish TS10/K706 behavior.

For decoder fallbacks, distinguish codec-list size support from an actual configure/output success. Verify the selected format, Surface handoff, first frame, queue lifetime, and target CPU/A/V behavior before changing codec or thread policy.

A screen stream's reader thread can already be processing a message when the stream is replaced. Check ownership for each codec/config/frame/close callback, not only for recovery requests; a stale close must not close the AirPlaySession now owned by its replacement.

For video overlays that retain a last frame or mask corners, test Surface destruction/recreation and bitmap release separately from visible geometry. A screenshot or Robolectric layout does not prove that the target decoder releases frames without stale content or memory growth.

Treat artifact signing as a release invariant: if the configured release key is missing, failing the release build is safer than silently substituting a debug signer.

In multi-lane CI, discover only the baselines consumed by the active lane. Draft runs do not use host or emulator baselines, so scanning old commits for those checks adds API latency on every iteration; defer that search until the full lane can consume it.

Use the dependency direction when scoping draft tests: `common` depends on `shared`, so a `common`-only source change needs the common suite while a shared production change can affect both suites. Keep the full cross-module checks on review-ready runs.

For microphone input, verify accepted `audioType` values through the media-engine start callback as well as in the policy unit test; compare the normalized value delivered downstream, not the caller's original casing.

For AirPlay display geometry, validate relationships between rectangles as well as each rectangle's bounds. Safe-area insets must be at least the corresponding view-area insets on all four edges; a non-overlapping Split safe region cannot be serialized as valid.

For explicit encoded ViewAreas, keep every display edge even-aligned through Split rounding and resolution scaling; clamp the scaled safe rectangle back inside its rounded view rectangle. Keep the legacy implicit one-area `/info` path unchanged, and use wide arithmetic to reject inset overflow.

An identity check followed by a sink callback still has a replacement race between the check and delivery. Serialize the ownership check with callback handoff and stream replacement; use a blocked in-flight callback regression to verify the sink cannot receive old media after the new stream becomes current.

When a downstream media sink is keyed by stream type, per-session ownership alone is insufficient. Track the current session's stream for that sink type so a former session's late teardown cannot clear the replacement decoder.

For media handoff, test both the engine's ownership gate and the sink's decoder-state reset. A fake sink's inactive/active events alone do not prove the Android sink discarded its old decoder/config.

Separate fixed main's generic codec-capability-gated low-latency request from vendor-specific tuning proposals. Preserve the baseline path; require target capability and performance evidence before adding device overrides.

For lifecycle/layout regression tests, use the production view hierarchy when validating parent/child resize invariants. A replacement test frame cannot prove that the TextureView and touch layer actually share the host's fitted content bounds.
When auditing real-time logging, trace the callback through to its storage destination. A lightweight `report(...)` call can synchronously perform locked file I/O on the audio worker even when the logging site does not show that work.

Treat microphone-source measurements as OEM- and route-specific. A cleaner signal on one head unit does not establish that another unit's AEC, AGC, echo reference, speakerphone, or route restore behaves the same way.

When an OEM callback is missing, first establish that fixed main owns that callback path. A proposal that adds a new media-focus manager is a new subsystem and requires target evidence for its lifecycle and interactions.

Treat an explicit “connect anyway” choice as a one-attempt bypass when the requested network never appears. Reset it only for the typed no-interface failure and a still-off platform report; preserve it when platform state is unknown or the failure is unrelated.

Separate credentials for joining a running AP from provisioning the AP itself. Read-only link diagnostics do not authorize importing or changing system-owned SSID/password settings; those are a separate, consented feature and must preserve other connected devices.

Treat Android logcat as a separate diagnostic destination from the app's redacted session file. Omit Bluetooth device names and MAC addresses before calling `Log`, since the session redactor cannot protect the platform log buffer.

For conditional display rotation, validate the square canvas, safe-area transform, HID coordinates, and iPhone screenshot framing as one target scenario. A screenshot note alone does not establish that the control is supported.

When an upstream patch changes only a localized string, confirm that fixed main contains the referenced setting and feature before porting it; otherwise the text can claim behavior the app cannot perform.

For generated inventories that need historical Git files, pin and fetch the immutable commit SHA rather than a source branch that may later be deleted. Validate the fetch in a fresh shallow repository so a warm local object database cannot mask a broken CI fallback.

Android pointer indices are event-local and can change after `ACTION_POINTER_UP`; HID identity must come from `getPointerId()` and be mapped to a fixed slot at the session boundary. Make this mapping transactional around the HID send so a dropped up cannot free a still-held remote slot.

An accepted socket can outlive the listener attachment that returned it. A deterministic blocked-accept fixture should switch both generation and server identity before unblocking, then verify that the stale socket closes without a new-session callback.

When embedding an existing Compose editor in a persistent View host, keep its `ComposeView` attached and set it `GONE` while another pane is active so scroll and editor state survive mode switches. Give the view a stable resource ID for saved-state restoration, share the application editor session with the standalone Activity, and guard async UI results with both operation generation and draft revision.

For media ownership races, exercise the old callback after the new owner is installed and assert both the old session and current session receive no stale first-frame notification. For lock-order regressions, hold opposite MAIN/ALT lifecycle locks through a barrier and require both close paths to finish.

When a numeric DSP bug is reported, add an adversarial sweep around the actual fold-back frequencies instead of relying on one high-quality/tap configuration. For instrumentation log evidence, clear logcat before the run and assert every required format explicitly; line counts do not prove unique measurements.

When a runtime invariant can be exceeded through multiple UI editing orders, centralize one profile predicate and call it from edit, save/load, and apply paths. Reject before replacing the active pipeline so a bad draft cannot turn off unrelated DSP modules.

API 35 hosted emulator p99 failures can move between DSP benchmark cases across runs; record each case and its threshold, and keep the result separate from source-level correctness and API 36.1 emulator timing. A local pass on another API level does not clear the hosted gate.

When a real-time microbenchmark runs on an ordinary instrumentation thread, scheduler preemption can dominate wall-time p99. Compare per-run histograms and match the test thread's priority to the production audio worker before changing DSP math or relaxing its deadline.

When callbacks from two independently closing streams race, assert each stream's required open/close events and the no-deadlock condition without imposing a relative order on their concurrent close callbacks.

For imports that persist data before returning to the UI, retain operation ownership until the callback resolves; reject conflicting operations, then delete the imported object if the active screen did not adopt it and no saved profile references it.

For NCM stream recovery, a valid next-sequence header inside an incomplete NTB's unused gap is not a block boundary. Recover a length-corrupt block only from a validated payload end; reject frames outside the VPN MTU and require a consecutive-block pair when the datagram-length field is invalid.

Wired-path counters and first-event timestamps are separate atomics, so an unsealed snapshot can observe the count before its timestamp. Preserve that partial report with `first_ms=none`; a sealed snapshot can still enforce complete timestamps after waiting for writers.

Split `TextureView` measurements are local pane geometry. Configuration changes during Split should retain that local cause while the Surface is absent; only Full-mode physical resizes should read the host root and participate in remote display renegotiation.

An additive harmonic exciter should add the band-limited nonlinear component to a latency-matched dry signal. Subtracting the input identity inside the nonlinear branch silently turns the amount control into a dry/wet crossfade and removes high-frequency fundamentals. Include the DSP's internal level-match multiplier in the Auto Headroom peak bound.

Latency-preserving DSP fallback has two independent settings: track-generation common latency and the accepted graph's relative Seat delay. Prepare fallback state off the render worker, feed it during crossfade, and swap only when that graph generation becomes active. Send retired pipelines to a prestarted lock-free reaper instead of scheduling or locking on the audio worker.

DSP master OFF has two valid contracts: a fresh disabled track is byte-exact PCM16, while live OFF retains the old common latency for that track to protect A/V alignment. Compare it with the exact path only after the next track generation.

When converting a legacy DSP preset into a new filter topology, label it as a seeded starting configuration unless a frequency sweep proves transfer-function equivalence.

Configuration acceptance and renderer adoption are separate events: preserve the prepared graph and expose its generation until the output path activates it. A USB transfer terminator must use the negotiated endpoint packet size, not a bus-speed assumption; zero-length blocking audio reads need bounded backoff without inventing samples. Redact data at the Logcat sink itself because later UI/report filtering cannot retract system logs.

Test the incomplete-NCM boundary with a staged receive: deliver the first 200 bytes, require no frame or resync, then deliver the declared remainder and require only the original frame. For a damaged length with one valid following block, inject repeated USB timeouts and verify `recv()` returns promptly and close still releases the connection.

Keep incremental resync scan position across USB completions and instrument candidate checks. A bytewise 8 KiB fixture is a useful regression for quadratic rescanning; bounded memory alone does not bound CPU work.

When an Android Activity must remain externally launchable for system USB attach, move app commands to a non-exported Activity and check both the currently attached device identity and `UsbManager.hasPermission()` before acting on the exported entry point.

For privacy audits, search every app-owned direct `Log.*` sink for Throwable arguments, then verify the replacement retains useful error classes without retaining exception messages, stack traces, or media payload bytes.

When a USB header is structurally incomplete, do not treat two plausible sequence headers in its unused region as a boundary. If the declared maximum length makes the boundary ambiguous, a bounded explicit failure is safer than silently delivering payload bytes as separate Ethernet frames.

Test late audio-owner state after a successful microphone start, not only initial start failure. The `AudioRecord` worker can die independently while downlink RTP continues; retry should inspect the current token's active uplink and honor the same backoff as startup retries.

Test notification actions by reusing the same `PendingIntent`, not by creating a new token for every click. A foreground notification can outlive its process, so its safe open action must not depend on an in-memory token map.

For MediaCodec replacement, fence callbacks and serialize decoder ownership separately: a generation check prevents stale UI/keyframe callbacks, while a stop ACK after codec release prevents a replacement worker from becoming current too early. SurfaceTexture destruction remains a device-level lifetime test.

For local MFi APK packaging, point `DIPLAY_AUTH_ASSETS_DIR` at the parent of `offline-mfi`; build ABI splits with `:mobile:assembleDebug`, then check each APK for only the expected identity/certificate assets and verify Android signing with `apksigner`.

For SurfaceTexture teardown, an asynchronous `SurfaceChanged(null)` enqueue is not a detach acknowledgement. Keep the texture app-owned (`onSurfaceTextureDestroyed` returns false) until both screen decoder owners finish; preserve a blocked lease if native codec release reports failure.

When a native DSP process fails after its fallback state has advanced for the current block, apply only the missing Seat gain/polarity to that already-delayed fallback output, then apply it once per subsequent block. Precompute gains off the audio callback and keep master OFF on a separate dry path.

For lock-free worker retirement failures, decrement only the in-flight count in `finally` and keep a separate failure state; otherwise a failed native close can falsely reopen the pipeline-adoption gate. Test post-inspection getters and callback publication as well as the primary preparer exception.

When Compose scroll crashes with an infinite maximum height in a local split pane, a current-window configuration cap can provide a finite dynamic upper bound without hard-coded display dimensions. Verify the originating Android measure path and real pane rendering on target hardware before claiming the crash fixed.

For a connection-enabled APK build, verify both packaging layers independently: expected MFi asset entry names inside every ABI APK and the Android APK signature schemes. Successful packaging and signature checks do not prove a new physical head-unit connection.

For a valid but incomplete NCM header, sequence and block length are enough to start bounded no-progress tracking; a later incomplete NDP must not bypass the deadline. For protocol traces, persist a strict stage/byte schema rather than attempting to redact raw serialized headers or bodies.

When DSP candidates retire through a background reaper, observe completion with a latch or other explicit signal in tests. For fallback audio, headroom/limiting should activate only on the failed-output path so normal and low-level delayed fallback samples remain unchanged.

For Compose scroll exceptions, bound the scroll node's incoming constraints directly; a finite cap on its higher-level Scaffold may not be sufficient on OEM measure paths. Capture both Android View MeasureSpecs and the Compose body's incoming maxHeight, then verify the rendered flow on the affected device.

Do not retry `DspRuntimeConfig.prepare()` to calculate an audio-failure fallback: the invalid profile that caused preparation to fail can throw again, and the full path scans EQ response and copies convolution data. The failure path only needs a conservative scalar gain bound and its existing output limiter.

When coalescing queue control jobs, keep required resource-release callbacks in a separate FIFO and coalesce only the latest control state. This avoids quadratic list copying without treating an unacknowledged Surface as safe to release; a hard cap needs an explicit safe overflow policy.

For asynchronous DSP teardown, carry the configuration generation with the pipeline into the reaper failure callback. Report an old graph's release failure beside the active generation and blocked-update state so the UI does not conflate retirement with candidate preparation.

When tests share an executor with asynchronous retirement or preparation, do not infer completion from a callback that only schedules the work. Wait for an explicit completion latch, and assert only status states that remain valid when the worker advances before the test thread resumes.

Pipeline close callbacks precede the reaper's retirement-count decrement, so a close latch alone does not prove that another update is adoptable. Wait for the exposed adoption gate when that is the behavior under test.

For concurrent route ownership, test selection and publication together while one callback is held at a latch; final owner state alone does not catch an older callback publishing after a newer removal.

For persisted workspace identity, validate the external source ID against the decoded embedded profile ID at the repository boundary. Validation only during later edits allows invalid state to be applied at startup first.

When initial hardware state and an asynchronous change listener can race, serialize validation and publication and fetch the current hardware state inside that serialized section; gating only by owner generation does not order two callbacks from the same track.

For Compose measurement diagnostics, `ComposeView` is final and its `onMeasure()` cannot be overridden. Capture the exact specs at the existing pane's `measureChildWithMargins` call, pass those specs to `child.measure()`, and record the child result afterward; do not report a reconstructed probe value or a parent's pre-measure size.

For decoder retirement, a throwing `MediaCodec.release()` does not prove the old Surface is safe to reuse. Retain the old decoder/Surface owner and deny replacement until a later release retry succeeds; inject the release boundary with a callback that delegates directly to `codec.release()` by default so the ownership failure path is deterministic in Robolectric.

For repeated UI diagnostics, a string-based dedupe API still allocates the candidate string before it can reject a duplicate. Compare primitive signature fields first, then allocate the retained signature and formatted log only when the value is new.

For microphone zero-read coverage, inject a scripted initialized `AudioRecord` through the existing Builder shadow and exercise the real capture worker plus a loopback UDP receiver. Hold the recorder before its first positive frame so a timeout proves zero reads never synthesize or transmit silence; then assert exactly one packet follows the positive frame.

Keep log-volume limits separate from bounded recent-history storage: an evicted signature can otherwise be emitted again indefinitely. A process-wide emission counter provides a hard cap while retaining the rolling history for diagnostics.

CI evidence tests should assert the meaning of a historical status record rather than an exact phrase that later becomes stale. Keep the run SHA and skipped job scope explicit when updating those records.

For CI change classification, treat an empty or malformed compare-file list as incomplete evidence, not as an empty/docs-only diff. Exercise baseline lookup, file-count limits, unknown paths, Draft skips, and docs-after-source cases through the same helper used by the workflow.

2026-10-26: A successful docs-only workflow run validates only its static checks. Record the workflow HEAD and each job/step's `success`, `failure`, or `skipped` result separately from the last passing source-test SHA; also exercise representative production paths through the current classifier.

When a test latch runs inside a worker that is deliberately interrupted during shutdown, `await()` can throw before the test releases the gate. Make the test gate interruption-tolerant and rely on explicit cleanup to release it; allow enough time for a separately scheduled assertion thread to run in CI.

Smart Loudness route calibration should use two trusted readings from the same route in increasing volume order. Failed, untrusted, reversed, or cross-route samples must leave the previous calibration intact.

For playback volume monitoring, use the renderer's actual `AudioTrack.routedDevice` callback and reset route-bound samples when the route changes. A route label and Android MUSIC index—even when the index changes—do not prove that an OEM volume control follows it; keep AUTO unavailable until that correspondence is validated on supported hardware.

## Keep screen instrumentation isolated and classify execution precisely

Give standalone screen tests their own files directory and preference name, and assert draft edits do not change runtime configuration before Preview. Android-test Kotlin compilation proves source/build validity only; a test that cannot run on a connected device remains `BLOCKED_ENV`, and CarPlay host geometry/session/Surface acceptance remains `NOT_RUN` until measured there.

For Compose graph drag tests, compute touch points from the node's measured Canvas bounds and then inject in node-local coordinates; separately assert the emitted editor model so a successful pointer dispatch cannot mask a mapping error.
When a Compose drag can begin with a single movement beyond touch slop, retain the original down position for target selection and use post-slop positions only for editing; selecting from the detector's simple drag-start position may miss the handle.
For wired startup, state-helper tests do not prove callback routing: exercise the real AirPlay session listener with MAIN and ALT SETUP, then close the control socket before RECORD and assert the active attempt requests recovery. Also verify RECORD closure follows the normal active-session end path.

For semaphore-limited socket handlers, test both rejection at capacity and permit return after session close/detach; a cap-only test misses leaked permits that prevent later valid reconnects.

For Surface churn, gate new Surface creation before handing a wrapper to decoder workers. Include the current live Surface in the outstanding budget, keep one latest generation pending, and never release a Surface or Texture until its specific decoder ownership ACKs have completed.

For drag tests that cross touch slop in one movement, band hit-testing must use the original pointer-down coordinate; the detector's drag-start coordinate is only valid for the post-slop edit position.

If a benchmark threshold miss is rerun, keep the threshold fixed and record both the first failure and the same-revision retry outcome. Final CI evidence must name the exact head SHA.

For Compose A/B regression tests, assert the displayed side, renderer configuration, editor draft, and unrelated module values. Include deleting the selected preset while A/B is active so a UI fallback cannot leave the renderer playing a stale comparison snapshot. Seed an already-synchronized editor draft before composition when the test starts from preexisting runtime state.

For an upstream range with many merge commits, keep source and merge decisions separate. Use parent topology to label PR integration duplicates versus branch-sync merges, and make each pending source row name the exact evidence that would change its disposition. Keep the old ledger population frozen when adding a new overlay.

For wired startup timing, use one monotonic origin per attempt and exact successful full-size NCM transfers. Generation-fence callbacks, retain only bounded numeric milestones, and keep packet diagnostics independently opt-in; model tests do not replace CPU/heap measurements or device traces.

For a receive-path performance comparison, warm each mode, interleave the measurement order, and use per-thread CPU/allocation counters over enough iterations to rise above timer resolution. Record the host architecture/JDK and keep the result distinct from Android target-device profiling.

Treat performance instrumentation as a distribution gate: retain both passing and failing runs at the exact SHA, do not average away a p99 breach, and do not claim a Draft workflow passed jobs it skipped. On macOS, run Android tests directly when the helper's GNU `timeout` dependency is unavailable, then capture the required filtered logcat separately.

2026-10-27 B09: an API 35 Shared p99 failure can be followed by a full 19/19 pass on the same code/test head without source changes. Preserve both; a repeat establishes the current run result, not the cause or stability of the earlier outlier.

2026-10-27 B09: verify CI report artifacts by opening the uploaded ZIP and checking that expected JUnit XML files and suite counts are present; a passing job alone does not prove reports needed to diagnose future failures were retained. Run #490 confirmed 865 unit-test and 20 connected-instrumentation results were archived.

2026-10-27 B09: a median-of-three p99 gate can pass while individual runs exceed the ceiling. Preserve all per-run samples and report both the gate statistic and largest tail; do not treat a green instrumentation job as proof that every measured run stayed under budget.

2026-10-27 B09: when API 35 wall p99 exceeds budget, compare per-block `Debug.threadCpuTimeNanos()` p99 with wall p99. A lower thread CPU p99 indicates time spent outside the measuring thread, but it does not establish real-device playback safety or excuse a wall-deadline breach.

2026-10-27 B09: preserve benchmark results by ABI and exact run. A passing x86_64 CI median does not override a failing arm64 emulator wall-p99 assertion; thread-CPU measurements help characterize the gap but do not identify its cause.

2026-10-27: If API 35 p99 failures overlap a CPU-heavy R8 build from another worktree, record the overlap and rerun the same benchmark alone at a named source SHA. A passing isolated run describes that run only; it does not retroactively prove why the concurrent samples failed.

2026-10-27: When an internal DSP stage is folded into a shared filter chain, update tests to assert both contracts: the existing user-visible capacity remains fixed, and the internal graph capacity grows only by the explicitly reserved stage slot. Feed native warm-history tests in chunks no larger than the production processing block limit.

2026-10-27: Audio-render callbacks should request a rejected-update retry by signaling the control worker. Do not acquire the configuration listener's monitor from the render path, even when the retry only re-prepares the latest generation.

2026-10-27: AtomicFile safety checks must include both the primary file and `.bak`, reject dangling and live symlinks without following them, and preserve a future-schema backup before starting a new write. Capturing rollback state before mutating the active workspace prevents failed persistence from restoring the failed candidate.
For native audio-control ramps, prepare and validate the dB/linear pair off the render callback, then pass a tagged command through the existing bounded HOT mailbox. Advance one ramp step per PCM frame, not per channel sample, so stereo channels stay matched. ACK only after the full ramp, preserve compatible Studio HOT, defer an incompatible Studio gain while the ramp runs, and fence HOT/LOCAL_WARM with one generation order.

2026-10-28: The Studio HOT gain-refund path is also render-thread work. Carry its prepared linear target into the graph and retain it while refunding headroom; calling the dB setter there reintroduces `pow` despite the separate Preamp ramp being precomputed.

2026-10-27: Evidence ledgers should validate both directions of ID coverage against the canonical catalog, and status rules should be machine-checked. Historical suite summaries are not per-ID evidence unless each row links its exact method, command, result, source SHA, and artifact.

2026-10-27: Evidence validation should resolve local artifact and runbook links within the repository boundary and compare acceptance wording with the canonical predicate, so plausible-looking but stale links or rewritten criteria cannot pass by ID alone.
2026-10-27: For tabbed Compose content that is replaced on selection, use a distinct saveable `ScrollState` per tab; a single shared scroll state leaks the prior tab's viewport position into the next tab.

2026-10-27: A descendant's `rememberSaveable` value is forgotten when its conditional tab pane leaves composition. Wrap the pane in `SaveableStateProvider(tabKey)` when selection must survive tab changes.
