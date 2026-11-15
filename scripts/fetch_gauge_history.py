"""Builds the daily level history of an ANA gauge in the current datum, for the early warning (docs/early-warning.md).

Reads the historical series of the ANA web service (HidroSerieHistorica, public, no account), keeps one value per day
(the most consistent level when several exist) and moves every value read before the gauge's datum change onto the
current datum. A file of recent telemetry already in the current datum (the backend's gauge_day, as "day,level_cm"
lines) can be appended for the days the historical series does not have yet.

The Tefé gauge (12900001) moved about +497 cm in 2024: the series up to 2024-08-13 is read on the old datum, the one
from 2025 matches the telemetry on the new one to the millimetre (1071.5 cm on 2025-10-20 in both). The days between
are inconsistent (jumps of -150 and +160 cm in a day, then a gap from September to November) and are dropped.

Usage:
    python scripts/fetch_gauge_history.py --station 12900001 --out fixtures/data/aois/tefe/gauge_history.json \\
        [--telemetry gauge_day.csv]
"""

import argparse
import json
import re
import urllib.request
from datetime import date

SERVICE = (
    "http://telemetriaws1.ana.gov.br/ServiceANA.asmx/HidroSerieHistorica"
    "?codEstacao={station}&dataInicio=01/01/{first}&dataFim=31/12/{last}&tipoDados=1&nivelConsistencia="
)

# (first day on the new datum, offset added to the days before it, days dropped as inconsistent)
DATUM_CHANGES = {
    "12900001": [
        {
            "new_datum_from": "2025-01-01",
            "offset_cm": 497.0,
            "old_datum_until": "2024-08-13",
            "note": "ANA gauge Tefé-Missões moved about +497 cm in 2024; 2024-08-14..2024-12-31 dropped "
            "(inconsistent readings around the change)",
        }
    ]
}


def fetch(station: str, first_year: int, last_year: int) -> dict[date, tuple[int, float]]:
    """Daily levels (cm, as published) by day, with their consistency level (2 consisted, 1 raw)."""
    days: dict[date, tuple[int, float]] = {}
    for start in range(first_year, last_year + 1, 5):
        url = SERVICE.format(station=station, first=start, last=min(start + 4, last_year))
        with urllib.request.urlopen(url, timeout=300) as response:
            text = response.read().decode("utf-8")
        for block in re.findall(r"<SerieHistorica[^>]*>(.*?)</SerieHistorica>", text, re.S):

            def field(name, block=block):
                m = re.search(rf"<{name}>([^<]*)</{name}>", block)
                return m.group(1) if m else None

            month = date.fromisoformat(field("DataHora")[:10])
            consistency = int(field("NivelConsistencia") or 1)
            for d in range(1, 32):
                value = field(f"Cota{d:02d}")
                if not value:
                    continue
                try:
                    day = month.replace(day=d)
                except ValueError:
                    continue
                if day not in days or consistency > days[day][0]:
                    days[day] = (consistency, float(value))
    return days


def harmonise(station: str, days: dict[date, tuple[int, float]]) -> dict[date, float]:
    """The levels on the current datum: older days moved by the offset, the days around a change dropped."""
    out = {}
    for day, (_, level) in sorted(days.items()):
        for change in DATUM_CHANGES.get(station, []):
            if day <= date.fromisoformat(change["old_datum_until"]):
                level += change["offset_cm"]
            elif day < date.fromisoformat(change["new_datum_from"]):
                level = None
                break
        if level is not None:
            out[day] = level
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--station", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--first-year", type=int, default=1980)
    ap.add_argument("--last-year", type=int, default=date.today().year)
    ap.add_argument("--telemetry", help="day,level_cm lines on the current datum, for the days after the series")
    a = ap.parse_args()

    levels = harmonise(a.station, fetch(a.station, a.first_year, a.last_year))
    if a.telemetry:
        for line in open(a.telemetry, encoding="utf-8"):
            parts = line.strip().split(",")
            if len(parts) == 2 and re.fullmatch(r"\d{4}-\d{2}-\d{2}", parts[0]):
                levels.setdefault(date.fromisoformat(parts[0]), float(parts[1]))
    document = {
        "station_code": a.station,
        "unit": "cm",
        "datum": "current",
        "datum_changes": DATUM_CHANGES.get(a.station, []),
        "source": "ANA HidroSerieHistorica (daily levels) and the backend's telemetry (gauge_day)",
        "days": [[day.isoformat(), level] for day, level in sorted(levels.items())],
    }
    with open(a.out, "w", encoding="utf-8") as f:
        json.dump(document, f, separators=(",", ":"))
    print(f"{len(levels)} days, {min(levels)} to {max(levels)} -> {a.out}")


if __name__ == "__main__":
    main()
