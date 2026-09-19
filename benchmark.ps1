param(
    [Parameter(Mandatory = $true)]
    [string]$JudgeSource,
    [string]$ResultsPath = "benchmark-results.csv"
)

$ErrorActionPreference = "Stop"
Push-Location $PSScriptRoot
try {
    & (Join-Path $PSScriptRoot "build.ps1") -JudgeSource $JudgeSource

    $judgeExecutable = Join-Path $PSScriptRoot "tmp/judgeHashCode2017.exe"
    $outputDirectory = Join-Path $PSScriptRoot "tmp/benchmark"
    New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null

    $datasets = @(
        "me_at_the_zoo",
        "videos_worth_spreading",
        "trending_today",
        "kittens"
    )
    $algorithms = @("baseline", "greedy")
    $rows = @()

    foreach ($dataset in $datasets) {
        foreach ($algorithm in $algorithms) {
            $inputPath = Join-Path $PSScriptRoot "examples/$dataset.in"
            $outputPath = Join-Path $outputDirectory "$dataset.$algorithm.out"
            $timer = [Diagnostics.Stopwatch]::StartNew()
            & java -Xmx4g -cp out com.hashcode.streaming.Main `
                $algorithm $inputPath $outputPath 2>&1 |
                ForEach-Object { Write-Host $_ }
            $solverExitCode = $LASTEXITCODE
            $timer.Stop()
            if ($solverExitCode -ne 0) {
                throw "$algorithm failed on $dataset with exit code $solverExitCode"
            }

            $judgeOutput = & $judgeExecutable $inputPath $outputPath
            if ($LASTEXITCODE -ne 0 -or $judgeOutput -notmatch "Score\s*=\s*(\d+)") {
                throw "Official judge failed on $dataset/$algorithm`: $judgeOutput"
            }
            $rows += [PSCustomObject]@{
                dataset = $dataset
                algorithm = $algorithm
                official_score = [long]$Matches[1]
                runtime_seconds = $timer.Elapsed.TotalSeconds.ToString(
                    "F3", [Globalization.CultureInfo]::InvariantCulture)
            }
        }
    }

    $resultsFile = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot $ResultsPath))
    $rows | Export-Csv -NoTypeInformation -Encoding utf8 -Path $resultsFile
    $rows | Format-Table -AutoSize
    Write-Host "Benchmark results written to $resultsFile"
} finally {
    Pop-Location
}
