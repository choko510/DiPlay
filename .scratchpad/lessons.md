# Lessons

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
