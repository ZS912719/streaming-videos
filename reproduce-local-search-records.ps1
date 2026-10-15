param(
    [Parameter(Mandatory = $true)]
    [string]$JudgeSource
)

$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    & (Join-Path $PSScriptRoot 'build.ps1') -JudgeSource $JudgeSource
    $judge = Join-Path $PSScriptRoot 'tmp/judgeHashCode2017.exe'
    $resultRoot = Join-Path $PSScriptRoot 'tmp/record-reproduction'
    New-Item -ItemType Directory -Force $resultRoot | Out-Null

    function Invoke-Construction([string]$Dataset, [string]$Algorithm) {
        $output = Join-Path $resultRoot "$Dataset.$Algorithm.out"
        & java -Xmx4g -cp out com.hashcode.streaming.Main $Algorithm `
            "examples/$Dataset.in" $output | Out-Host
        if ($LASTEXITCODE -ne 0) { throw "Construction failed: $Dataset/$Algorithm" }
        return $output
    }

    function Invoke-Tuning([string]$Dataset, [string]$Start, [string]$Tag,
            [int]$Passes, [int]$Moves, [string]$Selection, [string]$Order) {
        $output = Join-Path $resultRoot "$Dataset.$Tag.out"
        & java -Xmx4g -cp out com.hashcode.streaming.experiments.LocalSearchTuningRunner `
            "examples/$Dataset.in" $Start $output `
            (Join-Path $resultRoot "$Dataset.$Tag.metrics.csv") `
            (Join-Path $resultRoot "$Dataset.$Tag.trace.csv") `
            $Passes $Moves $Selection $Order forward | Out-Host
        if ($LASTEXITCODE -ne 0) { throw "Local Search failed: $Dataset/$Tag" }
        return $output
    }

    function Invoke-Exchange([string]$Dataset, [string]$Start, [string]$Tag,
            [int]$Pairs, [int]$Videos, [int]$Rounds) {
        $output = Join-Path $resultRoot "$Dataset.$Tag.out"
        & java -Xmx4g -cp out com.hashcode.streaming.experiments.CrossCacheSwapProbe `
            "examples/$Dataset.in" $Start $Pairs $Videos $Rounds $output | Out-Host
        if ($LASTEXITCODE -ne 0) { throw "Exchange failed: $Dataset/$Tag" }
        return $output
    }

    $final = @{}
    $final.me_at_the_zoo = Invoke-Construction 'me_at_the_zoo' 'greedy'

    $videos = Invoke-Construction 'videos_worth_spreading' 'ocag'
    $videos = Invoke-Tuning 'videos_worth_spreading' $videos 'tuned' 50 1 'first' 'forward'
    $exchangePlan = @(
        [PSCustomObject]@{videos=20;rounds=30},
        [PSCustomObject]@{videos=20;rounds=30},
        [PSCustomObject]@{videos=100;rounds=30},
        [PSCustomObject]@{videos=100;rounds=20},
        [PSCustomObject]@{videos=100;rounds=20},
        [PSCustomObject]@{videos=100;rounds=20}
    )
    for ($phase = 0; $phase -lt $exchangePlan.Count; $phase++) {
        $number = $phase + 1
        $plan = $exchangePlan[$phase]
        $videos = Invoke-Exchange 'videos_worth_spreading' $videos `
            "exchange$number" 4950 $plan.videos $plan.rounds
        $videos = Invoke-Tuning 'videos_worth_spreading' $videos `
            "retune$number" 70 1 'best' 'forward'
    }
    $final.videos_worth_spreading = $videos

    $trending = Invoke-Construction 'trending_today' 'greedy'
    $final.trending_today = Invoke-Tuning 'trending_today' $trending `
        'tuned' 50 2 'first' 'forward'

    $kittens = Invoke-Construction 'kittens' 'ocag'
    $kittens = Invoke-Tuning 'kittens' $kittens 'tuned' 30 1 'best' 'reverse'
    $kittens = Invoke-Exchange 'kittens' $kittens 'exchange1' 20000 100 1
    $final.kittens = Invoke-Tuning 'kittens' $kittens 'retune1' 30 1 'best' 'forward'

    $expected = @{
        me_at_the_zoo = 507906
        videos_worth_spreading = 610023
        trending_today = 499857
        kittens = 1022005
    }
    foreach ($dataset in @('me_at_the_zoo','videos_worth_spreading','trending_today','kittens')) {
        $judged = & $judge "examples/$dataset.in" $final[$dataset]
        if ($LASTEXITCODE -ne 0 -or $judged -notmatch 'Score\s*=\s*(\d+)') {
            throw "Judge failed for $dataset"
        }
        if ([long]$Matches[1] -ne [long]$expected[$dataset]) {
            throw "Reproduced $dataset score $($Matches[1]) differs from record"
        }
        Write-Host "$dataset`: $($Matches[1])"
    }
} finally {
    Pop-Location
}
