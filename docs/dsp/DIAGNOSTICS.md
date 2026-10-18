# DSP Runtime Diagnostics

The settings screen and five-second audio statistics snapshot expose the prepared graph's state. Values come from the graph that is actually running; `activeModulesMask` is a bit field:

| Bit | Module |
|---:|---|
| 0 | DSP master |
| 1 | Preamp / gain |
| 2 | Parametric EQ |
| 3 | Bass shelf |
| 4 | Compressor |
| 5 | Multiband |
| 6 | Dynamic EQ |
| 7 | Stereo Width |
| 8 | Mono Bass |
| 9 | Seat Focus |
| 10 | Advanced M/S |
| 11 | Cabin Correction |
| 12 | High Exciter |
| 13 | Virtual Bass |
| 14 | Convolver |
| 15 | Safety Limiter |
| 16 | Auto Headroom |

`seatLatencyFrames` reports Seat Focus's fixed reservation for the current sample rate. `algorithmicLatencyFrames` reports the total prepared pipeline latency, including Seat Focus, Harmonic, Convolver, and any latency-preserving fallback delay. `autoHeadroomDb` is the positive attenuation applied before the graph. `harmonicQuality` is null when neither Harmonic branch is prepared; otherwise it reports 2× or 4×. `cabinCalibrationId` is an opaque profile identifier and never a file path.

`processP99Us` measures the full `DspPcmPipeline.process()` call, including PCM conversion, JNI/native work, and final PCM16 conversion. The native-only histogram is retained separately in audio statistics. These host/emulator diagnostics do not imply TS10 performance or CarPlay continuity.
