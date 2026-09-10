#!/usr/bin/env bash
# Runs Maven with the JDK 21 this project requires. Usage: scripts/mvn.sh verify
set -euo pipefail
cd "$(dirname "$0")/.."
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*)
    exec cmd.exe //c "scripts\\mvn.cmd $*"
    ;;
esac
: "${JRSCTL_JDK:=${JAVA_HOME:-}}"
if [ -z "$JRSCTL_JDK" ] || [ ! -x "$JRSCTL_JDK/bin/java" ]; then
  echo "Set JRSCTL_JDK (or JAVA_HOME) to a JDK 21 home." >&2
  exit 1
fi
export JAVA_HOME="$JRSCTL_JDK"
export PATH="$JAVA_HOME/bin:$PATH"
if [ -f "$(dirname "$0")/../mvnw" ]; then
  exec "$(dirname "$0")/../mvnw" -B "$@"
else
  exec mvn -B "$@"
fi
