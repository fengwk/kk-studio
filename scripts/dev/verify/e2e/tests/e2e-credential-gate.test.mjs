/** Real mode requires all protocol pairs before startup, and runtime processes never see TEST_* inputs. */

import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import path from 'node:path'
import test from 'node:test'

import { REPOSITORY_ROOT, functionBody, runBash } from '../../../tests/support/shell.mjs'

const E2E_ROOT = path.join(REPOSITORY_ROOT, 'scripts/dev/verify/e2e')
const E2E_LIB = path.join(E2E_ROOT, 'lib.sh')
const E2E_ENTRY = path.join(E2E_ROOT, 'run.sh')

const CREDENTIAL_NAMES = [
  'TEST_GOOGLE_BASE_URL',
  'TEST_GOOGLE_API_KEY',
  'TEST_OPENAI_RESPONSES_BASE_URL',
  'TEST_OPENAI_RESPONSES_API_KEY',
  'TEST_ANTHROPIC_BASE_URL',
  'TEST_ANTHROPIC_API_KEY',
  'TEST_OPENAI_BASE_URL',
  'TEST_OPENAI_API_KEY',
]

test('test intent: a missing or blank protocol input fails before startup without leaking values', () => {
  const cleanEnv = Object.fromEntries(Object.entries(process.env).filter(([name]) => !name.startsWith('TEST_')))
  // A gate regression must fail JDK resolution rather than launch the real stack.
  cleanEnv.JAVA_HOME_21 = E2E_ENTRY
  const inputs = Object.fromEntries(
    CREDENTIAL_NAMES.map((name) => [name, name.endsWith('BASE_URL') ? 'https://cpa.example' : 'fixture-cpa-key']),
  )
  for (const flag of ['--real', '--with-branch']) {
    for (const missing of CREDENTIAL_NAMES) {
      for (const blank of [false, true]) {
        const env = { ...cleanEnv, ...inputs }
        if (blank) {
          env[missing] = ''
        } else {
          delete env[missing]
        }
        const result = spawnSync('bash', [E2E_ENTRY, flag], {
          cwd: REPOSITORY_ROOT,
          env,
          encoding: 'utf8',
          timeout: 10_000,
        })
        const context = `${flag} missing=${missing} blank=${blank}`
        assert.notEqual(result.status, 0, context)
        assert.ok(result.stderr.includes(`missing: ${missing}\n`), context)
        assert.equal(result.stdout, '', context)
        assert.ok(!result.stderr.includes('fixture-cpa-key'), context)
        assert.ok(!result.stderr.includes('https://cpa.example'), context)
      }
    }
  }
})

test('test intent: every E2E start path and its scrubber exclude all current and future TEST_ names', () => {
  for (const name of ['start_backend', 'start_frontend', 'start_daemon']) {
    assert.ok(functionBody(E2E_LIB, name).includes('"${test_env_unsets[@]}"'), name)
  }

  const scrubber = functionBody(E2E_LIB, 'test_env_unset_args')
  assert.ok(scrubber.includes('TEST_*)'))
  assert.ok(scrubber.includes("printf '%s\\0' -u"))
  assert.ok(scrubber.includes('compgen -e'))
  assert.ok(!scrubber.includes('< <(env)'))
  assert.ok(!scrubber.includes('TEST_GOOGLE'))
})

test('test intent: the E2E scrubber emits every TEST_ name and leaves unrelated inputs alone', () => {
  const result = runBash(
    `source ${E2E_LIB}\nmapfile -d '' -t args < <(test_env_unset_args)\nprintf '%s\\n' "\${args[@]}"`,
    {
      env: {
        ...process.env,
        TEST_FUTURE_PROVIDER_API_KEY: 'future-secret',
        TEST_API_KEY: 'generic-secret',
        UNRELATED_API_KEY: 'keep',
      },
    },
  )
  assert.equal(result.status, 0, result.stdout + result.stderr)
  const args = result.stdout.split('\n').filter(Boolean)
  const pairs = new Set()
  for (let index = 0; index + 1 < args.length; index += 2) {
    pairs.add(`${args[index]} ${args[index + 1]}`)
  }
  assert.ok(pairs.has('-u TEST_FUTURE_PROVIDER_API_KEY'))
  assert.ok(pairs.has('-u TEST_API_KEY'))
  assert.ok(!pairs.has('-u UNRELATED_API_KEY'))
})
