param(
    [Parameter(Mandatory=$true)][string]$JudgeSource,
    [ValidateSet('record','efficient','shared')][string]$Subset='record',
    [string[]]$Datasets=@('me_at_the_zoo','videos_worth_spreading','trending_today','kittens')
)
$ErrorActionPreference='Stop'
Push-Location $PSScriptRoot
try {
    & (Join-Path $PSScriptRoot 'build.ps1') -JudgeSource $JudgeSource
    $config=Get-Content -Raw 'experiments/final-ocag-ls-configurations.json' | ConvertFrom-Json
    $nodes=@{}; $byOutput=@{}; $completed=@{}
    foreach($node in $config.runs){$nodes[$node.run_id]=$node;$byOutput[$node.output_path]=$node.run_id}
    $root="tmp/final-replay/$Subset-$(Get-Date -Format yyyyMMddHHmmss)"
    New-Item -ItemType Directory -Force $root | Out-Null
    $rows=[Collections.Generic.List[object]]::new()
    function Invoke-Node([string]$RunId) {
        if($completed.ContainsKey($RunId)){return $completed[$RunId]}
        $node=$nodes[$RunId]
        if(-not $node){throw "Missing replay node: $RunId"}
        $start=$node.start_solution
        if($node.mode -ne 'dynamic' -and $byOutput.ContainsKey($start)) {
            $start=Invoke-Node $byOutput[$start]
        }
        $stem="$root/$RunId"
        $inputPath="examples/$($node.dataset).in"
        if($node.mode -eq 'local') {
            & java -Xmx4g -cp out com.hashcode.streaming.experiments.LocalSearchTuningRunner `
                $inputPath $start "$stem.out" "$stem.metrics.csv" "$stem.trace.csv" `
                $node.passes $node.moves $node.selection $node.cache_order forward | Out-Host
        } else {
            $parameters=switch($node.mode) {
                'dynamic' {@($node.weight,$node.regret_mode)}
                'exchange' {@($node.pairs,$node.video_limit,$node.rounds,$node.first.ToLowerInvariant(),$node.time_limit_ms)}
                'packing' {@($node.passes,$node.candidate_limit,$node.neighborhood)}
                'repack' {@($node.passes,$node.candidate_limit)}
            }
            & java -Xmx4g -cp out com.hashcode.streaming.experiments.FinalOptimizationRunner `
                $node.mode $inputPath $start "$stem.out" "$stem.metrics.csv" "$stem.trace.csv" @parameters | Out-Host
        }
        if($LASTEXITCODE -ne 0){throw "Replay solver failed: $RunId"}
        $judge=& .\tmp\judgeHashCode2017.exe $inputPath "$stem.out"
        if($LASTEXITCODE -ne 0 -or $judge -notmatch 'Score\s*=\s*(\d+)') {throw "Replay judge failed: $RunId"}
        $score=[long]$Matches[1]
        $metrics=Import-Csv "$stem.metrics.csv"
        $raw=if($node.mode -eq 'local'){$metrics.final_saved_latency}else{$metrics.raw_objective}
        if($score -ne [long]$node.official_score -or [long]$raw -ne [long]$node.raw_objective) {
            throw "Replay differs in official score or exact raw objective: $RunId"
        }
        $elapsed=if($node.mode -eq 'local'){$metrics.search_ms}else{$metrics.algorithm_ms}
        $rows.Add([PSCustomObject]@{run_id=$RunId;dataset=$node.dataset;mode=$node.mode;official_score=$score;raw_objective=$raw;algorithm_ms=$elapsed;output="$stem.out"})
        $completed[$RunId]="$stem.out"
        return "$stem.out"
    }
    $targets=$config."${Subset}_targets"
    foreach($dataset in $Datasets) {
        $id=$targets.$dataset
        if(-not $id){throw "No configured target for $dataset"}
        $output=Invoke-Node $id
        Write-Host "$dataset`: $($nodes[$id].official_score) -> $output"
    }
    $rows | Export-Csv -NoTypeInformation -Encoding utf8 "$root/replay-results.csv"
    Write-Host "Verified $($rows.Count) stages with exact raw objectives and official scores. Results: $root"
} finally { Pop-Location }
