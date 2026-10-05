[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^[0-9a-fA-F]{40}$')]
    [string]$ExpectedHead,

    [Parameter(Mandatory)]
    [string]$EvidenceDir,

    [ValidateRange(209, 10000)]
    [int]$MinimumNormalTests = 209,

    [ValidateRange(60, 3600)]
    [int]$CommandTimeoutSeconds = 1200,

    [string]$ToolRoot = $env:PART_C_TOOL_ROOT,

    [switch]$VerifyCleanupOnly
)

[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding
$ErrorActionPreference = 'Stop'
if (Test-Path Variable:PSNativeCommandUseErrorActionPreference) {
    $PSNativeCommandUseErrorActionPreference = $false
}

function New-RandomBytes([int]$Count) {
    $Bytes = New-Object byte[] $Count
    $Generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $Generator.GetBytes($Bytes)
    }
    finally {
        $Generator.Dispose()
    }
    return ,$Bytes
}

function Convert-ToLowerHex([byte[]]$Bytes) {
    return -join ($Bytes | ForEach-Object { $_.ToString('x2') })
}

$Repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$Backend = Join-Path $Repo 'backend'
$ToolCandidates = [Collections.Generic.List[string]]::new()
$CandidateValues = if (-not [string]::IsNullOrWhiteSpace($ToolRoot)) {
    @($ToolRoot)
}
else {
    @(
        (Join-Path $Repo '.omo\tooling'),
        $(if (-not [string]::IsNullOrWhiteSpace($env:CODEX_HOME)) {
            Join-Path $env:CODEX_HOME 'worktrees\part-c-foundation\Branch_LDBMS\.omo\tooling'
        }),
        $(if (-not [string]::IsNullOrWhiteSpace($env:USERPROFILE)) {
            Join-Path $env:USERPROFILE '.codex\worktrees\part-c-foundation\Branch_LDBMS\.omo\tooling'
        })
    )
}
foreach ($Candidate in $CandidateValues) {
    if (-not [string]::IsNullOrWhiteSpace($Candidate)) {
        $ToolCandidates.Add([IO.Path]::GetFullPath($Candidate))
    }
}
$SelectedToolRoots = @($ToolCandidates | Select-Object -Unique | Where-Object {
    (Test-Path -LiteralPath (Join-Path $_ 'jdk17') -PathType Container) -and
    (Test-Path -LiteralPath (Join-Path $_ 'gradle-home\wrapper\dists\gradle-8.5-bin') -PathType Container) -and
    (Test-Path -LiteralPath (Join-Path $_ 'postgresql\pg16\bin\postgres.exe') -PathType Leaf) -and
    (Test-Path -LiteralPath (Join-Path $_ 'redis\redis-7.4.11\src\redis-server') -PathType Leaf)
} | Select-Object -First 1)
if ($SelectedToolRoots.Count -ne 1) {
    throw 'Part C tooling was not found. Pass -ToolRoot or set PART_C_TOOL_ROOT.'
}
$ToolRoot = $SelectedToolRoots[0]
$JavaHome = @(Get-ChildItem -LiteralPath (Join-Path $ToolRoot 'jdk17') -Directory |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin\java.exe') -PathType Leaf } |
    Select-Object -First 1).FullName
if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    throw "No JDK 17 java.exe was found under $ToolRoot"
}
$Java = Join-Path $JavaHome 'bin\java.exe'
$Gradle = @(Get-ChildItem -LiteralPath (Join-Path $ToolRoot 'gradle-home\wrapper\dists\gradle-8.5-bin') `
    -Filter 'gradle.bat' -File -Recurse | Select-Object -First 1).FullName
if ([string]::IsNullOrWhiteSpace($Gradle)) {
    throw "Gradle 8.5 was not found under $ToolRoot"
}
$GradleUserHome = Join-Path $Repo '.omo\tooling\gradle-home'
$PgBin = Join-Path $ToolRoot 'postgresql\pg16\bin'
$Wsl = Join-Path $env:SystemRoot 'System32\wsl.exe'
$RedisRootWindows = Join-Path $ToolRoot 'redis\redis-7.4.11\src'
$RedisRootLinux = $null

$Ports = [ordered]@{
    FocusedRedis = 16421
    PostgreSql = 55453
    NativeRedis = 16423
    Application = 18103
    Bootstrap = 18104
    WebPushTls = 18443
    SlackTls = 18444
}

$Evidence = if ([IO.Path]::IsPathRooted($EvidenceDir)) {
    [IO.Path]::GetFullPath($EvidenceDir)
}
else {
    [IO.Path]::GetFullPath((Join-Path $Repo $EvidenceDir))
}
$Runtime = Join-Path $Evidence ("runtime-$PID")
$ManifestPath = Join-Path $Evidence 'manifest.json'
$AdversarialPath = Join-Path $Evidence 'adversarial.json'
$JournalPath = Join-Path $Evidence 'runner-journal.txt'
$SourceBeforePath = Join-Path $Evidence 'source-before.sha256'
$SourceAfterPath = Join-Path $Evidence 'source-after.sha256'
$SecretSentinel = Convert-ToLowerHex (New-RandomBytes 24)
$PgPassword = Convert-ToLowerHex (New-RandomBytes 24)
$JwtKey = [Convert]::ToBase64String((New-RandomBytes 32))
$DatabaseKey = [Convert]::ToBase64String((New-RandomBytes 32))
$BootstrapPassword = (Convert-ToLowerHex (New-RandomBytes 24)) + 'Aa1!'
$QaUserPassword = (Convert-ToLowerHex (New-RandomBytes 24)) + 'Bb2!'
$PgUser = 'part_c_native'
$BootstrapDatabase = 'part_c_bootstrap'
$BootstrapEmail = 'part-c-bootstrap@example.test'
$StartedAt = [datetime]::UtcNow
$ExitCode = 1
$PgStarted = $false
$PgData = Join-Path $Runtime 'postgres'
$FocusedRedis = $null
$NativeRedis = $null
$FocusedRedisLinuxPid = 0
$NativeRedisLinuxPid = 0
$OwnedProcesses = [Collections.Generic.List[Diagnostics.Process]]::new()
$PhaseSummaries = [ordered]@{}
$CleanupResults = [Collections.Generic.List[object]]::new()
$AdversarialResults = [Collections.Generic.List[object]]::new()
$OldEnvironment = @{}
$Utf8NoBom = [Text.UTF8Encoding]::new($false)
$TouchedEnvironment = @(
    'JAVA_HOME', 'GRADLE_USER_HOME', 'JAVA_TOOL_OPTIONS', 'PGPASSWORD',
    'SPRING_PROFILES_ACTIVE', 'SPRING_DATASOURCE_URL', 'SPRING_DATASOURCE_USERNAME',
    'SPRING_DATASOURCE_PASSWORD', 'SPRING_REDIS_HOST', 'SPRING_REDIS_PORT',
    'SPRING_REDIS_PASSWORD', 'JWT_SIGNING_KEYS', 'JWT_ACTIVE_KID',
    'DB_CONFIG_ENCRYPTION_KEYS', 'DB_CONFIG_ACTIVE_KEY_VERSION', 'LEGACY_TIME_ZONE',
    'PUBLIC_ORIGIN', 'AUTH_SECURE_COOKIES', 'DB_CONFIG_VERIFY_ON_STARTUP',
    'TARGET_DB_ALLOWED_CIDRS', 'TARGET_DB_ALLOWED_PORTS', 'TARGET_DB_TLS_REQUIRED',
    'APP_COLLECTOR_ENABLED', 'APP_METRICS_RETENTION_CLEANUP_ENABLED',
    'APP_PART_B_RETENTION_CLEANUP_ENABLED', 'APP_OUTBOX_PUBLISHER_ENABLED',
    'APP_OUTBOX_RETENTION_CLEANUP_ENABLED', 'RISK_ENABLED', 'REALTIME_ENABLED',
    'NOTIFICATIONS_ENABLED', 'STAGE3_INTEGRATION_ENABLED', 'STAGE3_APP_PORT',
    'STAGE3_STREAM_KEY', 'STAGE3_DEAD_LETTER_STREAM', 'STAGE3_TEST_PASSWORD',
    'STAGE3_DLQ_SECRET', 'PART_C_NATIVE_QA_ENABLED', 'PART_C_NATIVE_QA_EVIDENCE_DIR',
    'PART_C_NATIVE_QA_APP_PORT', 'PART_C_NATIVE_QA_WEB_PUSH_PORT',
    'PART_C_NATIVE_QA_SLACK_PORT', 'WEB_PUSH_VAPID_PUBLIC_KEY',
    'WEB_PUSH_VAPID_PRIVATE_KEY', 'WEB_PUSH_VAPID_SUBJECT', 'PUSH_ALLOWED_HOSTS',
    'BOOTSTRAP_ADMIN_EMAIL', 'BOOTSTRAP_ADMIN_PASSWORD', 'BOOTSTRAP_ADMIN_DISPLAY_NAME'
)
foreach ($Name in $TouchedEnvironment) {
    $OldEnvironment[$Name] = [Environment]::GetEnvironmentVariable($Name, 'Process')
}

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) {
        throw $Message
    }
}

function Write-Utf8NoBom([string]$Path, [object]$Value) {
    $Text = if ($Value -is [string]) {
        $Value
    }
    else {
        (@($Value) | ForEach-Object { [string]$_ }) -join [Environment]::NewLine
    }
    [IO.File]::WriteAllText($Path, $Text, $Utf8NoBom)
}

function Add-Utf8NoBom([string]$Path, [string]$Value) {
    [IO.File]::AppendAllText($Path, $Value + [Environment]::NewLine, $Utf8NoBom)
}

function Add-Journal([string]$Value) {
    Add-Utf8NoBom $JournalPath $Value
}

function Assert-PathWithin([string]$Root, [string]$Candidate) {
    $ResolvedRoot = [IO.Path]::GetFullPath($Root).TrimEnd('\') + '\'
    $ResolvedCandidate = [IO.Path]::GetFullPath($Candidate)
    Assert-True ($ResolvedCandidate.StartsWith($ResolvedRoot, [StringComparison]::OrdinalIgnoreCase)) `
        "Path is outside the owned root: $ResolvedCandidate"
}

function Get-RelativePath([string]$BasePath, [string]$TargetPath) {
    $BaseFull = [IO.Path]::GetFullPath($BasePath).TrimEnd('\') + '\'
    $TargetFull = [IO.Path]::GetFullPath($TargetPath)
    $BaseUri = New-Object Uri($BaseFull)
    $TargetUri = New-Object Uri($TargetFull)
    return [Uri]::UnescapeDataString($BaseUri.MakeRelativeUri($TargetUri).ToString()).Replace('/', '\')
}

function Test-Port([int]$Port) {
    $Client = [Net.Sockets.TcpClient]::new()
    try {
        $Connect = $Client.ConnectAsync('127.0.0.1', $Port)
        return $Connect.Wait(300) -and $Client.Connected
    }
    catch {
        return $false
    }
    finally {
        $Client.Dispose()
    }
}

function Wait-Port([int]$Port, [bool]$Open, [int]$TimeoutSeconds = 30) {
    $Deadline = [datetime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([datetime]::UtcNow -lt $Deadline) {
        if ((Test-Port $Port) -eq $Open) {
            return $true
        }
        Start-Sleep -Milliseconds 200
    }
    return (Test-Port $Port) -eq $Open
}

function Assert-PortsClosed {
    foreach ($Entry in $Ports.GetEnumerator()) {
        Assert-True (-not (Test-Port $Entry.Value)) `
            "Reserved port $($Entry.Value) ($($Entry.Key)) is already open"
    }
}

function Assert-FrozenSource {
    $Actual = (& git -c "safe.directory=$Repo" -C $Repo rev-parse HEAD).Trim()
    Assert-True ($LASTEXITCODE -eq 0) 'Unable to resolve candidate HEAD'
    Assert-True ($Actual -eq $ExpectedHead.ToLowerInvariant()) `
        "Exact HEAD mismatch: expected $ExpectedHead, observed $Actual"
    $Status = @(& git -c "safe.directory=$Repo" -C $Repo status --porcelain --untracked-files=all)
    Assert-True ($LASTEXITCODE -eq 0) 'Unable to inspect candidate worktree'
    Assert-True ($Status.Count -eq 0) ('Candidate worktree is dirty: ' + ($Status -join '; '))
    return $Actual
}

function Save-SourceManifest([string]$Destination) {
    $Paths = @(& git -c "safe.directory=$Repo" -c core.quotepath=false -C $Repo ls-files)
    Assert-True ($LASTEXITCODE -eq 0) 'Unable to enumerate tracked source'
    $Lines = foreach ($Relative in @($Paths | Sort-Object -Unique)) {
        $Full = Join-Path $Repo $Relative
        Assert-True (Test-Path -LiteralPath $Full -PathType Leaf) "Tracked source is missing: $Relative"
        $Hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $Full).Hash.ToLowerInvariant()
        "$Hash *$($Relative.Replace('\', '/'))"
    }
    Write-Utf8NoBom $Destination $Lines
    return [ordered]@{
        fileCount = $Lines.Count
        sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $Destination).Hash.ToLowerInvariant()
    }
}

function Invoke-BoundedProcess(
    [string]$Name,
    [string]$FilePath,
    [string[]]$Arguments,
    [string]$StdoutPath,
    [string]$StderrPath,
    [int]$TimeoutSeconds,
    [int[]]$AllowedExitCodes = @(0)
) {
    Assert-PathWithin $Evidence $StdoutPath
    Assert-PathWithin $Evidence $StderrPath
    Remove-Item -Force -LiteralPath $StdoutPath, $StderrPath -ErrorAction SilentlyContinue
    Add-Journal "COMMAND_START name=$Name utc=$([datetime]::UtcNow.ToString('o')) file=$FilePath args=$($Arguments -join ' ')"
    $Process = Start-Process -FilePath $FilePath -ArgumentList $Arguments -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $StdoutPath -RedirectStandardError $StderrPath
    $OwnedProcesses.Add($Process)
    if (-not $Process.WaitForExit($TimeoutSeconds * 1000)) {
        Stop-Process -Force -Id $Process.Id -ErrorAction SilentlyContinue
        [void]$Process.WaitForExit(5000)
        throw "Command timed out after $TimeoutSeconds seconds: $Name"
    }
    $Process.WaitForExit()
    $Process.Refresh()
    Add-Journal "COMMAND_END name=$Name utc=$([datetime]::UtcNow.ToString('o')) pid=$($Process.Id) exit=$($Process.ExitCode)"
    Assert-True ($Process.ExitCode -in $AllowedExitCodes) `
        "$Name exited $($Process.ExitCode); allowed: $($AllowedExitCodes -join ',')"
    return [ordered]@{
        name = $Name
        pid = $Process.Id
        exitCode = $Process.ExitCode
        stdout = (Get-RelativePath $Evidence $StdoutPath).Replace('\', '/')
        stderr = (Get-RelativePath $Evidence $StderrPath).Replace('\', '/')
    }
}

function Remove-TestResults {
    $Root = Join-Path $Backend 'build\test-results\test'
    Assert-PathWithin $Repo $Root
    if (Test-Path -LiteralPath $Root) {
        Remove-Item -Force -Recurse -LiteralPath $Root
    }
}

function Save-FreshJUnit(
    [string]$Phase,
    [datetime]$Started,
    [datetime]$Ended
) {
    $Source = Join-Path $Backend 'build\test-results\test'
    $Destination = Join-Path $Evidence ("junit-$Phase")
    New-Item -ItemType Directory -Force -Path $Destination | Out-Null
    $Reports = @(Get-ChildItem -LiteralPath $Source -Filter 'TEST-*.xml' -File)
    Assert-True ($Reports.Count -gt 0) "No JUnit XML was produced for $Phase"
    $Executed = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $Skipped = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $Tests = 0
    $Failures = 0
    $Errors = 0
    $SkippedCount = 0
    foreach ($Report in $Reports) {
        $Fresh = $Report.LastWriteTimeUtc -ge $Started.AddSeconds(-2) `
            -and $Report.LastWriteTimeUtc -le $Ended.AddSeconds(2)
        Assert-True $Fresh "Stale JUnit XML in ${Phase}: $($Report.Name)"
        Copy-Item -Force -LiteralPath $Report.FullName -Destination (Join-Path $Destination $Report.Name)
        [xml]$Xml = Get-Content -Raw -LiteralPath $Report.FullName
        $Tests += [int]$Xml.testsuite.tests
        $Failures += [int]$Xml.testsuite.failures
        $Errors += [int]$Xml.testsuite.errors
        $SkippedCount += [int]$Xml.testsuite.skipped
        foreach ($Case in @($Xml.testsuite.testcase)) {
            $Id = "$($Case.classname)::$($Case.name)"
            if ($null -eq $Case.skipped) {
                [void]$Executed.Add($Id)
            }
            else {
                [void]$Skipped.Add($Id)
            }
        }
    }
    Assert-True ($Failures -eq 0 -and $Errors -eq 0) "$Phase contains JUnit failures or errors"
    Assert-True ($Executed.Count -eq ($Tests - $SkippedCount)) `
        "$Phase executed test IDs are not unique or complete"
    Assert-True ($Skipped.Count -eq $SkippedCount) `
        "$Phase skipped test IDs are not unique or complete"
    Write-Utf8NoBom (Join-Path $Evidence "$Phase-executed-test-ids.txt") `
        @($Executed | Sort-Object)
    Write-Utf8NoBom (Join-Path $Evidence "$Phase-skipped-test-ids.txt") `
        @($Skipped | Sort-Object)
    return [ordered]@{
        tests = $Tests
        failures = $Failures
        errors = $Errors
        skipped = $SkippedCount
        executedUnique = $Executed.Count
        skippedUnique = $Skipped.Count
        executedIds = @($Executed | Sort-Object)
        skippedIds = @($Skipped | Sort-Object)
        directory = (Get-RelativePath $Evidence $Destination).Replace('\', '/')
    }
}

function Invoke-GradlePhase([string]$Phase, [string[]]$Arguments) {
    Assert-FrozenSource | Out-Null
    Remove-TestResults
    $Started = [datetime]::UtcNow
    $Out = Join-Path $Evidence "$Phase-gradle.out.log"
    $Err = Join-Path $Evidence "$Phase-gradle.err.log"
    $Result = Invoke-BoundedProcess $Phase $Gradle $Arguments $Out $Err $CommandTimeoutSeconds
    $Ended = [datetime]::UtcNow
    $JUnit = Save-FreshJUnit $Phase $Started $Ended
    Assert-FrozenSource | Out-Null
    $Summary = [ordered]@{
        startedAt = $Started.ToString('o')
        endedAt = $Ended.ToString('o')
        process = $Result
        junit = $JUnit
    }
    $PhaseSummaries[$Phase] = $Summary
    return $Summary
}

function Assert-NormalPartCCoverage([object]$JUnit) {
    $AllIds = @($JUnit.executedIds) + @($JUnit.skippedIds)
    $RequiredClasses = @(
        'com.example.monitoring.risk.service.RiskMetricTransactionIntegrationTest',
        'com.example.monitoring.risk.scheduler.RiskStaleTransactionIntegrationTest',
        'com.example.monitoring.notification.scheduling.NotificationSchedulingIntegrationTest',
        'com.example.monitoring.notification.delivery.DeliveryWorkerIntegrationTest',
        'com.example.monitoring.notification.session.PushSubscriptionSessionIntegrationTest',
        'com.example.monitoring.retention.PartCRetentionIntegrationTest',
        'com.example.monitoring.integration.PartCHttpContractTest',
        'com.example.monitoring.realtime.integration.PartCNativeQaTest',
        'com.example.monitoring.notification.integration.PartCProviderNativeQaTest'
    )
    $Coverage = [ordered]@{}
    $KnownNewTests = 0
    foreach ($ClassName in $RequiredClasses) {
        $Count = @($AllIds | Where-Object { $_.StartsWith("${ClassName}::", [StringComparison]::Ordinal) }).Count
        Assert-True ($Count -gt 0) "Normal suite did not discover required Part C class: $ClassName"
        $Coverage[$ClassName] = $Count
        $KnownNewTests += $Count
    }
    $RequiredTotal = $MinimumNormalTests + $KnownNewTests
    Assert-True ($JUnit.tests -ge $RequiredTotal) `
        "Normal suite discovered $($JUnit.tests); expected baseline $MinimumNormalTests plus at least $KnownNewTests required new Part C cases"
    return [ordered]@{
        baselineTests = $MinimumNormalTests
        requiredNewPartCTests = $KnownNewTests
        minimumExpectedTotal = $RequiredTotal
        discoveredTotal = $JUnit.tests
        classes = $Coverage
    }
}

function Convert-ToWslPath([string]$WindowsPath) {
    $Full = [IO.Path]::GetFullPath($WindowsPath)
    Assert-True ($Full -match '^(?<drive>[A-Za-z]):\\(?<rest>.*)$') "Cannot map path into WSL: $Full"
    return '/mnt/' + $Matches.drive.ToLowerInvariant() + '/' + $Matches.rest.Replace('\', '/')
}

function Invoke-WslControl(
    [string]$Name,
    [string[]]$Arguments,
    [int[]]$AllowedExitCodes = @(0)
) {
    return Invoke-BoundedProcess $Name $Wsl (@('-d', 'Ubuntu', '--') + $Arguments) `
        (Join-Path $Evidence "$Name.out.log") (Join-Path $Evidence "$Name.err.log") 15 $AllowedExitCodes
}

function Test-WslPid([string]$Name, [int]$LinuxPid) {
    if ($LinuxPid -le 0) {
        return $false
    }
    try {
        $Probe = Invoke-WslControl "$Name-pid-probe" @('kill', '-0', [string]$LinuxPid) @(0, 1)
        return $Probe.exitCode -eq 0
    }
    catch {
        Add-Journal "WSL_PID_PROBE_ERROR name=$Name pid=$LinuxPid error=$($_.Exception.Message)"
        return $true
    }
}

function Start-Redis([string]$Name, [int]$Port) {
    $Data = Join-Path $Runtime $Name
    New-Item -ItemType Directory -Force -Path $Data | Out-Null
    $Config = Join-Path $Runtime "$Name.conf"
    $PidFile = Join-Path $Data 'redis.pid'
    $ConfigLines = @(
        "port $Port",
        'bind 127.0.0.1',
        'protected-mode yes',
        'daemonize no',
        'appendonly no',
        'save ""',
        "dir $(Convert-ToWslPath $Data)",
        "pidfile $(Convert-ToWslPath $PidFile)",
        'logfile ""'
    )
    Write-Utf8NoBom $Config $ConfigLines
    $Out = Join-Path $Evidence "$Name.out.log"
    $Err = Join-Path $Evidence "$Name.err.log"
    $Process = Start-Process -FilePath $Wsl -ArgumentList @(
        '-d', 'Ubuntu', '--', "$RedisRootLinux/redis-server", (Convert-ToWslPath $Config)
    ) -WindowStyle Hidden -PassThru -RedirectStandardOutput $Out -RedirectStandardError $Err
    $OwnedProcesses.Add($Process)
    Assert-True (Wait-Port $Port $true 30) "$Name did not open port $Port"
    $LinuxPid = 0
    $PidDeadline = [datetime]::UtcNow.AddSeconds(5)
    while ($LinuxPid -le 0 -and [datetime]::UtcNow -lt $PidDeadline) {
        if (Test-Path -LiteralPath $PidFile) {
            [void][int]::TryParse((Get-Content -Raw -LiteralPath $PidFile).Trim(), [ref]$LinuxPid)
        }
        if ($LinuxPid -le 0) {
            Start-Sleep -Milliseconds 100
        }
    }
    Assert-True ($LinuxPid -gt 0) "$Name did not publish its Linux PID"
    Add-Journal "SERVICE_START name=$Name wrapperPid=$($Process.Id) linuxPid=$LinuxPid port=$Port"
    return [ordered]@{ process = $Process; linuxPid = $LinuxPid; port = $Port; pidFile = $PidFile }
}

function Stop-Redis([string]$Name, [object]$Handle) {
    if ($null -eq $Handle) {
        return
    }
    try {
        if (Test-Port $Handle.port) {
            [void](Invoke-WslControl "$Name-shutdown" @(
                "$RedisRootLinux/redis-cli", '-h', '127.0.0.1', '-p',
                [string]$Handle.port, 'shutdown', 'nosave'
            ) @(0, 1))
        }
        [void]$Handle.process.WaitForExit(10000)
        if (Test-WslPid $Name $Handle.linuxPid) {
            [void](Invoke-WslControl "$Name-term" @('kill', '-TERM', [string]$Handle.linuxPid) @(0, 1))
            [void]$Handle.process.WaitForExit(10000)
        }
        if (Test-WslPid $Name $Handle.linuxPid) {
            [void](Invoke-WslControl "$Name-kill" @('kill', '-KILL', [string]$Handle.linuxPid) @(0, 1))
            [void]$Handle.process.WaitForExit(5000)
        }
        if (-not $Handle.process.HasExited) {
            Stop-Process -Force -Id $Handle.process.Id -ErrorAction SilentlyContinue
            [void]$Handle.process.WaitForExit(5000)
        }
        $Closed = Wait-Port $Handle.port $false 20
        $LinuxAlive = Test-WslPid $Name $Handle.linuxPid
        $CleanupResults.Add([ordered]@{
            resource = $Name
            wrapperPid = $Handle.process.Id
            linuxPid = $Handle.linuxPid
            port = $Handle.port
            closed = $Closed
            linuxPidExited = -not $LinuxAlive
        })
        if ($LinuxAlive) {
            $script:ExitCode = 1
        }
    }
    catch {
        $script:ExitCode = 1
        $CleanupResults.Add([ordered]@{ resource = $Name; port = $Handle.port; closed = $false; error = $_.Exception.Message })
    }
}

function Start-Postgres {
    $Data = $PgData
    $PasswordFile = Join-Path $Runtime 'postgres-password.txt'
    New-Item -ItemType Directory -Force -Path $Data | Out-Null
    Set-Content -Encoding ascii -LiteralPath $PasswordFile -Value $PgPassword
    $Init = Invoke-BoundedProcess 'postgres-initdb' (Join-Path $PgBin 'initdb.exe') @(
        '-D', $Data, '-U', $PgUser, "--pwfile=$PasswordFile", '--auth-host=scram-sha-256',
        '--auth-local=trust', '--encoding=UTF8', '--no-locale'
    ) (Join-Path $Evidence 'postgres-initdb.out.log') (Join-Path $Evidence 'postgres-initdb.err.log') 120
    $Start = Invoke-BoundedProcess 'postgres-start' (Join-Path $PgBin 'pg_ctl.exe') @(
        '-D', $Data, '-l', (Join-Path $Evidence 'postgres.log'), '-w', '-t', '30',
        '-o', "-h 127.0.0.1 -p $($Ports.PostgreSql)", 'start'
    ) (Join-Path $Evidence 'postgres-start.out.log') (Join-Path $Evidence 'postgres-start.err.log') 45
    $script:PgStarted = $true
    Assert-True (Wait-Port $Ports.PostgreSql $true 30) 'PostgreSQL did not open its reserved port'
    $PidFile = Join-Path $Data 'postmaster.pid'
    $ServerPid = if (Test-Path $PidFile) { [int](Get-Content -LiteralPath $PidFile -TotalCount 1) } else { 0 }
    Add-Journal "SERVICE_START name=postgres pid=$ServerPid port=$($Ports.PostgreSql)"
    return [ordered]@{ data = $Data; pid = $ServerPid; init = $Init; start = $Start }
}

function Stop-Postgres([object]$Handle) {
    if (-not $PgStarted) {
        return
    }
    $Data = if ($null -eq $Handle) { $PgData } else { $Handle.data }
    $ServerPid = if ($null -eq $Handle) { 0 } else { $Handle.pid }
    $StopFailure = $null
    try {
        $Result = Invoke-BoundedProcess 'postgres-stop' (Join-Path $PgBin 'pg_ctl.exe') @(
            '-D', $Data, '-m', 'fast', '-w', '-t', '30', 'stop'
        ) (Join-Path $Evidence 'postgres-stop.out.log') (Join-Path $Evidence 'postgres-stop.err.log') 45
    }
    catch {
        $StopFailure = $_.Exception.Message
        $Result = $null
    }
    try {
        $Closed = Wait-Port $Ports.PostgreSql $false 20
        if (-not $Closed -and $ServerPid -gt 0) {
            $PidFile = Join-Path $Data 'postmaster.pid'
            $RecordedPid = 0
            if (Test-Path -LiteralPath $PidFile) {
                [void][int]::TryParse(
                    (Get-Content -LiteralPath $PidFile -TotalCount 1),
                    [ref]$RecordedPid)
            }
            if ($RecordedPid -eq $ServerPid) {
                $ServerProcess = Get-Process -Id $ServerPid -ErrorAction SilentlyContinue
                if ($null -ne $ServerProcess -and $ServerProcess.ProcessName -eq 'postgres') {
                    Stop-Process -Force -Id $ServerPid -ErrorAction SilentlyContinue
                    [void]$ServerProcess.WaitForExit(5000)
                }
            }
            $Closed = Wait-Port $Ports.PostgreSql $false 20
        }
    }
    catch {
        $Closed = $false
        $StopFailure = if ($null -eq $StopFailure) {
            $_.Exception.Message
        }
        else {
            "$StopFailure; $($_.Exception.Message)"
        }
    }
    $CleanupResults.Add([ordered]@{
        resource = 'postgres'
        pid = $ServerPid
        port = $Ports.PostgreSql
        closed = $Closed
        process = $Result
        error = $StopFailure
    })
    if ($Closed) {
        $script:PgStarted = $false
    }
    else {
        $script:ExitCode = 1
    }
}

function Save-ToolVersions {
    $Tools = [ordered]@{}
    foreach ($Spec in @(
        [ordered]@{ name = 'java'; file = $Java; arguments = @('-version') },
        [ordered]@{ name = 'gradle'; file = $Gradle; arguments = @('--version', '--no-daemon') },
        [ordered]@{ name = 'postgres'; file = (Join-Path $PgBin 'postgres.exe'); arguments = @('--version') },
        [ordered]@{ name = 'redis'; file = $Wsl; arguments = @('-d', 'Ubuntu', '--', "$RedisRootLinux/redis-server", '--version') }
    )) {
        $Out = Join-Path $Evidence "$($Spec.name)-version.out.txt"
        $Err = Join-Path $Evidence "$($Spec.name)-version.err.txt"
        $Result = Invoke-BoundedProcess "$($Spec.name)-version" $Spec.file $Spec.arguments $Out $Err 30
        $Text = ((Get-Content -Raw -LiteralPath $Out -ErrorAction SilentlyContinue) + "`n" +
            (Get-Content -Raw -LiteralPath $Err -ErrorAction SilentlyContinue)).Trim()
        Assert-True (-not [string]::IsNullOrWhiteSpace($Text)) "$($Spec.name) version output is empty"
        $Tools[$Spec.name] = [ordered]@{ process = $Result; output = $Text }
    }
    return $Tools
}

function Get-BootJar {
    $Jars = @(Get-ChildItem -LiteralPath (Join-Path $Backend 'build\libs') -Filter '*.jar' -File |
        Where-Object { $_.Name -notmatch '-plain\.jar$' })
    Assert-True ($Jars.Count -eq 1) "Expected one executable boot jar, found $($Jars.Count)"
    Assert-True ($Jars[0].Length -gt 0) 'Executable boot jar is empty'
    return [ordered]@{
        path = $Jars[0].FullName
        relativePath = $Jars[0].FullName.Substring($Repo.Length + 1).Replace('\', '/')
        bytes = $Jars[0].Length
        sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $Jars[0].FullName).Hash.ToLowerInvariant()
    }
}

function Invoke-Bootstrap([string]$Label, [string]$Jar, [bool]$ShouldSucceed) {
    $Out = Join-Path $Evidence "bootstrap-$($Label.ToLowerInvariant()).out.log"
    $Err = Join-Path $Evidence "bootstrap-$($Label.ToLowerInvariant()).err.log"
    $Process = Start-Process -FilePath $Java -ArgumentList @(
        '-jar', $Jar, '--spring.profiles.active=bootstrap-admin', "--server.port=$($Ports.Bootstrap)"
    ) -WindowStyle Hidden -PassThru -RedirectStandardOutput $Out -RedirectStandardError $Err
    $OwnedProcesses.Add($Process)
    $ListenerObserved = $false
    $Deadline = [datetime]::UtcNow.AddSeconds(90)
    while (-not $Process.HasExited -and [datetime]::UtcNow -lt $Deadline) {
        if (Test-Port $Ports.Bootstrap) {
            $ListenerObserved = $true
        }
        Start-Sleep -Milliseconds 100
        $Process.Refresh()
    }
    if (-not $Process.HasExited) {
        Stop-Process -Force -Id $Process.Id -ErrorAction SilentlyContinue
        [void]$Process.WaitForExit(5000)
        throw "Bootstrap $Label timed out"
    }
    $Process.WaitForExit()
    if ($ShouldSucceed) {
        Assert-True ($Process.ExitCode -eq 0) "Bootstrap $Label exited $($Process.ExitCode), expected zero"
    }
    else {
        Assert-True ($Process.ExitCode -ne 0) "Bootstrap $Label unexpectedly exited zero"
    }
    Assert-True (-not $ListenerObserved) "Bootstrap $Label opened an HTTP listener"
    return [ordered]@{
        label = $Label
        pid = $Process.Id
        exitCode = $Process.ExitCode
        listenerObserved = $ListenerObserved
        stdout = (Get-RelativePath $Evidence $Out).Replace('\', '/')
        stderr = (Get-RelativePath $Evidence $Err).Replace('\', '/')
    }
}

function Invoke-Psql([string]$Database, [string]$Sql, [string]$Name) {
    $Out = Join-Path $Evidence "$Name.out.txt"
    $Err = Join-Path $Evidence "$Name.err.txt"
    $Result = Invoke-BoundedProcess $Name (Join-Path $PgBin 'psql.exe') @(
        '-h', '127.0.0.1', '-p', [string]$Ports.PostgreSql, '-U', $PgUser,
        '-d', $Database, '-X', '-A', '-t', '-v', 'ON_ERROR_STOP=1', '-c', $Sql
    ) $Out $Err 30
    return [ordered]@{ result = $Result; value = (Get-Content -Raw -LiteralPath $Out).Trim() }
}

function Invoke-BootstrapScenario([string]$Jar) {
    $env:PGPASSWORD = $PgPassword
    $Create = Invoke-BoundedProcess 'bootstrap-createdb' (Join-Path $PgBin 'createdb.exe') @(
        '-h', '127.0.0.1', '-p', [string]$Ports.PostgreSql, '-U', $PgUser, $BootstrapDatabase
    ) (Join-Path $Evidence 'bootstrap-createdb.out.log') (Join-Path $Evidence 'bootstrap-createdb.err.log') 30
    $env:SPRING_PROFILES_ACTIVE = 'bootstrap-admin'
    $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://127.0.0.1:$($Ports.PostgreSql)/$BootstrapDatabase"
    $env:BOOTSTRAP_ADMIN_EMAIL = $BootstrapEmail
    $env:BOOTSTRAP_ADMIN_PASSWORD = $BootstrapPassword
    $env:BOOTSTRAP_ADMIN_DISPLAY_NAME = 'Part C Bootstrap Admin'
    $env:RISK_ENABLED = 'false'
    $env:REALTIME_ENABLED = 'false'
    $env:NOTIFICATIONS_ENABLED = 'false'
    $First = Invoke-Bootstrap 'First' $Jar $true
    $FirstText = ((Get-Content -Raw -LiteralPath (Join-Path $Evidence 'bootstrap-first.out.log')) + "`n" +
        (Get-Content -Raw -LiteralPath (Join-Path $Evidence 'bootstrap-first.err.log')))
    Assert-True ($FirstText -match 'Bootstrap administrator created successfully') `
        'First bootstrap omitted its success confirmation'
    $FirstState = Invoke-Psql $BootstrapDatabase @'
SELECT count(*) || '|' || count(*) FILTER (WHERE role='ADMIN') || '|' || count(*) FILTER (WHERE role='ADMIN' AND enabled=true)
FROM users;
'@ 'bootstrap-first-state'
    Assert-True ($FirstState.value -eq '1|1|1') "First bootstrap persisted unexpected state: $($FirstState.value)"
    $Second = Invoke-Bootstrap 'Second' $Jar $false
    $SecondText = ((Get-Content -Raw -LiteralPath (Join-Path $Evidence 'bootstrap-second.out.log')) + "`n" +
        (Get-Content -Raw -LiteralPath (Join-Path $Evidence 'bootstrap-second.err.log')))
    Assert-True ($SecondText -match 'Bootstrap admin can only run when the user table is empty') `
        'Repeated bootstrap omitted its refusal reason'
    $SecondState = Invoke-Psql $BootstrapDatabase @'
SELECT count(*) || '|' || count(*) FILTER (WHERE role='ADMIN') || '|' || count(*) FILTER (WHERE role='ADMIN' AND enabled=true)
FROM users;
'@ 'bootstrap-second-state'
    Assert-True ($SecondState.value -eq $FirstState.value) 'Repeated bootstrap changed persisted state'
    return [ordered]@{
        createDatabase = $Create
        first = $First
        firstState = $FirstState.value
        second = $Second
        secondState = $SecondState.value
        sameJar = $true
        noHttpListener = (-not $First.listenerObserved -and -not $Second.listenerObserved)
    }
}

function Assert-NativeArtifacts {
    $Required = [ordered]@{
        'risk-realtime.json' = @(
            'actualPostgres', 'actualRedis', 'http', 'stomp', 'metricRiskIncidentStatus',
            'notificationScheduledDelivered', 'updatedCredentialFailureSemantics'
        )
        'notification-scheduling.json' = @(
            'actualIncidentStream', 'durableScheduling', 'schedulingRollbackRetried',
            'notificationSharedDeadLetter',
            'localTlsDelivery', 'successReceiptLinked', 'committedBeforeAck'
        )
        'notification-retry-pre-restart.json' = @(
            'providerFailurePersisted', 'absoluteRetryDuePersisted', 'originalExpiryPersisted'
        )
        'notification-retry-lease.json' = @(
            'retryWindowSurvivedRestart', 'originalExpiryPreserved',
            'leaseLossStoppedBeforeHttp', 'replacementLeaseCompleted'
        )
        'timing-restart-replay.json' = @(
            'exactThreshold', 'restartClockReset', 'startupBeforeConsumption',
            'replaySuppressed', 'transactionRollbackRetried', 'heartbeatAcked',
            'oldHeartbeatIsolated', 'sharedDeadLetter', 'stalePollUnderOneSecond'
        )
        'session-retention.json' = @('sessionRevocation', 'retentionBoundary')
        'webpush-tls.json' = @(
            'tlsRequest', 'ciphertextDecrypted', 'vapidValid', 'ssrfRejected', 'noPublicEgress'
        )
        'slack-tls.json' = @(
            'success', 'retry', 'pacing', 'errorClassification', 'timeoutBounded',
            'noLingeringRequest', 'noPublicEgress'
        )
    }
    $Observed = [ordered]@{}
    foreach ($Entry in $Required.GetEnumerator()) {
        $Path = Join-Path $Evidence $Entry.Key
        Assert-True (Test-Path -LiteralPath $Path -PathType Leaf) "Native artifact is missing: $($Entry.Key)"
        Assert-True ((Get-Item -LiteralPath $Path).Length -gt 2) "Native artifact is empty: $($Entry.Key)"
        $Json = Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
        foreach ($Field in $Entry.Value) {
            Assert-True ($Json.$Field -eq $true) "Native artifact $($Entry.Key) did not prove $Field"
        }
        $Observed[$Entry.Key] = [ordered]@{
            bytes = (Get-Item -LiteralPath $Path).Length
            sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
        }
    }
    $Timing = Get-Content -Raw -LiteralPath (Join-Path $Evidence 'timing-restart-replay.json') |
        ConvertFrom-Json
    Assert-True ([long]$Timing.stalePollConfiguredMs -eq 1000L) `
        'Native stale proof did not use the production one-second scan interval'
    Assert-True (
        [long]$Timing.stalePollObservedLagMs -ge 0L -and
        [long]$Timing.stalePollObservedLagMs -lt [long]$Timing.stalePollConfiguredMs
    ) 'Native stale proof did not observe a nonnegative lag below the configured interval'
    $Risk = Get-Content -Raw -LiteralPath (Join-Path $Evidence 'risk-realtime.json') |
        ConvertFrom-Json
    Assert-True ($Risk.actualMariaDbCollector -eq $false) `
        'Native risk evidence must not claim an unexecuted MariaDB collector producer roundtrip'
    Assert-True (
        $Risk.collectorIngress -eq 'persisted canonical metric_data row plus Redis payload'
    ) 'Native risk evidence did not identify its exact collector ingress boundary'
    return $Observed
}

function Assert-SkipReconciliation {
    $Normal = $PhaseSummaries.normal.junit
    Assert-True ($Normal.tests -ge $MinimumNormalTests) `
        "Normal suite discovered $($Normal.tests), below baseline $MinimumNormalTests"
    $Rerun = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($Phase in @('focused-redis', 'native-stage3', 'native-part-c')) {
        foreach ($Id in $PhaseSummaries[$Phase].junit.executedIds) {
            [void]$Rerun.Add($Id)
        }
    }
    $Unexplained = @($Normal.skippedIds | Where-Object { -not $Rerun.Contains($_) } | Sort-Object)
    Write-Utf8NoBom (Join-Path $Evidence 'unexplained-skips.txt') $Unexplained
    Assert-True ($Unexplained.Count -eq 0) `
        ('Normal suite has skipped cases that never executed in a gated rerun: ' + ($Unexplained -join ', '))
    return [ordered]@{
        normalDiscovered = $Normal.tests
        normalExecuted = $Normal.tests - $Normal.skipped
        normalSkipped = $Normal.skipped
        normalSkippedUnique = $Normal.skippedUnique
        gatedExecutedUnique = $Rerun.Count
        unexplainedSkipped = $Unexplained.Count
    }
}

function Scrub-Evidence {
    $FilesScanned = 0
    $FilesRedacted = 0
    $Remaining = 0
    $TextExtensions = @('.txt', '.log', '.json', '.xml', '.sha256', '.out', '.err')
    $Assignment = '(?im)((?:password|secret|token|authorization|cookie|private[_-]?key|p256dh|auth|ciphertext|endpoint|webhook[_-]?url|crypto-key|encryption)\s*[:=]\s*)("[^"\r\n]*"|''[^''\r\n]*''|[^\s,;]+)'
    foreach ($File in @(Get-ChildItem -LiteralPath $Evidence -File -Recurse)) {
        if ($File.Extension.ToLowerInvariant() -notin $TextExtensions) {
            continue
        }
        $FilesScanned++
        $Text = Get-Content -Raw -LiteralPath $File.FullName -ErrorAction SilentlyContinue
        if ($null -eq $Text) {
            continue
        }
        $Updated = $Text.Replace($SecretSentinel, '[REDACTED_SENTINEL]')
        foreach ($Secret in @($PgPassword, $JwtKey, $DatabaseKey, $BootstrapPassword, $QaUserPassword)) {
            $Updated = $Updated.Replace($Secret, '[REDACTED]')
        }
        $Updated = [regex]::Replace($Updated, 'eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+', '[REDACTED_JWT]')
        $Updated = [regex]::Replace($Updated, '(?im)(authorization\s*[:=]\s*bearer\s+)[^\s,;]+', '$1[REDACTED]')
        $Updated = [regex]::Replace($Updated, '(?im)((?:set-cookie|cookie)\s*[:=]\s*)[^\r\n]+', '$1[REDACTED]')
        $Updated = [regex]::Replace($Updated, '(?i)https?://[^\s"''<>]+', '[REDACTED_HTTP_ENDPOINT]')
        $Updated = $Updated.Replace('127.0.0.1', '[REDACTED_ADDRESS]')
        $Updated = [regex]::Replace($Updated, $Assignment, '$1"[REDACTED]"')
        if ($Updated -ne $Text) {
            [IO.File]::WriteAllText($File.FullName, $Updated, [Text.UTF8Encoding]::new($false))
            $FilesRedacted++
        }
        $KnownSecretRemains = @($PgPassword, $JwtKey, $DatabaseKey, $BootstrapPassword, $QaUserPassword) |
            Where-Object { $Updated.Contains($_) }
        if ($Updated.Contains($SecretSentinel) -or
                $KnownSecretRemains.Count -gt 0 -or
                $Updated -match 'eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+' -or
                $Updated -match '(?im)authorization\s*[:=]\s*bearer\s+(?!\[REDACTED\])') {
            $Remaining++
        }
    }
    $Receipt = [ordered]@{
        filesScanned = $FilesScanned
        filesRedacted = $FilesRedacted
        secretMatchesAfterScrub = $Remaining
    }
    Write-Utf8NoBom (Join-Path $Evidence 'secret-scan.json') ($Receipt | ConvertTo-Json)
    Assert-True ($Remaining -eq 0) 'Evidence contains unredacted secret material'
    return $Receipt
}

function Assert-NoGeneratedSecretsInRepository {
    $Secrets = @($SecretSentinel, $PgPassword, $JwtKey, $DatabaseKey, $BootstrapPassword, $QaUserPassword)
    $Matches = 0
    $Tracked = @(& git -c "safe.directory=$Repo" -c core.quotepath=false -C $Repo ls-files)
    $Scanned = 0
    foreach ($Relative in $Tracked) {
        $Path = Join-Path $Repo $Relative
        $Scanned++
        $Text = [Text.Encoding]::UTF8.GetString([IO.File]::ReadAllBytes($Path))
        foreach ($Secret in $Secrets) {
            if ($Text.Contains($Secret)) {
                $Matches++
            }
        }
    }
    $Receipt = [ordered]@{ trackedFilesScanned = $Scanned; generatedSecretMatches = $Matches }
    Write-Utf8NoBom (Join-Path $Evidence 'repository-secret-scan.json') `
        ($Receipt | ConvertTo-Json)
    Assert-True ($Matches -eq 0) 'A generated QA secret appeared in tracked repository source'
    return $Receipt
}

function Save-FinalReports([bool]$Success, [string]$Failure) {
    $Cleanup = [ordered]@{}
    foreach ($Entry in $Ports.GetEnumerator()) {
        $Cleanup[$Entry.Key] = [ordered]@{ port = $Entry.Value; closed = -not (Test-Port $Entry.Value) }
    }
    $Owned = foreach ($Process in @($OwnedProcesses)) {
        try {
            $Process.Refresh()
            [ordered]@{ pid = $Process.Id; alive = -not $Process.HasExited }
        }
        catch {
            [ordered]@{ pid = $Process.Id; alive = $false }
        }
    }
    $Manifest = [ordered]@{
        schemaVersion = 1
        success = $Success
        head = $ExpectedHead.ToLowerInvariant()
        startedAt = $StartedAt.ToString('o')
        endedAt = [datetime]::UtcNow.ToString('o')
        sourceBefore = if (Test-Path $SourceBeforePath) { (Get-FileHash -Algorithm SHA256 -LiteralPath $SourceBeforePath).Hash.ToLowerInvariant() } else { $null }
        sourceAfter = if (Test-Path $SourceAfterPath) { (Get-FileHash -Algorithm SHA256 -LiteralPath $SourceAfterPath).Hash.ToLowerInvariant() } else { $null }
        gradleUserHome = (Get-RelativePath $Repo $GradleUserHome).Replace('\', '/')
        ports = $Ports
        phases = $PhaseSummaries
        cleanup = [ordered]@{ ports = $Cleanup; ownedProcesses = @($Owned); receipts = @($CleanupResults) }
        failure = if ([string]::IsNullOrWhiteSpace($Failure)) { $null } else { $Failure }
    }
    Write-Utf8NoBom $ManifestPath ($Manifest | ConvertTo-Json -Depth 12)
    $Adversarial = [ordered]@{
        schemaVersion = 1
        head = $ExpectedHead.ToLowerInvariant()
        results = @($AdversarialResults)
        cleanup = $Manifest.cleanup
    }
    Write-Utf8NoBom $AdversarialPath ($Adversarial | ConvertTo-Json -Depth 10)
}

function Invoke-CleanupVerification {
    Assert-FrozenSource | Out-Null
    Assert-True (Test-Path -LiteralPath $ManifestPath -PathType Leaf) `
        'Cleanup verification requires the completed run manifest in EvidenceDir'
    $Prior = Get-Content -Raw -LiteralPath $ManifestPath | ConvertFrom-Json
    $PortChecks = [ordered]@{}
    $AllPortsClosed = $true
    foreach ($Entry in $Ports.GetEnumerator()) {
        $Closed = -not (Test-Port $Entry.Value)
        $PortChecks[$Entry.Key] = [ordered]@{ port = $Entry.Value; closed = $Closed }
        if (-not $Closed) {
            $AllPortsClosed = $false
        }
    }
    $RecordedAlive = @($Prior.cleanup.ownedProcesses | Where-Object { $_.alive -eq $true })
    $BadReceipts = [Collections.Generic.List[object]]::new()
    foreach ($Receipt in @($Prior.cleanup.receipts)) {
        foreach ($Field in @('closed', 'linuxPidExited', 'exited', 'removed')) {
            $Property = $Receipt.PSObject.Properties[$Field]
            if ($null -ne $Property -and $Property.Value -ne $true) {
                $BadReceipts.Add([ordered]@{
                    resource = $Receipt.resource
                    field = $Field
                    value = $Property.Value
                })
            }
        }
    }
    $Success = $AllPortsClosed -and $RecordedAlive.Count -eq 0 -and $BadReceipts.Count -eq 0
    $Report = [ordered]@{
        schemaVersion = 1
        success = $Success
        head = $ExpectedHead.ToLowerInvariant()
        checkedAt = [datetime]::UtcNow.ToString('o')
        ports = $PortChecks
        recordedOwnedProcessCount = @($Prior.cleanup.ownedProcesses).Count
        recordedAliveProcessCount = $RecordedAlive.Count
        badCleanupReceipts = @($BadReceipts)
        preservedManifestSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $ManifestPath).Hash.ToLowerInvariant()
    }
    $Path = Join-Path $Evidence 'cleanup-verification.json'
    Write-Utf8NoBom $Path ($Report | ConvertTo-Json -Depth 8)
    Assert-True $Success 'Cleanup verification found an open reserved port or failed recorded cleanup receipt'
    return [ordered]@{ path = $Path; report = $Report }
}

$RedisRootLinux = Convert-ToWslPath $RedisRootWindows
if ($VerifyCleanupOnly) {
    New-Item -ItemType Directory -Force -Path $Evidence | Out-Null
    $Verification = Invoke-CleanupVerification
    Write-Output "EVIDENCE_DIR=$Evidence"
    Write-Output "CLEANUP_VERIFICATION=$($Verification.path)"
    exit 0
}
if (Test-Path -LiteralPath $Evidence) {
    $ExistingEvidence = @(Get-ChildItem -LiteralPath $Evidence -Force)
    Assert-True ($ExistingEvidence.Count -eq 0) `
        'EvidenceDir must be absent or empty for a fresh acceptance run'
}
New-Item -ItemType Directory -Force -Path $Evidence | Out-Null
Assert-PathWithin $Evidence $Runtime
New-Item -ItemType Directory -Force -Path $Runtime | Out-Null
$JournalHeader = @(
    "ATTEMPT_START_UTC=$($StartedAt.ToString('o'))",
    "EXPECTED_HEAD=$($ExpectedHead.ToLowerInvariant())",
    "EVIDENCE_DIR=$Evidence",
    'JOURNAL=The runner owns only processes recorded here and files under this evidence directory.'
)
Write-Utf8NoBom $JournalPath $JournalHeader

$Postgres = $null
try {
    foreach ($Required in @($Java, $Gradle, (Join-Path $PgBin 'initdb.exe'), (Join-Path $PgBin 'pg_ctl.exe'), $Wsl)) {
        Assert-True (Test-Path -LiteralPath $Required -PathType Leaf) "Required tool is missing: $Required"
    }
    New-Item -ItemType Directory -Force -Path $GradleUserHome | Out-Null
    $Head = Assert-FrozenSource
    Assert-PortsClosed
    Assert-True ($env:PART_C_RUNTIME_LEASE -eq 'granted') `
        'PART_C_RUNTIME_LEASE=granted is required from the sole runtime-lease owner'
    $Before = Save-SourceManifest $SourceBeforePath
    $env:JAVA_HOME = $JavaHome
    $env:GRADLE_USER_HOME = $GradleUserHome
    Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
    $PhaseSummaries.tools = Save-ToolVersions
    $AdversarialResults.Add([ordered]@{ scenario = 'exact-sha-clean-source'; passed = $true; observable = $Head })

    foreach ($Name in @(
        'STAGE3_INTEGRATION_ENABLED', 'PART_C_NATIVE_QA_ENABLED',
        'RISK_ENABLED', 'REALTIME_ENABLED', 'NOTIFICATIONS_ENABLED'
    )) {
        Remove-Item "Env:$Name" -ErrorAction SilentlyContinue
    }

    $Normal = Invoke-GradlePhase 'normal' @(
        '-p', $Backend, '--offline', 'test', '--rerun-tasks', '--no-daemon', '--console=plain'
    )
    Assert-True ($Normal.junit.tests -ge $MinimumNormalTests) `
        "Normal suite discovered $($Normal.junit.tests), below baseline $MinimumNormalTests"
    $PhaseSummaries.normalPartCCoverage = Assert-NormalPartCCoverage $Normal.junit

    $BuildOut = Join-Path $Evidence 'bootjar-gradle.out.log'
    $BuildErr = Join-Path $Evidence 'bootjar-gradle.err.log'
    $BuildProcess = Invoke-BoundedProcess 'bootjar' $Gradle @(
        '-p', $Backend, '--offline', 'bootJar', '--rerun-tasks', '--no-daemon', '--console=plain'
    ) $BuildOut $BuildErr $CommandTimeoutSeconds
    $BootJar = Get-BootJar
    $PhaseSummaries.bootJar = [ordered]@{ process = $BuildProcess; artifact = $BootJar }

    $FocusedRedis = Start-Redis 'focused-redis' $Ports.FocusedRedis
    $FocusedRedisLinuxPid = $FocusedRedis.linuxPid
    $PhaseSummaries.focusedRedisService = [ordered]@{
        port = $FocusedRedis.port
        wrapperPid = $FocusedRedis.process.Id
        linuxPid = $FocusedRedis.linuxPid
    }
    $RedisTestProperties = Join-Path $Runtime 'redis-test-properties.gradle'
    Write-Utf8NoBom $RedisTestProperties @"
allprojects {
    tasks.withType(Test).configureEach {
        systemProperty 'realtime.redis.integration', 'true'
        systemProperty 'realtime.redis.port', '$($Ports.FocusedRedis)'
        systemProperty 'partc.stream.integration', 'true'
        systemProperty 'partc.stream.redis.port', '$($Ports.FocusedRedis)'
    }
}
"@
    $Focused = Invoke-GradlePhase 'focused-redis' @(
        '-p', $Backend, '--offline', 'test',
        '--tests', 'com.example.monitoring.realtime.redis.RedisMetricConsumerIntegrationTest',
        '--tests', 'com.example.monitoring.common.stream.RedisStreamWorkerIntegrationTest',
        '--init-script', $RedisTestProperties,
        '--rerun-tasks', '--no-daemon', '--console=plain'
    )
    Assert-True ($Focused.junit.skipped -eq 0) 'Focused Redis rerun contains skipped cases'
    Stop-Redis 'focused-redis' $FocusedRedis
    $FocusedRedis = $null

    $Postgres = Start-Postgres
    $NativeRedis = Start-Redis 'native-redis' $Ports.NativeRedis
    $NativeRedisLinuxPid = $NativeRedis.linuxPid
    $PhaseSummaries.nativeServices = [ordered]@{
        postgres = [ordered]@{ port = $Ports.PostgreSql; pid = $Postgres.pid }
        redis = [ordered]@{ port = $NativeRedis.port; wrapperPid = $NativeRedis.process.Id; linuxPid = $NativeRedis.linuxPid }
    }
    $env:PGPASSWORD = $PgPassword
    $env:SPRING_PROFILES_ACTIVE = 'local'
    $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://127.0.0.1:$($Ports.PostgreSql)/postgres"
    $env:SPRING_DATASOURCE_USERNAME = $PgUser
    $env:SPRING_DATASOURCE_PASSWORD = $PgPassword
    $env:SPRING_REDIS_HOST = '127.0.0.1'
    $env:SPRING_REDIS_PORT = [string]$Ports.NativeRedis
    $env:SPRING_REDIS_PASSWORD = ''
    $env:JWT_SIGNING_KEYS = '{"part-c":"' + $JwtKey + '"}'
    $env:JWT_ACTIVE_KID = 'part-c'
    $env:DB_CONFIG_ENCRYPTION_KEYS = '{"1":"' + $DatabaseKey + '"}'
    $env:DB_CONFIG_ACTIVE_KEY_VERSION = '1'
    $env:LEGACY_TIME_ZONE = 'Asia/Seoul'
    $env:PUBLIC_ORIGIN = 'http://localhost:5173'
    $env:AUTH_SECURE_COOKIES = 'false'
    $env:DB_CONFIG_VERIFY_ON_STARTUP = 'true'
    $env:TARGET_DB_ALLOWED_CIDRS = '127.0.0.0/8'
    $env:TARGET_DB_ALLOWED_PORTS = '3306'
    $env:TARGET_DB_TLS_REQUIRED = 'false'
    $env:APP_COLLECTOR_ENABLED = 'false'
    $env:APP_METRICS_RETENTION_CLEANUP_ENABLED = 'false'
    $env:APP_PART_B_RETENTION_CLEANUP_ENABLED = 'false'
    $env:APP_OUTBOX_PUBLISHER_ENABLED = 'true'
    $env:APP_OUTBOX_RETENTION_CLEANUP_ENABLED = 'false'
    $env:RISK_ENABLED = 'false'
    $env:REALTIME_ENABLED = 'true'
    $env:NOTIFICATIONS_ENABLED = 'false'

    $env:STAGE3_INTEGRATION_ENABLED = 'true'
    $env:STAGE3_APP_PORT = [string]$Ports.Application
    $env:STAGE3_STREAM_KEY = "stream:stage3-native:$PID"
    $env:STAGE3_DEAD_LETTER_STREAM = "stream:stage3-native:$PID:dead-letter"
    $env:STAGE3_TEST_PASSWORD = $QaUserPassword
    $env:STAGE3_DLQ_SECRET = $SecretSentinel
    $Stage3 = Invoke-GradlePhase 'native-stage3' @(
        '-p', $Backend, '--offline', 'test',
        '--tests', 'com.example.monitoring.realtime.integration.RealtimeStage3IntegrationTest',
        '--rerun-tasks', '--no-daemon', '--console=plain'
    )
    Assert-True ($Stage3.junit.skipped -eq 0) 'Native STOMP rerun contains skipped cases'
    Assert-True (-not (Test-Port $Ports.Application)) 'Stage3 application port remained open after Gradle'

    Remove-Item Env:STAGE3_INTEGRATION_ENABLED -ErrorAction SilentlyContinue
    $env:RISK_ENABLED = 'true'
    $env:REALTIME_ENABLED = 'true'
    $env:NOTIFICATIONS_ENABLED = 'true'
    $env:PART_C_NATIVE_QA_ENABLED = 'true'
    $env:PART_C_NATIVE_QA_EVIDENCE_DIR = $Evidence
    $env:PART_C_NATIVE_QA_APP_PORT = [string]$Ports.Application
    $env:PART_C_NATIVE_QA_WEB_PUSH_PORT = [string]$Ports.WebPushTls
    $env:PART_C_NATIVE_QA_SLACK_PORT = [string]$Ports.SlackTls
    $Native = Invoke-GradlePhase 'native-part-c' @(
        '-p', $Backend, '--offline', 'test',
        '--tests', 'com.example.monitoring.realtime.integration.PartCNativeQaTest',
        '--tests', 'com.example.monitoring.notification.integration.PartCProviderNativeQaTest',
        '--rerun-tasks', '--no-daemon', '--console=plain'
    )
    Assert-True ($Native.junit.skipped -eq 0) 'Native Part C rerun contains skipped cases'
    foreach ($RequiredId in @(
        'com.example.monitoring.realtime.integration.PartCNativeQaTest::actualMetricsDriveRiskIncidentsStatusAndStomp()',
        'com.example.monitoring.realtime.integration.PartCNativeQaTest::prepareCandidateForFullApplicationRestart()',
        'com.example.monitoring.realtime.integration.PartCNativeQaTest::exactStaleRestartReplaySessionRevocationAndRetentionFailSafe()',
        'com.example.monitoring.notification.integration.PartCProviderNativeQaTest::webPushTlsCaptureDecryptsCiphertextAndValidatesVapid()',
        'com.example.monitoring.notification.integration.PartCProviderNativeQaTest::slackTlsSuccessRetryPacingAndErrorsAreExact()'
    )) {
        Assert-True ($RequiredId -in $Native.junit.executedIds) "Required native case did not execute: $RequiredId"
    }
    $NativeArtifacts = Assert-NativeArtifacts
    $PhaseSummaries.nativeArtifacts = $NativeArtifacts

    $Bootstrap = Invoke-BootstrapScenario $BootJar.path
    $PhaseSummaries.bootstrap = $Bootstrap
    $Reconciliation = Assert-SkipReconciliation
    $PhaseSummaries.reconciliation = $Reconciliation
    $AdversarialResults.Add([ordered]@{ scenario = 'skip-reconciliation'; passed = $true; observable = $Reconciliation })
    $AdversarialResults.Add([ordered]@{ scenario = 'timing-restart-replay'; passed = $true; artifact = 'timing-restart-replay.json' })
    $AdversarialResults.Add([ordered]@{
        scenario = 'notification-scheduling-retry-restart-lease-loss'
        passed = $true
        artifacts = @(
            'notification-scheduling.json',
            'notification-retry-pre-restart.json',
            'notification-retry-lease.json'
        )
    })
    $AdversarialResults.Add([ordered]@{ scenario = 'session-revocation-retention'; passed = $true; artifact = 'session-retention.json' })
    $AdversarialResults.Add([ordered]@{ scenario = 'provider-ssrf-tls-pacing-errors'; passed = $true; artifacts = @('webpush-tls.json', 'slack-tls.json') })

    Assert-FrozenSource | Out-Null
    $After = Save-SourceManifest $SourceAfterPath
    Assert-True ($Before.sha256 -eq $After.sha256 -and $Before.fileCount -eq $After.fileCount) `
        'Tracked source changed while the frozen QA matrix ran'
    $AdversarialResults.Add([ordered]@{ scenario = 'source-hash-before-after'; passed = $true; observable = $After.sha256 })
    $PhaseSummaries.repositorySecretScan = Assert-NoGeneratedSecretsInRepository
    $ExitCode = 0
}
catch {
    $Failure = $_.Exception.Message
    Add-Journal "SCENARIO_ERROR=$Failure"
    Add-Utf8NoBom $JournalPath ($_ | Out-String)
}
finally {
    Stop-Redis 'focused-redis' $FocusedRedis
    Stop-Redis 'native-redis' $NativeRedis
    Stop-Postgres $Postgres
    foreach ($Process in @($OwnedProcesses)) {
        try {
            $Process.Refresh()
            if (-not $Process.HasExited) {
                Stop-Process -Force -Id $Process.Id -ErrorAction SilentlyContinue
                [void]$Process.WaitForExit(5000)
                $Process.Refresh()
            }
            $Exited = $Process.HasExited
            $CleanupResults.Add([ordered]@{
                resource = "owned-pid:$($Process.Id)"
                exited = $Exited
            })
            if (-not $Exited) {
                $ExitCode = 1
            }
        }
        catch {
            $ExitCode = 1
            $CleanupResults.Add([ordered]@{ resource = "pid:$($Process.Id)"; closed = $false; error = $_.Exception.Message })
        }
    }
    foreach ($Entry in $Ports.GetEnumerator()) {
        $Closed = Wait-Port $Entry.Value $false 15
        $CleanupResults.Add([ordered]@{ resource = $Entry.Key; port = $Entry.Value; closed = $Closed })
        if (-not $Closed) {
            $ExitCode = 1
        }
    }
    try {
        Assert-PathWithin $Evidence $Runtime
        if (Test-Path -LiteralPath $Runtime) {
            Remove-Item -Force -Recurse -LiteralPath $Runtime
        }
        $CleanupResults.Add([ordered]@{ resource = 'runtime-directory'; removed = -not (Test-Path -LiteralPath $Runtime) })
    }
    catch {
        $ExitCode = 1
        $CleanupResults.Add([ordered]@{ resource = 'runtime-directory'; removed = $false; error = $_.Exception.Message })
    }
    foreach ($Name in $OldEnvironment.Keys) {
        if ($null -eq $OldEnvironment[$Name]) {
            Remove-Item "Env:$Name" -ErrorAction SilentlyContinue
        }
        else {
            [Environment]::SetEnvironmentVariable($Name, $OldEnvironment[$Name], 'Process')
        }
    }
    try {
        $AdversarialResults.Add([ordered]@{ scenario = 'owned-process-port-cleanup'; passed = ($ExitCode -eq 0); observable = @($CleanupResults) })
        Save-FinalReports ($ExitCode -eq 0) $Failure
        $SecretReceipt = Scrub-Evidence
        $PhaseSummaries.secretScan = $SecretReceipt
        Save-FinalReports ($ExitCode -eq 0) $Failure
        [void](Scrub-Evidence)
    }
    catch {
        $ExitCode = 1
        $Failure = $_.Exception.Message
        Save-FinalReports $false $Failure
        try { [void](Scrub-Evidence) } catch { }
    }
    Write-Output "EVIDENCE_DIR=$Evidence"
    Write-Output "MANIFEST=$ManifestPath"
    Write-Output "ADVERSARIAL=$AdversarialPath"
    Write-Output "SCENARIO_EXIT_CODE=$ExitCode"
}

exit $ExitCode
