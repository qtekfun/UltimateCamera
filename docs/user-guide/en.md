# UltimateCamera — user guide

A short, task-oriented guide. Quoted names are the app's English labels; *Settings › X* means the gear button on the
camera screen, then section X. Español: [es.md](es.md).

## 1. First start and auto-configuration
1. Grant camera and microphone. Without them the app only shows the permission notice.
2. **Auto-configuration** is offered (you can skip it). It tries each recording mode for a few seconds with camera,
   LUT and encoder together and keeps the ones the phone really holds without dropping frames. It takes a few
   minutes and stops by itself if the phone gets hot.
3. When it finishes tap "Apply". The format picker then lists only the modes that held. Run it again from
   *Settings › Auto-configuration* (after a system update, for example).

## 2. Recording
- **REC** (the big red button) starts and stops. With *Settings › Controls › Volume keys* the volume keys do too.
- At the top, the status strip: red dot and timecode, format, codec and 10-bit; on the right storage left, battery
  and temperature. Below it, dropped frames and bitrate during a take.
- The timecode is the take's running time or the time of day (*Settings › Camera › Timecode*).
- Nothing is lost if the app is killed: the take is recovered when you open it again.

## 3. Modes and codecs
- **Format** chip ("4K30", "1080p60"…): resolution and frame rate. If the phone refuses a mode, the app says why and
  keeps the previous one.
- **Codec**: HEVC (default, 10-bit), H.264 (widest compatibility, 8-bit) or AV1 where the phone has an encoder.
- **10-bit HLG** is recorded as real HDR; phones with an HDR panel show it in HDR (*Settings › Display › HDR
  viewfinder*).
- **Stabilisation**: chip with the electronic modes the camera offers; optical is on where it exists. Changing it
  reopens the camera, not during a take.

## 4. Lenses
- The pills above the controls ("0.5 · 1× · 2") switch lenses; you can also pinch the image to zoom.
- During a take only the zoom changes; opening another camera (front, or lenses listed separately) needs REC off.

## 5. Manual controls
Tap a readout (ISO, SHUTTER, WB, FOCUS, EV) to open its control. "Auto" returns to automatic. AE-L, AF-L and AWB-L
lock exposure, focus and white balance (amber = locked). Tap the image to focus there.

## 6. LUTs and UC-Log
- **LUT** chip: the look burnt into the file, or "Monitor only" (changes what you see; the file is not touched).
- **UC-Log** is a flat 10-bit profile for grading later. With "Auto" the monitor shows UC-Log → Rec.709 so you can
  judge exposure while recording Log.
- *Settings › LUTs* imports `.cube` files (17 or 33 points) and previews them.

## 7. Monitor tools
**Monitor** button: zebras (level of your choice, plus skin zebras 65–75 %), false colour, focus peaking (colour of
your choice), histogram, waveform and vectorscope, frame lines, guides and desqueeze. They only affect the screen.
*Settings › Display › Maximum brightness* raises the screen to full brightness while the camera is open (opt-in).

## 8. Storage and USB drives
- Tap the storage readout to choose where takes go: internal storage, SD card or a USB stick/drive.
- A new destination gets a write-speed test; if it is too slow for the current mode you are warned before recording.
- If the destination goes away (stick unplugged) recording continues on internal storage and the strip says so in
  amber.
- On FAT32 long takes are split into files under 4 GB.

## 9. Audio and WAV
- *Settings › Audio*: microphone (built-in, USB, Bluetooth), gain and level meter. If the chosen microphone fails the
  built-in one is used and you are told.
- **Separate WAV** also writes an uncompressed WAV next to each video, in sync with the picture and with the timecode
  in its metadata.

## 10. Photo and HDR
- The mode button (next to Settings) switches between video and photo.
- **HDR** on "Auto" uses the maker's HDR where the camera offers it, else Ultra HDR, else our own fusion of three
  exposures (a few seconds of "Processing…"). *Settings › Photo* changes the default.

## 11. Multicamera
- *Multicamera button › Role*: **Camera** (records and can be controlled), **Monitor** (sees and controls one camera)
  or **Master** (sees and controls several, "REC all").
- **Pairing**: on the camera "Show code"; on the monitor scan the QR or type the short code. Both phones on the same
  Wi-Fi, or one sharing a hotspot with the other. Pair once; afterwards they find each other.
- The monitor shows live video, the camera's status strip and alerts, and controls REC, exposure, focus, white
  balance, LUT and format. Takes start in sync on a common clock.
- **Proxies**: with *Settings › Multicamera › Proxy* the camera also records a light copy; the monitor downloads it
  from "Proxies" to review or edit on the spot.

## 12. Backup
*Settings › Backup › Export backup* saves settings, the LUT library and, optionally, this phone's measured
capabilities in one `.zip`, encrypted with a password (AES-256) if you set one; without it the file cannot be opened
or recovered. "Import backup" restores it here or on another phone (capabilities are only used on the same phone and
system version). Multicamera pairings are not included: pair again with the QR.

## 13. Accessibility
- Every control has a name and state for TalkBack; REC says "Stop recording, Recording, 00:01:12", the status strip
  announces the time once a minute and critical alerts interrupt.
- Text up to 200 %: text wraps onto more lines; numbers (timecode, values) grow up to 150 % so they do not cover the
  picture.
- With the system's *Remove animations* the fades and the REC pulse are off.

## 14. Troubleshooting
- **Heat**: a banner predicts the minutes to overheating and suggests a lighter mode (4K30 or 1080p). If the system
  reaches critical, the take is stopped and saved. Remove the case, keep out of the sun, drop to 4K30 or 1080p.
- **Storage**: the banner gives the minutes left at the current bitrate. When it fills up, the take is stopped and
  saved. A slow stick causes dropped frames: use a USB 3 one or record internally.
- **Refused mode**: the phone does not support that combination (resolution, fps, 10-bit, codec or second camera).
  Pick another or run auto-configuration again; modes that did not hold are greyed out with the reason.
- **Battery**: the banner gives the minutes left; below the minimum the take is stopped and saved.
- **Multicamera does not connect**: same network, no client isolation on the router; try one phone's hotspot.
- To report a bug, *Diagnostics* (the i button) exports a report of the phone without personal data.
