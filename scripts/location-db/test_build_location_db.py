"""Tests for the DB-IP month-fallback download logic (stdlib only, no network).

Run: python3 -m pytest scripts/location-db/test_build_location_db.py
 or: python3 scripts/location-db/test_build_location_db.py
"""

from __future__ import annotations

from pathlib import Path

import build_location_db as b


def test_previous_month_within_year():
    assert b._previous_month("2026-08") == "2026-07"
    assert b._previous_month("2026-12") == "2026-11"


def test_previous_month_year_boundary():
    assert b._previous_month("2026-01") == "2025-12"


def test_candidate_months_newest_first():
    assert b._candidate_months("2026-08", 6) == [
        "2026-08",
        "2026-07",
        "2026-06",
        "2026-05",
        "2026-04",
        "2026-03",
    ]


def test_candidate_months_crosses_year_boundary():
    assert b._candidate_months("2026-02", 4) == ["2026-02", "2026-01", "2025-12", "2025-11"]


def _with_fake_download(fake):
    """Return a (setup, teardown) pair that swaps build_location_db._download for [fake]."""
    original = b._download
    b._download = fake
    return original


def test_resolve_csv_returns_current_month_first():
    # Every month "available": resolution must stop at the current month without walking back.
    calls: list[str] = []

    def fake(month: str, dest: Path) -> Path:
        calls.append(month)
        return dest

    original = _with_fake_download(fake)
    try:
        result = b._resolve_csv("2026-08", Path("/cache"))
    finally:
        b._download = original
    assert result.name == "dbip-city-lite-2026-08.csv.gz"
    assert calls == ["2026-08"]


def test_resolve_csv_falls_back_to_newest_available():
    # Only 2026-08 exists; starting from 2027-01 must walk back through 404s to it.
    def fake(month: str, dest: Path) -> Path | None:
        return dest if month == "2026-08" else None

    original = _with_fake_download(fake)
    try:
        result = b._resolve_csv("2027-01", Path("/cache"))
    finally:
        b._download = original
    assert result.name == "dbip-city-lite-2026-08.csv.gz"


def test_resolve_csv_raises_when_no_month_available():
    def fake(month: str, dest: Path) -> None:
        return None

    original = _with_fake_download(fake)
    try:
        raised = False
        try:
            b._resolve_csv("2030-06", Path("/cache"))
        except SystemExit:
            raised = True
    finally:
        b._download = original
    assert raised, "expected SystemExit when no month is available"


def _run():
    fns = [v for k, v in sorted(globals().items()) if k.startswith("test_")]
    for fn in fns:
        fn()
        print(f"ok  {fn.__name__}")
    print(f"\n{len(fns)} passed")


if __name__ == "__main__":
    _run()
