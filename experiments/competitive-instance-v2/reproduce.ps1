param([string]$JudgeSource,[switch]$Fresh)
$ErrorActionPreference='Stop'
$taskRoot=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Push-Location $taskRoot
try {
    if (-not (Test-Path 'out/com/hashcode/streaming/experiments/PromotionPipelineRunner.class') -or
        -not (Test-Path 'tmp/judgeHashCode2017.exe')) {
        if (-not $JudgeSource) { throw 'Supply -JudgeSource to build the missing solver/judge.' }
        & .\build.ps1 -JudgeSource $JudgeSource
    }
    $steps=if($Fresh){@('replay')}else{@('diagnose','screen','full','counter','robustness','finish')}
    foreach ($step in $steps) {
        & python experiments/competitive-instance-v2/study.py $step
        if ($LASTEXITCODE -ne 0) { throw "V2 experiment failed: $step" }
    }
} finally { Pop-Location }
