/** Tests for the reliability matrix's MiniMax credential synchronization. */

import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import test from 'node:test'

import { repositoryRoot } from '../../../lib/repo-root.mjs'
import { ValidationError } from '../../../lib/provider-credentials.mjs'
import { makeMockHttp } from '../../../tests/support/mock-http.mjs'
import { cleanEnvironment, runBash, shellQuote } from '../../../tests/support/shell.mjs'
import {
  TEST_MINIMAX_API_KEY,
  TEST_MINIMAX_BASE_URL,
  syncMinimaxCredentials,
} from '../sync-minimax-credentials.mjs'

const REPOSITORY_ROOT = repositoryRoot()
const STACK_SCRIPT = path.join(REPOSITORY_ROOT, 'scripts/dev/verify/reliability/stack.sh')
const SYNC_TOOL = 'sync-minimax-credentials.mjs'
const SYNC_TOOL_PATH = path.join(REPOSITORY_ROOT, 'scripts/dev/verify/reliability', SYNC_TOOL)

test('test intent: unset TEST_MINIMAX_* skips without any HTTP request', async () => {
  const { request, calls } = makeMockHttp({ rows: [] })
  // An incomplete E2E credential must not be consumed by reliability.
  const lines = await syncMinimaxCredentials('http://backend', { TEST_GOOGLE_API_KEY: 'unrelated' }, request)

  assert.deepEqual(lines, ['minimax: skip (no credential pair)'])
  assert.deepEqual(calls, [])
})

test('test intent: a complete pair updates the Responses provider and appends /v1 to the base URL', async () => {
  const rows = [
    {
      name: 'minimax',
      description: 'MiniMax.',
      providerType: 'openai_responses',
      baseUrl: null,
      version: '7',
      modelCallTimeoutMillis: 1800000,
      modelCallIdleTimeoutMillis: 120000,
    },
  ]
  const { request, calls } = makeMockHttp({ rows })

  const lines = await syncMinimaxCredentials(
    'http://backend',
    { [TEST_MINIMAX_BASE_URL]: 'https://api.minimax.example///', [TEST_MINIMAX_API_KEY]: 'secret-minimax' },
    request,
  )

  assert.equal(calls.length, 2)
  assert.equal(calls[1][0], 'http://backend/api/providers/minimax')
  assert.equal(calls[1][1].baseUrl, 'https://api.minimax.example/v1')
  assert.equal(calls[1][1].providerType, 'openai_responses')
  assert.equal(calls[1][1].credential, 'secret-minimax')
  assert.deepEqual(lines, ['minimax: configured=True baseUrl_set=True'])
})

test('test intent: a half pair fails before HTTP without leaking the secret', async () => {
  const { request, calls } = makeMockHttp({ rows: [] })

  await assert.rejects(
    syncMinimaxCredentials('http://backend', { [TEST_MINIMAX_API_KEY]: 'secret-minimax' }, request),
    (error) => {
      assert.ok(error instanceof ValidationError)
      assert.ok(error.message.includes(TEST_MINIMAX_BASE_URL))
      assert.ok(error.message.includes(TEST_MINIMAX_API_KEY))
      assert.ok(!error.message.includes('secret-minimax'))
      return true
    },
  )
  assert.deepEqual(calls, [])
})

test('test intent: --list-env exposes exactly the reliability credential names', () => {
  const result = spawnSync(process.execPath, [SYNC_TOOL_PATH, '--list-env'], { encoding: 'utf8' })

  assert.equal(result.status, 0, result.stderr)
  assert.equal(result.stderr, '')
  assert.deepEqual(result.stdout.trim().split('\n'), [TEST_MINIMAX_BASE_URL, TEST_MINIMAX_API_KEY])
})

test('test intent: the stack resolves credential names through the shared list and never inlines them', () => {
  const stack = readFileSync(STACK_SCRIPT, 'utf8')

  assert.ok(stack.includes(SYNC_TOOL))
  assert.ok(stack.includes('--list-env'))
  assert.ok(stack.includes('--backend-url "$APP_URL"'))
  assert.ok(!stack.includes('TEST_MINIMAX_BASE_URL='))
  assert.ok(!stack.includes('TEST_MINIMAX_API_KEY='))
})

test('test intent: stack up exports unexported host credentials using the shared names', () => {
  // Stub the stack lifecycle and inspect the actual sync invocation without Docker or HTTP.
  const result = runBash(`
    source ${shellQuote(STACK_SCRIPT)} help >/dev/null
    stack_require_docker() { :; }
    require_cmd() { :; }
    assert_safe_config() { :; }
    compose() { :; }
    wait_app() { :; }
    wait_environment() { :; }
    node() {
      if [ "\${2-}" = --list-env ]; then
        command node "$@"
      else
        [ "\${2-}" = --backend-url ] && [ "\${3-}" = "$APP_URL" ] || return 1
        [ "$TEST_MINIMAX_BASE_URL" = fixture-url ] && [ "$TEST_MINIMAX_API_KEY" = fixture-key ] || return 2
        export -p | command grep -q ' TEST_MINIMAX_BASE_URL=' || return 3
        export -p | command grep -q ' TEST_MINIMAX_API_KEY=' || return 4
        printf 'sync-called\\n'
      fi
    }
    unset TEST_MINIMAX_BASE_URL TEST_MINIMAX_API_KEY
    TEST_MINIMAX_BASE_URL=fixture-url
    TEST_MINIMAX_API_KEY=fixture-key
    up_stack
  `, { env: cleanEnvironment() })

  assert.equal(result.status, 0, result.stderr)
  assert.match(result.stdout, /sync-called/)
  assert.ok(!result.stdout.includes('fixture-key'))
  assert.ok(!result.stderr.includes('fixture-key'))
})

test('test intent: the sync tool reuses the shared inventory and declares no credential list', () => {
  const source = readFileSync(SYNC_TOOL_PATH, 'utf8')

  assert.ok(source.includes("from '../../lib/provider-credentials.mjs'"))
  assert.ok(source.includes("from '../e2e/sync-provider-credentials.mjs'"))
  assert.ok(source.includes('RELIABILITY_PROVIDER_SPECS'))
  assert.ok(!source.includes('baseUrlEnv:'))
  assert.ok(!source.includes('apiKeyEnv:'))
})
