# Quirks

- Managed worktrees omit the ignored `local.properties`; setting `ANDROID_HOME` and `ANDROID_SDK_ROOT` to the installed SDK allows Gradle builds. This environment also reports an NDK 28.2 directory without `source.properties` while the shared module requests NDK 27.0; both APK builds still succeed and package the affected libraries without stripping.
- The 8-second startup snapshot and 15-second hard timeout reflect the slow-success timing in the 2026-09-30 brief. UIS8581/iOS 27 physical validation is still required.
- `gh` is unavailable in this checkout; use the GitHub connector after pushing a branch.
