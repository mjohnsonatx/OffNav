param(
    [string]$InputPbf,
    [string]$OutputAsset,
    [double]$MinLatitude = 30.0980,
    [double]$MaxLatitude = 30.5160,
    [double]$MinLongitude = -97.9380,
    [double]$MaxLongitude = -97.5610
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$gradlew = Join-Path $repoRoot 'gradlew.bat'
$builderRoot = Join-Path $PSScriptRoot 'graphhopper-builder'
$databaseBuilder = Join-Path $PSScriptRoot 'search-index\build_search_db.py'

if (-not $InputPbf) {
    $InputPbf = Join-Path $repoRoot 'data\texas-260728.osm.pbf'
}
if (-not $OutputAsset) {
    $OutputAsset = Join-Path $repoRoot 'app\src\main\assets\search\austin_places.db'
}
if (-not (Test-Path -LiteralPath $InputPbf -PathType Leaf)) {
    throw "OSM PBF not found: $InputPbf"
}

$pythonCommand = Get-Command python -ErrorAction Stop
$resolvedInput = (Resolve-Path -LiteralPath $InputPbf).Path
$resolvedOutput = [System.IO.Path]::GetFullPath($OutputAsset)
$intermediateDirectory = Join-Path $repoRoot 'build\search-index'
$intermediateTsv = Join-Path $intermediateDirectory 'austin_candidates.tsv'
New-Item -ItemType Directory -Path $intermediateDirectory -Force | Out-Null

$extractorArgs = '--input "{0}" --output "{1}" --min-lat {2} --max-lat {3} --min-lon {4} --max-lon {5}' -f `
    $resolvedInput,
    $intermediateTsv,
    $MinLatitude.ToString([Globalization.CultureInfo]::InvariantCulture),
    $MaxLatitude.ToString([Globalization.CultureInfo]::InvariantCulture),
    $MinLongitude.ToString([Globalization.CultureInfo]::InvariantCulture),
    $MaxLongitude.ToString([Globalization.CultureInfo]::InvariantCulture)

Write-Host "Extracting Austin addresses and POIs"
& $gradlew -p $builderRoot extractSearchData --args=$extractorArgs
if ($LASTEXITCODE -ne 0) {
    throw "Austin search extractor failed with exit code $LASTEXITCODE"
}

$bounds = '{0},{1},{2},{3}' -f $MinLatitude,$MinLongitude,$MaxLatitude,$MaxLongitude
Write-Host "Building SQLite FTS5 asset"
& $pythonCommand.Source $databaseBuilder `
    --input $intermediateTsv `
    --output $resolvedOutput `
    --source ([System.IO.Path]::GetFileName($resolvedInput)) `
    --bounds $bounds
if ($LASTEXITCODE -ne 0) {
    throw "SQLite search database builder failed with exit code $LASTEXITCODE"
}

Write-Host "Generated Austin search asset: $resolvedOutput"
