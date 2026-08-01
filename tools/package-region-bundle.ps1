[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-z0-9][a-z0-9_-]{0,63}$')]
    [string]$RegionId,

    [Parameter(Mandatory = $true)]
    [string]$DisplayName,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-z0-9][a-z0-9_-]{0,63}$')]
    [string]$Version,

    [Parameter(Mandatory = $true)]
    [ValidateScript({ Test-Path -LiteralPath $_ -PathType Leaf })]
    [string]$Tiles,

    [Parameter(Mandatory = $true)]
    [ValidateScript({ Test-Path -LiteralPath $_ -PathType Leaf })]
    [string]$Routing,

    [Parameter(Mandatory = $true)]
    [ValidateScript({ Test-Path -LiteralPath $_ -PathType Leaf })]
    [string]$Search,

    [Parameter(Mandatory = $true)]
    [string]$Output,

    [Parameter(Mandatory = $true)]
    [double]$MinLatitude,

    [Parameter(Mandatory = $true)]
    [double]$MaxLatitude,

    [Parameter(Mandatory = $true)]
    [double]$MinLongitude,

    [Parameter(Mandatory = $true)]
    [double]$MaxLongitude,

    [int]$SearchSchema = 2,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Get-AssetInfo {
    param([string]$Path, [string]$EntryName)
    $resolved = (Resolve-Path -LiteralPath $Path).Path
    $file = Get-Item -LiteralPath $resolved
    [pscustomobject]@{
        Path = $resolved
        EntryName = $EntryName
        Bytes = $file.Length
        Sha256 = (Get-FileHash -LiteralPath $resolved -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}

function Copy-ZipEntry {
    param(
        [System.IO.Compression.ZipArchive]$Archive,
        [pscustomobject]$Asset
    )
    $entry = $Archive.CreateEntry(
        $Asset.EntryName,
        [System.IO.Compression.CompressionLevel]::NoCompression
    )
    $input = [System.IO.File]::OpenRead($Asset.Path)
    $outputStream = $entry.Open()
    try {
        $input.CopyTo($outputStream, 1MB)
    } finally {
        $outputStream.Dispose()
        $input.Dispose()
    }
}

if ($SearchSchema -lt 1) { throw 'SearchSchema must be positive' }
if ($MinLatitude -ge $MaxLatitude -or $MinLongitude -ge $MaxLongitude) {
    throw 'Invalid region bounds'
}

$assets = @(
    Get-AssetInfo -Path $Tiles -EntryName 'tiles.mbtiles'
    Get-AssetInfo -Path $Routing -EntryName 'routing.ghz'
    Get-AssetInfo -Path $Search -EntryName 'search.db'
)
$byEntry = @{}
foreach ($asset in $assets) { $byEntry[$asset.EntryName] = $asset }

$invariant = [Globalization.CultureInfo]::InvariantCulture
$manifestLines = @(
    'format=1'
    "id=$RegionId"
    "displayName=$DisplayName"
    "version=$Version"
    "searchSchema=$SearchSchema"
    ('minLatitude=' + $MinLatitude.ToString($invariant))
    ('maxLatitude=' + $MaxLatitude.ToString($invariant))
    ('minLongitude=' + $MinLongitude.ToString($invariant))
    ('maxLongitude=' + $MaxLongitude.ToString($invariant))
    "tiles.bytes=$($byEntry['tiles.mbtiles'].Bytes)"
    "tiles.sha256=$($byEntry['tiles.mbtiles'].Sha256)"
    "routing.bytes=$($byEntry['routing.ghz'].Bytes)"
    "routing.sha256=$($byEntry['routing.ghz'].Sha256)"
    "search.bytes=$($byEntry['search.db'].Bytes)"
    "search.sha256=$($byEntry['search.db'].Sha256)"
)
$manifest = ($manifestLines -join "`n") + "`n"

$outputPath = [System.IO.Path]::GetFullPath($Output)
$outputDirectory = Split-Path -Parent $outputPath
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
if ((Test-Path -LiteralPath $outputPath) -and -not $Force) {
    throw "Bundle already exists; pass -Force to replace it: $outputPath"
}
$partialPath = "$outputPath.partial"
if (Test-Path -LiteralPath $partialPath) {
    Remove-Item -LiteralPath $partialPath -Force
}

try {
    $fileStream = [System.IO.File]::Open(
        $partialPath,
        [System.IO.FileMode]::CreateNew,
        [System.IO.FileAccess]::ReadWrite,
        [System.IO.FileShare]::None
    )
    $archive = [System.IO.Compression.ZipArchive]::new(
        $fileStream,
        [System.IO.Compression.ZipArchiveMode]::Create,
        $false
    )
    try {
        $manifestEntry = $archive.CreateEntry(
            'manifest.properties',
            [System.IO.Compression.CompressionLevel]::NoCompression
        )
        $manifestStream = $manifestEntry.Open()
        $writer = [System.IO.StreamWriter]::new(
            $manifestStream,
            [System.Text.UTF8Encoding]::new($false)
        )
        try {
            $writer.Write($manifest)
        } finally {
            $writer.Dispose()
        }
        foreach ($asset in $assets) {
            Copy-ZipEntry -Archive $archive -Asset $asset
        }
    } finally {
        $archive.Dispose()
        $fileStream.Dispose()
    }

    $readStream = [System.IO.File]::OpenRead($partialPath)
    $verifyArchive = [System.IO.Compression.ZipArchive]::new(
        $readStream,
        [System.IO.Compression.ZipArchiveMode]::Read,
        $false
    )
    try {
        $actualNames = @($verifyArchive.Entries | ForEach-Object { $_.FullName })
        $expectedNames = @('manifest.properties', 'tiles.mbtiles', 'routing.ghz', 'search.db')
        if (($actualNames -join '|') -ne ($expectedNames -join '|')) {
            throw "Bundle entry order mismatch: $($actualNames -join ', ')"
        }
        foreach ($asset in $assets) {
            $entry = $verifyArchive.GetEntry($asset.EntryName)
            if ($null -eq $entry -or $entry.Length -ne $asset.Bytes) {
                throw "Bundle length mismatch for $($asset.EntryName)"
            }
            $sha = [System.Security.Cryptography.SHA256]::Create()
            $entryStream = $entry.Open()
            try {
                $actualHash = ([BitConverter]::ToString($sha.ComputeHash($entryStream))).Replace('-', '').ToLowerInvariant()
            } finally {
                $entryStream.Dispose()
                $sha.Dispose()
            }
            if ($actualHash -ne $asset.Sha256) {
                throw "Bundle hash mismatch for $($asset.EntryName)"
            }
        }
    } finally {
        $verifyArchive.Dispose()
        $readStream.Dispose()
    }

    if (Test-Path -LiteralPath $outputPath) {
        Remove-Item -LiteralPath $outputPath -Force
    }
    Move-Item -LiteralPath $partialPath -Destination $outputPath
} catch {
    if (Test-Path -LiteralPath $partialPath) {
        Remove-Item -LiteralPath $partialPath -Force
    }
    throw
}

$bundle = Get-Item -LiteralPath $outputPath
Write-Host ("Created {0}: {1:N1} MB" -f $outputPath, ($bundle.Length / 1MB))
Write-Host "SHA-256: $((Get-FileHash -LiteralPath $outputPath -Algorithm SHA256).Hash)"
