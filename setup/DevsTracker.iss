[Setup]
AppName=DevsTracker
AppVersion=6.12.2
DefaultDirName={pf}\DevsTracker
OutputBaseFilename=DevsTracker-setup_6_12_2
ArchitecturesInstallIn64BitMode=x64

[Dirs]
Name: "{app}\data"
Name: "{app}\logs"

[Files]
Source: "Output\*"; DestDir: "{app}"; Flags: recursesubdirs

[Run]
Filename: "{app}\jre\bin\java.exe"; Parameters: "-jar ""{app}\tracker-server.jar"" --install .\conf\DevsTracker.xml"; Flags: runhidden

[UninstallRun]
Filename: "{app}\jre\bin\java.exe"; Parameters: "-jar ""{app}\tracker-server.jar"" --uninstall"; Flags: runhidden
