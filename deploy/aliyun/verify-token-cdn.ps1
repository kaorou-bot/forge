[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string]$ManifestPath,
    [Parameter(Mandatory)] [string]$BaseUrl,
    [Parameter(Mandatory)] [string]$ReportPath
)
$ErrorActionPreference = 'Stop'
$manifest = Get-Content -LiteralPath $ManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($manifest.asset_type -ne 'forge_tokens' -or $manifest.file_count -ne $manifest.files.Count) {
    throw 'Invalid tokens manifest'
}
$base = $BaseUrl.TrimEnd('/') + '/'
if (-not $base.StartsWith('https://')) { throw 'HTTPS required' }
$client = [Net.Http.HttpClient]::new()
$client.Timeout = [TimeSpan]::FromSeconds(30)
try {
    $results = @($manifest.files | ForEach-Object -Parallel {
        $row = $_
        $http = $using:client
        $url = $using:base + (($row.path.Split('/') | ForEach-Object { [Uri]::EscapeDataString($_) }) -join '/')
        $success = $false
        $errorText = ''
        for ($attempt=0; $attempt -lt 2 -and -not $success; $attempt++) {
            $response = $null
            try {
                $response = $http.GetAsync($url).GetAwaiter().GetResult()
                $response.EnsureSuccessStatusCode() | Out-Null
                $bytes = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
                $hash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
                if ($bytes.Length -ne $row.size -or $hash -ne $row.sha256) { throw 'Size or SHA-256 mismatch' }
                if ($bytes.Length -lt 2 -or $bytes[0] -ne 255 -or $bytes[1] -ne 216) { throw 'Not a JPEG' }
                $success = $true
            } catch { $errorText = $_.Exception.Message }
            finally { if ($response) { $response.Dispose() } }
        }
        [pscustomobject]@{ path=$row.path; verified=$success; error=$(if ($success) { '' } else { $errorText }) }
    } -ThrottleLimit 8)
} finally { $client.Dispose() }
$results | Sort-Object path | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $ReportPath -Encoding utf8NoBOM
$failed = @($results | Where-Object { -not $_.verified })
Write-Output "CDN verified: $($results.Count - $failed.Count)/$($manifest.file_count)"
if ($failed.Count -gt 0 -or $results.Count -ne $manifest.file_count) {
    $failed | Select-Object -First 10 | Format-Table -AutoSize
    throw 'Token CDN verification failed; do not publish client routing.'
}
