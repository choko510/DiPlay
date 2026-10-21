# PR-00 code review record

## Scope and disposition

- Base: fixed fork `main` SHA `95ba398f95ee104527812cd2b095977d71c51f98`.
- Scope reviewed: test-only fixtures/shadows, baseline documents, and `scripts/run_android_instrumentation.sh`. No production Kotlin/JNI source changed.
- Self-review result: no remaining static issue found after the corrections below. This is not an independent reviewer approval.
- Formal independent Review A and Review B: `NOT_RUN`. The static audit and local/hosted test results are recorded separately and must not be described as a double review.

## Self-review A — protocol, ownership, and security

- The fake USB connection fails closed by default: bulk OUT is `NotReady`, request queue returns false, and requestWait times out unless a test scripts an outcome. Packet/IPv4/IPv6/TUN values use synthetic documentation addresses and no user/device identity.
- Direct `NcmUsbBridge` tests do not call `open()`, claim an interface, start a status thread, or claim to simulate a physical USB controller. The simulated TUN uses JVM pipes, not `VpnService` or `ParcelFileDescriptor`.
- The 512-byte pad test distinguishes the NTB declared block length from the extra wire pad byte. The padless case records the current main defect; PR-00 does not alter production framing.
- No input/output packets, MAC address, RTSP credential, or real diagnostic log is added to fixtures.

## Self-review B — Android lifecycle, media, and verification

- The blocked `requestWait` test exercises `NcmUsbBridge.close()` on a separate thread and checks that cancellation, connection close, request close, and reader completion each happen once.
- The sync and async read tests compare delivery of the same synthetic Ethernet frame. This covers the main bridge contract but not real USB timing, Android VPN behavior, or iPhone/TCP acceptance.
- `scripts/run_android_instrumentation.sh` now brackets the test invocation with a unique logcat run ID and retains only benchmark records between those markers; it does not clear existing device logs. The updated script was run successfully on API 36.1 and captured 120 benchmark records for that invocation.
- Hosted run #101 validates the fixed-main code only. The new PR's hosted checks must validate this change set independently.

## Findings corrected during PR-00

1. The original instrumentation script dumped all retained benchmark-tag logcat records, so stale entries could satisfy the evidence check. The script now uses unique start/end markers, filters only the current run, and fails when markers or benchmark records are missing.
2. The NCM boundary fixture initially treated the output array length as the declared NTB length. `Ntb16Codec.build` returns a zero pad after a 512-byte declared block; the fixture now removes exactly that byte when modeling the padless input.

## Remaining acceptance limits

Independent review A/B and physical TS10/K706/iPhone acceptance remain `NOT_RUN`. The emulator, fake USB/TUN, and local-only app builds do not satisfy those gates.
