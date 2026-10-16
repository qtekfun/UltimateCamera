"""Tests for report.py. Run: python3 -I -m unittest discover -s scripts/spike -p 'test_*.py'"""

import copy
import io
import json
import math
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))  # -I leaves the script directory off sys.path.

import report  # noqa: E402

FULL = HERE / "fixtures" / "synthetic-full.json"
MINIMAL = HERE / "fixtures" / "synthetic-minimal.json"


def run(*argv):
    out = io.StringIO()
    with redirect_stdout(out):
        report.main(list(argv))
    return out.getvalue()


class LoadTest(unittest.TestCase):
    def test_bare_nan_and_infinity_tokens_are_parsed(self):
        self.assertIn("NaN", FULL.read_text())
        stats = report.load(FULL)["network"][1]["stats"]
        self.assertTrue(math.isnan(stats["p50Ms"]))
        self.assertEqual(stats["maxMs"], math.inf)


class MarkdownTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = run("markdown", str(FULL), str(MINIMAL))

    def test_header_fills_the_template(self):
        self.assertIn("### OPPO CPH2841 (QTI SM8850, Android 16, SDK 36) — 2026-10-20, app 0.0.1-fase0\n", self.text)
        # Lower-case Build.BRAND reads as a name.
        self.assertIn("### Google Pixel 8 (Google Tensor G3, Android 17, SDK 37) — 2026-10-21, app 0.0.1-fase0\n", self.text)

    def test_capabilities(self):
        self.assertIn("  - Camera 0 (BACK, logical of 2, 3, 4): 6.7, 2.2, 19.6 mm; 10-bit yes (STANDARD, HLG10, HDR10, HDR10_PLUS);", self.text)
        self.assertIn("4K+ modes: 7680x4320@30, 3840x2160@60; high-speed: 1920x1080@240, 1280x720@480; vendor tags: 7 (", self.text)
        self.assertIn("  - Camera 1 (FRONT): 3.1 mm; 10-bit no (STANDARD);", self.text)
        self.assertIn("- Hardware encoders (2):", self.text)
        self.assertIn("  - HEVC `c2.qti.hevc.encoder`: 10-bit yes, 4K60 yes, performance points: 1920x1080@240, 3840x2160@120, 7680x4320@30", self.text)
        self.assertNotIn("c2.android", self.text)
        self.assertIn("- GPU: Adreno (TM) 840 (OpenGL ES 3.2 V@0800.0); RGBA1010102 recordable yes; BT.2020 HLG yes; GL_EXT_YUV_target yes", self.text)

    def test_pipeline_rows(self):
        self.assertIn("| 1920x1080@30 H264 8-bit HARDWARE | yes | 30.0 | 0/0 | 661/661 | 2.4 | 19.8 | 0→0 (31.0→31.6 °C) | no |  |", self.text)
        self.assertIn("| 3840x2160@60 HEVC 10-bit HARDWARE | no | 58.5 | 0/29 | 1288/1290 | 18.7 | 98.2 | 0→1 (33.0→35.2 °C) | yes |  |", self.text)
        self.assertIn("| 3840x2160@30 AV1 10-bit HARDWARE | unsupported | | | | | | | | no AV1 encoder with a 10-bit profile |", self.text)
        self.assertIn("| 3840x2160@60 HEVC 10-bit HARDWARE (300 s) | no | 59.5 | 2/140 |", self.text)

    def test_sustained_summary(self):
        self.assertIn(
            "- Sustained 3840x2160@60 HEVC 10-bit HARDWARE, 300 s: meets no; fps sensor/GPU/encoder 60.0/59.5/59.5;"
            " thermal status 0 @ 0 s → 1 @ 90 s → 2 @ 181 s; battery 32.0→41.5 °C; first status ≥ 2: 181 s",
            self.text,
        )
        # The 20 s 4K60 run is a matrix entry, not a sustained run.
        self.assertEqual(self.text.count("- Sustained "), 1)

    def test_concurrent_audio_network_errors(self):
        self.assertIn("| 0 + 1 | yes | 0: 29.9, 1: 30.0 |  |", self.text)
        self.assertIn("| 1 + 2 | no | — |  |", self.text)
        self.assertIn("- Audio inputs: 12 USB_DEVICE (RODE VideoMic), 3 BUILTIN_MIC (CPH2841)", self.text)
        self.assertIn("| 12 USB_DEVICE | preferred | yes | 48000/2 | -6.2/-28.4 | 192000/192000 |  |", self.text)
        self.assertIn("| Wi-Fi router | 192.168.1.20:47800 | 1200 B @ 60 Hz | 600/597 | 0.5 | 4.8/9.6/15.2 |  |", self.text)
        self.assertIn("| unlabelled | 192.168.43.1:47800 | 1200 B @ 60 Hz | 600/0 | 100.0 | n/a/n/a/n/a | SocketTimeoutException: Receive timed out |", self.text)
        self.assertIn("- Error: AUDIO: microphone permission not granted", self.text)

    def test_monitor_link(self):
        self.assertIn(
            "| CAMERA | Wi-Fi router | 192.168.1.31 | 84.0/131.0/162.0 | 588/600 |  | MediaCodec c2.qti.avc.encoder | wifi |  |",
            self.text,
        )

    def test_conclusions(self):
        self.assertIn("- Conclusion: highest config meeting target: 1920x1080@30 H264 8-bit HARDWARE; 4K60 10-bit with LUT met target: no.", self.text)
        self.assertIn("- Pipeline: not run.", self.text)
        self.assertIn("- Conclusion: highest config meeting target: not run; 4K60 10-bit with LUT met target: not run.", self.text)

    def test_comparison_table(self):
        tail = self.text.split("### Comparison\n\n", 1)[1]
        self.assertEqual(
            tail.splitlines(),
            [
                "| Model | Brand | SoC | Android | Highest config meeting target | 4K60 10-bit LUT | Sustained |",
                "|---|---|---|---|---|---|---|",
                "| CPH2841 | OPPO | QTI SM8850 | 16 (SDK 36) | 1920x1080@30 H264 8-bit HARDWARE | no | no |",
                "| Pixel 8 | Google | Google Tensor G3 | 17 (SDK 37) | not run | not run | not run |",
            ],
        )

    def test_passing_4k60_lut_and_throttled_sustained(self):
        r = report.load(FULL)
        for run_ in r["pipeline"]:
            if run_["config"]["width"] == 3840 and run_["config"]["fps"] == 60:
                run_["meetsTarget"] = True
        self.assertEqual(report.highest_met(r), "3840x2160@60 HEVC 10-bit HARDWARE")
        self.assertEqual(report.verdict_4k60_lut(r), "yes")
        self.assertEqual(report.verdict_sustained(r), "yes, thermal status 2 at 181 s")

    def test_matrix_only_report(self):
        r = report.load(FULL)
        only = {k: r[k] for k in ("schema", "appVersion", "createdAt", "device", "pipeline")}
        text = report.markdown_section(only)
        self.assertIn("- Capabilities: not measured.", text)
        self.assertNotIn("GPU:", text)
        self.assertIn("| 1920x1080@30 H264 8-bit HARDWARE | yes |", text)

    def test_table_cells_are_escaped(self):
        r = copy.deepcopy(report.load(FULL))
        r["pipeline"][2]["reason"] = "a | b\nc"
        self.assertIn("| a \\| b c |", report.markdown_section(r))


class CatalogTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.out = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, fixture):
        run("catalog", str(fixture), "--out", str(self.out))

    def test_full_report(self):
        self.write(FULL)
        path = self.out / "oppo-cph2841.json"
        text = path.read_text()
        self.assertTrue(text.endswith("}\n"))
        data = json.loads(text)  # Strict JSON: no NaN even though the report had some.
        self.assertEqual(data["device"]["model"], "CPH2841")
        back = data["cameras"][0]
        self.assertEqual(back["id"], "0")
        self.assertEqual(back["physicalIds"], ["2", "3", "4"])
        self.assertEqual(back["tenBitProfiles"], ["HLG10", "HDR10", "HDR10_PLUS"])
        self.assertEqual(back["videoModes"][:2], ["7680x4320@30", "3840x2160@60"])
        self.assertEqual(back["highSpeedModes"], ["1920x1080@240", "1280x720@480"])
        self.assertTrue(back["ois"])
        self.assertFalse(data["cameras"][1]["ois"])
        self.assertEqual(back["zoomRatioRange"], [0.6, 120.0])
        self.assertEqual([e["name"] for e in data["encoders"]], ["c2.qti.avc.encoder", "c2.qti.hevc.encoder"])
        self.assertEqual(data["concurrentSets"], [["0", "1"]])
        self.assertEqual(data["audioInputTypes"], ["BUILTIN_MIC", "USB_DEVICE"])
        self.assertEqual(
            data["tiers"],
            [{"label": "1920x1080@30 H264 8-bit HARDWARE", "width": 1920, "height": 1080, "fps": 30, "codec": "H264", "tenBit": False, "lut": "HARDWARE"}],
        )

    def test_session_rejected_by_the_hal(self):
        data = report.load(FULL)
        data["pipeline"][1].update(sessionRejected=True, error="SessionRejectedException: session configuration failed")
        self.assertIn("| rejected by the camera HAL; SessionRejectedException: session configuration failed |", "\n".join(report.md_pipeline(data)))
        self.assertEqual(report.catalog_entry(data)["rejectedConfigs"], ["3840x2160@60 HEVC 10-bit HARDWARE"])
        self.assertEqual(report.catalog_entry(report.load(FULL))["rejectedConfigs"], [])

    def test_minimal_report_marks_unmeasured_sections(self):
        self.write(MINIMAL)
        data = json.loads((self.out / "google-pixel-8.json").read_text())
        self.assertIsNone(data["tiers"])
        self.assertIsNone(data["concurrentSets"])
        self.assertIsNone(data["audioInputTypes"])
        self.assertEqual(len(data["cameras"]), 1)

    def test_output_is_deterministic(self):
        self.write(FULL)
        first = (self.out / "oppo-cph2841.json").read_bytes()
        self.write(FULL)
        self.assertEqual((self.out / "oppo-cph2841.json").read_bytes(), first)
        # Same content whatever the key order of the input.
        shuffled = report.load(FULL)
        shuffled = json.loads(json.dumps(dict(reversed(list(shuffled.items())))), parse_constant=float)
        self.assertEqual(report.catalog_json(shuffled).encode(), first)

    def test_safe_file_name(self):
        r = report.load(MINIMAL)
        r["device"]["brand"], r["device"]["model"] = "HONOR", "Magic7 Pro/5G (EU)"
        self.assertEqual(report.catalog_name(r), "honor-magic7-pro-5g-eu.json")


if __name__ == "__main__":
    unittest.main()
