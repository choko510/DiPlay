# Backlog

- Repeat the K706/UIS8581 function-selection comparison using the mobile arm64 debug APK: the 2026-10-07 report shows FORCE_5_6 reaching NCM_LINK_READY once, while FORCE_3_4 has no link proof; neither report proves AirPlay screen startup. Alternate FORCE_3_4 and FORCE_5_6 several times before considering a default change, then test STATUS_POLLING or timeout profiles if still needed. Keep the post-startup UsbRequest queue/wait failure separate.
- If INSTALL_PARSE_FAILED_NO_CERTIFICATES persists on the fresh v1/v2-signed mobile APK, compare the exact installed file against the generated artifact and inspect the installer/package-manager log before changing signing or NCM behavior further.
