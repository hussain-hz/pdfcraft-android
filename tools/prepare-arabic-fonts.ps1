param([Parameter(Mandatory = $true)][string]$SourceDirectory)
$ErrorActionPreference = 'Stop'
$expectedRevision = 'd1cf47d05e04426615fbda1d637b9ca6b1e7af35'
$source = (Resolve-Path -LiteralPath $SourceDirectory).Path
$revision = (& git -C $source rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $revision -ne $expectedRevision) { throw "Expected craft-fonts revision $expectedRevision; found $revision" }
$entries = @(Get-Content -LiteralPath (Join-Path $source 'fonts/manifest.txt') | Where-Object { $_ -match '^Noto Sans Arabic \|' })
if ($entries.Count -ne 1) { throw 'Expected exactly one Noto Sans Arabic manifest entry' }
$fields = @($entries[0] -split ' \| ' | ForEach-Object { $_.Trim() })
if ($fields.Count -lt 8 -or $fields[3] -ne 'Arab' -or $fields[4] -ne 'OFL-1.1') { throw 'Unexpected Arabic font metadata' }
$font = Join-Path $source $fields[2]
$actual = (Get-FileHash -LiteralPath $font -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actual -ne $fields[6] -or $actual -ne '63111b5b2e074dd48cc67692e0a2726d86ee94c1c37fe8598257b7b4e87e869e') {
    throw 'Arabic font checksum does not match the pinned source'
}
if (-not (Test-Path -LiteralPath (Join-Path $source $fields[5]))) { throw 'Arabic font licence is missing' }
$env:CRAFT_FONTS_DIR = $source
$env:CRAFT_FONTS_REQUIRED = '1'
$env:CRAFT_FONT_SCRIPTS = 'Arab'
Write-Host "Verified Noto Sans Arabic ($actual), OFL-1.1; Arabic PDF editing enabled."
