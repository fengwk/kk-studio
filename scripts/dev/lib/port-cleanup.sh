#!/usr/bin/env bash
# Shared listener cleanup for the development entry points.
#
# Callers provide `step`: the dev lifecycle and the E2E harness prefix the message
# differently, but both reclaim a port the same way.

# Terminate whatever listens on the port, escalating to SIGKILL after a short grace period.
kill_port() {
  local port=$1
  local pids
  pids=$(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)
  if [ -z "$pids" ]; then
    return
  fi
  step "Stopping listeners on port $port: $pids"
  # shellcheck disable=SC2086
  kill $pids 2>/dev/null || true
  sleep 0.5
  pids=$(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)
  if [ -n "$pids" ]; then
    # shellcheck disable=SC2086
    kill -9 $pids 2>/dev/null || true
  fi
}
