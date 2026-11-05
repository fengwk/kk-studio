import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { copyFileSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import test from 'node:test'

import { REPOSITORY_ROOT } from '../../../tests/support/shell.mjs'

// 在隔离的 shell fixture 中记录两个 runner 的实参，不启动服务或触碰真实报告。
function runEntry(args, matrixExit = 0) {
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
node() {
  local script="$1"
  shift
  case "$script" in
    */run-matrix.mjs)
      printf '%s\\n' "$@" > "$STUB_ROOT/api-args"
      mkdir -p "$STUB_ROOT/reports/e2e/latest"
      printf 'API report\\n' > "$STUB_ROOT/reports/e2e/latest/report.md"
      return "$STUB_MATRIX_EXIT"
      ;;
    */ui-smoke.mjs)
      printf '%s\\n' "$@" > "$STUB_ROOT/ui-args"
      ;;
    *) return 1 ;;
  esac
}
`)
    const result = spawnSync('bash', [entry, ...args], {
      env: { ...process.env, STUB_ROOT: root, STUB_MATRIX_EXIT: String(matrixExit) },
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
    return { ...result, api: readArgs('api-args'), ui: readArgs('ui-args') }
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
