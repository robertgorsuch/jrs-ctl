@echo off
rem Signs the release artifacts in a directory (default dist\target) with the Jaspersoft publisher key.
rem Spec section 0 rule 11 / section 11.1: runs in CI only. Without JRSCTL_SIGNING_KEY it prints a
rem notice and exits 0 so local builds stay unsigned and green.
rem JRSCTL_SIGNING_KEY = Base64 PKCS#8 Ed25519 private key (the format of `jrsctl keys generate`).
setlocal
if "%JRSCTL_SIGNING_KEY%"=="" (
  echo signing skipped: no key ^(CI only^)
  exit /b 0
)
set "DIR=%~1"
if "%DIR%"=="" set "DIR=%~dp0..\dist\target"
if "%JAVA_HOME%"=="" (
  if "%JRSCTL_JDK%"=="" set "JRSCTL_JDK=C:\Program Files\Microsoft\jdk-21.0.9.10-hotspot"
  set "JAVA_HOME=%JRSCTL_JDK%"
)
"%JAVA_HOME%\bin\java.exe" "%~dp0SignArtifacts.java" "%DIR%"
exit /b %ERRORLEVEL%
