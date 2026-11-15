# scripts/dev/verify/supply-chain/sbom.sh — SBOM subcommand operations.
#
# Sourced by run.sh so the backend aggregate and the frontend npm project write into
# the same RUN_DIR and contribute to one summary.

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

npm_sbom_output_dir() {
    case "$1" in
        frontend) printf '%s' "$RUN_DIR/frontend-sbom" ;;
        *) die "unknown npm project: $1" ;;
    esac
}

npm_sbom_log_file() {
    case "$1" in
        frontend) printf '%s' "$RUN_DIR/logs/npm-sbom.log" ;;
        *) die "unknown npm project: $1" ;;
    esac
}

set_npm_sbom_status() {
    case "$1" in
        frontend) FRONTEND_SBOM_STATUS=$2 ;;
        *) die "unknown npm project: $1" ;;
    esac
}

# 前端 npm 项目单独生成并记录状态与产物；失败不会被后端结论掩盖。
run_npm_sbom() {
    local project=$1
    local output_dir log_file
    output_dir=$(npm_sbom_output_dir "$project")
    log_file=$(npm_sbom_log_file "$project")
    echo "==> Generating $project CycloneDX SBOM"

    if npm --prefix "$REPO_ROOT/$project" sbom \
        --package-lock-only \
        --sbom-format=cyclonedx \
        >"$output_dir/bom.json" 2>"$log_file"
    then
        if json_object_file_is_non_empty "$output_dir/bom.json"; then
            set_npm_sbom_status "$project" PASS
        else
            set_npm_sbom_status "$project" FAIL
            record_failure "$project CycloneDX JSON is missing, empty, or invalid"
        fi
    else
        local rc=$?
        set_npm_sbom_status "$project" FAIL
        record_failure "$project CycloneDX failed (exit $rc)"
    fi
}

run_sbom() {
    run_backend_sbom
    local project
    for project in "${NPM_PROJECTS[@]}"; do
        run_npm_sbom "$project"
    done
}
