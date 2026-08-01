[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateScript({ Test-Path -LiteralPath $_ -PathType Leaf })]
    [string]$InputPbf,

    [Parameter(Mandatory = $true)]
    [string]$OutputPbf,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-z0-9][a-z0-9_-]{0,63}$')]
    [string]$RegionId,

    [Parameter(Mandatory = $true)]
    [double]$MinLatitude,

    [Parameter(Mandatory = $true)]
    [double]$MaxLatitude,

    [Parameter(Mandatory = $true)]
    [double]$MinLongitude,

    [Parameter(Mandatory = $true)]
    [double]$MaxLongitude
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$gradlew = Join-Path $repoRoot 'gradlew.bat'
$builderRoot = Join-Path $PSScriptRoot 'graphhopper-builder'
$inputPath = (Resolve-Path -LiteralPath $InputPbf).Path
$outputPath = [System.IO.Path]::GetFullPath($OutputPbf)
$outputDirectory = Split-Path -Parent $outputPath
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null

$invariant = [Globalization.CultureInfo]::InvariantCulture
$extractorArgs = '--input "{0}" --output "{1}" --region "{2}" --min-lat {3} --max-lat {4} --min-lon {5} --max-lon {6}' -f `
    $inputPath,
    $outputPath,
    $RegionId,
    $MinLatitude.ToString($invariant),
    $MaxLatitude.ToString($invariant),
    $MinLongitude.ToString($invariant),
    $MaxLongitude.ToString($invariant)

& $gradlew --offline -p $builderRoot extractRegionPbf --args=$extractorArgs
if ($LASTEXITCODE -ne 0) {
    throw "$RegionId PBF extractor failed with exit code $LASTEXITCODE"
}

Write-Host "Generated $RegionId OSM extract: $outputPath"
