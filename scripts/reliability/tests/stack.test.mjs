import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import test from 'node:test'

const TEST_DIR = path.dirname(fileURLToPath(import.meta.url))
const REPO_ROOT = path.resolve(TEST_DIR, '../../..')
const STACK_SCRIPT = path.join(REPO_ROOT, 'scripts/reliability/stack.sh')

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
