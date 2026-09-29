[CmdletBinding()]
param([string]$RepositoryRoot = (Join-Path $PSScriptRoot '..'))
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$paths = @(& git -C $root -c core.quotepath=false ls-files --cached --others --exclude-standard)
if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect repository files' }
$violations = [Collections.Generic.List[string]]::new()
foreach ($path in $paths | Sort-Object -Unique) {
    $forbiddenPath = $path -match '(?i)(^|[/_.-])(ios|robovm|mobivm)([/_.-]|$)'
    $buildFile = $path -match '(^|/)pom\.xml$' -or $path -match '^\.github/workflows/.*\.ya?ml$'
    if (-not $forbiddenPath -and -not $buildFile -and $path -notmatch '\.java$') { continue }
    $full = Join-Path $root $path
    if (-not (Test-Path -LiteralPath $full -PathType Leaf)) { continue }
    # Match platform tokens, never substrings in card names such as thrasios.
    if ($forbiddenPath) {
        $violations.Add("Forbidden platform path: $path")
    }
    if ($buildFile) {
        $content = [IO.File]::ReadAllText($full)
        if ($content -match '(?i)\b(ios|robovm|mobivm)\b|forge-gui-ios') {
            $violations.Add("Forbidden platform build configuration: $path")
        }
    } elseif ($path -match '\.java$') {
        $content = [IO.File]::ReadAllText($full)
        if ($content -match '\b(?:isIOS|setIsIOS)\s*\(|forge\.ios\.|org\.robovm\.|com\.badlogic\.gdx\.backends\.ios') {
            $violations.Add("Forbidden platform runtime entry: $path")
        }
    }
}
if ($violations.Count) { throw ($violations -join "`n") }
Write-Output 'PASS: community platform scope (Android and desktop).'
