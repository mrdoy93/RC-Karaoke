param(
    [string]$SongDirectory = (Join-Path $PSScriptRoot '..\songs'),
    [string]$CatalogFile = (Join-Path $PSScriptRoot '..\app\src\main\assets\song_catalog.tsv'),
    [string]$PackFile = (Join-Path $PSScriptRoot '..\app\src\main\assets\songs.kpack')
)

$ErrorActionPreference = 'Stop'
$resolvedSongs = (Resolve-Path -LiteralPath $SongDirectory).Path
$files = [System.IO.Directory]::GetFiles($resolvedSongs, '*.mid', [System.IO.SearchOption]::TopDirectoryOnly)
[Array]::Sort($files, [System.StringComparer]::OrdinalIgnoreCase)

$encoding = New-Object System.Text.UTF8Encoding($false)
$writer = New-Object System.IO.StreamWriter($CatalogFile, $false, $encoding)
$pack = New-Object System.IO.FileStream($PackFile, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
$seenNumbers = New-Object 'System.Collections.Generic.HashSet[string]'
$written = 0
$rawBytes = [long]0

try {
    $writer.WriteLine("number`ttitle`tartist`toffset`tpackedLength`trawLength")
    foreach ($path in $files) {
        $fileName = [System.IO.Path]::GetFileName($path)
        $baseName = [System.IO.Path]::GetFileNameWithoutExtension($path)
        $firstSeparator = $baseName.IndexOf(' - ')
        $lastSeparator = $baseName.LastIndexOf(' - ')

        if ($firstSeparator -le 0 -or $lastSeparator -le $firstSeparator) {
            throw "Unexpected filename format: $fileName"
        }

        $number = $baseName.Substring(0, $firstSeparator).Trim()
        $title = $baseName.Substring($firstSeparator + 3, $lastSeparator - $firstSeparator - 3).Trim()
        $artist = $baseName.Substring($lastSeparator + 3).Trim()
        $parsedNumber = 0
        if (-not [int]::TryParse($number, [ref]$parsedNumber)) {
            throw "Invalid song number in: $fileName"
        }
        if (-not $seenNumbers.Add($number)) {
            throw "Duplicate song number: $number"
        }

        $title = $title.Replace("`t", ' ').Replace("`r", ' ').Replace("`n", ' ')
        $artist = $artist.Replace("`t", ' ').Replace("`r", ' ').Replace("`n", ' ')
        $offset = $pack.Position
        $input = New-Object System.IO.FileStream($path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::Read)
        try {
            $rawLength = $input.Length
            $gzip = New-Object System.IO.Compression.GZipStream($pack, [System.IO.Compression.CompressionLevel]::Optimal, $true)
            try {
                $input.CopyTo($gzip)
            }
            finally {
                $gzip.Dispose()
            }
        }
        finally {
            $input.Dispose()
        }

        $packedLength = $pack.Position - $offset
        $writer.WriteLine("$number`t$title`t$artist`t$offset`t$packedLength`t$rawLength")
        $written++
        $rawBytes += $rawLength
        if (($written % 5000) -eq 0) {
            Write-Output "Packed $written / $($files.Count) songs"
        }
    }
}
finally {
    $writer.Dispose()
    $pack.Dispose()
}

$packedBytes = (Get-Item -LiteralPath $PackFile).Length
Write-Output "Generated $written catalog entries at $CatalogFile"
Write-Output ("Song pack: {0:N2} MB from {1:N2} MB ({2:P1})" -f ($packedBytes / 1MB), ($rawBytes / 1MB), ($packedBytes / [double]$rawBytes))
