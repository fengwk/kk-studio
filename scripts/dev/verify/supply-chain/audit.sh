# scripts/dev/verify/supply-chain/audit.sh — dependency audit subcommand operations.
#
# Sourced by run.sh: npm audits and the Maven Dependency-Check aggregate share the
# same report directory, failure list and Java home as the SBOM/image checks.

dependency_check_report_has_no_vulnerabilities() {
    local file=$1
    node --input-type=module - "$file" <<'NODE'
import { readFileSync } from 'node:fs'

const report = JSON.parse(readFileSync(process.argv[2], 'utf8'))
if (!Array.isArray(report.dependencies)) {
    console.error('Dependency-Check JSON has no dependencies array')
    process.exit(1)
}

const findings = []
for (const dependency of report.dependencies) {
    if (dependency.vulnerabilities === undefined) {
        continue
    }
    if (!Array.isArray(dependency.vulnerabilities)) {
        console.error('Dependency-Check JSON has an invalid vulnerabilities array')
        process.exit(1)
    }
    const packageId = dependency.packages?.[0]?.id ?? dependency.fileName ?? '<unknown>'
    for (const vulnerability of dependency.vulnerabilities) {
        const score =
            vulnerability.cvssv4?.baseScore ??
            vulnerability.cvssv3?.baseScore ??
            vulnerability.cvssv2?.score
        const scoreText = score === undefined ? '' : ` (${score})`
        findings.push(`${packageId}: ${vulnerability.name ?? '<unknown>'}${scoreText}`)
    }
}

if (findings.length > 0) {
    console.error(
        `Dependency-Check JSON contains ${findings.length} non-suppressed vulnerabilities:`,
    )
    for (const finding of findings) {
        console.error(`- ${finding}`)
    }
    process.exit(1)
}
NODE
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

npm_audit_output_file() {
    case "$1" in
        frontend) printf '%s' "$RUN_DIR/frontend-audit/audit.json" ;;
        daemon) printf '%s' "$RUN_DIR/daemon-audit/audit.json" ;;
        *) die "unknown npm project: $1" ;;
    esac
}

npm_audit_log_file() {
    case "$1" in
        frontend) printf '%s' "$RUN_DIR/logs/npm-audit.log" ;;
        daemon) printf '%s' "$RUN_DIR/logs/npm-daemon-audit.log" ;;
        *) die "unknown npm project: $1" ;;
    esac
}

set_npm_audit_status() {
    case "$1" in
        frontend) NPM_AUDIT_STATUS=$2 ;;
        daemon) DAEMON_AUDIT_STATUS=$2 ;;
        *) die "unknown npm project: $1" ;;
    esac
}

# 每个 npm 项目各自审计、各自记录状态与报告；任一项目命中漏洞都让整体门禁失败。
run_npm_audit() {
    local project=$1
    local output_file log_file
    output_file=$(npm_audit_output_file "$project")
    log_file=$(npm_audit_log_file "$project")
    echo "==> Running $project npm audit (dev dependencies are included by default)"

    if npm --prefix "$REPO_ROOT/$project" audit \
        --audit-level=low \
        "--registry=$NPM_AUDIT_REGISTRY" \
        --json \
        >"$output_file" 2>"$log_file"
    then
        if json_object_file_is_non_empty "$output_file"; then
            set_npm_audit_status "$project" PASS
        else
            set_npm_audit_status "$project" FAIL
            record_failure "$project npm audit JSON is missing, empty, or invalid"
        fi
    else
        local rc=$?
        set_npm_audit_status "$project" FAIL
        if json_object_file_is_non_empty "$output_file"; then
            record_failure "$project npm audit found vulnerabilities or failed the online audit (exit $rc)"
        else
            record_failure "$project npm audit did not produce valid JSON (exit $rc)"
        fi
    fi
}

run_maven_audit() {
    local output_dir="$RUN_DIR/backend-audit"
    local maven_settings_args=()
    local nvd_source_args=()
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
        nvd_source_args=("-DnvdDatafeedUrl=$NVD_DATAFEED_URL")
        printf '%s\n' \
            "NVD_API_KEY is not set; using the official NVD JSON 2.0 data feed, which is slower and may take a long time on the first update." \
            | tee "$RUN_DIR/logs/nvd-mode.log"
    fi

    echo "==> Running Maven Dependency-Check aggregate gate"
    if "${maven_env[@]}" mvn \
        -B -ntp -P supply-chain \
        -Dcyclonedx.skip=true \
        -Ddependency-check.skip=false \
        -DskipTests \
        "-Dsupply-chain.report.directory=$output_dir" \
        "${nvd_source_args[@]}" \
        "${maven_settings_args[@]}" \
        verify >"$RUN_DIR/logs/maven-audit.log" 2>&1
    then
        if validate_dependency_check_reports && \
            dependency_check_report_has_no_vulnerabilities \
                "$output_dir/dependency-check-report.json"; then
            MAVEN_AUDIT_STATUS=PASS
        else
            MAVEN_AUDIT_STATUS=FAIL
            record_failure "Dependency-Check did not produce valid reports or has non-suppressed vulnerabilities"
        fi
    else
        local rc=$?
        MAVEN_AUDIT_STATUS=FAIL
        record_failure "Maven Dependency-Check failed closed (exit $rc); inspect backend-audit and logs"
    fi
}

run_audit() {
    local project
    for project in "${NPM_PROJECTS[@]}"; do
        run_npm_audit "$project"
    done
    run_maven_audit
}
