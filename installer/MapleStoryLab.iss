; MapleStory Lab — Inno Setup installer script
; Build with: ISCC.exe MapleStoryLab.iss

#define MyAppName "MapleStory Lab"
#define MyAppVersion "1.0"
#define MyAppExeName "MapleStoryLab.exe"

[Setup]
AppId={{8F2B4A91-E7C3-4D5F-B8A1-MAPLESTORYLAB}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher=MapleStory Lab
DefaultDirName=C:\MapleStory Lab
DisableDirPage=no
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
OutputDir=dist
OutputBaseFilename=MapleStory-Lab-Setup
SetupIconFile=..\maple.ico
UninstallDisplayIcon={app}\maple.ico
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=admin
ArchitecturesInstallIn64BitMode=x64compatible

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"
Name: "defenderexclusion"; Description: "Add Windows Defender exclusion for the install folder (recommended — prevents false virus detections)"

[Files]
; Launcher (PyInstaller onedir output)
Source: "dist\MapleStoryLab\{#MyAppExeName}"; DestDir: "{app}"; Flags: ignoreversion
Source: "dist\MapleStoryLab\_internal\*"; DestDir: "{app}\_internal"; Flags: ignoreversion recursesubdirs createallsubdirs

; Icon (root for launcher, server/ for dashboard favicon)
Source: "..\maple.ico"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\maple.ico"; DestDir: "{app}\server"; Flags: ignoreversion

; Runtime — Java (portable Corretto 21)
Source: "bundle\runtime\java\*"; DestDir: "{app}\runtime\java"; Flags: ignoreversion recursesubdirs createallsubdirs

; Runtime — MySQL (portable 8.4)
Source: "bundle\runtime\mysql\*"; DestDir: "{app}\runtime\mysql"; Flags: ignoreversion recursesubdirs createallsubdirs

; Server — Cosmic JAR
Source: "..\Cosmic\target\Cosmic.jar"; DestDir: "{app}\server"; Flags: ignoreversion

; Server — config
Source: "..\Cosmic\config.yaml"; DestDir: "{app}\server"; Flags: ignoreversion

; Server — WZ data
Source: "..\Cosmic\wz\*"; DestDir: "{app}\server\wz"; Flags: ignoreversion recursesubdirs createallsubdirs

; Server — event scripts (JavaScript, required for channels)
Source: "..\Cosmic\scripts\*"; DestDir: "{app}\server\scripts"; Flags: ignoreversion recursesubdirs createallsubdirs

; Server — handbook (item/map data for dashboard)
Source: "..\Cosmic\handbook\*"; DestDir: "{app}\server\handbook"; Flags: ignoreversion recursesubdirs createallsubdirs

; Client
Source: "bundle\client\*"; DestDir: "{app}\client"; Flags: ignoreversion recursesubdirs createallsubdirs

; Scripts
Source: "..\scripts\start-server.ps1"; DestDir: "{app}\scripts"; Flags: ignoreversion
Source: "..\scripts\stop-server.ps1"; DestDir: "{app}\scripts"; Flags: ignoreversion

[Dirs]
Name: "{app}\data"
Name: "{app}\logs"

[Icons]
Name: "{commondesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; IconFilename: "{app}\maple.ico"; Tasks: desktopicon
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; IconFilename: "{app}\maple.ico"
Name: "{group}\Uninstall {#MyAppName}"; Filename: "{uninstallexe}"

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "Launch MapleStory Lab"; Flags: nowait postinstall skipifsilent

[UninstallRun]
; Stop running server processes before removing files
Filename: "powershell.exe"; Parameters: "-NoProfile -Command ""Get-Process mysqld -ErrorAction SilentlyContinue | Stop-Process -Force; Get-Process java -ErrorAction SilentlyContinue | Where-Object {{ $_.CommandLine -like '*Cosmic*' }} | Stop-Process -Force"""; Flags: runhidden waituntilterminated
; Remove Defender exclusion if it was added
Filename: "powershell.exe"; Parameters: "-NoProfile -Command ""Remove-MpPreference -ExclusionPath '{app}'"""; Flags: runhidden waituntilterminated

[Registry]
; Set Windows 7 compatibility mode on the game client exe
Root: HKCU; Subkey: "Software\Microsoft\Windows NT\CurrentVersion\AppCompatFlags\Layers"; ValueType: string; ValueName: "{app}\client\HeavenMS-localhost-WINDOW.exe"; ValueData: "~ WIN7RTM"; Flags: uninsdeletevalue

[UninstallDelete]
Type: filesandordirs; Name: "{app}\data"
Type: filesandordirs; Name: "{app}\logs"
Type: filesandordirs; Name: "{app}\server\admin-icons"

[Code]
procedure CurStepChanged(CurStep: TSetupStep);
var
  ResultCode: Integer;
begin
  if CurStep = ssInstall then
  begin
    if IsTaskSelected('defenderexclusion') then
    begin
      // User opted in — add Defender exclusion before files are extracted
      Exec('powershell.exe',
        '-NoProfile -Command "Add-MpPreference -ExclusionPath ''' + ExpandConstant('{app}') + '''"',
        '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
    end;
  end;
end;
