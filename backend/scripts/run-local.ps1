[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$backendRoot = Split-Path -Parent $PSScriptRoot
$javaVersion = (& java -version 2>&1 | Select-Object -First 1)
if ($LASTEXITCODE -ne 0 -or $javaVersion -notmatch 'version "17\.') {
    throw "JDK 17 is required. Current runtime: $javaVersion"
}

$env:SPRING_PROFILES_ACTIVE = 'local'
Push-Location -LiteralPath $backendRoot
try {
    & .\gradlew.bat bootRun --no-daemon
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
