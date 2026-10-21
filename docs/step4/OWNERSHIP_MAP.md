# STEP4 resource and lifecycle ownership — fixed main

This map records the owners that PR-00 tests must preserve. It is based on the fixed `95ba398` source tree; a fake fixture is not evidence that Android USB, VPN, or iPhone behavior has been reproduced.

| Resource or state | Current owner | Main-code boundary / invariant |
|---|---|---|
| Wired startup attempt, data-path generation, recovery decision, and watchdog | `CarPlayController` | Opens the iAP2 session asynchronously, creates `NcmUsbBridge`, transfers the data path into `runStack`, and routes failures through its generation-checked retry policy. Startup attempt and data-path generation are distinct. |
| iAP2/MFi/USBMUX session | `IphoneUsbHost` / returned `Iap2UsbSession`, orchestrated by `CarPlayController` | The controller closes stale or failed sessions. USBMUX and NCM connections are separate. |
| NCM control/data interfaces, `UsbDeviceConnection`, bulk OUT, persistent bulk IN request | `NcmUsbBridge` | `open()` claims the control/data interfaces and selects the data alternate setting; the bridge owns the connection after success. Its `PersistentUsbReadRequestState` is the sole NCM request owner. `close()` cancels only a queued request, closes the connection, then closes the request. |
| NCM receive framing and typed outbound result | `NcmUsbBridge` / `Ntb16Codec` | The bridge frames/parses NTB16, queues Ethernet frames, updates NCM diagnostics/readiness, and returns `NcmSendResult`. It does not own a VPN route or TCP listener. |
| VPN TUN descriptor and wired attachment generation | `CarPlayVpnService` | `Builder.establish()` creates the TUN descriptor; the service owns the bridge, listener socket, active attachment, and AirPlay sessions, and releases them together. |
| NCM Ethernet ↔ TUN packet workers and learned peer MAC | `Ipv6NcmBridge` | Owns separate NCM-to-TUN and TUN-to-NCM threads. It strips/restores Ethernet headers, maintains `peerMac`, limits startup NDP retry through `NcmStartupNdpRetry`, and closes NCM/TUN before joining workers. |
| TCP accept, RTSP/AirPlay session, event channel, and media streams | `CarPlayVpnService` / `AirPlaySession` | The service binds `ServerSocket` and binds accepted sessions to the current attachment generation. `AirPlaySession` owns protocol/session lifecycle after accept. TCP accept, RTSP active, MAIN setup, and first frame are separate milestones. |
| Activity host UI, GeckoView Split layout, and UI reconnect timers | `CarPlayHostActivity` | Owns view layout, YouTube profile selection, Activity/UI generation, and its reconnect `Runnable`; it is not the USB connection, NCM request, or VPN owner. |
| Video decoder, Surface callbacks, AudioTrack renderer, and microphone uplinks | `AndroidMediaSink` | Owns per-stream decoder/renderer/uplink entries and maps them through `AudioOwnerToken`; callbacks from retired tokens must not mutate current streams. |
| YouTube runtime and device-profile alias storage | `YoutubeGeckoRuntimeProvider` / profile storage in `common` | The in-app GeckoView account/profile lifecycle is fork-specific and must remain separate from the attached iPhone and Android host Activity lifetimes. |
| DSP profile persistence and live config | `DspProfileRuntime` / `DspLiveUpdateController` | Runtime master state is persisted separately from profile data; DSP stream policy allows the full graph only for `MEDIA`. Updates prepare off the audio callback and preserve the active graph's latency contract. |
| App-owned connection trace files | `ConnectionTraceStore` | Writes bounded enum-derived records asynchronously; trace attempt IDs and sequence numbers do not own or replace Controller/USB/attachment generations. |

## PR-00 fixture rules

- `Step4ScriptedUsbConnection` scripts every transfer outcome. Its defaults are OUT `NotReady`, queue `false`, and `requestWait` timeout; the fixture cannot silently claim USB success.
- `Step4NcmBridgeFixture` constructs the real main `NcmUsbBridge` with test-only Robolectric shadows and explicit endpoint descriptors. It does not call `NcmUsbBridge.open()` and does not claim to test interface selection or a physical USB controller.
- `Step4SimulatedTun` is a JVM pipe pair, not a `VpnService` or Android `ParcelFileDescriptor` TUN. It proves only deterministic byte exchange for later tests.
- Synthetic IPv4/IPv6 TCP frames use documentation addresses and zero checksums; they are parser inputs, not packets observed from a phone or head unit.
