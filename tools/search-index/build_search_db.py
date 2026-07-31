#!/usr/bin/env python3
"""Build an immutable Android-compatible SQLite FTS4 place index from extractor TSV."""

from __future__ import annotations

import argparse
import csv
import os
import re
import sqlite3
from collections import Counter
from pathlib import Path


SCHEMA_VERSION = 2

CATEGORY_ALIASES = {
    "EV charging": "electric vehicle charger charging station",
    "Food and drink": "restaurant cafe dining food",
    "Fuel": "gas gasoline petrol station",
    "Healthcare": "hospital clinic doctor pharmacy medical",
    "Local business": "business shop store company",
    "Park": "park playground garden green space",
    "Transport": "bus train transit station stop",
}

ADDRESS_ABBREVIATIONS = {
    "avenue": "ave",
    "boulevard": "blvd",
    "circle": "cir",
    "court": "ct",
    "drive": "dr",
    "east": "e",
    "highway": "hwy",
    "lane": "ln",
    "north": "n",
    "parkway": "pkwy",
    "place": "pl",
    "road": "rd",
    "south": "s",
    "street": "st",
    "terrace": "ter",
    "trail": "trl",
    "west": "w",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--source", required=True)
    parser.add_argument("--bounds", required=True)
    return parser.parse_args()


def normalized_key(name: str, latitude: float, longitude: float) -> tuple[str, float, float]:
    return (" ".join(name.casefold().split()), round(latitude, 5), round(longitude, 5))


def search_aliases(name: str, subtitle: str, category: str) -> str:
    text = f"{name} {subtitle}".casefold()
    abbreviated = text
    for word, abbreviation in ADDRESS_ABBREVIATIONS.items():
        abbreviated = re.sub(rf"\b{word}\b", abbreviation, abbreviated)
    return f"{abbreviated} {CATEGORY_ALIASES.get(category, '')}".strip()


def build_database(input_path: Path, output_path: Path, source: str, bounds: str) -> None:
    if not input_path.is_file():
        raise FileNotFoundError(input_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    partial = output_path.with_suffix(output_path.suffix + ".partial")
    partial.unlink(missing_ok=True)

    counts: Counter[str] = Counter()
    seen: set[tuple[str, float, float]] = set()
    connection = sqlite3.connect(partial)
    try:
        connection.executescript(
            """
            PRAGMA journal_mode=OFF;
            PRAGMA synchronous=OFF;
            PRAGMA temp_store=MEMORY;
            PRAGMA page_size=4096;
            CREATE TABLE metadata (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            );
            CREATE TABLE places (
                id INTEGER PRIMARY KEY,
                osm_type TEXT NOT NULL,
                osm_id INTEGER NOT NULL,
                name TEXT NOT NULL,
                subtitle TEXT NOT NULL,
                category TEXT NOT NULL,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                search_text TEXT NOT NULL,
                rank INTEGER NOT NULL
            );
            CREATE VIRTUAL TABLE places_fts USING fts4(
                name,
                subtitle,
                category,
                search_text,
                content='places',
                tokenize=unicode61
            );
            """
        )
        insert_sql = """
            INSERT INTO places(
                osm_type, osm_id, name, subtitle, category,
                latitude, longitude, search_text, rank
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
        batch: list[tuple[object, ...]] = []
        with input_path.open("r", encoding="utf-8", newline="") as source_file:
            reader = csv.DictReader(source_file, delimiter="\t")
            for row in reader:
                latitude = float(row["latitude"])
                longitude = float(row["longitude"])
                key = normalized_key(row["name"], latitude, longitude)
                if key in seen:
                    continue
                seen.add(key)
                counts[row["category"]] += 1
                indexed_text = (
                    f"{row['search_text']} "
                    f"{search_aliases(row['name'], row['subtitle'], row['category'])}"
                ).strip()
                batch.append(
                    (
                        row["osm_type"],
                        int(row["osm_id"]),
                        row["name"],
                        row["subtitle"],
                        row["category"],
                        latitude,
                        longitude,
                        indexed_text,
                        int(row["rank"]),
                    )
                )
                if len(batch) >= 10_000:
                    connection.executemany(insert_sql, batch)
                    batch.clear()
        if batch:
            connection.executemany(insert_sql, batch)

        connection.executemany(
            "INSERT INTO metadata(key, value) VALUES (?, ?)",
            [
                ("schema_version", str(SCHEMA_VERSION)),
                ("source", source),
                ("bounds", bounds),
                ("record_count", str(sum(counts.values()))),
            ],
        )
        connection.execute("INSERT INTO places_fts(places_fts) VALUES ('rebuild')")
        connection.execute("INSERT INTO places_fts(places_fts) VALUES ('optimize')")
        connection.execute(f"PRAGMA user_version={SCHEMA_VERSION}")
        connection.commit()
        integrity = connection.execute("PRAGMA integrity_check").fetchone()[0]
        if integrity != "ok":
            raise RuntimeError(f"SQLite integrity check failed: {integrity}")
    finally:
        connection.close()

    os.replace(partial, output_path)
    print(f"Ready: {output_path} ({output_path.stat().st_size / 1_000_000:.1f} MB)")
    print(f"Records: {sum(counts.values()):,}")
    for category, count in counts.most_common():
        print(f"  {category}: {count:,}")


def main() -> None:
    args = parse_args()
    build_database(args.input, args.output, args.source, args.bounds)


if __name__ == "__main__":
    main()
