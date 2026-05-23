param(
    [string]$CosmicDir = "C:\dev\maplestory-lab\Cosmic",
    [string]$MySqlDataDir = "C:\dev\maplestory-lab\mysql-data"
)

$ErrorActionPreference = "Stop"
$resolved = Resolve-Path -LiteralPath $CosmicDir -ErrorAction SilentlyContinue

if ($null -eq $resolved) {
    Write-Warning "Cosmic directory not found: $CosmicDir"
    exit 0
}

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
        Write-Host "Stopping PID $($proc.ProcessId): $($proc.CommandLine)"
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
