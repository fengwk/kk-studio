/** Permanent guards for the dev app lifecycle: clean package, runtime work dir, credential scrubbing. */

import assert from 'node:assert/strict'
import { copyFileSync, mkdirSync, mkdtempSync, realpathSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import test from 'node:test'

import { REPOSITORY_ROOT, cleanEnvironment, functionBody, readText, runBash, shellQuote } from './support/shell.mjs'

const APP_SCRIPT = path.join(REPOSITORY_ROOT, 'scripts/dev/app.sh')
const ROOT_LIB = path.join(REPOSITORY_ROOT, 'scripts/dev/lib/repo-root.sh')
const LIB_DIR = path.join(REPOSITORY_ROOT, 'scripts/dev/lib')
const E2E_CREDENTIALS_LIB = path.join(LIB_DIR, 'e2e-credentials.sh')
const E2E_LIB = path.join(REPOSITORY_ROOT, 'scripts/dev/verify/e2e/lib.sh')
const MATRIX_RUNNER = path.join(REPOSITORY_ROOT, 'scripts/dev/verify/e2e/run-matrix.mjs')
// 最小的根 POM 骨架：入口只读取 kk-studio-parent 的 <version>。
const ROOT_POM = '<project>\n  <artifactId>kk-studio-parent</artifactId>\n  <version>9.9.9</version>\n</project>\n'
// 两份入口都 source 同一组 dev 库；夹具 checkout 必须与真实入口保持同一清单。
const LAUNCHER_LIBS = ['repo-root.sh', 'http-wait.sh', 'toolchain.sh', 'port-cleanup.sh', 'backend-jar.sh']

/** Launcher environment without any inherited root, preview input or JAR override. */
function launcherEnvironment() {
  return cleanEnvironment([
    'KK_STUDIO_REPO_ROOT',
    'BACKEND_JAR',
    'SHARED_PREVIEW_ENV_FILE',
    'SPRING_PROFILES_ACTIVE',
    'SPRING_FLYWAY_ENABLED',
    'KK_STUDIO_HARNESS_RUNTIME_WORKERS_ENABLED',
    'BACKEND_PORT',
    'FRONTEND_PORT',
    'DAEMON_ENV_ROOT',
  ])
}

/** A checkout whose root POM version is 9.9.9, plus every library the launchers source. */
function withLauncherCheckout(body) {
  const temporary = realpathSync(mkdtempSync(path.join(tmpdir(), 'kk-launcher-jar-')))
  try {
    const checkout = path.join(temporary, 'checkout')
    mkdirSync(path.join(checkout, 'scripts/dev/lib'), { recursive: true })
    mkdirSync(path.join(checkout, 'scripts/dev/verify/e2e'), { recursive: true })
    writeFileSync(path.join(checkout, '.git'), 'gitdir: /tmp/example\n')
    writeFileSync(path.join(checkout, 'pom.xml'), ROOT_POM)
    for (const name of [...LAUNCHER_LIBS, 'e2e-credentials.sh']) {
      copyFileSync(path.join(LIB_DIR, name), path.join(checkout, 'scripts/dev/lib', name))
    }
    copyFileSync(APP_SCRIPT, path.join(checkout, 'scripts/dev/app.sh'))
    copyFileSync(E2E_LIB, path.join(checkout, 'scripts/dev/verify/e2e/lib.sh'))
    return body(checkout, path.join(temporary, 'runtime'))
  } finally {
    rmSync(temporary, { recursive: true, force: true })
  }
}

test('test intent: launchers derive the distribution JAR name from the root POM version', () => {
  // 夹具 POM 的版本与真实工作树不同，写死的 JAR 名不可能靠“碰巧等于当前版本”通过。
  withLauncherCheckout((checkout, runtime) => {
    const expected = path.join(checkout, 'web/target/kk-studio-web-9.9.9.jar')
    const cases = [
      ['scripts/dev/app.sh', { DEV_WORK_DIR: runtime }],
      ['scripts/dev/verify/e2e/lib.sh', { E2E_WORK_DIR: runtime }],
    ]
    const paths = cases.map(([relative, overrides]) => {
      const result = runBash(`source ${shellQuote(path.join(checkout, relative))}\nprintf '%s\\n' "$BACKEND_JAR"`, {
        cwd: checkout,
        env: { ...launcherEnvironment(), ...overrides },
      })
      assert.equal(result.status, 0, `${relative}: ${result.stderr}`)
      return result.stdout.trim()
    })
    assert.deepEqual(paths, [expected, expected])
  })

  // 本工作树的根 POM（多行布局）也必须能被同一个解析器读出，否则两个入口只在夹具格式下成立。
  const version = /<artifactId>kk-studio-parent<\/artifactId>\s*<version>([^<]+)<\/version>/.exec(
    readText(path.join(REPOSITORY_ROOT, 'pom.xml')),
  )?.[1]
  assert.ok(version)
  const real = runBash('source scripts/dev/app.sh\nprintf \'%s\\n\' "$BACKEND_JAR"', { env: launcherEnvironment() })
  assert.equal(real.status, 0, real.stderr)
  assert.equal(real.stdout.trim(), path.join(REPOSITORY_ROOT, `web/target/kk-studio-web-${version}.jar`))
})

test('test intent: runnable examples carry the reactor version as a placeholder', () => {
  // 文档示例写死版本时，升级后命令指向不存在的产物；示例统一使用 <version> 占位符。
  for (const relative of ['docs/operations/deployment.md', 'docs/operations/development-and-testing.md']) {
    const names = readText(path.join(REPOSITORY_ROOT, relative)).match(/kk-studio-web-[^\s'"`]+\.jar/g)
    assert.ok(names, relative)
    assert.deepEqual(new Set(names), new Set(['kk-studio-web-<version>.jar']), relative)
  }
})

test('test intent: a dev backend start uses a clean package', () => {
  // Deleted classes must not survive a dev-requested backend rebuild.
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
    mkdirSync(checkout, { recursive: true })
    writeFileSync(path.join(checkout, '.git'), 'gitdir: /tmp/example\n')
    // 深度 4 与真实 scripts/dev/app.sh 的深度 2 不同：任何固定层级推导都会指向错误目录。
    const nested = path.join(checkout, 'a/b/c/d/app.sh')
    mkdirSync(path.dirname(nested), { recursive: true })
    writeFileSync(nested, source)
    mkdirSync(path.join(path.dirname(nested), 'lib'))
    for (const name of LAUNCHER_LIBS) {
      copyFileSync(path.join(LIB_DIR, name), path.join(path.dirname(nested), 'lib', name))
    }
    mkdirSync(path.join(checkout, 'scripts/dev/lib'), { recursive: true })
    copyFileSync(E2E_CREDENTIALS_LIB, path.join(checkout, 'scripts/dev/lib/e2e-credentials.sh'))
    // 入口在 source 期就从根 POM 版本推导 JAR 路径，夹具必须是完整 checkout。
    writeFileSync(path.join(checkout, 'pom.xml'), ROOT_POM)

    assert.equal(resolvedRoot(nested, cleanEnvironment()), checkout)

    const override = path.join(temporary, 'elsewhere')
    mkdirSync(path.join(override, 'scripts/dev/lib'), { recursive: true })
    copyFileSync(E2E_CREDENTIALS_LIB, path.join(override, 'scripts/dev/lib/e2e-credentials.sh'))
    writeFileSync(path.join(override, 'pom.xml'), ROOT_POM)
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
    mkdirSync(path.join(path.dirname(orphan), 'lib'))
    for (const name of LAUNCHER_LIBS) {
      copyFileSync(path.join(LIB_DIR, name), path.join(path.dirname(orphan), 'lib', name))
    }

    const result = runBash(`source ${shellQuote(orphan)}`, { cwd: '/', env: cleanEnvironment() })
    assert.notEqual(result.status, 0)
    assert.ok(result.stderr.includes('cannot locate the kk-studio repository root'))
  } finally {
    rmSync(temporary, { recursive: true, force: true })
  }
})

test('test intent: shared resolver honors relative overrides and returns failures to its caller', () => {
  // Overrides are relative to the invoking cwd, and sourcing the library alone must not exit the shell.
  const temporary = realpathSync(mkdtempSync(path.join(tmpdir(), 'kk-root-lib-')))
  try {
    mkdirSync(path.join(temporary, 'target'))
    const success = runBash(`source ${shellQuote(ROOT_LIB)}\nresolve_repo_root /\nprintf '%s\\n' "$REPO_ROOT"`, {
      cwd: temporary, env: { ...cleanEnvironment(), KK_STUDIO_REPO_ROOT: 'target' },
    })
    assert.equal(success.status, 0, success.stderr)
    assert.equal(success.stdout.trim(), path.join(temporary, 'target'))
    const failure = runBash(`source ${shellQuote(ROOT_LIB)}\nresolve_repo_root / || printf 'failed\\n'`, {
      cwd: temporary, env: cleanEnvironment(),
    })
    assert.equal(failure.status, 0)
    assert.equal(failure.stdout.trim(), 'failed')
    assert.match(failure.stderr, /cannot locate the kk-studio repository root/)
    const invalid = runBash(`source ${shellQuote(ROOT_LIB)}\nresolve_repo_root /`, {
      cwd: temporary, env: { ...cleanEnvironment(), KK_STUDIO_REPO_ROOT: 'missing' },
    })
    assert.notEqual(invalid.status, 0)
  } finally {
    rmSync(temporary, { recursive: true, force: true })
  }
})

// The dev app lifecycle must never leak host provider credentials into long-lived processes.

test('test intent: long-lived runtime processes scrub every TEST_ input', () => {
  // The dev lifecycle uses the shared synchronizer and scrubs long-lived processes.
  const startAll = functionBody(APP_SCRIPT, 'start_all')
  assert.ok(startAll.includes('sync_e2e_provider_credentials'))
  assert.ok(startAll.includes('"${test_env_unsets[@]}"'))

  // The scrubber lives in the shared credentials library the lifecycle sources.
  const scrubber = functionBody(E2E_CREDENTIALS_LIB, 'test_env_unset_args')
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
