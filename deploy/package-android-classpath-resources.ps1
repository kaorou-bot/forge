[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$JarTool,
    [Parameter(Mandatory)][string]$Apk,
    [Parameter(Mandatory)][string[]]$ClasspathEntries,
    [Parameter(Mandatory)][string]$StagingDirectory
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$shaderRoot = [IO.Path]::GetFullPath($StagingDirectory)
New-Item -ItemType Directory -Path $shaderRoot -Force | Out-Null
$required = @(
    'com/badlogic/gdx/graphics/g3d/shaders/default.vertex.glsl',
    'com/badlogic/gdx/graphics/g3d/shaders/default.fragment.glsl',
    'com/badlogic/gdx/graphics/g3d/shaders/depth.vertex.glsl',
    'com/badlogic/gdx/graphics/g3d/shaders/depth.fragment.glsl',
    'com/badlogic/gdx/graphics/g3d/particles/particles.vertex.glsl',
    'com/badlogic/gdx/graphics/g3d/particles/particles.fragment.glsl'
)
$found = [Collections.Generic.HashSet[string]]::new()
foreach ($dependency in $ClasspathEntries) {
    $archive = [IO.Compression.ZipFile]::OpenRead($dependency)
    try {
        foreach ($entry in $archive.Entries) {
            if (-not $entry.FullName.StartsWith('com/badlogic/gdx/') -or -not $entry.FullName.EndsWith('.glsl')) { continue }
            $target = [IO.Path]::GetFullPath((Join-Path $shaderRoot $entry.FullName))
            if (-not $target.StartsWith($shaderRoot.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) { throw "Invalid shader resource path: $($entry.FullName)" }
            if ($entry.Length -eq 0) { throw "Empty shader resource: $($entry.FullName)" }
            if (-not $found.Add($entry.FullName)) { continue }
            New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
        }
    } finally {
        $archive.Dispose()
    }
}
foreach ($name in $required) {
    if (-not $found.Contains($name)) { throw "Missing libGDX shader in runtime dependencies: $name" }
}
# D8 emits code only. AndroidFileHandle.classpath reads these resources at the APK root.
& $JarTool uf $Apk -C $shaderRoot com
if ($LASTEXITCODE -ne 0) { throw 'Adding libGDX shader resources to APK failed.' }
Write-Host "Packaged $($found.Count) libGDX shader resources."
