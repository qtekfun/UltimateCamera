# Progress

Living status of the project (prompt rule 2). Updated at the start and end of every phase and milestone.

## Current phase: 0 — spike on real devices (in progress)

### Done
- Repository bootstrap: Gradle 9.8.1 (wrapper checksum pinned), AGP 9.4.1, Kotlin 2.4.21, Compose BOM 2026.09.00,
  Material 3 1.5.0-beta01 (Expressive), minSdk 33, JDK 21 toolchain / Java 17 bytecode.
- Release signing: `~/keys/ultimatecamera/keystore.properties` locally, `KEYSTORE_PATH` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` /
  `KEY_PASSWORD` in CI; with neither, release tasks fail with a clear message (verified locally in three cases: none,
  incomplete env, throwaway key via env). Never falls back to the debug key.
- CI (`ci.yml`): ktlint, detekt, unit tests, Android lint, debug APK, and a check that an unsigned release build is refused.
- Release (`release.yml`): tag `v<appVersion>` → tests → signed APK → signature check (rejects debug certificates) →
  GitHub pre-release with the APK and `.sha256`.

- Phase 0 spike app (`feature-spike` + core modules), to be released as `v0.0.2-fase0` (see D18):
  - `core-gpu`: `.cube` parser/writer, CPU LUT reference pinned to PNG references, EGL (exact 8888/1010102 configs,
    recordable, BT.2020 HLG tagging), LUT shader with hardware or manual trilinear interpolation, GPU info probe.
  - `core-codec`: encoder capability probe (HEVC Main10, AV1, performance points), async surface encoder → MP4.
  - `core-camera`: capability dump incl. vendor tags, concurrent camera probe with real streaming, Camera2 coroutines.
  - `core-audio`: input listing, USB > BT > internal ordering, preferred/communication routing test, WAV writer, meter.
  - `core-net`: UDP latency probe + stats; `core-storage`: Downloads export via MediaStore.
  - Automation: DUMP-protected entry point + `scripts/spike/run-spike.sh`; on-device LUT shader test.
- Multi-language groundwork: every UI string in `values` + `values-es`, per-app language (`generateLocaleConfig`),
  `MissingTranslation` is a lint error (checked: an untranslated string fails lint).

### Pending (Phase 0 acceptance)
- Measurements on real phones → `docs/spike-report.md` per model (OPPO, vivo, Xiaomi, Honor, Pixel), then the
  documented decision on 4K60 as base target and which phones meet it. **Blocked on hardware**: see docs/TESTING.md.
- WebRTC monitor latency (D9, D19): implemented (core-net `MonitorLink`, core-gpu `FrameStamp`, spike roles *Be the
  camera* / *Be the monitor*); measure on Wi-Fi router and hotspot in both directions — **needs two phones**.

### Decisions (not covered by docs/, conservative choice taken)
| # | Date | Decision | Reason |
|---|---|---|---|
| D1 | 2026-10-16 | The only direct commit to `main` is the initial spec commit; everything else goes through PRs. | The GitHub repo was empty and a PR needs a base branch. |
| D2 | 2026-10-16 | Kept the existing GitHub repo `qtekfun/UltimateCamera`, which is public (docs/06 suggested `--private`). | Changing visibility is the owner's call; F-Droid needs public sources anyway. No secrets are in the repo. |
| D3 | 2026-10-16 | `compileSdk 37`, `targetSdk 36`. | Same as the sibling apps; target 36 is the behaviour already validated on the OPPO. |
| D4 | 2026-10-16 | Version lives in `gradle.properties` (`appVersion`); the release tag must equal `v<appVersion>` or the release fails. `versionCode = MAJOR*1_000_000 + MINOR*1_000 + PATCH`. | "Version derived from the tag" (docs/04) while keeping the source reproducible for F-Droid (the version must be in the source). The code always grows with phase and patch. |
| D5 | 2026-10-16 | Debug builds use `applicationIdSuffix .debug`. | Debug (debug key) and signed release can coexist on a test phone; `adb install -r` of releases keeps working. |
| D6 | 2026-10-16 | Dark dynamic-colour theme only, for now. | Field-monitor theme (docs/01); a light UI around the viewfinder distorts exposure judgement. Revisit in Phase 1 settings. |
| D7 | 2026-10-16 | PR CI never sees signing secrets; it checks that an unsigned release is refused. Only `release.yml` (tags) signs. | Least exposure of the signing key. |
| D8 | 2026-10-16 | Device work uses `flock /tmp/pixel-device.lock` and explicit `adb -s <serial>`. | The OPPO is shared with other projects' agents (same convention as ultimatevideoeditor). |

| D9 | 2026-10-16 | Phase 0 measures the monitor link with a UDP round-trip probe instead of a full WebRTC call. | Choosing the WebRTC library is an F-Droid/reproducibility decision (prebuilt Google binaries vs. building libwebrtc) that belongs to Phase 1 (docs/05 risk "Builds reproducibles con librerías nativas"); the RTT probe gives the network baseline now. Glass-to-glass WebRTC latency is measured once the stack is chosen. |
| D10 | 2026-10-16 | Spike runs at 2 s warm-up + 20 s per configuration, 5 s cool-down, plus one 300 s sustained 4K60 run; "meets target" = ≥ 98 % fps and zero drops at sensor, GPU and encoder. | Short runs show capability; the sustained run shows thermals. Zero drops is the requirement wording (REQ-NFR-001). |
| D11 | 2026-10-16 | Video stabilisation is off in the spike pipeline. | Isolates pipeline cost; stabilisation is measured separately with REQ-CAM-003. |
| D12 | 2026-10-16 | The app is landscape-only (sensorLandscape) for now. | Sensor-aligned preview without rotation maths in the spike; revisit with the real camera UI (Phase 2). |
| D13 | 2026-10-16 | Data goes to disk, not RAM: the spike report is rewritten atomically after every result; recordings and WAV stream to storage; only per-frame timestamps stay in small preallocated arrays. Dev work files go to `~/.cache/ultimatecamera-work` (the machine's /tmp is tmpfs). | Owner's instruction; also no lost results on a crash. Per-frame disk I/O on the camera/GL threads would cost performance. |
| D14 | 2026-10-16 | Performance on modest phones is a design criterion: the matrix starts at 1080p30 8-bit and isolates the LUT cost; no allocations on per-frame paths. | Owner's instruction. |
| D15 | 2026-10-16 | Report error strings and the one-line technical summaries (codec names, numbers, units) stay in English; everything else on screen is localised. | They are diagnostic data sent back to the developer, not UI. |

| D16 | 2026-10-16 | CI runners pinned to `ubuntu-24.04` instead of `ubuntu-latest`. | `ubuntu-latest` moves to Ubuntu 26 on 2026-10-19; an unannounced image change breaks reproducible builds. Upgrade deliberately in its own PR. |
| D17 | 2026-10-16 | Signing secrets are uploaded only with `scripts/release/set-signing-secrets.sh`, which verifies the keystore, alias and passwords (by signing a throwaway jar) before uploading; `release.yml` names any empty secret. | The first release run found `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` empty: the §1b commands read a keystore.properties that did not exist and silently stored empty values. |

| D18 | 2026-10-16 | The tag `v0.0.1-fase0` stays without a release (its run stopped at the empty secrets); the first spike release is `v0.0.2-fase0`, which adds the encoder operating rate and the non-blocking preview swap. | Tags are never moved or deleted; the fixes change what the spike measures, so testers must get them. |

| D19 | 2026-10-16 | Provisional WebRTC stack: prebuilt vanilla libwebrtc `im.conversations.webrtc:webrtc-android` (Maven Central, BSD-3), version pinned with Gradle checksum verification; `org.jitsi:webrtc` as drop-in alternative, libdatachannel (built from source) as plan B. Research: [docs/research/webrtc-android.md](research/webrtc-android.md). | Most conservative option that satisfies F-Droid (policy allows FLOSS binaries from Maven Central; Conversations and the LAN-only Meshenger ship it unmodified) and feeds hardware encoders from GPU textures. Reversible; confirmed or replaced by the WebRTC latency spike, especially the known own-hotspot interface problem. Open questions for the owner are listed in the research doc §open questions. |

| D20 | 2026-10-16 | APKs ship arm64-v8a and armeabi-v7a only (`abiFilters`). | libwebrtc adds ~9 MB (arm64) + ~5 MB (armv7); x86/x86_64 only serve emulators (+20 MB). Keeping armv7 excludes no real phone; dropping it is the owner's call (WebRTC research, open question 3). Per-ABI splits for F-Droid can come in Phase 1. |

### Risks and open issues
- **Blocker (owner):** release `v0.0.1-fase0` failed by design — the GitHub secrets `KEYSTORE_PASSWORD`, `KEY_ALIAS` and
  `KEY_PASSWORD` are empty. Fix: create `~/keys/ultimatecamera/keystore.properties` with the real values
  (docs/06-arranque.md §1), run `scripts/release/set-signing-secrets.sh`, then the release run is re-run
  and `v0.0.2-fase0` is tagged. Nothing unsigned was published.
- `~/keys/ultimatecamera/keystore.properties` does not exist on the dev machine, so a local `./gradlew assembleRelease`
  fails (by design). Signed APKs come from `release.yml` until the owner creates it (docs/06-arranque.md §1).
- `~/keys/ultimatecamera/ultimatecamera.jks` is world-readable (mode 644); recommended `chmod 600`.
- Pixel 8 (`PIXEL8_SERIAL`) is connected but the owner asked not to use it for now (2026-10-16).
- The OPPO (wireless adb) disconnected during the session and no longer advertises over mDNS: no device measurement yet.
- The 10-bit path samples camera frames through `samplerExternalOES` (driver YUV→RGB). If a driver drops to 8-bit
  precision there, `GL_EXT_YUV_target` raw sampling will be needed (availability recorded per phone).
- Hardware-filtered 3D LUT lookups may band in 10-bit (≈8-bit filter weights); the manual trilinear path costs 8 fetches.

## Devices seen
| Date | Serial | Model | SoC | Android (SDK) | Use |
|---|---|---|---|---|---|
| 2026-10-16 | PIXEL8_SERIAL | Google Pixel 8 | Tensor G3 | 37 | Not used (busy, owner's request) |
| 2026-10-16 | adb-OPPO_CPH2841_SERIAL-… (wireless) | OPPO CPH2841 | SM8850 | 36 | Seen at session start; disconnected before the spike build was ready |
