<#
.SYNOPSIS
    Builds the MapleStory Lab installer (MapleStory-Lab-Setup.exe).
.DESCRIPTION
    1. Downloads portable Java (Corretto 21) and MySQL 8.4 if not cached
    2. Copies the client from C:\Nexon\MapleStory
    3. Builds the launcher .exe via PyInstaller
    4. Compiles the Inno Setup installer
.NOTES
    Prerequisites:
    - Python 3.10+ with PyInstaller installed (pip install pyinstaller)
    - Inno Setup 6 installed (https://jrsoftware.org/isinfo.php)
    - Cosmic.jar built (run: cd ..\Cosmic && .\mvnw.cmd clean package)
    - WZ files present in ..\Cosmic\wz\
#>

param(
    [string]$ClientSource = "C:\Nexon\MapleStory",
    [switch]$SkipDownloads,
    [switch]$SkipPyInstaller,
    [switch]$SkipInnoSetup
)

$ErrorActionPreference = "Stop"
$InstallerDir = $PSScriptRoot
$RepoRoot = Split-Path $InstallerDir -Parent
$BundleDir = Join-Path $InstallerDir "bundle"
$DistDir = Join-Path $InstallerDir "dist"

$CorrettoUrl = "https://corretto.aws/downloads/latest/amazon-corretto-21-x64-windows-jdk.zip"
$MySqlVersion = "8.4.5"
$MySqlUrl = "https://dev.mysql.com/get/Downloads/MySQL-8.4/mysql-$MySqlVersion-winx64.zip"

# --- Helpers ---

function Write-Step($msg) { Write-Host "`n=== $msg ===" -ForegroundColor Cyan }
function Write-Ok($msg) { Write-Host "  OK: $msg" -ForegroundColor Green }

function Assert-Command($name) {
    if (-not (Get-Command $name -ErrorAction SilentlyContinue)) {
        throw "'$name' not found on PATH. Please install it first."
    }
}

function Download-AndExtract($url, $destDir, $label) {
    if (Test-Path $destDir) {
        Write-Ok "$label already cached at $destDir"
        return
    }
    $zipName = Split-Path $url -Leaf
    $zipPath = Join-Path $InstallerDir $zipName

    Write-Host "  Downloading $label..."
    if (-not (Test-Path $zipPath)) {
        Invoke-WebRequest -Uri $url -OutFile $zipPath -UseBasicParsing
    }

    Write-Host "  Extracting $label..."
    $tempExtract = Join-Path $InstallerDir "_extract_temp"
    if (Test-Path $tempExtract) { Remove-Item $tempExtract -Recurse -Force }
    Expand-Archive -Path $zipPath -DestinationPath $tempExtract

    # Most archives have a single top-level folder — move its contents to destDir
    $children = Get-ChildItem $tempExtract
    if ($children.Count -eq 1 -and $children[0].PSIsContainer) {
        New-Item -ItemType Directory -Force -Path (Split-Path $destDir -Parent) | Out-Null
        Move-Item $children[0].FullName $destDir
    } else {
        New-Item -ItemType Directory -Force -Path $destDir | Out-Null
        Move-Item "$tempExtract\*" $destDir
    }

    Remove-Item $tempExtract -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item $zipPath -Force -ErrorAction SilentlyContinue
    Write-Ok "$label extracted to $destDir"
}

# --- Preflight checks ---

Write-Step "Checking prerequisites"

Assert-Command "python"
if (-not $SkipPyInstaller) { Assert-Command "pyinstaller" }

$iscc = $null
$innoLocations = @(
    "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe",
    "$env:ProgramFiles\Inno Setup 6\ISCC.exe"
)
foreach ($loc in $innoLocations) {
    if (Test-Path $loc) { $iscc = $loc; break }
}
if (-not $SkipInnoSetup -and -not $iscc) {
    throw "Inno Setup 6 not found. Install from https://jrsoftware.org/isinfo.php"
}

$cosmicJar = Join-Path $RepoRoot "Cosmic\target\Cosmic.jar"
if (-not (Test-Path $cosmicJar)) {
    throw "Cosmic.jar not found at $cosmicJar. Build first: cd Cosmic && .\mvnw.cmd clean package"
}

$wzDir = Join-Path $RepoRoot "Cosmic\wz"
if (-not (Test-Path $wzDir) -or (Get-ChildItem $wzDir -Filter "*.wz").Count -eq 0) {
    throw "WZ files not found in $wzDir"
}

if (-not (Test-Path $ClientSource)) {
    throw "Client not found at $ClientSource"
}

Write-Ok "All prerequisites met"

# --- Step 1: Download portable runtimes ---

if (-not $SkipDownloads) {
    Write-Step "Downloading portable runtimes"
    Download-AndExtract $CorrettoUrl (Join-Path $BundleDir "runtime\java") "Amazon Corretto 21"
    Download-AndExtract $MySqlUrl (Join-Path $BundleDir "runtime\mysql") "MySQL $MySqlVersion"
} else {
    Write-Step "Skipping downloads (cached)"
}

# --- Step 2: Copy client ---

Write-Step "Copying client files"
$clientDest = Join-Path $BundleDir "client"
if (Test-Path $clientDest) {
    Write-Ok "Client already copied"
} else {
    Write-Host "  Copying $ClientSource -> $clientDest (this may take a few minutes)..."
    Copy-Item -Path $ClientSource -Destination $clientDest -Recurse
    Write-Ok "Client copied ($((Get-ChildItem $clientDest -Recurse | Measure-Object -Property Length -Sum).Sum / 1GB) GB)"
}

# --- Step 3: PyInstaller ---

if (-not $SkipPyInstaller) {
    Write-Step "Building launcher with PyInstaller"
    $launcherSrc = Join-Path $RepoRoot "tools\maplestory_launcher.py"
    $iconPath = Join-Path $RepoRoot "maple.ico"

    # Clean previous build
    $pyDistDir = Join-Path $DistDir "MapleStoryLab"
    if (Test-Path $pyDistDir) { Remove-Item $pyDistDir -Recurse -Force }

    $buildDir = Join-Path $InstallerDir "build"

    & pyinstaller --onedir --noconsole `
        --name "MapleStoryLab" `
        --icon $iconPath `
        --distpath $DistDir `
        --workpath $buildDir `
        --specpath $InstallerDir `
        $launcherSrc

    if ($LASTEXITCODE -ne 0) { throw "PyInstaller failed with exit code $LASTEXITCODE" }

    # Clean build artifacts
    Remove-Item $buildDir -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item (Join-Path $InstallerDir "MapleStoryLab.spec") -Force -ErrorAction SilentlyContinue

    Write-Ok "Launcher built at $pyDistDir"
} else {
    Write-Step "Skipping PyInstaller (--SkipPyInstaller)"
}

# --- Step 4: Inno Setup ---

if (-not $SkipInnoSetup) {
    Write-Step "Compiling installer with Inno Setup"
    $issFile = Join-Path $InstallerDir "MapleStoryLab.iss"
    & $iscc $issFile
    if ($LASTEXITCODE -ne 0) { throw "Inno Setup compilation failed with exit code $LASTEXITCODE" }

    $setupExe = Join-Path $DistDir "MapleStory-Lab-Setup.exe"
    if (Test-Path $setupExe) {
        $sizeMB = [math]::Round((Get-Item $setupExe).Length / 1MB)
        Write-Ok "Installer ready: $setupExe ($sizeMB MB)"
    }
} else {
    Write-Step "Skipping Inno Setup (--SkipInnoSetup)"
}

Write-Host "`nDone!" -ForegroundColor Green
