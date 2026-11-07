; VT Launcher - plain fallback installer (used only if vt-setup.iss fails to compile).
; Version comes from the build: /DAppVersion=x.y.z
; Pictures you can replace (PNG, see installer/images/README.txt): installer/images/background.png
#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif

[Setup]
AppId={{A3D5C6E2-5B7F-4C1E-9F6A-2B8D4E7F1C90}
AppName=VT Launcher
AppVersion={#AppVersion}
AppPublisher=VT
DefaultDirName={localappdata}\Programs\VT Launcher
DisableProgramGroupPage=yes
DisableWelcomePage=yes
DisableReadyPage=yes
DisableReadyMemo=yes
PrivilegesRequired=lowest
OutputDir=..\installer-out
OutputBaseFilename=VT-Launcher-Setup-v{#AppVersion}
SetupIconFile=..\src\main\resources\vt\vt.ico
UninstallDisplayIcon={app}\VT Launcher.exe
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
WizardImageFile=wizard-large.bmp
WizardSmallImageFile=wizard-small.bmp
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
CloseApplications=yes

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Files]
Source: "..\out\VT Launcher\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\VT Launcher"; Filename: "{app}\VT Launcher.exe"
Name: "{autodesktop}\VT Launcher"; Filename: "{app}\VT Launcher.exe"

[Run]
Filename: "{app}\VT Launcher.exe"; Description: "{cm:LaunchProgram,VT Launcher}"; Flags: nowait postinstall skipifsilent

