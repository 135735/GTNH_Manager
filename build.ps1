# Build helper: pick JDK 25 from ../../toolchain, then call gradlew.
# Usage: .\build.ps1            full build (spotless skipped)
#        .\build.ps1 compileJava compile only
$ErrorActionPreference = 'Stop'

$root = $PSScriptRoot

# Local JDK 25 lives in workspace/toolchain/zulu25.* (two levels above machine-radar)
$toolchain = Join-Path $root '..\..\toolchain'
if (Test-Path $toolchain) {
    $jdk = Get-ChildItem -Directory $toolchain -Filter 'zulu25*' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
}

if (-not $env:JAVA_HOME) { throw 'JAVA_HOME is not set: GTNH convention 2.0.20 needs JDK 25' }

$gradleArgs = @('build', '-x', 'spotlessJava', '-x', 'spotlessCheck', '--console=plain')
if ($args.Count -gt 0) { $gradleArgs = $args }

& (Join-Path $root 'gradlew.bat') @gradleArgs
exit $LASTEXITCODE
