# WebRTC stack for the remote monitor: research (Phase 0)

Status: research only. No code or dependency has been added. Written 2026-10-16 to follow up on decision D9
(`docs/PROGRESS.md`) and the risk "Builds reproducibles con librerías nativas (WebRTC)" (`docs/05-riesgos.md`).

Legend: **[V]** means verified for this document (Maven Central metadata, AAR contents inspected, fdroiddata recipe
read, or upstream source read). **[U]** means not verified: an estimate, general knowledge, or a claim I could not
confirm. Everything marked [U] must be checked by the Phase 0 spike before it is relied on.

## 1. Requirements recap

From `docs/00`, `docs/01` ("Multicámara y red") and `docs/02` (REQ-NET-003/004, REQ-NFR-003):

- Camera phone to monitor phone on a local network: Wi-Fi router, phone hotspot, later Wi-Fi Direct. No internet,
  no STUN/TURN.
- Live proxy video (720p/1080p H.264 or HEVC from a GPU surface, not the recording), the camera's audio, and a
  reliable data channel for control (REC, exposure/focus). File/proxy transfer comes later.
- WebRTC is the transport named in the docs.
- Hard constraints: F-Droid main repo plus GitHub, no telemetry, no proprietary libraries, no Google Play Services,
  reproducible builds, FLOSS licence.
- Performance on modest phones: hardware encoders through MediaCodec, zero-copy GPU surfaces, and no harm to the
  main recording (REQ-NFR-001, zero drops).

## 2. Summary table

APK impact is the size of the uncompressed native library for one ABI. AGP stores `.so` files uncompressed and
page-aligned by default, so this is close to what one per-ABI APK grows by.

| Option | Licence | F-Droid main repo | APK impact (arm64) | Feeds HW encoder / GPU surface | LAN latency (expected) | Hotspot / Wi-Fi Direct | Maintenance | Effort |
|---|---|---|---|---|---|---|---|---|
| **A1. Prebuilt libwebrtc AAR from Maven Central: `im.conversations.webrtc:webrtc-android` 149.0.0** | BSD-3 (libwebrtc) + third-party BSD/MIT-style; wrapper Apache-2.0/MIT [V] | Yes. Policy allows prebuilt FLOSS binaries from Maven Central [V]; used unmodified by Conversations and Meshenger [V] | 9.0 MB (armv7 4.9 MB) [V] | Yes: `VideoFrame` with `TextureBuffer` → `HardwareVideoEncoderFactory` (shared EGL context, MediaCodec input surface). H.264 and H.265 present in the Java API and native lib [V]. Using our own MediaCodec needs a custom `VideoEncoder` [U] | ~80–200 ms g2g; lower with zero playout delay [U] | Wi-Fi router OK. **Stock libwebrtc does not see tethered interfaces (own hotspot)** (Meshenger patch, 2024) [V]; workaround untested [U]. Wi-Fi Direct delegate is in the lib [V] | Vanilla build, bumped to m149 on 2026-09-16 [V]; one maintainer (Daniel Gultsch) | Low |
| A2. `org.jitsi:webrtc` 155.0.0 | BSD-3 + Apache-2.0/MIT wrapper [V] | Yes. Jitsi Meet recipe says the WebRTC binaries are "deemed acceptable" [V] | 12.5 MB [V] | Same as A1 | Same as A1 | Same as A1 | Updated 2026-10-13 [V]; built for react-native-webrtc | Low |
| A3. `ch.threema:webrtc-android` 144.0.0 | BSD-3 + Apache-2.0/MIT [V] | Yes. Used by Threema Libre (recipe rebuilds Rust from source but takes WebRTC from Maven) [V] | ~9 MB [U, AAR 16 MB total] | Same as A1 | Same as A1 | Same as A1. Threema patches change mDNS resolution, DTLS/SRTP ciphers, codecs [V] | 2026-01-08 [V]; patched for Threema, "no support outside Threema" [V] | Low |
| A4. `io.github.webrtc-sdk:android` 150.7871.01 (LiveKit) | BSD-3 [V] | Probably yes (Maven Central, FLOSS); no F-Droid example found [U] | 7.7 MB (`android-prefixed-stripped`) [V] | Same as A1 | Same as A1 | Same as A1 | 2026-09-05 [V]; LiveKit patches | Low |
| A5. `io.getstream:stream-webrtc-android` 1.3.10 | Apache-2.0 (POM) [V] | Probably yes [U] | 11.5 MB [V] | Same as A1 | Same as A1 | Same as A1 | Last release 2025-09-11, more than a year old [V] | Low |
| A6. `com.infobip:google-webrtc` 1.0.48246t | Apache-2.0 (POM) [V] | Probably yes [U] | 15.5 MB AAR in total [V]; .so not inspected | Same as A1 | Same as A1 | Same as A1 | 2026-07-29 [V]; upstream base not documented [U] | Low |
| — `org.webrtc:google-webrtc` 1.0.32006 | BSD-3 | Was used by older Conversations/monocles builds [V] | — | — | — | — | **Not on Maven Central** [V]; JCenter only, frozen in 2020. Do not use | — |
| B. Build libwebrtc from source ourselves (depot_tools, gn/ninja, `build_aar.py`) | BSD-3 + third-party | Possible, but no F-Droid recipe currently builds it [V]. Needs >20 GB download, ~30 GB build dir, ~1 h on 8–22 cores [V, F-Droid admin#364] | Like A1, can be trimmed with gn args | Same as A1, plus our own patches (for example tethering) | Same as A1 | Can carry the Meshenger tethering patch | We would own every security update | High, ongoing |
| C. libdatachannel (C++17) built with the NDK/CMake in our Gradle build | MPL-2.0 (file-level copyleft, compatible) [V] | Yes: plain CMake + NDK, built from source by F-Droid; no special recipe [U]. No F-Droid app found using it [V] | ~9–10 MB with OpenSSL 3 (estimate from non-debug sections of `tel.schich:libdatachannel-java-android`) [V]; a few MB with Mbed TLS and media-only [U] | **Best fit for zero-copy**: sends already-encoded frames. Our MediaCodec output NALUs go straight to H264/H265 packetizers; Opus packetizer; RTCP SR/NACK [V] | Lowest of the WebRTC options: no jitter buffer unless we write one [U] | Uses `getifaddrs` via libjuice, so the hotspot interface should appear [U]. mDNS candidates supported [V] | Active: v0.24.6, 2026-09-26 [V] | Medium-high: JNI, receive path (depacketize → MediaCodec decode), A/V sync, rate control |
| D. Pion (Go) via gomobile | MIT | Allowed (Go toolchain allowed; several gomobile apps in fdroiddata) [V] | ~10+ MB Go runtime + Pion [U] | Sends pre-encoded samples (`TrackLocalStaticSample`) [U]; H.265 support [U] | Low, similar to C [U] | `net.Interfaces()` fails on Android 11+ (`netlinkrib: permission denied`), needs the `anet` workaround [V] | Very active: v4.2.24, 2026-10-16 [V] | Medium-high, plus Go GC on the camera phone |
| E. webrtc-rs (Rust) | MIT/Apache-2.0 | Allowed (Rust toolchain allowed) [V] | [U] | Pre-encoded samples [U] | [U] | [U] | v0.21.1, 2026-10-11 [V]; less proven on Android [U] | High |
| F. GStreamer `webrtcbin` | LGPL-2.1 | Impractical: Android binaries come from Cerbero; F-Droid would need the whole Cerbero build from source. No F-Droid app found using it on Android [V] | +20–40 MB [U] | Yes (`amcvidenc`) [U] | Low [U] | [U] | Active | Very high. Rejected |
| G. Not WebRTC: RTP/UDP or SRT (libsrt MPL-2.0) + TCP/QUIC control | — | Easy | <1–3 MB | Native fit (send MediaCodec output) | Lowest [U] | Plain sockets, works on any interface [U] | Ours | Medium; **deviates from docs (owner approval)** |

## 3. Findings in detail

### 3.1 Prebuilt libwebrtc AARs and F-Droid

**F-Droid policy [V].** The Inclusion Policy says: "Applications can download prebuilt FLOSS binaries with specific
conditions from trusted Maven repositories. Those include Maven Central, Google Maven, OSS Sonatype, OSS JFrog,
JitPack.io and Clojars. Those binaries must still be freely licensed". So a Maven Central libwebrtc AAR is allowed.
What the build bot rejects is a binary committed to *our* source tree: Meshenger's committed `libwebrtc-123.0.0.aar`
failed the F-Droid scanner (forum thread, July 2024), and Meshenger went back to a Maven artifact.

**How existing F-Droid apps ship WebRTC (local fdroiddata clone at commit `f6a8fd17`, 2026-10-03) [V]:**

| App (fdroiddata id) | WebRTC source | Recipe handling |
|---|---|---|
| Conversations (`eu.siacs.conversations`), 2.20.4 | `im.conversations.webrtc:webrtc-android:149.0.0` (upstream `build.gradle` line 74) | Nothing special: no srclibs, no scandelete for WebRTC. Builds up to 2.10.x used `sed` to swap in `org.webrtc:google-webrtc:1.0.32006` |
| Meshenger (`d.d.meshenger`), 4.4.8: LAN-only P2P calls, the closest analogue to our use case | `im.conversations.webrtc:webrtc-android:119.0.0` | Nothing special |
| Threema Libre (`ch.threema.app.libre`) | `ch.threema:webrtc-android:144.0.0` (upstream `libs.versions.toml`) | Rebuilds Rust from source and removes `app/libs/*`, but takes WebRTC from Maven Central |
| Jitsi Meet (`org.jitsi.meet`) | `org.jitsi:webrtc` through react-native-webrtc | `MaintainerNotes`: "3 directories are currently (scan)ignored as they contain binaries which are currently deemed acceptable … WebRTC" |
| Element classic (`im.vector.app`) | Through the Jitsi Meet SDK srclib (`jitsi-meet@mobile-sdk-11.5.1`) | Builds the Jitsi SDK from source; WebRTC itself is still a Maven binary [U] |
| monocles, Snikket, Quicksy, Pix-Art, Cheogram | Old builds swapped in `org.webrtc:google-webrtc:1.0.30039/1.0.32006`; current Cheogram builds have no WebRTC handling | `sed` in `prebuild` |
| ScreenStream (`info.dvkr.screenstream`) | Upstream WebRTC module removed for F-Droid (`scandelete: webrtc`, `sed '/:webrtc/d'`) | Removed because of Firebase/online signalling in that module [U on the exact reason], not because of libwebrtc |

No recipe in fdroiddata runs `depot_tools`/`gclient` to build libwebrtc. F-Droid volunteers tried it (fdroid/admin#364,
"Freed By F-Droid"): it builds and is repeatable, but the first sync downloads more than 20 GB, the build directory
grows to ~30 GB and it takes ~1 h on 8–22 cores. They would rather publish the artifact to Maven than build it per app.

**Maven Central artifacts (metadata read on 2026-10-16) [V]:**

| Artifact | Latest | Last updated | arm64-v8a .so | armeabi-v7a .so | Notes |
|---|---|---|---|---|---|
| `im.conversations.webrtc:webrtc-android` | 149.0.0 | 2026-09-09 | 9,042,472 B | 4,895,456 B | "A vanilla WebRTC m149 build". Built with Threema's docker build scripts, without Threema's patches. Stripped, 16 KB-aligned LOAD segments, NDK r28. NEEDED only libEGL/libdl/libm/liblog/libc. Java API in `libs/libwebrtc.jar` (982 KB) |
| `org.jitsi:webrtc` | 155.0.0 | 2026-10-13 | 12,551,208 B | 7,069,012 B | "WebRTC mirror for building react-native-webrtc" |
| `ch.threema:webrtc-android` | 144.0.0 | 2026-01-08 | not inspected | not inspected | Threema patches (DTLS/SRTP ciphers, disabled codecs, `only-resolve-uuid-mdns-hostnames`, group-call frame crypto). PGP signing key listed as expiring 2026-09-04 |
| `io.github.webrtc-sdk:android` (+ `-prefixed`, `-prefixed-stripped`) | 150.7871.01 | 2026-09-05 | 7,707,408 B (prefixed-stripped) | 4,489,444 B | LiveKit's fork; the full `android` AAR is 49 MB (unstripped) |
| `io.getstream:stream-webrtc-android` | 1.3.10 | 2025-09-11 | 11,541,096 B | 6,574,072 B | Ships its own Java/Kotlin extras (527 KB classes.jar) |
| `com.infobip:google-webrtc` | 1.0.48246t | 2026-07-29 | — | — | 15.5 MB AAR in total; base milestone unknown |
| `io.github.100mslive:webrtc` | m111-hms-1.9 | 2024-04-18 | — | — | Stale |
| `org.webrtc:google-webrtc` | — | — | — | — | Not on Maven Central (JCenter only, 2020) |

All AARs also ship x86 and x86_64 libraries. We would drop those with `abiFilters`, or split per ABI for F-Droid.

**Codec and latency features in the m149 AAR [V]:** `VideoCodecMimeType` has `H265("video/hevc")`, and the
`HardwareVideoEncoderFactory` class references H265. Upstream `main` lists VP8, VP9, H264, AV1 and H265 for hardware
encoding. The native library contains the strings for the `playout-delay` and `abs-capture-time` RTP header extensions
and for the field trials `WebRTC-ForcePlayoutDelay` and `WebRTC-ZeroPlayoutDelay`. That these trials give low
latency on Android receivers is [U]. `PeerConnectionFactory.Options` has `disableNetworkMonitor` and
`networkIgnoreMask`. `RTCConfiguration` has `enableCpuOveruseDetection` (default true) and
`candidateNetworkPolicy`.

**Licence [V]:** the POMs declare Apache-2.0/MIT (the wrappers) or BSD-3. libwebrtc itself is BSD-3-Clause with
third-party code (BoringSSL, libyuv, opus, libvpx, libsrtp, usrsctp/dcsctp, abseil), all FLOSS. We must ship the
third-party notices; libwebrtc's `LICENSE` and the per-dependency licence files from the matching milestone can
produce them [U on the exact generation tooling].

### 3.2 Building libwebrtc from source

- Toolchain: `depot_tools` → `fetch --nohooks webrtc_android` → `gclient sync` → checkout `branch-heads/<N>` →
  `tools_webrtc/android/build_aar.py` (or `gn gen` + `ninja` per ABI with args such as `is_debug=false
  rtc_include_tests=false symbol_level=0 rtc_use_h265=true`) [V for the steps, U for the exact args per milestone].
- Cost: >20 GB first download, ~30 GB working tree, ~1 h on 8–22 cores [V, fdroid/admin#364]. That does not fit our
  GitHub CI per PR. It would be a separate, manually triggered workflow that publishes to Maven Central under our
  namespace, which brings Sonatype credentials and PGP keys.
- Reproducibility: Chromium's toolchain aims at deterministic builds, and the F-Droid volunteer reports "the same
  output of the artefacts" across rebuilds [V]. But `gclient sync` pulls Google-hosted prebuilt toolchains (clang,
  gn, the Android SDK and NDK snapshots), which F-Droid would see as non-Debian binaries [U how strictly that is
  enforced].
- How F-Droid apps do it: none of them build it inside the recipe today [V]. Meshenger built a patched AAR with
  Threema's `webrtc-build-docker`, was rejected for committing it, and moved back to Maven [V].
- Conclusion: only worth it if we **must** patch libwebrtc (for example the tethering patch, §3.5). Even then,
  publishing a patched build to Maven Central from a separate repo (like Threema and Conversations do) beats building
  it in our app's CI.

### 3.3 Lighter stacks

- **libdatachannel** (Paul-Louis Ageneau), MPL-2.0, v0.24.6 (2026-09-26) [V]. ICE via libjuice (or libnice), SCTP
  via usrsctp, SRTP via libsrtp, TLS via OpenSSL, GnuTLS or Mbed TLS [V]. Packetizers for H264, H265, AV1, VP8, VP9,
  Opus, AAC, PCMU/PCMA, G722; chainable RTCP SR reporter and NACK responder [V, project docs]. Works with Firefox,
  Chromium and Safari, so it is real WebRTC on the wire [V]. mDNS candidates supported [V]. Android is a listed platform
  [V]. A third-party JNI binding exists (`tel.schich:libdatachannel-java-android` 0.24.1.1, MIT binding), but its `.so`
  files are 30 MB unstripped with debug info, 4 KB-aligned and bundle OpenSSL 3.6.0 [V]. We would build our own trimmed
  library with the NDK through Gradle `externalNativeBuild` instead. The sender is ideal for us: our proxy MediaCodec
  output buffers (Annex-B NALUs) go straight to `track.sendFrame()`, with no second encoder or texture hand-off. The
  receiver is our job: depacketize, then MediaCodec decode with `KEY_LOW_LATENCY` (API 30+, so fine with minSdk 33),
  then render to a `SurfaceView`, plus audio decode and A/V sync. There is no bandwidth estimation (BWE); we would
  adapt the bitrate from RTCP receiver reports ourselves.
- **Pion** (Go, MIT, v4.2.24 2026-10-16) [V]. Mature, sends pre-encoded samples. On Android it needs gomobile, a Go
  runtime in the camera process, and the `anet` workaround because `net.Interfaces()` fails on Android 11+ [V]. It
  adds a second runtime with its own GC on the phone whose recording must not drop frames. Not preferred.
- **webrtc-rs** (MIT/Apache-2.0, v0.21.1 2026-10-11) [V]. The Rust toolchain is fine for F-Droid (Threema Libre
  already builds Rust). Less proven on Android than libwebrtc or libdatachannel [U]. Not preferred.
- **GStreamer webrtcbin**: LGPL; the Android SDK comes as Cerbero prebuilt binaries, and building those from source in
  an F-Droid recipe would be huge. Rejected.

### 3.4 A non-WebRTC LAN design

Proxy H.264/HEVC from MediaCodec → RTP over UDP (RFC 6184/7798 packetization, ~300 lines) or SRT (libsrt,
MPL-2.0, AES optional). Control over TCP, or QUIC (Cronet is Google, so a FLOSS QUIC library would be needed, which
is not attractive). The monitor decodes with low-latency MediaCodec.

- Pros: smallest APK, lowest latency, full control over buffering, works on any interface (hotspot, Wi-Fi Direct),
  no DTLS/ICE handshake.
- Cons: we must write encryption and authentication ourselves (pairing key from the QR, AEAD per packet), loss
  recovery (NACK/FEC), congestion handling, and A/V sync. No browser interop. **It is a deviation from
  `docs/00`/`docs/01` ("Transporte: WebRTC en red local") and needs the owner's approval.**
- Justified only if the spike shows libwebrtc cannot meet the latency target, or costs too much CPU/thermal headroom on
  modest phones while they record. libdatachannel (option C) sits in between: it keeps standard WebRTC on the wire
  (DTLS-SRTP, ICE, SCTP data channel) with the "send our own encoded frames" model, so it would not deviate from the
  docs.

### 3.5 Hotspot, Wi-Fi Direct, mDNS (all options)

- No STUN/TURN: `iceServers = []`; host candidates only. Signalling is ours: QR pairing (key + DTLS fingerprint +
  addresses) and DNS-SD via `NsdManager` (docs/01). After the first offer/answer, the data channel carries
  renegotiation and control.
- mDNS-obfuscated host candidates (`.local`) are a browser privacy feature. Between our own two apps we can exchange
  plain IP host candidates, so we do not depend on mDNS candidate resolution. Threema's build even restricts it with
  `only-resolve-uuid-mdns-hostnames` [V].
- **Own-hotspot risk [V]:** Meshenger (LAN calls) had to patch libwebrtc's `NetworkMonitorAutoDetect`
  (`detect-tethered-networks-on-android.patch`, from discuss-webrtc) because stock libwebrtc ignores tethered
  interfaces (Android hotspot, USB Ethernet). The phone *hosting* the hotspot then gathers no usable candidate. The
  m149 Java code has no tethering handling (no `tether`/`ap0`/`swlan` strings), but it does have a
  `WifiDirectManagerDelegate` [V]. Mitigations to test in the spike, in order:
  1. Phone acting as hotspot client works like normal Wi-Fi [U]; make the **monitor** the hotspot host and the
     camera the client, or the reverse, and measure both.
  2. `PeerConnectionFactory.Options.disableNetworkMonitor = true`, so native code enumerates interfaces with
     `getifaddrs` and may see the hotspot interface [U].
  3. A patched build published to Maven (option B), the last resort.
- Wi-Fi Direct: libwebrtc has a delegate for the P2P group interface [V]; whether it is active without a field trial
  is [U]. Phase 6 work.

### 3.6 Feeding the encoder with zero copy (libwebrtc)

- Path 1 (simplest): `core-gpu` renders the proxy (LUT, downscale) into a texture on a context shared with
  `EglBase14`. Wrap it as `TextureBufferImpl` → `VideoFrame` → `VideoSource.getCapturerObserver().onFrameCaptured()`.
  `HardwareVideoEncoderFactory(sharedContext, …)` then draws that texture into the MediaCodec input surface: a GPU
  blit, no CPU copy [V for the API shape, U for the cost on our phones].
- Path 2 (full control, one encoder): implement `org.webrtc.VideoEncoder`/`VideoEncoderFactory` around our own
  `core-codec` proxy MediaCodec. It emits `EncodedImage` from our output buffers and maps `setRates()` to
  `PARAMETER_KEY_VIDEO_BITRATE`, and `requestKeyFrame` to `PARAMETER_KEY_REQUEST_SYNC_FRAME` [U: not tried, but the
  Java interface allows it].
- Turn off `enableCpuOveruseDetection` and set the degradation preference so libwebrtc does not rescale behind our
  back.
- Either way, the camera phone runs **two hardware encoder sessions** (recording + proxy). Some SoCs limit concurrent
  instances or total macroblocks per second (`MediaCodecInfo.CodecCapabilities.getMaxSupportedInstances()`,
  `VideoCapabilities.getSupportedPerformancePoints()`). This must be measured on the modest phones together with the
  main recording [U].
- Audio: libwebrtc's `JavaAudioDeviceModule` opens its own `AudioRecord`. That can clash with `core-audio`, which
  picks the USB/BT source and writes WAV. Options: `setAudioSource`/preferred input device on the ADM [U], a custom
  native ADM fed from `core-audio` (more work), or Opus frames over a separate track/data channel. Open question
  (§6).

## 4. Recommendation

**Use upstream libwebrtc through the prebuilt Maven Central AAR `im.conversations.webrtc:webrtc-android`, pinned to
an exact version (149.0.0 today), checksum-pinned with Gradle dependency verification, and wrapped behind an interface
in `core-net`. Keep `org.jitsi:webrtc` as the drop-in alternative and libdatachannel as plan B.**

Why this is the most conservative choice:

1. **F-Droid**: allowed by policy (prebuilt FLOSS from Maven Central) and proven in practice: Conversations and
   Meshenger ship this exact artifact family in the main repo with no recipe tricks. No srclibs, no scanignore, no
   20 GB build.
2. **Reproducible builds**: our APK is reproducible because the AAR is identical bytes on every build. Pin the
   version, add `gradle/verification-metadata.xml` (SHA-256), never use dynamic versions.
3. **Docs**: real WebRTC (DTLS-SRTP, ICE, SCTP data channel), as `docs/00`/`docs/01` require. No deviation to
   approve.
4. **Performance**: vanilla build (no Threema patches that alter codecs or ciphers), hardware H.264/H.265 through
   MediaCodec with texture input from our GL pipeline, 16 KB-aligned (future-proof), 9 MB on arm64.
5. **Maintenance**: tracks upstream milestones; swapping to `org.jitsi:webrtc` is a one-line change if Conversations'
   build lags. The `org.webrtc.*` package is the same.
6. **No telemetry, no GMS**: libwebrtc has neither [U for a full audit; nothing in the NEEDED list or the Java API
   points to GMS].

When to fall back:
- **To libdatachannel (option C)** if the spike shows libwebrtc's jitter buffer and pacing cannot reach the latency
  target even with zero playout delay, or its texture/encoder path costs drops in the main recording on a modest
  phone. Still WebRTC on the wire, still F-Droid-friendly (built from source with CMake).
- **To a self-published patched libwebrtc (option B)** only if the own-hotspot problem has no workaround and the
  topology cannot avoid it.
- **To raw RTP/SRT (option G)** only with the owner's explicit approval (deviation from the docs).

## 5. Phase 0 latency spike with the recommended stack

Goal: replace D9's UDP RTT baseline with glass-to-glass (g2g) numbers for the monitor link, and check the hotspot
risk. Results go to `docs/spike-report.md` (network section) and a new decision entry in `docs/PROGRESS.md`.

Scope, kept small:
- **`core-net`**: `MonitorLink` interface (sender/receiver, `sendControl()`, stats flow) with a `LibWebRtcMonitorLink`
  implementation. Signalling for the spike: a plain TCP socket on a fixed port, IP from a QR code or typed in. It
  reuses the existing `UdpLatencyProbe` peer setup; no NsdManager or crypto pairing yet. `iceServers` empty;
  `disableNetworkMonitor` switchable from the UI (hotspot test).
- **`core-gpu`**: a second render target for the proxy (720p30 / 1080p30). It overlays (a) a big frame counter and
  sender timestamp for humans, and (b) a 64-bit binary strip (black/white blocks, top-left) holding the capture time
  in µs, readable by machine.
- **`feature-spike`**: a "Monitor link" screen with roles Camera and Monitor, a codec choice (H.264/HEVC), a bitrate
  choice, and live stats.

Measurements:
1. **Automatic per-frame latency.** The monitor reads the binary strip from the decoded frame (`glReadPixels` of a
   tiny region at render time; no full-frame copy). Latency = render time − capture time − clock offset. The clock
   offset comes from an NTP-style ping over the data channel (Cristian's algorithm, best-of-N). Error ≈ ±RTT/2,
   reported next to it. This also gives the control-channel RTT, which feeds REQ "REC simultáneo con compensación
   de latencia medida".
2. **True g2g (optical) check**, a few runs per configuration. The monitor phone shows a running millisecond clock
   next to the received video. The camera phone films that clock. A screenshot or `screenrecord` of the monitor shows
   both the live clock and the older clock inside the received video; the difference is g2g, including sensor
   exposure, ISP, display. Cross-check with a 240 fps slow-motion video from a third device when available.
3. **libwebrtc `getStats()`** every second: `jitterBufferDelay/jitterBufferEmittedCount`, `totalDecodeTime`,
   `framesDropped`, `framesPerSecond`, `roundTripTime`, `encoderImplementation`/`decoderImplementation` (confirms
   hardware `c2.*` codecs), `qualityLimitationReason`.
4. **Cost on the camera phone**: run the link while recording the base configuration (1080p30, then 4K60 where
   supported). Report main-recording drops (REQ-NFR-001), thermal status, CPU per process, and whether the second
   encoder session opens at all.

Matrix (each 60 s after a 5 s warm-up, consistent with D10):
- Networks: 5 GHz router; 2.4 GHz router; hotspot with the camera as host; hotspot with the monitor as host
  (each with `disableNetworkMonitor` off and on). Wi-Fi Direct is deferred to Phase 6.
- Video: 720p30 at 2 Mbps H.264; 1080p30 at 5 Mbps H.264; 1080p30 at 4 Mbps HEVC. Playout delay default vs.
  `WebRTC-ZeroPlayoutDelay`/`ForcePlayoutDelay` min 0.
- Devices: the OPPO first (D8 locking), then the others in `docs/TESTING.md` as hardware allows; one modest phone
  as camera is essential.

Proposed pass criteria (for owner approval, not yet in docs/02): g2g median ≤ 150 ms and p95 ≤ 250 ms at 1080p30
on 5 GHz Wi-Fi and on hotspot; control RTT p95 ≤ 50 ms; zero added drops in the main recording; the link comes back
by itself after Wi-Fi off/on within 5 s (REQ "Reconexión automática").

## 6. Open questions for the owner

1. **Accept prebuilt libwebrtc from Maven Central** (allowed by F-Droid, used by Conversations/Meshenger), or require
   building it from source? The recommendation assumes "accept".
2. **Which vendor**: Conversations' vanilla build (recommended), Jitsi (newest, m155), or Threema (patched)?
3. **ABIs and APK size budget**: arm64-v8a only, or armeabi-v7a too for very low-end phones? Each ABI adds
   ~5–9 MB for WebRTC alone.
4. **Browser interop**: should a laptop browser ever work as a monitor? If yes, standard WebRTC (libwebrtc or
   libdatachannel) becomes a hard requirement, and option G is out.
5. **Monitor audio source**: must the monitor hear the source chosen in `core-audio` (USB/BT mic)? That decides
   whether we need a custom audio device module.
6. **Hotspot topology**: which phone normally hosts the hotspot in the field: the camera or the monitor?
7. **Latency target**: are the proposed thresholds in §5 right for focus pulling and REC triggering?
8. **Fallbacks**: may we switch to libdatachannel without another review if the spike fails? Is raw RTP/SRT
   acceptable at all (deviation from docs/00)?

## 7. Things not verified

- Latency figures for every option (all [U]; the spike measures them).
- Whether `disableNetworkMonitor = true` makes libwebrtc see the own-hotspot interface on Android 13+.
- Whether the zero-playout-delay field trials behave as expected in m149 on Android.
- That a custom `VideoEncoder` around our own MediaCodec works with libwebrtc's rate control.
- That the m149 native library was built with H.265 RTP support enabled end-to-end. The strings are present; a real
  HEVC call was not tested.
- F-Droid acceptance of A4–A6 and option C (no existing example found; policy suggests yes).
- APK impact of Pion, webrtc-rs, GStreamer, and a trimmed libdatachannel build.
- Exact reason ScreenStream's WebRTC module is removed for F-Droid.
- Reproducibility of the Conversations AAR itself (we rely on it being byte-identical from Maven Central, which it is
  by construction; how upstream builds it is a trust question, not a reproducibility one).

## 8. Sources

F-Droid:
- F-Droid Inclusion Policy (prebuilt binaries from trusted Maven repos): https://f-droid.org/docs/Inclusion_Policy/
- fdroiddata (local clone, commit `f6a8fd17`, 2026-10-03): `metadata/eu.siacs.conversations.yml`,
  `metadata/d.d.meshenger.yml`, `metadata/ch.threema.app.libre.yml`, `metadata/org.jitsi.meet.yml`,
  `metadata/im.vector.app.yml`, `metadata/info.dvkr.screenstream.yml`, `metadata/de.monocles.chat.yml`,
  `metadata/org.snikket.android.yml`, `metadata/com.cheogram.android.yml`. Upstream: https://gitlab.com/fdroid/fdroiddata
- F-Droid admin issue "Freed By F-Droid - upload artefacts to maven" (libwebrtc build cost): https://gitlab.com/fdroid/admin/-/issues/364
- fdroiddata issue on WebRTC: https://gitlab.com/fdroid/fdroiddata/-/issues/2064
- F-Droid forum, "How to use WebRTC with a patch?" (Meshenger, committed AAR rejected): https://forum.f-droid.org/t/how-to-use-webrtc-with-a-patch/26752

Prebuilt AARs (Maven Central metadata, POMs and AARs fetched 2026-10-16):
- https://repo1.maven.org/maven2/im/conversations/webrtc/webrtc-android/ — source: https://codeberg.org/iNPUTmice/webrtc-android
- https://repo1.maven.org/maven2/org/jitsi/webrtc/ — source: https://github.com/jitsi/webrtc
- https://repo1.maven.org/maven2/ch/threema/webrtc-android/ — source: https://github.com/threema-ch/webrtc-android and https://github.com/threema-ch/webrtc-build-docker
- https://repo1.maven.org/maven2/io/github/webrtc-sdk/android/ — source: https://github.com/webrtc-sdk/android
- https://repo1.maven.org/maven2/io/getstream/stream-webrtc-android/ — source: https://github.com/GetStream/webrtc-android
- https://repo1.maven.org/maven2/com/infobip/google-webrtc/
- https://repo1.maven.org/maven2/tel/schich/libdatachannel-java-android/ — source: https://github.com/pschichtel/libdatachannel-java

Upstream code and apps:
- Conversations `build.gradle` (WebRTC dependency): https://codeberg.org/iNPUTmice/Conversations/src/branch/master/build.gradle
- Threema `gradle/libs.versions.toml`: https://github.com/threema-ch/threema-android/blob/main/gradle/libs.versions.toml
- Meshenger `app/build.gradle` and tethering patch commit: https://github.com/meshenger-app/meshenger-android/blob/master/app/build.gradle , https://github.com/meshenger-app/meshenger-android/commit/f2a8b33ecaf94baa898c91c495075d71a1e4cf9f
- libwebrtc Android SDK sources (main): https://webrtc.googlesource.com/src/+/refs/heads/main/sdk/android/api/org/webrtc/HardwareVideoEncoderFactory.java , https://webrtc.googlesource.com/src/+/refs/heads/main/sdk/android/src/java/org/webrtc/VideoCodecMimeType.java , https://webrtc.googlesource.com/src/+/refs/heads/main/sdk/android/api/org/webrtc/PeerConnection.java
- libwebrtc playout-delay extension: https://webrtc.googlesource.com/src/+/refs/heads/main/docs/native-code/rtp-hdrext/playout-delay/
- libdatachannel: https://github.com/paullouisageneau/libdatachannel (docs: `DOC.md`, release v0.24.6)
- Pion: https://github.com/pion/webrtc (release v4.2.24)
- Go on Android 11+ `netlinkrib: permission denied`: https://github.com/golang/go/pull/61089
- webrtc-rs: https://github.com/webrtc-rs/webrtc (release v0.21.1)
- Stack Overflow, WebRTC over the phone's own hotspot: https://stackoverflow.com/questions/40853791
