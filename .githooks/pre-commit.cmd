@echo off
rem Pre-commit hook to verify Google Java Format compliance via Spotless before committing.

echo Running pre-commit spotless check...

setlocal
for /f "delims=" %%i in ('git rev-parse --show-toplevel 2^>nul') do set "REPO_ROOT=%%i"
if "%REPO_ROOT%"=="" set "REPO_ROOT=%~dp0\.."

cd /d "%REPO_ROOT%"

if exist "scripts\mvn.cmd" (
  set "MVN_CMD=scripts\mvn.cmd"
) else if exist "mvnw.cmd" (
  set "MVN_CMD=mvnw.cmd"
) else (
  set "MVN_CMD=mvn"
)

call %MVN_CMD% spotless:check
if %ERRORLEVEL% neq 0 (
  echo.
  echo Error: Spotless formatting check failed.
  echo To automatically format Java source files, run:
  echo   .\mvnw.cmd spotless:apply
  echo   (or 'scripts\mvn.cmd spotless:apply')
  echo.
  exit /b %ERRORLEVEL%
)

endlocal
exit /b 0
