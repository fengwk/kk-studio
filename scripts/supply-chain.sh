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
NVD_DATAFEED_URL=https://nvd.nist.gov/feeds/json/cve/2.0/nvdcve-2.0-{0}.json.gz
NPM_AUDIT_REGISTRY=https://registry.npmjs.org
TRIVY_IMAGE=aquasec/trivy@sha256:62b1e65e8869bc4b4c6aa4fa2b21595256c7c2f6018a9d9ad61caf87187c1969
TRIVY_DB_REPOSITORY=public.ecr.aws/aquasecurity/trivy-db:2
TRIVY_JAVA_DB_REPOSITORY=public.ecr.aws/aquasecurity/trivy-java-db:1
TRIVY_CACHE_VOLUME=${SUPPLY_CHAIN_TRIVY_CACHE_VOLUME:-kk-studio-trivy-cache}
APP_IMAGE=${SUPPLY_CHAIN_APP_IMAGE:-kk-studio-app:supply-chain}
DAEMON_IMAGE=${SUPPLY_CHAIN_DAEMON_IMAGE:-kk-studio-daemon:supply-chain}
TRIVY_SKIP_DB_UPDATE=${TRIVY_SKIP_DB_UPDATE:-false}

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
APP_IMAGE_BUILD_STATUS=SKIPPED
DAEMON_IMAGE_BUILD_STATUS=SKIPPED
APP_IMAGE_SMOKE_STATUS=SKIPPED
DAEMON_IMAGE_SMOKE_STATUS=SKIPPED
APP_IMAGE_SCAN_STATUS=SKIPPED
DAEMON_IMAGE_SCAN_STATUS=SKIPPED
APP_IMAGE_ID=
APP_IMAGE_DIGEST=
DAEMON_IMAGE_ID=
DAEMON_IMAGE_DIGEST=
TRIVY_VERSION=UNKNOWN
TRIVY_VERSION_STATUS=SKIPPED
OVERALL_STATUS=FAIL
FAILURES=()
TRIVY_DOCKER_RUN_ARGS=()
IMAGE_BUILD_PROXY_ARGS=()
IMAGE_BUILD_STANDARD_PROXY_ARGS=()
IMAGE_BUILD_NETWORK=default
RAW_LOG_TEMP=

usage() {
    cat <<'EOF'
Usage: ./scripts/supply-chain.sh <command>

Commands:
  sbom    Generate and validate backend and frontend CycloneDX SBOMs
  audit   Run npm audit and the Maven Dependency-Check gate
  image   Build and scan the current app and daemon images with pinned Trivy
  all     Run sbom, dependency audit, and image scan in one report
  test    Run permanent supply-chain script tests only
  help    Show this help

Environment:
  SUPPLY_CHAIN_REPORT_ROOT  Report root, relative to the repository by default
                            (default: reports/supply-chain)
  NVD_API_KEY               Optional NVD API key; never printed or passed on the
                            Maven command line
  JAVA_HOME_21               JDK 21 used for Maven (JAVA_HOME is a fallback)
  SUPPLY_CHAIN_APP_IMAGE    App image tag (default: kk-studio-app:supply-chain)
  SUPPLY_CHAIN_DAEMON_IMAGE Daemon image tag (default: kk-studio-daemon:supply-chain)
  SUPPLY_CHAIN_TRIVY_CACHE_VOLUME
                            Named Trivy cache volume (default: kk-studio-trivy-cache)
  TRIVY_SKIP_DB_UPDATE      Use the existing named cache in offline mode when true
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

trivy_report_has_no_high_critical() {
    local file=$1
    node --input-type=module - "$file" <<'NODE'
import { readFileSync } from 'node:fs'

const report = JSON.parse(readFileSync(process.argv[2], 'utf8'))
if (!Array.isArray(report.Results)) {
    console.error('Trivy JSON has no Results array')
    process.exit(1)
}

const findings = []
for (const result of report.Results) {
    const vulnerabilities = result.Vulnerabilities ?? []
    if (!Array.isArray(vulnerabilities)) {
        console.error('Trivy JSON has an invalid Vulnerabilities array')
        process.exit(1)
    }
    for (const vulnerability of vulnerabilities) {
        const severity = String(vulnerability.Severity ?? '').toUpperCase()
        if (severity === 'HIGH' || severity === 'CRITICAL') {
            const fixedVersion =
                typeof vulnerability.FixedVersion === 'string'
                    ? vulnerability.FixedVersion.trim()
                    : 'no fixed version'
            findings.push(
                `${vulnerability.PkgName ?? '<unknown>'}: ${
                    vulnerability.VulnerabilityID ?? '<unknown>'
                } (${fixedVersion})`,
            )
        }
    }
}

if (findings.length > 0) {
    console.error(
        `Trivy JSON contains ${findings.length} HIGH/CRITICAL vulnerabilities:`,
    )
    for (const finding of findings) {
        console.error(`- ${finding}`)
    }
    process.exit(1)
}
NODE
}

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
        "$RUN_DIR/image" \
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

    if npm --prefix "$REPO_ROOT/frontend" sbom \
        --package-lock-only \
        --sbom-format=cyclonedx \
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

    if npm --prefix "$REPO_ROOT/frontend" audit \
        --audit-level=low \
        "--registry=$NPM_AUDIT_REGISTRY" \
        --json \
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
            record_failure "npm audit found vulnerabilities or failed the online audit (exit $rc)"
        else
            record_failure "npm audit did not produce valid JSON (exit $rc)"
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
    run_npm_audit
    run_maven_audit
}

proxy_uses_loopback() {
    local proxy=$1
    local authority=${proxy#*://}
    local host

    authority=${authority##*@}
    if [[ "$authority" == \[* ]]; then
        host=${authority#\[}
        host=${host%%\]*}
    else
        host=${authority%%:*}
    fi

    case "$host" in
        127.*|localhost|::1)
            return 0
            ;;
        *)
            return 1
            ;;
    esac
}

has_loopback_proxy() {
    local proxy
    for proxy in \
        "${HTTP_PROXY:-}" \
        "${HTTPS_PROXY:-}" \
        "${ALL_PROXY:-}" \
        "${http_proxy:-}" \
        "${https_proxy:-}" \
        "${all_proxy:-}"
    do
        if [[ -n "$proxy" ]] && proxy_uses_loopback "$proxy"; then
            return 0
        fi
    done
    return 1
}

prepare_image_build() {
    local http_proxy_value=${HTTP_PROXY:-${http_proxy:-}}
    local https_proxy_value=${HTTPS_PROXY:-${https_proxy:-}}
    local all_proxy_value=${ALL_PROXY:-${all_proxy:-}}
    local no_proxy_value=${NO_PROXY:-${no_proxy:-}}

    IMAGE_BUILD_PROXY_ARGS=()
    IMAGE_BUILD_STANDARD_PROXY_ARGS=()
    if [[ -n "$http_proxy_value" ]]; then
        IMAGE_BUILD_STANDARD_PROXY_ARGS+=(--build-arg "HTTP_PROXY=$http_proxy_value")
    fi
    if [[ -n "$https_proxy_value" ]]; then
        IMAGE_BUILD_STANDARD_PROXY_ARGS+=(--build-arg "HTTPS_PROXY=$https_proxy_value")
    fi
    if [[ -n "$all_proxy_value" ]]; then
        IMAGE_BUILD_STANDARD_PROXY_ARGS+=(--build-arg "ALL_PROXY=$all_proxy_value")
    fi
    if [[ -n "$no_proxy_value" ]]; then
        IMAGE_BUILD_STANDARD_PROXY_ARGS+=(--build-arg "NO_PROXY=$no_proxy_value")
    fi
    IMAGE_BUILD_PROXY_ARGS+=(--build-arg "KK_STUDIO_BUILD_HTTP_PROXY=$http_proxy_value")
    IMAGE_BUILD_PROXY_ARGS+=(--build-arg "KK_STUDIO_BUILD_HTTPS_PROXY=$https_proxy_value")
    IMAGE_BUILD_PROXY_ARGS+=(--build-arg "KK_STUDIO_BUILD_NO_PROXY=$no_proxy_value")
    if [[ -n "${SUPPLY_CHAIN_MAVEN_BUILD_OPTS:-}" ]]; then
        IMAGE_BUILD_PROXY_ARGS+=(
            --build-arg
            "KK_STUDIO_MAVEN_BUILD_OPTS=$SUPPLY_CHAIN_MAVEN_BUILD_OPTS"
        )
    fi

    if has_loopback_proxy; then
        IMAGE_BUILD_NETWORK=host
    else
        IMAGE_BUILD_NETWORK=default
    fi
}

redact_proxy_values() {
    local source=$1
    local target=$2
    HTTP_PROXY_VALUE="${HTTP_PROXY:-}" \
        HTTPS_PROXY_VALUE="${HTTPS_PROXY:-}" \
        ALL_PROXY_VALUE="${ALL_PROXY:-}" \
        NO_PROXY_VALUE="${NO_PROXY:-}" \
        MAVEN_BUILD_OPTS_VALUE="${SUPPLY_CHAIN_MAVEN_BUILD_OPTS:-}" \
        http_proxy_value="${http_proxy:-}" \
        https_proxy_value="${https_proxy:-}" \
        all_proxy_value="${all_proxy:-}" \
        no_proxy_value="${no_proxy:-}" \
        node --input-type=module - "$source" "$target" <<'NODE'
import { readFileSync, writeFileSync } from 'node:fs'

const source = process.argv[2]
const target = process.argv[3]
const values = [
    process.env.HTTP_PROXY_VALUE,
    process.env.HTTPS_PROXY_VALUE,
    process.env.ALL_PROXY_VALUE,
    process.env.NO_PROXY_VALUE,
    process.env.MAVEN_BUILD_OPTS_VALUE,
    process.env.http_proxy_value,
    process.env.https_proxy_value,
    process.env.all_proxy_value,
    process.env.no_proxy_value,
]
    .filter(Boolean)
    .sort((left, right) => right.length - left.length)

let content = readFileSync(source, 'utf8')
for (const value of values) {
    content = content.split(value).join('[redacted-proxy]')
}
writeFileSync(target, content)
NODE
}

set_image_build_status() {
    local name=$1
    local status=$2
    case "$name" in
        app)
            APP_IMAGE_BUILD_STATUS=$status
            ;;
        daemon)
            DAEMON_IMAGE_BUILD_STATUS=$status
            ;;
        *)
            record_failure "unknown image build name: $name"
            ;;
    esac
}

set_image_metadata() {
    local name=$1
    local image_id=$2
    local image_digest=$3
    case "$name" in
        app)
            APP_IMAGE_ID=$image_id
            APP_IMAGE_DIGEST=$image_digest
            ;;
        daemon)
            DAEMON_IMAGE_ID=$image_id
            DAEMON_IMAGE_DIGEST=$image_digest
            ;;
        *)
            record_failure "unknown image metadata name: $name"
            ;;
    esac
}

set_image_scan_status() {
    local name=$1
    local status=$2
    case "$name" in
        app)
            APP_IMAGE_SCAN_STATUS=$status
            ;;
        daemon)
            DAEMON_IMAGE_SCAN_STATUS=$status
            ;;
        *)
            record_failure "unknown image scan name: $name"
            ;;
    esac
}

set_image_smoke_status() {
    local name=$1
    local status=$2
    case "$name" in
        app)
            APP_IMAGE_SMOKE_STATUS=$status
            ;;
        daemon)
            DAEMON_IMAGE_SMOKE_STATUS=$status
            ;;
        *)
            record_failure "unknown image smoke name: $name"
            ;;
    esac
}

read_image_metadata() {
    local name=$1
    local image=$2
    local inspect_log="$RUN_DIR/logs/${name}-image-inspect.log"
    local image_id
    local repo_digests
    local image_digest

    if ! image_id=$(docker image inspect --format '{{.Id}}' "$image" 2>"$inspect_log"); then
        record_failure "$name image metadata lookup failed; inspect $inspect_log"
        return 1
    fi
    image_id=$(printf '%s' "$image_id" | tr -d '\r\n')
    if [[ -z "$image_id" ]]; then
        record_failure "$name image metadata did not contain an image id"
        return 1
    fi

    if ! repo_digests=$(
        docker image inspect \
            --format '{{range .RepoDigests}}{{println .}}{{end}}' \
            "$image" 2>>"$inspect_log"
    ); then
        record_failure "$name image digest lookup failed; inspect $inspect_log"
        return 1
    fi
    image_digest=$(printf '%s\n' "$repo_digests" | sed -n '/./{p;q;}')
    if [[ -z "$image_digest" ]]; then
        image_digest=$image_id
    fi

    set_image_metadata "$name" "$image_id" "$image_digest"
}

run_image_build() {
    local name=$1
    local dockerfile=$2
    local image=$3
    local log="$RUN_DIR/logs/${name}-image-build.log"
    local raw_log="$RUN_DIR/logs/.${name}-image-build.log.$$"
    local docker_args=(
        build
        --file "$REPO_ROOT/$dockerfile"
        --tag "$image"
        --network "$IMAGE_BUILD_NETWORK"
    )

    docker_args+=("${IMAGE_BUILD_STANDARD_PROXY_ARGS[@]}")
    if [[ "$name" == app ]]; then
        docker_args+=("${IMAGE_BUILD_PROXY_ARGS[@]}")
    fi

    echo "==> Building $name image from the current source"
    RAW_LOG_TEMP=$raw_log
    local rc=0
    docker "${docker_args[@]}" "$REPO_ROOT" >"$raw_log" 2>&1 || rc=$?
    local redact_rc=0
    redact_proxy_values "$raw_log" "$log" || redact_rc=$?
    rm -f -- "$raw_log"
    RAW_LOG_TEMP=

    if ((redact_rc != 0)); then
        set_image_build_status "$name" FAIL
        record_failure "$name image build log could not be redacted"
    elif ((rc == 0)); then
        if read_image_metadata "$name" "$image"; then
            set_image_build_status "$name" PASS
        else
            set_image_build_status "$name" FAIL
        fi
    else
        set_image_build_status "$name" FAIL
        record_failure "$name image build failed (exit $rc); inspect $log"
    fi
}

run_image_smoke() {
    local name=$1
    local image=$2
    local build_status
    local log="$RUN_DIR/logs/${name}-image-smoke.log"
    local raw_log="$RUN_DIR/logs/.${name}-image-smoke.log.$$"
    local smoke_script

    if [[ "$name" == app ]]; then
        build_status=$APP_IMAGE_BUILD_STATUS
        smoke_script=$(cat <<'EOF'
set -eu
uid="$(id -u)"
user="$(id -un)"
printf 'uid=%s user=%s\n' "$uid" "$user"
test "$uid" -ne 0
java -version
command -v ffmpeg
command -v ffprobe
command -v curl
EOF
)
    else
        build_status=$DAEMON_IMAGE_BUILD_STATUS
        smoke_script=$(cat <<'EOF'
set -euo pipefail
uid="$(id -u)"
user="$(id -un)"
printf 'uid=%s user=%s\n' "$uid" "$user"
test "$uid" -ne 0
java -version
node_version="$(node --version)"
printf 'node=%s\n' "$node_version"
if [[ ! "$node_version" =~ ^v22\.19\.[0-9]+$ ]]; then
    printf 'unexpected Node version: %s\n' "$node_version" >&2
    exit 1
fi
npm_version="$(npm --version)"
printf 'npm=%s\n' "$npm_version"
if [[ "$npm_version" != 11.19.0 ]]; then
    printf 'unexpected npm version: %s\n' "$npm_version" >&2
    exit 1
fi
command -v bash
command -v git
bash --version
git --version
smoke_dir="$(mktemp -d /workspace/kk-studio-supply-chain-smoke.XXXXXX)"
trap 'rm -rf -- "$smoke_dir"' EXIT
cd "$smoke_dir"
npm init --yes
export npm_config_ignore_scripts=true
npm install --package-lock-only --ignore-scripts --no-audit --no-fund lodash@4.17.21
test -s package-lock.json
node --input-type=module <<'NODE'
import { readFileSync } from 'node:fs'

const lock = JSON.parse(readFileSync('package-lock.json', 'utf8'))
const lodash = lock.packages?.['node_modules/lodash'] ?? lock.dependencies?.lodash
if (!lodash || lodash.version !== '4.17.21') {
    console.error('package-lock.json does not contain lodash@4.17.21')
    process.exit(1)
}
NODE
printf '%s\n' 'package-lock.json contains lodash@4.17.21'
EOF
)
    fi

    if [[ "$build_status" != PASS ]]; then
        set_image_smoke_status "$name" FAIL
        printf '%s\n' \
            "$name image smoke was not run because its image build did not pass" \
            >"$log"
        record_failure "$name image smoke was not run because its image build did not pass"
        return 0
    fi

    echo "==> Running $name image functional smoke"
    RAW_LOG_TEMP=$raw_log
    local rc=0
    if [[ "$name" == app ]]; then
        docker run --rm --entrypoint /bin/sh "$image" -ceu "$smoke_script" \
            >"$raw_log" 2>&1 || rc=$?
    else
        docker run --rm --entrypoint /bin/bash "$image" -ceu "$smoke_script" \
            >"$raw_log" 2>&1 || rc=$?
    fi
    local redact_rc=0
    redact_proxy_values "$raw_log" "$log" || redact_rc=$?
    if ((redact_rc != 0)); then
        printf '%s\n' \
            'image smoke log redaction failed; command output was discarded' \
            >"$log"
    fi
    rm -f -- "$raw_log"
    RAW_LOG_TEMP=

    if ((redact_rc != 0)); then
        set_image_smoke_status "$name" FAIL
        record_failure "$name image smoke log could not be redacted"
    elif ((rc != 0)); then
        set_image_smoke_status "$name" FAIL
        record_failure "$name image smoke failed (exit $rc); inspect $log"
    else
        set_image_smoke_status "$name" PASS
    fi
}

prepare_trivy_docker_run() {
    TRIVY_DOCKER_RUN_ARGS=(
        run
        --rm
        --volume /var/run/docker.sock:/var/run/docker.sock
        --volume "$TRIVY_CACHE_VOLUME:/root/.cache/trivy"
    )
    if has_loopback_proxy; then
        TRIVY_DOCKER_RUN_ARGS+=(--network host)
    fi

    local name
    for name in HTTP_PROXY HTTPS_PROXY ALL_PROXY NO_PROXY http_proxy https_proxy all_proxy no_proxy; do
        if [[ -n "${!name:-}" ]]; then
            # Pass the variable name only so proxy credentials never enter the
            # scanner command line or the supply-chain report.
            TRIVY_DOCKER_RUN_ARGS+=(--env "$name")
        fi
    done
}

run_trivy_version() {
    local version_file="$RUN_DIR/image/trivy-version.json"
    local log="$RUN_DIR/logs/trivy-version.log"
    local version

    prepare_trivy_docker_run
    echo "==> Reading the pinned Trivy scanner version"
    local rc=0
    docker "${TRIVY_DOCKER_RUN_ARGS[@]}" "$TRIVY_IMAGE" version --format json \
        >"$version_file" 2>"$log" || rc=$?
    if ((rc != 0)); then
        TRIVY_VERSION_STATUS=FAIL
        record_failure "Trivy version check failed (exit $rc); inspect $log"
        return 0
    fi

    if ! json_object_file_is_non_empty "$version_file"; then
        TRIVY_VERSION_STATUS=FAIL
        record_failure "Trivy version output is missing, empty, or invalid"
        return 0
    fi

    if ! version=$(
        node --input-type=module - "$version_file" <<'NODE'
import { readFileSync } from 'node:fs'

const document = JSON.parse(readFileSync(process.argv[2], 'utf8'))
const version = document.Version ?? document.version
if (typeof version !== 'string' || version.trim() === '') {
    process.exit(1)
}
process.stdout.write(version.trim())
NODE
    ); then
        TRIVY_VERSION_STATUS=FAIL
        record_failure "Trivy version output has no version field"
        return 0
    fi

    TRIVY_VERSION=$version
    if [[ "$TRIVY_VERSION" != 0.74.0 ]]; then
        TRIVY_VERSION_STATUS=FAIL
        record_failure "pinned Trivy image reported unexpected version $TRIVY_VERSION"
        return 0
    fi
    TRIVY_VERSION_STATUS=PASS
}

run_image_scan() {
    local name=$1
    local image=$2
    local build_status
    local report="$RUN_DIR/image/$name.json"
    local log="$RUN_DIR/logs/${name}-image-scan.log"
    local trivy_args=(
        --cache-dir /root/.cache/trivy
        image
        --db-repository "$TRIVY_DB_REPOSITORY"
        --java-db-repository "$TRIVY_JAVA_DB_REPOSITORY"
        --exit-code 1
        --scanners vuln
    )

    if [[ "$name" == app ]]; then
        build_status=$APP_IMAGE_BUILD_STATUS
    else
        build_status=$DAEMON_IMAGE_BUILD_STATUS
    fi

    if [[ "$build_status" != PASS ]]; then
        set_image_scan_status "$name" FAIL
        record_failure "$name image scan was not run because its image build did not pass"
        return 0
    fi

    if [[ "$TRIVY_SKIP_DB_UPDATE" == true ]]; then
        trivy_args+=(--skip-db-update --skip-java-db-update --offline-scan)
    fi
    trivy_args+=(
        --format json
        --severity HIGH,CRITICAL
        --ignore-unfixed
        "$image"
    )

    prepare_trivy_docker_run
    echo "==> Scanning $name image with pinned Trivy"
    local rc=0
    docker "${TRIVY_DOCKER_RUN_ARGS[@]}" "$TRIVY_IMAGE" "${trivy_args[@]}" \
        >"$report" 2>"$log" || rc=$?

    if ((rc != 0)); then
        set_image_scan_status "$name" FAIL
        record_failure "$name Trivy scan failed (exit $rc); inspect $report and $log"
        return 0
    fi
    if ! json_object_file_is_non_empty "$report"; then
        set_image_scan_status "$name" FAIL
        record_failure "$name Trivy JSON report is missing, empty, or invalid"
        return 0
    fi
    if ! trivy_report_has_no_high_critical "$report" >>"$log" 2>&1; then
        set_image_scan_status "$name" FAIL
        record_failure "$name Trivy report contains HIGH/CRITICAL vulnerabilities"
        return 0
    fi
    set_image_scan_status "$name" PASS
}

run_image() {
    prepare_image_build
    run_image_build app deploy/local/Dockerfile "$APP_IMAGE"
    run_image_smoke app "$APP_IMAGE"
    run_image_build daemon deploy/reliability/daemon.Dockerfile "$DAEMON_IMAGE"
    run_image_smoke daemon "$DAEMON_IMAGE"
    run_trivy_version
    run_image_scan app "$APP_IMAGE"
    run_image_scan daemon "$DAEMON_IMAGE"
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
| npm audit (low) | $NPM_AUDIT_STATUS | [frontend-audit/audit.json](frontend-audit/audit.json) | [logs/npm-audit.log](logs/npm-audit.log) |
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
                success_note="The requested backend and frontend SBOM checks completed successfully, and both CycloneDX JSON reports are non-empty and parseable."
                ;;
            audit)
                success_note="The requested dependency audit checks completed successfully. npm audit reported no findings at the low threshold, and Dependency-Check JSON contains zero non-suppressed vulnerabilities."
                ;;
            image)
                success_note="The requested image checks completed successfully. Both image functional smokes passed, and each Trivy report contains zero fixable HIGH/CRITICAL vulnerabilities."
                ;;
            all)
                success_note="All requested checks completed successfully. Both SBOMs are valid, npm audit and Dependency-Check contain zero non-suppressed vulnerabilities, both image functional smokes passed, and each Trivy report contains zero fixable HIGH/CRITICAL vulnerabilities."
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
        sbom|audit|image|all)
            MODE=$1
            ;;
        *)
            echo "ERROR: unknown command: $1" >&2
            usage >&2
            return 2
            ;;
    esac

    require_cmd node
    if [[ "$MODE" == image ]]; then
        require_cmd docker
    else
        require_cmd mvn
        require_cmd npm
        JAVA_HOME_FOR_BUILD="$(resolve_java_home)"
    fi

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
            run_image
            ;;
        image)
            run_image
            ;;
    esac

    if finalize_report; then
        return 0
    fi
    return 1
}

main "$@"
