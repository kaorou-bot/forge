$ErrorActionPreference = 'Stop'
function Assert-ForgeChildPath([string]$Path, [string]$Parent) {
    $full = [IO.Path]::GetFullPath($Path).TrimEnd('\','/')
    $boundary = [IO.Path]::GetFullPath($Parent).TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar
    if (-not $full.StartsWith($boundary, [StringComparison]::OrdinalIgnoreCase)) { throw "Path is outside its permitted directory: $full" }
    return $full
}
function Invoke-ForgeGit([string]$Root, [string[]]$Arguments) {
    $result = @(& git -C $Root @Arguments)
    if ($LASTEXITCODE -ne 0) { throw "Git failed: $($Arguments[0])" }
    return $result
}
function Get-ForgeCommonDirectory([string]$Root) {
    $common = [string](Invoke-ForgeGit $Root @('rev-parse','--git-common-dir'))
    if (-not [IO.Path]::IsPathRooted($common)) { $common = Join-Path $Root $common }
    return [IO.Path]::GetFullPath($common).TrimEnd('\','/')
}
function Assert-ForgeWorkspace([string]$Root, [string]$Primary) {
    $rootPath = Assert-ForgeChildPath $Root (Split-Path -Parent $Primary)
    if ((Get-Item -LiteralPath $rootPath -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Linked workspace root is not permitted.' }
    if ((Get-ForgeCommonDirectory $rootPath) -ine (Get-ForgeCommonDirectory $Primary)) { throw 'Workspace belongs to another Git repository.' }
    return $rootPath
}
if (-not ('ForgeMaintenanceTree' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Collections.Generic;
public sealed class ForgeMaintenanceTree {
    public long Bytes, Files, LatestTicks;
    public static ForgeMaintenanceTree Read(string path) {
        var result = new ForgeMaintenanceTree();
        var pending = new Stack<string>(); pending.Push(path);
        while (pending.Count > 0) {
            var entry = pending.Pop();
            var attr = File.GetAttributes(entry);
            if ((attr & FileAttributes.ReparsePoint) != 0) throw new IOException("Refusing linked path: " + entry);
            result.LatestTicks = Math.Max(result.LatestTicks, File.GetLastWriteTimeUtc(entry).Ticks);
            if ((attr & FileAttributes.Directory) != 0) {
                foreach (var child in Directory.EnumerateFileSystemEntries(entry)) pending.Push(child);
            } else {
                result.Files++; result.Bytes += new FileInfo(entry).Length;
            }
        }
        return result;
    }
}
'@
}
