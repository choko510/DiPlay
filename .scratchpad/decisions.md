# Decisions

## Keep connection traces opt-in and app-owned

The debug setting applies from the next connection attempt and writes structured elapsed-time events to bounded app storage. This keeps tracing independent of the foreground activity lifecycle; existing session logs continue to supply throughput and media diagnostics in the same exported report.

## Keep one USBMUX IN request and use bounded recovery

USBMUX now reuses one 32 KiB direct-buffer request across wait timeouts; framing rebuilds up to 64 KiB messages above the USB read layer. On failure, close the wired CSM, USBMUX/session, and VPN/NCM together, then allow at most one automatic retry. USBMUX and NCM remain on separate `UsbDeviceConnection`s because the current interface layout provides no evidence that combining them improves reliability.

## Bind asynchronous startup results to the wired attempt

AirPlay startup success and VPN/NCM transport errors must identify the attachment/connection attempt that produced them before they can cancel a watchdog or tear down/restart the wired stack. A service-local cleanup is not sufficient when the controller still owns the iAP2 CSM and USBMUX host.

The attempt check must be atomic with the state mutation or cleanup it protects. A check followed by an unscoped callback or teardown still leaves a check-then-act race.

For the user-visible wired startup interval, keep the watchdog active until the screen stream is opened. The AirPlay `RECORD` event marks its control session active earlier, while the host still shows its Opening panel.

## Keep extra USB diagnostics opt-in and bounded

Use the existing debug-log preference for syslog relay capture and small CarKit write splitting. Limit the capture by time, bytes, and matching-line count, and log only process-category counts so raw phone log contents are not persisted.

## Preserve observed USBMUX receive compatibility

Keep the received TCP `word8` field diagnostic-only because observed iPhone replies omit the host's `0xfeedface` value. The version handshake still validates its response version; do not reject TCP frames on an unconfirmed magic-field rule.

## Separate navigation output offers from voice and media

Restrict the high-rate-only negotiation fix to stream type 101 (AltAudio). Wired type 101/default and compatibility advertise configured 44.1/48 kHz PCM; wireless type 101/default also advertises Opus, while compatibility stays PCM-only. Keep type 100 MainAudio PCM capabilities broad, and make its Opus and microphone input offers transport-specific. Keep type 102 media unchanged.

Keep the transport flag false by default and set it only when creating the wireless AirPlay session config. This preserves wired callers while making /info capabilities match the active transport.
