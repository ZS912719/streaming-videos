param(
    [Parameter(Mandatory=$true)][string]$Prefix,
    [Parameter(Mandatory=$true)][string]$Dataset,
    [Parameter(Mandatory=$true)][string]$StartSolution,
    [ValidateSet('repair','exchange-first','packing','alternate','repack')][string]$Schedule='alternate',
    [int]$Cycles=1,
    [int]$ExchangeRounds=20,
    [int]$PackingPasses=10,
    [int]$CandidateLimit=64,
    [int]$Passes=50,
    [int]$Moves=1,
    [string]$Selection='best',
    [string]$CacheOrder='forward',
    [switch]$FirstExchange
)
$ErrorActionPreference='Stop'
Push-Location $PSScriptRoot
try {
    $current=$StartSolution
    $previousRaw=''
    for($cycle=1;$cycle -le $Cycles;$cycle++) {
        $steps=switch($Schedule) {
            'repair' {@('local','exchange','local')}
            'exchange-first' {@('exchange','local')}
            'packing' {@('local','packing','local')}
            'alternate' {@('local','packing','exchange','local')}
            'repack' {@('local','repack','exchange','local')}
        }
        $number=0
        foreach($mode in $steps) {
            $number++
            $runId="$Prefix-c$cycle-s$number-$mode"
            & (Join-Path $PSScriptRoot 'run-final-study.ps1') -RunId $runId -Dataset $Dataset `
                -Mode $mode -StartSolution $current -Rounds $ExchangeRounds -Passes `
                $(if($mode -in @('packing','repack')){$PackingPasses}else{$Passes}) `
                -CandidateLimit $CandidateLimit -Selection $Selection -CacheOrder $CacheOrder `
                -Moves $Moves -First:$FirstExchange
            $current="tmp/final-study/$runId.out"
        }
        $metrics=Import-Csv "tmp/final-study/$runId.metrics.csv"
        $raw=if($mode -eq 'local'){$metrics.final_saved_latency}else{$metrics.raw_objective}
        if($raw -eq $previousRaw){Write-Host 'Complete cycle made no further raw improvement.';break}
        $previousRaw=$raw
    }
    Write-Host "Pipeline output: $current"
} finally { Pop-Location }
