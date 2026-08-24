#!/usr/bin/env bash
# scripts/supply-chain.sh — explicit supply-chain SBOM and vulnerability gate
#
# Normal `mvn verify` never activates the online supply-chain profile. This
# entry point creates a timestamped, gitignored report and fails closed when
# a tool, its online source, or its output is unavailable.

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
NVD_SETTINGS_SERVER_ID=kk-studio-supply-chain-nvd

MODE=
REPORT_ROOT=
RUN_ID=
RUN_DIR=
STARTED_AT=
FINISHED_AT=
JAVA_HOME_FOR_BUILD=
TEMP_SETTINGS_FILE=
BACKEND_SBOM_STATUS=SKIPPED
FRONTEND_SBOM_STATUS=SKIPPED
NPM_AUDIT_STATUS=SKIPPED
MAVEN_AUDIT_STATUS=SKIPPED
OVERALL_STATUS=FAIL
FAILURES=()

usage() {
    cat <<'EOF'
Usage: ./scripts/supply-chain.sh <command>

Commands:
  sbom    Generate and validate backend and frontend CycloneDX SBOMs
  audit   Run npm audit and the Maven Dependency-Check gate
  all     Run sbom followed by audit and retain one combined report
  test    Run permanent supply-chain script tests only
  help    Show this help

Environment:
  SUPPLY_CHAIN_REPORT_ROOT  Report root, relative to the repository by default
                            (default: reports/supply-chain)
  NVD_API_KEY               Optional NVD API key; never printed or passed on the
                            Maven command line
  JAVA_HOME_21               JDK 21 used for Maven (JAVA_HOME is a fallback)
EOF
}

die() {
    echo "ERROR: $*" >&2
    exit 2
}

require_cmd() {
    command -v "$1" >/dev/null 2>&1 || die "missing command: $1"
}

resolve_java_home() {
    local java_home=${JAVA_HOME_21:-${JAVA_HOME:-}}
    if [[ -z "$java_home" || ! -x "$java_home/bin/java" ]]; then
        die "JAVA_HOME_21 or JAVA_HOME must point to JDK 21"
    fi
    printf '%s\n' "$java_home"
}

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

resolve_report_root() {
    local configured=${SUPPLY_CHAIN_REPORT_ROOT:-reports/supply-chain}
    if [[ "$configured" = /* ]]; then
        REPORT_ROOT=$configured
    else
        REPORT_ROOT="$REPO_ROOT/$configured"
    fi

    case "$REPORT_ROOT" in
        ""|"/"|"$REPO_ROOT"|"$REPO_ROOT/")
            die "SUPPLY_CHAIN_REPORT_ROOT must be a dedicated report directory"
            ;;
    esac
}

init_report() {
    resolve_report_root
    mkdir -p "$REPORT_ROOT"

    RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$$"
    RUN_DIR="$REPORT_ROOT/$RUN_ID"
    local suffix=0
    while [[ -e "$RUN_DIR" ]]; do
        suffix=$((suffix + 1))
        RUN_DIR="$REPORT_ROOT/$RUN_ID-$suffix"
    done

    mkdir -p \
        "$RUN_DIR/backend-sbom" \
        "$RUN_DIR/frontend-sbom" \
        "$RUN_DIR/frontend-audit" \
        "$RUN_DIR/backend-audit" \
        "$RUN_DIR/logs"
    STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}

record_failure() {
    FAILURES+=("$1")
}

run_backend_sbom() {
    local output_dir="$RUN_DIR/backend-sbom"
    echo "==> Generating backend CycloneDX aggregate SBOM"

    if env -u NVD_API_KEY JAVA_HOME="$JAVA_HOME_FOR_BUILD" mvn \
        -B -ntp -P supply-chain \
        -Ddependency-check.skip=true \
        -Dcyclonedx.skip=false \
        -DskipTests \
        "-Dsupply-chain.report.directory=$output_dir" \
        verify >"$RUN_DIR/logs/maven-sbom.log" 2>&1
    then
        if json_object_file_is_non_empty "$output_dir/bom.json"; then
            BACKEND_SBOM_STATUS=PASS
        else
            BACKEND_SBOM_STATUS=FAIL
            record_failure "backend CycloneDX JSON is missing, empty, or invalid"
        fi
    else
        local rc=$?
        BACKEND_SBOM_STATUS=FAIL
        record_failure "backend CycloneDX failed (exit $rc)"
    fi
}

run_frontend_sbom() {
    local output_file="$RUN_DIR/frontend-sbom/bom.json"
    echo "==> Generating frontend CycloneDX SBOM"

    if npm --prefix "$REPO_ROOT/frontend" sbom --sbom-format=cyclonedx \
        >"$output_file" 2>"$RUN_DIR/logs/npm-sbom.log"
    then
        if json_object_file_is_non_empty "$output_file"; then
            FRONTEND_SBOM_STATUS=PASS
        else
            FRONTEND_SBOM_STATUS=FAIL
            record_failure "frontend CycloneDX JSON is missing, empty, or invalid"
        fi
    else
        local rc=$?
        FRONTEND_SBOM_STATUS=FAIL
        record_failure "frontend CycloneDX failed (exit $rc)"
    fi
}

run_sbom() {
    run_backend_sbom
    run_frontend_sbom
}

create_nvd_settings() {
    TEMP_SETTINGS_FILE="$(mktemp "${TMPDIR:-/tmp}/kk-studio-supply-chain-settings.XXXXXX.xml")"
    if ! chmod 600 "$TEMP_SETTINGS_FILE"; then
        return 1
    fi

    if ! node --input-type=module - "$TEMP_SETTINGS_FILE" >/dev/null 2>&1 <<'NODE'
import { chmodSync, writeFileSync } from 'node:fs'

const file = process.argv[2]
const apiKey = process.env.NVD_API_KEY
if (!apiKey) {
    process.exit(1)
}

const xmlEscape = value =>
    value.replace(/[<>&'"]/g, character => {
        const entities = {
            '<': '&lt;',
            '>': '&gt;',
            '&': '&amp;',
            "'": '&apos;',
            '"': '&quot;',
        }
        return entities[character]
    })

const settings = `<?xml version="1.0" encoding="UTF-8"?>
<settings>
  <servers>
    <server>
      <id>kk-studio-supply-chain-nvd</id>
      <password>${xmlEscape(apiKey)}</password>
    </server>
  </servers>
</settings>
`

writeFileSync(file, settings, { mode: 0o600 })
chmodSync(file, 0o600)
NODE
    then
        return 1
    fi
}

validate_dependency_check_reports() {
    local output_dir="$RUN_DIR/backend-audit"
    local report
    for report in \
        "$output_dir/dependency-check-report.html" \
        "$output_dir/dependency-check-report.json" \
        "$output_dir/dependency-check-report.sarif"
    do
        [[ -s "$report" ]] || return 1
    done

    json_object_file_is_non_empty "$output_dir/dependency-check-report.json" || return 1
    json_object_file_is_non_empty "$output_dir/dependency-check-report.sarif" || return 1
}

run_npm_audit() {
    local output_file="$RUN_DIR/frontend-audit/audit.json"
    echo "==> Running npm audit (dev dependencies are included by default)"

    if npm --prefix "$REPO_ROOT/frontend" audit --audit-level=high --json \
        >"$output_file" 2>"$RUN_DIR/logs/npm-audit.log"
    then
        if json_object_file_is_non_empty "$output_file"; then
            NPM_AUDIT_STATUS=PASS
        else
            NPM_AUDIT_STATUS=FAIL
            record_failure "npm audit JSON is missing, empty, or invalid"
        fi
    else
        local rc=$?
        NPM_AUDIT_STATUS=FAIL
        if json_object_file_is_non_empty "$output_file"; then
            record_failure "npm audit found high-severity issues or failed the online audit (exit $rc)"
        else
            record_failure "npm audit did not produce valid JSON (exit $rc)"
        fi
    fi
}

run_maven_audit() {
    local output_dir="$RUN_DIR/backend-audit"
    local maven_settings_args=()
    local maven_env=(env -u NVD_API_KEY JAVA_HOME="$JAVA_HOME_FOR_BUILD")

    if [[ -n "${NVD_API_KEY:-}" ]]; then
        printf '%s\n' \
            "NVD_API_KEY is present; the value is injected through a temporary mode-600 Maven settings.xml." \
            >"$RUN_DIR/logs/nvd-mode.log"
        if ! create_nvd_settings; then
            MAVEN_AUDIT_STATUS=FAIL
            record_failure "could not create the protected temporary NVD settings.xml"
            return 0
        fi
        maven_settings_args=(-s "$TEMP_SETTINGS_FILE" "-DnvdApiServerId=$NVD_SETTINGS_SERVER_ID")
    else
        printf '%s\n' \
            "NVD_API_KEY is not set; using the official no-key NVD path, which is slower and may take a long time on the first update." \
            | tee "$RUN_DIR/logs/nvd-mode.log"
    fi

    echo "==> Running Maven Dependency-Check aggregate gate"
    if "${maven_env[@]}" mvn \
        -B -ntp -P supply-chain \
        -Dcyclonedx.skip=true \
        -Ddependency-check.skip=false \
        -DskipTests \
        "-Dsupply-chain.report.directory=$output_dir" \
        "${maven_settings_args[@]}" \
        verify >"$RUN_DIR/logs/maven-audit.log" 2>&1
    then
        if validate_dependency_check_reports; then
            MAVEN_AUDIT_STATUS=PASS
        else
            MAVEN_AUDIT_STATUS=FAIL
            record_failure "Dependency-Check did not produce valid HTML, JSON, and SARIF reports"
        fi
    else
        local rc=$?
        MAVEN_AUDIT_STATUS=FAIL
        record_failure "Maven Dependency-Check failed closed (exit $rc); inspect backend-audit and logs"
    fi
}

run_audit() {
    run_npm_audit
    run_maven_audit
}

write_summary() {
    local status=$1
    local finished_at=$2
    local failure_lines=
    if ((${#FAILURES[@]} > 0)); then
        failure_lines="$(printf '%s\n' "${FAILURES[@]}")"
    fi

    SUPPLY_CHAIN_SUMMARY_FILE="$RUN_DIR/summary.json" \
        SUPPLY_CHAIN_RUN_ID="$RUN_ID" \
        SUPPLY_CHAIN_MODE="$MODE" \
        SUPPLY_CHAIN_STARTED_AT="$STARTED_AT" \
        SUPPLY_CHAIN_FINISHED_AT="$finished_at" \
        SUPPLY_CHAIN_STATUS="$status" \
        SUPPLY_CHAIN_BACKEND_SBOM_STATUS="$BACKEND_SBOM_STATUS" \
        SUPPLY_CHAIN_FRONTEND_SBOM_STATUS="$FRONTEND_SBOM_STATUS" \
        SUPPLY_CHAIN_NPM_AUDIT_STATUS="$NPM_AUDIT_STATUS" \
        SUPPLY_CHAIN_MAVEN_AUDIT_STATUS="$MAVEN_AUDIT_STATUS" \
        SUPPLY_CHAIN_FAILURES="$failure_lines" \
        node --input-type=module <<'NODE'
import { writeFileSync } from 'node:fs'

const env = process.env
const failures = env.SUPPLY_CHAIN_FAILURES
    ? env.SUPPLY_CHAIN_FAILURES.split('\n').filter(Boolean)
    : []
const report = {
    runId: env.SUPPLY_CHAIN_RUN_ID,
    mode: env.SUPPLY_CHAIN_MODE,
    startedAt: env.SUPPLY_CHAIN_STARTED_AT,
    finishedAt: env.SUPPLY_CHAIN_FINISHED_AT,
    status: env.SUPPLY_CHAIN_STATUS,
    checks: [
        {
            name: 'backend-sbom',
            status: env.SUPPLY_CHAIN_BACKEND_SBOM_STATUS,
            artifact: 'backend-sbom/bom.json',
            log: 'logs/maven-sbom.log',
        },
        {
            name: 'frontend-sbom',
            status: env.SUPPLY_CHAIN_FRONTEND_SBOM_STATUS,
            artifact: 'frontend-sbom/bom.json',
            log: 'logs/npm-sbom.log',
        },
        {
            name: 'npm-audit',
            status: env.SUPPLY_CHAIN_NPM_AUDIT_STATUS,
            artifact: 'frontend-audit/audit.json',
            log: 'logs/npm-audit.log',
        },
        {
            name: 'maven-dependency-check',
            status: env.SUPPLY_CHAIN_MAVEN_AUDIT_STATUS,
            artifacts: [
                'backend-audit/dependency-check-report.html',
                'backend-audit/dependency-check-report.json',
                'backend-audit/dependency-check-report.sarif',
            ],
            log: 'logs/maven-audit.log',
        },
    ],
    failures,
}

writeFileSync(env.SUPPLY_CHAIN_SUMMARY_FILE, `${JSON.stringify(report, null, 2)}\n`)
NODE

    cat >"$RUN_DIR/summary.md" <<EOF
# Supply-chain quality gate

- Run: \`$RUN_ID\`
- Mode: \`$MODE\`
- Status: **$status**
- Started: \`$STARTED_AT\`
- Finished: \`$finished_at\`

## Checks

| Check | Status | Artifact | Log |
| --- | --- | --- | --- |
| Backend CycloneDX aggregate | $BACKEND_SBOM_STATUS | [backend-sbom/bom.json](backend-sbom/bom.json) | [logs/maven-sbom.log](logs/maven-sbom.log) |
| Frontend CycloneDX | $FRONTEND_SBOM_STATUS | [frontend-sbom/bom.json](frontend-sbom/bom.json) | [logs/npm-sbom.log](logs/npm-sbom.log) |
| npm audit (high) | $NPM_AUDIT_STATUS | [frontend-audit/audit.json](frontend-audit/audit.json) | [logs/npm-audit.log](logs/npm-audit.log) |
| Maven Dependency-Check | $MAVEN_AUDIT_STATUS | [HTML](backend-audit/dependency-check-report.html), [JSON](backend-audit/dependency-check-report.json), [SARIF](backend-audit/dependency-check-report.sarif) | [logs/maven-audit.log](logs/maven-audit.log) |

EOF

    if ((${#FAILURES[@]} == 0)); then
        cat >>"$RUN_DIR/summary.md" <<'EOF'
## Notes

All requested checks completed successfully.
EOF
    else
        cat >>"$RUN_DIR/summary.md" <<'EOF'
## Failures

The gate is fail-closed. A non-zero tool result, unavailable online source, or invalid/missing report keeps this run failed.
EOF
        local failure
        for failure in "${FAILURES[@]}"; do
            printf '%s\n' "- $failure" >>"$RUN_DIR/summary.md"
        done
    fi
}

publish_latest() {
    local latest="$REPORT_ROOT/latest"
    local latest_tmp="$REPORT_ROOT/.latest.tmp.$$"
    rm -rf -- "$latest_tmp" "$latest"
    if ! cp -a -- "$RUN_DIR" "$latest_tmp"; then
        rm -rf -- "$latest_tmp"
        return 1
    fi
    if ! mv -- "$latest_tmp" "$latest"; then
        rm -rf -- "$latest_tmp"
        return 1
    fi
    printf '%s\n' "$RUN_DIR" >"$REPORT_ROOT/LATEST_RUN.txt"
}

finalize_report() {
    if ((${#FAILURES[@]} == 0)); then
        OVERALL_STATUS=PASS
    else
        OVERALL_STATUS=FAIL
    fi

    FINISHED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    write_summary "$OVERALL_STATUS" "$FINISHED_AT"
    if ! publish_latest; then
        record_failure "could not update the latest supply-chain report copy"
        OVERALL_STATUS=FAIL
        write_summary "$OVERALL_STATUS" "$FINISHED_AT"
        return 1
    fi

    echo "Read report: $REPORT_ROOT/latest/summary.md"
    [[ "$OVERALL_STATUS" == PASS ]]
}

cleanup() {
    if [[ -n "$TEMP_SETTINGS_FILE" ]]; then
        rm -f -- "$TEMP_SETTINGS_FILE"
    fi
}

trap cleanup EXIT

main() {
    if [[ $# -ne 1 ]]; then
        usage >&2
        return 2
    fi

    case "$1" in
        help|-h|--help)
            usage
            return 0
            ;;
        test)
            require_cmd node
            exec node --test "$SCRIPT_DIR/supply-chain/tests"/*.test.mjs
            ;;
        sbom|audit|all)
            MODE=$1
            ;;
        *)
            echo "ERROR: unknown command: $1" >&2
            usage >&2
            return 2
            ;;
    esac

    require_cmd mvn
    require_cmd npm
    require_cmd node
    JAVA_HOME_FOR_BUILD="$(resolve_java_home)"

    cd "$REPO_ROOT"
    init_report

    case "$MODE" in
        sbom)
            run_sbom
            ;;
        audit)
            run_audit
            ;;
        all)
            run_sbom
            run_audit
            ;;
    esac

    if finalize_report; then
        return 0
    fi
    return 1
}

main "$@"
