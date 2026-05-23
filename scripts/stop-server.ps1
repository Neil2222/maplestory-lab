param(
    [string]$CosmicDir = "C:\dev\maplestory-lab\Cosmic",
    [string]$MySqlDataDir = "C:\dev\maplestory-lab\mysql-data",
    [int]$GracefulTimeoutSec = 30
)

$ErrorActionPreference = "Stop"
$resolved = Resolve-Path -LiteralPath $CosmicDir -ErrorAction SilentlyContinue

if ($null -eq $resolved) {
    Write-Warning "Cosmic directory not found: $CosmicDir"
    exit 0
}

# Try graceful shutdown via dashboard endpoint first. This saves all online characters
# before exiting, so we don't lose progress to a force-kill.
$dashboardListening = Get-NetTCPConnection -LocalAddress 127.0.0.1 -LocalPort 8787 -State Listen -ErrorAction SilentlyContinue
if ($null -ne $dashboardListening) {
    Write-Host "Requesting graceful shutdown via dashboard (127.0.0.1:8787)..."
    $httpReachedServer = $false
    try {
        # Longer timeout: handler does saveCharToDB synchronously for every online char,
        # which can take a few seconds with many players. We still need to wait on port
        # close afterwards, so this is just the time to receive the OK response.
        $response = Invoke-WebRequest -UseBasicParsing -Method POST -Uri 'http://127.0.0.1:8787/api/action' `
            -ContentType 'application/json' -Body '{"action":"shutdown"}' -TimeoutSec 20
        Write-Host "  $($response.Content)"
        $httpReachedServer = $true
    } catch {
        Write-Warning "Graceful shutdown HTTP call did not return cleanly: $($_.Exception.Message)"
        # The save may still have run server-side even if the response was slow/lost.
        # Wait on port-close anyway before force-killing.
        $httpReachedServer = $true
    }

    if ($httpReachedServer) {
        $deadline = (Get-Date).AddSeconds($GracefulTimeoutSec)
        while ((Get-Date) -lt $deadline) {
            $loginStillUp = Get-NetTCPConnection -LocalAddress 127.0.0.1 -LocalPort 8484 -State Listen -ErrorAction SilentlyContinue
            if ($null -eq $loginStillUp) {
                Write-Host "Server exited cleanly."
                break
            }
            Start-Sleep -Milliseconds 500
        }
    }
}

# Fallback: kill any Cosmic Java process that's still running.
$javaProcesses = Get-CimInstance Win32_Process -Filter "name = 'java.exe' OR name = 'javaw.exe'" |
    Where-Object {
        $_.CommandLine -and (
            $_.CommandLine -like "*$($resolved.Path)*" -or
            $_.CommandLine -like "*Cosmic*" -or
            $_.CommandLine -like "*net.server.Server*"
        )
    }

if (-not $javaProcesses) {
    Write-Host "No matching Cosmic Java process found."
} else {
    foreach ($proc in $javaProcesses) {
        Write-Host "Force-stopping PID $($proc.ProcessId): $($proc.CommandLine)"
        Stop-Process -Id $proc.ProcessId -Force
    }
}

$mysqlData = Resolve-Path -LiteralPath $MySqlDataDir -ErrorAction SilentlyContinue
if ($null -ne $mysqlData) {
    $mysqlProcesses = Get-CimInstance Win32_Process -Filter "name = 'mysqld.exe'" |
        Where-Object { $_.CommandLine -and $_.CommandLine -like "*$($mysqlData.Path)*" }

    foreach ($proc in $mysqlProcesses) {
        Write-Host "Stopping local MySQL PID $($proc.ProcessId): $($proc.CommandLine)"
        Stop-Process -Id $proc.ProcessId -Force
    }
}
