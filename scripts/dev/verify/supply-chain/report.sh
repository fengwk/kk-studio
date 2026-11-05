# scripts/dev/verify/supply-chain/report.sh — report aggregation for every mode.
#
# Sourced by run.sh: sbom/audit/image/all all write into the single RUN_DIR below,
# and finalize_report publishes the one timestamped run plus `latest`.

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
        "$RUN_DIR/daemon-sbom" \
        "$RUN_DIR/frontend-audit" \
        "$RUN_DIR/daemon-audit" \
        "$RUN_DIR/backend-audit" \
        "$RUN_DIR/image" \
        "$RUN_DIR/logs"
    STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}

record_failure() {
    FAILURES+=("$1")
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
        SUPPLY_CHAIN_DAEMON_SBOM_STATUS="$DAEMON_SBOM_STATUS" \
        SUPPLY_CHAIN_NPM_AUDIT_STATUS="$NPM_AUDIT_STATUS" \
        SUPPLY_CHAIN_DAEMON_AUDIT_STATUS="$DAEMON_AUDIT_STATUS" \
        SUPPLY_CHAIN_MAVEN_AUDIT_STATUS="$MAVEN_AUDIT_STATUS" \
        SUPPLY_CHAIN_APP_IMAGE_BUILD_STATUS="$APP_IMAGE_BUILD_STATUS" \
        SUPPLY_CHAIN_DAEMON_IMAGE_BUILD_STATUS="$DAEMON_IMAGE_BUILD_STATUS" \
        SUPPLY_CHAIN_APP_IMAGE_SMOKE_STATUS="$APP_IMAGE_SMOKE_STATUS" \
        SUPPLY_CHAIN_DAEMON_IMAGE_SMOKE_STATUS="$DAEMON_IMAGE_SMOKE_STATUS" \
        SUPPLY_CHAIN_APP_IMAGE_SCAN_STATUS="$APP_IMAGE_SCAN_STATUS" \
        SUPPLY_CHAIN_DAEMON_IMAGE_SCAN_STATUS="$DAEMON_IMAGE_SCAN_STATUS" \
        SUPPLY_CHAIN_APP_IMAGE="$APP_IMAGE" \
        SUPPLY_CHAIN_DAEMON_IMAGE="$DAEMON_IMAGE" \
        SUPPLY_CHAIN_APP_IMAGE_ID="$APP_IMAGE_ID" \
        SUPPLY_CHAIN_APP_IMAGE_DIGEST="$APP_IMAGE_DIGEST" \
        SUPPLY_CHAIN_DAEMON_IMAGE_ID="$DAEMON_IMAGE_ID" \
        SUPPLY_CHAIN_DAEMON_IMAGE_DIGEST="$DAEMON_IMAGE_DIGEST" \
        SUPPLY_CHAIN_TRIVY_IMAGE="$TRIVY_IMAGE" \
        SUPPLY_CHAIN_TRIVY_VERSION="$TRIVY_VERSION" \
        SUPPLY_CHAIN_TRIVY_VERSION_STATUS="$TRIVY_VERSION_STATUS" \
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
            name: 'daemon-sbom',
            status: env.SUPPLY_CHAIN_DAEMON_SBOM_STATUS,
            artifact: 'daemon-sbom/bom.json',
            log: 'logs/npm-daemon-sbom.log',
        },
        {
            name: 'npm-audit',
            status: env.SUPPLY_CHAIN_NPM_AUDIT_STATUS,
            artifact: 'frontend-audit/audit.json',
            log: 'logs/npm-audit.log',
        },
        {
            name: 'daemon-audit',
            status: env.SUPPLY_CHAIN_DAEMON_AUDIT_STATUS,
            artifact: 'daemon-audit/audit.json',
            log: 'logs/npm-daemon-audit.log',
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
        {
            name: 'app-image-build',
            status: env.SUPPLY_CHAIN_APP_IMAGE_BUILD_STATUS,
            image: env.SUPPLY_CHAIN_APP_IMAGE,
            log: 'logs/app-image-build.log',
        },
        {
            name: 'app-image-smoke',
            status: env.SUPPLY_CHAIN_APP_IMAGE_SMOKE_STATUS,
            image: env.SUPPLY_CHAIN_APP_IMAGE,
            log: 'logs/app-image-smoke.log',
        },
        {
            name: 'app-image-scan',
            status: env.SUPPLY_CHAIN_APP_IMAGE_SCAN_STATUS,
            image: env.SUPPLY_CHAIN_APP_IMAGE,
            imageId: env.SUPPLY_CHAIN_APP_IMAGE_ID || null,
            imageDigest: env.SUPPLY_CHAIN_APP_IMAGE_DIGEST || null,
            scannerImage: env.SUPPLY_CHAIN_TRIVY_IMAGE,
            scannerVersion: env.SUPPLY_CHAIN_TRIVY_VERSION,
            scannerVersionStatus: env.SUPPLY_CHAIN_TRIVY_VERSION_STATUS,
            artifact: 'image/app.json',
            log: 'logs/app-image-scan.log',
        },
        {
            name: 'daemon-image-build',
            status: env.SUPPLY_CHAIN_DAEMON_IMAGE_BUILD_STATUS,
            image: env.SUPPLY_CHAIN_DAEMON_IMAGE,
            log: 'logs/daemon-image-build.log',
        },
        {
            name: 'daemon-image-smoke',
            status: env.SUPPLY_CHAIN_DAEMON_IMAGE_SMOKE_STATUS,
            image: env.SUPPLY_CHAIN_DAEMON_IMAGE,
            log: 'logs/daemon-image-smoke.log',
        },
        {
            name: 'daemon-image-scan',
            status: env.SUPPLY_CHAIN_DAEMON_IMAGE_SCAN_STATUS,
            image: env.SUPPLY_CHAIN_DAEMON_IMAGE,
            imageId: env.SUPPLY_CHAIN_DAEMON_IMAGE_ID || null,
            imageDigest: env.SUPPLY_CHAIN_DAEMON_IMAGE_DIGEST || null,
            scannerImage: env.SUPPLY_CHAIN_TRIVY_IMAGE,
            scannerVersion: env.SUPPLY_CHAIN_TRIVY_VERSION,
            scannerVersionStatus: env.SUPPLY_CHAIN_TRIVY_VERSION_STATUS,
            artifact: 'image/daemon.json',
            log: 'logs/daemon-image-scan.log',
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
| Daemon CycloneDX | $DAEMON_SBOM_STATUS | [daemon-sbom/bom.json](daemon-sbom/bom.json) | [logs/npm-daemon-sbom.log](logs/npm-daemon-sbom.log) |
| npm audit (low) | $NPM_AUDIT_STATUS | [frontend-audit/audit.json](frontend-audit/audit.json) | [logs/npm-audit.log](logs/npm-audit.log) |
| Daemon npm audit (low) | $DAEMON_AUDIT_STATUS | [daemon-audit/audit.json](daemon-audit/audit.json) | [logs/npm-daemon-audit.log](logs/npm-daemon-audit.log) |
| Maven Dependency-Check | $MAVEN_AUDIT_STATUS | [HTML](backend-audit/dependency-check-report.html), [JSON](backend-audit/dependency-check-report.json), [SARIF](backend-audit/dependency-check-report.sarif) | [logs/maven-audit.log](logs/maven-audit.log) |
| App image build | $APP_IMAGE_BUILD_STATUS | \`$APP_IMAGE\` | [logs/app-image-build.log](logs/app-image-build.log) |
| App image smoke | $APP_IMAGE_SMOKE_STATUS | \`$APP_IMAGE\` | [logs/app-image-smoke.log](logs/app-image-smoke.log) |
| App image scan | $APP_IMAGE_SCAN_STATUS | [image/app.json](image/app.json) (id: \`$APP_IMAGE_ID\`, digest: \`$APP_IMAGE_DIGEST\`, Trivy: \`$TRIVY_VERSION\`) | [logs/app-image-scan.log](logs/app-image-scan.log) |
| Daemon image build | $DAEMON_IMAGE_BUILD_STATUS | \`$DAEMON_IMAGE\` | [logs/daemon-image-build.log](logs/daemon-image-build.log) |
| Daemon image smoke | $DAEMON_IMAGE_SMOKE_STATUS | \`$DAEMON_IMAGE\` | [logs/daemon-image-smoke.log](logs/daemon-image-smoke.log) |
| Daemon image scan | $DAEMON_IMAGE_SCAN_STATUS | [image/daemon.json](image/daemon.json) (id: \`$DAEMON_IMAGE_ID\`, digest: \`$DAEMON_IMAGE_DIGEST\`, Trivy: \`$TRIVY_VERSION\`) | [logs/daemon-image-scan.log](logs/daemon-image-scan.log) |

EOF

    if ((${#FAILURES[@]} == 0)); then
        local success_note
        case "$MODE" in
            sbom)
                success_note="The requested backend, frontend and daemon SBOM checks completed successfully, and every CycloneDX JSON report is non-empty and parseable."
                ;;
            audit)
                success_note="The requested dependency audit checks completed successfully. npm audit reported no findings at the low threshold for every npm project (frontend and daemon), and Dependency-Check JSON contains zero non-suppressed vulnerabilities."
                ;;
            image)
                success_note="The requested image checks completed successfully. Both image functional smokes passed, and each Trivy report contains zero fixable HIGH/CRITICAL vulnerabilities."
                ;;
            all)
                success_note="All requested checks completed successfully. Every SBOM is valid, npm audit (frontend and daemon) and Dependency-Check contain zero non-suppressed vulnerabilities, both image functional smokes passed, and each Trivy report contains zero fixable HIGH/CRITICAL vulnerabilities."
                ;;
            *)
                success_note="All requested checks completed successfully."
                ;;
        esac
        cat >>"$RUN_DIR/summary.md" <<EOF
## Notes

$success_note
EOF
    else
        cat >>"$RUN_DIR/summary.md" <<'EOF'
## Failures

The gate is fail-closed. A non-zero tool result, unavailable online source, invalid/missing report, or non-suppressed vulnerability keeps this run failed.
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
    if [[ -n "$RAW_LOG_TEMP" ]]; then
        rm -f -- "$RAW_LOG_TEMP"
    fi
    if [[ -n "$TEMP_SETTINGS_FILE" ]]; then
        rm -f -- "$TEMP_SETTINGS_FILE"
    fi
}
