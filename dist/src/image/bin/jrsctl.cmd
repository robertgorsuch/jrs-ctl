@echo off
rem jrsctl launcher (Windows). Uses the bundled runtime; no JDK or JAVA_HOME needed.
rem JRSCTL_JAVA_OPTS: extra JVM options (optional).
rem After Ctrl-C, cmd.exe may ask "Terminate batch job (Y/N)?" once jrsctl has already cancelled
rem and exited 5; the run is finished either way. Schedulers and scripts that need exit code 5
rem should run runtime\bin\java.exe -jar lib\jrsctl.jar directly (operator guide, Exit codes; #33).
setlocal
"%~dp0..\runtime\bin\java.exe" %JRSCTL_JAVA_OPTS% -jar "%~dp0..\lib\jrsctl.jar" %*
exit /b %ERRORLEVEL%
