# Building DiPlay

Requirements: JDK 25, Android SDK 37.1, NDK 27.0.12077973 for the shared native module, and the included Gradle wrapper.

## Source and CI builds

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintDebug :automotive:lintDebug :mobile:assembleDebug :automotive:assembleDebug --stacktrace
```

The source-only APKs contain no accessory identity. Standalone CarPlay requires runtime authentication provisioning. Tests generate synthetic identities at runtime; no test private-key files are tracked.

Application APKs are split by ABI to avoid packaging every GeckoView native library into one very large download. Builds produce `arm64-v8a`, `armeabi-v7a` and `x86_64` APKs; distribute all three and install the one matching the head unit. No supported ABI is removed, and no universal APK is produced. The current arm64 debug APK is about 242 MB because GeckoView's native runtime is large; release size may differ.

## Local release packaging

Provide an external asset directory using `DIPLAY_AUTH_ASSETS_DIR`. The directory must contain exactly the intended runtime files under `offline-mfi/identity.pk8` and `offline-mfi/certificate.p7b`. Neither file belongs in Git. The build permits those two files only when this explicit input is set and rejects unexpected credential containers elsewhere in APK assets.

Set `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD` locally for your Android signing key. Never commit these values or the keystore. Different signing keys cannot update an existing project-signed installation.

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintRelease :mobile:assembleRelease
```

Output: one APK for each supported ABI under `mobile/build/outputs/apk/release/` (for example `mobile-arm64-v8a-release.apk`). The release APKs deliberately contain the experimental identity described in the notices; it is extractable by recipients. The separate Android signing key is not included. The retired build-beta.py helper is not used; this Gradle workflow uses explicit environment inputs.

The public release source archive corresponds to the tagged source and excludes runtime identities, signing keys, local configuration and build output.
