param(
    [Parameter(Mandatory = $true)]
    [string]$JudgeSource,
    [string]$OutputDirectory = "experiments",
    [int]$FastRepetitions = 5,
    [int]$KittensRepetitions = 3
)

$ErrorActionPreference = "Stop"
Push-Location $PSScriptRoot
try {
    & (Join-Path $PSScriptRoot "build.ps1") -JudgeSource $JudgeSource
    $judge = Join-Path $PSScriptRoot "tmp/judgeHashCode2017.exe"
    $outputRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot $OutputDirectory))
    $rawRoot = Join-Path $PSScriptRoot "tmp/scientific-runs"
    New-Item -ItemType Directory -Force -Path $outputRoot, $rawRoot | Out-Null

    $datasets = @("me_at_the_zoo", "videos_worth_spreading", "trending_today", "kittens")
    $primaryAlgorithms = @("baseline", "greedy", "ocag", "greedy-ls", "ocag-ls")
    $analysisAlgorithms = @("baseline-ls", "greedy-ls-insertion", "greedy-ls-replacement")
    $runtimeRows = [Collections.Generic.List[object]]::new()
    $localRows = [Collections.Generic.List[object]]::new()
    $opportunityRows = [Collections.Generic.List[object]]::new()
    $localTraceRows = [Collections.Generic.List[object]]::new()
    $ocagTraceRows = [Collections.Generic.List[object]]::new()

    function Invoke-Experiment([string]$Dataset, [string]$Algorithm, [int]$Repetition,
            [bool]$KeepTrace) {
        $stem = "$Dataset.$Algorithm.rep_$Repetition"
        $inputPath = Join-Path $PSScriptRoot "examples/$Dataset.in"
        $solutionPath = Join-Path $rawRoot "$stem.out"
        $metricsPath = Join-Path $rawRoot "$stem.metrics.csv"
        $localTracePath = Join-Path $rawRoot "$stem.local-trace.csv"
        $ocagTracePath = Join-Path $rawRoot "$stem.ocag-trace.csv"

        $psi = [Diagnostics.ProcessStartInfo]::new()
        $psi.FileName = "java"
        foreach ($argument in @("-Xmx4g", "-cp", "out",
                "com.hashcode.streaming.experiments.ScientificExperimentRunner",
                $Algorithm, $inputPath, $solutionPath, $metricsPath,
                $localTracePath, $ocagTracePath)) {
            $psi.ArgumentList.Add($argument)
        }
        $psi.RedirectStandardOutput = $true
        $psi.RedirectStandardError = $true
        $psi.UseShellExecute = $false
        $process = [Diagnostics.Process]::new()
        $process.StartInfo = $psi
        [void]$process.Start()
        $peakBytes = 0L
        while (-not $process.WaitForExit(100)) {
            $process.Refresh()
            $peakBytes = [Math]::Max($peakBytes, $process.WorkingSet64)
        }
        $stdout = $process.StandardOutput.ReadToEnd()
        $stderr = $process.StandardError.ReadToEnd()
        $peakMb = ($peakBytes / 1MB).ToString(
            "F3", [Globalization.CultureInfo]::InvariantCulture)
        if ($process.ExitCode -ne 0) {
            throw "$Dataset/$Algorithm failed: $stderr $stdout"
        }

        $judgeOutput = & $judge $inputPath $solutionPath
        if ($LASTEXITCODE -ne 0 -or $judgeOutput -notmatch "Score\s*=\s*(\d+)") {
            throw "Judge failed for $Dataset/$Algorithm`: $judgeOutput"
        }
        $officialScore = [long]$Matches[1]
        $metrics = Import-Csv $metricsPath
        if ([long]$metrics.internal_final_score -ne $officialScore) {
            throw "Internal/official score mismatch for $Dataset/$Algorithm"
        }
        $row = [PSCustomObject]@{
            dataset = $Dataset
            algorithm = $Algorithm
            repetition = $Repetition
            official_score = $officialScore
            initial_score = [long]$metrics.internal_initial_score
            parse_ms = $metrics.parse_ms
            construction_ms = $metrics.construction_ms
            local_search_ms = $metrics.local_search_ms
            validation_scoring_ms = $metrics.validation_scoring_ms
            writing_ms = $metrics.writing_ms
            algorithm_ms = $metrics.algorithm_ms
            peak_working_set_mb = $peakMb
            ls_passes = [int]$metrics.ls_passes
            ls_evaluated_moves = [long]$metrics.ls_evaluated_moves
            ls_accepted_moves = [int]$metrics.ls_accepted_moves
            ls_additions = [int]$metrics.ls_additions
            ls_replacements = [int]$metrics.ls_replacements
            ls_saved_latency_gain = [long]$metrics.ls_saved_latency_gain
            ls_termination = $metrics.ls_termination
            ocag_placements = [int]$metrics.ocag_placements
            ocag_regret_placements = [int]$metrics.ocag_regret_placements
            ocag_selected_static_regret = [long]$metrics.ocag_selected_static_regret
        }
        if ($KeepTrace) {
            if (Test-Path $localTracePath) {
                foreach ($traceRow in Import-Csv $localTracePath) { $localTraceRows.Add($traceRow) }
            }
            if (Test-Path $ocagTracePath) {
                foreach ($traceRow in Import-Csv $ocagTracePath) { $ocagTraceRows.Add($traceRow) }
            }
        }
        Write-Host "$Dataset / $Algorithm / repetition $Repetition`: score $officialScore"
        return $row
    }

    foreach ($dataset in $datasets) {
        $repetitions = if ($dataset -eq "kittens") { $KittensRepetitions } else { $FastRepetitions }
        foreach ($algorithm in $primaryAlgorithms) {
            for ($repetition = 1; $repetition -le $repetitions; $repetition++) {
                $row = Invoke-Experiment $dataset $algorithm $repetition ($repetition -eq 1)
                $runtimeRows.Add($row)
                if ($repetition -eq 1 -and $algorithm -in @("greedy", "ocag", "greedy-ls", "ocag-ls")) {
                    $opportunityRows.Add($row)
                }
                if ($repetition -eq 1 -and $algorithm -eq "greedy-ls") {
                    $localRows.Add($row)
                }
            }
        }
        foreach ($algorithm in $analysisAlgorithms) {
            $row = Invoke-Experiment $dataset $algorithm 1 $true
            $localRows.Add($row)
        }
    }

    $runtimeRows | Export-Csv -NoTypeInformation -Encoding utf8 `
        (Join-Path $outputRoot "runtime-results.csv")
    $localRows | Export-Csv -NoTypeInformation -Encoding utf8 `
        (Join-Path $outputRoot "local-search-results.csv")
    $opportunityRows | Export-Csv -NoTypeInformation -Encoding utf8 `
        (Join-Path $outputRoot "opportunity-cost-results.csv")
    $localTraceRows | Export-Csv -NoTypeInformation -Encoding utf8 `
        (Join-Path $outputRoot "local-search-trace.csv")
    $ocagTraceRows | Export-Csv -NoTypeInformation -Encoding utf8 `
        (Join-Path $outputRoot "opportunity-cost-trace.csv")
} finally {
    Pop-Location
}
