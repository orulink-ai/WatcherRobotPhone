#!/usr/bin/env python3
"""
Build the compact offline location DB from the DB-IP City Lite CSV.

Source : https://download.db-ip.com/free/dbip-city-lite-<YYYY-MM>.csv.gz  (CC BY 4.0)
Output : a gzipped LDB1 binary (see location_db.py for the format).

Usage:
  python3 build_location_db.py --out app/src/main/assets/geo/location-db.bin.gz
  python3 build_location_db.py --csv /path/to/dbip-city-lite.csv.gz --out out.bin.gz

By default the newest available monthly CSV is downloaded (and cached) if --csv is not
given: the current month is tried first, then earlier months in sequence (up to 6 months
total), since DB-IP publishes each month's file a few days into the month.
Both IPv4 and IPv6 are ingested at city granularity.

Attribution: this product includes IP geolocation data created by DB-IP.com,
available from https://db-ip.com, licensed under CC BY 4.0.
"""

from __future__ import annotations

import argparse
import csv
import datetime
import gzip
import io
import ipaddress
import sys
import urllib.error
import urllib.request
from pathlib import Path

from location_db import LocationDbBuilder, LocationDbReader

DBIP_URL = "https://download.db-ip.com/free/dbip-city-lite-{month}.csv.gz"

# DB-IP publishes the new monthly DB a few days into the month, so the current month's
# file is often absent (HTTP 404) at build time. Try the current month, then walk back up
# to this many months total, using the newest one that is actually available.
FALLBACK_MONTHS = 6


def _default_month() -> str:
    return datetime.date.today().strftime("%Y-%m")


def _previous_month(month: str) -> str:
    """Return the YYYY-MM string for the calendar month before [month]."""
    year, mon = (int(p) for p in month.split("-"))
    if mon == 1:
        return f"{year - 1:04d}-12"
    return f"{year:04d}-{mon - 1:02d}"


def _candidate_months(start_month: str, count: int) -> list[str]:
    """[start_month] and the [count]-1 preceding months, newest first."""
    months = [start_month]
    for _ in range(count - 1):
        months.append(_previous_month(months[-1]))
    return months


def _download(month: str, dest: Path) -> Path | None:
    """Fetch the DB-IP CSV for [month] into [dest]. Return the path, or None if that
    month is not published yet (HTTP 404)."""
    if dest.exists():
        print(f"using cached {dest} ({dest.stat().st_size:,} bytes)")
        return dest
    url = DBIP_URL.format(month=month)
    print(f"downloading {url} ...")
    dest.parent.mkdir(parents=True, exist_ok=True)
    # DB-IP rejects the default urllib user-agent (403), so present a browser-like one.
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (location-db build)"})
    try:
        with urllib.request.urlopen(req) as resp, open(dest, "wb") as f:  # noqa: S310 (trusted host)
            f.write(resp.read())
    except urllib.error.HTTPError as e:
        if e.code == 404:
            print(f"  {month} not published yet (HTTP 404)")
            return None
        raise
    print(f"saved {dest} ({dest.stat().st_size:,} bytes)")
    return dest


def _resolve_csv(start_month: str, cache_dir: Path) -> Path:
    """Return the newest available DB-IP CSV, trying [start_month] then earlier months.

    Fails only when none of the last [FALLBACK_MONTHS] months is available.
    """
    candidates = _candidate_months(start_month, FALLBACK_MONTHS)
    for month in candidates:
        result = _download(month, cache_dir / f"dbip-city-lite-{month}.csv.gz")
        if result is not None:
            return result
    raise SystemExit(
        f"No DB-IP City Lite database available for any of the last {FALLBACK_MONTHS} months "
        f"({', '.join(candidates)}). DB-IP may be delayed; retry later or pass --csv explicitly."
    )


def _open_csv(path: Path) -> io.TextIOBase:
    if path.suffix == ".gz":
        return io.TextIOWrapper(gzip.open(path, "rb"), encoding="utf-8", newline="")
    return open(path, "r", encoding="utf-8", newline="")


def build(csv_path: Path) -> bytes:
    builder = LocationDbBuilder()
    v4 = v6 = 0
    with _open_csv(csv_path) as fh:
        for row in csv.reader(fh):
            if len(row) < 6:
                continue
            start, end, country, city = row[0], row[1], row[3], row[5]
            if ":" in start:
                try:
                    s = int(ipaddress.IPv6Address(start))
                    e = int(ipaddress.IPv6Address(end))
                except ipaddress.AddressValueError:
                    continue
                builder.add_ipv6_range(s, e, country, city)
                v6 += 1
            else:
                try:
                    builder.add_ipv4_range(_ipv4_int(start), _ipv4_int(end), country, city)
                except ValueError:
                    continue
                v4 += 1
    print(f"parsed {v4:,} IPv4 rows + {v6:,} IPv6 rows (both city-level)")
    return builder.serialize()


def _ipv4_int(s: str) -> int:
    a, b, c, d = (int(p) for p in s.split("."))
    if not all(0 <= x <= 255 for x in (a, b, c, d)):
        raise ValueError(s)
    return (a << 24) | (b << 16) | (c << 8) | d


def _report(blob: bytes, gz: bytes) -> None:
    r = LocationDbReader(blob)
    print("\n--- compact DB ---")
    print(f"countries     : {len(r._codes):,}")
    print(f"cities        : {len(r._cities):,}")
    print(f"locations     : {len(r._locs):,}")
    print(f"ipv4 ranges   : {len(r._starts):,}")
    print(f"ipv6 ranges   : {len(r._v6_starts):,}")
    print(f"raw bytes     : {len(blob):,} ({len(blob) / 1e6:.1f} MB)")
    print(f"gzipped bytes : {len(gz):,} ({len(gz) / 1e6:.1f} MB)")
    print("\nsample lookups:")
    for ip in ("1.1.1.1", "8.8.8.8", "208.67.222.222"):
        code, city = r.lookup_ipv4(_ipv4_int(ip))
        print(f"  {ip:<18} -> {code or '??'} / {city or '(no city)'}")
    for ip in ("2606:4700:4700::1111", "2001:4860:4860::8888"):
        code, city = r.lookup_ipv6(int(ipaddress.IPv6Address(ip)))
        print(f"  {ip:<18} -> {code or '??'} / {city or '(no city)'}")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--csv", type=Path, help="path to dbip-city-lite CSV(.gz); downloads if omitted")
    ap.add_argument("--month", default=_default_month(), help="DB-IP month YYYY-MM (download)")
    ap.add_argument("--out", type=Path, required=True, help="output .bin.gz path")
    ap.add_argument("--cache-dir", type=Path, default=Path("/tmp/dbip-cache"))
    args = ap.parse_args()

    csv_path = args.csv or _resolve_csv(args.month, args.cache_dir)
    blob = build(csv_path)
    gz = gzip.compress(blob, mtime=0)

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_bytes(gz)
    _report(blob, gz)
    print(f"\nwrote {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
