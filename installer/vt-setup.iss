; VT Launcher - simple one-page installer (dark, big picture on top, folder box, Install button).
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
Source: "art.bmp"; Flags: dontcopy

[Icons]
Name: "{autoprograms}\VT Launcher"; Filename: "{app}\VT Launcher.exe"
Name: "{autodesktop}\VT Launcher"; Filename: "{app}\VT Launcher.exe"

[Run]
Filename: "{app}\VT Launcher.exe"; Description: "{cm:LaunchProgram,VT Launcher}"; Flags: nowait postinstall skipifsilent

[Code]
const
  BG    = $120E0E;   { #0E0E12 (Inno colors are BGR) }
  PANEL = $1A1612;
  WHITE = $F0F0F0;
  MUTED = $B8B0B0;

procedure Paint(L: TNewStaticText; C: TColor);
begin
  L.Font.Color := C;
end;

procedure InitializeWizard();
var
  Art: TBitmapImage;
  Shift: Integer;
begin
  { dark colours }
  WizardForm.Color := BG;
  WizardForm.MainPanel.Color := PANEL;
  WizardForm.InnerPage.Color := BG;
  WizardForm.SelectDirPage.Color := BG;
  WizardForm.InstallingPage.Color := BG;
  WizardForm.FinishedPage.Color := BG;
  Paint(WizardForm.PageNameLabel, WHITE);
  Paint(WizardForm.PageDescriptionLabel, MUTED);
  Paint(WizardForm.SelectDirLabel, WHITE);
  Paint(WizardForm.SelectDirBrowseLabel, MUTED);
  Paint(WizardForm.DiskSpaceLabel, MUTED);
  Paint(WizardForm.StatusLabel, WHITE);
  Paint(WizardForm.FilenameLabel, MUTED);
  Paint(WizardForm.FinishedHeadingLabel, WHITE);
  Paint(WizardForm.FinishedLabel, MUTED);

  { big picture on top of the folder page }
  ExtractTemporaryFile('art.bmp');
  Shift := ScaleY(150);
  WizardForm.Height := WizardForm.Height + Shift;
  Art := TBitmapImage.Create(WizardForm);
  Art.Parent := WizardForm.SelectDirPage;
  Art.Stretch := True;
  Art.Left := 0;
  Art.Top := 0;
  Art.Width := WizardForm.SelectDirPage.Width;
  Art.Height := Shift - ScaleY(6);
  Art.Bitmap.LoadFromFile(ExpandConstant('{tmp}\art.bmp'));
  WizardForm.SelectDirLabel.Top := WizardForm.SelectDirLabel.Top + Shift;
  WizardForm.SelectDirBrowseLabel.Top := WizardForm.SelectDirBrowseLabel.Top + Shift;
  WizardForm.DirEdit.Top := WizardForm.DirEdit.Top + Shift;
  WizardForm.DirBrowseButton.Top := WizardForm.DirBrowseButton.Top + Shift;
  WizardForm.DiskSpaceLabel.Top := WizardForm.DiskSpaceLabel.Top + Shift;
end;

procedure CurPageChanged(CurPageID: Integer);
begin
  if CurPageID = wpSelectDir then
    WizardForm.NextButton.Caption := 'Install';
end;
