#!/usr/bin/env bash
# scripts/dev/verify/supply-chain/run.sh — explicit supply-chain SBOM and vulnerability gate
#
# Normal `mvn verify` never activates the online supply-chain profile. This
# entry point creates a timestamped, gitignored report and fails closed when
# a tool, its online source, or its output is unavailable.
#
# Subcommand implementations are sourced into this process: sbom/audit/image/all
# share one RUN_DIR, one FAILURES list and one exit status without a cross-process
# protocol.

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/../../lib/repo-root.sh"
resolve_repo_root "$SCRIPT_DIR"
NVD_SETTINGS_SERVER_ID=kk-studio-supply-chain-nvd
NVD_DATAFEED_URL=https://nvd.nist.gov/feeds/json/cve/2.0/nvdcve-2.0-{0}.json.gz
NPM_AUDIT_REGISTRY=https://registry.npmjs.org
# npm 生态的项目根：SBOM 与漏洞审计按项目分别产出报告与状态，任一项目有漏洞都让门禁失败。
NPM_PROJECTS=(frontend daemon)
TRIVY_IMAGE=aquasec/trivy@sha256:62b1e65e8869bc4b4c6aa4fa2b21595256c7c2f6018a9d9ad61caf87187c1969
TRIVY_DB_REPOSITORY=public.ecr.aws/aquasecurity/trivy-db:2
TRIVY_JAVA_DB_REPOSITORY=public.ecr.aws/aquasecurity/trivy-java-db:1
TRIVY_CACHE_VOLUME=${SUPPLY_CHAIN_TRIVY_CACHE_VOLUME:-kk-studio-trivy-cache}
TRIVY_NETWORK=${SUPPLY_CHAIN_TRIVY_NETWORK:-}
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
DAEMON_SBOM_STATUS=SKIPPED
NPM_AUDIT_STATUS=SKIPPED
DAEMON_AUDIT_STATUS=SKIPPED
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

# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"
# shellcheck source=report.sh
source "$SCRIPT_DIR/report.sh"
# shellcheck source=sbom.sh
source "$SCRIPT_DIR/sbom.sh"
# shellcheck source=audit.sh
source "$SCRIPT_DIR/audit.sh"
# shellcheck source=image.sh
source "$SCRIPT_DIR/image.sh"

usage() {
    cat <<'EOF'
Usage: scripts/dev/verify/supply-chain/run.sh <command>

Commands:
  sbom    Generate and validate backend, frontend and daemon CycloneDX SBOMs
  audit   Run npm audit for every npm project and the Maven Dependency-Check gate
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
  SUPPLY_CHAIN_TRIVY_NETWORK
                            Optional Docker network name/id (e.g. host); empty
                            keeps automatic loopback-proxy network selection
  TRIVY_SKIP_DB_UPDATE      Use the existing named cache in offline mode when true
EOF
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
            exec node --test "$SCRIPT_DIR/tests"/*.test.mjs
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

    if [[ "$MODE" == image || "$MODE" == all ]]; then
        # Accept one network name/id, never Docker flags or shell fragments.
        if [[ -n "$TRIVY_NETWORK" && ! "$TRIVY_NETWORK" =~ ^[a-zA-Z0-9][a-zA-Z0-9_.-]*$ ]]; then
            die "SUPPLY_CHAIN_TRIVY_NETWORK must be a Docker network name/id"
        fi
    fi
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
