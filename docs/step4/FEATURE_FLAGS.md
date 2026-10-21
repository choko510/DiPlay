# STEP4 feature and fallback baseline

This file separates current main behavior from planned Step4 gates. Example flag names in v3 are not proof that a flag already exists. PR-00 adds no production flag or behavior change.

| Feature | Current main state | Planned initial state / fallback |
|---|---|---|
| Wired path diagnostics | No `WIRED_PATH_V1` recorder yet; existing connection trace is opt-in and bounded. | Debug opt-in, default OFF; no packet or session payload; disabling it restores uninstrumented transport behavior. |
| NCM RX optional pad / finite recovery | `NcmUsbBridge.drainFrames()` currently waits for the 512-byte boundary byte and treats the following NTB header as a malformed pad. | PR-01 changes only framing after EX-01 baseline. Keep a direct legacy fallback or revertable boundary until its tests pass. |
| Host retry epoch guard | `CarPlayHostActivity` has generation checks and a delayed reconnect runnable; recovered-session/second-loss integration is not established by this baseline. | Default to existing restart fallback; cancel stale retry only after regression cases pass. |
| USB re-enumeration probe | No new 10 s same-node probe is enabled by PR-00. | OFF until a same-node reconnect stall is captured; no repeated mode switching or permission prompt. |
| API 28+ queue-size fallback | Current NCM uses one 32 KiB persistent IN request. | OFF/absent unless explicit `queue() == false` is reproduced; keep 32 KiB as normal case and bound fallback attempts. |
| NCM status polling | Existing `NcmDiagnosticProfile.AUTO` polls an available status endpoint; debug `NO_STATUS_POLLING` is the comparator. | Preserve this current main behavior pending paired hardware A/B. Do not add a second requestWait owner. |
| Late first bulk OUT recovery | No new retry is introduced in PR-00. | OFF; only after EX-01 proves the delayed-OUT failure and the watchdog reachability gate passes. |
| Split local no-restart | Main has a 55% split layout but display-size updates can reach `restartCarPlay()`. | Start in a separate LOCAL candidate with legacy reconnect retained as explicit fallback. Do not change remote dimensions. |
| Dynamic ViewArea | Main serializes one initial area; no verified dynamic command is established. | OFF / experimental. Fall back to LOCAL, then legacy reconnect only on explicit recovery. |
| Video frame hold / codec tuning | No new PR-07 behavior is enabled by PR-00. | OFF unless a specific codec/Surface failure is measured; release decoder/surface resources on timeout. |
| DSP new modules | Main has an established profile, master switch, MEDIA stream policy and native graph. The four requested new modules are not part of this baseline. | New modules default OFF; preserve current PCM path and the existing latency/defer behavior on rollback. |

Any new persisted setting requires schema/migration review, disabled-default tests, old-profile compatibility and a rollback exercise before a rollout claim.
