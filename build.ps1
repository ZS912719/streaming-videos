param(
    [string]$JudgeSource
)

$ErrorActionPreference = "Stop"
Push-Location $PSScriptRoot
try {
    $outputDirectory = Join-Path $PSScriptRoot "out"
    if (Test-Path -LiteralPath $outputDirectory) {
        Remove-Item -LiteralPath $outputDirectory -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
    $temporaryDirectory = Join-Path $PSScriptRoot "tmp"
    New-Item -ItemType Directory -Force -Path $temporaryDirectory | Out-Null

    $sources = Get-ChildItem -Recurse -File "src/main/java/*.java" |
        ForEach-Object { $_.FullName }
    & javac --release 17 -encoding UTF-8 -d $outputDirectory $sources
    if ($LASTEXITCODE -ne 0) {
        throw "Java compilation failed with exit code $LASTEXITCODE"
    }
    Write-Host "Java classes built in out/"

    if ($JudgeSource) {
        $resolvedJudgeSource = (Resolve-Path -LiteralPath $JudgeSource).Path
        $judgeExecutable = Join-Path $temporaryDirectory "judgeHashCode2017.exe"
        & g++ -O2 -std=c++17 $resolvedJudgeSource -o $judgeExecutable
        if ($LASTEXITCODE -ne 0) {
            throw "Judge compilation failed with exit code $LASTEXITCODE"
        }
        Write-Host "Official judge built in tmp/judgeHashCode2017.exe"
    }
} finally {
    Pop-Location
}
