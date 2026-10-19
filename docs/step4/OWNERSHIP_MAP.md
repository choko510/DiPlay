# Main ownership map for STEP4 regression work

This map records current-main responsibilities before STEP4 behavior changes. It is a test and review boundary map, not permission to move responsibilities across modules.

| Concern | Current-main owner | Existing evidence to keep in regression | PR-00 fixture boundary |
|---|---|---|---|
| iPhone USB enumeration and permission | `shared/.../transport/IphoneUsbHost.kt`, `IphoneUsbMatcher` | `Iap2WiredControlClientTest`, `MfiAuthenticationClientTest` | Synthetic endpoint/interface descriptors; no physical enumeration is mocked as success. |
| iAP2 USBMUX control/data session | `shared/.../transport/Iap2UsbMuxHost.kt`; frame parser `UsbMuxFrameDecoder.kt` | `UsbMuxFrameDecoderTest`, iAP2 protocol tests | Fake USB connection and explicit wait outcomes. |
| CDC-NCM pairing and NTB codec | `NcmFunctionDiscovery.kt`, `Ntb16Codec.kt` | `NcmFunctionDiscoveryTest`, `NcmNtbParametersTest`, `Ntb16CodecTest` | Declared NTB blocks, optional short pad, split/coalesced reads. |
| NCM bulk IN/OUT and status endpoint | `NcmUsbBridge.kt`, `PersistentUsbReadRequestState.kt`, `NcmDiagnosticProfile.kt` | `PersistentUsbReadRequestStateTest`, `NcmDiagnosticProfileTest` | Request-wait queue, timeout, null, and unexpected-completion dispatcher. One request-wait owner per connection remains the rule. |
| NCM-to-TUN packet path | `shared/.../network/Ipv6NcmBridge.kt`; VPN descriptor/lifecycle `CarPlayVpnService.kt` | `EthernetIpv6CodecTest`, `NcmLinkReadinessTest`, `NcmStartupNdpRetryTest` | Explicit inbound packets and explicit TUN write outcomes; no implicit ACK or send success. |
| Wired session orchestration and watchdog | `shared/.../orchestration/CarPlayController.kt`, `WiredStartupWatchdog.kt` | `WiredStartupWatchdogTest`, `ConnectionTraceEventTest` | Virtual clock/scheduler and distinct StartSession/SYN/TUN/accept/RTSP/ALT/MAIN events. |
| Foreground host and background owner | `common/.../CarPlayHostActivity.kt`, `CarPlayBackgroundSession` | `CarPlayRestartHandoffTest`, `CarPlayHostActionsTest` | Host layout and recovered/second-loss scenarios remain separate from production owners. |
| YouTube/GeckoView lifecycle and profile | `common/.../youtube/YoutubeBrowserController.kt`, `YoutubeBrowserLifecycle.kt`, `YoutubeDeviceProfile.kt` | `YoutubeBrowserLifecycleTest`, `YoutubeDeviceProfileTest`, alias-store and Split layout tests | Opaque video-frame and 55% Split viewport inputs; no Google account data is stored in fixtures. |
| AirPlay media and microphone uplink | `shared/.../airplay/CarPlayMediaEngine.kt`, `MicrophonePacketizer.kt`, `shared/.../media/MicrophoneUplink.kt` | `CarPlayMediaEngineTest`, `MicrophoneUplinkLifecycleTest`, `MicrophonePacketizerTest` | Siri/phone/voice-message/media request examples and deterministic PCM16. |
| Audio/video rendering | `shared/.../media/AndroidMediaSink.kt`, audio stream lifecycle/clock classes | `AndroidMediaSinkStateTest`, `AudioRendererLifecycleTest`, `AudioPlaybackClockTest`, `VideoDecodeQueueTest` | PCM and opaque encoded-frame payloads are deterministic test data, not device output. |
| DSP graph and persistent profile | `shared/.../media/dsp/`, `common/.../dsp/DspProfileRuntime.kt`, `DspProfileRepository.kt` | `DspOffLegacyPcmTest`, DSP unit tests, `DspPipelineBenchmarkInstrumentedTest` | PCM fixture only; DSP master-off remains the main baseline. |
| APK entry points and packaging | `mobile/` and `automotive/` manifests/build files | mobile/automotive lint, Debug/Benchmark builds, JNI mapping check | No product variant or manifest change in PR-00. |

## Ownership invariants

- USBMUX and NCM keep separate `UsbDeviceConnection` instances; each connection has one `requestWait()` owner.
- `CarPlayVpnService` owns the VPN/TUN descriptor. NCM read/write success, TUN write success, TCP accept, RTSP Active, screen setup, and MAIN first frame are distinct evidence.
- `CarPlayController` and `CarPlayBackgroundSession` own the session lifecycle across host UI coverage; an Activity callback is not a substitute for controller/session ownership.
- `AndroidMediaSink` owns output track generations. DSP latency/fallback and the playback clock must remain aligned to that generation.
- GeckoView profile resolution is identity-scoped. A Split layout state is not proof that the phone accepted a ViewArea protocol or that the media session remained connected.

PR-00 only adds test-source fixtures. It changes no owner, resource lifetime, production dependency, or UI/runtime behavior.
