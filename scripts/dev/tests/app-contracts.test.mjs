/** Permanent guards for the dev app lifecycle: clean package, runtime work dir, credential scrubbing. */

import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, realpathSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import test from 'node:test'

import { REPOSITORY_ROOT, cleanEnvironment, functionBody, readText, runBash, shellQuote } from './support/shell.mjs'

const APP_SCRIPT = path.join(REPOSITORY_ROOT, 'scripts/dev/app.sh')
const MATRIX_RUNNER = path.join(REPOSITORY_ROOT, 'scripts/dev/verify/e2e/run-matrix.mjs')

// Deleted classes must not survive a dev-requested backend rebuild.

test('test intent: launchers and documentation follow the reactor version', () => {
  // A release bump must not leave launchers or runnable examples pointing at an old JAR.
  const version = /<version>([^<]+)<\/version>/.exec(readText(path.join(REPOSITORY_ROOT, 'pom.xml')))?.[1]
  assert.ok(version)
  const expected = `kk-studio-web-${version}.jar`
  for (const relative of [
    'scripts/dev/app.sh',
    'scripts/dev/verify/e2e/lib.sh',
    'docs/operations/deployment.md',
    'docs/operations/development-and-testing.md',
  ]) {
    const names = readText(path.join(REPOSITORY_ROOT, relative)).match(/kk-studio-web-[\w.+-]+\.jar/g)
    assert.ok(names, relative)
    assert.deepEqual(new Set(names), new Set([expected]), relative)
  }
})

test('test intent: a dev backend start uses a clean package', () => {
  assert.match(functionBody(APP_SCRIPT, 'package_backend'), /(?:\bmvn\b|\brun_maven\b).*clean\b\s+\bpackage\b/)
})

test('test intent: the runtime work directory survives maven clean', () => {
  // Build output may be cleaned; the managed runtime directory must not live under target.
  assert.ok(readText(APP_SCRIPT).includes('WORK_DIR=${DEV_WORK_DIR:-"$REPO_ROOT/runtime/dev"}'))
  assert.ok(readText(MATRIX_RUNNER).includes("path.join(REPO_ROOT, 'runtime', 'dev')"))
})

// The dev lifecycle must locate the checkout by discovery, never by a fixed directory depth.

function resolvedRoot(script, env) {
  const result = runBash(`source ${shellQuote(script)}\nprintf "%s\\n" "$REPO_ROOT"`, { cwd: '/', env })
  assert.equal(result.status, 0, result.stdout + result.stderr)
  return realpathSync(result.stdout.trim())
}

test('test intent: root discovery is depth independent and overridable', () => {
  // `../..` breaks as soon as the entry moves; discovery plus the override must win. The same real
  // script body is sourced from a nested depth, so the assertion is about the shipped implementation.
  const source = readText(APP_SCRIPT)
  assert.ok(!source.includes('$SCRIPT_HOME/../..'))
  assert.ok(!source.includes('"$SCRIPT_HOME/../../"'))

  const temporary = realpathSync(mkdtempSync(path.join(tmpdir(), 'kk-app-root-')))
  try {
    const checkout = path.join(temporary, 'checkout')
    mkdirSync(path.join(checkout, '.git'), { recursive: true })
    // 深度 4 与真实 scripts/dev/app.sh 的深度 2 不同：任何固定层级推导都会指向错误目录。
    const nested = path.join(checkout, 'a/b/c/d/app.sh')
    mkdirSync(path.dirname(nested), { recursive: true })
    writeFileSync(nested, source)

    assert.equal(resolvedRoot(nested, cleanEnvironment()), checkout)

    const override = path.join(temporary, 'elsewhere')
    mkdirSync(override)
    assert.equal(resolvedRoot(nested, { ...cleanEnvironment(), KK_STUDIO_REPO_ROOT: override }), override)
  } finally {
    rmSync(temporary, { recursive: true, force: true })
  }
})

test('test intent: an unresolvable root fails closed before any lifecycle command', () => {
  const temporary = realpathSync(mkdtempSync(path.join(tmpdir(), 'kk-app-orphan-')))
  try {
    const orphan = path.join(temporary, 'snapshot/scripts/dev/app.sh')
    mkdirSync(path.dirname(orphan), { recursive: true })
    writeFileSync(orphan, readText(APP_SCRIPT))

    const result = runBash(`source ${shellQuote(orphan)}`, { cwd: '/', env: cleanEnvironment() })
    assert.notEqual(result.status, 0)
    assert.ok(result.stderr.includes('cannot locate the kk-studio repository root'))
  } finally {
    rmSync(temporary, { recursive: true, force: true })
  }
})

// The dev app lifecycle must never leak host provider credentials into long-lived processes.

test('test intent: long-lived runtime processes scrub every TEST_ input', () => {
  // The dev lifecycle owns its own scrubber and its own four-pair sync call.
  const startAll = functionBody(APP_SCRIPT, 'start_all')
  const sync = functionBody(APP_SCRIPT, 'sync_e2e_provider_credentials')
  const names = [
    'TEST_GOOGLE_BASE_URL',
    'TEST_GOOGLE_API_KEY',
    'TEST_OPENAI_RESPONSES_BASE_URL',
    'TEST_OPENAI_RESPONSES_API_KEY',
    'TEST_ANTHROPIC_BASE_URL',
    'TEST_ANTHROPIC_API_KEY',
    'TEST_OPENAI_BASE_URL',
    'TEST_OPENAI_API_KEY',
  ]
  for (const name of names) {
    assert.ok(sync.includes(`${name}="\${${name}-}"`), name)
  }
  // MiniMax 凭据属于 reliability 矩阵，不得进入本机 dev 数据面。
  assert.ok(!sync.includes('TEST_MINIMAX_BASE_URL='))
  assert.ok(!sync.includes('TEST_MINIMAX_API_KEY='))
  assert.ok(startAll.includes('"${test_env_unsets[@]}"'))

  const scrubber = functionBody(APP_SCRIPT, 'test_env_unset_args')
  assert.ok(scrubber.includes('TEST_*)'))
  assert.ok(scrubber.includes("printf '%s\\0' -u"))
  assert.ok(scrubber.includes('compgen -e'))
  assert.ok(!scrubber.includes('< <(env)'))
  assert.ok(!scrubber.includes('TEST_GOOGLE'))
})

test('test intent: the scrubber emits every TEST_ name, including unknown ones, and nothing else', () => {
  const result = runBash(
    `source ${shellQuote(APP_SCRIPT)} status >/dev/null\n` +
      "mapfile -d '' -t args < <(test_env_unset_args)\n" +
      "printf '%s\\n' \"${args[@]}\"",
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
