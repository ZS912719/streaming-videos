param(
    [Parameter(Mandatory = $true)]
    [string]$JudgeSource,
    [int]$Repetitions = 3
)

$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    & (Join-Path $PSScriptRoot 'build.ps1') -JudgeSource $JudgeSource
    $judge = Join-Path $PSScriptRoot 'tmp/judgeHashCode2017.exe'
    $winning = @(
        [PSCustomObject]@{dataset='me_at_the_zoo';start='greedy';passes=3;moves=1;selection='best';order='forward';candidate='forward';score=507906},
        [PSCustomObject]@{dataset='videos_worth_spreading';start='ocag';passes=50;moves=1;selection='first';order='forward';candidate='forward';score=609635},
        [PSCustomObject]@{dataset='trending_today';start='greedy';passes=50;moves=2;selection='first';order='forward';candidate='forward';score=499857},
        [PSCustomObject]@{dataset='kittens';start='ocag';passes=30;moves=1;selection='best';order='reverse';candidate='forward';score=1022004}
    )
    $rows = [Collections.Generic.List[object]]::new()
    foreach ($config in $winning) {
        $inputPath = "examples/$($config.dataset).in"
        $startPath = "tmp/tuning-reference-$($config.dataset)-$($config.start).out"
        & java -Xmx4g -cp out com.hashcode.streaming.Main $config.start $inputPath $startPath
        if ($LASTEXITCODE -ne 0) { throw "Failed to build $startPath" }
        for ($repetition = 1; $repetition -le $Repetitions; $repetition++) {
            $stem = "tmp/winner-$($config.dataset)-rep$repetition"
            & java -Xmx4g -cp out com.hashcode.streaming.experiments.LocalSearchTuningRunner `
                $inputPath $startPath "$stem.out" "$stem.csv" "$stem-trace.csv" `
                $config.passes $config.moves $config.selection $config.order $config.candidate
            if ($LASTEXITCODE -ne 0) { throw "Search failed for $stem" }
            $judged = & $judge $inputPath "$stem.out"
            if ($LASTEXITCODE -ne 0 -or $judged -notmatch 'Score\s*=\s*(\d+)') {
                throw "Judge failed for $stem"
            }
            $official = [long]$Matches[1]
            $metrics = Import-Csv "$stem.csv"
            if ($official -ne $config.score -or
                    $official -ne [long]$metrics.internal_final_score) {
                throw "Score mismatch for $stem"
            }
            $rows.Add([PSCustomObject]@{
                dataset = $config.dataset
                start_algorithm = $config.start
                repetition = $repetition
                official_score = $official
                max_passes = $config.passes
                max_moves_per_cache = $config.moves
                selection = $config.selection
                cache_order = $config.order
                candidate_order = $config.candidate
                search_ms = $metrics.search_ms
                executed_passes = $metrics.passes
                accepted_moves = $metrics.accepted_moves
                termination = $metrics.termination
            })
        }
    }
    $rows | Export-Csv -NoTypeInformation -Encoding utf8 `
        'experiments/local-search-winning-repetitions.csv'
    Write-Host "Wrote $($rows.Count) repeated, officially checked runs."
} finally {
    Pop-Location
}
