#!/usr/bin/env python3
"""Verify the SQLite portions of an OffNav region build using the Python standard library."""

from __future__ import annotations

import argparse
import sqlite3
from pathlib import Path


def open_read_only(path: Path) -> sqlite3.Connection:
    return sqlite3.connect(f"file:{path.as_posix()}?mode=ro", uri=True)


def verify_database(path: Path, required_tables: set[str]) -> None:
    if not path.is_file() or path.stat().st_size == 0:
        raise RuntimeError(f"Database is missing or empty: {path}")
    with open_read_only(path) as connection:
        result = connection.execute("PRAGMA integrity_check").fetchone()
        if result is None or result[0] != "ok":
            raise RuntimeError(f"SQLite integrity check failed for {path}: {result}")
        tables = {
            row[0]
            for row in connection.execute(
                "SELECT name FROM sqlite_master WHERE type IN ('table', 'view')"
            )
        }
        missing = required_tables - tables
        if missing:
            raise RuntimeError(f"Missing tables in {path}: {sorted(missing)}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tiles", type=Path, required=True)
    parser.add_argument("--search", type=Path, required=True)
    args = parser.parse_args()

    verify_database(args.tiles.resolve(), {"metadata", "tiles"})
    verify_database(args.search.resolve(), {"places", "places_fts"})
    with open_read_only(args.search.resolve()) as connection:
        schema = connection.execute("PRAGMA user_version").fetchone()[0]
        if schema != 2:
            raise RuntimeError(f"Expected search schema 2, found {schema}")
    print(f"Verified SQLite integrity: {args.tiles}")
    print(f"Verified SQLite integrity and schema 2: {args.search}")


if __name__ == "__main__":
    main()
