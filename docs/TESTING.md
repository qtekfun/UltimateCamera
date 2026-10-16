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
| Maximum brightness switch: off at launch (system brightness), on forces full brightness, off restores it | REQ-MON-003 | any phone |
| Per-app language switch (Settings › Apps › UltimateCamera › Language: English/Español) | REQ-NFR-002 | any phone |
