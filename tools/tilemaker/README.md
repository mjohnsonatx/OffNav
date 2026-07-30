# tilemaker for OffNav

This directory contains the official tilemaker v2.4.0 Windows executable, its
matching OpenMapTiles processing script, and OffNav's OSM-only configuration.
The OffNav configuration does not require coastline or Natural Earth shapefiles.
Version 2.4.0 is the newest official release that provides a prebuilt Windows
executable.

From the project root, convert an OpenStreetMap `.osm.pbf` extract into the
asset path already expected by OffNav:

```powershell
.\tools\build-offline-tiles.ps1 -InputPbf C:\path\to\region.osm.pbf
```

For a large extract, provide an SSD-backed temporary store:

```powershell
.\tools\build-offline-tiles.ps1 `
    -InputPbf C:\path\to\region.osm.pbf `
    -Store C:\path\to\tilemaker-work
```

The default output is
`app/src/main/assets/tiles/region.mbtiles`, matching `TileAssetManager`.

Source: <https://github.com/systemed/tilemaker/releases/tag/v2.4.0>

Downloaded archive SHA-256:
`280451DA4549176F17AA698B94F570BC0834349DA90FF6B343B9D80A5BBE4D65`
