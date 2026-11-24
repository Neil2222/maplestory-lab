; MapleStory Lab Client — Inno Setup installer script
; Client-only: connects to the server address patched into the client exe

#define MyAppName "MapleStory Lab"
#define MyAppVersion "1.0"
#define MyAppExeName "HeavenMS-localhost-WINDOW.exe"

[Setup]
AppId={{8F2B4A91-E7C3-4D5F-B8A1-MSLABCLIENT}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher=MapleStory Lab
DefaultDirName={autopf}\MapleStory Lab
DisableDirPage=no
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
OutputDir=dist
OutputBaseFilename=MapleStory-Lab-Client-Setup
SetupIconFile=..\maple.ico
UninstallDisplayIcon={app}\maple.ico
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
ArchitecturesInstallIn64BitMode=x64compatible

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"
Name: "defenderexclusion"; Description: "Add Windows Defender exclusion for the install folder (recommended)"

[Files]
; Client files (with the exe patched to point at your server's public address)
Source: "bundle\client\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
; Icon
Source: "..\maple.ico"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{commondesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; IconFilename: "{app}\maple.ico"; Tasks: desktopicon
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; IconFilename: "{app}\maple.ico"
Name: "{group}\Uninstall {#MyAppName}"; Filename: "{uninstallexe}"

[Registry]
; Win7 compatibility mode to prevent CoInitialize errors
Root: HKCU; Subkey: "Software\Microsoft\Windows NT\CurrentVersion\AppCompatFlags\Layers"; ValueType: string; ValueName: "{app}\{#MyAppExeName}"; ValueData: "~ WIN7RTM"; Flags: uninsdeletevalue

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "Launch MapleStory Lab"; Flags: nowait postinstall skipifsilent

[Code]
procedure CurStepChanged(CurStep: TSetupStep);
var
  ResultCode: Integer;
begin
  if CurStep = ssInstall then
  begin
    if IsTaskSelected('defenderexclusion') then
    begin
      Exec('powershell.exe',
        '-NoProfile -Command "Add-MpPreference -ExclusionPath ''' + ExpandConstant('{app}') + '''"',
        '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
    end;
  end;
end;
