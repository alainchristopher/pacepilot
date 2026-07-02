#!/usr/bin/env python3
"""
PacePilot ride analysis — pull Karoo FIT files + optional Strava export,
compute fitness metrics, recommend 70.3 race targets, seed ride_history.json.

Usage:
  pip install -r tools/requirements.txt
  python tools/analyze_rides.py --karoo-adb --strava ~/Downloads/strava_export
  python tools/analyze_rides.py --fit-dir ~/fit_rides --output docs/fitness_report.md
"""

from __future__ import annotations

import argparse
import json
import math
import subprocess
import sys
from dataclasses import dataclass, asdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable

try:
    from fitparse import FitFile
except ImportError:
    FitFile = None  # type: ignore


@dataclass
class RideMetrics:
    file: str
    timestamp_ms: int
    duration_sec: int
    distance_km: float
    elevation_m: float
    avg_power: int
    normalized_power: int
    max_power: int
    avg_hr: int
    max_hr: int
    if_pct: float
    tss: float
    hr_decoupling_pct: float
    power_zone_time_pct: list[float]
    hr_zone_time_pct: list[float]


def rolling_avg(values: list[float], window: int) -> list[float]:
    if not values or window <= 1:
        return values
    out: list[float] = []
    for i in range(len(values)):
        start = max(0, i - window + 1)
        chunk = values[start : i + 1]
        out.append(sum(chunk) / len(chunk))
    return out


def power_zone(power: float, ftp: int) -> int:
    if ftp <= 0 or power <= 0:
        return 0
    pct = power / ftp * 100
    return (
        1 if pct < 55 else
        2 if pct < 75 else
        3 if pct < 90 else
        4 if pct < 105 else
        5 if pct < 120 else
        6 if pct < 150 else
        7
    )


def hr_zone(hr: float, max_hr: int) -> int:
    if max_hr <= 0 or hr <= 0:
        return 0
    pct = hr / max_hr * 100
    return (
        1 if pct < 60 else
        2 if pct < 70 else
        3 if pct < 80 else
        4 if pct < 90 else
        5
    )


def zone_distribution(powers: list[float], hrs: list[float], ftp: int, max_hr: int) -> tuple[list[float], list[float]]:
    p_counts = [0.0] * 7
    h_counts = [0.0] * 5
    for p in powers:
        z = power_zone(p, ftp)
        if z > 0:
            p_counts[z - 1] += 1
    for h in hrs:
        z = hr_zone(h, max_hr)
        if z > 0:
            h_counts[z - 1] += 1
    p_total = sum(p_counts) or 1.0
    h_total = sum(h_counts) or 1.0
    return [round(c / p_total * 100, 1) for c in p_counts], [round(c / h_total * 100, 1) for c in h_counts]


def parse_fit(path: Path, ftp: int = 250) -> RideMetrics | None:
    if FitFile is None:
        raise RuntimeError("fitparse not installed — pip install -r tools/requirements.txt")
    fit = FitFile(str(path))
    records = list(fit.get_messages("record"))
    if not records:
        return None

    powers: list[float] = []
    hrs: list[float] = []
    start_ts = None
    end_ts = None
    dist_m = 0.0
    elev_gain = 0.0
    last_alt: float | None = None

    for rec in records:
        d = rec.get_values()
        if d.get("timestamp"):
            if start_ts is None:
                start_ts = d["timestamp"]
            end_ts = d["timestamp"]
        p = d.get("power")
        if p is not None:
            powers.append(float(p))
        h = d.get("heart_rate")
        if h is not None:
            hrs.append(float(h))
        if d.get("distance") is not None:
            dist_m = max(dist_m, float(d["distance"]))
        alt = d.get("altitude")
        if alt is not None and last_alt is not None and alt > last_alt:
            elev_gain += alt - last_alt
        if alt is not None:
            last_alt = alt

    if not powers or start_ts is None or end_ts is None:
        return None

    duration = int((end_ts - start_ts).total_seconds())
    avg_p = int(sum(powers) / len(powers))
    p30 = rolling_avg(powers, 30)
    np_val = int((sum(p ** 4 for p in p30) / len(p30)) ** 0.25) if p30 else avg_p
    if_pct = np_val / ftp if ftp > 0 else 0.0
    tss = duration / 3600 * if_pct ** 2 * 100

    decoupling = 0.0
    if len(hrs) > 600 and len(powers) > 600:
        first_hr = sum(hrs[: len(hrs) // 3]) / max(1, len(hrs) // 3)
        last_hr = sum(hrs[-len(hrs) // 3 :]) / max(1, len(hrs) // 3)
        first_p = sum(powers[: len(powers) // 3]) / max(1, len(powers) // 3)
        last_p = sum(powers[-len(powers) // 3 :]) / max(1, len(powers) // 3)
        if first_p > 0 and last_p > 0:
            decoupling = ((last_hr / last_p) - (first_hr / first_p)) / (first_hr / first_p) * 100

    max_hr_val = int(max(hrs)) if hrs else 0
    p_zones, h_zones = zone_distribution(powers, hrs, ftp, max_hr_val or 185)

    return RideMetrics(
        file=path.name,
        timestamp_ms=int(start_ts.replace(tzinfo=timezone.utc).timestamp() * 1000),
        duration_sec=duration,
        distance_km=dist_m / 1000.0,
        elevation_m=elev_gain,
        avg_power=avg_p,
        normalized_power=np_val,
        max_power=int(max(powers)),
        avg_hr=int(sum(hrs) / len(hrs)) if hrs else 0,
        max_hr=int(max(hrs)) if hrs else 0,
        if_pct=round(if_pct, 3),
        tss=round(tss, 1),
        hr_decoupling_pct=round(decoupling, 1),
        power_zone_time_pct=p_zones,
        hr_zone_time_pct=h_zones,
    )


def pull_karoo_fits(dest: Path) -> list[Path]:
    dest.mkdir(parents=True, exist_ok=True)
    remote_dirs = [
        "/sdcard/Android/data/io.hammerhead.karoo/files/activities",
        "/sdcard/Hammerhead/activities",
    ]
    pulled: list[Path] = []
    for remote in remote_dirs:
        subprocess.run(
            ["adb", "pull", remote, str(dest / remote.replace("/", "_"))],
            capture_output=True,
        )
    for fit in dest.rglob("*.fit"):
        pulled.append(fit)
    return pulled


def collect_fits(args: argparse.Namespace) -> list[Path]:
    paths: list[Path] = []
    if args.karoo_adb:
        paths.extend(pull_karoo_fits(Path("tools/.karoo_fits")))
    if args.fit_dir:
        paths.extend(Path(args.fit_dir).rglob("*.fit"))
    if args.strava:
        paths.extend(Path(args.strava).rglob("*.fit"))
    return sorted(set(paths))


def estimate_ftp(rides: list[RideMetrics]) -> int:
    best_20min = 0
    for r in rides:
        # NP proxy for 20min blocks — use ride NP if duration >= 20min
        if r.duration_sec >= 1200:
            best_20min = max(best_20min, int(r.normalized_power * 0.95))
    return best_20min or 250


def to_ride_summary(r: RideMetrics, ftp: int) -> dict:
    return {
        "timestamp": r.timestamp_ms,
        "durationSec": r.duration_sec,
        "distanceKm": round(r.distance_km, 2),
        "elevationGainM": int(r.elevation_m),
        "avgPowerWatts": r.avg_power,
        "normalizedPower": r.normalized_power,
        "maxPowerWatts": r.max_power,
        "ftpAtTime": ftp,
        "avgHrBpm": r.avg_hr,
        "maxHrBpm": r.max_hr,
        "powerZoneTimePct": r.power_zone_time_pct,
        "hrZoneTimePct": r.hr_zone_time_pct,
        "powerFadingDetected": False,
        "hrDecouplingPct": r.hr_decoupling_pct,
        "avgHrRecoveryRateBpmPerSec": 0.0,
        "wasStructuredWorkout": False,
        "avgIntervalComplianceScore": 0.0,
        "effortSetAvgPowers": [],
        "alertsFired": 0,
        "aiUpgrades": 0,
        "aiFailures": 0,
        "suppressedCount": 0,
    }


def write_report(rides: list[RideMetrics], ftp: int, out: Path) -> None:
    if not rides:
        out.write_text("# PacePilot Fitness Report\n\nNo FIT files found.\n")
        return

    recent = sorted(rides, key=lambda r: r.timestamp_ms)[-30:]
    avg_np = sum(r.normalized_power for r in recent) / len(recent)
    avg_tss = sum(r.tss for r in recent) / len(recent)
    race_if = min(0.85, max(0.78, (avg_np / ftp) * 0.92 if ftp else 0.82))
    race_watts = int(ftp * race_if)
    carb_gph = 75 if race_if >= 0.82 else 65

    lines = [
        "# PacePilot Fitness Report — Ironman 70.3 Kraków prep",
        "",
        f"Generated: {datetime.now(timezone.utc).isoformat()}",
        f"Rides analysed: **{len(rides)}** (using last **{len(recent)}** for targets)",
        "",
        "## Recommended race targets (bike leg)",
        "",
        f"- **FTP estimate:** {ftp} W",
        f"- **Race IF:** {race_if:.2f}",
        f"- **Target power:** {race_watts} W",
        f"- **Carbs:** {carb_gph} g/h (front-load first 90 min)",
        f"- **Avg recent NP:** {avg_np:.0f} W | **Avg TSS/ride:** {avg_tss:.0f}",
        "",
        "## 4-week outline",
        "",
        "| Week | Focus | Key sessions |",
        "|------|-------|--------------|",
        "| 1 | Build | 2× race-pace blocks (60–90min @ IF 0.82), long Z2 |",
        "| 2 | Build | 70.3 simulation ride 3h @ race watts, fueling practice |",
        "| 3 | Sharpen | Shorter race-pace intervals, reduce volume 15% |",
        "| 4 | Taper | 3 easy spins, race mode test 45min, carb load |",
        "",
        "## Recent rides",
        "",
        "| Date | km | NP | IF | TSS | Decouple % |",
        "|------|-----|-----|-----|-----|------------|",
    ]
    for r in recent[-10:]:
        dt = datetime.fromtimestamp(r.timestamp_ms / 1000, tz=timezone.utc).strftime("%Y-%m-%d")
        lines.append(
            f"| {dt} | {r.distance_km:.1f} | {r.normalized_power} | {r.if_pct:.2f} | {r.tss:.0f} | {r.hr_decoupling_pct:.1f} |"
        )
    out.write_text("\n".join(lines) + "\n")


def main() -> int:
    p = argparse.ArgumentParser(description="Analyse rides for PacePilot race prep")
    p.add_argument("--karoo-adb", action="store_true", help="Pull FIT files from connected Karoo via adb")
    p.add_argument("--fit-dir", type=str, help="Local directory of FIT files")
    p.add_argument("--strava", type=str, help="Strava export folder")
    p.add_argument("--ftp", type=int, default=0, help="Override FTP (0 = estimate)")
    p.add_argument("--output", type=str, default="docs/fitness_report.md")
    p.add_argument("--history-json", type=str, default="tools/ride_history_seed.json")
    p.add_argument("--race-plan-json", type=str, default="tools/race_plan_seed.json")
    args = p.parse_args()

    fits = collect_fits(args)
    rides: list[RideMetrics] = []
    for f in fits:
        try:
            m = parse_fit(f, ftp=args.ftp or 250)
            if m:
                rides.append(m)
        except Exception as e:
            print(f"skip {f.name}: {e}", file=sys.stderr)

    ftp = args.ftp or estimate_ftp(rides)
    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    write_report(rides, ftp, out)
    print(f"Wrote {out}")

    recent = sorted(rides, key=lambda r: r.timestamp_ms)[-30:]
    history = {"rides": [to_ride_summary(r, ftp) for r in recent]}
    hist_path = Path(args.history_json)
    hist_path.parent.mkdir(parents=True, exist_ok=True)
    hist_path.write_text(json.dumps(history, indent=2))
    print(f"Wrote {hist_path} ({len(recent)} rides)")

    if recent:
        race_if = min(0.85, max(0.78, (sum(r.normalized_power for r in recent) / len(recent) / ftp) * 0.92 if ftp else 0.82))
        race_watts = int(ftp * race_if)
        carb_gph = 75 if race_if >= 0.82 else 65
        race_plan = {
            "enabled": True,
            "targetIf": round(race_if, 2),
            "targetWatts": race_watts,
            "durationMin": 150,
            "distanceKm": 90.0,
            "carbGramsPerHour": carb_gph,
            "eventName": "Ironman 70.3 Kraków",
            "courseNotes": "Generated from recent ride analysis",
        }
        race_path = Path(args.race_plan_json)
        race_path.parent.mkdir(parents=True, exist_ok=True)
        race_path.write_text(json.dumps(race_plan, indent=2))
        print(f"Wrote {race_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
