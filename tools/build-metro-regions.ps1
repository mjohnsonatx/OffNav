[CmdletBinding()]
param(
    [ValidateSet('austin', 'dallas-fort-worth', 'san-antonio', 'houston')]
    [string[]]$Regions = @('dallas-fort-worth', 'san-antonio', 'houston'),

    [string]$InputPbf = (Join-Path $PSScriptRoot '..\data\texas-260728.osm.pbf'),
    [string]$SourceMbtiles = (Join-Path $PSScriptRoot '..\app\src\main\assets\tiles\region.mbtiles'),
    [string]$Version = '2026-07-28',
    [string]$OutputRoot = (Join-Path $PSScriptRoot '..\build\offline-regions'),
    [string]$WorkRoot = (Join-Path $PSScriptRoot '..\build\region-work'),
    [switch]$KeepIntermediate,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$inputPath = (Resolve-Path -LiteralPath $InputPbf).Path
$sourceMbtilesPath = (Resolve-Path -LiteralPath $SourceMbtiles).Path
$outputRootPath = [System.IO.Path]::GetFullPath($OutputRoot)
$workRootPath = [System.IO.Path]::GetFullPath($WorkRoot)
New-Item -ItemType Directory -Path $outputRootPath -Force | Out-Null
New-Item -ItemType Directory -Path $workRootPath -Force | Out-Null

$definitions = @{
    'austin' = [pscustomobject]@{
        Id = 'austin'; DisplayName = 'Austin-San Antonio'
        MinLat = 28.9500; MaxLat = 30.5200; MinLon = -99.1500; MaxLon = -97.5300
        FromLat = 30.2672; FromLon = -97.7431; ToLat = 29.4241; ToLon = -98.4936
    }
    'dallas-fort-worth' = [pscustomobject]@{
        Id = 'dallas-fort-worth'; DisplayName = 'Dallas-Fort Worth'
        MinLat = 32.25; MaxLat = 33.55; MinLon = -98.05; MaxLon = -96.15
        FromLat = 32.7767; FromLon = -96.7970; ToLat = 32.7555; ToLon = -97.3308
    }
    'san-antonio' = [pscustomobject]@{
        Id = 'san-antonio'; DisplayName = 'San Antonio'
        MinLat = 28.95; MaxLat = 30.25; MinLon = -99.15; MaxLon = -97.55
        FromLat = 29.4241; FromLon = -98.4936; ToLat = 29.5502; ToLon = -98.5086
    }
    'houston' = [pscustomobject]@{
        Id = 'houston'; DisplayName = 'Greater Houston'
        MinLat = 28.85; MaxLat = 30.55; MinLon = -96.10; MaxLon = -94.45
        FromLat = 29.7604; FromLon = -95.3698; ToLat = 29.6197; ToLon = -95.6349
    }
}

function Remove-RegionWork {
    param([string]$Target)
    $rootPrefix = $workRootPath.TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
    $targetPath = [System.IO.Path]::GetFullPath($Target)
    if (-not $targetPath.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to remove work outside $workRootPath`: $targetPath"
    }
    if (Test-Path -LiteralPath $targetPath) {
        Remove-Item -LiteralPath $targetPath -Recurse -Force
    }
}

foreach ($regionId in $Regions) {
    $region = $definitions[$regionId]
    $bundlePath = Join-Path $outputRootPath "$($region.Id)-$Version.offnav"
    if ((Test-Path -LiteralPath $bundlePath) -and -not $Force) {
        Write-Host "Skipping existing bundle: $bundlePath"
        continue
    }

    $regionWork = Join-Path $workRootPath $region.Id
    if (Test-Path -LiteralPath $regionWork) {
        if (-not $Force) {
            throw "Work directory already exists; pass -Force to replace it: $regionWork"
        }
        Remove-RegionWork -Target $regionWork
    }
    New-Item -ItemType Directory -Path $regionWork -Force | Out-Null

    $regionalPbf = Join-Path $regionWork "$($region.Id).osm.pbf"
    $tiles = Join-Path $regionWork 'tiles.mbtiles'
    $routing = Join-Path $regionWork 'routing.ghz'
    $search = Join-Path $regionWork 'search.db'
    $routingWork = Join-Path $regionWork 'graphhopper-work'

    Write-Host ""
    Write-Host "=== $($region.DisplayName) ==="
    & (Join-Path $PSScriptRoot 'extract-region-pbf.ps1') `
        -InputPbf $inputPath -OutputPbf $regionalPbf -RegionId $region.Id `
        -MinLatitude $region.MinLat -MaxLatitude $region.MaxLat `
        -MinLongitude $region.MinLon -MaxLongitude $region.MaxLon

    $tileSliceArgs = @(
        (Join-Path $PSScriptRoot 'slice-region-mbtiles.py'),
        '--source', $sourceMbtilesPath,
        '--output', $tiles,
        '--name', "OffNav $($region.DisplayName)",
        '--min-lat', $region.MinLat.ToString([Globalization.CultureInfo]::InvariantCulture),
        '--max-lat', $region.MaxLat.ToString([Globalization.CultureInfo]::InvariantCulture),
        '--min-lon', $region.MinLon.ToString([Globalization.CultureInfo]::InvariantCulture),
        '--max-lon', $region.MaxLon.ToString([Globalization.CultureInfo]::InvariantCulture)
    )
    if ($Force) { $tileSliceArgs += '--force' }
    & python @tileSliceArgs
    if ($LASTEXITCODE -ne 0) {
        throw "$($region.Id) MBTiles slice failed with exit code $LASTEXITCODE"
    }

    & (Join-Path $PSScriptRoot 'build-routing-graph.ps1') `
        -InputPbf $regionalPbf -WorkRoot $routingWork -OutputAsset $routing `
        -VerifyLabel $region.Id `
        -VerifyFromLatitude $region.FromLat -VerifyFromLongitude $region.FromLon `
        -VerifyToLatitude $region.ToLat -VerifyToLongitude $region.ToLon

    & (Join-Path $PSScriptRoot 'build-austin-search.ps1') `
        -InputPbf $regionalPbf -OutputAsset $search -RegionId $region.Id `
        -WorkRoot (Join-Path $regionWork 'search-work') `
        -MinLatitude $region.MinLat -MaxLatitude $region.MaxLat `
        -MinLongitude $region.MinLon -MaxLongitude $region.MaxLon

    & python (Join-Path $PSScriptRoot 'verify-region-sqlite.py') `
        --tiles $tiles --search $search
    if ($LASTEXITCODE -ne 0) {
        throw "$($region.Id) SQLite verification failed with exit code $LASTEXITCODE"
    }

    $packageArgs = @{
        RegionId = $region.Id; DisplayName = $region.DisplayName; Version = $Version
        Tiles = $tiles; Routing = $routing; Search = $search; Output = $bundlePath
        MinLatitude = $region.MinLat; MaxLatitude = $region.MaxLat
        MinLongitude = $region.MinLon; MaxLongitude = $region.MaxLon
        Force = $Force
    }
    & (Join-Path $PSScriptRoot 'package-region-bundle.ps1') @packageArgs

    if (-not $KeepIntermediate) {
        Remove-RegionWork -Target $regionWork
        Write-Host "Removed verified temporary work: $regionWork"
    }
}

Write-Host ""
Write-Host "Completed region bundles:"
Get-ChildItem -LiteralPath $outputRootPath -Filter '*.offnav' | ForEach-Object {
    Write-Host ("  {0} ({1:N1} MB)" -f $_.FullName, ($_.Length / 1MB))
}
