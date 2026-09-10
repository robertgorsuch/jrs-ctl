@echo off
rem Runs Maven with the JDK 21 this project requires. Usage: scripts\mvn.cmd verify
setlocal
if "%JRSCTL_JDK%"=="" set "JRSCTL_JDK=C:\Program Files\Microsoft\jdk-21.0.9.10-hotspot"
if not exist "%JRSCTL_JDK%\bin\java.exe" (
  echo JDK 21 not found at "%JRSCTL_JDK%". Set JRSCTL_JDK to a JDK 21 home. 1>&2
  exit /b 1
)
set "JAVA_HOME=%JRSCTL_JDK%"
set "PATH=%JAVA_HOME%\bin;%PATH%"
if "%JRSCTL_MAVEN%"=="" (
  call "%~dp0..\mvnw.cmd" -B %*
) else (
  call "%JRSCTL_MAVEN%\bin\mvn.cmd" -B %*
)
exit /b %ERRORLEVEL%
