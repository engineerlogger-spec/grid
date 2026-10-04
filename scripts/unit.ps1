# Run JVM unit tests (optionally filtered) and print a pass/fail summary from the JUnit XML reports.
# Usage: .\scripts\unit.ps1 [-Filter "com.grid.app.core.money.*"]
param([string]$Filter)
. "$PSScriptRoot\env.ps1"

Push-Location $Root
try {
    $reports = Join-Path $Root 'app\build\test-results\testDebugUnitTest'
    if (Test-Path $reports) { Remove-Item $reports -Recurse -Force }
    $gradleArgs = @('testDebugUnitTest', '--console=plain')
    if ($Filter) { $gradleArgs += @('--tests', $Filter) }
    $out = & .\gradlew.bat @gradleArgs 2>&1
    $code = $LASTEXITCODE
    $out | Where-Object { $_ -match '^e: |What went wrong|FAILED' } | Select-Object -First 30 | ForEach-Object { Write-Host $_ }

    $tests = 0; $failures = 0; $errors = 0; $skipped = 0
    Get-ChildItem $reports -Filter *.xml -ErrorAction SilentlyContinue | ForEach-Object {
        [xml]$x = Get-Content $_.FullName -Raw
        $s = $x.testsuite
        $tests += [int]$s.tests; $failures += [int]$s.failures; $errors += [int]$s.errors; $skipped += [int]$s.skipped
        foreach ($case in $s.testcase) {
            if ($case.failure -or $case.error) {
                $msg = (($case.failure, $case.error | Where-Object { $_ } | Select-Object -First 1).message -split "`n")[0]
                Write-Host "  FAIL $($s.name.Split('.')[-1]).$($case.name): $msg"
            }
        }
    }
    Write-Host "Tests: $tests  failed: $($failures + $errors)  skipped: $skipped  (gradle exit $code)"
    exit $code
} finally { Pop-Location }
