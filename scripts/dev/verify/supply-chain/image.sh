# scripts/dev/verify/supply-chain/image.sh — image build, smoke, and Trivy scan.
#
# Sourced by run.sh. Image checks are a separate subcommand but still write into the
# shared report so `all` produces a single summary with one failure list.

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
frozen=/opt/kk-studio/daemon/runtime/code-server/lib/node
entry=/opt/kk-studio/daemon/main.js
test -x "$frozen"
test -f "$entry"
test "$(readlink -f "$(command -v node)")" = "$(readlink -f "$frozen")"
node_version="$(node --version)"
printf 'node=%s\n' "$node_version"
if [[ "$node_version" != v24.* ]]; then
    printf 'the frozen runtime must be Node 24.x, found: %s\n' "$node_version" >&2
    exit 1
fi
if command -v java >/dev/null 2>&1; then
    printf 'the Daemon image must not ship a JRE\n' >&2
    exit 1
fi
npm_version="$(npm --version)"
printf 'npm=%s\n' "$npm_version"
if [[ "$npm_version" != 11.* ]]; then
    printf 'unexpected npm major: %s\n' "$npm_version" >&2
    exit 1
fi
command -v bash
command -v git
bash --version
git --version
expected_version="kk-studio-daemon $(node -p 'require("/opt/kk-studio/daemon/package.json").version')"
reported_version="$(node "$entry" --version)"
printf 'daemon=%s\n' "$reported_version"
if [[ "$reported_version" != "$expected_version" ]]; then
    printf 'daemon --version mismatch: expected %s, got %s\n' "$expected_version" "$reported_version" >&2
    exit 1
fi
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
        if [[ "$name" == daemon ]]; then
            run_npm_cache_probe "$image"
        fi
    fi
}

run_npm_cache_probe() {
    local image=$1
    local report="$RUN_DIR/image/npm-cache-probe.json"
    local log="$RUN_DIR/logs/npm-cache-probe.log"
    local rc=0
    echo "==> Observing daemon npm cache boundaries (not a vulnerability clearance)"
    docker run --rm -i --network none --read-only --tmpfs /tmp --entrypoint node \
        "$image" --input-type=module <"$SCRIPT_DIR/npm-cache-probe.mjs" \
        >"$report" 2>"$log" || rc=$?
    if ((rc != 0)); then
        DAEMON_IMAGE_SMOKE_STATUS=FAIL
        record_failure "daemon npm cache probe failed (exit $rc); inspect $report and $log"
    elif ! node --input-type=module - "$report" >>"$log" 2>&1 <<'NODE'
import { readFileSync } from 'node:fs'

const report = JSON.parse(readFileSync(process.argv[2], 'utf8'))
const expected = [
    ['same-auth-fresh', 'control'],
    ['auth-change-no-vary', 'observation'],
    ['auth-vary', 'protection'],
    ['cookie-vary', 'protection'],
    ['no-store', 'protection'],
    ['no-cache-max-stale', 'observation'],
    ['set-cookie-no-public', 'header-observation'],
]
if (report.status !== 'ASSERTIONS_SATISFIED' ||
    report.runtime?.shared !== false || report.runtime?.uid !== 10001 ||
    !['node', 'npm', 'makeFetchHappen', 'httpCacheSemantics'].every(
        key => typeof report.runtime?.[key] === 'string' && report.runtime[key].length > 0) ||
    report.assertions?.sharedFalse !== true ||
    !Array.isArray(report.errors) || report.errors.length !== 0 ||
    !Array.isArray(report.cases) || report.cases.length !== expected.length ||
    !expected.every(([id, kind]) => {
        const result = report.cases.find(item => item.id === id && item.kind === kind)
        return result && Number.isInteger(result.originHits) && result.originHits >= 1 &&
            ['A', 'B'].includes(result.source) && typeof result.secondBody === 'string' &&
            typeof result.setCookieReplayed === 'boolean' &&
            (result.setCookie === null || typeof result.setCookie === 'string') &&
            ['first', 'second'].every(key => result.cacheStatus &&
                (result.cacheStatus[key] === null || typeof result.cacheStatus[key] === 'string')) &&
            (kind === 'control' || kind === 'protection'
                ? result.status === 'SATISFIED' &&
                    result.originHits === (kind === 'control' ? 1 : 2) &&
                    result.source === (kind === 'control' ? 'A' : 'B') &&
                    (kind !== 'control' || result.cacheStatus.second === 'hit')
                : ['OBSERVED', 'NOT_OBSERVED'].includes(result.status))
    })) {
    throw new Error('npm cache probe expected report is missing or invalid')
}
NODE
    then
        DAEMON_IMAGE_SMOKE_STATUS=FAIL
        record_failure "daemon npm cache probe JSON is missing, empty, or invalid; inspect $report and $log"
    fi
}

prepare_trivy_docker_run() {
    TRIVY_DOCKER_RUN_ARGS=(
        run
        --rm
        --volume /var/run/docker.sock:/var/run/docker.sock
        --volume "$TRIVY_CACHE_VOLUME:/root/.cache/trivy"
    )
    if [[ -n "$TRIVY_NETWORK" ]]; then
        TRIVY_DOCKER_RUN_ARGS+=(--network "$TRIVY_NETWORK")
    elif has_loopback_proxy; then
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
