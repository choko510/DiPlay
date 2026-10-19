# DSP-00 main baseline

Source of truth: fork `main` at `95ba398f95ee104527812cd2b095977d71c51f98`. DSP-00 records the existing two-channel graph before DSP-01..05; it changes no production DSP processing, persisted fields, or profile schema.

## Existing contract from main

| Area | Verified implementation |
|---|---|
| Profile | `DspAudioProfile` contains preamp, 15-band PEQ, bass shelf, compressor, stereo width, mono bass, Convolver, multiband, Dynamic EQ, and safety limiter. Seat Focus, measured Cabin Correction, and Harmonic Engine are absent. |
| Schema | `DspProfileRepository.CURRENT_SCHEMA_VERSION` is 3. Tests cover legacy v0/v1/v2 migration, v3 round trip, malformed profiles, and safe refusal to overwrite a future schema. |
| Master switch | `DspProfileRuntime` reads a separate global enable preference defaulting false. `toRuntimeConfig(masterEnabled)` takes runtime enable from that switch; legacy `profile.enabled` does not override it. |
| Stream eligibility | `DspStreamPolicy` allows the full graph only for MEDIA. Navigation, alert, assistant, phone, and other low-latency streams remain outside it. |
| Format | `DspAudioFormat` accepts 8–192 kHz and one or two channels. |
| Native graph order | `dsp_graph.c`: gain → PEQ → bass shelf → compressor → multiband → Dynamic EQ → stereo spatial processing → Convolver → limiter. |
| DSP OFF | `AudioRenderer` leaves `dspPipeline` absent when the master switch is off. LPCM remains on the PCM16 write path, and DSP latency for the track generation is zero. |

## Disabled-path PCM and latency golden

`DspRendererProductionPathTest.dspOffLpcmProductionPathWritesTheOriginalPcm16Samples` now exercises the production renderer with master OFF and deliberately non-neutral configured gain/width values. It covers 44.1/48 kHz, mono/stereo, and 1/128/512/1,537/16,384-frame payloads. A pipeline preparer that throws if called proves the graph is not prepared; the PCM16 samples remain byte-exact at the AudioTrack-write boundary, `dspPipeline` remains null, and the generation latency remains 0 frames. The test also checks that the first RTP clock anchor is unchanged.

`DspOffLegacyPcmTest` separately covers PCM16 normalization without copying the source array and preserves the input offset. `DspProfileRepositoryTest.legacyProfileMigratesToCurrentSchema` maps a migrated v0 profile through the global master-OFF setting and confirms the disabled runtime retains the migrated gain without enabling processing. Repository tests also cover v1/v2 migration, v3 round trip, malformed values, and future-schema preservation; `DspStreamPolicyTest` covers MEDIA-only policy.

## Verification on this branch

| Gate | Result | Evidence |
|---|---|---|
| Shared/common unit tests | `PASS_LOCAL`: 453/136, zero failures/errors/skips | Full `:shared:testDebugUnitTest :common:testDebugUnitTest` |
| Mobile/automotive lint, debug, benchmark | `PASS_LOCAL` | Full Gradle lint and app build tasks |
| JNI mappings and public tree | `PASS_LOCAL` | Both app R8 mappings verified; no credential/private-key artifacts |
| Android instrumentation | `PASS_EMULATOR`: 16/16 | API 36.1 x86_64; existing DSP JNI smoke/benchmark coverage, not acoustic or CarPlay hardware evidence |
| Native C test | `PASS_EMULATOR` | Existing `test_dsp_engine.c` plus production DSP C files compiled for x86_64 Android with NDK Clang and run on the AVD |
| Native ASan/UBSan test | `PASS_EMULATOR` | Same native test passed with AddressSanitizer and UndefinedBehaviorSanitizer; LeakSanitizer disabled on Android |
| Host `scripts/test_dsp_native.sh` | `NOT_RUN` | WSL launch failed with `HCS_E_SERVICE_NOT_AVAILABLE`; no Windows host C compiler is installed |
| TS10/K706 audio/AV | `NOT_RUN` | No target head unit/iPhone attached |

Performance values already recorded in `.scratchpad/optimization.md` are historical measurements of the existing graph, not a new DSP-00 measurement and not TS10 evidence.
