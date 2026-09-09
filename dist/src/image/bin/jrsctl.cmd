@echo off
rem jrsctl launcher (Windows). Uses the bundled runtime; no JDK or JAVA_HOME needed.
rem JRSCTL_JAVA_OPTS: extra JVM options (optional).
setlocal
"%~dp0..\runtime\bin\java.exe" %JRSCTL_JAVA_OPTS% -jar "%~dp0..\lib\jrsctl.jar" %*
exit /b %ERRORLEVEL%
