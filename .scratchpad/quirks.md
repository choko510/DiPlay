# Quirks

- `ANDROID_HOME`, `ANDROID_SDK_ROOT`, and repository `local.properties` are unset, but the SDK is installed at `$LOCALAPPDATA/Android/Sdk`; pass that path to the Gradle process when needed. The configured NDK `28.2.13676358` folder lacks `source.properties` and emits AGP `CXX1101` warnings. With the SDK path supplied, shared Kotlin compilation stops because `onIphonePermission` reads `requestId` and `device` from the `PermissionResult` base type, although those properties exist only on its subclasses. The same source mismatch is present on `origin/main`.
- Focused pure Kotlin/JUnit tests can be compiled with the Gradle-cached Kotlin compiler, JUnit, required cached dependencies, and the installed Android `android.jar` without compiling the full shared source set.
- The 8-second wired AirPlay startup timeout still has no measured slow-device distribution; validate or adjust it with real-device connection timing before treating it as a stable threshold.
