[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string]$PackageDirectory,
    [Parameter(Mandatory)] [string]$OutputDirectory,
    [string]$PreviousPackageDirectory,
    [string]$PreviousVersion,
    [string]$PreviousBuildId,
    [string]$Compiler = "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe"
)

$ErrorActionPreference = 'Stop'
$package = (Resolve-Path -LiteralPath $PackageDirectory).Path
$compilerPath = (Resolve-Path -LiteralPath $Compiler).Path
$scriptPath = Join-Path $PSScriptRoot 'windows\forge-community.iss'
[xml]$project = Get-Content -LiteralPath (Join-Path $PSScriptRoot '..\pom.xml') -Raw -Encoding UTF8
$buildId = [string]$project.project.properties.versionCode + [string]$project.project.properties.snapshotName
$displayVersion = [string]$project.project.properties.displayVersion
$marker = Join-Path $package 'forge-community-version.txt'
if (-not (Test-Path -LiteralPath (Join-Path $package 'forge.exe')) -or
    -not (Test-Path -LiteralPath (Join-Path $package 'runtime\bin\java.exe')) -or
    -not (Test-Path -LiteralPath (Join-Path $package 'res')) -or
    -not (Test-Path -LiteralPath $marker)) {
    throw 'PackageDirectory is not a complete Forge desktop build.'
}
if ((Get-Content -LiteralPath $marker -Raw -Encoding UTF8).Trim() -ne $displayVersion) {
    throw 'Package version marker does not match pom.xml.'
}
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$output = (Resolve-Path -LiteralPath $OutputDirectory).Path

function Invoke-Compiler([string]$source, [string]$kind, [string]$name) {
    $log = Join-Path $output "$name-build.log"
    & $compilerPath "/DSourceDir=$source" "/DOutputDir=$output" "/DBuildVersion=$buildId" `
        "/DDisplayVersion=$displayVersion" "/DPackageKind=$kind" "/DOutputName=$name" $scriptPath *> $log
    if ($LASTEXITCODE -ne 0) {
        Get-Content -LiteralPath $log -Tail 35
        throw "Inno Setup compilation failed: $kind (see $log)"
    }
    Write-Output "Compiled $kind installer (log: $log)"
    $result = Join-Path $output "$name.exe"
    if (-not (Test-Path -LiteralPath $result)) { throw "Missing installer: $result" }
    Get-Item -LiteralPath $result | Select-Object FullName,Length
    Get-FileHash -Algorithm SHA256 -LiteralPath $result | Select-Object Hash
}

Invoke-Compiler $package 'full' "Forge-$buildId-Setup"

if ([string]::IsNullOrWhiteSpace($PreviousPackageDirectory)) { return }
if ([string]::IsNullOrWhiteSpace($PreviousVersion) -or
    [string]::IsNullOrWhiteSpace($PreviousBuildId)) {
    throw 'PreviousVersion and PreviousBuildId are required for a patch.'
}
$previous = (Resolve-Path -LiteralPath $PreviousPackageDirectory).Path
$previousMarker = Join-Path $previous 'forge-community-version.txt'
if (-not (Test-Path -LiteralPath $previousMarker) -or
    (Get-Content -LiteralPath $previousMarker -Raw -Encoding UTF8).Trim() -ne $PreviousVersion) {
    throw 'Previous package is missing its matching version marker.'
}
if ($PreviousVersion -eq $displayVersion) { throw 'Patch requires distinct versions.' }

$staging = Join-Path $output "patch-files-$PreviousBuildId-to-$buildId"
$deleteList = Join-Path $output "patch-delete-$PreviousBuildId-to-$buildId.iss"
if (Test-Path -LiteralPath $staging) { throw "Patch staging already exists: $staging" }
New-Item -ItemType Directory -Path $staging | Out-Null
try {
    $previousFiles = @{}
    foreach ($file in (Get-ChildItem -LiteralPath $previous -File -Recurse)) {
        $relative = $file.FullName.Substring($previous.Length + 1).Replace('\', '/')
        $previousFiles[$relative] = $file
    }
    $currentFiles = @{}
    $changed = 0
    foreach ($file in (Get-ChildItem -LiteralPath $package -File -Recurse)) {
        $relative = $file.FullName.Substring($package.Length + 1).Replace('\', '/')
        $currentFiles[$relative] = $true
        $old = $previousFiles[$relative]
        if ($null -eq $old -or $old.Length -ne $file.Length -or
            (Get-FileHash -Algorithm SHA256 -LiteralPath $old.FullName).Hash -ne
            (Get-FileHash -Algorithm SHA256 -LiteralPath $file.FullName).Hash) {
            $destination = Join-Path $staging $relative
            New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
            Copy-Item -LiteralPath $file.FullName -Destination $destination
            $changed++
        }
    }
    $deletions = foreach ($relative in $previousFiles.Keys) {
        if (-not $currentFiles.ContainsKey($relative)) {
            if ($relative -eq 'forge.profile.properties' -or $relative -notmatch '^(res/|runtime/|forge-gui-desktop-|forge-community-version\.txt$)') {
                throw "Refusing to delete unexpected path: $relative"
            }
            'Type: files; Name: "{app}\' + $relative.Replace('/', '\') + '"'
        }
    }
    [IO.File]::WriteAllLines($deleteList, [string[]]@($deletions), [Text.UTF8Encoding]::new($false))
    if ($changed -eq 0) { throw 'No changed files to package.' }
    $env:FORGE_DELETE_LIST = $deleteList
    $env:FORGE_PREVIOUS_VERSION = $PreviousVersion
    try {
        Invoke-Compiler $staging 'patch' "Forge-$buildId-Patch-from-$PreviousBuildId"
    } finally {
        Remove-Item Env:FORGE_DELETE_LIST -ErrorAction SilentlyContinue
        Remove-Item Env:FORGE_PREVIOUS_VERSION -ErrorAction SilentlyContinue
    }
    Write-Output "Patch files: $changed; removed files: $(@($deletions).Count)"
} finally {
    # Keep staging and delete list for inspection; the output directory is ignored by Git.
}
