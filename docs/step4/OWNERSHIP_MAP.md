# PR-00 main resource ownership map

This map records ownership found in the fixed main tree. It is a source-level design map, not proof of TS10/K706 resource behavior.

## Transport and connection attempts

| Resource / state | Main owner | Main source anchor | Boundary to preserve |
|---|---|---|---|
| iAP2/MFi accessory connection | `CarPlayController` delegates to the wired host/CSM path | `shared/.../orchestration/CarPlayController.kt`, `shared/.../transport/IphoneUsbHost.kt`, `shared/.../transport/Iap2CsmChannel.kt` | Keep connection/session retries owned by the current Controller attempt. |
| USBMUX channel and its USB connection | `Iap2UsbMuxHost` | `shared/.../transport/Iap2UsbMuxHost.kt` | Keep USBMUX `requestWait` and framing isolated from the NCM USB connection. |
| NCM connection, bulk endpoints, persistent IN request | `NcmUsbBridge` after the caller opens the connection | `shared/.../transport/NcmUsbBridge.kt`, `shared/.../transport/PersistentUsbReadRequestState.kt` | The bridge owns the passed connection after construction; preserve the single queued request and timeout state. |
| Wired startup attempt, NCM bridge publication, retry and teardown | `CarPlayController` | `shared/.../orchestration/CarPlayController.kt` | Attempt, Controller, and attachment generations are separate; one layer owns recovery at a time. |
| TUN descriptor, listener attachment and accepted socket generation | `CarPlayVpnService` | `shared/.../network/CarPlayVpnService.kt` | A stale accept callback must not publish into a replacement attachment. |
| Packet/link proof | Existing `NcmLinkReadiness` and NCM diagnostic snapshot | `shared/.../transport/NcmLinkReadiness.kt`, `shared/.../transport/NcmUsbBridge.kt` | NCM RX/TX proof remains distinct from AirPlay control acceptance and screen presentation. |

## AirPlay session, media, and host UI

| Resource / state | Main owner | Main source anchor | Boundary to preserve |
|---|---|---|---|
| AirPlay control session, negotiated streams and callbacks | `AirPlaySession` | `shared/.../airplay/AirPlaySession.kt` | Keep control-session milestones distinct from USB/NCM readiness and rendered frames. |
| Android audio renderer, `AudioTrack`, and microphone uplinks | `AndroidMediaSink` | `shared/.../media/AndroidMediaSink.kt`, `shared/.../media/MicrophoneUplink.kt` | Renderer and microphone generations have separate lifecycle and teardown ownership. |
| Video decoder and output surface | `AndroidMediaSink` | `shared/.../media/AndroidMediaSink.kt`, `shared/.../media/VideoDecoder.kt` | Surface or codec recovery must not implicitly recreate the USB/NCM session. |
| DSP graph and native processor handles | `AudioRenderer`/`DspPcmPipeline` own the prepared processor; `NativeDspProcessor` owns its JNI handle | `shared/.../media/AndroidMediaSink.kt`, `shared/.../media/dsp/DspPcmPipeline.kt`, `shared/.../media/dsp/NativeDspProcessor.kt` | Prepare/configuration is separated from PCM processing; close the native handle with its graph generation. |
| Foreground host UI and current Activity callbacks | `CarPlayHostActivity` | `common/.../CarPlayHostActivity.kt` | UI callbacks must be checked against the current owner and controller generation. |
| Controller/media sink retained while the host UI is covered or recreated | `CarPlayBackgroundSession` | `common/.../CarPlayHostActivity.kt` | Activity destruction is not session destruction; stale Activities cannot tear down the retained controller. |

## GeckoView, profiles, and Split

| Resource / state | Main owner | Main source anchor | Boundary to preserve |
|---|---|---|---|
| Gecko runtime | Application-scoped `YoutubeGeckoRuntimeProvider` | `common/.../youtube/YoutubeGeckoRuntimeProvider.kt` | Reuse one runtime; do not create one per Activity or phone. |
| Per-device persistent browser storage context | `YoutubeDeviceProfile` identity resolution | `common/.../youtube/YoutubeDeviceProfile.kt` | Use versioned hashed identifiers; do not persist raw controller/device identifiers or mix accounts. |
| Gecko session and temporary popup session | `YoutubeBrowserController` | `common/.../youtube/YoutubeBrowserController.kt` | Session activation/closure belongs to the host lifecycle; popup navigations stay within the resolved profile. |
| Display resize and IME settlement | `DisplayResizeCoordinator` and `ImeResizeResumePolicy` | `common/.../youtube/DisplayResizeCoordinator.kt` | Resize/IME events are UI geometry state, not authorization to restart USB or AirPlay. |

## Existing test ownership

| Concern | Main test anchor | Baseline limit |
|---|---|---|
| Persistent NCM request state | `shared/src/test/.../transport/PersistentUsbReadRequestStateTest.kt` | Tests the request state model, not a USB device. |
| USBMUX stream framing | `shared/src/test/.../transport/UsbMuxFrameDecoderTest.kt` | Does not prove NCM NTB transport behavior. |
| Startup watchdog | `shared/src/test/.../orchestration/WiredStartupWatchdogTest.kt` | Unit policy coverage; no head-unit timing evidence. |
| Audio renderer, mic, and DSP | `shared/src/test/.../media/`, `shared/src/test/.../airplay/`, `shared/src/test/.../media/dsp/` | JVM coverage does not prove Siri, calls, A/V synchronization, or target DSP quality. |
| Split/profile behavior | `common/src/test/.../youtube/` | State/geometry tests do not prove 20 same-session hardware cycles. |

No additional `UsbRequest`, VPN accept loop, session owner, or persistence owner is introduced by PR-00 fixtures.
