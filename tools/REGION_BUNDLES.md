# OffNav regional bundles

`build-metro-regions.ps1` creates prebuilt, versioned `.offnav` files from the
local Texas OSM snapshot and the existing full-Texas MBTiles archive. It does
not download dependencies or require Tilemaker to rebuild Texas.

## Included regions

| Region ID | Bounds (west, south, east, north) | Route verification |
| --- | --- | --- |
| `austin` | `-99.15,28.95,-97.53,30.52` | Downtown Austin to downtown San Antonio |
| `dallas-fort-worth` | `-98.05,32.25,-96.15,33.55` | Dallas to Fort Worth |
| `san-antonio` | `-99.15,28.95,-97.55,30.25` | Downtown to north-central San Antonio |
| `houston` | `-96.10,28.85,-94.45,30.55` | Downtown Houston to Sugar Land |

## Build

From the repository root:

```powershell
.\tools\build-metro-regions.ps1 `
    -Regions austin,dallas-fort-worth,san-antonio,houston `
    -WorkRoot 'D:\OffNav-region-work'
```

Use `-Force` to replace existing output and temporary work. The default source
version is `2026-07-28`; output is written to `build\offline-regions`. Regional
map tiles are sliced from `app\src\main\assets\tiles\region.mbtiles`, routing
and search data are built from `data\texas-260728.osm.pbf`, and all builders run
offline from the repository's existing Gradle ecosystem.

The `austin` bundle keeps its existing region ID for upgrade compatibility, but
is displayed as `Austin-San Antonio`. Its single continuous rectangle includes
both metros and the complete connecting I-35 corridor, with a route check from
downtown Austin to downtown San Antonio.

Each bundle contains these uncompressed ZIP entries in a fixed order:

1. `manifest.properties`
2. `tiles.mbtiles`
3. `routing.ghz`
4. `search.db`

The manifest records format version, region identity, geographic bounds, byte
sizes, and SHA-256 digests. Packaging reopens the completed file and verifies
entry order, sizes, and hashes before it is accepted. SQLite integrity and
search schema checks run before packaging, and each routing graph must answer a
representative regional route.

The `.offnav` files are intended to be copied to an Android device (for example
over USB) and consumed without compiling data on the phone. The Android-side
bundle importer/activation feature is separate and is not implemented by this
desktop preparation pipeline.
