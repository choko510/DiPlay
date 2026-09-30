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
