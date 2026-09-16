[CmdletBinding()]
param([string]$OutputDirectory = (Join-Path (Join-Path $PSScriptRoot '..\..') 'dist\installer-smoke'))

$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $root) { throw "Test directory already exists: $root" }
$old = Join-Path $root 'old'
$current = Join-Path $root 'current'
$out = Join-Path $root 'output'
$install = Join-Path $root 'installed'
[xml]$project = Get-Content -LiteralPath (Join-Path $PSScriptRoot '..\..\pom.xml') -Raw -Encoding UTF8
$displayVersion = [string]$project.project.properties.displayVersion
foreach ($folder in @($old,$current,$out)) {
    New-Item -ItemType Directory -Force -Path (Join-Path $folder 'runtime\bin') | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $folder 'res') | Out-Null
}
$utf8 = [Text.UTF8Encoding]::new($false)
function Write-TestFile([string]$path,[string]$value) {
    [IO.File]::WriteAllText($path,$value,$utf8)
}
Write-TestFile (Join-Path $old 'forge.exe') 'old executable'
Write-TestFile (Join-Path $old 'runtime\bin\java.exe') 'unchanged runtime'
Write-TestFile (Join-Path $old 'res\old.txt') 'remove me'
Write-TestFile (Join-Path $old 'forge-community-version.txt') "old-test-version`n"
Write-TestFile (Join-Path $current 'forge.exe') 'new executable'
Write-TestFile (Join-Path $current 'runtime\bin\java.exe') 'unchanged runtime'
Write-TestFile (Join-Path $current 'res\new.txt') 'new resource'
Write-TestFile (Join-Path $current 'forge-community-version.txt') "$displayVersion`n"
Write-TestFile (Join-Path $current 'forge.profile.properties') 'MUST NOT INSTALL'

& (Join-Path $PSScriptRoot '..\build-desktop-installer.ps1') `
    -PackageDirectory $current -OutputDirectory $out `
    -PreviousPackageDirectory $old -PreviousVersion 'old-test-version' -PreviousBuildId 'old-test'
if ($LASTEXITCODE -ne 0) { throw 'Installer build failed.' }
$buildId = [string]$project.project.properties.versionCode + [string]$project.project.properties.snapshotName
$full = Join-Path $out "Forge-$buildId-Setup.exe"
$patch = Join-Path $out "Forge-$buildId-Patch-from-old-test.exe"
function Invoke-Setup([string]$file) {
    $arguments = '/VERYSILENT /SUPPRESSMSGBOXES /NORESTART /DIR="' + $install + '"'
    return (Start-Process -FilePath $file -ArgumentList $arguments -Wait -PassThru -WindowStyle Hidden).ExitCode
}
if ((Invoke-Setup $full) -ne 0) { throw 'Full installer failed.' }
if (-not (Test-Path -LiteralPath (Join-Path $install 'res\new.txt'))) { throw 'Full install is incomplete.' }
if (Test-Path -LiteralPath (Join-Path $install 'forge.profile.properties')) { throw 'User profile was overwritten.' }

# A patch must fail without touching an install at the wrong baseline.
if ((Invoke-Setup $patch) -eq 0) { throw 'Patch accepted a mismatched baseline.' }
if (-not (Test-Path -LiteralPath (Join-Path $install 'res\new.txt'))) { throw 'Rejected patch changed the install.' }

Write-TestFile (Join-Path $install 'forge-community-version.txt') "old-test-version`n"
Write-TestFile (Join-Path $install 'res\old.txt') 'remove me'
if ((Invoke-Setup $patch) -ne 0) { throw 'Matching patch failed.' }
if (Test-Path -LiteralPath (Join-Path $install 'res\old.txt')) { throw 'Patch did not remove obsolete file.' }
if ((Get-Content -LiteralPath (Join-Path $install 'forge-community-version.txt') -Raw).Trim() -ne $displayVersion) {
    throw 'Patch did not update the version marker.'
}
Write-Output 'Installer smoke test passed: full install, profile preservation, wrong-baseline rejection, matching delta.'
$uninstaller = Join-Path $install 'unins000.exe'
if (-not (Test-Path -LiteralPath $uninstaller)) { throw 'Uninstaller was not created.' }
$uninstallResult = Start-Process -FilePath $uninstaller -ArgumentList '/VERYSILENT /NORESTART' `
    -Wait -PassThru -WindowStyle Hidden
if ($uninstallResult.ExitCode -ne 0) { throw 'Smoke-test uninstall failed.' }
if (Test-Path -LiteralPath $install) { throw 'Uninstall left managed files behind.' }
