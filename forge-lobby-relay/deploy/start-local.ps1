[CmdletBinding()]
param([ValidateRange(1,65535)][int]$Port = 36744,
      [ValidateRange(1,65535)][int]$HealthPort = 36745)
$ErrorActionPreference = 'Stop'
$jar = Join-Path $PSScriptRoot 'forge-lobby-relay.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw "Missing $jar" }
& java '-Xms128m' '-Xmx512m' '-Dforge.relay.bind=127.0.0.1' "-Dforge.relay.port=$Port" `
    '-Dforge.relay.health.bind=127.0.0.1' "-Dforge.relay.health.port=$HealthPort" '-jar' $jar
exit $LASTEXITCODE
