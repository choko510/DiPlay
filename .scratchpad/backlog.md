# Backlog — PR #45

> Future/evidence-dependent tasks only. For latest recorded status read [current.md](current.md). These are not automatic implementation instructions.

## P0 — Review and acceptance blockers

- [ ] Resolve or explicitly classify 4AI gaps AI4-CI-NEW-01/02, AI4-EX08-NEW-01, AI4-TEST-NEW-01 and policy-dependent AI4-SYS-BASE-01; retain concrete evidence and negative tests.
- [ ] Revalidate host/native, Android lint/build/JNI and API 35 instrumentation with the correct baseline scope. Investigate unstable DSP p99 results without silently weakening budgets or counting skipped jobs as passes.
- [ ] Execute supported **TS10/K706 + iPhone** physical suite: NCM/IPv6→TUN; Siri/call/AEC/AGC; MAIN/ALT, YouTube/DSP/local Split 20-cycle continuity; Surface/rotation/HID/safeArea; DSP THD/headroom/latency/A-V/CPU/underruns; 30–60-minute soak; install/signature/update/rollback. SH M20 is only PARTIAL evidence.
- [ ] Independent v3 review and G8 release decision remain NOT_RUN/NO_GO_THIS_RUN unless specifically authorized and verified.
- [ ] Sixth report C-00: verify the dynamic finite-height DSP Split scroll viewport on SH-M27 Android 15 at 1612×720/55:45, including draw, scroll, tabs, 20 toggles, IME/rotation and Surface recovery while CarPlay stays connected and PID remains stable. The attached log confirms the Compose infinity-height crash; the first Android parent MeasureSpec source and device acceptance remain unverified.
- [ ] Third re-audit evidence gates remain open: USB requestWait/close and SYN-ACK recovery on TS10/K706; ALT-only session policy; decoder stop ACK and Surface lifetime; MAIN/ALT shared Surface feature reachability; Seat phase response; same-engine JNI process ownership; API 35/full-host/JNI/instrumentation at the current review head; and the CI classifier's API/baseline end-to-end negative fixtures.
- [ ] Fourth re-audit residuals: test SurfaceTexture release ordering on target hardware; verify two-worker reaper worst-case backlog and post-start mic recovery on supported devices; gather single-following-NTB liveness, requestWait/close, SYN-ACK, ALT/Surface reachability and diagnostics-off cost evidence; confirm the MANAGE_USB attach resolver path on TS10/K706 OEM firmware. Keep API 35, full-host/R8, signing/rollback and branch-protection checks separate from Draft PASS.
- [ ] Fifth integrated review gates after source fixes: API 35/JNI/R8 and hosted CI on the updated head; TS10/K706 VPN revoke, malformed NTB and USB close timing; TextureView/MediaCodec release-failure lifecycle; keyframe queue churn/recovery on target; DSP release-failure, CPU, THD and A/V measurements. Track the RX-only USB send failure separately until its own device logs are available; the SH-M27 Compose crash now has attached log evidence and a source mitigation, with device acceptance tracked under C-00.
- [ ] AI4-R3-08 patch hashes need independent recomputation for any adopted EX-08 candidate. `generate_step4_ex08_inventory.py --check` proves ledger consistency, not semantic adoption or patch-hash provenance.

## P1 — Measurements before fixes

- [ ] Same 3→4/1 target A/B: NCM status `AUTO` vs `NO_STATUS_POLLING`, first frame, `SCREEN_STREAM_OPENED`, timeout counts. Four 2026-10-09 status-on successes do not establish status-off parity. Do not change 5→6 or add NCM controls without evidence.
- [ ] Reproduce queue-false / `requestWait`-null while device is **present**. Previous failures coincided with physical disconnect; keep persistent USB-request ownership otherwise.
- [ ] Plan and implement only Stage3 per-attempt monotonic **timing** diagnostics after source review; keep EX05 second StartSession gated by target timing.
- [ ] Build **111-commit versioned EX-08 overlay** without mutating the 1,289-row historical source catalog; retain immutable commit anchors, dispositions, requirements and generated-file `--check`.

## P2 — Conditional upstream candidates

- [ ] Pass15 delta row 4 Bluetooth turning-on, row 24 CS11/VLAN hotspot interface, row 28 Android 13 Wi-Fi Direct refusal and row 44 second wired-start watchdog need target-specific evidence.
- [ ] `ROTATION_SQUARE_CANVAS` remains `NO_GO_THIS_RUN` pending verified rotation/safeArea/HID/iPhone screenshots; do not publish UI claims for missing controls.
- [ ] Preserve all 353 `NEED_EVIDENCE` rows and do not bulk-import upstream, BYD OEM code, root/appops writes or cross-gate aggregate commits.

Older details: [archived current](archive/2026-10-23-current-before-consolidation.md), [completed](completed.md), [decisions](decisions.md).
