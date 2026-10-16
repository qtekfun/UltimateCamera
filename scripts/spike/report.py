#!/usr/bin/env python3
"""Turns Phase 0 spike JSON reports into docs/spike-report.md sections and capability-catalogue seeds.

  python3 scripts/spike/report.py markdown <report.json>...         # one section per phone + comparison table
  python3 scripts/spike/report.py catalog <report.json> [--out DIR] # writes catalog/devices/<brand>-<model>.json

The input schema is the Kotlin SpikeReport (feature-spike). Standard library only, so it runs with `python3 -I`.
"""

import argparse
import json
import math
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
# Runs at least this long are the sustained (thermal) run, not a matrix entry.
SUSTAINED_SECONDS = 120
# PowerManager.THERMAL_STATUS_MODERATE: the first level where the platform asks apps to cut work.
THROTTLING_STATUS = 2
VENDOR_TAGS_LISTED = 5


def load(path):
    # The app writes NaN/Infinity as bare tokens (allowSpecialFloatingPointValues).
    with open(path, encoding="utf-8") as f:
        return json.loads(f.read(), parse_constant=float)


# ---------- shared helpers ----------

def num(value, digits=1):
    """A number for a table cell; missing or non-finite values read as n/a."""
    if value is None or (isinstance(value, float) and not math.isfinite(value)):
        return "n/a"
    return f"{value:.{digits}f}"


def yes_no(value):
    return "n/a" if value is None else ("yes" if value else "no")


def cell(text):
    """Keeps free text (reasons, errors) from breaking a markdown table."""
    return str(text).replace("|", "\\|").replace("\n", " ")


def mode(m):
    return f"{m['width']}x{m['height']}@{m['maxFps']}"


def is_ten_bit_profile(name):
    return name != "STANDARD" and "8B" not in name


def is_4k(width, height):
    return width * height >= 3840 * 2160


def rank(config):
    """Orders pipeline configs from cheapest to most demanding."""
    return (config["width"] * config["height"], config["fps"], config["tenBit"], config.get("lut") is not None)


def met_runs(report):
    return [r for r in report.get("pipeline") or [] if r.get("supported") and r.get("meetsTarget")]


def highest_met(report):
    runs = met_runs(report)
    return max(runs, key=lambda r: rank(r["config"]))["label"] if runs else None


def highest_label(report):
    return highest_met(report) or ("none" if report.get("pipeline") else "not run")


def is_4k60_10bit_lut(config):
    return is_4k(config["width"], config["height"]) and config["fps"] >= 60 and config["tenBit"] and config.get("lut") is not None


def verdict_4k60_lut(report):
    runs = [r for r in report.get("pipeline") or [] if r.get("supported") and is_4k60_10bit_lut(r["config"])]
    if not runs:
        return "not run"
    return "yes" if any(r.get("meetsTarget") for r in runs) else "no"


def sustained_runs(report):
    return [r for r in report.get("pipeline") or [] if r.get("supported") and r.get("measuredSeconds", 0) >= SUSTAINED_SECONDS]


def first_throttling(thermal):
    return next((s["tSeconds"] for s in thermal if s["status"] >= THROTTLING_STATUS), None)


def verdict_sustained(report):
    runs = sustained_runs(report)
    if not runs:
        return "not run"
    run = runs[-1]
    if not run.get("meetsTarget"):
        return "no"
    t = first_throttling(run.get("thermal") or [])
    return "yes" if t is None else f"yes, thermal status {THROTTLING_STATUS} at {t:.0f} s"


def title(report):
    d = report["device"]
    brand = d["brand"][:1].upper() + d["brand"][1:] if d["brand"].islower() else d["brand"]
    return brand, d["model"]


def soc(d):
    return " ".join(p for p in (d.get("socManufacturer"), d.get("socModel")) if p) or "unknown SoC"


# ---------- markdown ----------

def md_capabilities(report):
    out = []
    cameras = report.get("cameras")
    if cameras is None:
        out.append("- Capabilities: not measured.")
    else:
        out.append(f"- Cameras ({len(cameras)}):")
        for c in cameras:
            logical = f", logical of {', '.join(c['physicalIds'])}" if c.get("physicalIds") else ""
            focal = ", ".join(f"{f:g}" for f in c.get("focalLengthsMm") or []) or "?"
            profiles = c.get("dynamicRangeProfiles") or []
            ten_bit = any(is_ten_bit_profile(p) for p in profiles)
            big = [mode(m) for m in c.get("videoModes") or [] if is_4k(m["width"], m["height"])]
            vendor = sorted(set(c.get("vendorCharacteristicKeys", []) + c.get("vendorRequestKeys", []) + c.get("vendorResultKeys", [])))
            shown = ", ".join(vendor[:VENDOR_TAGS_LISTED]) + (", ..." if len(vendor) > VENDOR_TAGS_LISTED else "")
            out.append(
                f"  - Camera {c['id']} ({c['facing']}{logical}): {focal} mm; 10-bit {yes_no(ten_bit)}"
                f" ({', '.join(profiles) or 'none'}); colour spaces: {', '.join(c.get('colorSpaces') or []) or 'none'};"
                f" 4K+ modes: {', '.join(big) or 'none'}; high-speed: {', '.join(c.get('highSpeedModes') or []) or 'none'};"
                f" vendor tags: {len(vendor)}{f' ({shown})' if vendor else ''}"
            )
    encoders = report.get("encoders")
    if encoders is not None:
        hardware = [e for e in encoders if e.get("hardware")]
        out.append(f"- Hardware encoders ({len(hardware)}):" if hardware else "- Hardware encoders: none.")
        for e in hardware:
            points = e.get("performancePoints") or []
            out.append(
                f"  - {e['codec']} `{e['name']}`: 10-bit {yes_no(e.get('tenBitProfile'))},"
                f" 4K60 {yes_no((e.get('supports') or {}).get('3840x2160@60'))},"
                f" performance points: {', '.join(points) or 'none'}"
            )
    gpu = report.get("gpu")
    if gpu is not None:
        ext = ", ".join(f"{k} {yes_no(v)}" for k, v in sorted((gpu.get("extensions") or {}).items()))
        out.append(
            f"- GPU: {gpu['renderer']} ({gpu['version']}); RGBA1010102 recordable {yes_no(gpu.get('rgba1010102Recordable'))};"
            f" BT.2020 HLG {yes_no(gpu.get('bt2020HlgColorspace'))}; {ext}"
        )
    return out


def thermal_span(thermal):
    if not thermal:
        return "n/a"
    statuses = [s["status"] for s in thermal]
    temps = [s["batteryCelsius"] for s in thermal if s.get("batteryCelsius") is not None]
    text = f"{statuses[0]}→{max(statuses)}"
    return text + (f" ({temps[0]:.1f}→{max(temps):.1f} °C)" if temps else "")


def md_pipeline(report):
    runs = report.get("pipeline") or []
    if not runs:
        return ["- Pipeline: not run."]
    out = [
        "",
        "| Config | Meets | GPU fps | Drops sensor/GPU | Enc/GPU frames | Render p95 ms | Mbps | Thermal start→max | Max brightness | Reason/error |",
        "|---|---|---|---|---|---|---|---|---|---|",
    ]
    for r in runs:
        name = r["label"] + (f" ({r['measuredSeconds']:.0f} s)" if r.get("measuredSeconds", 0) >= SUSTAINED_SECONDS else "")
        note = cell("; ".join(x for x in (r.get("reason"), r.get("error"), r.get("previewError") and f"preview: {r['previewError']}") if x))
        if not r.get("supported"):
            out.append(f"| {name} | unsupported | | | | | | | | {note} |")
            continue
        sensor, gpu = r.get("sensor") or {}, r.get("gpu") or {}
        drops = f"{sensor.get('dropped', 'n/a')}/{gpu.get('dropped', 'n/a')}"
        frames = f"{r.get('encodedFramesTotal', 0)}/{r.get('gpuFramesTotal', 0)}"
        p95 = num((r.get("renderCpu") or {}).get("p95Ms"))
        out.append(
            f"| {name} | {yes_no(r.get('meetsTarget'))} | {num(gpu.get('fps'))} | {drops} | {frames} | {p95} |"
            f" {num(r.get('encodedMbps'))} | {thermal_span(r.get('thermal'))} | {yes_no(r.get('maxBrightness'))} | {note} |"
        )
    out.append("")
    return out


def status_timeline(thermal):
    """Compresses one-per-second samples into status changes, e.g. `0 @ 0 s → 1 @ 90 s`."""
    steps = []
    for s in thermal:
        if not steps or steps[-1][0] != s["status"]:
            steps.append((s["status"], s["tSeconds"]))
    return " → ".join(f"{st} @ {t:.0f} s" for st, t in steps)


def md_sustained(report):
    out = []
    for r in sustained_runs(report):
        thermal = r.get("thermal") or []
        temps = [s["batteryCelsius"] for s in thermal if s.get("batteryCelsius") is not None]
        battery = f"{temps[0]:.1f}→{temps[-1]:.1f} °C" if temps else "n/a"
        first = first_throttling(thermal)
        out.append(
            f"- Sustained {r['label']}, {r['measuredSeconds']:.0f} s: meets {yes_no(r.get('meetsTarget'))};"
            f" fps sensor/GPU/encoder {num((r.get('sensor') or {}).get('fps'))}/{num((r.get('gpu') or {}).get('fps'))}/{num(r.get('encodedFps'))};"
            f" thermal status {status_timeline(thermal) or 'n/a'}; battery {battery};"
            f" first status ≥ {THROTTLING_STATUS}: {'never' if first is None else f'{first:.0f} s'}"
        )
    return out


def md_concurrent(report):
    concurrent = report.get("concurrent")
    if concurrent is None:
        return []
    sets = concurrent.get("sets") or []
    if not sets:
        return ["- Concurrent cameras: none listed."]
    out = ["", "| Concurrent set | Declared | Measured fps | Error |", "|---|---|---|---|"]
    for s in sets:
        fps = ", ".join(f"{k}: {num(v)}" for k, v in sorted(s.get("measuredFps", {}).items())) or "—"
        out.append(f"| {' + '.join(s['cameraIds'])} | {yes_no(s.get('declaredSupported'))} | {fps} | {cell(s.get('error') or '')} |")
    out.append("")
    return out


def md_audio(report):
    out = []
    inputs = report.get("audioInputs")
    if inputs is not None:
        listed = ", ".join(f"{i['id']} {i['type']} ({i['productName'] or '?'})" for i in inputs)
        out.append(f"- Audio inputs: {listed or 'none'}")
    recordings = report.get("audioRecordings") or []
    if recordings:
        out += [
            "",
            "| Audio device | Route | Honoured | Rate/channels | Peak/RMS dBFS | Frames read/expected | Error |",
            "|---|---|---|---|---|---|---|",
        ]
        for a in recordings:
            routed = f" → {a['routedType']}" if a.get("routedType") and a.get("routedDeviceId") != a["deviceId"] else ""
            out.append(
                f"| {a['deviceId']} {a['type']}{routed} | {a['route']} | {yes_no(a.get('preferredHonored'))} |"
                f" {a.get('actualSampleRate', 0)}/{a.get('actualChannels', 0)} | {num(a.get('peakDbfs'))}/{num(a.get('rmsDbfs'))} |"
                f" {a.get('framesRead', 0)}/{a.get('expectedFrames', 0)} | {cell(a.get('error') or '')} |"
            )
        out.append("")
    return out


LINKS = {"WIFI_ROUTER": "Wi-Fi router", "PHONE_HOTSPOT": "phone hotspot"}


def md_network(report):
    results = report.get("network") or []
    if not results:
        return []
    out = [
        "",
        "| Link | Network target | Packets | Sent/received | Loss % | p50/p95/p99 ms | Error |",
        "|---|---|---|---|---|---|---|",
    ]
    for n in results:
        s = n["stats"]
        out.append(
            f"| {LINKS.get(n.get('link'), 'unlabelled')} | {n['target']} | {n['packetBytes']} B @ {n['rateHz']} Hz | {s['sent']}/{s['received']} | {num(s['lossPercent'])} |"
            f" {num(s['p50Ms'])}/{num(s['p95Ms'])}/{num(s['p99Ms'])} | {cell(n.get('error') or '')} |"
        )
    out.append("")
    return out


def md_monitor(report):
    """WebRTC monitor-link runs: the camera side holds the glass-to-glass latency, the monitor side the decode counts."""
    results = report.get("monitorLinks") or []
    if not results:
        return []
    out = [
        "",
        "| Role | Link | Peer | g2g p50/p95/p99 ms | Echoed/sent | Stamps decoded/received | Encoder / decoder | Via | Error |",
        "|---|---|---|---|---|---|---|---|---|",
    ]
    for m in results:
        g2g = (m.get("latency") or {}).get("glassToGlass") or {}
        recv = m.get("receive") or {}
        codec = (m.get("outbound") or {}).get("encoderImplementation") or (m.get("inbound") or {}).get("decoderImplementation") or ""
        via = (m.get("candidatePair") or {}).get("localNetworkType") or ""
        latency = f"{num(g2g.get('p50Ms'))}/{num(g2g.get('p95Ms'))}/{num(g2g.get('p99Ms'))}" if g2g else ""
        echoed = f"{g2g.get('received', 0)}/{g2g.get('sent', 0)}" if g2g else ""
        decoded = f"{recv.get('stampsDecoded', 0)}/{recv.get('framesReceived', 0)}" if recv else ""
        out.append(
            f"| {m.get('role', '')} | {LINKS.get(m.get('link'), 'unlabelled')} | {cell(m.get('peer') or '')} | {latency} | {echoed} |"
            f" {decoded} | {cell(codec)} | {cell(via)} | {cell(m.get('error') or '')} |"
        )
    out.append("")
    return out


def markdown_section(report):
    d = report["device"]
    brand, model = title(report)
    out = [
        f"### {brand} {model} ({soc(d)}, Android {d['android']}, SDK {d['sdk']}) — {report['createdAt'][:10]},"
        f" app {report['appVersion']}",
        "",
        f"Build `{d.get('build', '')}`, device `{d.get('device', '')}`.",
        "",
    ]
    out += md_capabilities(report)
    out += md_pipeline(report)
    out += md_sustained(report)
    out += md_concurrent(report)
    out += md_audio(report)
    out += md_network(report)
    out += md_monitor(report)
    out.append("- LUT precision: not in the JSON report (logcat `LUT_8BIT`, `LUT_PRECISION`); add by hand.")
    for e in report.get("errors") or []:
        out.append(f"- Error: {e}")
    out.append(
        f"- Conclusion: highest config meeting target: {highest_label(report)};"
        f" 4K60 10-bit with LUT met target: {verdict_4k60_lut(report)}."
    )
    # Collapse the blank lines the tables add next to each other.
    text = "\n".join(out)
    return re.sub(r"\n{3,}", "\n\n", text).rstrip() + "\n"


def comparison_table(reports):
    out = [
        "| Model | Brand | SoC | Android | Highest config meeting target | 4K60 10-bit LUT | Sustained |",
        "|---|---|---|---|---|---|---|",
    ]
    for r in reports:
        d = r["device"]
        brand, model = title(r)
        out.append(
            f"| {model} | {brand} | {soc(d)} | {d['android']} (SDK {d['sdk']}) | {highest_label(r)} | {verdict_4k60_lut(r)} |"
            f" {verdict_sustained(r)} |"
        )
    return "\n".join(out) + "\n"


# ---------- catalogue ----------

def finite(value):
    """Catalogue files are strict JSON: non-finite floats become null."""
    if isinstance(value, float) and not math.isfinite(value):
        return None
    if isinstance(value, dict):
        return {k: finite(v) for k, v in value.items()}
    if isinstance(value, list):
        return [finite(v) for v in value]
    return value


def catalog_entry(report):
    d = report["device"]
    cameras = report.get("cameras")
    encoders = report.get("encoders")
    concurrent = report.get("concurrent")
    inputs = report.get("audioInputs")
    pipeline = report.get("pipeline") or []
    tiers = {}
    for r in met_runs(report):
        c = r["config"]
        tiers[r["label"]] = {k: c.get(k) for k in ("width", "height", "fps", "codec", "tenBit", "lut")}
    entry = {
        "schema": 1,
        "source": {"appVersion": report.get("appVersion"), "createdAt": report.get("createdAt")},
        "device": {k: d.get(k) for k in ("manufacturer", "brand", "model", "device", "socManufacturer", "socModel", "android", "sdk", "build")},
        # null means "not measured", an empty list means "measured, none".
        "cameras": None if cameras is None else [
            {
                "id": c["id"],
                "facing": c["facing"],
                "physicalIds": c.get("physicalIds", []),
                "tenBitProfiles": [p for p in c.get("dynamicRangeProfiles", []) if is_ten_bit_profile(p)],
                "recommendedTenBitProfile": c.get("recommendedTenBitProfile"),
                "colorSpaces": c.get("colorSpaces", []),
                "videoModes": [mode(m) for m in c.get("videoModes", [])],
                "highSpeedModes": c.get("highSpeedModes", []),
                "videoStabilizationModes": c.get("videoStabilizationModes", []),
                "ois": "ON" in c.get("opticalStabilizationModes", []),
                "zoomRatioRange": c.get("zoomRatioRange", []),
                "isoRange": c.get("isoRange", []),
                "exposureRangeNs": c.get("exposureRangeNs", []),
            }
            for c in cameras
        ],
        "encoders": None if encoders is None else sorted(
            (
                {
                    "name": e["name"],
                    "codec": e["codec"],
                    "tenBit": e.get("tenBitProfile", False),
                    "maxSize": f"{e.get('maxWidth')}x{e.get('maxHeight')}",
                    "supports": e.get("supports", {}),
                    "performancePoints": e.get("performancePoints", []),
                }
                for e in encoders if e.get("hardware")
            ),
            key=lambda e: (e["codec"], e["name"]),
        ),
        "concurrentSets": None if concurrent is None else sorted(
            s["cameraIds"] for s in concurrent.get("sets", []) if s.get("declaredSupported") is True
        ),
        "audioInputTypes": None if inputs is None else sorted({i["type"] for i in inputs}),
        "tiers": None if not pipeline else [
            dict(label=label, **tiers[label]) for label in sorted(tiers, key=lambda k: rank(tiers[k]))
        ],
    }
    return finite(entry)


def catalog_name(report):
    d = report["device"]
    return re.sub(r"[^a-z0-9]+", "-", f"{d['brand']}-{d['model']}".lower()).strip("-") + ".json"


def catalog_json(report):
    return json.dumps(catalog_entry(report), indent=2, sort_keys=True, ensure_ascii=False, allow_nan=False) + "\n"


def write_catalog(report, out_dir):
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / catalog_name(report)
    path.write_text(catalog_json(report), encoding="utf-8")
    return path


# ---------- command line ----------

def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    md = sub.add_parser("markdown", help="print docs/spike-report.md sections and a comparison table")
    md.add_argument("reports", nargs="+")
    cat = sub.add_parser("catalog", help="write a capability-catalogue seed")
    cat.add_argument("report")
    cat.add_argument("--out", default=str(REPO_ROOT / "catalog" / "devices"))
    args = parser.parse_args(argv)

    if args.command == "markdown":
        reports = [load(p) for p in args.reports]
        for r in reports:
            sys.stdout.write(markdown_section(r) + "\n")
        sys.stdout.write("### Comparison\n\n" + comparison_table(reports))
    else:
        print(write_catalog(load(args.report), args.out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
