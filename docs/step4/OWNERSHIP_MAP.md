# STEP4 ownership map

This map records the current `main` ownership boundaries so later stages do not create a second owner for the same resource. Source paths below are the fixed-main baseline at `95ba398`.

| Area / resource | Current owner | Boundary to preserve |
|---|---|---|
| USB permission, iAP2 host session, USBMUX | `shared/.../transport/IphoneUsbHost.kt`, `Iap2UsbMuxHost.kt` | One USB connection/session owner; stale permission and open callbacks must not publish into a newer attempt |
| CDC-NCM interface discovery | `shared/.../transport/NcmFunctionDiscovery.kt` | Pair control/data interfaces from CDC descriptors; do not infer pairings from interface order alone |
| NCM connection, endpoints, persistent bulk-IN request, bulk-OUT send result | `shared/.../transport/NcmUsbBridge.kt`, `PersistentUsbReadRequestState.kt` | The bridge owns the claimed connection after open; one persistent `UsbRequest` is the sole `requestWait()` owner; preserve typed `NcmSendResult` and diagnostics |
| NTB framing | `shared/.../transport/Ntb16Codec.kt` | Codec builds the current NTB16 transmit format; receiver stream framing belongs above the USB read chunk and must remain bounded |
| Controller attempt, startup, retry, watchdog, USB/NCM cleanup | `shared/.../orchestration/CarPlayController.kt`, `WiredStartupWatchdog.kt` | The controller owns each wired attempt and the 8s/15s watchdog; Host must not issue a duplicate wired retry |
| VPN, TUN descriptor, TCP listener and accepted socket | `shared/.../network/CarPlayVpnService.kt` | The VPN service owns its TUN/socket lifecycle and attachment generation; an old accept loop must not act on a new attachment |
| Ethernet/NCM ↔ TUN forwarding and peer-MAC state | `shared/.../network/Ipv6NcmBridge.kt`; link readiness in `shared/.../transport/NcmLinkReadiness.kt` | Preserve the IPv6/NDP path and distinguish packet reception, successful TUN writes, OUT results, TCP accept, and screen opening |
| Host Activity, menu, in-app Split, Surface and UI lifecycle | `common/.../CarPlayHostActivity.kt`, `CarPlayHostActions.kt` | Activity owns presentation and UI callbacks, not the long-lived transport session; do not make a surface resize a second connection owner |
| Split state, resize coordination and Activity restart handoff | `common/.../youtube/SplitLayoutState.kt`, `DisplayResizeCoordinator.kt`, `CarPlayRestartHandoff.kt` | The current local split is 55:45; the verified restart fallback remains available until same-session behavior is proven |
| YouTube Gecko runtime, sessions, profile identity and popup policy | `common/.../youtube/YoutubeGeckoRuntimeProvider.kt`, `YoutubeBrowserController.kt`, `YoutubeDeviceProfile.kt` | Keep one runtime and profile-specific storage; never mix two iPhone identities or clear persistent cookies to recover a session |
| AirPlay display declaration and authenticated session commands | `shared/.../airplay/AirPlayInfoPlist.kt`, `AirPlaySession.kt`, `shared/.../media/CarPlayTouchMapper.kt` | The current `/info` declaration has one ViewArea; a command send is not proof that the phone accepted it or changed frame dimensions |
| Video decoder, output format and render surface | `shared/.../media/AndroidMediaSink.kt`, `VideoDecodeQueue.kt` | Preserve codec/Surface generation ownership, CSD and output-format transitions; vendor codec flags remain capability-gated |
| Audio stream negotiation, AudioTrack/clock and media routing | `shared/.../airplay/CarPlayMediaEngine.kt`, `shared/.../media/AndroidMediaSink.kt`, `AudioPlaybackClock.kt`, `AudioStreamClassification.kt` | Keep MEDIA, NAV, PHONE and ASSISTANT roles separate; preserve track generation and latency alignment |
| Microphone capture and uplink | `shared/.../media/MicrophoneUplink.kt`, `MicrophonePacketizer.kt` | One recorder owner per session; permission, `isInput`, port and stop/restart state govern capture; never apply MEDIA DSP to phone/Siri uplink |
| DSP profile persistence and runtime master | `common/.../dsp/DspProfileRuntime.kt`, `DspProfileRepository.kt`, `shared/.../media/dsp/DspStreamPolicy.kt` | Schema v3 and the global default-off master are existing contracts; profile edits must not bypass the master or apply an editor draft |
| DSP PCM pipeline and native graph | `shared/.../media/dsp/DspPcmPipeline.kt`, `NativeDspProcessor.kt`, `shared/src/main/jni/dsp/` | Prepare state off the render thread; process only MEDIA; no dynamic allocation, I/O or unbounded lock in the audio path |
| Connection trace, display snapshot and user export | `common/.../ConnectionTraceStore.kt`, `DiagnosticExportStore.kt`, `DisplayDiagnosticSnapshot.kt` | Diagnostics are bounded and app-owned; redact before sharing; do not persist packet payloads or raw phone identifiers |

## Review rule for later changes

Changes that touch more than one owner above require a split by the plan's dependency order or an explicit ownership handoff in the PR. PR-00 introduces test-only fakes and does not move runtime ownership.
