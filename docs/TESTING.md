# Testing

## Automated
| What | Where | Command |
|---|---|---|
| Unit tests (LUT parser/writer, LUT maths vs PNG references, frame statistics, WAV, audio priority, latency stats + loopback UDP echo, version code) | every module, CI | `./gradlew testDebugUnitTest` |
| Style and static analysis, missing translations | CI | `./gradlew ktlintCheck detekt lintDebug` |
| Unsigned release is refused | CI | `./gradlew :app:assembleRelease` must fail with "Release signing is not configured" |
| Release signature | `release.yml` | `apksigner verify --print-certs` (fails on a debug certificate) |
| GLSL LUT pass vs CPU reference (8-bit and float precision) | device | `ANDROID_SERIAL=<serial> ./gradlew :core-gpu:connectedDebugAndroidTest` |
| Phase 0 spike over adb | device | `scripts/spike/run-spike.sh <serial> --apk dist/ultimatecamera-<v>.apk [--pull-videos]` |

Reference images: `core-gpu/src/test/resources/reference/*.png`. After an intended change to the LUT maths regenerate them
with `UPDATE_REFERENCES=1 ./gradlew :core-gpu:testDebugUnitTest --tests '*ReferenceImageTest*'` and review the PNG diff.

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

## Manual tests pending
Tests that need hardware and could not be run yet. Each line names the device(s) still missing.

| Test | Requirement | Pending on |
|---|---|---|
| Install the signed release over itself with `adb install -r` (same key, same applicationId) | signing | OPPO, vivo, Xiaomi, Honor, Pixel |
| Instrumented LUT shader test (`:core-gpu:connectedDebugAndroidTest`) | REQ-COL-005 groundwork | OPPO, vivo, Xiaomi, Honor, Pixel |
| Spike: capabilities + pipeline matrix (A or B) | REQ-NFR-001, REQ-COL-001, REQ-CAM-004 | OPPO, vivo, Xiaomi, Honor, Pixel |
| Spike: sustained 4K60 10-bit LUT, 5 min, unplugged | REQ-NFR-001 | OPPO, vivo, Xiaomi, Honor, Pixel |
| Spike: recorded files are HEVC Main10 / BT.2020 / HLG with the expected frame count (`--pull-videos`, ffprobe) | REQ-CAM-004, REQ-COL-001 | OPPO, vivo, Xiaomi, Honor, Pixel |
| Spike: concurrent cameras | REQ-NET-001 | OPPO, vivo, Xiaomi, Honor, Pixel |
| Spike: audio with a USB microphone (preferred device honoured?) | REQ-AUD-001 | all (needs a USB mic) |
| Spike: audio with a Bluetooth SCO and an LE Audio microphone | REQ-AUD-005 | all (needs BT mics) |
| Spike: network latency Wi-Fi router and phone hotspot (phone↔phone and phone↔computer) | REQ-NET-004 | needs two phones |
| Spike: WebRTC remote monitor latency — Wi-Fi router, hotspot on the camera phone, hotspot on the monitor phone | REQ-NET-004, D19 | needs two phones |
| Instrumented frame-stamp test (`:core-gpu:connectedDebugAndroidTest`, FrameStampRendererTest) | REQ-NET-004 | any phone |
| Maximum brightness switch: off at launch (system brightness), on forces full brightness, off restores it | REQ-MON-003 | any phone |
| Per-app language switch (Settings › Apps › UltimateCamera › Language: English/Español) | REQ-NFR-002 | any phone |
