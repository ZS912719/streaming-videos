param(
    [Parameter(Mandatory = $true)]
    [string]$JudgeSource,
    [switch]$CollectExisting,
    [switch]$BestOnly
)

$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    & (Join-Path $PSScriptRoot 'build.ps1') -JudgeSource $JudgeSource
    $judge = Join-Path $PSScriptRoot 'tmp/judgeHashCode2017.exe'
    $resultsPath = Join-Path $PSScriptRoot 'experiments/local-search-tuning-results.csv'
    $expectedBest = @{
        me_at_the_zoo = 507906
        videos_worth_spreading = 609635
        trending_today = 499857
        kittens = 1022004
    }

    if ($CollectExisting) {
        $rows = [Collections.Generic.List[object]]::new()
        foreach ($metricsFile in Get-ChildItem 'tmp' -Filter 'tune-*.csv' -File) {
            if ($metricsFile.BaseName -like '*-trace') { continue }
            $metrics = Import-Csv -LiteralPath $metricsFile.FullName
            $runId = $metricsFile.BaseName
            $output = Join-Path $PSScriptRoot "tmp/$runId.out"
            if (-not (Test-Path -LiteralPath $output)) {
                throw "Missing output for $runId"
            }
            $dataset = $metrics.dataset.Replace('.in', '')
            $inputPath = Join-Path $PSScriptRoot "examples/$dataset.in"
            $judged = & $judge $inputPath $output
            if ($LASTEXITCODE -ne 0 -or $judged -notmatch 'Score\s*=\s*(\d+)') {
                throw "Official judge failed for $runId`: $judged"
            }
            $official = [long]$Matches[1]
            if ($official -ne [long]$metrics.internal_final_score) {
                throw "Internal/official mismatch for $runId"
            }
            $start = if ($metrics.start_solution -match '-(greedy|ocag)\.out$') {
                $Matches[1]
            } else {
                throw "Unknown start for $runId"
            }
            $reference = Import-Csv "tmp/tuning-reference-$dataset-$start.csv"
            $candidateOrder = if ($metrics.PSObject.Properties.Name -contains 'candidate_order') {
                $metrics.candidate_order
            } else { 'forward' }
            $rows.Add([PSCustomObject]@{
                run_id = $runId
                dataset = $dataset
                start_algorithm = $start
                max_passes = $metrics.max_passes
                max_moves_per_cache = $metrics.max_moves_per_cache
                selection = $metrics.selection
                cache_order = $metrics.cache_order
                candidate_order = $candidateOrder
                initial_score = $metrics.initial_score
                official_score = $official
                score_gain = $official - [long]$metrics.initial_score
                construction_ms = $reference.construction_ms
                search_ms = $metrics.search_ms
                executed_passes = $metrics.passes
                accepted_moves = $metrics.accepted_moves
                evaluated_moves = $metrics.evaluated_moves
                initial_saved_latency = $metrics.initial_saved_latency
                final_saved_latency = $metrics.final_saved_latency
                termination = $metrics.termination
            })
        }
        $rows | Sort-Object dataset,start_algorithm,run_id |
            Export-Csv -NoTypeInformation -Encoding utf8 $resultsPath
        Write-Host "Collected $($rows.Count) independently judged tuning runs."
        return
    }

    $plan = Import-Csv $resultsPath
    if ($BestOnly) {
        $plan = $plan | Where-Object {
            [long]$_.official_score -eq [long]$expectedBest[$_.dataset]
        } | Group-Object dataset | ForEach-Object {
            $_.Group | Sort-Object {[double]$_.search_ms} | Select-Object -First 1
        }
    }
    foreach ($group in ($plan | Group-Object dataset,start_algorithm)) {
        $first = $group.Group[0]
        $startPath = "tmp/tuning-reference-$($first.dataset)-$($first.start_algorithm).out"
        & java -Xmx4g -cp out com.hashcode.streaming.Main $first.start_algorithm `
            "examples/$($first.dataset).in" $startPath
        if ($LASTEXITCODE -ne 0) { throw "Failed to reconstruct $startPath" }
    }
    foreach ($row in $plan) {
        $stem = "tmp/$($row.run_id)"
        & java -Xmx4g -cp out com.hashcode.streaming.experiments.LocalSearchTuningRunner `
            "examples/$($row.dataset).in" `
            "tmp/tuning-reference-$($row.dataset)-$($row.start_algorithm).out" `
            "$stem.out" "$stem.csv" "$stem-trace.csv" `
            $row.max_passes $row.max_moves_per_cache $row.selection `
            $row.cache_order $row.candidate_order
        if ($LASTEXITCODE -ne 0) { throw "Failed to reconstruct $($row.run_id)" }
        $judged = & $judge "examples/$($row.dataset).in" "$stem.out"
        if ($LASTEXITCODE -ne 0 -or $judged -notmatch 'Score\s*=\s*(\d+)') {
            throw "Official judge failed for $($row.run_id)"
        }
        if ([long]$Matches[1] -ne [long]$row.official_score) {
            throw "Reproduced score differs for $($row.run_id)"
        }
    }
    Write-Host "Reproduced and officially validated $(@($plan).Count) configurations."
} finally {
    Pop-Location
}
