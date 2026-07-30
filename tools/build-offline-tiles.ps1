[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateScript({ Test-Path -LiteralPath $_ -PathType Leaf })]
    [string] $InputPbf,

    [string] $Output = (Join-Path $PSScriptRoot '..\app\src\main\assets\tiles\region.mbtiles'),

    [string] $Store
)

$ErrorActionPreference = 'Stop'

$tilemakerRoot = Join-Path $PSScriptRoot 'tilemaker'
$tilemakerExe = Join-Path $tilemakerRoot 'tilemaker.exe'
$configPath = Join-Path $tilemakerRoot 'resources\config-offnav.json'
$processPath = Join-Path $tilemakerRoot 'resources\process-openmaptiles.lua'

foreach ($requiredFile in @($tilemakerExe, $configPath, $processPath)) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
        throw "Required tilemaker file is missing: $requiredFile"
    }
}

$inputPath = (Resolve-Path -LiteralPath $InputPbf).Path
$outputPath = [System.IO.Path]::GetFullPath($Output)
$outputDirectory = Split-Path -Parent $outputPath
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null

$tilemakerArguments = @(
    '--input'
    $inputPath
    '--output'
    $outputPath
    '--config'
    $configPath
    '--process'
    $processPath
)

if ($Store) {
    $storePath = [System.IO.Path]::GetFullPath($Store)
    New-Item -ItemType Directory -Path $storePath -Force | Out-Null
    $tilemakerArguments += @('--store', $storePath)
}

& $tilemakerExe @tilemakerArguments
if ($LASTEXITCODE -ne 0) {
    throw "tilemaker failed with exit code $LASTEXITCODE"
}

Write-Host "Created offline map tiles at $outputPath"
