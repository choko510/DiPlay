# Backlog — PR #45

> Unstarted or evidence-dependent work only. Read [current.md](current.md) for the active batch and exact CI/source status. These entries do not authorize a PR state change.

## P0 — Review and acceptance blockers

- [ ] Run the supported TS10/K706 + iPhone matrix: wired NCM/IPv6→TUN; Siri/call/AEC/AGC; MAIN/ALT and local Split continuity; Surface/rotation/HID/safeArea; DSP THD/headroom/latency/A-V/CPU/underruns; 30–60-minute soak; install/signature/update/rollback. SH M20 remains PARTIAL evidence only.
- [ ] On TS10/K706 Android 10, verify the `MANAGE_USB`-restricted Apple attach entry appears in the USB chooser, cold-launches after task removal, handles permission refusal/retry and 20 physical re-enumerations without duplicate Controllers, and preserves wireless→wired plus DSP/YouTube Split state. Also verify an already-connected iPhone after navigation reboot and record whether Android emits a fresh attach identity during re-enumeration; never infer the same phone from VID/PID alone. ADB had only an emulator in the 2026-10-28 implementation run.
- [ ] Validate F8-01 MAIN/ALT pre-RECORD disconnect recovery, F8-02 4 MiB RTSP-body compatibility, and F8-03 four-session cap/release under supported-device reconnect traffic.
- [ ] On SH-M27, verify DSP Split inset visibility, scroll boundaries and ComposeView measurement diagnostics while CarPlay remains active. C-00 itself remains USER_CONFIRMED_RESOLVED; this gate is device evidence only.
- [ ] Release-signed update and installed rollback; release credentials/keystore are currently unavailable. Do not substitute a debug signature.
- [ ] Keep independent second review `NOT_RUN` per user direction; G8 remains `NO_GO_THIS_RUN` until its requested evidence is complete.

## P1 — Measurements before conditional changes

- [ ] Collect same-device TS10/K706 + supported-iPhone video worker, `receiveToSurfaceSubmitMs`, actual display-presentation delay, MAIN/ALT request-result, Surface recovery, and 30–60-minute memory/FD metrics before enabling the default-off Surface recovery policy. Surface-submit percentiles do not measure visible display time. Measure Host log dispatch/file-write cost before considering OPT-06 batching.
- [ ] Same-device NCM `AUTO` vs `NO_STATUS_POLLING` A/B with first-frame, `SCREEN_STREAM_OPENED`, and timeout evidence; do not change status behavior or add control requests without cause.
- [ ] Reproduce `queue()==false` / `requestWait()==null` while the USB device is present; preserve persistent USB-request ownership otherwise.
- [ ] Keep Stage3 diagnostics timing-only and EX05 second StartSession retry disabled until target packet timing proves the need.
- [ ] Recompute source patch hashes only for EX-08 candidates selected for adoption; inventory `--check` alone is not semantic review or patch provenance.

## P2 — Conditional candidates

- [ ] Review Pass15 delta rows 4, 24, 28 and 44 only with their target-specific evidence.
- [ ] Keep `ROTATION_SQUARE_CANVAS`, dynamic ViewArea, vendor codec overrides, and unconditional extra StartSession attempts `NO_GO_THIS_RUN` until their specific prerequisites are measured.
- [ ] Preserve all existing `NEED_EVIDENCE` EX-08 rows; do not bulk-import upstream, BYD OEM code, root/appops writes, or cross-gate aggregate commits.

Historical notes: [completed](completed.md), [decisions](decisions.md), [lessons](lessons.md), [quirks](quirks.md), [optimization](optimization.md).
