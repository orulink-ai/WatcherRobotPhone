"""
Compact offline IP-geolocation database: builder, serializer and reference reader.

Format "LDB1". Integers are little-endian EXCEPT the IPv6 range starts, which are 16-byte
big-endian (network order) so a byte-wise unsigned comparison equals the numeric one. The
database maps an IP address to a (country_code, city_name) pair via per-family sorted,
gap-filled, contiguous range tables plus deduplicated dictionaries. Both IPv4 and IPv6 are
stored at city granularity. Country *names* are NOT stored: the Android app derives the
localized name from the 2-letter code via java.util.Locale and the flag via emoji.

Layout:
  header
    magic        : 4 bytes  = b"LDB1"
    version      : u8       = 1
    flags        : u8       (bit0 = has_ipv6: the IPv6 section below is present)
    reserved     : u16      = 0
  country table
    count        : u16
    codes        : count * 2 bytes ASCII (index 0 = b"\\x00\\x00" = unknown)
  city pool
    count        : u32      (index 0 = "" = unknown)
    offsets      : (count + 1) * u32  (city i is blob[offsets[i]:offsets[i+1]])
    blob         : UTF-8 bytes
  location table
    count        : u32      (index 0 = unknown = (country 0, city 0))
    entries      : count * (country_idx u16 + city_idx u32)   # 6 bytes each
  ipv4 ranges (sorted, contiguous: range i covers [starts[i], starts[i+1]) )
    count        : u32
    starts       : count * u32           # little-endian
    loc_ids      : count * u24
  ipv6 ranges (present iff flags bit0; sorted, contiguous; same scheme as ipv4)
    count        : u32
    starts       : count * 16 bytes      # big-endian (network order)
    loc_ids      : count * u24

Lookup(ip): in the matching family, i = bisect_right(starts, ip) - 1
            -> loc_ids[i] -> (country_idx, city_idx).
"""

from __future__ import annotations

import bisect
import struct
from dataclasses import dataclass, field

MAGIC = b"LDB1"
VERSION = 1
MAX_IPV4 = 0xFFFFFFFF
MAX_IPV6 = (1 << 128) - 1
UNKNOWN_LOC = 0
FLAG_HAS_IPV6 = 0x01

# --- country code normalisation -------------------------------------------------

# DB-IP uses "ZZ" (and occasionally empty) for unknown; treat both as "no country".
_UNKNOWN_COUNTRY_CODES = {"", "ZZ"}


def _norm_country(code: str) -> str:
    code = (code or "").strip().upper()
    return "" if code in _UNKNOWN_COUNTRY_CODES else code


def _norm_city(name: str) -> str:
    name = (name or "").strip()
    return "" if name == "0" else name


# --- builder --------------------------------------------------------------------


@dataclass
class LocationDbBuilder:
    """Accumulates raw ranges and deduplicates them into compact tables."""

    _countries: dict[str, int] = field(default_factory=lambda: {"": 0})
    _cities: dict[str, int] = field(default_factory=lambda: {"": 0})
    _locations: dict[tuple[int, int], int] = field(default_factory=lambda: {(0, 0): 0})
    # raw ranges as (start, end, loc_id), appended in arbitrary order
    _v4: list[tuple[int, int, int]] = field(default_factory=list)
    _v6: list[tuple[int, int, int]] = field(default_factory=list)

    def _country_idx(self, code: str) -> int:
        return self._countries.setdefault(code, len(self._countries))

    def _city_idx(self, name: str) -> int:
        return self._cities.setdefault(name, len(self._cities))

    def _loc_id(self, country_code: str, city_name: str) -> int:
        country = _norm_country(country_code)
        if country == "":
            return UNKNOWN_LOC
        key = (self._country_idx(country), self._city_idx(_norm_city(city_name)))
        return self._locations.setdefault(key, len(self._locations))

    def add_ipv4_range(self, start: int, end: int, country_code: str, city_name: str) -> None:
        if start > end:
            return
        self._v4.append((start, end, self._loc_id(country_code, city_name)))

    def add_ipv6_range(self, start: int, end: int, country_code: str, city_name: str) -> None:
        if start > end:
            return
        self._v6.append((start, end, self._loc_id(country_code, city_name)))

    # -- compaction --

    @staticmethod
    def _contiguous(ranges: list[tuple[int, int, int]], max_ip: int) -> tuple[list[int], list[int]]:
        """Sort, gap-fill (gaps -> unknown) and coalesce equal-location neighbours."""
        if not ranges:
            return [], []  # no data for this family -> no table (and the family flag stays clear)
        starts: list[int] = []
        loc_ids: list[int] = []
        cursor = 0
        for s, e, loc in sorted(ranges):
            if s < cursor:  # overlap: clip to the uncovered tail
                if e < cursor:
                    continue
                s = cursor
            if s > cursor:  # gap before this range -> unknown
                if not loc_ids or loc_ids[-1] != UNKNOWN_LOC:
                    starts.append(cursor)
                    loc_ids.append(UNKNOWN_LOC)
            if not loc_ids or loc_ids[-1] != loc:
                starts.append(s)
                loc_ids.append(loc)
            cursor = e + 1
        if cursor <= max_ip and (not loc_ids or loc_ids[-1] != UNKNOWN_LOC):
            starts.append(cursor)
            loc_ids.append(UNKNOWN_LOC)
        return starts, loc_ids

    def serialize(self) -> bytes:
        codes = [b"\x00\x00"] * len(self._countries)
        for code, idx in self._countries.items():
            codes[idx] = code.encode("ascii").ljust(2, b"\x00")[:2] if code else b"\x00\x00"

        cities = [""] * len(self._cities)
        for name, idx in self._cities.items():
            cities[idx] = name
        blob = bytearray()
        offsets = [0]
        for name in cities:
            blob += name.encode("utf-8")
            offsets.append(len(blob))

        locs = [(0, 0)] * len(self._locations)
        for (c_idx, city_idx), loc in self._locations.items():
            locs[loc] = (c_idx, city_idx)

        v4_starts, v4_locs = self._contiguous(self._v4, MAX_IPV4)
        v6_starts, v6_locs = self._contiguous(self._v6, MAX_IPV6)

        out = bytearray()
        out += MAGIC
        flags = FLAG_HAS_IPV6 if v6_starts else 0
        out += struct.pack("<BBH", VERSION, flags, 0)

        out += struct.pack("<H", len(codes))
        for code in codes:
            out += code

        out += struct.pack("<I", len(cities))
        for off in offsets:
            out += struct.pack("<I", off)
        out += blob

        out += struct.pack("<I", len(locs))
        for c_idx, city_idx in locs:
            out += struct.pack("<HI", c_idx, city_idx)

        out += struct.pack("<I", len(v4_starts))
        for s in v4_starts:
            out += struct.pack("<I", s)
        for loc in v4_locs:
            out += loc.to_bytes(3, "little")

        out += struct.pack("<I", len(v6_starts))
        for s in v6_starts:
            out += s.to_bytes(16, "big")
        for loc in v6_locs:
            out += loc.to_bytes(3, "little")

        return bytes(out)


# --- reference reader -----------------------------------------------------------


class LocationDbReader:
    """Parses an LDB1 blob and resolves an IPv4 address to (country_code, city)."""

    def __init__(self, data: bytes):
        if data[:4] != MAGIC:
            raise ValueError("not an LDB1 database")
        pos = 4
        version, _flags, _reserved = struct.unpack_from("<BBH", data, pos)
        pos += 4
        if version != VERSION:
            raise ValueError(f"unsupported version {version}")

        (country_count,) = struct.unpack_from("<H", data, pos)
        pos += 2
        self._codes = [data[pos + i * 2: pos + i * 2 + 2] for i in range(country_count)]
        pos += country_count * 2

        (city_count,) = struct.unpack_from("<I", data, pos)
        pos += 4
        offsets = list(struct.unpack_from(f"<{city_count + 1}I", data, pos))
        pos += (city_count + 1) * 4
        blob_len = offsets[-1]
        blob = data[pos: pos + blob_len]
        pos += blob_len
        self._cities = [blob[offsets[i]: offsets[i + 1]].decode("utf-8") for i in range(city_count)]

        (loc_count,) = struct.unpack_from("<I", data, pos)
        pos += 4
        self._locs = [struct.unpack_from("<HI", data, pos + i * 6) for i in range(loc_count)]
        pos += loc_count * 6

        (v4_count,) = struct.unpack_from("<I", data, pos)
        pos += 4
        self._starts = list(struct.unpack_from(f"<{v4_count}I", data, pos))
        pos += v4_count * 4
        self._loc_ids = [
            int.from_bytes(data[pos + i * 3: pos + i * 3 + 3], "little") for i in range(v4_count)
        ]
        pos += v4_count * 3

        self._v6_starts: list[int] = []
        self._v6_loc_ids: list[int] = []
        if _flags & FLAG_HAS_IPV6:
            (v6_count,) = struct.unpack_from("<I", data, pos)
            pos += 4
            self._v6_starts = [
                int.from_bytes(data[pos + i * 16: pos + i * 16 + 16], "big") for i in range(v6_count)
            ]
            pos += v6_count * 16
            self._v6_loc_ids = [
                int.from_bytes(data[pos + i * 3: pos + i * 3 + 3], "little") for i in range(v6_count)
            ]

    def _resolve(self, loc_id: int) -> tuple[str | None, str | None]:
        country_idx, city_idx = self._locs[loc_id]
        code = self._codes[country_idx].rstrip(b"\x00").decode("ascii") or None
        city = self._cities[city_idx] or None
        return code, city

    def lookup_ipv4(self, ip: int) -> tuple[str | None, str | None]:
        i = bisect.bisect_right(self._starts, ip) - 1
        return (None, None) if i < 0 else self._resolve(self._loc_ids[i])

    def lookup_ipv6(self, ip: int) -> tuple[str | None, str | None]:
        i = bisect.bisect_right(self._v6_starts, ip) - 1
        return (None, None) if i < 0 else self._resolve(self._v6_loc_ids[i])
