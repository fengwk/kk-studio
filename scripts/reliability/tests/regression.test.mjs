import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'
import test from 'node:test'

const TEST_DIR = path.dirname(fileURLToPath(import.meta.url))
const REPO_ROOT = path.resolve(TEST_DIR, '../../..')
const RUNNER = path.join(REPO_ROOT, 'scripts/reliability/regression.sh')

function runRunner(args) {
  return spawnSync('bash', [RUNNER, ...args], {
    cwd: REPO_ROOT,
    encoding: 'utf8',
  })
}

test('regression CLI help is executable without starting Maven', () => {
  // Help is a safe real CLI probe: it must describe the frozen controls and
  // return before checking JDK/Docker/test prerequisites.
  const result = runRunner(['--help'])
  assert.equal(result.status, 0)
  assert.match(result.stdout, /Usage: .*scripts\/reliability\/regression\.sh/)
  assert.match(result.stdout, /--iterations N/)
  assert.match(result.stdout, /--report-root DIR/)
  assert.match(result.stdout, /--help/)
})

test('regression CLI rejects an iteration count outside 1..100', () => {
  // Invalid input must fail closed before it can create a report or launch a
  // Maven child process.
  const result = runRunner(['--iterations', '101'])
  assert.notEqual(result.status, 0)
  assert.match(`${result.stdout}\n${result.stderr}`, /between 1 and 100/)
})

test('regression CLI rejects zero iterations', () => {
  // Zero would produce a misleading green gate with no executed evidence, so
  // the boundary is exercised through the real shell entrypoint.
  const result = runRunner(['--iterations', '0'])
  assert.notEqual(result.status, 0)
  assert.match(`${result.stdout}\n${result.stderr}`, /between 1 and 100/)
})

test('regression source keeps the fail-closed evidence guards', () => {
  // This static fixture protects the contract that a future shell refactor
  // cannot silently turn a skipped target or Surefire/Maven error into pass.
  const source = readFileSync(RUNNER, 'utf8')
  assert.match(source, /-Dsurefire\.failIfNoSpecifiedTests=false/)
  assert.match(source, /missingClasses/)
  assert.match(source, /Surefire is going to kill/)
  assert.match(source, /\[ERROR\]/)
  assert.match(source, /ITERATION_STATUS\[\$index\]=fail/)
  assert.match(source, /target\/surefire-reports/)
})
