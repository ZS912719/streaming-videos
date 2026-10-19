param(
    [Parameter(Mandatory=$true)][string]$JudgeSource,
    [ValidateSet('all','screen','strengthen','validate','stress')][string]$Phase='all'
)
$ErrorActionPreference='Stop'
Push-Location $PSScriptRoot
try {
    & .\build.ps1 -JudgeSource $JudgeSource
    $phases=if($Phase -eq 'all'){@('screen','strengthen','validate','stress')}else{@($Phase)}
    foreach ($step in $phases) {
        & python .\competitive-study.py $step
        if ($LASTEXITCODE -ne 0) { throw "Competitive experiment failed: $step" }
    }
    if ($Phase -in @('all','validate','stress')) {
        & python .\summarize-competitive.py
        if ($LASTEXITCODE -ne 0) { throw 'Competitive summary failed' }
    }
} finally { Pop-Location }
