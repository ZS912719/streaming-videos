param(
    [Parameter(Mandatory = $true)]
    [string]$JudgeSource,
    [string]$OutputDirectory = "experiments"
)

$ErrorActionPreference = "Stop"
Push-Location $PSScriptRoot
try {
    & (Join-Path $PSScriptRoot "build.ps1") -JudgeSource $JudgeSource
    $judge = Join-Path $PSScriptRoot "tmp/judgeHashCode2017.exe"
    $rawRoot = Join-Path $PSScriptRoot "tmp/robustness-study"
    $outputRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot $OutputDirectory))
    New-Item -ItemType Directory -Force -Path $rawRoot, $outputRoot | Out-Null
    $datasets = @("me_at_the_zoo", "videos_worth_spreading", "trending_today", "kittens")
    $algorithms = @("baseline", "greedy", "greedy-ls", "ocag", "ocag-ls")
    $allRows = [Collections.Generic.List[object]]::new()

    foreach ($dataset in $datasets) {
        $input = Join-Path $PSScriptRoot "examples/$dataset.in"
        $solutionArguments = @()
        foreach ($algorithm in $algorithms) {
            $solution = Join-Path $rawRoot "$dataset.$algorithm.out"
            $metrics = Join-Path $rawRoot "$dataset.$algorithm.metrics.csv"
            & java -Xmx4g -cp out `
                com.hashcode.streaming.experiments.ScientificExperimentRunner `
                $algorithm $input $solution $metrics
            if ($LASTEXITCODE -ne 0) { throw "$dataset/$algorithm construction failed" }
            $solutionArguments += "$algorithm=$solution"
        }

        $datasetResults = Join-Path $rawRoot "$dataset.robustness.csv"
        $samples = Join-Path $rawRoot "$dataset-samples"
        & java -Xmx4g -cp out com.hashcode.streaming.experiments.RobustnessExperiment `
            $input $datasetResults $samples @solutionArguments
        if ($LASTEXITCODE -ne 0) { throw "$dataset robustness evaluation failed" }
        $datasetRows = Import-Csv $datasetResults

        foreach ($scenarioPath in Get-ChildItem -LiteralPath $samples -Filter *.in) {
            foreach ($algorithm in $algorithms) {
                $solution = Join-Path $rawRoot "$dataset.$algorithm.out"
                $judgeOutput = & $judge $scenarioPath.FullName $solution
                if ($LASTEXITCODE -ne 0 -or $judgeOutput -notmatch "Score\s*=\s*(\d+)") {
                    throw "Judge failed for $dataset/$algorithm/$($scenarioPath.BaseName)"
                }
                $expected = ($datasetRows | Where-Object {
                    $_.scenario -eq $scenarioPath.BaseName -and $_.algorithm -eq $algorithm
                }).score
                if ([long]$Matches[1] -ne [long]$expected) {
                    throw "Robustness evaluator mismatch for $dataset/$algorithm"
                }
            }
        }
        foreach ($row in $datasetRows) { $allRows.Add($row) }
        Write-Host "$dataset robustness scenarios complete and judge-checked."
    }

    $allRows | Export-Csv -NoTypeInformation -Encoding utf8 `
        (Join-Path $outputRoot "robustness-results.csv")
    & python (Join-Path $PSScriptRoot "summarize-experiments.py")
} finally {
    Pop-Location
}
