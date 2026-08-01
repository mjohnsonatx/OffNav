[CmdletBinding()]
param(
    [string]$InputPbf = (Join-Path $PSScriptRoot '..\data\texas-260728.osm.pbf'),
    [string]$Output = (Join-Path $PSScriptRoot '..\app\src\main\assets\texas_outline.geojson'),
    [ValidateRange(0.0001, 0.05)]
    [double]$Tolerance = 0.0035
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$gradlew = Join-Path $repoRoot 'gradlew.bat'
$builderRoot = Join-Path $PSScriptRoot 'graphhopper-builder'
if (-not (Test-Path -LiteralPath $InputPbf -PathType Leaf)) {
    throw "Texas OSM PBF not found: $InputPbf"
}

$inputPath = (Resolve-Path -LiteralPath $InputPbf).Path
$outputPath = [System.IO.Path]::GetFullPath($Output)
$invariant = [Globalization.CultureInfo]::InvariantCulture
$extractorArgs = '--input "{0}" --output "{1}" --tolerance {2}' -f `
    $inputPath,
    $outputPath,
    $Tolerance.ToString($invariant)

& $gradlew --offline -p $builderRoot extractTexasOutline --args=$extractorArgs
if ($LASTEXITCODE -ne 0) {
    throw "Texas outline extractor failed with exit code $LASTEXITCODE"
}

Write-Host "Generated Texas outline: $outputPath"
