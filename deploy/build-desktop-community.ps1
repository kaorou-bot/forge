[CmdletBinding()]
param(
    [string]$JavaHome,
    [string]$Maven,
    [string]$SevenZip,
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

function Write-Step([string]$Message) {
    Write-Host "`n==> $Message" -ForegroundColor Cyan
}

function Resolve-RequiredPath {
    param([string]$Path, [string]$Description, [switch]$Container)
    if ([string]::IsNullOrWhiteSpace($Path)) { throw "$Description is not configured." }
    $pathType = if ($Container) { 'Container' } else { 'Leaf' }
    if (-not (Test-Path -LiteralPath $Path -PathType $pathType)) {
        throw "$Description does not exist: $Path"
    }
    return (Resolve-Path -LiteralPath $Path).Path
}

function Invoke-Checked {
    param([string]$FilePath, [string[]]$Arguments, [string]$Description)
    Write-Host "[$Description] $FilePath"
    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Description failed with exit code $LASTEXITCODE" }
}

function New-ShortDriveMapping([string]$RepositoryRoot) {
    foreach ($drive in @('W:', 'V:', 'U:', 'T:')) {
        $existing = (& subst.exe $drive 2>$null | Out-String).Trim()
        if ($LASTEXITCODE -eq 0 -and $existing) {
            if ($existing -match '=>\s*(.+)$') {
                $mappedPath = $Matches[1].Trim().TrimEnd('\')
                if ($mappedPath.Equals($RepositoryRoot.TrimEnd('\'),
                        [System.StringComparison]::OrdinalIgnoreCase)) {
                    return [pscustomobject]@{ Drive = $drive; Created = $false }
                }
            }
            continue
        }
        & subst.exe $drive $RepositoryRoot
        if ($LASTEXITCODE -eq 0) {
            return [pscustomobject]@{ Drive = $drive; Created = $true }
        }
    }
    throw 'Cannot create a temporary drive mapping. Free W:, V:, U:, or T: and retry.'
}

function Assert-ChildPath([string]$Path, [string]$Parent) {
    $fullPath = [IO.Path]::GetFullPath($Path)
    $fullParent = [IO.Path]::GetFullPath($Parent).TrimEnd('\') + '\'
    if (-not $fullPath.StartsWith($fullParent, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to modify a path outside the output directory: $fullPath"
    }
}

$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $scriptDirectory '..')).Path
$desktopDirectory = Join-Path $repositoryRoot 'forge-gui-desktop'
$desktopTarget = Join-Path $desktopDirectory 'target'
[xml]$rootProject = Get-Content -LiteralPath (Join-Path $repositoryRoot 'pom.xml') -Raw -Encoding UTF8
$versionCode = [string]$rootProject.project.properties.versionCode
$snapshotName = [string]$rootProject.project.properties.snapshotName
$revision = "$versionCode$snapshotName"
$displayVersion = [string]$rootProject.project.properties.displayVersion

if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    $JavaHome = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\jdk-21.0.10' }
}
if ([string]::IsNullOrWhiteSpace($Maven)) {
    $Maven = Join-Path $repositoryRoot '.tools\apache-maven-3.9.12\bin\mvn.cmd'
}
if ([string]::IsNullOrWhiteSpace($SevenZip)) {
    $SevenZip = 'C:\Program Files\7-Zip\7z.exe'
}
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $repositoryRoot 'dist\desktop'
}

$JavaHome = Resolve-RequiredPath $JavaHome 'JDK directory used to build the JRE' -Container
$Maven = Resolve-RequiredPath $Maven 'Maven launcher'
$SevenZip = Resolve-RequiredPath $SevenZip '7-Zip executable'
$jlink = Resolve-RequiredPath (Join-Path $JavaHome 'bin\jlink.exe') 'jlink executable'
$java = Resolve-RequiredPath (Join-Path $JavaHome 'bin\java.exe') 'Java executable'

$env:JAVA_HOME = $JavaHome
$env:MAVEN_OPTS = '-Xmx4g -Dfile.encoding=UTF-8'
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$OutputDirectory = (Resolve-Path -LiteralPath $OutputDirectory).Path

$packageName = "Forge-$revision-Windows"
$packageDirectory = Join-Path $OutputDirectory $packageName
$packageZip = Join-Path $OutputDirectory "$packageName.zip"
Assert-ChildPath $packageDirectory $OutputDirectory
Assert-ChildPath $packageZip $OutputDirectory

Write-Host 'Forge Windows community build'
Write-Host "Revision: $revision (display: $displayVersion)"
Write-Host 'Runtime: custom Java 21 JRE image (no JDK development tools)'
Write-Host "Output: $packageZip"

$mapping = $null
try {
    Write-Step 'Compile the Windows desktop client from an ASCII-only path'
    $mapping = New-ShortDriveMapping $repositoryRoot
    Push-Location "$($mapping.Drive)\"
    try {
        Invoke-Checked $Maven @('-DskipTests', '-Dcheckstyle.skip=true',
            '-pl', 'forge-gui-desktop', '-am', 'package') 'Maven desktop package'
    } finally {
        Pop-Location
    }

    $jarPath = Join-Path $desktopTarget "forge-gui-desktop-$revision-jar-with-dependencies.jar"
    $jar = Resolve-RequiredPath $jarPath 'Desktop all-in-one JAR'
    $exe = Resolve-RequiredPath (Join-Path $desktopTarget 'forge.exe') 'Launch4j executable'

    Write-Step 'Assemble the Windows package'
    if (Test-Path -LiteralPath $packageDirectory) {
        Remove-Item -LiteralPath $packageDirectory -Recurse -Force
    }
    if (Test-Path -LiteralPath $packageZip) {
        Remove-Item -LiteralPath $packageZip -Force
    }
    New-Item -ItemType Directory -Path $packageDirectory | Out-Null
    Copy-Item -LiteralPath $exe -Destination (Join-Path $packageDirectory 'forge.exe')
    Copy-Item -LiteralPath $jar -Destination (Join-Path $packageDirectory ([IO.Path]::GetFileName($jar)))
    Copy-Item -LiteralPath (Join-Path $repositoryRoot 'forge-gui\res') `
        -Destination (Join-Path $packageDirectory 'res') -Recurse
    Copy-Item -LiteralPath (Join-Path $repositoryRoot 'forge-gui\forge.profile.properties.example') `
        -Destination $packageDirectory
    Copy-Item -LiteralPath (Join-Path $repositoryRoot 'LICENSE') `
        -Destination (Join-Path $packageDirectory 'LICENSE.txt')
    Copy-Item -LiteralPath (Join-Path $repositoryRoot 'deploy\aliyun\Windows使用说明.txt') `
        -Destination $packageDirectory
    $releaseNotes = Join-Path $repositoryRoot `
        'forge-gui\src\main\resources\forge-community-release-notes-zh-CN.txt'
    Copy-Item -LiteralPath $releaseNotes -Destination (Join-Path $packageDirectory '更新说明.txt')

    Write-Step 'Create a dedicated Java 21 JRE with jlink'
    $runtimeDirectory = Join-Path $packageDirectory 'runtime'
    $runtimeModules = @(
        'java.se',
        'jdk.accessibility',
        'jdk.charsets',
        'jdk.crypto.cryptoki',
        'jdk.crypto.ec',
        'jdk.crypto.mscapi',
        'jdk.localedata',
        'jdk.unsupported',
        'jdk.zipfs'
    ) -join ','
    Invoke-Checked $jlink @('--add-modules', $runtimeModules, '--strip-debug',
        '--compress=2', '--no-header-files', '--no-man-pages',
        '--output', $runtimeDirectory) 'jlink JRE image'

    $runtimeJava = Resolve-RequiredPath (Join-Path $runtimeDirectory 'bin\java.exe') 'Bundled JRE java.exe'
    Resolve-RequiredPath (Join-Path $runtimeDirectory 'bin\javaw.exe') 'Bundled JRE javaw.exe' | Out-Null
    if (Test-Path -LiteralPath (Join-Path $runtimeDirectory 'bin\javac.exe')) {
        throw 'The generated runtime unexpectedly contains javac.exe; it is not a JRE-only image.'
    }
    if (Test-Path -LiteralPath (Join-Path $runtimeDirectory 'jmods')) {
        throw 'The generated runtime unexpectedly contains a jmods directory.'
    }
    Invoke-Checked $runtimeJava @('-version') 'Bundled JRE verification'

    Write-Step 'Verify Launch4j points to the packaged JAR'
    $rg = (Get-Command rg -ErrorAction SilentlyContinue).Source
    if ($rg) {
        & $rg -a -F -- ([IO.Path]::GetFileName($jar)) $exe | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "forge.exe does not reference the packaged JAR: $([IO.Path]::GetFileName($jar))"
        }
    }

    Write-Step 'Create the distributable ZIP'
    Push-Location $OutputDirectory
    try {
        Invoke-Checked $SevenZip @('a', '-tzip', '-mx=6', $packageZip, $packageName) '7-Zip package'
    } finally {
        Pop-Location
    }

    $zip = Get-Item -LiteralPath $packageZip
    $runtimeSize = (Get-ChildItem -LiteralPath $runtimeDirectory -Recurse -File |
        Measure-Object -Property Length -Sum).Sum
    Write-Host "`nBuild succeeded" -ForegroundColor Green
    Write-Host "Package: $($zip.FullName)"
    Write-Host ('Package size: {0:N1} MiB' -f ($zip.Length / 1MB))
    Write-Host ('Bundled JRE size: {0:N1} MiB' -f ($runtimeSize / 1MB))
    Write-Host "SHA-256: $((Get-FileHash -LiteralPath $zip.FullName -Algorithm SHA256).Hash.ToLowerInvariant())"
} finally {
    if ($mapping -and $mapping.Created) {
        & subst.exe $mapping.Drive /D | Out-Null
    }
}
