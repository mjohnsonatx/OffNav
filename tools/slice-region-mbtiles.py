#!/usr/bin/env python3
"""Create a bounded MBTiles archive from an existing local MBTiles source."""

from __future__ import annotations

import argparse
import math
import os
import sqlite3
from pathlib import Path


WEB_MERCATOR_LIMIT = 85.05112878


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--name", required=True)
    parser.add_argument("--min-lat", required=True, type=float)
    parser.add_argument("--max-lat", required=True, type=float)
    parser.add_argument("--min-lon", required=True, type=float)
    parser.add_argument("--max-lon", required=True, type=float)
    parser.add_argument("--force", action="store_true")
    return parser.parse_args()


def tile_x(longitude: float, zoom: int) -> int:
    size = 1 << zoom
    return min(size - 1, max(0, math.floor((longitude + 180.0) / 360.0 * size)))


def tile_y(latitude: float, zoom: int) -> int:
    size = 1 << zoom
    latitude = min(WEB_MERCATOR_LIMIT, max(-WEB_MERCATOR_LIMIT, latitude))
    radians = math.radians(latitude)
    value = (1.0 - math.asinh(math.tan(radians)) / math.pi) / 2.0 * size
    return min(size - 1, max(0, math.floor(value)))


def main() -> None:
    args = parse_args()
    source = args.source.resolve()
    output = args.output.resolve()
    if not source.is_file():
        raise SystemExit(f"Source MBTiles does not exist: {source}")
    if source == output:
        raise SystemExit("Source and output MBTiles paths must differ")
    if not (-90 <= args.min_lat < args.max_lat <= 90):
        raise SystemExit("Invalid latitude bounds")
    if not (-180 <= args.min_lon < args.max_lon <= 180):
        raise SystemExit("Invalid longitude bounds")
    if output.exists() and not args.force:
        raise SystemExit(f"Output exists; pass --force to replace it: {output}")

    output.parent.mkdir(parents=True, exist_ok=True)
    partial = output.with_name(output.name + ".partial")
    partial.unlink(missing_ok=True)

    source_uri = source.as_uri() + "?mode=ro"
    source_db = sqlite3.connect(source_uri, uri=True)
    target_db = sqlite3.connect(partial)
    try:
        source_tables = {
            row[0]
            for row in source_db.execute(
                "SELECT name FROM sqlite_master WHERE type IN ('table', 'view')"
            )
        }
        if not {"metadata", "tiles"}.issubset(source_tables):
            raise SystemExit("Source is missing required MBTiles metadata/tiles objects")

        target_db.executescript(
            """
            PRAGMA journal_mode = OFF;
            PRAGMA synchronous = OFF;
            PRAGMA temp_store = MEMORY;
            CREATE TABLE metadata (name TEXT PRIMARY KEY, value TEXT NOT NULL);
            CREATE TABLE tiles (
                zoom_level INTEGER NOT NULL,
                tile_column INTEGER NOT NULL,
                tile_row INTEGER NOT NULL,
                tile_data BLOB NOT NULL
            );
            """
        )
        metadata = dict(source_db.execute("SELECT name, value FROM metadata"))
        metadata.update(
            {
                "name": args.name,
                "bounds": (
                    f"{args.min_lon:.6f},{args.min_lat:.6f},"
                    f"{args.max_lon:.6f},{args.max_lat:.6f}"
                ),
                "center": (
                    f"{(args.min_lon + args.max_lon) / 2:.6f},"
                    f"{(args.min_lat + args.max_lat) / 2:.6f},9"
                ),
            }
        )
        target_db.executemany(
            "INSERT INTO metadata(name, value) VALUES (?, ?)", sorted(metadata.items())
        )

        copied = 0
        zoom_counts: list[tuple[int, int]] = []
        zooms = [row[0] for row in source_db.execute(
            "SELECT DISTINCT zoom_level FROM tiles ORDER BY zoom_level"
        )]
        for zoom in zooms:
            min_x = tile_x(args.min_lon, zoom)
            max_x = tile_x(args.max_lon, zoom)
            min_xyz_y = tile_y(args.max_lat, zoom)
            max_xyz_y = tile_y(args.min_lat, zoom)
            size = 1 << zoom
            min_tms_y = size - 1 - max_xyz_y
            max_tms_y = size - 1 - min_xyz_y
            rows = source_db.execute(
                """
                SELECT zoom_level, tile_column, tile_row, tile_data
                FROM tiles
                WHERE zoom_level = ?
                  AND tile_column BETWEEN ? AND ?
                  AND tile_row BETWEEN ? AND ?
                ORDER BY tile_column, tile_row
                """,
                (zoom, min_x, max_x, min_tms_y, max_tms_y),
            )
            count = 0
            while batch := rows.fetchmany(512):
                target_db.executemany(
                    "INSERT INTO tiles VALUES (?, ?, ?, ?)", batch
                )
                count += len(batch)
            copied += count
            zoom_counts.append((zoom, count))
            print(f"z{zoom}: {count:,} tiles")

        if copied == 0:
            raise SystemExit("Regional slice contains no tiles")
        target_db.execute(
            "CREATE UNIQUE INDEX tile_index ON tiles(zoom_level, tile_column, tile_row)"
        )
        target_db.commit()
        result = target_db.execute("PRAGMA integrity_check").fetchone()
        if result != ("ok",):
            raise SystemExit(f"Regional MBTiles integrity check failed: {result}")
    finally:
        target_db.close()
        source_db.close()

    if output.exists():
        output.unlink()
    os.replace(partial, output)
    print(f"Ready: {output} ({output.stat().st_size / 1_000_000:.1f} MB, {copied:,} tiles)")


if __name__ == "__main__":
    main()
