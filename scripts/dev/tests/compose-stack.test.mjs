/** Contracts of the shared isolated-Compose lifecycle skeleton. */

import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import test from 'node:test'

import { REPOSITORY_ROOT, cleanEnvironment, runBash, shellQuote } from './support/shell.mjs'

const LIB = path.join(REPOSITORY_ROOT, 'scripts/dev/lib/compose-stack.sh')

/**
 * 运行骨架函数，只用假的 docker/curl/node 记录边界；COMPOSE 前缀固定，便于断言
 * profile 透传与 down 参数。
 */
function withSkeleton(body, extraEnvironment = {}) {
  const directory = mkdtempSync(path.join(tmpdir(), 'kk-compose-stack-'))
  const bin = path.join(directory, 'bin')
  const log = path.join(directory, 'calls.log')
  mkdirSync(bin, { recursive: true })
  writeFileSync(path.join(bin, 'docker'), `#!/usr/bin/env bash
printf '%s\\n' "$*" >>"\${FAKE_STACK_LOG}"
if [ "\${FAKE_STACK_DOWN_FAIL:-false}" = true ]; then
  case "$*" in
    *" down "*) exit 9 ;;
  esac
fi
`, { mode: 0o755 })
  writeFileSync(path.join(bin, 'curl'), '#!/usr/bin/env bash\nprintf \'{"data":[]}\\n\'\n', { mode: 0o755 })
  writeFileSync(path.join(bin, 'node'), `#!/usr/bin/env bash
# 真实 probe 会读完管道输入；这里同样排空 stdin，避免 curl 在消费方先退出时收到 SIGPIPE。
cat >/dev/null
case "\${2:-}" in
  environment-ready) [ "\${FAKE_ENV_READY:-false}" = true ]; exit $? ;;
  environment-status) printf 'OFFLINE\\n' ;;
esac
`, { mode: 0o755 })
  writeFileSync(log, '')

  const result = runBash(`
set -euo pipefail
die() { printf 'ERROR: %s\\n' "$*" >&2; exit 1; }
COMPOSE=(docker compose --project-name skeleton -f compose.yaml)
source ${shellQuote(LIB)}
${body}
`, {
    env: {
      ...cleanEnvironment(),
      PATH: `${bin}${path.delimiter}${process.env.PATH ?? ''}`,
      FAKE_STACK_LOG: log,
      ...extraEnvironment,
    },
  })
  const calls = readFileSync(log, 'utf8').split('\n').filter(Boolean)
  rmSync(directory, { recursive: true, force: true })
  return { result, calls }
}

test('test intent: config, up, and down keep one compose prefix and explicit flags', () => {
  const { result, calls } = withSkeleton(`
stack_config_quiet --profile app
stack_up_wait --profile app
stack_down false
stack_down true
`)
  assert.equal(result.status, 0, result.stdout + result.stderr)
  assert.deepEqual(calls, [
    'compose --project-name skeleton -f compose.yaml --profile app config --quiet',
    'compose --project-name skeleton -f compose.yaml --profile app up -d --wait --no-build',
    'compose --project-name skeleton -f compose.yaml down --remove-orphans',
    'compose --project-name skeleton -f compose.yaml down --volumes --remove-orphans',
  ])
})

test('test intent: cleanup diagnoses only failures and still swallows a down error', () => {
  const success = withSkeleton('cleanup_compose_stack 0 true --profile app')
  assert.equal(success.result.status, 0, success.result.stderr)
  assert.deepEqual(success.calls, [
    'compose --project-name skeleton -f compose.yaml --profile app down --volumes --remove-orphans',
  ])

  const failure = withSkeleton('cleanup_compose_stack 1 true --profile app')
  assert.equal(failure.result.status, 0, failure.result.stderr)
  assert.deepEqual(failure.calls, [
    'compose --project-name skeleton -f compose.yaml --profile app ps -a',
    'compose --project-name skeleton -f compose.yaml --profile app logs --no-color',
    'compose --project-name skeleton -f compose.yaml --profile app down --volumes --remove-orphans',
  ])

  const downFailure = withSkeleton(
    'cleanup_compose_stack 0 true --profile app\nprintf "after\\n"',
    { FAKE_STACK_DOWN_FAIL: 'true' },
  )
  assert.equal(downFailure.result.status, 0, downFailure.result.stderr)
  assert.equal(downFailure.result.stdout.trim(), 'after')
})

test('test intent: HTTP and environment waits fail with the caller-facing context', () => {
  const http = withSkeleton('stack_wait_http http://example.invalid/probe probe 0')
  assert.equal(http.result.status, 1)
  assert.match(http.result.stderr, /timed out waiting for probe/)

  const environment = withSkeleton(
    'stack_wait_environment http://backend named probe.mjs "Environment READY: named" 1 0',
  )
  assert.equal(environment.result.status, 1)
  assert.match(environment.result.stderr, /timed out waiting for Environment READY: named \(last status='OFFLINE'\)/)

  const ready = withSkeleton(
    'stack_wait_environment http://backend named probe.mjs "Environment READY: named" 1 0',
    { FAKE_ENV_READY: 'true' },
  )
  assert.equal(ready.result.status, 0, ready.result.stderr)
})
