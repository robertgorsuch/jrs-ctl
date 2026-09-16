# jrsctl launcher (Windows, PowerShell). Uses the bundled runtime; no JDK or JAVA_HOME needed.
# JRSCTL_JAVA_OPTS: extra JVM options (optional), split on spaces.
#
# Prefer this launcher for schedulers and scripts: unlike bin\jrsctl.cmd it is not a batch file, so
# Ctrl-C does not make cmd.exe ask "Terminate batch job (Y/N)?" and block, and the caller always
# gets jrsctl's own exit code (#33). Run it as:
#   powershell -NoProfile -ExecutionPolicy Bypass -File <install>\bin\jrsctl.ps1 <command> [options]
#
# It never reports success without having run jrsctl: a missing runtime or jar, or a JVM that could
# not be started at all, exits 1 with a message on stderr rather than the 0 an empty $LASTEXITCODE
# would give.
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$java = Join-Path $root 'runtime\bin\java.exe'
$jar = Join-Path $root 'lib\jrsctl.jar'
foreach ($required in @($java, $jar)) {
  if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
    [Console]::Error.WriteLine("jrsctl: $required is missing; unpack the whole archive and run the launcher from its bin directory")
    exit 1
  }
}
$jvmArgs = @()
if ($env:JRSCTL_JAVA_OPTS) {
  $jvmArgs = $env:JRSCTL_JAVA_OPTS -split ' ' | Where-Object { $_ -ne '' }
}
$global:LASTEXITCODE = $null
& $java @jvmArgs -jar $jar @args
if ($null -eq $LASTEXITCODE) {
  [Console]::Error.WriteLine('jrsctl: the bundled Java runtime could not be started')
  exit 1
}
exit $LASTEXITCODE
