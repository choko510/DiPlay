# iPhone USB auto-launch hardware test

Use a physical Android USB Host head unit and iPhone. Emulator results and manually starting `IphoneUsbAutoAttachActivity` do not verify Android's USB attach routing.

## Setup

1. Park the vehicle and install the exact mobile or automotive build under test. Debug package names are `com.shihab.diplay.hudtest` for mobile and `com.shilapi.xcertplay` for automotive.
2. Record the build commit, variant, head-unit model, Android version, and iPhone/iOS version.
3. Confirm USB Host sees the phone with `adb devices -l`, `adb shell getprop ro.build.version.sdk`, and `adb shell dumpsys usb`. Do not copy raw `dumpsys usb` output into a report; it can contain serial numbers.
4. In **Settings → Automatic connection**, enable **Auto-open on iPhone USB connection**. If the iPhone is already plugged in, use **Open CarPlay with the connected iPhone**.
5. Start log capture before connecting the phone:

   ```powershell
   adb logcat -c
   adb logcat -v threadtime | Select-String 'xcertplay-usb-auto|IphoneUsb|CarPlayController'
   ```

## Checks

1. **Setting off:** turn the option off, unplug and reconnect the iPhone, and confirm DiPlay does not open. The existing CH341 attach flow must still work.
2. **Cold app start:** enable the option, close DiPlay from Recents without force-stopping it, and physically connect the iPhone. Accept any Android USB chooser or USB permission prompt. Confirm the Host opens and the existing wired Controller starts once.
3. **Permission refusal:** deny Android's USB permission once. Confirm there is no crash or prompt loop; replugging or using the foreground connected-iPhone button must allow another attempt.
4. **Re-enumeration:** with the setting on, perform 20 physical unplug/replug cycles. Count Controller starts, duplicate sessions, crashes, and callbacks from an older connection.
5. **Existing sessions:** connect wired CarPlay and observe USB re-enumeration; then connect wirelessly and plug in USB. Confirm wired recovery stays with the existing Controller and wireless-to-wired switches once.
6. **Boot scan:** restart the head unit with the iPhone connected. If Android blocks background Activity launch, verify that the DiPlay notification opens the connected iPhone when tapped. Do not treat force-stop recovery as a supported case.
7. **Split state:** repeat a USB attach while editing DSP Split or using YouTube Split. Confirm the layout and current editing state survive the wired handoff.

An explicit `adb shell am start ...IphoneUsbAutoAttachActivity` is useful only for debugging the Activity. It does not prove that a physical USB attach launches the entry. The entry requires Android's system `MANAGE_USB` permission as a caller restriction; the app does not request or grant that privileged permission to itself. Respect Android's chooser, USB permission, background-start, and OEM restrictions.

## Result record

Record one line per test run. Never include serial numbers, UDIDs, MAC addresses, Apple IDs, or raw USB descriptors.

```text
USB_AUTO_ATTACH_V1
app_build: <commit + variant>
android_sdk: <number>
setting_enabled: <true/false>
source: PHYSICAL_ATTACH / BOOT_SCAN / FOREGROUND_SCAN
apple_device_present: <true/false>
permission_granted: <true/false>
entry_activity_started: <true/false>
host_opened: <true/false>
controller_started_count: <number>
wired_connected: <true/false>
reenumeration_count: <number>
unique_owner_violation_count: <number>
old_callback_misfire_count: <number>
result: PASS / FAIL / NOT_RUN_HARDWARE
```
