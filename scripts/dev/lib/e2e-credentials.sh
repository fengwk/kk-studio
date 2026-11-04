# The synchronizer's provider specifications are the source of credential names.
load_e2e_credential_names() {
  local names
  names=$(node "$REPO_ROOT/scripts/dev/verify/e2e/sync-provider-credentials.mjs" --list-env) || return
  local name
  REAL_CREDENTIAL_VARS=()
  while IFS= read -r name; do
    REAL_CREDENTIAL_VARS+=("$name")
  done <<< "$names"
}

sync_e2e_provider_credentials() (
  require_cmd node
  step "Syncing provider E2E credentials from env (secrets not printed)"
  local -a REAL_CREDENTIAL_VARS
  load_e2e_credential_names || return
  local name
  for name in "${REAL_CREDENTIAL_VARS[@]}"; do
    export "$name=${!name-}"
  done
  unset TEST_MINIMAX_BASE_URL TEST_MINIMAX_API_KEY
  node "$REPO_ROOT/scripts/dev/verify/e2e/sync-provider-credentials.mjs" --backend-url "$BACKEND_URL"
)
