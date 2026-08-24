import assert from 'node:assert/strict'
import {
  chmodSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  symlinkSync,
  writeFileSync,
} from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'
import { tmpdir } from 'node:os'
import test from 'node:test'

const TEST_DIR = path.dirname(fileURLToPath(import.meta.url))
const REPO_ROOT = path.resolve(TEST_DIR, '../../..')
const RUNNER = path.join(REPO_ROOT, 'scripts/reliability/regression.sh')
const RUNNER_SOURCE = readFileSync(RUNNER, 'utf8')
const TARGET_CLASSES = [...RUNNER_SOURCE.matchAll(
  /^\s+(fun\.fengwk\.kkstudio\.[\w.]+Test)\s*$/gm,
)].map((match) => match[1])
const TARGET_MODULES = ['web', 'canvas/infra', 'harness/infra', 'platform']

assert.equal(TARGET_CLASSES.length, 17)

function runRunner(args, extraEnv = {}) {
  return spawnSync('bash', [RUNNER, ...args], {
    cwd: REPO_ROOT,
    encoding: 'utf8',
    env: { ...process.env, ...extraEnv },
  })
}

function moduleOf(fqcn) {
  if (fqcn.startsWith('fun.fengwk.kkstudio.web.')) {
    return 'web'
  }
  if (fqcn.startsWith('fun.fengwk.kkstudio.canvas.')) {
    return 'canvas/infra'
  }
  if (fqcn.startsWith('fun.fengwk.kkstudio.harness.')) {
    return 'harness/infra'
  }
  if (fqcn.startsWith('fun.fengwk.kkstudio.platform.')) {
    return 'platform'
  }
  throw new Error(`unknown target module: ${fqcn}`)
}

function removeTargetReports() {
  for (const module of TARGET_MODULES) {
    rmSync(path.join(REPO_ROOT, module, 'target', 'surefire-reports'), {
      force: true,
      recursive: true,
    })
  }
}

function createFakeToolchain(root, mode, invalidClass) {
  const binDir = path.join(root, 'bin')
  const jdkDir = path.join(root, 'jdk21')
  const jdkBinDir = path.join(jdkDir, 'bin')
  mkdirSync(binDir, { recursive: true })
  mkdirSync(jdkBinDir, { recursive: true })

  const java = path.join(jdkBinDir, 'java')
  writeFileSync(
    java,
    '#!/usr/bin/env bash\nprintf \'java version "21.0.0" fixture\\n\' >&2\n',
  )
  chmodSync(java, 0o755)

  const moduleCases = TARGET_CLASSES.map((fqcn) => `    ${JSON.stringify(fqcn)})
      module=${JSON.stringify(moduleOf(fqcn))}
      ;;`).join('\n')

  const maven = path.join(binDir, 'mvn')
  writeFileSync(
    maven,
    `#!/usr/bin/env bash
set -eu
if [ "\${1:-}" = "--version" ]; then
  printf 'Apache Maven 3.9.9 (fixture)\\n'
  exit 0
fi
repo_root=$(pwd)
for class_name in ${TARGET_CLASSES.map((fqcn) => JSON.stringify(fqcn)).join(' ')}; do
  case "$class_name" in
${moduleCases}
  esac
  report_dir="$repo_root/$module/target/surefire-reports"
  mkdir -p "$report_dir"
  case "$class_name" in
    "$REGRESSION_FIXTURE_CLASS")
      case "$REGRESSION_FIXTURE_MODE" in
        tests-zero)
          tests=0
          skipped=0
          failures=0
          errors=0
          ;;
        all-skipped)
          tests=2
          skipped=2
          failures=0
          errors=0
          ;;
        failure)
          tests=1
          skipped=0
          failures=1
          errors=0
          ;;
        valid)
          tests=1
          skipped=0
          failures=0
          errors=0
          ;;
        *)
          printf 'unknown fixture mode: %s\\n' "$REGRESSION_FIXTURE_MODE" >&2
          exit 97
          ;;
      esac
      ;;
    *)
      tests=1
      skipped=0
      failures=0
      errors=0
      ;;
  esac
  cat >"$report_dir/TEST-$class_name.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="$class_name" tests="$tests" failures="$failures" errors="$errors" skipped="$skipped">
  <testcase classname="$class_name" name="fixture"/>
</testsuite>
EOF
done
`,
  )
  chmodSync(maven, 0o755)
  return {
    JAVA_HOME_21: jdkDir,
    PATH: `${binDir}:${process.env.PATH}`,
    REGRESSION_FIXTURE_MODE: mode,
    REGRESSION_FIXTURE_CLASS: invalidClass,
  }
}

function withFixture(mode, invalidClass, callback) {
  const root = mkdtempSync(path.join(tmpdir(), 'kk-studio-regression-'))
  try {
    const env = createFakeToolchain(root, mode, invalidClass)
    return callback(root, env)
  } finally {
    removeTargetReports()
    rmSync(root, { force: true, recursive: true })
  }
}

function readLatestSummary(reportRoot) {
  return JSON.parse(
    readFileSync(path.join(reportRoot, 'latest-regression', 'summary.json'), 'utf8'),
  )
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

test('regression CLI rejects unsafe report roots before creating reports', () => {
  // The runner must never turn a root or repository path into a destructive
  // latest-regression target, and an empty value must fail before Maven.
  const temporaryRoot = mkdtempSync(path.join(tmpdir(), 'kk-studio-report-root-'))
  const symlinkTarget = path.join(temporaryRoot, 'real-root')
  const symlinkRoot = path.join(temporaryRoot, 'root-link')
  mkdirSync(symlinkTarget)
  symlinkSync(symlinkTarget, symlinkRoot, 'dir')
  try {
    for (const value of ['', '/', REPO_ROOT, symlinkRoot]) {
      const result = runRunner(['--iterations', '1', '--report-root', value])
      assert.equal(result.status, 2, `expected rejection for ${JSON.stringify(value)}`)
      assert.match(`${result.stdout}\n${result.stderr}`, /report-root|latest-regression/)
    }
    assert.equal(existsSync(path.join(symlinkTarget, 'latest-regression')), false)
  } finally {
    rmSync(temporaryRoot, { force: true, recursive: true })
  }
})

test('regression CLI rejects an existing latest-regression symlink without touching its target', () => {
  // A pre-existing publication link could point outside the dedicated report
  // directory; fail closed and preserve both the link and its external data.
  const temporaryRoot = mkdtempSync(path.join(tmpdir(), 'kk-studio-report-root-'))
  const reportRoot = path.join(temporaryRoot, 'reports')
  const externalRoot = path.join(temporaryRoot, 'external')
  const sentinel = path.join(externalRoot, 'sentinel.txt')
  mkdirSync(reportRoot)
  mkdirSync(externalRoot)
  writeFileSync(sentinel, 'must survive')
  symlinkSync(externalRoot, path.join(reportRoot, 'latest-regression'), 'dir')
  try {
    const result = runRunner(['--iterations', '1', '--report-root', reportRoot])
    assert.equal(result.status, 2)
    assert.match(`${result.stdout}\n${result.stderr}`, /latest-regression/)
    assert.equal(readFileSync(sentinel, 'utf8'), 'must survive')
    assert.equal(
      readFileSync(path.join(reportRoot, 'latest-regression', 'sentinel.txt'), 'utf8'),
      'must survive',
    )
  } finally {
    rmSync(temporaryRoot, { force: true, recursive: true })
  }
})

test('regression CLI normalizes a dedicated report root before publishing latest', () => {
  // A lexical .. segment is harmless only after normalization; publication
  // must land below the normalized temp directory, not beside it.
  const targetClass = TARGET_CLASSES[0]
  withFixture('valid', targetClass, (root, env) => {
    const reportRoot = path.join(root, 'nested', '..', 'normalized')
    const result = runRunner(
      ['--iterations', '1', '--report-root', reportRoot],
      env,
    )
    assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`)
    assert.equal(
      existsSync(path.join(root, 'normalized', 'latest-regression', 'summary.json')),
      true,
    )
    assert.equal(existsSync(path.join(root, 'latest-regression')), false)
  })
})

test('regression fixture accepts all 17 targets only with real Surefire evidence', () => {
  // This all-valid fixture models the actual frozen class list and proves the
  // report gate still records every target as executed, not merely discovered.
  withFixture('valid', TARGET_CLASSES[0], (root, env) => {
    const reportRoot = path.join(root, 'reports')
    const result = runRunner(
      ['--iterations', '1', '--report-root', reportRoot],
      env,
    )
    assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`)
    const iteration = readLatestSummary(reportRoot).iterations[0]
    assert.equal(iteration.result, 'pass')
    assert.equal(iteration.matchedClasses.length, 17)
    assert.deepEqual(iteration.missingClasses, [])
    assert.deepEqual(iteration.invalidClasses, [])
  })
})

for (const [mode, label] of [
  ['tests-zero', 'tests=0'],
  ['all-skipped', 'all tests skipped'],
  ['failure', 'failure XML'],
]) {
  test(`regression fixture fails closed for ${label}`, () => {
    // Each malformed-success fixture must be classified invalid even though
    // the fake Maven process exits zero and the target class name is present.
    const invalidClass = TARGET_CLASSES[0]
    withFixture(mode, invalidClass, (root, env) => {
      const reportRoot = path.join(root, 'reports')
      const result = runRunner(
        ['--iterations', '1', '--report-root', reportRoot],
        env,
      )
      assert.notEqual(result.status, 0)
      const summary = readLatestSummary(reportRoot)
      const iteration = summary.iterations[0]
      assert.equal(summary.status, 'fail')
      assert.equal(iteration.result, 'fail')
      assert.equal(iteration.matchedClasses.length, 16)
      assert.deepEqual(iteration.missingClasses, [])
      assert.deepEqual(iteration.invalidClasses, [invalidClass])
      assert.match(iteration.reason, /invalid-target/)
    })
  })
}

test('regression source keeps the fail-closed evidence guards', () => {
  // This static fixture protects the contract that a future shell refactor
  // cannot silently turn a skipped target or Surefire/Maven error into pass.
  assert.match(RUNNER_SOURCE, /-Dsurefire\.failIfNoSpecifiedTests=false/)
  assert.match(RUNNER_SOURCE, /missingClasses/)
  assert.match(RUNNER_SOURCE, /invalidClasses/)
  assert.match(RUNNER_SOURCE, /tests \+ 0 > 0/)
  assert.match(RUNNER_SOURCE, /skipped \+ 0 < tests \+ 0/)
  assert.match(RUNNER_SOURCE, /Surefire is going to kill/)
  assert.match(RUNNER_SOURCE, /\[ERROR\]/)
  assert.match(RUNNER_SOURCE, /ITERATION_STATUS\[\$index\]=fail/)
  assert.match(RUNNER_SOURCE, /target\/surefire-reports/)
  assert.doesNotMatch(RUNNER_SOURCE, /rm -rf/)
})
