[CmdletBinding()]
param(
    [switch]$WithMariaDb
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $repositoryRoot
try {
    & docker compose -f docker-compose.yml version | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Docker Compose is required to start the local services.'
    }

    $services = @('postgres', 'redis')
    if ($WithMariaDb) {
        $services += 'mariadb-target'
    }

    & docker compose -f docker-compose.yml up -d --wait @services
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose exited with code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}
