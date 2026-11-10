[CmdletBinding()]
param(
    [ValidateSet('Prepare','Inspect','Finish')][string]$Mode = 'Prepare',
    [string]$RepositoryRoot = (Join-Path $PSScriptRoot '..'),
    [string]$RunId,
    [ValidateSet('success','unchanged','failed','prepared')][string]$Result = 'success',
    [switch]$SkipFetch,
    # Explicit, user-authorized recovery of a failed run; never reset unpublished work.
    [string]$ResumeCommit,
    # Resume an inspected failed merge without changing its index or files.
    [string]$ResumeMergeHead,
    [string]$FailedRunId
)
. (Join-Path $PSScriptRoot 'workspace-maintenance-common.ps1')
$primary = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$policy = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'workspace-retention.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$leaf = Split-Path -Leaf $primary
$workspace = Join-Path (Split-Path -Parent $primary) ($leaf + $policy.syncWorktreeSuffix)
$common = Get-ForgeCommonDirectory $primary
$statePath = Join-Path $common 'forge-upstream-sync-session.json'
$state = if (Test-Path -LiteralPath $statePath) { Get-Content -LiteralPath $statePath -Raw -Encoding UTF8 | ConvertFrom-Json } else { $null }
if ($Mode -eq 'Inspect') {
    $status = if (Test-Path -LiteralPath $workspace) { @(Invoke-ForgeGit $workspace @('status','--porcelain=v1')) } else { @() }
    [pscustomobject]@{Workspace=$workspace;Exists=(Test-Path -LiteralPath $workspace);Clean=($status.Count -eq 0);ActiveRun=($null -ne $state -and $state.status -eq 'running');ReleaseDirectory=(Join-Path $primary $policy.releaseDirectory)} | ConvertTo-Json
    return
}
$lock = [IO.File]::Open((Join-Path $common 'forge-workspace-maintenance.lock'), [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
try {
    # Read again under the lock; another invocation may have changed the lease.
    $state = if (Test-Path -LiteralPath $statePath) { Get-Content -LiteralPath $statePath -Raw -Encoding UTF8 | ConvertFrom-Json } else { $null }
    if ($Mode -eq 'Finish') {
        if (-not $state -or $state.status -ne 'running' -or -not $RunId -or $state.runId -cne $RunId) { throw 'Run ID does not match the active synchronization.' }
        $state.status = $Result
        $state | Add-Member -NotePropertyName completedAt -NotePropertyValue ([DateTimeOffset]::Now.ToString('o')) -Force
        $state | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding UTF8
        "Finished synchronization $RunId ($Result)"
        return
    }
    if ($state -and $state.status -eq 'running') { throw "Another synchronization is unfinished: $($state.runId). Inspect its task before clearing the lease." }
    if (-not $SkipFetch) { Invoke-ForgeGit $primary @('fetch','origin','zh-cn-community-release','zh-cn-card-translations') | Out-Null }
    $published = [string](Invoke-ForgeGit $primary @('rev-parse','origin/zh-cn-community-release'))
    if (-not (Test-Path -LiteralPath $workspace)) {
        Invoke-ForgeGit $primary @('worktree','add','-b','codex/weekly-upstream-sync',$workspace,$published) | Out-Null
    }
    $workspace = Assert-ForgeWorkspace $workspace $primary
    $resumeMerge = -not [string]::IsNullOrWhiteSpace($ResumeMergeHead)
    if ($FailedRunId -and -not $resumeMerge) { throw 'FailedRunId requires ResumeMergeHead.' }
    if ($resumeMerge) {
        if (-not $state -or $state.status -ne 'failed' -or $state.workspace -ine $workspace -or
            -not $FailedRunId -or $state.runId -cne $FailedRunId) { throw 'Merge recovery requires the exact failed run ID for this fixed workspace.' }
        $head = [string](Invoke-ForgeGit $workspace @('rev-parse','HEAD'))
        if ($ResumeCommit -notmatch '^[0-9a-f]{40}$' -or $head -cne $ResumeCommit -or $state.startingCommit -cne $head) { throw 'Merge recovery requires the inspected, unchanged starting HEAD.' }
        $mergeMarker = [string](Invoke-ForgeGit $workspace @('rev-parse','--git-path','MERGE_HEAD'))
        if (-not [IO.Path]::IsPathRooted($mergeMarker)) { $mergeMarker = Join-Path $workspace $mergeMarker }
        if (-not (Test-Path -LiteralPath $mergeMarker -PathType Leaf)) { throw 'Merge recovery requires an existing MERGE_HEAD file.' }
        $mergeHeads = @(Get-Content -LiteralPath $mergeMarker -Encoding ASCII)
        if ($ResumeMergeHead -notmatch '^[0-9a-f]{40}$' -or $mergeHeads.Count -ne 1 -or $mergeHeads[0] -cne $ResumeMergeHead) { throw 'Merge recovery requires the exact inspected MERGE_HEAD.' }
        & git -C $workspace merge-base --is-ancestor $ResumeMergeHead upstream/master
        if ($LASTEXITCODE -ne 0) { throw 'Preserved merge target is not in the fetched upstream master.' }
    }
    if (-not $resumeMerge -and @(Invoke-ForgeGit $workspace @('status','--porcelain=v1')).Count) { throw 'Fixed sync workspace has local changes. Preserve them and stop; do not create another dated workspace.' }
    foreach ($name in @('MERGE_HEAD','CHERRY_PICK_HEAD','REBASE_HEAD','rebase-merge','rebase-apply')) {
        $marker = [string](Invoke-ForgeGit $workspace @('rev-parse','--git-path',$name))
        if (-not [IO.Path]::IsPathRooted($marker)) { $marker = Join-Path $workspace $marker }
        if ((Test-Path -LiteralPath $marker) -and -not ($resumeMerge -and $name -eq 'MERGE_HEAD')) { throw 'Fixed sync workspace has an unfinished Git operation.' }
    }
    $startingCommit = $published
    if ($ResumeCommit) {
        $head = [string](Invoke-ForgeGit $workspace @('rev-parse','HEAD'))
        if ($ResumeCommit -notmatch '^[0-9a-f]{40}$' -or $head -cne $ResumeCommit) { throw 'Recovery requires the exact inspected HEAD commit.' }
        if (-not $state -or $state.status -ne 'failed' -or $state.workspace -ine $workspace) { throw 'Recovery requires a failed lease for this fixed workspace.' }
        & git -C $workspace merge-base --is-ancestor $published HEAD
        if ($LASTEXITCODE -ne 0) { throw 'Recovery candidate does not contain the published branch; inspect divergence first.' }
        $startingCommit = $head
    } else {
        & git -C $workspace merge-base --is-ancestor HEAD $published
        if ($LASTEXITCODE -ne 0) { throw 'Fixed sync workspace contains unpublished or divergent commits; preserve and inspect them.' }
        Invoke-ForgeGit $workspace @('merge','--ff-only',$published) | Out-Null
    }
    $previousState = $state
    $state = [ordered]@{runId=[Guid]::NewGuid().ToString('N');status='running';startedAt=[DateTimeOffset]::Now.ToString('o');workspace=$workspace;startingCommit=$startingCommit}
    if ($resumeMerge) {
        $state.resumedFromRunId = $previousState.runId
        $state.mergeHead = $ResumeMergeHead
        $state.preservedStatus = @(Invoke-ForgeGit $workspace @('status','--porcelain=v1'))
        $state.preservedUnmerged = @(Invoke-ForgeGit $workspace @('ls-files','--unmerged'))
        $history = Join-Path $common 'forge-upstream-sync-history'
        [IO.Directory]::CreateDirectory($history) | Out-Null
        $previousState | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $history ($previousState.runId + '.json')) -Encoding UTF8
    }
    $state | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $statePath -Encoding UTF8
    [pscustomobject]@{runId=$state.runId;status=$state.status;workspace=$workspace;startingCommit=$startingCommit;resumedFromRunId=$state.resumedFromRunId;mergeHead=$state.mergeHead} | ConvertTo-Json
} finally { $lock.Dispose() }
