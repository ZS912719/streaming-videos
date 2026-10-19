param(
    [Parameter(Mandatory=$true)][string]$JudgeSource,
    [string]$Archive,
    [ValidateSet('all','inventory','pilot','run','replay')][string]$Phase='all'
)
$ErrorActionPreference='Stop'
Push-Location $PSScriptRoot
try {
    if ($Phase -in @('all','inventory')) {
        $parameters=@()
        if ($Archive) { $parameters=@('--archive',$Archive) }
        & python .\inventory-promotion.py @parameters
        if ($LASTEXITCODE -ne 0) { throw 'Input inventory failed' }
    }
    if ($Phase -eq 'inventory') { return }
    & .\build.ps1 -JudgeSource $JudgeSource
    & g++ -O2 -std=c++17 experiments/promo-benchmark/judgeHashCode2017-wide.cpp -o tmp/judgeHashCode2017-wide.exe
    if ($LASTEXITCODE -ne 0) { throw 'Wide-integer judge compilation failed' }
    if ($Phase -eq 'replay') {
        & python .\replay-promotion-study.py promo
        if ($LASTEXITCODE -ne 0) { throw 'Fresh replay failed' }
        return
    }
    if ($Phase -eq 'pilot') {
        & python .\promo-benchmark.py pilot
        if ($LASTEXITCODE -ne 0) { throw 'Pilot failed' }
        return
    }
    # The selected protocol is versioned. Never refit it on final-evaluation scores.
    if (-not (Test-Path experiments/promo-benchmark/configuration.json)) {
        throw 'Missing frozen protocol. Run the pilot, then python promo-benchmark.py freeze.'
    }
    & python .\promo-benchmark.py run
    if ($LASTEXITCODE -ne 0) { throw 'Benchmark failed; completed checkpoints were retained' }
    & python .\summarize-promotion.py
    if ($LASTEXITCODE -ne 0) { throw 'Benchmark reconciliation failed' }
} finally { Pop-Location }
