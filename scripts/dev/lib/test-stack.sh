# The smoke and performance runners own the same disposable Canvas test project.
cleanup_test_stack() {
  if [ "$1" -ne 0 ]; then
    "${COMPOSE[@]}" --profile app ps -a >&2 || true
    "${COMPOSE[@]}" --profile app logs --no-color >&2 || true
  fi
  "${COMPOSE[@]}" --profile app down --volumes --remove-orphans >/dev/null 2>&1 || true
}
