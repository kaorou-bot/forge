$ErrorActionPreference = 'Stop'
$guard = Join-Path $PSScriptRoot 'check-platform-scope.ps1'
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('forge-platform-scope-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixture | Out-Null
& git -C $fixture -c init.defaultBranch=test init --quiet
if ($LASTEXITCODE -ne 0) { throw 'Cannot create isolated guard fixture' }
$cases = @(
    @{Path='forge-gui-ios/pom.xml'; Content='<project/>'; Reject=$true},
    @{Path='.github/workflows/mobile-check.yml'; Content='run: bash forge-gui-ios/pipeline/build.sh'; Reject=$true},
    @{Path='pom.xml'; Content='<profile><id>ios</id></profile>'; Reject=$true},
    @{Path='src/Mobile.java'; Content='if (GuiBase.isIOS()) {}'; Reject=$true},
    @{Path='forge-gui/res/cardsfolder/t/thrasios_triton_hero.txt'; Content='Name:Thrasios, Triton Hero'; Reject=$false},
    @{Path='docs/macOS.md'; Content='Desktop macOS remains supported.'; Reject=$false},
    @{Path='src/Reader.java'; Content='// A general fallback originally helped RoboVM.'; Reject=$false}
)
foreach ($case in $cases) {
    $file = Join-Path $fixture $case.Path
    New-Item -ItemType Directory -Path (Split-Path -Parent $file) -Force | Out-Null
    [IO.File]::WriteAllText($file, $case.Content)
    $rejected = $false
    try { & $guard -RepositoryRoot $fixture | Out-Null } catch { $rejected = $true }
    # Remove only this generated fixture file; keep the isolated test repository for inspection.
    Remove-Item -LiteralPath $file
    if ($rejected -ne $case.Reject) { throw "Unexpected guard result: $($case.Path)" }
}
Write-Output "PASS: $($cases.Count) platform guard regression cases. Fixture: $fixture"
