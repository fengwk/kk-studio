# scripts/dev/verify/supply-chain/lib.sh — shared helpers for the supply-chain gate.
#
# Sourced by run.sh in the same process so the gate keeps one report and one exit
# status; this file is never an entry point.

die() {
    echo "ERROR: $*" >&2
    exit 2
}

source "$SCRIPT_DIR/../../lib/toolchain.sh"

json_object_file_is_non_empty() {
    local file=$1
    [[ -s "$file" ]] || return 1
    node --input-type=module - "$file" >/dev/null 2>&1 <<'NODE'
import { readFileSync } from 'node:fs'

const document = JSON.parse(readFileSync(process.argv[2], 'utf8'))
if (
    document === null ||
    typeof document !== 'object' ||
    Array.isArray(document) ||
    Object.keys(document).length === 0
) {
    process.exit(1)
}
NODE
}
