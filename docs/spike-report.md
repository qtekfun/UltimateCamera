# Phase 0 spike report

Status: **open — 1 of 5 phones measured (Pixel 8, matrix only).** Results per phone are added here as they come in
(automated runs over adb or JSON reports sent by the owner, see [TESTING.md](TESTING.md)).

## What is measured
| Area | How | Output (JSON field) |
|---|---|---|
| Capabilities | Camera2 characteristics of every logical and physical camera (10-bit dynamic range profiles, colour spaces, GPU output sizes with max fps, high-speed modes, AE fps ranges, OIS/EIS, zoom, ISO/shutter ranges, **vendor tag names**), video encoders (HEVC Main10, AV1, declared performance points), GPU (renderer, 10-bit recordable EGL config, BT.2020 HLG colourspace, `GL_EXT_YUV_target`, float render targets) | `cameras`, `encoders`, `gpu` |
| Pipeline | Camera2 (HLG10 > HDR10 > HDR10+ when 10-bit) → SurfaceTexture → GLSL LUT pass (33³ project LUT or an imported `.cube`) → MediaCodec surface → MP4, plus the on-screen preview. 2 s warm-up, then 20 s measured (300 s for the sustained run). Cadence measured at three points: sensor timestamps (camera drops), frames rendered by the GPU (render-thread drops) and encoder output (codec drops); render-thread CPU time per frame; bitrate; thermal status, headroom and battery temperature once per second | `pipeline[]` |
| Matrix | 1080p30 H.264 8-bit · 1080p60 HEVC 8-bit · 1080p30 HEVC 10-bit · 4K30 HEVC 10-bit · 4K60 HEVC 10-bit without LUT / hardware-filtered LUT / manual trilinear LUT · 4K30 AV1 10-bit (only with a hardware AV1 encoder) · sustained 4K60 10-bit LUT | |
| LUT precision | On-device instrumented test: GPU output vs the CPU reference (which is pinned to PNG references in CI), in RGBA8 and in float | logcat `LUT_8BIT`, `LUT_PRECISION` |
| Concurrent cameras | `getConcurrentCameraIds`, `isConcurrentSessionConfigurationSupported` (720p per camera) and a real 5 s stream from all cameras of each set, with measured fps | `concurrent` |
| Audio | Every input device; 4 s recording each: USB/wired/built-in through `setPreferredDevice` (CAMCORDER source), Bluetooth SCO / LE Audio through `setCommunicationDevice`; checks the device the system really routed to, actual rate/channels, peak/RMS; WAV kept on the phone | `audioInputs`, `audioRecordings[]` |
| Remote monitor (WebRTC) | Camera phone records 4K30 10-bit + LUT and streams a 720p30 H.264 proxy (libwebrtc, hardware encoder, LAN host candidates only) with a 16-bit frame counter + CRC strip drawn by the GPU; the monitor phone decodes the strip from one luma row per frame and echoes it over the data channel; latency = echo arrival − submit − RTT/2 (no clock sync). Also encoder/decoder implementation, jitter buffer, drops, and the interface the media used | `monitorLinks[]`, `pipeline[]` (row recorded while streaming) |
| Network | UDP round trip, 1200-byte packets at 60 Hz for 10 s, to an echo server (other phone or `scripts/spike/udp_echo.py` on a computer) over Wi-Fi and over a phone hotspot | `network[]` |

A pipeline run "meets target" when it holds the frame rate (≥ 98 %) with **zero** dropped frames at the sensor, the GPU
and the encoder, and no error.

## Decision on the 4K60 base target (REQ-NFR-001)
Pending: needs at least the OPPO and one more brand.

**Data point so far (Pixel 8):** the camera HAL refuses 10-bit HLG above 30 fps for a third-party session
(`unsupported combination matched {FPS RANGE: LargerThan 30, dynamic range profile [Hlg10]}`), although the encoder,
the GPU and the sensor (3840x2160@60) all could. So 4K60 10-bit cannot be a universal base target: it is a camera
capability to read per device. Working hypothesis for the decision: base = **4K30 10-bit + LUT** on every supported
phone; **4K60 10-bit + LUT** where the HAL accepts the session; 4K60 8-bit + LUT as the 60 fps fallback (the Pixel 8
holds it with zero drops). The ladder must be driven by `isSessionConfigurationSupported`/session rejection, not only by
the advertised stream sizes. The decision will state, per phone, the highest configuration that
meets target for 20 s and whether 4K60 10-bit + LUT survives the 5-minute sustained run without thermal throttling, and
the guided-degradation ladder (4K60 → 4K30 → 1080p60 → 1080p30) for phones that do not.

## Results per device
| Model | Brand | SoC | Android | Status |
|---|---|---|---|---|
| CPH2841 | OPPO | SM8850 | 16 (SDK 36) | Pending: the phone was connected at the start of the session, then disconnected (wireless adb) before the run |
| Pixel 8 | Google | Tensor G3 | 17 (SDK 37) | **Matrix, capabilities, concurrent, built-in audio, GPU tests done** (2026-10-16). Pending: sustained run unplugged with a lit scene, USB/BT microphones, network, WebRTC |
| — | vivo | | | Pending: not available |
| — | Xiaomi | | | Pending: not available |
| — | Honor | | | Pending: not available |

### Google Pixel 8 (Google Tensor G3, Android 17, SDK 37) — 2026-10-16, app 0.0.2-fase0-debug

Build `CP3A.261005.005`, device `shiba`.

- Cameras (7):
  - Camera 0 (BACK, logical of 2, 3, 4): 6.9 mm; 10-bit yes (STANDARD, HLG10); colour spaces: none; 4K+ modes: 4080x3072@30, 4032x3024@30, 4000x3000@30, 4032x2268@60, 3840x2160@60; high-speed: 1280x720@120, 1280x720@240, 1920x1080@120, 1920x1080@240; vendor tags: 54 (com.google.hal.logicalcamera.DefaultPhysicalCamId, com.google.internal.IsCameraApp1P, com.google.internal.IsMetadataValid, com.google.internal.OutputIntent, com.google.internal.ProcessingMode, ...)
  - Camera 1 (FRONT, logical of 5, 6): 2.74 mm; 10-bit yes (STANDARD, HLG10); colour spaces: none; 4K+ modes: 3440x2448@30; high-speed: none; vendor tags: 54 (com.google.hal.logicalcamera.DefaultPhysicalCamId, com.google.internal.IsCameraApp1P, com.google.internal.IsMetadataValid, com.google.internal.OutputIntent, com.google.internal.ProcessingMode, ...)
  - Camera 2 (BACK): 6.9 mm; 10-bit yes (STANDARD, HLG10); colour spaces: none; 4K+ modes: 4080x3072@30, 4032x3024@30, 4000x3000@30, 4032x2268@60, 3840x2160@60; high-speed: 1280x720@120, 1280x720@240, 1920x1080@120, 1920x1080@240; vendor tags: 53 (com.google.internal.IsCameraApp1P, com.google.internal.IsMetadataValid, com.google.internal.OutputIntent, com.google.internal.ProcessingMode, com.google.internal.SensorBinningMode, ...)
  - Camera 3 (BACK): 1.95 mm; 10-bit yes (STANDARD, HLG10); colour spaces: none; 4K+ modes: 4032x3016@30, 4000x3000@30, 4032x2268@60, 3840x2160@60; high-speed: 1280x720@120, 1280x720@240, 1920x1080@120, 1920x1080@240; vendor tags: 53 (com.google.internal.IsCameraApp1P, com.google.internal.IsMetadataValid, com.google.internal.OutputIntent, com.google.internal.ProcessingMode, com.google.internal.SensorBinningMode, ...)
  - Camera 4 (BACK): 6.9 mm; 10-bit yes (STANDARD, HLG10); colour spaces: none; 4K+ modes: 4080x3072@30, 4032x3024@30, 4000x3000@30, 4032x2268@30, 3840x2160@30; high-speed: 1280x720@120, 1280x720@240, 1920x1080@120, 1920x1080@240; vendor tags: 53 (com.google.internal.IsCameraApp1P, com.google.internal.IsMetadataValid, com.google.internal.OutputIntent, com.google.internal.ProcessingMode, com.google.internal.SensorBinningMode, ...)
  - Camera 5 (FRONT): 2.74 mm; 10-bit yes (STANDARD, HLG10); colour spaces: none; 4K+ modes: 3840x2736@30, 3840x2160@60; high-speed: none; vendor tags: 54 (com.google.internal.IsCameraApp1P, com.google.internal.IsMetadataValid, com.google.internal.OutputIntent, com.google.internal.ProcessingMode, com.google.internal.SensorBinningMode, ...)
  - Camera 6 (FRONT): 2.74 mm; 10-bit yes (STANDARD, HLG10); colour spaces: none; 4K+ modes: 3440x2448@30; high-speed: none; vendor tags: 53 (com.google.internal.IsCameraApp1P, com.google.internal.IsMetadataValid, com.google.internal.OutputIntent, com.google.internal.ProcessingMode, com.google.internal.SensorBinningMode, ...)
- Hardware encoders (3):
  - H264 `c2.exynos.h264.encoder`: 10-bit no, 4K60 yes, performance points: PerformancePoint(7680x4320@30), PerformancePoint(3840x2160@120), PerformancePoint(1920x1088@240)
  - HEVC `c2.exynos.hevc.encoder`: 10-bit yes, 4K60 yes, performance points: PerformancePoint(7680x4320@30), PerformancePoint(3840x2160@120), PerformancePoint(1920x1088@240)
  - AV1 `c2.google.av1.encoder`: 10-bit yes, 4K60 no, performance points: PerformancePoint(3840x2160@60), PerformancePoint(1920x1088@180), PerformancePoint(1920x1088@120), PerformancePoint(1280x720@200)
- GPU: Mali-G715 (OpenGL ES 3.2 v1.r54p3-00eac0.d6609729a0c2466c24fd0530143c8edf); RGBA1010102 recordable no; BT.2020 HLG yes; GL_EXT_YUV_target yes, GL_EXT_color_buffer_float yes, GL_EXT_color_buffer_half_float yes, GL_EXT_texture_norm16 yes, GL_OES_EGL_image_external_essl3 yes, GL_OES_texture_float_linear yes

| Config | Meets | GPU fps | Drops sensor/GPU | Enc/GPU frames | Render p95 ms | Mbps | Thermal start→max | Max brightness | Reason/error |
|---|---|---|---|---|---|---|---|---|---|
| 1920x1080@30 H264 8-bit HARDWARE | yes | 29.7 | 0/0 | 648/648 | 7.8 | 0.0 | 0→0 (31.6→31.6 °C) | no |  |
| 1920x1080@60 HEVC 8-bit HARDWARE | yes | 59.5 | 0/0 | 1297/1297 | 5.7 | 0.2 | 0→0 (31.6→31.6 °C) | no |  |
| 1920x1080@30 HEVC 10-bit HARDWARE | yes | 29.7 | 0/0 | 648/648 | 9.4 | 10.2 | 0→0 (31.6→31.6 °C) | no |  |
| 3840x2160@30 HEVC 10-bit HARDWARE | yes | 29.7 | 0/0 | 646/646 | 10.4 | 60.0 | 0→0 (31.6→31.6 °C) | no |  |
| 3840x2160@60 HEVC 10-bit NO_LUT | no | n/a | n/a/n/a | 0/0 | n/a | 0.0 | n/a | no | rejected by the camera HAL; SessionRejectedException: session configuration failed |
| 3840x2160@60 HEVC 10-bit HARDWARE | no | n/a | n/a/n/a | 0/0 | n/a | 0.0 | n/a | no | rejected by the camera HAL; SessionRejectedException: session configuration failed |
| 3840x2160@60 HEVC 10-bit MANUAL_TRILINEAR | no | n/a | n/a/n/a | 0/0 | n/a | 0.0 | n/a | no | rejected by the camera HAL; SessionRejectedException: session configuration failed |
| 3840x2160@60 HEVC 8-bit HARDWARE | yes | 59.5 | 0/0 | 1298/1298 | 5.8 | 1.2 | 0→0 (31.6→31.6 °C) | no |  |
| 3840x2160@30 AV1 10-bit HARDWARE | yes | 29.7 | 0/0 | 646/646 | 10.9 | 40.1 | 0→0 (31.6→31.6 °C) | no |  |

| Concurrent set | Declared | Measured fps | Error |
|---|---|---|---|
| 0 + 1 | yes | 0: 15.0, 1: 24.0 |  |

- Audio inputs: 21 BUILTIN_MIC (Pixel 8), 22 BUILTIN_MIC (Pixel 8), 27 TELEPHONY (Pixel 8), 33 REMOTE_SUBMIX (Pixel 8), 31 TYPE_28 (Pixel 8)

| Audio device | Route | Honoured | Rate/channels | Peak/RMS dBFS | Frames read/expected | Error |
|---|---|---|---|---|---|---|
| 21 BUILTIN_MIC | preferred | yes | 48000/2 | -47.0/-63.8 | 188160/192000 |  |
| 22 BUILTIN_MIC | preferred | yes | 48000/2 | -45.1/-62.0 | 188160/192000 |  |
| 27 TELEPHONY | preferred | no | 0/0 | n/a/n/a | 0/192000 | IllegalStateException: AudioRecord.read returned -4 |
| 33 REMOTE_SUBMIX | preferred | no | 0/0 | n/a/n/a | 0/192000 | IllegalStateException: AudioRecord.read returned -4 |
| 31 TYPE_28 | preferred | no | 0/0 | n/a/n/a | 0/192000 | IllegalStateException: AudioRecord.read returned -4 |

- LUT precision: not in the JSON report (logcat `LUT_8BIT`, `LUT_PRECISION`); add by hand.

- LUT precision (instrumented test, Mali-G715): 8-bit max diff 1/255 with hardware and with manual trilinear; float
  max error 0.33 (hardware) and 0.24 (manual) 10-bit codes. **Hardware filtering is precise enough for 10-bit here.**
- Caveats of this run: the phone lay face down (frames almost black), so encoder load and bitrate are lower than in real
  use (8-bit files ~0–1 Mbps) and the AE could pick lower rates in the concurrent test; debug build. Thermal never left
  status 0 (28→32 °C) over the 20 s runs. Repeat with a lit, detailed scene before the decision.
- Conclusion for the catalogue: highest config meeting target **4K60 HEVC 8-bit + LUT**; highest 10-bit **4K30 HEVC
  and AV1 10-bit HLG + LUT** (ffprobe: Main10, BT.2020, ARIB STD-B67, full range); `rejectedConfigs` = the three 4K60
  10-bit runs.

<!-- Template for each device:
### <Brand> <Model> (<SoC>, Android <n>) — <date>, app <version>
- Capabilities: cameras (logical/physical, 10-bit on which), dynamic range profiles, vendor tags of interest, encoders.
- Pipeline table: config | meets | gpu fps | drops sensor/gpu/enc | render p95 | Mbps | thermal start→max.
- Sustained 4K60 10-bit LUT: fps over time, first throttling time, thermal status/battery °C at end.
- LUT precision: 8-bit max diff (HW/manual), float max error in 10-bit codes.
- Concurrent cameras: sets, declared, measured fps.
- Audio: per device, route, honoured, rate/channels, notes (SCO quality).
- Network: Wi-Fi and hotspot p50/p95/p99, loss.
- Conclusion for the capability catalogue.
-->

## Findings so far
- **Session parameters are required.** Without them the Pixel 8 configured a 30 fps sensor mode and closed the camera
  on the 60 fps request ("CameraDevice was already closed"). The pipeline now passes the repeating request as session
  parameters, and a refused configuration is reported as `sessionRejected` (PR #12).
- **10-bit through an EGL config that is not "recordable".** The Pixel 8 reports no recordable RGBA1010102 config, yet
  the 10-bit encoder surface works and the files are Main10/HLG. Keep probing `eglRecordable` per phone; do not use it
  as a gate.
- **HLG files are tagged full range** (`color_range=pc`). Broadcast HLG is usually limited range; decide in Phase 2/4
  which one to write and check how editors read it.
- **Audio inputs that are not microphones.** TELEPHONY, REMOTE_SUBMIX and type 28 are listed as inputs and fail to
  record (`read` returns -4). The Phase 3 source selector must filter to real capture devices.
- **Concurrent cameras** 0 + 1 are declared and stream, but at 15 and 24 fps (dark scene; to repeat with light).
- The spike's report accumulates runs until reset; `report.py` output must be curated to the latest run (done here).
- The LUT is stored as an RGB16F 3D texture; hardware trilinear filtering on many mobile GPUs uses ~8-bit weights, which
  can band in 10-bit output. Both a hardware-filtered and a manual 8-fetch trilinear shader exist; the matrix and the
  float precision test measure cost and error of each, and decide the 10-bit path (REQ-COL-005, Phase 4).
- Camera frames are sampled with `samplerExternalOES`, so the driver converts the 10-bit YUV to RGB. Whether that keeps
  10-bit precision (or whether `GL_EXT_YUV_target` raw sampling is needed) is recorded per phone in `gpu.extensions`
  and checked with the recorded files (ffprobe) and the banding test of Phase 4.
