import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { copyFileSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import test from 'node:test'

import { REPOSITORY_ROOT } from '../../../tests/support/shell.mjs'

// 在隔离的 shell fixture 中记录两个 runner 的实参，不启动服务或触碰真实报告。
function runEntry(args, matrixExit = 0, syncExit = 0) {
  const root = mkdtempSync(path.join(tmpdir(), 'e2e-selection-'))
  try {
    const entry = path.join(root, 'run.sh')
    copyFileSync(path.join(REPOSITORY_ROOT, 'scripts/dev/verify/e2e/run.sh'), entry)
    writeFileSync(path.join(root, 'lib.sh'), `
REPO_ROOT="$STUB_ROOT"
BACKEND_URL=http://backend
FRONTEND_URL=http://frontend
DAEMON_ENV_NAME=fixture
step() { :; }
die() { echo "ERROR: $*" >&2; exit 1; }
ensure_stack() { :; }
cleanup_owned_stack() { :; }
load_e2e_credential_names() { REAL_CREDENTIAL_VARS=(FIXTURE_CREDENTIAL); }
sync_e2e_provider_credentials() {
  printf '%s\\n' "$@" >> "$STUB_ROOT/sync-args"
  printf 'arbitrary sync result\\n'
  printf 'arbitrary sync error\\n' >&2
  return "$STUB_SYNC_EXIT"
}
e2e_studio_get() { printf '[]'; }
E2E_STUDIO_COOKIE=fixture
node() {
  local script="$1"
  shift
  case "$script" in
    */run-matrix.mjs)
      if [[ " $* " == *' --real '* && -v FIXTURE_CREDENTIAL ]]; then
        echo 'credential reached the matrix' >&2
        return 2
      fi
      printf '%s\\n' "$@" > "$STUB_ROOT/api-args"
      mkdir -p "$STUB_ROOT/reports/e2e/latest"
      printf 'API report\\n' > "$STUB_ROOT/reports/e2e/latest/report.md"
      return "$STUB_MATRIX_EXIT"
      ;;
    */lib/json-probe.mjs)
      printf '%s\\n' "$@" > "$STUB_ROOT/probe-args"
      printf 'ok\\n'
      ;;
    */ui-smoke.mjs)
      printf '%s\\n' "$@" > "$STUB_ROOT/ui-args"
      ;;
    *) return 1 ;;
  esac
}
`)
    const result = spawnSync('bash', [entry, ...args], {
      env: { PATH: process.env.PATH, STUB_ROOT: root, STUB_MATRIX_EXIT: String(matrixExit),
        STUB_SYNC_EXIT: String(syncExit), FIXTURE_CREDENTIAL: 'synthetic' },
      encoding: 'utf8',
    })
    const readArgs = (name) => {
      try {
        return readFileSync(path.join(root, name), 'utf8').trim().split('\n')
      } catch (error) {
        if (error.code === 'ENOENT') return null
        throw error
      }
    }
    return { ...result, api: readArgs('api-args'), ui: readArgs('ui-args'), sync: readArgs('sync-args'), probe: readArgs('probe-args') }
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
}

test('only forwards UI ids to UI and supports mixed, UI-only, and API-only selection', () => {
  // 显式选择必须收窄两个矩阵，不能运行未请求的 UI 用例。
  const mixed = runEntry(['--ui', '--only', 'canvas.function_fake_runtime', '--only', 'ui.canvas.function_fake_flow'])
  assert.equal(mixed.status, 0, mixed.stderr)
  assert.deepEqual(mixed.api.slice(-4), ['--only', 'canvas.function_fake_runtime', '--only', 'ui.canvas.function_fake_flow'])
  assert.deepEqual(mixed.ui.slice(-2), ['--only', 'ui.canvas.function_fake_flow'])
  assert.ok(!mixed.ui.includes('canvas.function_fake_runtime'))
  assert.ok(!mixed.api.includes('--ui-only'))

  const ui = runEntry(['--ui', '--only', 'ui.agents.page_loads'])
  assert.equal(ui.status, 0, ui.stderr)
  assert.deepEqual(ui.api.slice(-3), ['--only', 'ui.agents.page_loads', '--ui-only'])
  assert.deepEqual(ui.ui.slice(-2), ['--only', 'ui.agents.page_loads'])

  const api = runEntry(['--ui', '--only', 'canvas.function_fake_runtime'])
  assert.equal(api.status, 0, api.stderr)
  assert.equal(api.ui, null)

  const all = runEntry(['--ui'])
  assert.equal(all.status, 0, all.stderr)
  assert.ok(!all.ui.includes('--only'))
})

test('real model is forwarded to both runners, sync and readiness, while free mode never syncs', () => {
  // 本地替身记录生产入口的实际实参，不启动栈。
  const selection = ['--real-model', 'openai_responses']
  const run = runEntry([...selection, '--ui'])
  assert.equal(run.status, 0, run.stderr)
  assert.deepEqual(run.api.slice(-3), ['--real', ...selection])
  assert.deepEqual(run.ui.slice(-3), ['--real', ...selection])
  assert.deepEqual(run.sync, selection)
  assert.deepEqual(run.probe, ['providers-ready', selection[1]])
  const all = runEntry(['--real', '--ui'])
  assert.equal(all.status, 0, all.stderr)
  assert.deepEqual(all.probe, ['providers-ready'])
  assert.ok(all.api.includes('--real') && all.ui.includes('--real'))
  assert.ok(!all.api.includes('--real-model') && !all.ui.includes('--real-model'))
  const free = runEntry(['--ui'])
  assert.equal(free.sync, null)
  assert.equal(free.probe, null)
  assert.ok(!free.api.includes('--real') && !free.ui.includes('--real'))
})

test('results-only forwards both modes and suppresses the synchronizer without swallowing its exit', () => {
  const args = ['--results-only', '--real-model', 'openai_responses', '--ui']
  const run = runEntry(args)
  assert.equal(run.status, 0)
  assert.ok(run.api.includes('--results-only') && run.ui.includes('--results-only'))
  assert.deepEqual(run.sync, ['--real-model', 'openai_responses'])
  assert.doesNotMatch(run.stdout + run.stderr, /arbitrary sync/)
  const failed = runEntry(args, 0, 37)
  assert.equal(failed.status, 37)
  assert.equal(failed.api, null)
  assert.equal(failed.ui, null)
  assert.doesNotMatch(failed.stdout + failed.stderr, /arbitrary sync/)
  const ordinary = runEntry(args.slice(1), 0, 37)
  assert.equal(ordinary.status, 37)
  assert.match(ordinary.stdout, /arbitrary sync result/)
  assert.match(ordinary.stderr, /arbitrary sync error/)
  const free = runEntry(['--results-only'])
  assert.notEqual(free.status, 0)
  assert.equal(free.api, null)
  assert.equal(free.sync, null)
})

test('strict Studio launch overrides both Logback initialization paths without touching existing logs', () => {
  const root = mkdtempSync(path.join(tmpdir(), 'e2e-logback-'))
  try {
    const scripts = path.join(REPOSITORY_ROOT, 'scripts/dev/verify/e2e')
    const log = path.join(root, 'backend.log')
    const config = path.join(scripts, 'logback-results-only.xml')
    writeFileSync(log, 'previous diagnostics')
    const shell = `
source "$SCRIPTS/lib.sh"
lsof() { return 1; }
reset_e2e_database() { :; }
wait_http_process() { :; }
e2e_studio_login() { printf fixture; }
nohup() {
  for arg in "$@"; do
    case "$arg" in
      -Dlogback.configurationFile=*|--logging.config=*|--server.tomcat.accesslog.enabled=*)
        printf '%s\\n' "$arg" >> "$WORK_DIR/logging-args"
        ;;
    esac
  done
  printf 'backend stdout\\n'
  printf 'backend stderr\\n' >&2
}
start_backend /unused-fixture-jdk
wait "$OWNED_BACKEND_PID"
`
    const run = (resultsOnly) => spawnSync('bash', ['-c', shell], {
      env: { PATH: process.env.PATH, SCRIPTS: scripts, E2E_WORK_DIR: root, RESULTS_ONLY: resultsOnly,
        KK_STUDIO_DB_URL: 'jdbc:fixture', LOGGING_CONFIG: 'file:ignored.xml', LOGGING_FILE_PATH: root },
      encoding: 'utf8',
    })
    const strict = run('true')
    assert.equal(strict.status, 0, strict.stderr)
    assert.doesNotMatch(strict.stdout + strict.stderr, /backend std/)
    assert.equal(readFileSync(log, 'utf8'), 'previous diagnostics')
    assert.equal(readFileSync(path.join(root, 'logging-args'), 'utf8'),
      `-Dlogback.configurationFile=${config}\n--logging.config=file:${config}\n--server.tomcat.accesslog.enabled=false\n`)
    assert.match(readFileSync(config, 'utf8'), /<configuration>\s*<root level="OFF"\/>\s*<\/configuration>/)
    const ordinary = run('false')
    assert.equal(ordinary.status, 0, ordinary.stderr)
    assert.equal(readFileSync(log, 'utf8'), 'backend stdout\nbackend stderr\n')
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('UI selection without --ui fails before startup', () => {
  // 防止拼漏能力开关后空跑并误报成功。
  const result = runEntry(['--only', 'ui.agents.page_loads'])
  assert.notEqual(result.status, 0)
  assert.match(result.stderr, /UI case selection requires --ui/)
  assert.equal(result.api, null)
  assert.equal(result.ui, null)
})

test('a matrix selection error does not attach UI results to an old report', () => {
  // API 选择或启动失败时不能继续写入可能属于上一次运行的报告。
  const result = runEntry(['--ui', '--only', 'ui.agents.page_loads'], 2)
  assert.equal(result.status, 2)
  assert.equal(result.ui, null)
})

test('API runner permits an empty selection only for explicitly selected UI cases', () => {
  const runner = path.join(REPOSITORY_ROOT, 'scripts/dev/verify/e2e/run-matrix.mjs')
  for (const args of [
    ['--only', 'ui.nonexistent'],
    ['--ui-only'],
    ['--ui-only', '--only', 'not-an-api-case'],
  ]) {
    const result = spawnSync(process.execPath, [runner, ...args], { encoding: 'utf8' })
    assert.notEqual(result.status, 0, args.join(' '))
    assert.match(result.stderr, /No cases selected|--ui-only requires only UI case ids/)
  }
  // 混选时即使另一个 API ID 有效，也不能静默忽略拼错的 ID。
  const mixed = spawnSync(process.execPath, [
    runner, '--only', 'canvas.function_fake_runtime', '--only', 'not-an-api-case', '--only', 'ui.agents.page_loads',
  ], { encoding: 'utf8' })
  assert.notEqual(mixed.status, 0)
  assert.match(mixed.stderr, /unknown API case id: not-an-api-case/)
})
