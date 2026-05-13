[Setup]
AppName=SerbTracker
AppVersion=6.13.3
DefaultDirName={pf}\SerbTracker
OutputBaseFilename=SerbTracker-setup_6_13_3
ArchitecturesInstallIn64BitMode=x64

[Dirs]
Name: "{app}\data"
Name: "{app}\logs"

[Files]
Source: "Output\*"; DestDir: "{app}"; Flags: recursesubdirs

[Run]
Filename: "{app}\jre\bin\java.exe"; Parameters: "-jar ""{app}\tracker-server.jar"" --install .\conf\SerbTracker.xml"; Flags: runhidden

[UninstallRun]
Filename: "{app}\jre\bin\java.exe"; Parameters: "-jar ""{app}\tracker-server.jar"" --uninstall"; Flags: runhidden
