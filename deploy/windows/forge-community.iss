#ifndef SourceDir
  #error SourceDir must be defined
#endif
#ifndef OutputDir
  #error OutputDir must be defined
#endif
#ifndef BuildVersion
  #error BuildVersion must be defined
#endif
#ifndef DisplayVersion
  #error DisplayVersion must be defined
#endif
#ifndef PackageKind
  #error PackageKind must be defined
#endif
#ifndef OutputName
  #error OutputName must be defined
#endif

[Setup]
AppId={{81359762-8A4D-4D75-9BB4-7E116BB0C5F3}
AppName=Forge 简体中文社区版
AppVersion={#DisplayVersion}
AppPublisher=Forge 简体中文社区
AppPublisherURL=https://github.com/kaorou-bot/forge/tree/zh-cn-community-release
DefaultDirName={localappdata}\Programs\ForgeCN
DefaultGroupName=Forge 简体中文社区版
OutputDir={#OutputDir}
OutputBaseFilename={#OutputName}
SetupIconFile=..\..\forge-gui-desktop\src\main\config\forge.ico
PrivilegesRequired=lowest
ArchitecturesAllowed=x64os
ArchitecturesInstallIn64BitMode=x64os
Compression=lzma2
SolidCompression=yes
CloseApplications=yes
RestartApplications=no
UsePreviousAppDir=yes
WizardStyle=modern
SetupLogging=yes
UninstallDisplayIcon={app}\forge.exe

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion; Excludes: "forge.profile.properties"

#if PackageKind == "patch"
[InstallDelete]
#include GetEnv("FORGE_DELETE_LIST")
#endif

[Icons]
Name: "{group}\Forge 简体中文社区版"; Filename: "{app}\forge.exe"
Name: "{autodesktop}\Forge 简体中文社区版"; Filename: "{app}\forge.exe"; Tasks: desktopicon

[Tasks]
Name: desktopicon; Description: "创建桌面快捷方式"; GroupDescription: "其他选项："

[Run]
Filename: "{app}\forge.exe"; Description: "启动 Forge"; Flags: nowait postinstall skipifsilent

[Code]
function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  InstalledVersion: AnsiString;
begin
  Result := '';
#if PackageKind == "patch"
  if not LoadStringFromFile(ExpandConstant('{app}\forge-community-version.txt'), InstalledVersion) then
  begin
    Result := '未找到已安装版本。请下载并运行完整安装包。';
    Exit;
  end;
  if Trim(String(InstalledVersion)) <> '{#GetEnv("FORGE_PREVIOUS_VERSION")}' then
    Result := '当前安装版本不匹配，不能应用此增量包。请下载完整安装包。';
#endif
end;
