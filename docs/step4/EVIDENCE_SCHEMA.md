# STEP4 Evidence Record Schema

Every gate result must identify the exact source revision and evidence level. Keep user-confirmed statements, automated test output, emulator output, and physical-device measurements as separate evidence types. A skipped or unavailable check is never a pass.

## Required fields

```json
{
  "gate_id": "USB-QUEUE-01",
  "status": "BLOCKED_DEVICE",
  "source_sha": "<40-character source SHA>",
  "build_sha256": null,
  "device_class": "TS10/K706",
  "android_version": null,
  "iphone_ios": null,
  "test_command": null,
  "assertions": ["No double queue", "No stale callback"],
  "evidence_files": [],
  "excluded_data": ["deviceId", "MAC", "PCM", "MFi secret"],
  "notes": "Physical target unavailable"
}
```

| Field | Meaning and rule |
|---|---|
| `gate_id` | Stable identifier from the implementation plan or test matrix. |
| `status` | One of the status values below. State the actual evidence level; do not promote historical `NOT_RUN` records to a pass. |
| `source_sha` | Full 40-character commit SHA containing the code under test. For documentation-only revisions, also identify the latest source-code SHA in the notes. |
| `build_sha256` | SHA-256 of the exact APK/native artifact tested; `null` when no build artifact exists or the gate was not run. |
| `device_class` | Target or test environment, such as `TS10/K706`, `API 35 emulator`, or `host`. |
| `android_version`, `iphone_ios` | Observed versions, or `null` if not applicable/not collected. |
| `test_command` | Exact command or manual procedure. Use `null` when not run. |
| `assertions` | Observable pass/fail conditions, not a summary claim. |
| `evidence_files` | Repository-relative, redacted artifact paths. Keep private device logs outside Git unless explicitly sanitized. |
| `excluded_data` | Sensitive data omitted from the evidence record, at minimum device identifiers, MAC addresses, PCM/audio payloads, and MFi secrets. |
| `notes` | Limits, failures, missing evidence, and user confirmation context. |

## Status vocabulary

- `PASS_LOCAL`, `PASS_EMULATOR`, `PASS_CI`, `PASS_DEVICE`, `PASS_STATIC`: the named test level completed successfully at the recorded `source_sha`; include command, output, and applicable artifact hash.
- `PARTIAL`: some stated observations passed, but one or more required assertions or target conditions are missing.
- `FAIL`: an executed assertion failed. Preserve the failing SHA and evidence before recording a later fix.
- `SKIP`: the runner explicitly skipped the check; state the reason.
- `NOT_RUN`: no execution evidence exists yet.
- `BLOCKED_DEVICE`: the required physical device/phone is unavailable.
- `BLOCKED_ENV`: the required build, signing, SDK, or host environment is unavailable.
- `NO_GO_THIS_RUN`: a conditional feature/release gate remains disabled because its prerequisites are not proven.
- `USER_CONFIRMED_RESOLVED`: the user explicitly confirmed resolution; this is not a test pass or physical-device measurement.

## Evidence hygiene

Record CI run ID, job and step conclusions alongside the exact commit SHA. Keep `success`, `failure`, and `skipped` distinct. Redact personal and network identifiers, authentication material, and media payloads before adding artifacts to the repository. Use `null`/empty values for unavailable data; do not infer them.
