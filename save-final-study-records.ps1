$ErrorActionPreference='Stop'
Push-Location $PSScriptRoot
try {
    $root='experiments/final-best-solutions'
    New-Item -ItemType Directory -Force $root | Out-Null
    $ledger=Import-Csv 'experiments/final-study-ledger.csv'
    foreach($dataset in @('me_at_the_zoo','videos_worth_spreading','trending_today','kittens')) {
        $winner=$ledger | Where-Object dataset -eq $dataset |
            Sort-Object @{Expression={[long]$_.official_score};Descending=$true},
                @{Expression={[double]$_.algorithm_ms};Descending=$false} | Select-Object -First 1
        $target="$root/$dataset-$($winner.official_score).txt"
        if(-not (Test-Path -LiteralPath $target)) {
            $judge=& .\tmp\judgeHashCode2017.exe "examples/$dataset.in" $winner.output_path
            if($LASTEXITCODE -ne 0 -or $judge -notmatch 'Score\s*=\s*(\d+)' -or [long]$Matches[1] -ne [long]$winner.official_score) {
                throw "Record validation failed: $dataset"
            }
            Copy-Item -LiteralPath $winner.output_path -Destination $target
            $winner | ConvertTo-Json | Set-Content -Encoding utf8 "$root/$dataset-$($winner.official_score).json"
        }
        Write-Host "$dataset $($winner.official_score) $target"
    }
} finally { Pop-Location }
