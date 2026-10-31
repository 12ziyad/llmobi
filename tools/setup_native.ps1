$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$nativeRoot = Join-Path $projectRoot 'native\llama.cpp'
$revision = (Get-Content -LiteralPath (Join-Path $projectRoot 'native\llama.cpp.ref') -Raw).Trim()
if ($revision -notmatch '^[a-f0-9]{40}$') { throw 'Invalid pinned llama.cpp revision' }
if (-not (Test-Path -LiteralPath $nativeRoot)) {
    git clone --filter=blob:none --no-checkout https://github.com/ggml-org/llama.cpp.git $nativeRoot
    if ($LASTEXITCODE -ne 0) { throw 'Could not download llama.cpp' }
} else {
    # A fresh --no-checkout clone intentionally has an empty worktree. Only an
    # existing checkout can contain user edits that must be protected.
    $changes = git -C $nativeRoot status --porcelain
    if ($LASTEXITCODE -ne 0) { throw 'The native directory is not a valid Git checkout' }
    if ($changes) { throw 'llama.cpp has local changes; preserve them before changing its revision.' }
}
git -C $nativeRoot fetch origin $revision
if ($LASTEXITCODE -ne 0) { throw 'Could not fetch the pinned revision' }
git -C $nativeRoot checkout --detach $revision
if ($LASTEXITCODE -ne 0) { throw 'Could not select the pinned revision' }
