# 提交并推送当前改动：.\git-sync.ps1 "feat: xxx"
# 用法：
#   .\git-sync.ps1 "fix: 修正滚轮"          # add + commit + push
#   .\git-sync.ps1 "wip: 试验" -NoPush      # 只提交不推送
param(
    [Parameter(Mandatory = $true)][string]$Message,
    [string]$Remote = 'origin',
    [switch]$NoPush
)

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

git add -A
git diff --cached --quiet
if ($LASTEXITCODE -eq 0) {
    Write-Host '没有需要提交的改动。'
    exit 0
}

git commit -m $Message
if ($LASTEXITCODE -ne 0) { throw 'git commit 失败' }

if (-not $NoPush) {
    git push $Remote HEAD
    if ($LASTEXITCODE -ne 0) { throw 'git push 失败：检查远程与凭据' }
}
