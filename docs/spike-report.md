# Phase 0 spike report

Status: **open — no device measured yet.** The spike app is built and released; results per phone are added here as
they come in (automated runs over adb or JSON reports sent by the owner, see [TESTING.md](TESTING.md)).

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
Pending: needs at least the OPPO and one more brand. The decision will state, per phone, the highest configuration that
meets target for 20 s and whether 4K60 10-bit + LUT survives the 5-minute sustained run without thermal throttling, and
the guided-degradation ladder (4K60 → 4K30 → 1080p60 → 1080p30) for phones that do not.

## Results per device
| Model | Brand | SoC | Android | Status |
|---|---|---|---|---|
| CPH2841 | OPPO | SM8850 | 16 (SDK 36) | Pending: the phone was connected at the start of the session, then disconnected (wireless adb) before the run |
| Pixel 8 | Google | Tensor G3 | SDK 37 | Pending: connected but not to be used for now (owner's request) |
| — | vivo | | | Pending: not available |
| — | Xiaomi | | | Pending: not available |
| — | Honor | | | Pending: not available |

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

## Findings so far (without devices)
- The LUT is stored as an RGB16F 3D texture; hardware trilinear filtering on many mobile GPUs uses ~8-bit weights, which
  can band in 10-bit output. Both a hardware-filtered and a manual 8-fetch trilinear shader exist; the matrix and the
  float precision test measure cost and error of each, and decide the 10-bit path (REQ-COL-005, Phase 4).
- Camera frames are sampled with `samplerExternalOES`, so the driver converts the 10-bit YUV to RGB. Whether that keeps
  10-bit precision (or whether `GL_EXT_YUV_target` raw sampling is needed) is recorded per phone in `gpu.extensions`
  and checked with the recorded files (ffprobe) and the banding test of Phase 4.
