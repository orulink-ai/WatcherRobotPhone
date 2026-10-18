#!/usr/bin/env python3
"""
Regenerate the tiny LDB1 fixture used by the Kotlin reader test (LocationDbTest), keeping the Kotlin
reader in lockstep with this module's format. Run from anywhere:

    python3 scripts/location-db/make_fixture.py

Writes app/src/test/resources/geo/location-db-fixture.bin (relative to the repo root).
"""

from __future__ import annotations

import ipaddress
from pathlib import Path

from location_db import LocationDbBuilder, LocationDbReader

# Resolve the repo root from this file's location (scripts/location-db/ -> repo root).
FIXTURE = Path(__file__).resolve().parents[2] / "app/src/test/resources/geo/location-db-fixture.bin"


def _ip(s: str) -> int:
    return int(ipaddress.ip_address(s))


def build_fixture() -> bytes:
    b = LocationDbBuilder()
    b.add_ipv4_range(_ip("1.0.0.0"), _ip("1.0.0.255"), "AU", "Brisbane")
    b.add_ipv4_range(_ip("1.0.8.0"), _ip("1.0.15.255"), "CN", "Guangzhou")
    b.add_ipv4_range(_ip("200.1.2.0"), _ip("200.1.2.255"), "US", "Denver")  # high bit set: unsigned compare
    b.add_ipv6_range(_ip("2001:db8::"), _ip("2001:db8:ffff:ffff:ffff:ffff:ffff:ffff"), "DE", "Berlin")
    return b.serialize()


def main() -> None:
    blob = build_fixture()
    r = LocationDbReader(blob)
    assert r.lookup_ipv4(_ip("1.0.0.5")) == ("AU", "Brisbane")
    assert r.lookup_ipv4(_ip("1.0.10.0")) == ("CN", "Guangzhou")
    assert r.lookup_ipv4(_ip("200.1.2.5")) == ("US", "Denver")
    assert r.lookup_ipv4(_ip("1.0.4.0")) == (None, None)
    assert r.lookup_ipv6(_ip("2001:db8::1")) == ("DE", "Berlin")
    assert r.lookup_ipv6(_ip("2002::1")) == (None, None)
    FIXTURE.parent.mkdir(parents=True, exist_ok=True)
    FIXTURE.write_bytes(blob)
    print(f"wrote {FIXTURE} ({len(blob)} bytes)")


if __name__ == "__main__":
    main()
