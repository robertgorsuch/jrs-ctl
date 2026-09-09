@echo off
rem Builds the portable distribution for this platform: jlink image, ZIP, checksum, SBOM.
rem Output: dist\target\image\windows-x64\ and dist\target\jrsctl-<version>-windows-x64.zip
rem Usage: scripts\build-dist.cmd [extra maven args]
call "%~dp0mvn.cmd" -Pdist -DskipTests package %*
exit /b %ERRORLEVEL%
