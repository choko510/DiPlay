# Quirks

- Managed worktrees omit the ignored `local.properties`; setting `ANDROID_HOME` and `ANDROID_SDK_ROOT` to the installed SDK allows Gradle builds. This checkout's `ndk.dir` points to NDK 27.0 while `android.ndkVersion` requests 28.2, producing a nonfatal mismatch warning; both APK builds still succeed and package two affected libraries without stripping.
- The 8-second startup snapshot and 15-second hard timeout reflect the slow-success timing in the 2026-09-30 brief. UIS8581/iOS 27 physical validation is still required.
- `gh` is unavailable in this checkout; use the GitHub connector after pushing a branch.
- This managed worktree had no `ANDROID_HOME` or `ANDROID_SDK_ROOT`; setting both to the installed SDK and using Android Studio JBR 21 enabled Gradle. NDK 28.2 emitted a nonfatal missing `source.properties` warning and packaged two libraries without stripping. The first shared-test run hit a 50 ms MFi retry timeout; the complete shared suite passed on rerun.
