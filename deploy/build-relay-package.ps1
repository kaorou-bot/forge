[CmdletBinding()]
param([string]$Maven = 'mvn', [string]$OutputDirectory = 'dist/relay', [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
[xml]$pom = Get-Content -LiteralPath (Join-Path $root 'pom.xml') -Raw -Encoding UTF8
$version = [string]$pom.project.properties.versionCode + [string]$pom.project.properties.snapshotName
$name = "Forge-Lobby-Relay-$version"
$output = [IO.Path]::GetFullPath((Join-Path $root $OutputDirectory))
$package = Join-Path $output $name
if (Test-Path -LiteralPath $package) { throw "Refusing to overwrite existing package: $package" }
Push-Location $root
try {
    if (-not $SkipBuild) {
        & $Maven '-pl' 'forge-lobby-relay' '-am' '-Dcheckstyle.skip=true' 'package'
        if ($LASTEXITCODE -ne 0) { throw 'Relay build/tests failed' }
    }
    $jar = Join-Path $root "forge-lobby-relay/target/forge-lobby-relay-$version-jar-with-dependencies.jar"
    if (-not (Test-Path -LiteralPath $jar)) { throw "Missing $jar" }
    New-Item -ItemType Directory -Path $package -Force | Out-Null
    Copy-Item -LiteralPath $jar -Destination (Join-Path $package 'forge-lobby-relay.jar')
    Copy-Item -LiteralPath (Join-Path $root 'LICENSE') -Destination $package
    foreach ($file in @('README.zh-CN.md','forge-lobby-relay.service','nginx-self-hosted.conf.example','start-local.ps1','start-local.sh')) {
        $content = [IO.File]::ReadAllText((Join-Path $root "forge-lobby-relay/deploy/$file"))
        [IO.File]::WriteAllText((Join-Path $package $file), $content.Replace("`r`n","`n"), [Text.UTF8Encoding]::new($false))
    }
    $source = Join-Path $package 'source'
    New-Item -ItemType Directory -Path $source | Out-Null
    # Include exact standalone module sources; never copy target/, local credentials or runtime data.
    foreach ($module in @('forge-relay-protocol','forge-lobby-relay')) {
        $destination = Join-Path $source $module
        New-Item -ItemType Directory -Path $destination | Out-Null
        Copy-Item -LiteralPath (Join-Path $root "$module/pom.xml") -Destination $destination
        Copy-Item -LiteralPath (Join-Path $root "$module/src") -Destination $destination -Recurse
    }
    foreach ($module in @($pom.project.modules.module)) {
        if ([string]$module -notin @('forge-relay-protocol','forge-lobby-relay')) {
            $node = @($pom.project.modules.ChildNodes) | Where-Object { $_.InnerText -eq [string]$module }
            foreach ($entry in $node) { $null = $pom.project.modules.RemoveChild($entry) }
        }
    }
    $pom.Save((Join-Path $source 'pom.xml'))
    Copy-Item -LiteralPath (Join-Path $root 'LICENSE') -Destination $source
    $commit = (& git rev-parse HEAD).Trim()
    [IO.File]::WriteAllText((Join-Path $package 'VERSION.txt'), "Build=$version`nSourceBaseline=$commit`nIncludesExactModuleSources=true`n", [Text.UTF8Encoding]::new($false))
    $sums = foreach ($file in Get-ChildItem -LiteralPath $package -Recurse -File | Sort-Object FullName) {
        $relative = $file.FullName.Substring($package.Length + 1).Replace('\','/')
        (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant() + "  $relative"
    }
    [IO.File]::WriteAllLines((Join-Path $package 'SHA256SUMS'), [string[]]$sums, [Text.UTF8Encoding]::new($false))
    $zip = Join-Path $output "$name.zip"
    if (Test-Path -LiteralPath $zip) { throw "Refusing to overwrite $zip" }
    Compress-Archive -LiteralPath $package -DestinationPath $zip
    Get-Item -LiteralPath $zip | Select-Object FullName,Length
    Get-FileHash -LiteralPath $zip -Algorithm SHA256
} finally { Pop-Location }
