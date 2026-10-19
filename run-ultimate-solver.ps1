param(
    [Parameter(Mandatory=$true)][string]$JudgeSource,
    [string]$Archive,
    [ValidateSet('all','coverage','development','heldout','ablation','run','validate','summary','diagnostics','unseen','intensive','repeat','polish')]
    [string]$Phase='all'
)
$ErrorActionPreference='Stop'
Push-Location $PSScriptRoot
try {
    & .\build.ps1 -JudgeSource $JudgeSource
    if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
    & g++ -O2 -std=c++17 experiments/promo-benchmark/judgeHashCode2017-wide.cpp -o tmp/judgeHashCode2017-wide.exe
    if ($LASTEXITCODE -ne 0) { throw 'Wide judge compilation failed' }
    & g++ -O2 -std=c++17 experiments/ultimate-solver/judge-exact.cpp -o tmp/judge-exact.exe
    if ($LASTEXITCODE -ne 0) { throw 'Strict 128-bit judge compilation failed' }
    if ($Archive) {
        & python .\inventory-promotion.py --archive $Archive --target tmp/promo-inputs --report tmp/ultimate-inventory
        if ($LASTEXITCODE -ne 0) { throw 'Safe archive extraction failed' }
    }
    $steps = if ($Phase -eq 'all') {
        @('coverage','heldout','ablation','diagnostics','run','validate','intensive','repeat','unseen','run','polish','run','validate','summary')
    } else { @($Phase) }
    foreach ($step in $steps) {
        if ($step -eq 'development') { & python .\ultimate-experiments.py compare --group development }
        elseif ($step -eq 'heldout') { & python .\ultimate-experiments.py compare --group heldout }
        else { & python .\ultimate-experiments.py $step }
        if ($LASTEXITCODE -ne 0) { throw "Study phase failed: $step" }
    }
} finally { Pop-Location }
