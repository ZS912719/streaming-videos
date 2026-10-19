param(
    [Parameter(Mandatory=$true)][string]$JudgeSource,
    [string]$Archive,
    [string]$Inputs='tmp/promo-inputs',
    [ValidateSet('all','fresh','incumbent','validate','summary')][string]$Phase='validate'
)
$ErrorActionPreference='Stop'
Push-Location $PSScriptRoot
try {
    # A separate export preserves the study's whole-source fingerprint and ledgers.
    $revision='d30ef6e87cd900aaf904baa2b5c640903205392e'
    $judge=(Resolve-Path -LiteralPath $JudgeSource).Path
    $inputPath=if ([IO.Path]::IsPathRooted($Inputs)) { $Inputs } else {
        [IO.Path]::GetFullPath((Join-Path $PSScriptRoot $Inputs))
    }
    $options=@{JudgeSource=$judge;Inputs=$inputPath;Phase=$Phase}
    if ($Archive) { $options.Archive=(Resolve-Path -LiteralPath $Archive).Path }
    $replay=Join-Path $PSScriptRoot 'tmp/frozen-v2-d30ef6e-lf'
    if (-not (Test-Path -LiteralPath $replay)) {
        New-Item -ItemType Directory -Force -Path $replay | Out-Null
        $zip=Join-Path $PSScriptRoot 'tmp/frozen-v2-source.zip'
        & git archive --worktree-attributes --format=zip --output=$zip $revision
        if ($LASTEXITCODE -ne 0) { throw 'Frozen revision unavailable; fetch full repository history.' }
        Expand-Archive -LiteralPath $zip -DestinationPath $replay
    }
    & (Join-Path $replay 'run-ultimate-v2.ps1') @options
    if ($LASTEXITCODE -ne 0) { throw 'Frozen study replay failed' }
    Write-Host "Replay results are isolated in $replay"
} finally { Pop-Location }
