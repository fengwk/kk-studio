# Shared host-side E2E login; the cookie stays in shell memory and out of curl argv.
e2e_studio_login() {
  node --input-type=module -e '
    const { loginStudio } = await import(process.argv[1]);
    process.stdout.write(await loginStudio(process.argv[2]));
  ' "$SCRIPT_DIR/lib/http.mjs" "$1"
}

e2e_studio_get() {
  local cookie=$1 url=$2
  printf 'Cookie: %s\n' "$cookie" | curl -fsS -H @- "$url"
}
