[CmdletBinding()]
param([Parameter(Mandatory)][string]$PackageDirectory, [string]$Java = 'java')
$ErrorActionPreference = 'Stop'
$package = (Resolve-Path -LiteralPath $PackageDirectory).Path
foreach ($line in Get-Content -LiteralPath (Join-Path $package 'SHA256SUMS')) {
    if ($line -notmatch '^([0-9a-f]{64})  (.+)$') { throw 'Invalid checksum manifest' }
    $hash = $matches[1]
    $file = [IO.Path]::GetFullPath((Join-Path $package $matches[2]))
    if (-not $file.StartsWith($package + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Checksum path outside package'
    }
    if ((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $hash) { throw "Checksum mismatch: $file" }
}
function Free-Port {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try { return $listener.LocalEndpoint.Port } finally { $listener.Stop() }
}
$port = Free-Port
do { $healthPort = Free-Port } while ($healthPort -eq $port)
$logs = Join-Path ([IO.Path]::GetTempPath()) ('forge-relay-smoke-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $logs | Out-Null
$jar = Join-Path $package 'forge-lobby-relay.jar'
$arguments = "-Xmx256m -Dforge.relay.bind=127.0.0.1 -Dforge.relay.port=$port -Dforge.relay.health.bind=127.0.0.1 -Dforge.relay.health.port=$healthPort -jar `"$jar`""
$process = Start-Process -FilePath $Java -ArgumentList $arguments -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $logs 'stdout.log') -RedirectStandardError (Join-Path $logs 'stderr.log')
try {
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    $ok = $false
    while ([DateTime]::UtcNow -lt $deadline -and -not $process.HasExited) {
        try {
            $health = Invoke-RestMethod "http://127.0.0.1:$healthPort/health" -TimeoutSec 2
            if ($health.status -eq 'ok' -and $health.rooms -eq 0) { $ok = $true; break }
        } catch { Start-Sleep -Milliseconds 200 }
    }
    if (-not $ok) { throw "Packaged relay failed health check. See $logs" }
    Write-Output "PASS: package hashes and standalone JAR health. Ports $port/$healthPort; logs $logs"
} finally {
    if (-not $process.HasExited) { Stop-Process -Id $process.Id }
    $process.Dispose()
}
