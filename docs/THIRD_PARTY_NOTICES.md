# Credits and license notices

## Receiver

DiPlay is a modified version of [xcertplay by shilapi](https://github.com/shilapi/xcertplay). The upstream receiver is licensed under GNU GPL version 3; the full text is in `LICENSE` and the original README is retained in `docs/UPSTREAM-README.md`.

Upstream credits [LIVI](https://github.com/f-io/LIVI) and [Showcase](https://github.com/amineross/showcase) for protocol research. Existing source comments and attribution are preserved.

## Home and settings UI

`common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt` and `common/src/main/java/com/shilapi/xcertplay/DiPlayMenuActivity.kt` adapt the palette, visual arrangement and interface copy of the [DiAuto project](https://github.com/shihabal3amri/DiAuto). DiAuto's source is licensed under AGPL version 3. Both UI files are marked AGPL-3.0-only; its license text is included in `docs/licenses/DiAuto-AGPL-3.0.txt`.

## CarPlay icon

The unmodified icon was obtained from Apple's developer site at:

https://developer.apple.com/assets/elements/icons/carplay/carplay-96x96_2x.png

CarPlay and the CarPlay icon are Apple Inc. marks/assets. This asset is not covered by the project's open-source code license. Its use here does not imply Apple approval or certification.

## Runtime dependencies

- GeckoView 156.0.20260921121718 — Mozilla; Mozilla Public License 2.0. The matching source revision identified by the resolved AAR metadata is [mozilla-release revision `6f2c158dfc7e9693f880fad2510ceb51a158c069`](https://hg.mozilla.org/releases/mozilla-release/rev/6f2c158dfc7e9693f880fad2510ceb51a158c069). Full text: `docs/licenses/dependencies/MPL-2.0.txt`.
- AndroidX, Jetpack Compose and AndroidX Media3 1.11.0 — Android Open Source Project; Apache License 2.0.
- Kotlin standard library — JetBrains; Apache License 2.0.
- Google Play services FIDO 21.3.1 and its Play services runtime dependencies — Android Software Development Kit License; [terms](https://developer.android.com/studio/terms.html).
- SnakeYAML 2.2 — Apache Software Foundation; Apache License 2.0.
- Bouncy Castle 1.79 — The Legion of the Bouncy Castle Inc.; Bouncy Castle license (MIT-style).
- JmDNS 3.6.3 — JmDNS contributors; Apache License 2.0.
- SLF4J — QOS.ch; MIT license.
- KissFFT minimal real-FFT sources (`shared/src/main/jni/third_party/kissfft`) — Mark Borgerding; BSD-3-Clause. Vendored from upstream revision [`e5e3fac46e0d94a8f8170c06706b7a4218828333`](https://github.com/mborgerding/kissfft/tree/e5e3fac46e0d94a8f8170c06706b7a4218828333). Copyright/redistribution notice: `docs/licenses/dependencies/KissFFT-COPYING.txt`; full license terms: `docs/licenses/dependencies/KissFFT-BSD-3-Clause.txt`.

Gradle dependency declarations and version catalog accompany the source. License files available in the resolved artifacts are included under `docs/licenses/dependencies/`.

## Experimental authentication data

The public preview APK includes an accessory certificate/key pair recovered from public Carlinkit C2Air Allwinner V821 firmware during the owner's local investigation. These data are not newly generated Apple-issued credentials for DiPlay and are not relicensed as project source code. They are bundled in the preview APK to reproduce the offline experiment; continued acceptance and suitability for general distribution are unresolved. The source archive does not contain the private key, and the separate Android APK-signing key is never distributed.
