# Offline location database (`LDB1`)

Build-time tooling that converts the **DB-IP City Lite** dataset into a compact, gzipped binary
(`LDB1`) that the app memory-maps to resolve a client IP → `(country_code, city)`. Country *names* and
flags are derived in-app from the 2-letter code (`java.util.Locale` + emoji), so nothing locale-specific
is stored.

## Files

| File | Purpose |
|------|---------|
| `location_db.py` | Format spec + builder/serializer + reference reader (the contract the Kotlin reader mirrors). |
| `build_location_db.py` | CLI: download/read the DB-IP City Lite CSV, build the `LDB1` blob, gzip it, print stats. |
| `test_location_db.py` | Structure tests on synthetic data (gap-fill, coalescing, dedup, unknown handling). |

## Usage

```bash
# downloads the current month's DB-IP City Lite CSV and writes the gzipped LDB1 asset
python3 scripts/location-db/build_location_db.py --out app/src/main/assets/geo/location-db.bin.gz

# or from a local CSV(.gz)
python3 scripts/location-db/build_location_db.py --csv dbip-city-lite-2026-06.csv.gz --out out.bin.gz

# tests
python3 scripts/location-db/test_location_db.py
```

Both IPv4 and IPv6 are stored at **city** granularity, full precision. Current footprint:
~25.5 MB gzipped (in the APK) / ~104 MB decompressed (memory-mapped, not heap).

## Format

`LDB1`, little-endian. A sorted, gap-filled, contiguous range table maps an address to a location id;
location ids index a deduplicated `(country, city)` table; lookups are a binary search
(`bisect_right(starts, ip) - 1`). See the module docstring in `location_db.py` for the byte layout.

## Attribution

This product includes IP geolocation data created by **DB-IP.com** (<https://db-ip.com>), the IP-to-City
Lite database, licensed under **CC BY 4.0** (<https://creativecommons.org/licenses/by/4.0/>). This
attribution must be surfaced in the app's licenses/about screen.
