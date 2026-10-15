param([int]$Repetitions=3)
$ErrorActionPreference='Stop'
Push-Location $PSScriptRoot
try {
    $rows=[Collections.Generic.List[object]]::new()
    $inputPath='examples/videos_worth_spreading.in'
    $reference='experiments/final-study-references/videos_worth_spreading.txt'
    foreach($implementation in @('legacy','indexed')) {
        for($repeat=1;$repeat -le $Repetitions;$repeat++) {
            $stem="tmp/final-study/profile-$implementation-$repeat"
            if($implementation -eq 'legacy') {
                $lines=& java -Xmx4g -cp out com.hashcode.streaming.experiments.CrossCacheSwapProbe `
                    $inputPath $reference 4950 100 3 "$stem.out"
                if($LASTEXITCODE -ne 0){throw 'Legacy profile failed'}
                $summary=$lines[-1]
                if($summary -notmatch 'search_ms=([0-9.,]+)'){throw 'Missing legacy timing'}
                $milliseconds=$Matches[1].Replace(',','.')
            } else {
                & java -Xmx4g -cp out com.hashcode.streaming.experiments.FinalOptimizationRunner `
                    exchange $inputPath $reference "$stem.out" "$stem.metrics.csv" "$stem.trace.csv" 4950 100 3 false 0
                if($LASTEXITCODE -ne 0){throw 'Indexed profile failed'}
                $milliseconds=(Import-Csv "$stem.metrics.csv").algorithm_ms
            }
            $judge=& .\tmp\judgeHashCode2017.exe $inputPath "$stem.out"
            if($LASTEXITCODE -ne 0 -or $judge -notmatch 'Score\s*=\s*(\d+)' -or [long]$Matches[1] -ne 610031){throw 'Profile score mismatch'}
            $rows.Add([PSCustomObject]@{implementation=$implementation;repetition=$repeat;official_score=610031;search_ms=$milliseconds})
        }
    }
    $rows | Export-Csv -NoTypeInformation -Encoding utf8 'experiments/exchange-performance-results.csv'
} finally { Pop-Location }
