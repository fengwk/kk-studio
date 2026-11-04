#!/usr/bin/env bash
# Resolve from the caller's directory, not this library or the target override.
resolve_repo_root() {
  if [ -n "${KK_STUDIO_REPO_ROOT:-}" ]; then
    REPO_ROOT=$(cd "$KK_STUDIO_REPO_ROOT" && pwd) || return 1
  else
    REPO_ROOT=$1
    while [ "$REPO_ROOT" != "/" ] && [ ! -e "$REPO_ROOT/.git" ]; do
      REPO_ROOT=$(dirname "$REPO_ROOT")
    done
    if [ ! -e "$REPO_ROOT/.git" ]; then
      echo "ERROR: cannot locate the kk-studio repository root; set KK_STUDIO_REPO_ROOT" >&2
      return 1
    fi
  fi
}
