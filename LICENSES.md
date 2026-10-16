# Licences

UltimateCamera is free software under the GNU General Public License, version 3 or (at your option) any later version
(`GPL-3.0-or-later`, decision D23 in `docs/PROGRESS.md`). The full text is in [`LICENSE`](LICENSE).

## Third-party software in the APK

Only the runtime dependencies packaged in the release APK are listed (`./gradlew :app:dependencies --configuration
releaseRuntimeClasspath`); build tools and test libraries (JUnit, AndroidX Test, ktlint, detekt) are not distributed.
Every new runtime dependency must be compatible with the GPL and be added here in the same PR. Licences were read
from each artifact's POM in the Gradle cache (2026-10-16).

| Dependency | Version | Licence (from the POM) | Compatible with GPLv3 | Use |
|---|---|---|---|---|
| AndroidX: Compose (BOM), Material 3, Activity, Lifecycle, Core, SavedState, Navigation Event, Window, Emoji2, Graphics, Profile Installer, Startup, Tracing, Collection, Annotation and their transitive AndroidX modules | BOM 2026.09.00, Material 3 1.5.0-beta01, Activity 1.13.0, Lifecycle 2.11.0, Core 1.19.1 | Apache-2.0 | Yes | UI and app framework |
| Kotlin standard library (`org.jetbrains.kotlin`) | 2.4.21 | Apache-2.0 | Yes | All modules |
| kotlinx-coroutines (`core`, `android`) | 1.11.0 | Apache-2.0 | Yes | Camera, audio and network modules |
| kotlinx-serialization (`core`, `json`) | 1.11.0 | Apache-2.0 | Yes | Spike reports, capability catalogue |
| JetBrains annotations, JSpecify, Guava `listenablefuture` (transitive) | per AndroidX | Apache-2.0 | Yes | Annotations, `ListenableFuture` interface |
| AndroidX DataStore Preferences (includes a shaded protobuf-javalite, `datastore-preferences-external-protobuf`) | 1.2.1 | Apache-2.0; the shaded protobuf: BSD-3-Clause | Yes | `:core-settings`; not in the APK until `:app` depends on it |
| libwebrtc via `im.conversations.webrtc:webrtc-android` | 149.0.0 (WebRTC m149) | Wrapper: Apache-2.0 **or** MIT (the POM declares both). libwebrtc itself: BSD-3-Clause, with the WebRTC patent grant, plus the third-party code listed below | Yes | `:core-net`, remote monitor (D19) |

### Third-party code inside libwebrtc

The AAR ships no licence or notice files (checked: `classes.jar`, `libs/libwebrtc.jar` and `jni/` contain none), so
the components were identified from the native library itself (`strings` on
`jni/arm64-v8a/libjingle_peerconnection_so.so`: source paths and symbol names). Their licences are those published
upstream in the WebRTC m149 tree (`third_party/*/LICENSE`, https://webrtc.googlesource.com/src); the texts must be
shipped with the APK (an in-app licences screen is pending, Phase 1).

| Component | Evidence in the library | Upstream licence |
|---|---|---|
| WebRTC (incl. dcSCTP) | the whole library | BSD-3-Clause + PATENTS grant |
| BoringSSL | `third_party/boringssl`, `BoringSSLCertificate` | Apache-2.0 (older files ISC / OpenSSL-SSLeay) |
| Opus | `third_party/opus` | BSD-3-Clause |
| libvpx (VP8/VP9) | `LibvpxVp8Encoder`, `LibvpxVp9Decoder` | BSD-3-Clause + PATENTS grant |
| libyuv | `libyuv::I420Rotate` | BSD-3-Clause |
| libsrtp | `libsrtp log handler` | BSD-3-Clause |
| Abseil | `third_party/abseil-cpp` | Apache-2.0 |
| LLVM libc++ / libc++abi | `third_party/libc++abi` | Apache-2.0 WITH LLVM-exception |
| jni_zero (Chromium) | `third_party/jni_zero` | BSD-3-Clause |

Upstream licences are not stated by the artifact itself; they must be re-checked against the m149 sources when the
licence screen is built or libwebrtc is bumped.
