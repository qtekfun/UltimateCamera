# Unlocking more from each phone: Open Camera, Camera FV-5 and vendor modes (research)

Status: research only. No code or dependency has been added. Written 2026-10-16 after the first Phase 0 results
(`docs/spike-report.md`): the OPPO CPH2841 records 4K60 10-bit HLG10 with zero drops although its stream configuration
map advertises 4K at 30 fps at most, and the Pixel 8 HAL refuses HLG10 above 30 fps. The owner wants to unlock as much
as possible (4K60, 8K, native Log, vendor modes) and an OBS-style "auto-configuration" that probes each phone.

Legend: **[V]** verified for this document (upstream source read at the commit given, or official documentation read).
**[U]** not verified: an inference, a forum or third-party claim, or general knowledge. Anything marked [U] must be
checked on a device before it is relied on.

Sources read:

| Source | Version / commit | Licence | How |
|---|---|---|---|
| Open Camera (Mark Harman), `https://git.code.sf.net/p/opencamera/code` | `0dd4cbe` (2026-05-27, v1.56.2) | GPL-3.0-or-later (`opencamera_source.txt` §Licence, `_docs/index.html`) [V] | Cloned to `~/.cache/ultimatecamera-work/opencamera`, read only, not built |
| FreeDcam (KillerInk), `github.com/KillerInk/FreeDcam` | `master`, pushed 2026-07-08 | GPL-2.0-or-later (README §License: "either version 2 … or (at your option) any later version"; 161 files carry the same header) [V] | Shallow clone, read only |
| Snap camera (CodeAurora/LineageOS), `github.com/LineageOS/android_packages_apps_Snap` | `lineage-18.1`, pushed 2024-10-19 | Apache-2.0 + BSD (Linux Foundation) file headers [V] | Shallow clone, read only |
| Aperture (LineageOS), `github.com/LineageOS/android_packages_apps_Aperture` | `lineage-24.0`, pushed 2026-10-15 | Apache-2.0 (SPDX headers, REUSE) [V] | Shallow clone, read only |
| Camera FV-5 / Cinema FV-5 (Flavionet, proprietary) | website, docs, FAQ, device database | Proprietary | Public pages only. Nothing decompiled |
| mcpro24fps, MotionCam (proprietary) | public spec page, GitHub metadata | Proprietary | Public pages only |
| Android reference: `CameraDevice`, `CameraDevice.CameraDeviceSetup`, `CameraCharacteristics`, `SessionConfiguration`, `CaptureRequest.Key` | API 35–37 docs | — | developer.android.com through Context7 |

All clones are treated as untrusted data: no build, script or Gradle task was run from them.

## 1. The ten findings that matter most

1. **Open Camera trusts the advertised map and never tries unadvertised frame rates.** Normal video sizes come from
   `StreamConfigurationMap.getOutputSizes(MediaRecorder.class)`, each size's max fps from
   `getOutputMinFrameDuration`, and the offered fps list is `{15, 24, 25, 30, 60, 96, 100, 120, 240}` filtered by that
   (`CameraController2.getCameraFeatures()` ~L2178–2280; `MainActivity` ~L2844–2860) [V]. On the OPPO it would never
   offer 4K60. Its only "force" option, *Force 4K UHD*, exists for the old Camera API only and is disabled with Camera2
   (`MainActivity` L5075–5079) [V].
2. **Open Camera does not pass session parameters**, does not use `isSessionConfigurationSupported`, and caps video at
   4096×2160 ("Nexus 6 returns these, even though not supported?!", L2201–2202) [V]. So no 8K and no help with the
   Pixel 8 class of failure. Our spike already found that session parameters are required (PR #12).
3. **Open Camera has no 10-bit/HDR video.** HLG10 is used only for Ultra HDR (JPEG_R) photos, and is explicitly
   disabled in video mode because "video recording fails if CameraController2 sets
   `setDynamicRangeProfile(HLG10)`" with its MediaRecorder path (`MyApplicationInterface.getJpegRPref()` ~L1780) [V].
   Its "Log" is a `TONEMAP_CURVE` on 8-bit output (`Camera2Settings.setTonemapProfile()` L754–1010) [V].
4. **What is worth reusing from Open Camera is its catalogue of HAL quirks, not its architecture**: MediaRecorder and
   `CamcorderProfile` (not MediaCodec/GL), a 9,000-line `Preview` god class, Java. The quirks are listed in §2.12 with
   the reason each exists; most are "do not trust X on device Y" rules that belong in our capability catalogue.
5. **Vendor tags can be read and written with public API only.** `CameraCharacteristics.getKeys()` already lists them
   (our spike dumps them), and `CaptureRequest.Key(String, Class)` / `CameraCharacteristics.Key(String, Class)` are
   public constructors [V, Android reference]. FreeDcam's reflection and `RestrictionBypass` hidden-API trick is not
   needed for this and must not be copied.
6. **Qualcomm phones publish their real sensor-mode table.** Snap reads
   `org.quic.camera2.sensormode.info.SensorModeTable` (`int[]`: header, stride, then `width, height, fps, …` rows),
   `org.quic.camera2.customhfrfps.info.CustomHFRFpsTable` and
   `org.quic.camera2.VideoConfigurations.info.VideoConfigurationsTable` (`Snap/CaptureModule.java` L431–491,
   L2214–2262) [V]. If the OPPO exposes these, they explain *why* 4K60 works there despite the map, and tell us what to
   probe instead of guessing. The OPPO's `org.codeaurora.qcamera3.sessionParameters.SensorModesInConfig` is a
   session-parameter cousin [U on its semantics].
7. **Vendor operating modes go through the public `SessionConfiguration(sessionType, …)` constructor.** Snap ORs
   Qualcomm modes into `sessionType` (`0xF002` ZZHDR, `0xF004` QTI EIS real-time, `0xF008` QTI EIS look-ahead, `0xF010`
   FOVC, `0xF040` FS2; L269–273, L5614–5631) [V], and FreeDcam lists Xiaomi modes such as `0x8004` VIDEO, `0x801D`
   VIDEO_EIS_8K, `0x8024` HDR10, `0x803C` HSR_60, `0x8078` HFR_120 (`camera2_hidden_keys/xiaomi/OpCode.java`) [V].
   Whether stock OEM camera services accept these from an unprivileged third-party package is **not known** [U]; it is
   exactly what a probe can find out, cheaply and safely (a refused session).
8. **Native Log exists behind vendor tags on some phones.** Xiaomi: `xiaomi.pro.video.log.enabled` (FreeDcam
   `CaptureRequestXiaomi`, `ProVideoLogDetector`, `XiaomiHdr10Processor`) [V that FreeDcam sets it; U that it works on a
   given phone]. OPPO exposes `com.oplus.log.video.mode` (spike dump) [V that the tag exists; U on values/effect].
   Sony's pipeline is unlocked by three tags its own apps always send (`stoutput/xperia-camera-mod`) [U]. Some OEMs
   gate lenses or modes by package name: `vendor.camera.aux.packagelist` (Xiaomi/Qualcomm devices; Camera FV-5's own
   root recipe) [V that the property is documented by FV-5], OxygenOS/ColorOS 12 main-sensor-only reports [U, 2021].
9. **Android's probing APIs only half-solve this.** `CameraDevice.isSessionConfigurationSupported` (API 28) needs an
   open camera and **ignores session parameters** (CameraX `CameraDeviceSetupCompat` docs) [V], so it cannot see the
   Pixel's "HLG10 above 30 fps" rule. `CameraDeviceSetup.isSessionConfigurationSupported` (API 35) checks session
   parameters without opening the camera, takes "a few milliseconds per call", but is only *guaranteed accurate* for
   the combinations CTS verifies at `INFO_SESSION_CONFIGURATION_QUERY_VERSION` 35: PRIV 1080p/720p (+ JPEG/JPEG_R),
   stabilisation OFF/PREVIEW, fps {*,30}/{*,60}, STANDARD/HLG10 [V]. 4K PRIV video streams are not in that list, so a
   "yes" for 4K60 HLG10 is a hint, and a real session is the proof. The AndroidX wrapper's Play Services backend is
   proprietary and must stay out of the F-Droid build.
10. **Camera FV-5 is a stills app; its relevance is its device database.** Video is handed to the separate Cinema FV-5
    ("Prefer external applications"). Flavionet keeps a database of 29,374 devices / 2,605 manufacturers with "Camera2
    keys (including manufacturer-private keys)", licensed commercially on request (camerafv5.com/devices/licensing) [V].
    It shows that a per-device capability catalogue is the industry answer, but we cannot use theirs.

## 2. Open Camera in detail

File paths below are relative to `app/src/main/java/net/sourceforge/opencamera/` at commit `0dd4cbe`. Line numbers
are approximate (±5).

### 2.1 Architecture in one paragraph
`cameracontroller/CameraController` (abstract) with `CameraController1` (old API) and `CameraController2` (8,815 lines)
hides the APIs and "encapsulate[s] code that covers driver specific behaviours (or driver bug workarounds!)"
(`opencamera_source.txt`). `cameracontroller/Camera2Settings` holds the per-request state and applies it to a
`CaptureRequest.Builder`. `preview/Preview` (9,364 lines) drives the camera and video recording through
**MediaRecorder** with a `VideoProfile` built from `CamcorderProfile` (`preview/VideoProfile.java`). There is no GL
pipeline for video; MediaCodec/MediaMuxer appear only in `Preshots.java` (pre-capture clips). minSdk 23, targetSdk 36.

### 2.2 Video resolutions and frame rates
- `CameraController2.getCameraFeatures()` (L1748): AE ranges from `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES` (L2178);
  sizes from `configs.getOutputSizes(MediaRecorder.class)` (L2189), skipping anything above 4096×2160 (L2201); for each
  size `max_fps = 1e9 / getOutputMinFrameDuration(MediaRecorder.class, size)` and a single range `[min AE fps, max_fps]`
  (L2204–2212) [V].
- High speed: only if `REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO` and **no physical camera selected**
  ("seems unreliable on Pixel 6 Pro and Galaxy S24+", L2219); ranges with `min != max` are skipped because
  `createHighSpeedRequestList` requires a fixed range and Pixel 6 Pro advertises `[30-120]` (L2222–2236); per-size
  ranges not present in the global list are dropped (L2252–2268) [V].
- UI list: `MainActivity` ~L2844, candidates `{15,24,25,30,60,96,100,120,240}`, each kept only if high speed or
  `VideoQualityHandler.videoSupportsFrameRate()` (L166) [V].
- Applying fps: `Preview.setPreviewFps()` (L4923) → `matchPreviewFpsToVideo()` (L4813, narrowest AE range containing the
  rate, else the closest) → `CameraController2.setPreviewFpsRange()` (L4436) sets `CONTROL_AE_TARGET_FPS_RANGE` **and**
  `SENSOR_FRAME_DURATION` on the repeating request [V]. Not as a session parameter.
- `Preview.getVideoProfile()` (L3607): if the profile's fps is not supported at that size (Pixel 6 Pro 1920×1440),
  pick the closest supported fps (L3659–3681) [V].
- Failure UX: `Preview.getErrorFeatures()` (L5180) tells the user which non-default option (4K forced, bitrate, fps,
  slow motion) probably caused a failed recording [V]. This is "try and report", but only for the user's own choice.
- Help text: bitrate "may be unsupported … in some cases, this can cause problems with the camera that require a reboot"
  (`_docs/help.html` ~L1123) [V]. A real risk for any prober.

### 2.3 Session creation
`createCaptureSession()` (L5285): regular sessions use `SessionConfiguration(SESSION_REGULAR, …)` only when a physical
camera or JPEG_R is needed, otherwise the deprecated `createCaptureSession(surfaces, …)` (L5720–5772); high speed uses
`SessionConfiguration(SESSION_HIGH_SPEED, …)` or `createConstrainedHighSpeedCaptureSession` (L5672–5712); extension
sessions for photo modes (L5613–5660). **No `setSessionParameters`, no `isSessionConfigurationSupported`, no
`CameraDeviceSetup`** (grep over the tree) [V]. It catches `NullPointerException`, `IllegalArgumentException` and
`UnsupportedOperationException` thrown "from deep within createCaptureSession" on Google Play devices (L5776–5840) [V].

### 2.4 10-bit, HDR, Log
- Reads `REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT` and `DynamicRangeProfiles` only to decide JPEG_R
  (L1963, L2041–2075) [V]. No 10-bit video, no HEVC Main10 profile setting (HEVC is plain
  `MediaRecorder.VideoEncoder.HEVC`, `Preview` L3843) [V].
- Video "picture profiles" (`MyApplicationInterface.getVideoTonemapProfile()` ~L862): off, REC709, sRGB (preset or
  contrast curve), gamma N, log at five strengths, and JTVideo/JTLog/JTLog2 (point tables contributed by JT Haapala,
  `CameraController2` L130–187). Log curve: `out = log1p(a·in) / log1p(a)`, `a ∈ {10, 32, 100, 224, 500}`
  (`Camera2Settings.getLogProfile()` L727, `MyApplicationInterface.getVideoLogProfileStrength()` ~L891) [V].
- Tonemap quirks: Samsung needs ≥16–32 curve points but loses peak brightness above 32, OnePlus 3T improves up to 64
  (`CameraController2.enforceMinTonemapCurvePoints()` L1506; `Camera2Settings` L838–848) [V]. Version history: "REC709
  and sRGB video picture profiles gave errors on some devices (e.g. Pixel)" (`_docs/history.html` ~L135) [V].
- Relevance for us: we apply Log in the GPU from 10-bit (REQ-COL-002), so the curve maths is not needed; the
  `TONEMAP_CURVE` route is a possible **8-bit fallback** to get a flatter image out of the ISP before quantisation on
  phones without 10-bit, and the quirks above are what to expect.

### 2.5 Vendor-specific code, by manufacturer
There is **no vendor tag, vendor session mode or reflection** anywhere in Open Camera (grep for `new CaptureRequest.Key`,
`com.`/`org.codeaurora` strings, `getDeclaredMethod`) [V]. All device handling is `Build.MANUFACTURER`/`MODEL` checks:

| Brand | Where | What and why |
|---|---|---|
| Samsung (all) | `CameraController2` L1142, L1418, L1512, L6235, L7042; `Camera2Settings` L90, L848; `MainActivity.setDeviceDefaults()` L748 | 32 tonemap points; video-start sound instead of shutter sound; run a post-capture step for manual exposure in photo mode; no fast burst for NR; new installs disable fast HDR/expo burst; Camera2 by default on Android 12+ (`MainActivity` L816) |
| Samsung Galaxy S/Fold-Flip (`sm-g`, `sm-s`, `sm-f`), Android 10+ | `CameraController2` L2599 | Max exposure boosted to 0.5 s although the HAL says 0.1 s |
| Samsung S7 (`sm-g93`) | `Camera2Settings` L397, L443 | Force `EDGE_MODE_OFF` and `NOISE_REDUCTION_MODE_OFF` (glow and blur) |
| Google | `MainActivity` L810–818 | Default to Camera2 on Android 12+ |
| Google Pixel 5a, Nokia G42 5G, HMD Fusion/Skyline | `CameraController2` L1146–1149 | `block_vendor_extensions`: `getCameraExtensionCharacteristics`/`getSupportedExtensions` takes ages or ANRs |
| Nexus 6 | `CameraController2` L3192 (no manual WB), L2201 (fake >4K sizes); `Preview` L4953 | Manual WB broken; preview too dark unless fps range set |
| Nokia (HMD) | `MainActivity` L811; `Preview` L5764 | Camera2 default on Android 9+; camera server dies on `setNextOutputFile` with `setMaxDuration` |
| OnePlus | `MainActivity` L812–820; `Preview` L5765 | Camera2 default only on Android 14+; `setMaxDuration` ignored after `setNextOutputFile` (3T) |
| OPPO | `_docs/history.html` ~L308 | Old-API crash fix only |
| Xiaomi, Huawei/Honor, vivo, realme | — | **Nothing** [V by grep]. Open Camera has no knowledge to offer for three of our five target brands |

Camera2 is enabled by default only where every camera is at least `LIMITED` (`CameraControllerManager2.allowCamera2Support()`
L229) and the brand is in the list above [V].

### 2.6 Physical lenses
`getPhysicalCameraIds()` of a `LOGICAL_MULTI_CAMERA` (L1991, L2703, ignored if ≤1); the physical id is set with
`OutputConfiguration.setPhysicalCameraId()` (L5216) and per-physical preferences use keys like `"0_2"`
(`PreferenceKeys.getCameraIDKey()`) [V]. Extensions and high speed are disabled for physical ids (Pixel 6 Pro night mode
always uses the wide lens; high speed unreliable) (L1211, L2219) [V].

### 2.7 Manual controls
ISO/exposure exposed only at `LIMITED` or better and only if `SENSOR_INFO_SENSITIVITY_RANGE` and
`SENSOR_INFO_EXPOSURE_TIME_RANGE` are non-null ("may be null on some devices") (L2588–2596) [V]. Preview/video exposure
is capped at 1/5 s (`max_preview_exposure_time_c`, L337; `Camera2Settings.setAEMode()` L498–509) [V]. Manual WB:
Kelvin → RGGB gains with the Tanner Helland black-body approximation and back (`convertTemperatureToRggb()` L415,
`convertRggbToTemperature()` L503), gains clamped to 10 [V]. Focus distance from `LENS_INFO_MINIMUM_FOCUS_DISTANCE`
(L2486). Android 16 `CONTROL_AE_AVAILABLE_PRIORITY_MODES` is only logged (L2611) [V].

### 2.8 Stabilisation
`Camera2Settings.setStabilization()` (L696): digital `CONTROL_VIDEO_STABILIZATION_MODE_ON` turns OIS **off** and restores
the default when digital is off ("Don't set optical image stabilization if video digital stabilization is enabled",
history ~L447) [V]. No `PREVIEW_STABILIZATION`. Not set for extension sessions.

### 2.9 Audio
MediaRecorder audio source only: CAMCORDER (default), MIC, DEFAULT, VOICE_COMMUNICATION, VOICE_RECOGNITION, UNPROCESSED
(`Preview` L3782–3812); mono/stereo channel count; level meter through a separate `AudioRecord(MIC)`
(`AudioListener.java` L48) [V]. **No `AudioDeviceInfo`, no `setPreferredDevice`, no USB/BT selection** [V]. The help
text says an external mic works "if your device supports" it (`_docs/help.html` L1093) [V]. Nothing to reuse for
REQ-AUD; our `core-audio` is already ahead.

### 2.10 Bitrate, encoder, files
- Bitrate list 0.1–200 Mbit/s or "default" (from `CamcorderProfile`, ×2.8 when forcing 4K)
  (`res/values/arrays.xml` L652) [V]. Formats: MPEG-4 H.264/HEVC, 3GPP, WebM VP8 (no audio) (`Preview` L3830–3870) [V].
- Max file size with **seamless restart** through `MediaRecorder.setNextOutputFile()` on
  `MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING` (Android 8+), not combined with `setMaxDuration` because of the Nokia 8
  and OnePlus 3T bugs (`Preview.onVideoInfo()` L5748–5790) [V]. Our MediaMuxer path will need its own split-and-continue
  for FAT32/exFAT SSDs (MP4 > 4 GiB handling of `MediaMuxer` [U]).
- SAF: `ACTION_OPEN_DOCUMENT_TREE`, `DocumentsContract.createDocument` with graceful failure ("reports of this from
  Google Play", `StorageUtils` L811–831), free space via `openFileDescriptor` + `Os.fstatvfs`
  (`StorageUtils.freeMemorySAF()` L1616), called **off the UI thread** because of ANRs (`ui/DrawPreview` L163) [V].
  MediaStore with `IS_PENDING` when not using SAF (`ImageSaver` L2000, L2158) [V].

### 2.11 Thermal and battery
No thermal handling at all (no `PowerManager` thermal API, no headroom) [V by grep]. Only "stop video on critically low
battery" (`PreferenceKeys.VideoLowPowerCheckPreferenceKey`) [V]. Our spike already samples thermal status and headroom.

### 2.12 Lessons distilled from Open Camera's workarounds
Each is a rule for our probe or catalogue, not code to copy:
- A size in the map may be fake (Nexus 6 > 4K); a size's fps may differ from its `CamcorderProfile` (Pixel 6 Pro
  1920×1440): **always verify with a real session**.
- High-speed ranges with `min != max` exist and must not be used for 60 fps; high speed with physical ids is unreliable.
- `createCaptureSession`, `setRepeatingRequest` and extension queries can throw unchecked exceptions or block for
  seconds: **every probe step needs a try/catch for all three exception types and a watchdog timeout**.
- Some settings (bitrate) can wedge the camera until reboot: **probe in an order that minimises the blast radius** and
  persist progress before each step (§6.1).
- Tonemap curves have device-specific point-count bugs: if we ever use `TONEMAP_CURVE`, the point count is a catalogue
  field.

### 2.13 Tests and verification
Two suites: the legacy `androidTest/.../test/MainActivityTest.java` (412 tests, including `testTakeVideo4K`,
`testTakeVideoFPS`, `testTakeVideoFPSHighSpeedManual`, `testVideoLogProfile`, `testTakeVideoMaxFileSize*`,
`testTakeVideoForceFailure`, `testTakeVideoStabilization`, `testTakeVideoSAF`) and the newer JUnit4
`InstrumentedTest.java` (244 tests) split into category suites (`VideoInstrumentedTests` etc.); one JVM unit test file
[V]. They run on the author's own phones; there is no device farm or capability probe [U, inferred from the code and
the `Nexus7InstrumentedTests`/`OldDeviceInstrumentedTests` suites]. Idea worth copying: **forced-failure tests**
(`testTakeVideoForceFailure`, `testTakeVideoForceIOException`, `testTakeVideoForceCameraControllerException`) that
inject an error to check recovery.

### 2.14 What we can reuse, with licence notes
Open Camera is **GPL-3.0-or-later**; we are GPL-3.0-or-later, so adapted code is allowed. Its source files have **no
per-file headers**; the licence is stated in `opencamera_source.txt` §Licence and `_docs/index.html` §Licence. When
adapting, add to the Kotlin file: `Portions adapted from Open Camera (https://opencamera.org.uk), Copyright Mark
Harman, GPL-3.0-or-later, file <path> at commit 0dd4cbe`, and list it in `LICENSES.md`.

| Candidate | Path (commit `0dd4cbe`) | Worth it? |
|---|---|---|
| Kelvin ↔ RGGB gains | `cameracontroller/CameraController2.java` `convertTemperatureToRggb()` L415, `RGBtoGain()`, `convertRggbToTemperature()` L503, `GaintoRGB()` | Yes, small and isolated (REQ-CAM-002). The algorithm is the public Tanner Helland approximation; we could also write it from the formula |
| AE fps range matching | `preview/Preview.java` `matchPreviewFpsToVideo()` L4813, `chooseBestPreviewFps()` L4865 | Yes as a reference for the "narrowest range containing the target" rule; trivial to re-implement |
| High-speed range filtering | `CameraController2.getCameraFeatures()` L2219–2280 | Re-implement the rules (min==max, subset check, no physical ids) |
| Tonemap point enforcement | `CameraController2.enforceMinTonemapCurvePoints()` L1506 | Only if we add the 8-bit `TONEMAP_CURVE` fallback |
| Seamless file split logic | `Preview.onVideoInfo()` L5748 | Design reference only (MediaRecorder-specific) |
| SAF free space off the UI thread | `StorageUtils.freeMemorySAF()` L1616 | Re-implement (10 lines); keep the "background thread" rule |
| Device quirk table | §2.5 above | Seed rows of our catalogue's quirk list (as data with a source note, not code) |
| JTLog curves | `CameraController2` L130–187 (contributed by JT Haapala) | No: 8-bit tonemap tables, and the contributor's terms are only implied by the GPL tree [U] |

Not worth adopting: `Preview`/`CameraController2` structure, MediaRecorder/`CamcorderProfile` path, its extension
session code (photo only), its UI.

## 3. Camera FV-5 (and Cinema FV-5), from public sources only

- **Scope.** Camera FV-5's website and docs describe a stills app (manual controls, bracketing, intervalometer, RAW).
  The only video setting is *Prefer external applications*: "Cinema FV-5 will be used to capture video", passing the
  current photographic parameters (camerafv5.com/docs/settings/reference) [V].
- **Cinema FV-5** (store listings, mostly mirrored on third-party sites): manual ISO/EV/WB/metering changeable during
  recording, focus lock and focus change while recording, zebras, waveform, histogram, codec/bitrate/audio sample-rate
  and channel choice, 4K "on supported devices"; the listings show updates in 2015–2023 [U: third-party mirrors; the
  official listing could not be read]. No Log, 10-bit or vendor-mode claims found.
- **Capability detection, public statements.** "Features like ISO, manual exposure or manual white balance require the
  device to support that … The value range … is also device-dependent" (home page); FAQ: manufacturers must ship a
  "full-compliant camera driver"; some "have blocked third party applications from modifying this parameter"; Sony
  limited third-party apps to 8 MP; LG's stock app has "privileged access" [V]. The settings page has a "Compatibility"
  group "to fix compatibility issues with partially-supported devices" (contents not listed) [V].
- **Device database.** 29,374 devices, 2,605 manufacturers (updated 2024-12-16); the licensing page says it contains
  Camera1 parameters, "Camera2 keys (including manufacturer-private keys)" and derived values; licensing on request by
  e-mail [V]. Device pages show lens, sensor, exposure/ISO ranges (Pixel 8: 1/37,958–16 s, ISO 21–10,666) [V].
  Collection method not stated; most likely uploads from the app's users [U]. **Not usable** (proprietary, no licence),
  but it validates our catalogue approach, including vendor keys.
- **Unlocking.** Their only public "unlock" is a root recipe: add the package to `vendor.camera.aux.packagelist` in
  `/system/build.prop` so auxiliary lenses become visible; "at most 4 apps"; "We DO NOT guarantee that it will work"
  (camerafv5.com/docs/recipes/private-cameras) [V]. No vendor-API or vendor-mode statements found.

## 4. Other projects

| Project | Licence | What is relevant | Status |
|---|---|---|---|
| **FreeDcam** | GPL-2.0-or-later [V] → compatible with our GPL-3.0-or-later | Largest open catalogue of vendor keys: `app/src/main/java/camera2_hidden_keys/{qcom,xiaomi,huawei,samsung,mtk,lg}` (536 distinct `com.*`/`org.codeaurora` names) plus raw dumps from Poco F2 and Galaxy Tab S6 (`devices/`); Xiaomi session op-codes (`xiaomi/OpCode.java`); first-run feature detection (`freed/cam/apis/featuredetector/Camera2FeatureDetectorTask.java` and one `*Detector` per feature, vendor ones implement `VendorKeyDetector`); invokes the hidden `CameraDevice.createCustomCaptureSession` by reflection (`CaptureSessionHandler.java` L331) and reads vendor keys through `RestrictionBypass` (`VendorKeyParser.java`) [V] | Active (2026-07). Use as a **reference list of key names and op-codes** (facts, with attribution); do not copy the reflection/hidden-API code |
| **Snap (CodeAurora Snapdragon Camera)** | Apache-2.0/BSD [V] | Qualcomm keys and how the vendor's own app uses them: sensor-mode table, HFR tables, EIS modes, `sessionParameters.*` keys, opMode in `SessionConfiguration` (§1 items 6–7). Also calls `CameraDevice.setVendorStreamConfigMode` by reflection (L6085), which exists only on CAF-patched frameworks [V that it is reflective; U on availability] | Old (lineage-18.1, Android 11) but the best public documentation of QTI tags |
| **Aperture (LineageOS)** | Apache-2.0 [V] | CameraX-based; per-device **RRO overlays** shipped by device maintainers: `config_additionalVideoConfigurations` adds unadvertised `(camera, size class, fps)` triplets and can mark one unsupported with `-60`; also `config_enableAuxCameras`, `config_ignoredAuxCameraIds`, `config_logicalZoomRatios` (`app/src/main/res/values/config.xml` L8–86) [V] | Active. Confirms that "the map lies, keep a curated per-device table" is standard practice |
| **MotionCam** | Old open revision GPL-3.0, archived 2022 (`TadiT7/motioncam`); MotionCam Pro proprietary; `mirsadm/motioncam-decoder` Apache-2.0 [V metadata] | RAW video pipeline; not studied further (archived, RAW focus) | — |
| **mcpro24fps** | Proprietary | Public spec: AVC/HEVC/APV, 8/10-bit, HLG10/HDR10/Dolby Vision "and others", "all resolutions defined by the device's Camera2 API", 4K/8K, up to 500 Mbit/s, Log and LUT tools, stabilisation "depends on … approvals from the manufacturer for 3rd-party applications", dropped-frame counter, overheat indicator, bitrate mismatch detector (mcpro24fps.com/?p=46) [V]. Forum users: 60 fps often 8-bit only and unstable [U] | Competitor benchmark; no unlock claims |
| **xperia-camera-mod** | not stated | LSPosed hook adding three Sony tags (`vagueControlMode`, `usecase`, `yuvFrameDrawMode`) to every request activates Sony's processing, including S-Log; "wrong type causes the HAL to silently discard the tag" [U] | Shows that some vendor features need only request tags, which a normal app can set |

## 5. Vendor tags, vendor session modes and Android's probing APIs

### 5.1 What we already see on our phones
OPPO CPH2841 (SM8850): `com.oplus.camera.mode`, `com.oplus.log.video.mode`, `com.oplus.video.stabilization.mode`,
`org.codeaurora.qcamera3.sessionParameters.SensorModesInConfig`, `com.qti.qualcomm.spatialVideo.SpatialVideoMode`.
Pixel 8: 54 `com.google.*` tags including `com.google.internal.IsCameraApp1P` and `ProcessingMode` (spike report) [V].
No public documentation was found for any `com.oplus.*` tag [V: searches returned nothing]; their types and values
must be learned from the characteristics themselves (many vendor characteristics are lists of supported values) and
from session acceptance.

### 5.2 Mechanisms, from least to most invasive
1. **Vendor characteristics (read only).** Public `CameraCharacteristics.Key(name, type)`; wrong type throws
   `IllegalArgumentException` [V]. Safe. Example: QTI `SensorModeTable`, `available_video_hdr_modes.*`,
   `iso_exp_priority.*`.
2. **Vendor request keys in the repeating request or as session parameters.** Public `CaptureRequest.Key(name, type)`
   [V]. A key not in `getAvailableSessionKeys()` is ignored as a session parameter [V]. Vendor keys that the HAL
   ignores are silent [U], so success must be measured (fps, metadata in `CaptureResult`, image statistics).
3. **Vendor operating mode** in `SessionConfiguration(sessionType, …)`: public constructor, vendor values from `0x8000`
   up [U: framework constant] (Xiaomi `0x80xx`, QTI `0xF0xx`) [V that apps use it; U that framework and OEM service accept it for third-party
   apps on current Android]. A refused mode is a normal `onConfigureFailed` [U for every OEM].
4. **Hidden or reflective framework methods** (`createCustomCaptureSession`, `setVendorStreamConfigMode`,
   `getAllVendorKeys` via `RestrictionBypass`): **excluded** (hidden-API policy, breaks on updates, F-Droid
   anti-features).
5. **Package allow-lists** (`vendor.camera.aux.packagelist`, Samsung camera-ID allow-list, `com.oplus.permission.*`
   camera permissions on `com.oplus.camera`) [U for current models]: cannot be bypassed without root; we can only
   detect them (fewer camera ids/physical ids than the stock app, or a vendor mode refused) and document per model.

### 5.3 Android probing APIs (minSdk 33)
| API | Level | Needs open camera | Checks session parameters (fps, dynamic range) | Reliability |
|---|---|---|---|---|
| `StreamConfigurationMap`, `getOutputMinFrameDuration`, high-speed configs, `DynamicRangeProfiles.getProfileCaptureRequestConstraints` | 21–33 | No | — | Advertised only; the OPPO under-advertises, the Pixel over-promises HLG10 at 60 [V, spike] |
| `CameraDevice.isSessionConfigurationSupported` | 28 | Yes | **No** (CameraX docs: "does not check values set in setSessionParameters") [V] | Weak for our questions |
| `CameraManager.isConcurrentSessionConfigurationSupported` | 30 | No | No ("session parameters will be ignored") [V] | For multicamera only |
| `CameraManager.getCameraDeviceSetup` → `CameraDeviceSetup.isSessionConfigurationSupported`, `getSessionCharacteristics`, `createCaptureRequest` | 35, if `INFO_SESSION_CONFIGURATION_QUERY_VERSION ≥ 35` | **No** | **Yes** [V] | "a few milliseconds per call"; do not "explore the entire space"; guaranteed only for the CTS-verified set (§1 item 9) [V]. Also accepts `OutputConfiguration(Size, Class)` without real surfaces [V] |
| Real session + 2–3 s stream + measured cadence (our spike) | any | Yes | Yes | The ground truth; 1–5 s per configuration |

`CameraExtensionSession` (Night, HDR, Bokeh…) is photo/preview only and does not help video [U for future versions].

## 6. Recommendations for UltimateCamera

### 6.1 Auto-configuration ("probe this phone"), OBS-style
Goal: in about a minute, find for each back/front camera and lens the best *proven* video tiers, and store them in the
`DeviceCapabilityCatalog`, so the UI only offers what works and the degradation ladder (REQ-NFR-001) is per device.

**When.** Offered on first start (skippable, with a "use safe defaults" path), from Diagnostics at any time, and
automatically suggested when the probe key changes: `Build.FINGERPRINT` + app `versionCode` + probe-schema version +
`INFO_SESSION_CONFIGURATION_QUERY_VERSION`. A security patch or OS update changes the fingerprint and invalidates
proven tiers (they become "unverified" until re-probed, never silently trusted).

**Three stages, cheapest first.**
1. **Static read** (< 1 s, no camera open): everything the spike already dumps: maps, AE ranges, high-speed configs,
   10-bit profiles and their constraints, stabilisation modes, physical ids, `getAvailableSessionKeys`, all vendor key
   names, `SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION` (where 8K-class modes may hide on quad-Bayer sensors [U]),
   encoder performance points, GPU caps, and known vendor tables (QTI sensor-mode/HFR/video-config tables) through the
   vendor plug-ins (§6.2). Output: a **candidate list** = advertised tiers ∪ vendor-table tiers ∪ a short fixed list of
   "commonly hidden" tiers (4K60, 4K60 10-bit, 1080p120 regular session, 8K24/30 where the encoder's performance points
   allow it).
2. **Fast query** (API 35+ and query version ≥ 35, no camera open, ~ms each): run each candidate through
   `CameraDeviceSetup.isSessionConfigurationSupported` with `OutputConfiguration(Size, SurfaceTexture.class)` +
   `OutputConfiguration(Size, MediaCodec.class)`, the target fps range and dynamic range profile as session parameters.
   Use it only to **order** and **prune obvious nos** (an explicit `false` inside the CTS-verified set), never to
   promote a tier to proven. Record disagreements with stage 3 in the catalogue; they are useful bug reports.
3. **Real trial** (the spike pipeline, reused): open the camera once per logical/physical camera, then for each
   remaining candidate, highest value first within a group: create the session with session parameters, stream 2 s
   warm-up + 3 s measured through the real GL → MediaCodec path (encoder output to a discarded or temp file on disk,
   D13), and require ≥ 98 % fps and zero sensor/GPU/encoder drops (D10 rule, shorter window). Early exit: once 4K60
   10-bit is proven, skip 4K30 10-bit for that lens (implied); once 4K60 8-bit is refused, skip 4K120.
   Budget: ~20–25 trials, ~5 s each ≈ 2 min worst case; typical < 1 min with pruning.

**Keeping it fast and safe on modest phones.**
- Probe the main back camera first and stop there by default; other lenses and the front camera "on demand" or in a
  "full probe" option.
- Start from cheap tiers only for the safety net (1080p30 must pass first: if it fails, abort and report), then jump to
  the top candidates (binary-search style down the ladder) instead of walking every tier.
- Thermal gate: do not start above `THERMAL_STATUS_LIGHT` or with headroom > 0.7; pause between trials while headroom
  rises; abort at `MODERATE`. Record thermal state per result. Sustained capability (5-minute runs) stays a separate,
  opt-in test (it is a property of the phone + case + ambient, not a capability).
- Battery ≥ 30 % or charging; screen dimmed; no LUT preview animation during the probe.
- Crash safety: write a "probe in progress: step N, config X" marker to disk **before** each risky step; if the app
  restarts with the marker present, mark X as `crashed` (never retried automatically) and continue. Watchdog per step
  (e.g. 8 s); on timeout close the device, mark `timeout`. Catch `IllegalArgumentException`, `NullPointerException`,
  `UnsupportedOperationException`, `CameraAccessException`, `IllegalStateException` (Open Camera §2.3).
- Run in the foreground with progress and a cancel button; never in the background.

**Storing results.** Extend `catalog/devices/*.json` (schema bump) with, per camera id (and physical id):
`provenTiers[]` (config, measured fps, drops, render p95, thermal start/end, timestamp, probe key),
`rejectedTiers[]` with `reason ∈ {sessionRejected, queryFalse, drops, timeout, crashed, thermalAbort}`,
`queryDisagreements[]`, `vendorFeatures{}` (from §6.2, each with `state ∈ {present, accepted, effective, refused}`),
and `probeKey`. The on-device catalogue is local and regenerable (docs/01), not in the backup; the owner's curated
seed files in the repo come from the same JSON (shared through the spike's report flow). Seed files can carry
`knownGood`/`knownBad` lists like Aperture's overlays, used as candidates and as warnings, still re-verified on the
user's phone.

**Using results.** The UI offers proven tiers normally, unverified-but-advertised tiers behind a "may not work" badge
(pro users want to try), and never offers rejected ones without a "re-test" action. The REQ-NFR-001 ladder is built from
proven tiers of that lens.

### 6.2 Vendor features behind a manufacturer interface
- `core-camera` defines `interface VendorCameraPlugin { fun matches(info: DeviceInfo, chars: CameraCharacteristics):
  Boolean; fun readStatic(chars): VendorStaticInfo; fun candidateTiers(...): List<Candidate>;
  fun sessionTweaks(feature): SessionTweak }` where `SessionTweak` = extra session-parameter keys, repeating-request
  keys and an optional operating-mode value. Plug-ins: `QualcommPlugin` (keys under `org.codeaurora.*`, `org.quic.*`,
  `com.qti.*`), `OplusPlugin`, `XiaomiPlugin`, `VivoPlugin`, `HonorPlugin`, `SamsungPlugin`, `GooglePlugin`, `NoOp`.
  Matching by **presence of key names**, not by `Build.MANUFACTURER` (OPPO, OnePlus and realme share Oplus; Qualcomm
  keys appear on many brands).
- Only public API: `CameraCharacteristics.Key`/`CaptureRequest.Key` constructors and `SessionConfiguration`'s public
  constructor. No reflection, no hidden API, no root features. A plug-in may not touch anything else.
- Each vendor feature goes through the same probe states: **present** (key listed) → **accepted** (session created with
  it) → **effective** (measurable effect: fps reached, `CaptureResult` echoes the value, or an image statistic changes,
  e.g. a Log mode lowers contrast / lifts shadows on a fixed scene) → offered in the UI as "vendor" with a warning.
  Value discovery for unknown enums (`com.oplus.log.video.mode`): try only values listed by a matching vendor
  characteristic; never brute-force integer ranges (blast radius, §2.12).
- First experiments, in order of value: (1) dump QTI `SensorModeTable`/`VideoConfigurationsTable`/`CustomHFRFpsTable` on
  the OPPO and compare with what the spike measured (explains 4K60 and may reveal 8K and 120 fps tiers); (2)
  `com.oplus.log.video.mode` in session parameters with 10-bit HLG10, measured against our own GPU Log; (3) QTI EIS
  operating modes `0xF004`/`0xF008` and `com.oplus.video.stabilization.mode` vs `PREVIEW_STABILIZATION`; (4) Xiaomi
  `xiaomi.pro.video.log.enabled` and op-codes `0x8004`/`0x801D` when a Xiaomi phone is available; (5) Pixel:
  confirm `CameraDeviceSetup` returns `false` for 4K60 HLG10 (if it does, stage 2 could prune it without a session).

### 6.3 What to adopt from Open Camera
Adopt as data/rules (no licence obligation beyond good practice, credit in the doc): the quirk rules of §2.12 and the
quirk table of §2.5 as catalogue entries. Adopt as adapted code (GPL-3.0-or-later, attribution header + `LICENSES.md`):
Kelvin↔RGGB conversion; optionally the tonemap point enforcement if an 8-bit `TONEMAP_CURVE` fallback is added. Copy the
testing idea of forced-failure instrumented tests for session refusal, IOException and camera disconnects. Do not adopt
the MediaRecorder/`CamcorderProfile` design or the extension-session code. From FreeDcam (GPL-2.0-or-later, compatible)
take key names and op-code values as reference data with attribution; from Snap (Apache-2.0) the QTI table formats;
both are facts about vendor interfaces rather than copied code.

### 6.4 Risks
| Risk | Mitigation |
|---|---|
| HAL crash or camera service restart during a probe (seen by Open Camera users with unusual bitrates; vendor modes are untested territory) | Crash marker on disk, never auto-retry a crashed config, one camera open at a time, watchdog, vendor experiments only in "full probe", catalogue flags dangerous configs per fingerprint |
| Camera wedged until reboot | Detect "cannot open camera" after a probe step, stop and tell the user to reboot; mark the step `crashed` |
| Thermal: probing heats the phone and later trials fail for thermal reasons, not capability | Thermal gate and cool-down; record thermal per result; sustained test separate |
| A config passes 5 s but fails in long takes (thermal, storage speed) | Proven tier ≠ sustained tier; REQ-NFR-001 ladder handles it live; SSD speed test (docs/01) |
| OS/firmware updates change behaviour (OPPO 4K60 could vanish; Pixel could gain HLG10@60) | Probe key includes `Build.FINGERPRINT`; re-probe prompt; results older than the fingerprint shown as unverified |
| `CameraDeviceSetup` answers wrong outside the CTS set | Only prunes and orders; real trial decides; disagreements logged |
| Vendor tags silently ignored → false "accepted" | Three-state model; "effective" requires a measured effect |
| Vendor modes accepted but degrade quality (e.g. extra noise reduction, crop) | Effectiveness check includes field of view/crop (compare frame with standard mode) [U how precisely]; vendor features stay opt-in |
| OEM allow-lists hide lenses or modes from third-party apps | Detect and document; no bypass |
| Hidden-API or Play Services dependencies sneaking in (FreeDcam `RestrictionBypass`, CameraX `featurecombinationquery-play-services`) | Explicit rule in this doc; F-Droid scanner and dependency verification (D21) catch new artifacts |
| Probe takes too long → users skip it | Main camera only by default, < 1 min target, safe defaults if skipped |

## 7. Open questions for the owner
1. Should the auto-configuration run automatically on first start (with skip), or only from Diagnostics?
2. Are vendor features (Oplus Log mode, QTI EIS modes, Xiaomi op-codes) acceptable in the main build as opt-in
   "experimental vendor" options, given they may be refused or misbehave after OS updates?
3. Should unverified-but-advertised tiers be offered at all (badge "may not work"), or only proven tiers?
4. 8K: is it a v1 goal? It needs encoder performance points ≥ 8K30, maximum-resolution sensor modes and probably a
   different GL path (memory), and it is the most likely to crash HALs on modest phones.
5. May probe results (anonymised: model, fingerprint, proven/rejected tiers, vendor key names, no images) be exported
   by the user as a JSON file to contribute to `catalog/devices/` (no telemetry, manual share only)?
6. Is rooting/`build.prop` guidance (like FV-5's auxiliary-camera recipe) in scope for docs, or explicitly out?

## 8. Links
- Open Camera source: https://sourceforge.net/p/opencamera/code/ (commit `0dd4cbe`); help: https://opencamera.org.uk/help.html
- FreeDcam: https://github.com/KillerInk/FreeDcam
- Snap (CodeAurora camera, LineageOS mirror): https://github.com/LineageOS/android_packages_apps_Snap
- Aperture: https://github.com/LineageOS/android_packages_apps_Aperture
- Camera FV-5: https://www.camerafv5.com/ , FAQ https://www.camerafv5.com/faq/ , settings https://www.camerafv5.com/docs/settings/reference/ ,
  device database https://www.camerafv5.com/devices/ and https://www.camerafv5.com/devices/licensing/ ,
  private cameras recipe https://camerafv5.com/docs/recipes/private-cameras
- mcpro24fps specification: https://www.mcpro24fps.com/?p=46
- MotionCam (archived GPL-3 revision): https://github.com/TadiT7/motioncam ; decoder https://github.com/mirsadm/motioncam-decoder
- Sony tags module: https://github.com/stoutput/xperia-camera-mod
- Android: https://developer.android.com/reference/android/hardware/camera2/CameraDevice.CameraDeviceSetup ,
  https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#INFO_SESSION_CONFIGURATION_QUERY_VERSION ,
  https://developer.android.com/reference/android/hardware/camera2/params/SessionConfiguration ,
  https://developer.android.com/reference/android/hardware/camera2/CaptureRequest.Key ,
  https://developer.android.com/reference/androidx/camera/featurecombinationquery/CameraDeviceSetupCompatFactory
- OxygenOS 12 lens restriction report (2021): https://androidauthority.com/oneplus-gcam-oxygen-os-12-3074950
