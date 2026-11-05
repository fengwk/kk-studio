import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { chmodSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import test from 'node:test'

import { REPO_ROOT } from '../../../lib/repo-root.mjs'

const STACK_SCRIPT = path.join(REPO_ROOT, 'scripts/dev/verify/reliability/stack.sh')

function runStack(args, overrides = {}) {
  const env = { ...process.env }
  delete env.PI_ANCHOR
  delete env.PI_BASE_ANCHOR
  Object.assign(env, overrides)
  return spawnSync('bash', [STACK_SCRIPT, ...args], {
    cwd: REPO_ROOT,
    encoding: 'utf8',
    env,
  })
}

test('snapshot rejects an empty PI_ANCHOR before contacting Docker', () => {
  // Missing configuration must fail with an actionable anchor-specific message,
  // even on a host where Docker is unavailable.
  const result = runStack(['snapshot'], {
    PI_ANCHOR: '',
    PI_BASE_ANCHOR: '/provided/pi-base',
  })

  assert.notEqual(result.status, 0)
  assert.match(`${result.stdout}\n${result.stderr}`, /non-empty PI_ANCHOR/)
})

test('snapshot rejects an empty PI_BASE_ANCHOR before contacting Docker', () => {
  // Both snapshot inputs are explicit; supplying only one must not restore a
  // machine-specific default for the other anchor.
  const result = runStack(['snapshot'], {
    PI_ANCHOR: '/provided/pi',
    PI_BASE_ANCHOR: '',
  })

  assert.notEqual(result.status, 0)
  assert.match(`${result.stdout}\n${result.stderr}`, /non-empty PI_BASE_ANCHOR/)
})

test('stack help documents the explicit snapshot anchor contract', () => {
  // Non-snapshot commands remain usable without either private anchor path.
  const result = runStack(['help'])

  assert.equal(result.status, 0)
  assert.match(result.stdout, /PI_ANCHOR=\/path\/to\/pi/)
  assert.match(result.stdout, /PI_BASE_ANCHOR=\/path\/to\/pi-base/)
})

test('reliability commands target only their project and preserve volume cleanup semantics', () => {
  // Record real shell invocations without starting or removing any containers.
  const directory = mkdtempSync(path.join(tmpdir(), 'kk-reliability-compose-'))
  try {
    const docker = path.join(directory, 'docker')
    const log = path.join(directory, 'calls')
    writeFileSync(docker, '#!/bin/sh\nprintf "%s\\n%s\\n" "$COMPOSE_PROFILES" "$*" >> "$CALL_LOG"\n')
    chmodSync(docker, 0o755)
    const env = { PATH: `${directory}:${process.env.PATH}`, CALL_LOG: log, COMPOSE_PROFILES: 'app' }
    for (const args of [['logs', 'postgres', 'app'], ['down'], ['down', '--volumes']]) {
      const result = runStack(args, env)
      assert.equal(result.status, 0, result.stderr)
    }
    const calls = readFileSync(log, 'utf8').trim().split('\n')
    assert.equal(calls.length, 6)
    for (const index of [0, 2, 4]) {
      assert.equal(calls[index], 'reliability')
      assert.match(calls[index + 1], /compose --project-name kk-studio-reliability --file .*deploy\/test\/compose\.yaml/)
    }
    assert.match(calls[1], /logs .*reliability-postgres reliability-app$/)
    assert.match(calls[3], /down --remove-orphans$/)
    assert.match(calls[5], /down --volumes --remove-orphans$/)
  } finally {
    rmSync(directory, { recursive: true, force: true })
  }
})

test('up passes exported credentials to the synchronizer without printing them', () => {
  // Exercise a separate stack process with fake dependencies, not a running stack.
  const directory = mkdtempSync(path.join(tmpdir(), 'kk-reliability-sync-'))
  try {
    const log = path.join(directory, 'sync.log')
    writeFileSync(path.join(directory, 'docker'), '#!/bin/sh\nexit 0\n', { mode: 0o755 })
    writeFileSync(path.join(directory, 'curl'), '#!/bin/sh\nprintf "{}\\n"\n', { mode: 0o755 })
    writeFileSync(path.join(directory, 'node'), [
      '#!/bin/sh',
      'case "$1" in',
      '  */stack-probe.mjs) cat >/dev/null ;;',
      '  */sync-minimax-credentials.mjs)',
      '    [ "$#" = 3 ] && [ "$2" = --backend-url ] && [ "$3" = "$EXPECTED_URL" ] || exit 1',
      '    [ "$BACKEND_URL" = "$EXPECTED_URL" ] || exit 2',
      '    [ "$TEST_MINIMAX_BASE_URL" = fixture-url ] && [ "$TEST_MINIMAX_API_KEY" = fixture-key ] || exit 3',
      '    printf "sync-called\\n" >> "$SYNC_LOG" ;;',
      '  *) exit 4 ;;',
      'esac',
      '',
    ].join('\n'), { mode: 0o755 })
    const result = runStack(['up'], {
      PATH: `${directory}:${process.env.PATH}`,
      RELIABILITY_APP_PORT: '18123',
      EXPECTED_URL: 'http://127.0.0.1:18123',
      TEST_MINIMAX_BASE_URL: 'fixture-url',
      TEST_MINIMAX_API_KEY: 'fixture-key',
      SYNC_LOG: log,
    })

    assert.equal(result.status, 0, result.stderr)
    assert.equal(readFileSync(log, 'utf8'), 'sync-called\n')
    assert.ok(!`${result.stdout}${result.stderr}`.includes('fixture-key'))
  } finally {
    rmSync(directory, { recursive: true, force: true })
  }
})
