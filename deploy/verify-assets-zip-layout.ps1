[CmdletBinding()]
param([Parameter(Mandatory = $true)][string]$AssetsZip)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$resolvedArchive = (Resolve-Path -LiteralPath $AssetsZip).Path
$archive = [IO.Compression.ZipFile]::OpenRead($resolvedArchive)
try {
    $directories = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $names = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($entry in $archive.Entries) {
        $name = $entry.FullName
        if (-not $names.Add($name) -or $name.StartsWith('/') -or $name.Contains('\') -or
            $name.Contains(':') -or $name.Contains([char]0) -or
            @($name.Split('/') | Where-Object { $_ -eq '..' }).Count) {
            throw "Unsafe or duplicate resource entry: $name"
        }
        $trimmed = $name.TrimEnd('/')
        $slash = $trimmed.LastIndexOf('/')
        if ($slash -ge 0 -and -not $directories.Contains($trimmed.Substring(0, $slash + 1))) {
            throw "Missing earlier parent directory entry for $name. Older Forge clients cannot extract this archive. Use deploy/repair-assets-zip-directories.py."
        }
        if ($name.EndsWith('/')) { $null = $directories.Add($name) }
    }
    foreach ($required in @('res/build.txt', 'res/cardsfolder/cardsfolder.zip',
        'res/languages/en-US.properties', 'res/languages/zh-CN.properties',
        'res/skins/default/bg_splash.png', 'res/skins/default/font1.ttf')) {
        $entry = $archive.GetEntry($required)
        if ($null -eq $entry -or $entry.Length -le 0) { throw "Missing or empty core resource: $required" }
    }
    Write-Output "Resource archive layout compatible: $($archive.Entries.Count) entries, $($directories.Count) directories."
} finally { $archive.Dispose() }
