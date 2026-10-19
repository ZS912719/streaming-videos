param(
    [Parameter(Mandatory=$true)][string]$JudgeSource,
    [string]$Archive,
    [string]$Inputs='tmp/promo-inputs',
    [ValidateSet('all','fresh','incumbent','validate','summary')][string]$Phase='all'
)
$ErrorActionPreference='Stop'
Push-Location $PSScriptRoot
try {
    & .\build.ps1 -JudgeSource $JudgeSource
    & g++ -O2 -std=c++17 experiments/promo-benchmark/judgeHashCode2017-wide.cpp -o tmp/judgeHashCode2017-wide.exe
    if ($LASTEXITCODE -ne 0) { throw 'Wide judge build failed' }
    & g++ -O2 -std=c++17 experiments/ultimate-solver/judge-exact.cpp -o tmp/judge-exact.exe
    if ($LASTEXITCODE -ne 0) { throw 'Exact judge build failed' }
    if ($Archive) {
        & python .\inventory-promotion.py --archive $Archive --target $Inputs --report tmp/ultimate-v2-inventory
        if ($LASTEXITCODE -ne 0) { throw 'Safe extraction failed' }
    }
    $steps=if($Phase -eq 'all'){@('fresh','incumbent','validate','summary')}else{@($Phase)}
    foreach($step in $steps) {
        if($step -in @('fresh','incumbent')) {
            & python .\ultimate-v2-experiments.py final --inputs $Inputs --evaluation $step --judge-source $JudgeSource
        } else {
            & python .\ultimate-v2-experiments.py $step --inputs $Inputs --judge-source $JudgeSource
        }
        if($LASTEXITCODE -ne 0) { throw "V2 phase failed: $step" }
    }
} finally { Pop-Location }
