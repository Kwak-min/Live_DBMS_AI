[CmdletBinding()]
param(
    [switch]$WithMariaDb
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $repositoryRoot
try {
    & docker compose version | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Docker Compose is required to start the local services.'
    }

    $arguments = @('compose')
    if ($WithMariaDb) {
        $arguments += @('--profile', 'target-db')
    }
    $arguments += @('up', '-d', '--wait')

    & docker @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose exited with code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}
