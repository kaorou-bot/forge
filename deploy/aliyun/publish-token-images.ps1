[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string]$ManifestPath,
    [Parameter(Mandatory)] [string]$TokenImageDirectory,
    [Parameter(Mandatory)] [string]$StagingDirectory,
    [ValidatePattern('^[a-z0-9-]+$')] [string]$Bucket = 'forge-cn-images-a8k3',
    [string]$Ossutil = 'ossutil',
    [switch]$Upload
)
$ErrorActionPreference = 'Stop'
$manifest = Get-Content -LiteralPath $ManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($manifest.asset_type -ne 'forge_tokens' -or $manifest.file_count -ne $manifest.files.Count -or
    $manifest.release_id -notmatch '^tokens-[0-9]{8}-[0-9]{6}$') {
    throw 'Invalid tokens release manifest.'
}
$source = (Resolve-Path -LiteralPath $TokenImageDirectory).Path.TrimEnd('\')
$stage = [IO.Path]::GetFullPath($StagingDirectory)
if (Test-Path -LiteralPath $stage) { throw 'Use a new staging directory; existing data is never removed.' }
$seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
$validated = foreach ($row in $manifest.files) {
    $name = [string]$row.path
    if ($name -notmatch '^[^/]+/[^/]+\.jpg$' -or $name -match '[\\:]|(^|/)\.\.(/|$)' -or -not $seen.Add($name)) {
        throw "Unsafe or duplicate token path: $name"
    }
    $path = [IO.Path]::GetFullPath((Join-Path $source $name))
    if (-not $path.StartsWith($source + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Path escapes source.' }
    $file = Get-Item -LiteralPath $path
    if ($file.Length -ne $row.size -or (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ine $row.sha256) {
        throw "Source checksum mismatch: $name"
    }
    [pscustomobject]@{ Name=$name; Path=$path; Hash=$row.sha256 }
}
# Only verified manifest entries are staged. Do not upload metadata, unrelated files or delete cloud objects.
New-Item -ItemType Directory -Path $stage | Out-Null
foreach ($row in $validated) {
    $destination = Join-Path $stage $row.Name
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
    Copy-Item -LiteralPath $row.Path -Destination $destination
    if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ine $row.Hash) {
        throw "Staging checksum mismatch: $($row.Name)"
    }
}
Write-Output "Verified and staged $($validated.Count) token images."
$target = "oss://$Bucket/tokens/$($manifest.release_id)/"
Write-Output "Target: $target"
if (-not $Upload) { return }
# Fail fast on write-permission errors before submitting thousands of requests.
$probe = $validated[0]
& $Ossutil cp (Join-Path $stage $probe.Name) ($target + $probe.Name) --force `
    --content-type 'image/jpeg' --cache-control 'public,max-age=31536000,immutable'
if ($LASTEXITCODE -ne 0) { throw 'Token write preflight failed; bulk upload was not started.' }
# A release-specific prefix avoids overwriting existing token/card images or stale CDN content.
& $Ossutil cp ($stage + '\') $target --recursive --job 8 --force `
    --content-type 'image/jpeg' --cache-control 'public,max-age=31536000,immutable'
if ($LASTEXITCODE -ne 0) { throw "Token upload failed: $LASTEXITCODE" }
Write-Output 'Upload complete. Verify public CDN files before publishing client routing. No cloud objects deleted.'
