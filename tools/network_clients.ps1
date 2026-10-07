# Launches two isolated development clients from Gradle's exported argument list (no shell parsing).
# Prerequisites: ./gradlew.bat exportClientLaunch -PclientSmoke, then ./gradlew.bat runGameTestServer -PnetworkSmoke in another terminal.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$config = Get-Content -Raw -Encoding UTF8 (Join-Path $root '.tools/client-launch.json') | ConvertFrom-Json
$ready = Join-Path $root 'run-network-smoke/network-ready.txt'
$started = Get-Date
$deadline = $started.AddSeconds(180)
while (-not (Test-Path $ready) -or (Get-Item $ready).LastWriteTime -lt $started.AddSeconds(-30)) {
    if ((Get-Date) -gt $deadline) { throw 'Network server did not become ready' }
    Start-Sleep -Seconds 1
}

# Windows command-line quoting compatible with the MSVC runtime used by java.exe.
function Quote([string]$arg) {
    if ($arg -notmatch '[\s"]') { return $arg }
    $escaped = [regex]::Replace($arg, '(\\*)"', '$1$1\"')
    $escaped = [regex]::Replace($escaped, '(\\+)$', '$1$1')
    return '"' + $escaped + '"'
}

foreach ($property in $config.environment.PSObject.Properties) { Set-Item -Path ("env:" + $property.Name) -Value $property.Value }
$processes = @()
try {
    foreach ($role in 'A', 'B') {
        $folder = Join-Path $root ('run-client-network-' + $role.ToLower())
        New-Item -ItemType Directory -Force $folder | Out-Null
        Remove-Item -ErrorAction SilentlyContinue (Join-Path $folder 'smoke-result.txt')
        # Explicitly test the French UI with a known viewport and render distance.
        [IO.File]::WriteAllText((Join-Path $folder 'options.txt'), "version:3465`nonboardAccessibility:false`nlang:fr_fr`nguiScale:3`nrenderDistance:8`nsimulationDistance:5`npauseOnLostFocus:false`n")
        $command = [Collections.Generic.List[string]]::new([string[]]$config.command)
        $command.Insert(1, "-Dannocraft1800.networkRole=$role")
        if (-not ($command | Where-Object { $_ -like '-Dannocraft1800.clientSmoke=*' })) { $command.Insert(1, '-Dannocraft1800.clientSmoke=true') }
        $command.Add('--username'); $command.Add("AnnoTester$role")
        $arguments = ($command | Select-Object -Skip 1 | ForEach-Object { Quote $_ }) -join ' '
        $processes += Start-Process -FilePath $command[0] -ArgumentList $arguments -WorkingDirectory $folder -PassThru -WindowStyle Normal `
            -RedirectStandardOutput (Join-Path $folder 'launch.log') -RedirectStandardError (Join-Path $folder 'launch-error.log')
    }
    $deadline = (Get-Date).AddSeconds(300)
    while (($processes | Where-Object { -not $_.HasExited }) -and (Get-Date) -lt $deadline) { Start-Sleep -Seconds 1 }
    foreach ($role in 'A', 'B') {
        $result = Join-Path $root ('run-client-network-' + $role.ToLower() + '/smoke-result.txt')
        if (-not (Test-Path $result) -or -not (Get-Content -Raw $result).StartsWith('PASS')) { throw "Client $role failed; inspect its launch.log and smoke-result.txt" }
    }
    $serverResult = Join-Path $root 'run-network-smoke/network-result.txt'
    $serverDeadline = (Get-Date).AddSeconds(30)
    while (-not (Test-Path $serverResult) -and (Get-Date) -lt $serverDeadline) { Start-Sleep -Seconds 1 }
    if (-not (Test-Path $serverResult) -or -not (Get-Content -Raw $serverResult).StartsWith('PASS')) { throw 'Server network assertions did not complete' }
    Write-Output 'PASS: both graphical clients and the loopback server completed the network smoke test.'
} finally {
    foreach ($process in $processes) { if (-not $process.HasExited) { $process.Kill(); $process.WaitForExit(20000) | Out-Null } }
}
