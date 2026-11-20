; Standalone public KaryoForge distribution. No legacy/private installation migration.
Unicode true
RequestExecutionLevel user
ManifestLongPathAware true
!include "MUI2.nsh"
!include "x64.nsh"
!include "WinVer.nsh"
!include "LogicLib.nsh"

!ifndef STAGE
  !error "STAGE must point to the validated application directory"
!endif
!ifndef OUTPUT
  !error "OUTPUT must name the installer executable"
!endif

Name "KaryoForge ${APP_VERSION}"
OutFile "${OUTPUT}"
InstallDir "$LOCALAPPDATA\Programs\OpenKaryon\KaryoForge"
BrandingText "KaryoForge"
Icon "${STAGE}\bin\karyoforge.ico"
UninstallIcon "${STAGE}\bin\karyoforge.ico"
SetCompressor /SOLID zlib
ShowInstDetails show
ShowUninstDetails show
VIProductVersion "${NUMERIC_VERSION}"
VIAddVersionKey /LANG=1033 "ProductName" "KaryoForge"
VIAddVersionKey /LANG=1033 "CompanyName" "OpenKaryon"
VIAddVersionKey /LANG=1033 "FileDescription" "KaryoForge Windows installer"
VIAddVersionKey /LANG=1033 "FileVersion" "${APP_VERSION}"
VIAddVersionKey /LANG=1033 "LegalCopyright" "Copyright OpenKaryon contributors"

!define MUI_ABORTWARNING
!define MUI_DIRECTORYPAGE_VERIFYONLEAVE
!define MUI_FINISHPAGE_RUN "$INSTDIR\bin\karyoforge.exe"
!define MUI_FINISHPAGE_RUN_NOTCHECKED
!define MUI_FINISHPAGE_RUN_TEXT "Start KaryoForge"
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_LICENSE "${STAGE}\openkaryon-license\LICENSE"
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "English"

!define APP_KEY "Software\OpenKaryon\KaryoForge"
!define UNINSTALL_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\OpenKaryon.KaryoForge"

Function .onInit
  ${IfNot} ${RunningX64}
    MessageBox MB_ICONSTOP "KaryoForge requires 64-bit Windows."
    Abort
  ${EndIf}
  ${IfNot} ${AtLeastWin10}
    MessageBox MB_ICONSTOP "KaryoForge requires Windows 10 or later."
    Abort
  ${EndIf}
  SetRegView 64
  SetShellVarContext current
  ReadRegStr $0 HKCU "${APP_KEY}" "InstallDir"
  ${If} $0 != ""
    StrCpy $INSTDIR $0
  ${EndIf}
FunctionEnd

Section "KaryoForge" SEC_MAIN
  SetRegView 64
  SetShellVarContext current
  ; Replace only this public product's installation, using its exact-file uninstaller.
  ${If} ${FileExists} "$INSTDIR\karyoforge-distribution.json"
    ${IfNot} ${FileExists} "$INSTDIR\Uninstall.exe"
      MessageBox MB_ICONSTOP "This folder contains a portable KaryoForge distribution. Choose a different installation folder."
      Abort
    ${EndIf}
    ExecWait '"$INSTDIR\Uninstall.exe" /S _?=$INSTDIR' $0
    ${If} $0 != 0
      MessageBox MB_ICONSTOP "The previous installation could not be removed. Close KaryoForge and retry."
      Abort
    ${EndIf}
    Delete "$INSTDIR\Uninstall.exe"
  ${Else}
    ; Never merge into an unrelated IDE, project directory or private installation.
    FindFirst $0 $1 "$INSTDIR\*"
    ${DoWhile} $1 != ""
      ${If} $1 != "."
      ${AndIf} $1 != ".."
        FindClose $0
        MessageBox MB_ICONSTOP "Choose an empty folder for the public KaryoForge installation."
        Abort
      ${EndIf}
      FindNext $0 $1
    ${Loop}
    FindClose $0
  ${EndIf}
  SetOutPath "$INSTDIR"
  File /r "${STAGE}\*"
  WriteUninstaller "$INSTDIR\Uninstall.exe"
  CreateShortcut "$SMPROGRAMS\KaryoForge.lnk" "$INSTDIR\bin\karyoforge.exe" "" "$INSTDIR\bin\karyoforge.exe"
  WriteRegStr HKCU "${APP_KEY}" "InstallDir" "$INSTDIR"
  WriteRegStr HKCU "${UNINSTALL_KEY}" "DisplayName" "KaryoForge"
  WriteRegStr HKCU "${UNINSTALL_KEY}" "DisplayVersion" "${APP_VERSION}"
  WriteRegStr HKCU "${UNINSTALL_KEY}" "Publisher" "OpenKaryon"
  WriteRegStr HKCU "${UNINSTALL_KEY}" "DisplayIcon" "$INSTDIR\bin\karyoforge.exe"
  WriteRegStr HKCU "${UNINSTALL_KEY}" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "${UNINSTALL_KEY}" "UninstallString" '$\"$INSTDIR\Uninstall.exe$\"'
  WriteRegStr HKCU "${UNINSTALL_KEY}" "QuietUninstallString" '$\"$INSTDIR\Uninstall.exe$\" /S'
  WriteRegDWORD HKCU "${UNINSTALL_KEY}" "NoModify" 1
  WriteRegDWORD HKCU "${UNINSTALL_KEY}" "NoRepair" 1
SectionEnd

Section "Uninstall"
  SetRegView 64
  SetShellVarContext current
  IfFileExists "$INSTDIR\karyoforge-distribution.json" +3
    MessageBox MB_ICONSTOP "The public KaryoForge installation marker is missing. No files were removed."
    Abort
  ${If} ${FileExists} "$INSTDIR\bin\karyoforge.exe"
    ClearErrors
    FileOpen $0 "$INSTDIR\bin\karyoforge.exe" a
    ${If} ${Errors}
      MessageBox MB_ICONSTOP "Close KaryoForge before removing or replacing this installation."
      SetErrorLevel 1
      Abort
    ${EndIf}
    FileClose $0
  ${EndIf}
  !include "${UNINSTALL_FILES}"
  Delete "$INSTDIR\Uninstall.exe"
  RMDir "$INSTDIR"
  Delete "$SMPROGRAMS\KaryoForge.lnk"
  DeleteRegKey HKCU "${APP_KEY}"
  DeleteRegKey HKCU "${UNINSTALL_KEY}"
  ; User settings, installed plugins, caches and projects remain outside the payload.
SectionEnd
