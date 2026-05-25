param(
    [string]$CosmicDir = "C:\dev\maplestory-lab\Cosmic",
    [string]$JavaHome = "C:\Program Files\Amazon Corretto\jdk21.0.11_10",
    [string]$MySqlBin = "C:\Program Files\MySQL\MySQL Server 8.4\bin",
    [string]$MySqlDataDir = "C:\dev\maplestory-lab\mysql-data",
    [switch]$Build
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $CosmicDir)) {
    throw "Cosmic directory not found: $CosmicDir. Clone https://github.com/P0nk/Cosmic.git first."
}

$configPath = Join-Path $CosmicDir "config.yaml"
if (-not (Test-Path -LiteralPath $configPath)) {
    throw "config.yaml not found in $CosmicDir."
}

$configText = Get-Content -LiteralPath $configPath -Raw
foreach ($required in @("HOST: 127.0.0.1", "LANHOST: 127.0.0.1", "LOCALHOST: 127.0.0.1")) {
    if ($configText -notmatch [regex]::Escape($required)) {
        Write-Warning "Expected localhost binding not found exactly: $required. Verify config.yaml before exposing anything."
    }
}

$cosmicListener = Get-NetTCPConnection -LocalAddress 127.0.0.1 -LocalPort 8484 -State Listen -ErrorAction SilentlyContinue
if ($null -ne $cosmicListener) {
    Write-Host "Cosmic lijkt al te draaien op 127.0.0.1:8484. Je kunt nu de v83 client starten."
    exit 0
}

$java = Join-Path $JavaHome "bin\java.exe"
if (-not (Test-Path -LiteralPath $java)) {
    throw "Java not found: $java"
}
$env:JAVA_HOME = $JavaHome
$env:PATH = "$JavaHome\bin;$env:PATH"

$mysqld = Join-Path $MySqlBin "mysqld.exe"
$mysql = Join-Path $MySqlBin "mysql.exe"
if (-not (Test-Path -LiteralPath $mysqld)) {
    throw "mysqld.exe not found: $mysqld"
}
if (-not (Test-Path -LiteralPath $mysql)) {
    throw "mysql.exe not found: $mysql"
}

$mysqlBaseDir = Split-Path (Split-Path $mysqld -Parent) -Parent
$mysqlDataReady = (Test-Path -LiteralPath $MySqlDataDir) -and
    (Test-Path -LiteralPath (Join-Path $MySqlDataDir "mysql"))

if (-not $mysqlDataReady) {
    if (-not (Test-Path -LiteralPath $MySqlDataDir)) {
        New-Item -ItemType Directory -Force -Path $MySqlDataDir | Out-Null
    }
    Write-Host "Initializing MySQL data directory..."
    & $mysqld "--initialize-insecure" "--basedir=$mysqlBaseDir" "--datadir=$MySqlDataDir" "--console"
    if ($LASTEXITCODE -ne 0) {
        throw "MySQL initialization failed with exit code $LASTEXITCODE."
    }
}

$mysqlListener = Get-NetTCPConnection -LocalAddress 127.0.0.1 -LocalPort 3307 -State Listen -ErrorAction SilentlyContinue
if ($null -eq $mysqlListener) {
    Write-Host "Starting local MySQL on 127.0.0.1:3307"
    $mysqlLogDir = Split-Path $MySqlDataDir -Parent
    $mysqlErrLog = Join-Path $mysqlLogDir "mysql-error.log"
    $mysqlArgs = "--basedir=`"$mysqlBaseDir`" --datadir=`"$MySqlDataDir`" --bind-address=127.0.0.1 --port=3307 --mysqlx=0 --log-error=`"$mysqlErrLog`""
    $mysqlProc = Start-Process -FilePath $mysqld -ArgumentList $mysqlArgs -WorkingDirectory $mysqlLogDir -NoNewWindow -PassThru

    $ready = $false
    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep -Seconds 1
        if ($mysqlProc.HasExited) {
            Write-Host "MySQL process exited with code $($mysqlProc.ExitCode)"
            if (Test-Path $mysqlErrLog) {
                Write-Host "--- MySQL error log ---"
                Get-Content $mysqlErrLog -Tail 20 | ForEach-Object { Write-Host $_ }
            }
            throw "MySQL failed to start."
        }
        $listening = Get-NetTCPConnection -LocalAddress 127.0.0.1 -LocalPort 3307 -State Listen -ErrorAction SilentlyContinue
        if ($null -ne $listening) { $ready = $true; break }
    }
    if (-not $ready) {
        throw "MySQL did not start within 30 seconds."
    }
    Write-Host "MySQL is ready."
}

& $mysql -h 127.0.0.1 -P 3307 -u root -e "CREATE DATABASE IF NOT EXISTS cosmic CHARACTER SET utf8 COLLATE utf8_general_ci;"
if ($LASTEXITCODE -ne 0) {
    throw "Could not create or verify database 'cosmic'."
}

Push-Location $CosmicDir
try {
    if ($Build) {
        & .\mvnw.cmd clean package
        if ($LASTEXITCODE -ne 0) {
            throw "Maven build failed with exit code $LASTEXITCODE."
        }
    }

    $jar = Get-ChildItem -LiteralPath (Join-Path $CosmicDir "target") -Filter "*jar-with-dependencies.jar" -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    if ($null -eq $jar) {
        $jar = Get-ChildItem -LiteralPath (Join-Path $CosmicDir "target") -Filter "*.jar" -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notmatch "sources|javadoc" } |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
    }

    if ($null -eq $jar) {
        $jar = Get-ChildItem -LiteralPath $CosmicDir -Filter "*.jar" -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
    }

    if ($null -ne $jar) {
        Write-Host "Starting Cosmic from jar: $($jar.FullName)"
        $wzPath = Join-Path $CosmicDir "wz"
        & $java "-Dwz-path=$wzPath" -jar "$($jar.FullName)"
    } elseif (Test-Path -LiteralPath (Join-Path $CosmicDir "launch.bat")) {
        Write-Host "No built jar found. Falling back to launch.bat."
        & .\launch.bat
    } else {
        throw "No runnable jar or launch.bat found. Run: .\mvnw.cmd clean package"
    }
}
finally {
    Pop-Location
}
