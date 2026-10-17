# Testing

## Automated
| What | Where | Command |
|---|---|---|
| Unit tests (LUT parser/writer, LUT maths vs PNG references, frame statistics, WAV, audio priority, latency stats + loopback UDP echo, version code, settings: defaults, round-trip through disk, reset, corrupt file and wrong-type recovery, schema version; audio DSP with synthetic signals: gain accuracy and smoothing, limiter ceiling on transients and sweeps, hold/release, meter dBFS of known sines and ballistics, channel mapping, no allocation per buffer, 60 s stereo benchmark (> 50× real time), AudioRecord↔video clock mapping and drift, source-change notices; settings: recording folder key; storage: SAF tree parsing, validation order and fallback, grant handling, write-speed test with a fake clock and sink incl. cancellation and cleanup, speed verdict thresholds, minutes remaining, clip naming and collisions; LUTs: `.3dl` variants (Lustre header, Nuke, float), blue-fastest axis order vs `.cube`, malformed files with line numbers, size and line-length limits; UC-Log code values, round-trip, monotonicity, toe continuity, ≤ 1 % per 10-bit code from −2 stops, Rec.709 LUT; library import/dedupe/folders/rename/move/delete in a temp dir, corrupt and newer index, orphan adoption, swatch PNG; catalogue schema and licence policy, no committed LUT files, download with checksum mismatch, oversize, missing entry and unconfirmed rejection; multicamera control in `core-net`: pairing QR format and validation, paired-devices file (atomic write, corrupt and newer-schema recovery), DNS-SD TXT and address book with a fake discovery, control message codec, handshake (RFC 5869 HKDF vector, mutual proofs, wrong key, tampering, refusals), encrypted frames (tamper and replay), loopback TCP session, clock offset/skew estimator on simulated asymmetric Wi-Fi delays, authenticated clock packets and loopback UDP sync, SMPTE timecode incl. drop-frame, frame-aligned scheduled start, camera/master/monitor state machines with fake sinks and a fake clock (loss, duplicates, reordering, half-open link, reconnection with resync, recording never stopped by the link), pair-then-resume over loopback drivers; capability catalogue: probe plan order and early stop, unadvertised rates, thermal guard, budget, watchdog, crash marker, cancellation and resume, vendor-parameter retry, store round-trip/atomic write/corruption/schema, re-probe on OS update, selector and degradation ladder, with the `catalog/devices/` seeds and the measured OPPO CPH2841 / Pixel 8 facts as fixtures) | every module, CI | `./gradlew testDebugUnitTest` |
| Style and static analysis, missing translations | CI | `./gradlew ktlintCheck detekt lintDebug` |
| Unsigned release is refused | CI | `./gradlew :app:assembleRelease` must fail with "Release signing is not configured" |
| Reproducible release APK: built twice (checkout, and a `git archive` export in another path with other TZ, locale and `--max-workers=1`), signed with a throwaway key generated in the job; entries, zip metadata and contents must match, signature excluded | CI job *Reproducible release build* | `scripts/release/compare-apks.sh first.apk second.apk` |
| Release signature | `release.yml` | `apksigner verify --print-certs` (fails on a debug certificate) |
| GLSL LUT pass vs CPU reference (8-bit and float precision); GLSL UC-Log stage vs `UcLog` (float, < 1 10-bit code) | device | `ANDROID_SERIAL=<serial> ./gradlew :core-gpu:connectedDebugAndroidTest` |
| Meter CPU references (REQ-MON-001): peaking, zebras (static and animated), false colour (ARRI-like bands), guides (thirds, centre, safe areas, 2.39 frame lines + matte), desqueeze letterbox for every factor, integer luma/Cb/Cr binning, layered histogram within capacity, waveform, vectorscope, saturation, exact block downsample, scope displays, cost percentile; every pass pinned to a PNG in `core-gpu/src/test/resources/reference/meters` | core-gpu, CI | `./gradlew :core-gpu:testDebugUnitTest --tests '*.meters.*'` |
| GLSL meters vs CPU reference (REQ-MON-001, REQ-MON-002): `MonitorOverlayShaderTest` (overlays 1/255 after skipping decisions within 1e-4 of a threshold, < 1 % of pixels; desqueeze 3/255; viewport offset; OES variant compiles), `ScopeShaderTest` (analysis image, histogram layers, async PBO readback, waveform, vectorscope **exact** in the driver's format and in the RGBA8 fallback; displays 1/255), `MeterCostBenchmarkTest` (logs `METER_COST` per pass at 1080p, GPU timer query or glFinish fallback) | device | `ANDROID_SERIAL=<serial> ./gradlew :core-gpu:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.qtekfun.ultimatecamera.core.gpu.meters`, then `adb logcat -d -s UCSPIKE` |
| Phase 0 spike over adb | device | `scripts/spike/run-spike.sh <serial> --apk dist/ultimatecamera-<v>.apk [--pull-videos]` |

Optional check of the real OPPO packages against `luts/catalog.json` (the zips are link-only and never committed):
download the two zips named in the catalogue into an empty directory and run
`OPPO_LUT_ZIP_DIR=<dir> ./gradlew :core-luts:testDebugUnitTest --tests '*realOppoPackages*'` (2026-10-17: all five
checksums match and the 65-point files import).

Reference images: `core-gpu/src/test/resources/reference/*.png` (incl. `uclog_hlg_gradient.png`, the gradient read as HLG
and encoded to UC-Log, and `uclog_rec709_33_gradient.png`, the gradient read as UC-Log through the default Rec.709 LUT). After an intended change to the LUT maths regenerate them
with `UPDATE_REFERENCES=1 ./gradlew :core-gpu:testDebugUnitTest --tests '*ReferenceImageTest*'` and review the PNG diff.
Meter references live in `reference/meters/` (stored top-down, as on screen): regenerate them with
`UPDATE_REFERENCES=1 ./gradlew :core-gpu:testDebugUnitTest --tests '*MeterReferenceImageTest*'`.

## Dependencies
Every dependency is pinned by SHA-256 in `gradle/verification-metadata.xml`; a changed or unknown artifact fails the
build. After adding or bumping a dependency, regenerate it by running the real tasks (not `--dry-run`, which misses
artifacts resolved by lint, R8 or detekt) and review the diff:
```bash
./gradlew --no-daemon --write-verification-metadata sha256 ktlintCheck detekt testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```
(add `:app:assembleRelease` with the signing variables set to cover R8), and cross-check new native or security-
relevant artifacts against the repository's published `.sha256`.

## Running the Phase 0 spike on a phone

### A. Automated (phone connected to the dev machine)
```bash
adb devices                                            # pick the serial
scripts/spike/run-spike.sh <serial> --apk ultimatecamera-<version>.apk --pull-videos
scripts/spike/run-spike.sh <serial> --tests sustained --sustained 300
python3 scripts/spike/udp_echo.py &                     # on the computer, same Wi-Fi
scripts/spike/run-spike.sh <serial> --tests net --net-host <computer IP> --net-link wifi    # or hotspot
```
The phone must be unlocked. The script takes `/tmp/pixel-device.lock` (shared with other projects using the same
phones), grants the permissions, starts the DUMP-protected automation entry point and copies the JSON report to
`~/.cache/ultimatecamera-work/spike/<model>/`.

### B. By hand (owner, any phone)
Follow the release notes of the spike pre-release (also in `docs/release-notes/<tag>.md`): install, *Run
everything*, *Sustained 4K60*, network test with the echo server, *Export report*, and send the JSON from
`Download/UltimateCamera-spike/`. With a USB or Bluetooth microphone connected the audio test covers it too.

### Remote monitor latency (WebRTC, two phones)
Both phones on the same Wi-Fi router, or one phone sharing a hotspot and the other joined to it. Pick the link chip
(Wi-Fi router / phone hotspot) on both.
1. Phone A: *Be the camera*. It shows its IPs and waits up to 3 minutes for the monitor.
2. Phone B: type phone A's IP, *Be the monitor*. B shows A's proxy in its preview area and echoes every stamp.
3. A records 4K30 10-bit with LUT for the matrix duration while streaming a 720p30 H.264 proxy; both phones export
   their report. A's report holds the glass-to-glass latency (render → encode → network → decode, without camera
   exposure and display), B's the decode counts; A's pipeline row shows what streaming costs the recording.
4. Repeat with the hotspot on A, then on B (own-hotspot interfaces are the known libwebrtc risk).

Over adb: `run-spike.sh <A> --tests monitor-camera --net-link wifi` and, once A waits,
`run-spike.sh <B> --tests monitor-view --net-host <A's IP> --net-link wifi` (each script takes the device lock, so run
them for two different phones from two terminals).

### Turning reports into docs
```bash
python3 scripts/spike/report.py markdown spike-*.json     # sections for docs/spike-report.md + comparison table
python3 scripts/spike/report.py catalog spike-<maker>-<model>-<time>.json   # writes catalog/devices/<brand>-<model>.json
```
`markdown` prints one section per report (the template in `docs/spike-report.md`) and a comparison table for
"Results per device"; LUT precision comes from logcat and is added by hand. `catalog` writes a deterministic seed for
the capability catalogue (`--out DIR` to write elsewhere). Tests: `python3 -I -m unittest discover -s scripts/spike -p 'test_*.py'`.

## Device verification log
| Date | Device | What | Result |
|---|---|---|---|
| 2026-10-16 | Pixel 8 | App shell: launch opens Home (es/en strings), Diagnostics opens the spike bench, Back returns Home, the `SpikeAutomation` alias opens the bench directly and autoruns | pass |
| 2026-10-16 | Pixel 8 | Settings (REQ-MON-003): maximum brightness off by default; on → `screenBrightness=1.0` on Home and Settings, kept after a force-stop and relaunch, not applied in Diagnostics (the spike's own switch rules there), off → override removed | pass |
| 2026-10-16 | Pixel 8 | Settings: feature-group toggle (Multicamera) persists across a relaunch; Reset asks for confirmation and restores the defaults; rows expose their on/off state to accessibility (`checkable`, `checked`); "Choose language" opens the system per-app language screen (Spanish / system default) | pass |
| 2026-10-16 | Pixel 8 | Minified release (R8) signed with a throwaway local key: Settings work and persist, no crash; uninstalled afterwards | pass |
| 2026-10-17 | Pixel 8 | Camera screen (REQ-CAM-004, REQ-STO-001): opens on launch; 4K60 10-bit refused by the HAL (`configureStreamsLocked: Set of requested inputs/outputs not supported`), ladder falls to 4K30 10-bit; preview upright with letterbox; storage line "Movies/UltimateCamera · 263 min"; REC shows red dot, timer and L/R meter, stop square; take `UC_20261017_085542_CAM0_T001.mp4` 75 MB: HEVC Main 10, 3840×2160, HLG (arib-std-b67), BT.2020, 360 frames in 12.0 s (one 67 ms gap at frame 7, start-up), ~50 Mbit/s; AAC LC 48 kHz stereo 192 kbit/s starting 0.18 s after the first video frame | pass |
| 2026-10-17 | Pixel 8 | Camera screen: Home pressed 6 s into a take → take finished and saved (`UC_20261017_085749_CAM0_T001.mp4`, 190 frames / 6.34 s, AAC 6.38 s), full decode with ffmpeg without errors | pass |

## Manual tests pending
Tests that need hardware and could not be run yet. Each line names the device(s) still missing.

| Test | Requirement | Pending on |
|---|---|---|
| Install the signed release over itself with `adb install -r` (same key, same applicationId) | signing | OPPO, vivo, Xiaomi, Honor, Pixel |
| Instrumented LUT shader test (`:core-gpu:connectedDebugAndroidTest`) | REQ-COL-005 groundwork | OPPO, vivo, Xiaomi, Honor (Pixel 8 passed 2026-10-16) |
| Spike: capabilities + pipeline matrix (A or B) | REQ-NFR-001, REQ-COL-001, REQ-CAM-004 | OPPO, vivo, Xiaomi, Honor; Pixel 8 done but to repeat with a lit scene (it lay face down) |
| Spike: sustained 4K60 10-bit LUT, 5 min, unplugged (falls back to 4K60 8-bit when the HAL refuses 10-bit, as on the Pixel 8) | REQ-NFR-001 | OPPO, vivo, Xiaomi, Honor, Pixel |
| Spike: recorded files are HEVC Main10 / BT.2020 / HLG with the expected frame count (`--pull-videos`, ffprobe) | REQ-CAM-004, REQ-COL-001 | OPPO, vivo, Xiaomi, Honor (Pixel 8 passed: Main10, BT.2020, HLG, full range) |
| Spike: concurrent cameras | REQ-NET-001 | OPPO, vivo, Xiaomi, Honor; Pixel 8 to repeat with light (0 + 1 at 15/24 fps in the dark) |
| Spike: audio with a USB microphone (preferred device honoured?) | REQ-AUD-001 | all (needs a USB mic) |
| Spike: audio with a Bluetooth SCO and an LE Audio microphone | REQ-AUD-005 | all (needs BT mics) |
| Audio chain by ear with a USB microphone: gain steps without clicks or zipper noise; loud claps and shouts with +20 dB gain stay under −1 dBFS in the WAV (no clipping in an editor) and the limiter does not pump on speech | REQ-AUD-004 | all (needs a USB mic and the Phase 3 recorder) |
| Meter vs a reference app (e.g. a calibrated level-meter app or a DAW on the WAV): 1 kHz tone at −20 and −6 dBFS reads within 0.5 dB, peak falls ~20 dB in 1.7 s, clip lamp lights only on input overload | REQ-AUD-004 | all (Phase 3 UI) |
| WAV track sync: clap at the start and after 10+ minutes lines up with the video within one video frame | REQ-AUD-003 | all (Phase 3 recorder) |
| Spike: network latency Wi-Fi router and phone hotspot (phone↔phone and phone↔computer) | REQ-NET-004 | needs two phones |
| Spike: WebRTC remote monitor latency — Wi-Fi router, hotspot on the camera phone, hotspot on the monitor phone | REQ-NET-004, D19 | needs two phones |
| Multicamera control, pairing: camera shows the QR, monitor scans it; both list each other after pairing; the code refuses a second use and after 5 min; unpairing on the camera stops the monitor from redialling | REQ-NET-002 | two phones (needs the Phase 6 UI) |
| Multicamera control, discovery: with the camera's DHCP address changed (router reboot or other Wi-Fi), the monitor finds it again through `_ultimatecamera._tcp` without re-pairing; also on a phone hotspot | REQ-NET-002 | two phones, Wi-Fi router and hotspot |
| Remote REC from the monitor: start/stop, exposure/focus/WB, profile and LUT reach the camera; refusals (profile while recording) show the translated error | REQ-NET-003 | two phones |
| Link loss: switch the monitor's Wi-Fi off for 30 s while the camera records; the camera keeps recording, the monitor shows "retrying" and gets the current state (recording, time, battery) back within ~1 s of the network returning; a stop sent while offline is applied once on reconnect | REQ-NET-007 | two phones |
| Simultaneous REC: master + two cameras filming a clapper or a phone showing a millisecond clock; the first frames of both clips show the same instant within one frame, and both clips carry the same start timecode; repeat on a hotspot and after 30 min (drift) | REQ-NET-005 | three phones (two at minimum: monitor as clock source) |
| Clock sync accuracy: compare the camera's estimated offset with a reference (both phones filming one millisecond clock, or adb `uptime` sampled together); expected error ≤ 2 ms on 5 GHz Wi-Fi | REQ-NET-005 | two phones |
| Instrumented frame-stamp test (`:core-gpu:connectedDebugAndroidTest`, FrameStampRendererTest) | REQ-NET-004 | done on Pixel 8 (3/3, 2026-10-16); repeat on other GPUs |
| Instrumented meter tests (`MonitorOverlayShaderTest`, `ScopeShaderTest`): all pass, and the logged `skippedAmbiguous` stays under 1 % | REQ-MON-001 | Pixel 8 (Mali-G715), OPPO SM8850 (Adreno 840), then vivo, Xiaomi, Honor |
| Meter cost (`MeterCostBenchmarkTest`): `METER_COST` lines per pass into `spike-report.md`; overlays + three scopes must fit the frame budget left by the 4K60 LUT pass (~8 ms p95 of 16.7 ms on Pixel 8 / SM8850) | REQ-MON-002 | Pixel 8, OPPO SM8850, and one modest phone |
| Overlays on the live preview once wired into the camera screen: peaking follows focus, zebras/false colour track exposure, guides and desqueeze line up, nothing of it in the recorded file | REQ-MON-001 | all (after the Phase 5 UI) |
| Maximum brightness switch: off at launch (system brightness), on forces full brightness, off restores it | REQ-MON-003 | done on Pixel 8 (2026-10-16: no window override at launch, `screenBrightness=1.0` when on, override removed when off); repeat on the Phase 1 setting |
| Recording folder on a USB SSD: pick a folder on the SSD (label "External drive (USB or SD card)" + system name), run the write-speed test (MB/s and verdict for 4K60 10-bit; test file gone afterwards); unplug the SSD → validation reports "drive not connected" and recordings fall back to phone storage without a crash; plug it back → the folder is valid again. Repeat on phone storage (`Movies/UltimateCamera`) and with a slow USB 2 stick (expect marginal or insufficient) | REQ-STO-001 | all (needs the storage UI and a USB SSD) |
| Auto-configuration with the real Camera2 `ProbeRunner` (not written yet): Pixel 8 records 4K60 10-bit as rejected and 4K30 10-bit as best; OPPO CPH2841 holds unadvertised 4K60 10-bit; run stays under the budget and cool | REQ-NFR-001, REQ-COL-001 | runner implementation; then OPPO, Pixel, vivo, Xiaomi, Honor |
| Per-app language switch (Settings › Apps › UltimateCamera › Language: English/Español) | REQ-NFR-002 | done on Pixel 8 (2026-10-16, `cmd locale set-app-locales` es-ES/en-US, every spike string switched); repeat for each new screen |
| Camera screen: sustained 10 min 4K30 10-bit take on battery, frame count = 30 × duration ± 1, no drops after start-up; storage minutes-left goes down | REQ-NFR-001, REQ-STO-001 | Pixel 8, OPPO, vivo, Xiaomi, Honor |
| Camera screen: hold the phone in the other landscape orientation → preview turns, a take started there plays upright (orientation hint 180) | REQ-CAM-004 | all |
| Camera screen: lip sync — clap on camera, audio and video peaks within one frame in an editor | REQ-AUD-003 | all |
