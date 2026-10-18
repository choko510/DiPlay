# STEP4 ownership map

基準: fork `main` `95ba398f95ee104527812cd2b095977d71c51f98`。この表はコード上の責務と所有境界を示す。実機の実行挙動を証明するものではない。

| Flow / owner | Main source | State and boundary to preserve |
|---|---|---|
| USB descriptor selection | `shared/.../transport/NcmFunctionDiscovery.kt` | CDC-NCM control/data interface pairing, endpoint descriptors, selected function diagnostics |
| NCM USB transport | `shared/.../transport/NcmUsbBridge.kt` | Owns NCM connection after open; `readLock`, `stateLock`, `writeLock`; one persistent bulk-IN request; `NcmSendResult`; bounded frame queue |
| Async read request lifecycle | `shared/.../transport/PersistentUsbReadRequestState.kt` | Same request survives wait timeout; close/cancel and terminal result ownership stays single |
| NTB encoding | `shared/.../transport/Ntb16Codec.kt` | Existing NTB16 TX layout; RX framing work must not change USB request mode or TX bytes |
| Ethernet / NDP bridge | `shared/.../network/Ipv6NcmBridge.kt`, `shared/.../transport/NcmLinkReadiness.kt`, `shared/.../network/NcmStartupNdpRetry.kt` | NDP-only bounded retry, explicit inbound/outbound readiness evidence, no application retry of normal TCP sends |
| TUN service | `shared/.../network/CarPlayVpnService.kt` | Owns VPN interface and packet I/O; attachment generation identifies VPN attachment, not UI restart generation |
| Wired controller | `shared/.../orchestration/CarPlayController.kt` | Owns wired startup attempt, iAP2/NCM opening, controller recovery, AirPlay/TUN attachment, and existing 8s/15s watchdog |
| Host UI and reconnect | `common/.../CarPlayHostActivity.kt` | Owns UI lifecycle, display/resize actions and host reconnect scheduling; background controller ownership is transferred separately |
| AirPlay control / stream | `shared/.../airplay/AirPlaySession.kt`, `ScreenStream.kt`, `CarPlayMediaEngine.kt` | Session owns control events and media streams; MAIN/ALT stream state and first rendered frame must remain distinguishable |
| Microphone uplink | `shared/.../media/MicrophoneUplink.kt`, `OpusEncoder.kt` | AudioRecord and encoder lifecycle; explicit no-input request must prevent recording |
| Video output | `shared/.../media/AndroidMediaSink.kt`, `VideoDecodeQueue.kt`, `VideoStats.kt` | Codec, output format, Surface and frame queue lifecycle; decoder-only recovery must not close the network session |
| YouTube split/profile | `common/.../youtube/` and `CarPlayHostActivity.kt` | iPhone-specific Gecko context/profile, Google login, browser reuse, touch transform, IME and background session behavior; LOCAL keeps the negotiated canvas with session-scoped resize ownership |
| DSP persistence/UI | `common/.../dsp/`, `DspSettingsActivity.kt` | Profile schema, master switch, persistence and UI/runtime snapshot boundary |
| DSP runtime | `shared/.../media/dsp/` | Renderer thread owns graph adoption and PCM clock; native handles and latency budget remain generation-scoped |
| OEM removal invariant | `mobile/`, `automotive/`, `shared/` manifests/resources | BYD-only HUD/cluster/rhythm dependencies removed on main must not re-enter through upstream changes |

## Ownership invariants

- The controller wired attempt/run identity, VPN attach generation, Host UI generation, AirPlay session generation, and Gecko profile identity are distinct values; do not substitute one for another.
- An old Host retry or callback cannot tear down a recovered session or mutate the new attachment. The controller is the only retry owner when `wiredRecoveryManaged` is active.
- USB `requestWait()` completion has one owner per connection. A second consumer for the status endpoint cannot be added without a shared dispatcher design and completion tests.
- Split LOCAL rendering keeps the iPhone media stream and NCM/AirPlay session unchanged. Dynamic ViewArea remains separately gated by observed iPhone acceptance and frame-size change.
- The TextureView aspect-fit bounds and inverse HID coordinate transform share one geometry; view size never replaces the session's negotiated canvas while LOCAL is active.
- MEDIA-only DSP eligibility is determined by stream semantics. PHONE, NAV, ASSISTANT, alerts, and microphone capture retain their existing routing and clock contracts.
