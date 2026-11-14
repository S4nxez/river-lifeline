# Runs the full stack locally for development (IntelliJ run configuration "Local stack", or any terminal):
#   1. builds the backend jar and starts the stack (podman-compose.yml + compose.build.yml, http://127.0.0.1:8091)
#   2. starts the `front` service of compose.dev.yml on port 5500: the frontend straight from disk, so a page reload
#      shows every edit, with /api proxied to the stack. It listens on every interface, so a phone on the same
#      network or tailnet reaches it too
#   3. opens http://localhost:5500 once the API answers and follows the backend logs
# Stopping the script in any way (IntelliJ's Stop button, Ctrl+C, closing the window) runs `docker compose down` for
# both. The MySQL data volume is kept; add -v by hand to wipe it.
#
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts/local-stack.ps1

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $root

$stackArgs = @('compose', '-f', 'podman-compose.yml', '-f', 'compose.build.yml')
# Its own project name, so that the two compose files do not see each other's containers as orphans
$frontArgs = @('compose', '-p', 'river-lifeline-front', '-f', 'compose.dev.yml')
$frontUrl = 'http://localhost:5500'
$downLog = Join-Path $env:TEMP 'river-lifeline-down.log'

function Stop-OnFailure([string] $step) {
    if ($LASTEXITCODE -ne 0) {
        Write-Host "FAILED: $step (exit code $LASTEXITCODE)" -ForegroundColor Red
        exit 1
    }
}

# Watcher that brings everything down when this script ends. It is started through WMI so that it is not a child of
# this process: IntelliJ's Stop kills the whole process tree, which would take a plain child process down with it.
$q = { param($s) "'" + ($s -replace "'", "''") + "'" }
$watcher = @"
Wait-Process -Id $PID -ErrorAction SilentlyContinue
foreach (`$compose in $(& $q (($frontArgs + 'down') -join ' ')), $(& $q (($stackArgs + 'down') -join ' '))) {
    Start-Process docker -ArgumentList `$compose -WorkingDirectory $(& $q $root) ``
        -RedirectStandardError $(& $q $downLog) -WindowStyle Hidden -Wait
}
"@
$encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($watcher))
$null = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
    CommandLine      = "powershell.exe -NoProfile -NonInteractive -EncodedCommand $encoded"
    CurrentDirectory = $root
}

Write-Host '==> Building the backend jar' -ForegroundColor Cyan
Push-Location server
& .\mvnw.cmd -q -DskipTests package
Pop-Location
Stop-OnFailure 'mvnw package'

Write-Host '==> Starting the stack' -ForegroundColor Cyan
& docker @stackArgs up -d --build --remove-orphans
Stop-OnFailure 'docker compose up (stack)'

Write-Host "==> Frontend from disk on $frontUrl (reload the page to see edits; port 5500 from other devices)" `
    -ForegroundColor Cyan
$env:BACKEND = 'host.docker.internal:8091'
$env:FRONT_BIND = '0.0.0.0'
& docker @frontArgs up -d front
Stop-OnFailure 'docker compose up (front)'

$null = Start-Job -ArgumentList $frontUrl -ScriptBlock {
    param($url)
    $deadline = (Get-Date).AddMinutes(4)
    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -TimeoutSec 5 "$url/api/communities"
            if ($response.StatusCode -eq 200) { Start-Process $url; return }
        } catch { }
        Start-Sleep -Seconds 2
    }
}

Write-Host '==> Backend logs (Stop shuts everything down)' -ForegroundColor Cyan
& docker @stackArgs logs -f --no-log-prefix backend

# The log stream ends when the backend container stops for good: keep the stack up so the logs can be read
Write-Host 'The backend container stopped. Press Stop to bring the stack down.' -ForegroundColor Yellow
while ($true) { Start-Sleep -Seconds 3600 }
