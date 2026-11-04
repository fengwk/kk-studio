# Poll HTTP readiness. Return 2 when the optional process exits, 1 on timeout.
poll_http() {
  local url=$1 attempts=$2 pid=${3:-} pid_file=${4:-} attempt
  for ((attempt = 0; attempt < attempts; attempt++)); do
    if curl -fsS "$url" >/dev/null 2>&1; then
      return 0
    fi
    if [ -n "$pid_file" ]; then
      pid=$(cat "$pid_file" 2>/dev/null || true)
      [ -n "$pid" ] || return 2
    fi
    if [ -n "$pid" ] && ! kill -0 "$pid" >/dev/null 2>&1; then
      return 2
    fi
    sleep 1
  done
  return 1
}
