param(
    [string]$InputPbf,
    [string]$WorkRoot = 'D:\OffNav-graphhopper-work',
    [string]$OutputAsset,
    [string]$VerifyLabel = 'Austin',
    [double]$VerifyFromLatitude = 30.2672,
    [double]$VerifyFromLongitude = -97.7431,
    [double]$VerifyToLatitude = 30.2850,
    [double]$VerifyToLongitude = -97.7350
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$gradlew = Join-Path $repoRoot 'gradlew.bat'
$builderRoot = Join-Path $PSScriptRoot 'graphhopper-builder'

if (-not $InputPbf) {
    $preferredInput = Join-Path $repoRoot 'data\texas-260728.osm.pbf'
    $legacyInput = Join-Path $repoRoot 'app\src\main\assets\routing\region.osm.pbf'
    $InputPbf = if (Test-Path -LiteralPath $preferredInput) { $preferredInput } else { $legacyInput }
}
if (-not $OutputAsset) {
    $OutputAsset = Join-Path $repoRoot 'app\src\main\assets\routing\region.ghz'
}
if (-not (Test-Path -LiteralPath $InputPbf -PathType Leaf)) {
    throw "OSM PBF not found: $InputPbf"
}

$resolvedInput = (Resolve-Path -LiteralPath $InputPbf).Path
$resolvedWorkRoot = [System.IO.Path]::GetFullPath($WorkRoot)
$resolvedOutput = [System.IO.Path]::GetFullPath($OutputAsset)
$runName = 'run-{0}' -f (Get-Date -Format 'yyyyMMdd-HHmmss')
$runPath = Join-Path $resolvedWorkRoot $runName

New-Item -ItemType Directory -Path $resolvedWorkRoot -Force | Out-Null
$invariant = [Globalization.CultureInfo]::InvariantCulture
$builderArgs = '--input "{0}" --work "{1}" --output "{2}" --verify-label "{3}" --verify-from-lat {4} --verify-from-lon {5} --verify-to-lat {6} --verify-to-lon {7}' -f `
    $resolvedInput,
    $runPath,
    $resolvedOutput,
    $VerifyLabel,
    $VerifyFromLatitude.ToString($invariant),
    $VerifyFromLongitude.ToString($invariant),
    $VerifyToLatitude.ToString($invariant),
    $VerifyToLongitude.ToString($invariant)

Write-Host "Building GraphHopper graph in $runPath"
& $gradlew -p $builderRoot run --args=$builderArgs
if ($LASTEXITCODE -ne 0) {
    throw "GraphHopper builder failed with exit code $LASTEXITCODE"
}

Write-Host "Generated routing asset: $resolvedOutput"
