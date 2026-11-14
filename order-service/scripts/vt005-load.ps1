param([string]$Maven = 'mvn', [string]$LocalRepository = '')
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
Push-Location $repoRoot
try {
    $mavenArguments = @('-pl', 'order-service', '-Dtest=OrderSearchLoadHarness', '-Djacoco.skip=true', 'test')
    if ($LocalRepository) { $mavenArguments += "-Dmaven.repo.local=$LocalRepository" }
    # Windows PowerShell can wrap harmless JVM stderr warnings as NativeCommandError.
    # Judge Maven by its process exit code while preserving stdout/stderr in the log.
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $Maven @mavenArguments
        $runExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($runExitCode -ne 0) { throw "Load harness failed (exit $runExitCode); inspect target/vt005-load-results.json if generated." }
    Write-Host 'Measurements: order-service/target/vt005-load-results.json'
} finally {
    Pop-Location
}
