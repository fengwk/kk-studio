#!/usr/bin/env bash

# Deterministic repeated regression for the frozen Java reliability test set.
# This runner does not start the backend/frontend/daemon stack; the selected
# JUnit tests own any Testcontainers lifecycle they need.

set -u -o pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# 仓库根解析：KK_STUDIO_REPO_ROOT 优先；否则从脚本位置向上寻找 worktree 根（.git 文件或目录），
# 不依赖本脚本在仓库中的深度。
if [ -n "${KK_STUDIO_REPO_ROOT:-}" ]; then
  REPO_ROOT=$(cd "$KK_STUDIO_REPO_ROOT" && pwd)
else
  REPO_ROOT=$SCRIPT_DIR
  while [ "$REPO_ROOT" != "/" ] && [ ! -e "$REPO_ROOT/.git" ]; do
    REPO_ROOT=$(dirname "$REPO_ROOT")
  done
  if [ ! -e "$REPO_ROOT/.git" ]; then
    echo "ERROR: cannot locate the kk-studio repository root; set KK_STUDIO_REPO_ROOT" >&2
    exit 1
  fi
fi

ITERATIONS=3
REPORT_ROOT="$REPO_ROOT/reports/reliability"
RUN_DIR=
RUN_ID=
RUN_STARTED_AT=
RUN_STARTED_EPOCH=
RUN_FINISHED_AT=
RUN_FINISHED_EPOCH=
JAVA_VERSION=unknown
MAVEN_VERSION=unknown
GIT_COMMIT=unknown
OVERALL_STATUS=fail
FAILURE_REASON=
REPORT_FINALIZED=false
CURRENT_MAVEN_PID=
CURRENT_MAVEN_PGID=
CURRENT_ITERATION_INDEX=
CURRENT_ITERATION_SIGNAL=

TARGET_MODULES=(
  web
  canvas/infra
  harness/infra
  platform
)

TARGET_FQCNS=(
  fun.fengwk.kkstudio.web.events.postgresql.PostgresqlNotificationLoopTest
  fun.fengwk.kkstudio.web.events.postgresql.PostgresqlNotificationLoopPostgresqlIntegrationTest
  fun.fengwk.kkstudio.web.environment.DaemonOutboundSenderTest
  fun.fengwk.kkstudio.web.events.ApplicationEventHubTest
  fun.fengwk.kkstudio.web.events.ApplicationEventWebSocketHandlerTest
  fun.fengwk.kkstudio.canvas.infra.postgresql.CanvasFunctionWorkStoreIntegrationTest
  fun.fengwk.kkstudio.canvas.infra.function.CanvasFunctionDispatcherTest
  fun.fengwk.kkstudio.canvas.infra.function.CanvasFunctionExecutionContextImplTest
  fun.fengwk.kkstudio.canvas.infra.function.CanvasFunctionWorkerHeartbeatTest
  fun.fengwk.kkstudio.harness.infra.dispatch.HarnessWorkDispatcherHandoffTest
  fun.fengwk.kkstudio.harness.infra.dispatch.HarnessWorkDispatcherLifecycleTest
  fun.fengwk.kkstudio.harness.runtime.store.testing.PostgresqlWorkTest
  fun.fengwk.kkstudio.harness.runtime.store.testing.PostgresqlWorkNotificationTest
  fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlRealtimeEventSourceConcurrencyTest
  fun.fengwk.kkstudio.platform.storage.StorageUploadCleanupLeaseIntegrationTest
  fun.fengwk.kkstudio.platform.storage.StorageMaintenanceTest
  fun.fengwk.kkstudio.platform.storage.StorageUploadServiceIntegrationTest
)

MAVEN_TEST_FILTER=
declare -a MAVEN_ARGS=()
declare -a ITERATION_STATUS=()
declare -a ITERATION_STARTED_EPOCH=()
declare -a ITERATION_FINISHED_EPOCH=()
declare -a ITERATION_DURATION_SECONDS=()
declare -a ITERATION_MAVEN_EXIT=()
declare -a ITERATION_MAVEN_SIGNAL=()
declare -a ITERATION_LOG=()
declare -a ITERATION_MATCHED=()
declare -a ITERATION_MISSING=()
declare -a ITERATION_INVALID=()
declare -a ITERATION_MARKERS=()
declare -a ITERATION_REASON=()

usage() {
  cat <<'EOF'
Usage: scripts/dev/verify/reliability/regression.sh [options]

Run the frozen Java reliability regression set repeatedly from the repository
root with JDK 21. The default is three complete iterations.

Options:
  --iterations N       Run N iterations (1..100, default: 3).
  --report-root DIR    Write reports below DIR (default: reports/reliability).
  --help, -h           Show this help without running Maven.

The runner stops on the first failed iteration. Each iteration has an
independent Maven log and Surefire report evidence. Reports are written to a
timestamped directory and copied to latest-regression.
EOF
}

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 2
}

now_epoch() {
  date +%s
}

now_iso() {
  date -u +%Y-%m-%dT%H:%M:%SZ
}

one_line() {
  tr '\n' ' ' | sed -E 's/[[:space:]]+/ /g; s/^ //; s/ $//'
}

target_module() {
  case "$1" in
    fun.fengwk.kkstudio.web.*)
      printf 'web\n'
      ;;
    fun.fengwk.kkstudio.canvas.*)
      printf 'canvas/infra\n'
      ;;
    fun.fengwk.kkstudio.harness.*)
      printf 'harness/infra\n'
      ;;
    fun.fengwk.kkstudio.platform.*)
      printf 'platform\n'
      ;;
    *)
      return 1
      ;;
  esac
}

target_area() {
  case "$1" in
    web)
      printf 'Web\n'
      ;;
    canvas/infra)
      printf 'Canvas\n'
      ;;
    harness/infra)
      printf 'Harness Infra\n'
      ;;
    platform)
      printf 'Platform Storage\n'
      ;;
    *)
      return 1
      ;;
  esac
}

json_escape() {
  local value=${1-}
  value=${value//\\/\\\\}
  value=${value//\"/\\\"}
  value=${value//$'\r'/\\r}
  value=${value//$'\n'/\\n}
  value=${value//$'\t'/\\t}
  printf '%s' "$value"
}

json_quote() {
  printf '"%s"' "$(json_escape "$1")"
}

json_delimited_array() {
  local value=${1-}
  local delimiter=${2-,}
  local item
  local first=true
  printf '['
  if [ -n "$value" ]; then
    while IFS= read -r item; do
      if [ "$first" = true ]; then
        first=false
      else
        printf ','
      fi
      json_quote "$item"
    done < <(printf '%s\n' "$value" | tr "$delimiter" '\n')
  fi
  printf ']'
}

json_targets() {
  local index
  local module
  local area
  printf '['
  for index in "${!TARGET_FQCNS[@]}"; do
    [ "$index" -gt 0 ] && printf ','
    module=$(target_module "${TARGET_FQCNS[$index]}")
    area=$(target_area "$module")
    printf '{"area":%s,"module":%s,"class":%s}' \
      "$(json_quote "$area")" \
      "$(json_quote "$module")" \
      "$(json_quote "${TARGET_FQCNS[$index]}")"
  done
  printf ']'
}

json_iterations() {
  local index
  printf '['
  for index in "${!ITERATION_STATUS[@]}"; do
    [ "$index" -gt 0 ] && printf ','
    printf '{'
    printf '"iteration":%d,' "$((index + 1))"
    printf '"result":%s,' "$(json_quote "${ITERATION_STATUS[$index]}")"
    printf '"durationSeconds":%d,' "${ITERATION_DURATION_SECONDS[$index]:-0}"
    printf '"mavenExitCode":%d,' "${ITERATION_MAVEN_EXIT[$index]:-125}"
    if [ -n "${ITERATION_MAVEN_SIGNAL[$index]:-}" ]; then
      printf '"mavenSignal":%s,' "$(json_quote "${ITERATION_MAVEN_SIGNAL[$index]}")"
    else
      printf '"mavenSignal":null,'
    fi
    printf '"log":%s,' "$(json_quote "${ITERATION_LOG[$index]}")"
    printf '"matchedClasses":'
    json_delimited_array "${ITERATION_MATCHED[$index]:-}" ','
    printf ','
    printf '"missingClasses":'
    json_delimited_array "${ITERATION_MISSING[$index]:-}" ','
    printf ','
    printf '"invalidClasses":'
    json_delimited_array "${ITERATION_INVALID[$index]:-}" ','
    printf ','
    printf '"markers":'
    json_delimited_array "${ITERATION_MARKERS[$index]:-}" '|'
    printf ','
    if [ -n "${ITERATION_REASON[$index]:-}" ]; then
      printf '"reason":%s' "$(json_quote "${ITERATION_REASON[$index]}")"
    else
      printf '"reason":null'
    fi
    printf '}'
  done
  printf ']'
}

surefire_report_status() {
  local fqcn=$1
  local report_file=$2

  [ -f "$report_file" ] && [ ! -L "$report_file" ] || return 2
  awk -v target="$fqcn" '
    function attribute(tag, key, pattern, value) {
      pattern = "[[:space:]]" key "=\"[^\"]*\""
      if (match(tag, pattern)) {
        value = substr(tag, RSTART, RLENGTH)
        sub("^[[:space:]]" key "=\"", "", value)
        sub("\"$", "", value)
        return value
      }
      return ""
    }

    {
      if (!capturing) {
        if (match($0, /<testsuite[[:space:]>]/)) {
          tag = substr($0, RSTART)
          capturing = 1
        } else {
          next
        }
      } else {
        tag = tag $0
      }

      if (capturing && index(tag, ">") > 0) {
        tag = substr(tag, 1, index(tag, ">"))
        name = attribute(tag, "name")
        classname = attribute(tag, "classname")
        if (name == target || classname == target) {
          identity_found = 1
          tests = attribute(tag, "tests")
          failures = attribute(tag, "failures")
          errors = attribute(tag, "errors")
          skipped = attribute(tag, "skipped")
          if (tests ~ /^[0-9]+$/ && failures == "0" && errors == "0" \
              && skipped ~ /^[0-9]+$/ && tests + 0 > 0 \
              && skipped + 0 < tests + 0) {
            valid_found = 1
          }
        }
        tag = ""
        capturing = 0
      }
    }

    END {
      if (valid_found) {
        exit 0
      }
      if (identity_found) {
        exit 2
      }
      exit 1
    }
  ' "$report_file"
}

report_class_file() {
  local fqcn=$1
  local module
  local report_dir
  local exact
  local candidate
  local status
  local invalid=false

  module=$(target_module "$fqcn") || return 1
  report_dir="$REPO_ROOT/$module/target/surefire-reports"
  exact="$report_dir/TEST-$fqcn.xml"

  if [ -e "$exact" ] || [ -L "$exact" ]; then
    if surefire_report_status "$fqcn" "$exact"; then
      printf '%s\n' "$exact"
      return 0
    fi
    return 2
  fi
  if [ -L "$report_dir" ]; then
    return 2
  fi
  [ -d "$report_dir" ] || return 1
  while IFS= read -r -d '' candidate; do
    surefire_report_status "$fqcn" "$candidate"
    status=$?
    case "$status" in
      0)
        printf '%s\n' "$candidate"
        return 0
        ;;
      2)
        invalid=true
        ;;
    esac
  done < <(find "$report_dir" -maxdepth 1 -type f -name '*.xml' -print0 2>/dev/null)
  [ "$invalid" = true ] && return 2
  return 1
}

remove_path() {
  local path=$1
  if [ -L "$path" ] || [ -f "$path" ]; then
    rm -f -- "$path"
  elif [ -d "$path" ]; then
    find "$path" -depth -mindepth 1 -delete || return 1
    rmdir -- "$path"
  elif [ -e "$path" ]; then
    rm -f -- "$path"
  fi
}

clear_target_reports() {
  local module
  local report_dir
  for module in "${TARGET_MODULES[@]}"; do
    report_dir="$REPO_ROOT/$module/target/surefire-reports"
    if [ -e "$report_dir" ] || [ -L "$report_dir" ]; then
      remove_path "$report_dir" || return 1
    fi
  done
}

copy_target_reports() {
  local iteration_dir=$1
  local module
  local report_dir
  local destination
  for module in "${TARGET_MODULES[@]}"; do
    report_dir="$REPO_ROOT/$module/target/surefire-reports"
    destination="$iteration_dir/surefire-reports/$module"
    mkdir -p "$destination" || return 1
    if [ -d "$report_dir" ] && [ ! -L "$report_dir" ]; then
      cp -a "$report_dir/." "$destination/" || return 1
    fi
  done
  return 0
}

collect_iteration_classes() {
  local matched=()
  local missing=()
  local invalid=()
  local index
  local status
  for index in "${!TARGET_FQCNS[@]}"; do
    report_class_file "${TARGET_FQCNS[$index]}" >/dev/null
    status=$?
    if [ "$status" -eq 0 ]; then
      matched+=("${TARGET_FQCNS[$index]}")
    elif [ "$status" -eq 2 ]; then
      invalid+=("${TARGET_FQCNS[$index]}")
    else
      missing+=("${TARGET_FQCNS[$index]}")
    fi
  done
  if [ "${#matched[@]}" -gt 0 ]; then
    ITERATION_MATCHED[$CURRENT_ITERATION_INDEX]=$(IFS=,; printf '%s' "${matched[*]}")
  else
    ITERATION_MATCHED[$CURRENT_ITERATION_INDEX]=
  fi
  if [ "${#missing[@]}" -gt 0 ]; then
    ITERATION_MISSING[$CURRENT_ITERATION_INDEX]=$(IFS=,; printf '%s' "${missing[*]}")
  else
    ITERATION_MISSING[$CURRENT_ITERATION_INDEX]=
  fi
  if [ "${#invalid[@]}" -gt 0 ]; then
    ITERATION_INVALID[$CURRENT_ITERATION_INDEX]=$(IFS=,; printf '%s' "${invalid[*]}")
  else
    ITERATION_INVALID[$CURRENT_ITERATION_INDEX]=
  fi
}

collect_iteration_markers() {
  local log_file="$RUN_DIR/${ITERATION_LOG[$CURRENT_ITERATION_INDEX]}"
  local markers=()
  [ -f "$log_file" ] || : >"$log_file"
  if grep -Fq 'Surefire is going to kill' "$log_file"; then
    markers+=('Surefire is going to kill')
  fi
  if grep -Fq '[ERROR]' "$log_file"; then
    markers+=('Maven [ERROR]')
  fi
  if [ "${#markers[@]}" -gt 0 ]; then
    ITERATION_MARKERS[$CURRENT_ITERATION_INDEX]=$(IFS='|'; printf '%s' "${markers[*]}")
  else
    ITERATION_MARKERS[$CURRENT_ITERATION_INDEX]=
  fi
}

terminate_current_maven() {
  local pid=${CURRENT_MAVEN_PID:-}
  local pgid=${CURRENT_MAVEN_PGID:-}
  local attempt

  [ -n "$pid" ] || return 0
  if [ -n "$pgid" ]; then
    kill -TERM -- "-$pgid" 2>/dev/null || true
  else
    kill -TERM "$pid" 2>/dev/null || true
  fi
  for attempt in 1 2 3 4 5; do
    if ! kill -0 "$pid" 2>/dev/null; then
      break
    fi
    sleep 1
  done
  if kill -0 "$pid" 2>/dev/null; then
    if [ -n "$pgid" ]; then
      kill -KILL -- "-$pgid" 2>/dev/null || true
    else
      kill -KILL "$pid" 2>/dev/null || true
    fi
  fi
  wait "$pid" 2>/dev/null || true
  CURRENT_MAVEN_PID=
  CURRENT_MAVEN_PGID=
}

finish_iteration() {
  local index=$1
  local maven_exit=$2
  local maven_signal=${3-}
  local forced_status=${4-}
  local iteration_end
  local iteration_dir
  local reasons=()
  local marker
  local missing
  local invalid

  CURRENT_ITERATION_INDEX=$index
  iteration_end=$(now_epoch)
  ITERATION_FINISHED_EPOCH[$index]=$iteration_end
  ITERATION_DURATION_SECONDS[$index]=$((iteration_end - ITERATION_STARTED_EPOCH[$index]))
  ITERATION_MAVEN_EXIT[$index]=$maven_exit
  ITERATION_MAVEN_SIGNAL[$index]=$maven_signal

  iteration_dir="$RUN_DIR/iterations/$(printf '%03d' "$((index + 1))")"
  if ! copy_target_reports "$iteration_dir"; then
    reasons+=('copy-surefire-evidence')
  fi
  collect_iteration_classes
  collect_iteration_markers

  if [ "$maven_exit" -ne 0 ]; then
    reasons+=("maven-exit:$maven_exit")
  fi
  if [ -n "$maven_signal" ]; then
    reasons+=("maven-signal:$maven_signal")
  fi
  if [ -n "${ITERATION_MARKERS[$index]:-}" ]; then
    while IFS= read -r marker; do
      [ -n "$marker" ] && reasons+=("marker:$marker")
    done < <(printf '%s' "${ITERATION_MARKERS[$index]}" | tr '|' '\n')
  fi
  missing=${ITERATION_MISSING[$index]:-}
  if [ -n "$missing" ]; then
    reasons+=("missing-target:$missing")
  fi
  invalid=${ITERATION_INVALID[$index]:-}
  if [ -n "$invalid" ]; then
    reasons+=("invalid-target:$invalid")
  fi
  if [ -n "$forced_status" ]; then
    ITERATION_STATUS[$index]=$forced_status
  elif [ "${#reasons[@]}" -gt 0 ]; then
    ITERATION_STATUS[$index]=fail
  else
    ITERATION_STATUS[$index]=pass
  fi
  if [ "${#reasons[@]}" -gt 0 ]; then
    ITERATION_REASON[$index]=$(IFS=';'; printf '%s' "${reasons[*]}")
  else
    ITERATION_REASON[$index]=
  fi
}

run_maven_iteration() {
  local log_file=$1
  local maven_exit
  {
    printf 'Working directory: %s\n' "$REPO_ROOT"
    printf 'Command: JAVA_HOME=%s mvn' "$JAVA_HOME_21"
    printf ' %s' "${MAVEN_ARGS[@]}"
    printf '\n\n'
  } >"$log_file"

  cd "$REPO_ROOT" || {
    printf '[ERROR] cannot change to repository root: %s\n' "$REPO_ROOT" >>"$log_file"
    return 125
  }
  setsid env \
    "JAVA_HOME=$JAVA_HOME_21" \
    "PATH=$JAVA_HOME_21/bin:$PATH" \
    mvn "${MAVEN_ARGS[@]}" >>"$log_file" 2>&1 &
  CURRENT_MAVEN_PID=$!
  CURRENT_MAVEN_PGID=$CURRENT_MAVEN_PID
  wait "$CURRENT_MAVEN_PID"
  maven_exit=$?
  CURRENT_MAVEN_PID=
  CURRENT_MAVEN_PGID=
  return "$maven_exit"
}

read_maven_version() {
  local version_log="$RUN_DIR/maven-version.log"
  local maven_exit

  setsid env \
    "JAVA_HOME=$JAVA_HOME_21" \
    "PATH=$JAVA_HOME_21/bin:$PATH" \
    mvn --version >"$version_log" 2>&1 &
  CURRENT_MAVEN_PID=$!
  CURRENT_MAVEN_PGID=$CURRENT_MAVEN_PID
  wait "$CURRENT_MAVEN_PID"
  maven_exit=$?
  CURRENT_MAVEN_PID=
  CURRENT_MAVEN_PGID=
  if [ -f "$version_log" ]; then
    MAVEN_VERSION=$(head -n 1 "$version_log" | one_line)
  fi
  return "$maven_exit"
}

run_iteration() {
  local index=$1
  local iteration_dir="$RUN_DIR/iterations/$(printf '%03d' "$((index + 1))")"
  local maven_exit

  mkdir -p "$iteration_dir" || {
    ITERATION_LOG[$index]="iterations/$(printf '%03d' "$((index + 1))")/maven.log"
    ITERATION_STARTED_EPOCH[$index]=$(now_epoch)
    ITERATION_STATUS[$index]=fail
    ITERATION_MAVEN_EXIT[$index]=125
    ITERATION_MAVEN_SIGNAL[$index]=
    ITERATION_DURATION_SECONDS[$index]=0
    ITERATION_MATCHED[$index]=
    ITERATION_MISSING[$index]=
    ITERATION_INVALID[$index]=
    ITERATION_MARKERS[$index]=
    ITERATION_REASON[$index]=cannot-create-iteration-directory
    return 1
  }

  ITERATION_LOG[$index]="iterations/$(printf '%03d' "$((index + 1))")/maven.log"
  ITERATION_STARTED_EPOCH[$index]=$(now_epoch)
  CURRENT_ITERATION_INDEX=$index
  CURRENT_ITERATION_SIGNAL=
  if ! clear_target_reports; then
    printf '[ERROR] cannot clear target Surefire reports before iteration %d\n' "$((index + 1))" \
      >"$RUN_DIR/${ITERATION_LOG[$index]}"
    finish_iteration "$index" 125 '' fail
    return 1
  fi

  run_maven_iteration "$RUN_DIR/${ITERATION_LOG[$index]}"
  maven_exit=$?
  finish_iteration "$index" "$maven_exit"
  CURRENT_ITERATION_INDEX=
  [ "${ITERATION_STATUS[$index]}" = pass ]
}

write_summary_json() {
  local summary_file="$RUN_DIR/summary.json"
  local finished_epoch=${RUN_FINISHED_EPOCH:-$(now_epoch)}
  local duration=$((finished_epoch - RUN_STARTED_EPOCH))

  {
    printf '{\n'
    printf '  "schemaVersion": 1,\n'
    printf '  "runId": %s,\n' "$(json_quote "$RUN_ID")"
    printf '  "status": %s,\n' "$(json_quote "$OVERALL_STATUS")"
    printf '  "commit": %s,\n' "$(json_quote "$GIT_COMMIT")"
    printf '  "jdk": %s,\n' "$(json_quote "$JAVA_VERSION")"
    printf '  "maven": %s,\n' "$(json_quote "$MAVEN_VERSION")"
    printf '  "startedAt": %s,\n' "$(json_quote "$RUN_STARTED_AT")"
    printf '  "finishedAt": %s,\n' "$(json_quote "$RUN_FINISHED_AT")"
    printf '  "durationSeconds": %d,\n' "$duration"
    printf '  "requestedIterations": %d,\n' "$ITERATIONS"
    printf '  "completedIterations": %d,\n' "${#ITERATION_STATUS[@]}"
    printf '  "targetClassCount": %d,\n' "${#TARGET_FQCNS[@]}"
    printf '  "targetClasses": '
    json_targets
    printf ',\n'
    printf '  "mavenArgs": ['
    local arg_index
    for arg_index in "${!MAVEN_ARGS[@]}"; do
      [ "$arg_index" -gt 0 ] && printf ','
      json_quote "${MAVEN_ARGS[$arg_index]}"
    done
    printf '],\n'
    printf '  "iterations": '
    json_iterations
    printf ',\n'
    if [ -n "$FAILURE_REASON" ]; then
      printf '  "failureReason": %s\n' "$(json_quote "$FAILURE_REASON")"
    else
      printf '  "failureReason": null\n'
    fi
    printf '}\n'
  } >"$summary_file"
}

markdown_list() {
  local value=${1-}
  if [ -n "$value" ]; then
    printf '%s' "${value//,/, }"
  else
    printf 'none'
  fi
}

write_report_md() {
  local report_file="$RUN_DIR/report.md"
  local index
  local matched_count
  local target_count=${#TARGET_FQCNS[@]}
  local result_upper
  local module
  local area

  result_upper=$(printf '%s' "$OVERALL_STATUS" | tr '[:lower:]' '[:upper:]')
  {
    printf '# Reliability Regression Report `%s`\n\n' "$RUN_ID"
    printf -- '- **Result:** `%s`\n' "$result_upper"
    printf -- '- **Commit:** `%s`\n' "$GIT_COMMIT"
    printf -- '- **JDK:** `%s`\n' "$JAVA_VERSION"
    printf -- '- **Maven:** `%s`\n' "$MAVEN_VERSION"
    printf -- '- **Started:** `%s`\n' "$RUN_STARTED_AT"
    printf -- '- **Finished:** `%s`\n' "$RUN_FINISHED_AT"
    printf -- '- **Duration:** `%ss`\n' "$((RUN_FINISHED_EPOCH - RUN_STARTED_EPOCH))"
    printf -- '- **Iterations:** `%d requested, %d completed`\n' "$ITERATIONS" "${#ITERATION_STATUS[@]}"
    printf '\n'
    printf 'This is a deterministic repeated Maven/Surefire regression gate. It is not the paid Agent/Provider matrix and it does not start backend, frontend, daemon, or a Compose stack. Testcontainers started by the selected JUnit tests remain test-owned.\n\n'

    if [ -n "$FAILURE_REASON" ]; then
      printf '## Failure\n\n%s\n\n' "$FAILURE_REASON"
    fi

    printf '## Target classes (%d)\n\n' "$target_count"
    printf '| Area | Module | Class |\n'
    printf '| --- | --- | --- |\n'
    for index in "${!TARGET_FQCNS[@]}"; do
      module=$(target_module "${TARGET_FQCNS[$index]}")
      area=$(target_area "$module")
      printf '| %s | `%s` | `%s` |\n' \
        "$area" \
        "$module" \
        "${TARGET_FQCNS[$index]}"
    done
    printf '\n'

    printf '## Iterations\n\n'
    if [ "${#ITERATION_STATUS[@]}" -eq 0 ]; then
      printf 'No Maven iteration was started.\n'
    else
      for index in "${!ITERATION_STATUS[@]}"; do
        matched_count=0
        [ -n "${ITERATION_MATCHED[$index]:-}" ] && matched_count=$(printf '%s' "${ITERATION_MATCHED[$index]}" | awk -F',' '{print NF}')
        printf '### Iteration %d — `%s`\n\n' "$((index + 1))" \
          "$(printf '%s' "${ITERATION_STATUS[$index]}" | tr '[:lower:]' '[:upper:]')"
        printf -- '- **Duration:** %ss\n' "${ITERATION_DURATION_SECONDS[$index]}"
        printf -- '- **Maven exit:** `%s`\n' "${ITERATION_MAVEN_EXIT[$index]}"
        if [ -n "${ITERATION_MAVEN_SIGNAL[$index]:-}" ]; then
          printf -- '- **Maven signal:** `%s`\n' "${ITERATION_MAVEN_SIGNAL[$index]}"
        fi
        printf -- '- **Target classes hit:** `%d/%d`\n' "$matched_count" "$target_count"
        printf -- '- **Matched:** %s\n' "$(markdown_list "${ITERATION_MATCHED[$index]}")"
        printf -- '- **Missing:** %s\n' "$(markdown_list "${ITERATION_MISSING[$index]}")"
        printf -- '- **Invalid:** %s\n' "$(markdown_list "${ITERATION_INVALID[$index]}")"
        printf -- '- **Fail-closed markers:** %s\n' "$(markdown_list "${ITERATION_MARKERS[$index]//|/,}")"
        printf -- '- **Reason:** %s\n' "${ITERATION_REASON[$index]:-none}"
        printf -- '- **Log:** [`%s`](%s)\n' "${ITERATION_LOG[$index]}" "${ITERATION_LOG[$index]}"
        printf '\n'
      done
    fi
  } >"$report_file"
}

publish_latest() {
  local latest="$REPORT_ROOT/latest-regression"
  local latest_marker="$REPORT_ROOT/LATEST_REGRESSION_RUN.txt"
  local staging

  [ ! -L "$REPORT_ROOT" ] || return 1
  [ ! -L "$latest" ] || return 1
  [ ! -L "$latest_marker" ] || return 1
  staging=$(mktemp -d -- "$REPORT_ROOT/.latest-regression.XXXXXX") || return 1
  if ! cp -a "$RUN_DIR/." "$staging/"; then
    remove_path "$staging"
    return 1
  fi
  if ! remove_path "$latest"; then
    remove_path "$staging"
    return 1
  fi
  if ! mv -- "$staging" "$latest"; then
    remove_path "$staging"
    return 1
  fi
  printf '%s\n' "$RUN_ID" >"$latest_marker" || return 1
  return 0
}

finish_report() {
  [ "$REPORT_FINALIZED" = true ] && return 0
  REPORT_FINALIZED=true
  RUN_FINISHED_EPOCH=$(now_epoch)
  RUN_FINISHED_AT=$(now_iso)
  write_summary_json
  write_report_md
  if ! publish_latest; then
    printf 'WARNING: failed to update %s\n' "$REPORT_ROOT/latest-regression" >&2
  fi
  printf 'Reliability regression report: %s\n' "$RUN_DIR/report.md"
}

handle_signal() {
  local signal=$1
  local exit_code=130
  if [ "$signal" = TERM ]; then
    exit_code=143
  fi
  OVERALL_STATUS=interrupted
  FAILURE_REASON="received SIG$signal"
  CURRENT_ITERATION_SIGNAL="SIG$signal"
  terminate_current_maven
  if [ -n "${CURRENT_ITERATION_INDEX:-}" ] \
    && [ -z "${ITERATION_STATUS[$CURRENT_ITERATION_INDEX]+set}" ]; then
    finish_iteration "$CURRENT_ITERATION_INDEX" "$exit_code" "SIG$signal" interrupted
  fi
  finish_report
  exit "$exit_code"
}

cleanup_on_exit() {
  local exit_code=$?
  terminate_current_maven
  if [ "$REPORT_FINALIZED" != true ] && [ -n "${RUN_DIR:-}" ]; then
    OVERALL_STATUS=fail
    [ -n "$FAILURE_REASON" ] || FAILURE_REASON="unexpected runner exit: $exit_code"
    finish_report
  fi
  return "$exit_code"
}

has_symlink_component() {
  local path=$1
  while :; do
    path=${path%/}
    [ -n "$path" ] || path=/
    [ -L "$path" ] && return 0
    [ "$path" = "/" ] && return 1
    path=$(dirname -- "$path")
  done
}

normalize_report_root() {
  local value=$1
  local candidate
  local normalized
  local latest
  local latest_normalized
  local latest_marker

  [ -n "$value" ] || die '--report-root requires a value'
  command -v realpath >/dev/null 2>&1 || die 'missing command: realpath'
  if [[ "$value" = /* ]]; then
    candidate=$value
  else
    candidate="$REPO_ROOT/$value"
  fi
  if has_symlink_component "$candidate"; then
    die '--report-root must not contain an existing symlink'
  fi
  normalized=$(realpath -m -- "$candidate") \
    || die "cannot normalize report root: $value"
  [ "$normalized" != "/" ] \
    || die '--report-root must not be the filesystem root'
  [ "$normalized" != "$REPO_ROOT" ] \
    || die '--report-root must not be the repository root'
  if has_symlink_component "$normalized"; then
    die '--report-root must not contain an existing symlink'
  fi
  latest="$normalized/latest-regression"
  [ "$(dirname -- "$latest")" = "$normalized" ] \
    || die 'latest-regression must stay inside the report root'
  latest_normalized=$(realpath -m -- "$latest") \
    || die 'cannot normalize latest-regression'
  case "$latest_normalized" in
    "$normalized"/latest-regression) ;;
    *) die 'latest-regression must stay inside the report root' ;;
  esac
  [ ! -L "$latest" ] \
    || die 'latest-regression must not be an existing symlink'
  latest_marker="$normalized/LATEST_REGRESSION_RUN.txt"
  [ ! -L "$latest_marker" ] \
    || die 'LATEST_REGRESSION_RUN.txt must not be an existing symlink'
  REPORT_ROOT=$normalized
}

parse_args() {
  local value
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --iterations)
        [ "$#" -ge 2 ] || die '--iterations requires a value'
        value=$2
        [[ "$value" =~ ^[0-9]+$ ]] || die '--iterations must be between 1 and 100'
        [ "${#value}" -le 3 ] || die '--iterations must be between 1 and 100'
        ITERATIONS=$((10#$value))
        [ "$ITERATIONS" -ge 1 ] && [ "$ITERATIONS" -le 100 ] \
          || die '--iterations must be between 1 and 100'
        shift 2
        ;;
      --report-root)
        [ "$#" -ge 2 ] || die '--report-root requires a value'
        value=$2
        [ -n "$value" ] || die '--report-root requires a value'
        if [[ "$value" = /* ]]; then
          REPORT_ROOT=$value
        else
          REPORT_ROOT="$REPO_ROOT/$value"
        fi
        shift 2
        ;;
      --help|-h)
        usage
        exit 0
        ;;
      *)
        die "unknown argument: $1"
        ;;
    esac
  done
}

parse_args "$@"
normalize_report_root "$REPORT_ROOT"

MAVEN_TEST_FILTER=$(IFS=,; printf '%s' "${TARGET_FQCNS[*]}")
MAVEN_ARGS=(
  --batch-mode
  -pl
  web,canvas/infra,harness/infra,platform
  -am
  "-Dtest=$MAVEN_TEST_FILTER"
  -Dsurefire.failIfNoSpecifiedTests=false
  -Dstyle.color=never
  test
)

mkdir -p "$REPORT_ROOT" || die "cannot create report root: $REPORT_ROOT"
[ ! -L "$REPORT_ROOT" ] || die '--report-root must not be a symlink'
[ ! -L "$REPORT_ROOT/latest-regression" ] \
  || die 'latest-regression must not be an existing symlink'
[ ! -L "$REPORT_ROOT/LATEST_REGRESSION_RUN.txt" ] \
  || die 'LATEST_REGRESSION_RUN.txt must not be an existing symlink'
RUN_STARTED_AT=$(now_iso)
RUN_STARTED_EPOCH=$(now_epoch)
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-regression-$$"
RUN_DIR="$REPORT_ROOT/$RUN_ID"
if [ -e "$RUN_DIR" ] || [ -L "$RUN_DIR" ]; then
  die "run directory already exists: $RUN_DIR"
fi
mkdir "$RUN_DIR" || die "cannot create run directory: $RUN_DIR"
mkdir "$RUN_DIR/iterations" || die "cannot create iteration directory: $RUN_DIR/iterations"
GIT_COMMIT=$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null || printf 'unknown')

trap 'handle_signal INT' INT
trap 'handle_signal TERM' TERM
trap cleanup_on_exit EXIT

preflight_error=
if [ -z "${JAVA_HOME_21:-}" ] || [ ! -x "$JAVA_HOME_21/bin/java" ]; then
  preflight_error='JAVA_HOME_21 must point to an executable JDK 21'
else
  JAVA_VERSION=$("$JAVA_HOME_21/bin/java" -version 2>&1 | head -n 1 | one_line)
  case "$JAVA_VERSION" in
    *'"21.'*|*'"21"'*) ;;
    *) preflight_error="JAVA_HOME_21 is not JDK 21: $JAVA_VERSION" ;;
  esac
fi
if [ -z "$preflight_error" ] && ! command -v mvn >/dev/null 2>&1; then
  preflight_error='missing command: mvn'
fi
if [ -z "$preflight_error" ] && ! command -v setsid >/dev/null 2>&1; then
  preflight_error='missing command: setsid'
fi
if [ -n "$preflight_error" ]; then
  FAILURE_REASON=$preflight_error
  finish_report
  exit 1
fi

read_maven_version
maven_info_exit=$?
if [ "$maven_info_exit" -ne 0 ]; then
  FAILURE_REASON="mvn --version failed with exit code $maven_info_exit"
  finish_report
  exit 1
fi

for ((iteration = 0; iteration < ITERATIONS; iteration++)); do
  run_iteration "$iteration"
  if [ "$?" -ne 0 ]; then
    OVERALL_STATUS=fail
    FAILURE_REASON="iteration $((iteration + 1)) failed: ${ITERATION_REASON[$iteration]:-unknown failure}"
    break
  fi
done

if [ "$OVERALL_STATUS" != interrupted ] && [ -z "$FAILURE_REASON" ] \
  && [ "${#ITERATION_STATUS[@]}" -eq "$ITERATIONS" ]; then
  OVERALL_STATUS=pass
else
  [ -n "$FAILURE_REASON" ] || FAILURE_REASON="completed ${#ITERATION_STATUS[@]} of $ITERATIONS iterations"
fi

finish_report
if [ "$OVERALL_STATUS" = pass ]; then
  exit 0
fi
exit 1
