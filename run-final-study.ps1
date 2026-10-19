param(
    [Parameter(Mandatory=$true)][string]$RunId,
    [Parameter(Mandatory=$true)][string]$Dataset,
    [Parameter(Mandatory=$true)][ValidateSet('dynamic','exchange','local','packing','repack')][string]$Mode,
    [string]$StartSolution,
    [double]$Weight=1.0,
    [ValidateSet('dynamic','static')][string]$RegretMode='dynamic',
    [int]$Pairs=2147483647,
    [int]$VideoLimit=2147483647,
    [int]$Rounds=50,
    [switch]$First,
    [long]$Milliseconds=0,
    [int]$Passes=50,
    [int]$Moves=1,
    [string]$Selection='best',
    [string]$CacheOrder='forward',
    [int]$CandidateLimit=64,
    [string]$Neighborhood='both',
    [string]$JudgeExecutable='tmp/judgeHashCode2017.exe'
)

$ErrorActionPreference='Stop'
Push-Location $PSScriptRoot
try {
    if ($RunId -notmatch '^[a-zA-Z0-9_-]+$') { throw 'RunId must be a simple filename' }
    $root='tmp/final-study'
    New-Item -ItemType Directory -Force $root | Out-Null
    $stem="$root/$RunId"
    if (Test-Path "$stem.metrics.csv") { throw "Run already exists: $RunId" }
    $inputPath="examples/$Dataset.in"
    if (-not $StartSolution) { $StartSolution="experiments/final-study-references/$Dataset.txt" }
    if ($Mode -eq 'local') {
        & java -Xmx4g -cp out com.hashcode.streaming.experiments.LocalSearchTuningRunner `
            $inputPath $StartSolution "$stem.out" "$stem.metrics.csv" "$stem.trace.csv" `
            $Passes $Moves $Selection $CacheOrder forward
    } else {
        $parameters = if ($Mode -eq 'dynamic') {
            @($Weight.ToString([Globalization.CultureInfo]::InvariantCulture),$RegretMode)
        } elseif ($Mode -eq 'packing') { @($Passes,$CandidateLimit,$Neighborhood) }
        elseif ($Mode -eq 'repack') { @($Passes,$CandidateLimit) } else {
            @($Pairs,$VideoLimit,$Rounds,([bool]$First).ToString().ToLowerInvariant(),$Milliseconds)
        }
        & java -Xmx4g -cp out com.hashcode.streaming.experiments.FinalOptimizationRunner `
            $Mode $inputPath $StartSolution "$stem.out" "$stem.metrics.csv" "$stem.trace.csv" @parameters
    }
    if ($LASTEXITCODE -ne 0) { throw "Solver failed: $RunId" }
    $judged = & $JudgeExecutable $inputPath "$stem.out"
    if ($LASTEXITCODE -ne 0 -or $judged -notmatch 'Score\s*=\s*(\d+)') { throw "Judge failed: $RunId $judged" }
    $score=[long]$Matches[1]
    $metrics=Import-Csv "$stem.metrics.csv"
    $internal=if($Mode -eq 'local'){$metrics.internal_final_score}else{$metrics.internal_score}
    if ($score -ne [long]$internal) { throw "Internal/official mismatch: $RunId" }
    $row=[PSCustomObject]@{
        run_id=$RunId; dataset=$Dataset; mode=$Mode; start_solution=$StartSolution
        weight=$Weight.ToString([Globalization.CultureInfo]::InvariantCulture); regret_mode=$RegretMode
        pairs=$Pairs; video_limit=$VideoLimit; rounds=$Rounds; first=[bool]$First
        time_limit_ms=$Milliseconds; passes=$Passes; moves=$Moves; selection=$Selection; cache_order=$CacheOrder
        candidate_limit=$CandidateLimit; neighborhood=$Neighborhood
        official_score=$score
        raw_objective=if($Mode -eq 'local'){$metrics.final_saved_latency}else{$metrics.raw_objective}
        algorithm_ms=if($Mode -eq 'local'){$metrics.search_ms}else{$metrics.algorithm_ms}
        index_ms=$metrics.index_ms; scan_ms=$metrics.scan_ms; update_ms=$metrics.update_ms
        accepted=if($Mode -eq 'local'){$metrics.accepted_moves}else{$metrics.accepted}
        evaluated=if($Mode -eq 'local'){$metrics.evaluated_moves}else{$metrics.candidate_evaluations}
        feasible=$metrics.feasible; positive=$metrics.positive; regret_updates=$metrics.regret_updates
        infeasible_alternatives=$metrics.infeasible_alternatives; changed_selected_regrets=$metrics.changed_selected_regrets
        estimated_array_bytes=$metrics.estimated_array_bytes; positive_regret_placements=$metrics.positive_regret_placements
        rounded_improvements=$metrics.rounded_improvements; executed_passes=$metrics.passes; executed_rounds=$metrics.rounds
        termination=$metrics.termination; output_path="$stem.out"; trace_path="$stem.trace.csv"
    }
    $row | Export-Csv -NoTypeInformation -Encoding utf8 -Append 'experiments/final-study-ledger.csv'
    Write-Host "$RunId official=$score ms=$($row.algorithm_ms)"
} finally { Pop-Location }
