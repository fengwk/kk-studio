#!/usr/bin/env bash
# Shared tool and JDK checks for the development entry points.
#
# Callers provide `die`: the supply-chain gate exits with its own status and the other
# entries exit with 1, and every entry keeps its published exit code.

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "missing command: $1"
}

resolve_java_home() {
  local java_home=${JAVA_HOME_21:-${JAVA_HOME:-}}
  if [ -z "$java_home" ] || [ ! -x "$java_home/bin/java" ]; then
    die "JAVA_HOME_21 or JAVA_HOME must point to JDK 21"
  fi
  printf '%s\n' "$java_home"
}
