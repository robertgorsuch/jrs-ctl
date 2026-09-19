@echo off
rem The fast inner loop; see scripts\fast.sh for the commands. Needs bash (Git for Windows ships it).
rem Usage: scripts\fast.cmd test app HelpExamplesTest   |   scripts\fast.cmd guards
setlocal
where bash >nul 2>&1
if errorlevel 1 (
  echo scripts\fast.cmd needs bash on the PATH; install Git for Windows or run scripts\mvn.cmd directly. 1>&2
  exit /b 1
)
bash "%~dp0fast.sh" %*
exit /b %ERRORLEVEL%
