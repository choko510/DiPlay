# Backlog

- Run the K706/UIS8581 NCM A/B sequence using the mobile arm64 debug APK with debug profile logs. The 2026-10-05 follow-up is still AUTO-only; it shows repeated 100 ms OUT NotReady, including Router Solicitation frames, but does not test status polling. Test STATUS_POLLING first, then isolate pre-ready OUT timeout, SYNC_BULK_IN and forced 5→6/1 only if needed. Keep the post-startup UsbRequest queue/wait failure separate and select a production fix only from repeatable device evidence.
- If INSTALL_PARSE_FAILED_NO_CERTIFICATES persists on the fresh v1/v2-signed mobile APK, compare the exact installed file against the generated artifact and inspect the installer/package-manager log before changing signing or NCM behavior further.
