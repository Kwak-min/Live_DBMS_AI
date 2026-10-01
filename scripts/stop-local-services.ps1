[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $repositoryRoot
try {
    & docker compose -f docker-compose.yml down
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose exited with code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}
