$ErrorActionPreference = "Stop"
Push-Location $PSScriptRoot
try {
    & (Join-Path $PSScriptRoot "build.ps1")
    $testOutput = Join-Path $PSScriptRoot "tmp/test-classes"
    New-Item -ItemType Directory -Force -Path $testOutput | Out-Null
    $sources = Get-ChildItem -Recurse -File "src/main/java/*.java" |
        ForEach-Object { $_.FullName }
    $tests = Get-ChildItem -Recurse -File "src/test/java/*.java" |
        ForEach-Object { $_.FullName }
    & javac --release 17 -encoding UTF-8 -d $testOutput $sources $tests
    if ($LASTEXITCODE -ne 0) { throw "Test compilation failed." }
    & java -cp $testOutput com.hashcode.streaming.SolverRegressionTest
    if ($LASTEXITCODE -ne 0) { throw "Solver regression tests failed." }
} finally {
    Pop-Location
}
