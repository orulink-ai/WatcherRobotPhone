"""Structure tests for the compact location DB (synthetic data, stdlib only).

Run: python3 -m pytest scripts/location-db/test_location_db.py
 or: python3 scripts/location-db/test_location_db.py
"""

from __future__ import annotations

import ipaddress

from location_db import LocationDbBuilder, LocationDbReader


def _ip(s: str) -> int:
    return int(ipaddress.ip_address(s))


def _roundtrip(builder: LocationDbBuilder) -> LocationDbReader:
    return LocationDbReader(builder.serialize())


def test_basic_lookup_and_gap_fill():
    b = LocationDbBuilder()
    b.add_ipv4_range(_ip("1.0.0.0"), _ip("1.0.0.255"), "AU", "Brisbane")
    b.add_ipv4_range(_ip("1.0.8.0"), _ip("1.0.15.255"), "CN", "Guangzhou")
    r = _roundtrip(b)

    assert r.lookup_ipv4(_ip("1.0.0.5")) == ("AU", "Brisbane")
    assert r.lookup_ipv4(_ip("1.0.0.255")) == ("AU", "Brisbane")
    assert r.lookup_ipv4(_ip("1.0.10.0")) == ("CN", "Guangzhou")
    # the gap between the two ranges resolves to unknown, not to a neighbour
    assert r.lookup_ipv4(_ip("1.0.4.0")) == (None, None)
    # below the first range and the 0.0.0.0 floor -> unknown
    assert r.lookup_ipv4(_ip("0.255.255.255")) == (None, None)


def test_ipv6_lookup_and_flag():
    b = LocationDbBuilder()
    b.add_ipv6_range(_ip("2001:db8::"), _ip("2001:db8:ffff:ffff:ffff:ffff:ffff:ffff"), "DE", "Berlin")
    b.add_ipv6_range(_ip("2a00::"), _ip("2a00::ffff"), "FR", "Paris")
    blob = b.serialize()
    assert blob[5] & 0x01, "has_ipv6 flag must be set when IPv6 ranges exist"
    r = LocationDbReader(blob)
    assert r.lookup_ipv6(_ip("2001:db8::1")) == ("DE", "Berlin")
    assert r.lookup_ipv6(_ip("2001:db8:ffff::abcd")) == ("DE", "Berlin")
    assert r.lookup_ipv6(_ip("2a00::5")) == ("FR", "Paris")
    # gap between the two ranges and below the first -> unknown
    assert r.lookup_ipv6(_ip("2002::1")) == (None, None)
    assert r.lookup_ipv6(_ip("::1")) == (None, None)


def test_no_ipv6_flag_when_only_ipv4():
    b = LocationDbBuilder()
    b.add_ipv4_range(_ip("1.0.0.0"), _ip("1.0.0.255"), "AU", "Brisbane")
    assert (b.serialize()[5] & 0x01) == 0, "has_ipv6 flag must be clear with no IPv6 ranges"


def test_coalesces_equal_neighbours():
    b = LocationDbBuilder()
    b.add_ipv4_range(_ip("10.0.0.0"), _ip("10.0.0.255"), "DE", "Berlin")
    b.add_ipv4_range(_ip("10.0.1.0"), _ip("10.0.1.255"), "DE", "Berlin")
    r = _roundtrip(b)
    # both halves resolve identically...
    assert r.lookup_ipv4(_ip("10.0.0.10")) == ("DE", "Berlin")
    assert r.lookup_ipv4(_ip("10.0.1.10")) == ("DE", "Berlin")
    # ...and the two adjacent same-location ranges were merged into one boundary
    assert r._starts.count(_ip("10.0.1.0")) == 0


def test_unknown_country_collapses_to_unknown_location():
    b = LocationDbBuilder()
    b.add_ipv4_range(_ip("2.0.0.0"), _ip("2.0.0.255"), "ZZ", "0")
    b.add_ipv4_range(_ip("2.0.1.0"), _ip("2.0.1.255"), "", "Nowhere")
    r = _roundtrip(b)
    assert r.lookup_ipv4(_ip("2.0.0.1")) == (None, None)
    assert r.lookup_ipv4(_ip("2.0.1.1")) == (None, None)


def test_dictionaries_are_deduplicated():
    b = LocationDbBuilder()
    for i in range(5):
        base = _ip(f"3.0.{i}.0")
        b.add_ipv4_range(base, base + 255, "FR", "Paris")
    blob = b.serialize()
    r = LocationDbReader(blob)
    # one country, one city, one location despite five ranges
    assert r._codes[1:] == [b"FR"]
    assert "Paris" in r._cities
    assert sum(1 for c in r._cities if c == "Paris") == 1
    assert r.lookup_ipv4(_ip("3.0.3.7")) == ("FR", "Paris")


def _run():
    fns = [v for k, v in sorted(globals().items()) if k.startswith("test_")]
    for fn in fns:
        fn()
        print(f"ok  {fn.__name__}")
    print(f"\n{len(fns)} passed")


if __name__ == "__main__":
    _run()
