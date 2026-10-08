[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$JavaHome,
    [Parameter(Mandatory)][string]$GdxJar
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$testRoot = Join-Path $root ('forge-gui-android/target/resource-test-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot -Force | Out-Null
$apk = Join-Path $testRoot 'test.apk'
$missing = Join-Path $testRoot 'missing.jar'
foreach ($path in @($apk, $missing)) { [IO.Compression.ZipFile]::Open($path, [IO.Compression.ZipArchiveMode]::Create).Dispose() }
& (Join-Path $PSScriptRoot 'package-android-classpath-resources.ps1') -JarTool (Join-Path $JavaHome 'bin/jar.exe') -Apk $apk -ClasspathEntries @($GdxJar) -StagingDirectory (Join-Path $testRoot 'shaders')
$source = [IO.Compression.ZipFile]::OpenRead($GdxJar)
$result = [IO.Compression.ZipFile]::OpenRead($apk)
try {
    $count = 0
    foreach ($entry in $source.Entries) {
        if (-not $entry.FullName.EndsWith('.glsl')) { continue }
        $copy = $result.GetEntry($entry.FullName)
        if ($null -eq $copy) { throw "APK shader missing: $($entry.FullName)" }
        $expectedStream = $entry.Open()
        $actualStream = $copy.Open()
        $sha = [Security.Cryptography.SHA256]::Create()
        try {
            $expected = [Convert]::ToHexString($sha.ComputeHash($expectedStream))
            $actual = [Convert]::ToHexString($sha.ComputeHash($actualStream))
            if ($expected -ne $actual) { throw "Shader changed: $($entry.FullName)" }
        } finally { $sha.Dispose(); $expectedStream.Dispose(); $actualStream.Dispose() }
        $count++
    }
    if ($count -lt 6) { throw "Only $count shader files validated." }
} finally { $source.Dispose(); $result.Dispose() }
$rejected = $false
try {
    & (Join-Path $PSScriptRoot 'package-android-classpath-resources.ps1') -JarTool (Join-Path $JavaHome 'bin/jar.exe') -Apk $apk -ClasspathEntries @($missing) -StagingDirectory (Join-Path $testRoot 'missing-shaders')
} catch {
    if ($_.Exception.Message -notlike 'Missing libGDX shader*') { throw }
    $rejected = $true
}
if (-not $rejected) { throw 'A dependency without shaders was incorrectly accepted.' }
Write-Host "PASS: $count shaders match runtime dependency bytes; missing resources reject the build. Test output: $testRoot"
