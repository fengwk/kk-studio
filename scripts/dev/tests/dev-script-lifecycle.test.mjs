/** Generic scripts/dev/app.sh behavior that the shared preview still depends on. */

import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { chmodSync, copyFileSync, existsSync, mkdirSync, readFileSync, rmSync, unlinkSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import test from 'node:test'

import { functionBody, readText, runBash } from './support/shell.mjs'
import { DEV_SCRIPT, cleanPreviewEnvironment, sourceDevScript, withTempDir } from './support/shared-preview.mjs'

const output = (result) => result.stdout + result.stderr

function executable(file, content) {
  writeFileSync(file, content)
  chmodSync(file, 0o755)
}

test('test intent: artifact_current binds a built artifact to its revision', () => {
  // `DEV_SKIP_PACKAGE=true` is the only thing standing between a restart and a JAR from a previous
  // revision. Each case asserts the boolean decision of the real function.
  withTempDir((root) => {
    const artifact = path.join(root, 'kk-studio-web-1.0.0.jar')
    const stamp = path.join(root, '.kk-studio-revision')
    const revision = '9c73718d94be910308e6b075dd70ab7075b2ae46'
    const artifactCurrent = (expected) =>
      sourceDevScript(`artifact_current '${artifact}' '${stamp}' '${expected}'`, {
        DEV_WORK_DIR: path.join(root, 'runtime'),
      })

    assert.notEqual(artifactCurrent(revision).status, 0, 'missing artifact')
    // A source-snapshot workspace has no revision to compare and keeps its artifact.
    writeFileSync(artifact, 'jar\n')
    assert.equal(artifactCurrent('').status, 0, 'snapshot workspace')
    assert.notEqual(artifactCurrent(revision).status, 0, 'missing stamp')
    writeFileSync(stamp, 'deadbeef\n')
    assert.notEqual(artifactCurrent(revision).status, 0, 'mismatched stamp')
    for (const recorded of [`${revision}\n`, `\n${revision}\n`, revision]) {
      writeFileSync(stamp, recorded)
      const result = artifactCurrent(revision)
      assert.equal(result.status, 0, `stamp ${JSON.stringify(recorded)} must match release ${revision}: ${result.stderr}`)
    }
  })
})

test('test intent: the backend is rebuilt when the stamp is stale', () => {
  // The stamp written at the previous build decides whether Maven may be skipped; shell wiring must
  // call the same predicate, otherwise the helper could be correct while the lifecycle still serves
  // stale artifacts.
  const packageBody = functionBody(DEV_SCRIPT, 'package_backend')
  assert.ok(
    packageBody.includes('artifact_current "$BACKEND_JAR" "$BACKEND_JAR_REVISION_STAMP" "$(current_revision)"'),
  )
  assert.ok(packageBody.includes('mvn -pl web -am -DskipTests clean package'))
  assert.ok(packageBody.includes("printf '%s\\n' \"$(current_revision)\" > \"$BACKEND_JAR_REVISION_STAMP\""))
  // `mvn clean` removes the JAR and the stamp together, so the stamp can never claim that a deleted
  // artifact is current.
  assert.ok(readText(DEV_SCRIPT).includes('BACKEND_JAR_REVISION_STAMP="$REPO_ROOT/web/target/.kk-studio-revision"'))
  const revisionFn = functionBody(DEV_SCRIPT, 'current_revision')
  assert.ok(revisionFn.includes('git -C "$REPO_ROOT" rev-parse HEAD'))
  assert.ok(revisionFn.includes('|| true'))
})

test('test intent: frontend dependencies are reinstalled only when the lock changed', () => {
  // `npm install` is only correct for a missing tree; an upgraded `package-lock.json` needs `npm ci`,
  // otherwise a restarted preview keeps dependency versions the current lock file no longer allows.
  // Each case runs the real function against a recorder `npm`, so the assertions are about issued commands.
  withTempDir((root) => {
    const appHome = path.join(root, 'app')
    mkdirSync(path.join(appHome, 'scripts/dev'), { recursive: true })
    // 仓库根由 worktree 标记向上解析，因此夹具必须是一个可识别的 checkout。
    mkdirSync(path.join(appHome, '.git'))
    copyFileSync(DEV_SCRIPT, path.join(appHome, 'scripts/dev/app.sh'))
    const frontend = path.join(appHome, 'frontend')
    mkdirSync(frontend)
    const lock = path.join(frontend, 'package-lock.json')
    writeFileSync(lock, '{"lockfileVersion": 3}\n')
    const nodeModules = path.join(frontend, 'node_modules')
    const stamp = path.join(nodeModules, '.kk-studio-package-lock.sha')
    const binDir = path.join(root, 'bin')
    mkdirSync(binDir)
    const npmLog = path.join(root, 'npm.log')
    executable(
      path.join(binDir, 'npm'),
      '#!/usr/bin/env bash\n' +
        'printf \'%s\\n\' "$*" >> "$NPM_LOG"\n' +
        // A real install also (re)creates the tree the stamp lives in.
        'mkdir -p node_modules\n',
    )

    const runEnsure = (overrides = {}) => {
      const environment = cleanPreviewEnvironment()
      delete environment.DEV_SKIP_NPM_INSTALL
      return runBash('source scripts/dev/app.sh\nensure_frontend_deps', {
        cwd: appHome,
        env: {
          ...environment,
          PATH: `${binDir}${path.delimiter}${process.env.PATH}`,
          NPM_LOG: npmLog,
          DEV_WORK_DIR: path.join(root, 'runtime'),
          ...overrides,
        },
      })
    }
    const calls = () => {
      const recorded = existsSync(npmLog) ? readFileSync(npmLog, 'utf8').split('\n').filter(Boolean) : []
      rmSync(npmLog, { force: true })
      return recorded
    }
    const lockDigest = () =>
      spawnSync('git', ['hash-object', 'package-lock.json'], { cwd: frontend, encoding: 'utf8' }).stdout.trim()

    // A missing tree is a plain install, and the fresh tree is stamped.
    let result = runEnsure()
    assert.equal(result.status, 0, result.stderr)
    assert.deepEqual(calls(), ['install'])
    assert.ok(existsSync(nodeModules))
    const firstDigest = readFileSync(stamp, 'utf8').trim()
    assert.match(firstDigest, /^[0-9a-f]{40}$/)
    assert.equal(lockDigest(), firstDigest, 'the stamp must be the lock content hash that a later start recomputes')

    // An unchanged lock reuses the installed tree without invoking npm at all.
    result = runEnsure()
    assert.equal(result.status, 0, result.stderr)
    assert.deepEqual(calls(), [])
    assert.equal(readFileSync(stamp, 'utf8').trim(), firstDigest)

    // A changed lock must force `npm ci` and refresh the stamp.
    writeFileSync(lock, '{"lockfileVersion": 3, "changed": 1}\n')
    result = runEnsure()
    assert.equal(result.status, 0, result.stderr)
    assert.deepEqual(calls(), ['ci'])
    assert.notEqual(readFileSync(stamp, 'utf8').trim(), firstDigest)

    // The explicit skip switch stays a total opt-out: with a changed lock and even with no tree at
    // all, the lifecycle must not invoke npm.
    writeFileSync(lock, '{"lockfileVersion": 3, "changed": 2}\n')
    result = runEnsure({ DEV_SKIP_NPM_INSTALL: 'true' })
    assert.equal(result.status, 0, result.stderr)
    assert.deepEqual(calls(), [])
    rmSync(nodeModules, { recursive: true })
    result = runEnsure({ DEV_SKIP_NPM_INSTALL: 'true' })
    assert.equal(result.status, 0, result.stderr)
    assert.deepEqual(calls(), [])
    assert.ok(!existsSync(nodeModules), 'an explicit skip must not install anything, not even for a missing tree')
    // `npm` is what recreates the skipped tree, so the stamp it owned is gone too; the failed-install
    // case below starts from an explicit, known stamp value.
    mkdirSync(nodeModules)
    const previousDigest = '0'.repeat(40)
    writeFileSync(stamp, `${previousDigest}\n`)

    // A failed install must not leave a fresh-looking stamp, otherwise the next start would reuse a
    // tree that npm never finished rebuilding.
    const failingBin = path.join(root, 'failing-bin')
    mkdirSync(failingBin)
    executable(path.join(failingBin, 'npm'), '#!/usr/bin/env bash\nexit 1\n')
    writeFileSync(lock, '{"lockfileVersion": 3, "changed": 3}\n')
    result = runEnsure({ PATH: [failingBin, binDir, process.env.PATH].join(path.delimiter) })
    assert.notEqual(result.status, 0, 'a failed npm must fail the start')
    assert.equal(readFileSync(stamp, 'utf8').trim(), previousDigest, 'the stamp must only be refreshed after npm succeeded')

    // Without a lock file there is nothing to compare, so the tree is reused.
    unlinkSync(lock)
    rmSync(nodeModules, { recursive: true })
    mkdirSync(nodeModules)
    result = runEnsure()
    assert.equal(result.status, 0, result.stderr)
    assert.deepEqual(calls(), [])
    assert.ok(!existsSync(stamp), 'no lock file means no stamp to maintain')
    const digest = runBash('source scripts/dev/app.sh\nfrontend_package_lock_digest', {
      cwd: appHome,
      env: { ...cleanPreviewEnvironment(), DEV_WORK_DIR: path.join(root, 'runtime') },
    })
    assert.notEqual(digest.status, 0, 'a missing lock file must be reported by status, not by a fabricated digest')
  })
})

test('test intent: readiness fails fast for an exited child and start cleans up', () => {
  // A configuration or startup failure must not consume the whole readiness timeout or leave the
  // other managed process running.
  withTempDir((temporary) => {
    const pidFile = path.join(temporary, 'backend.pid')
    writeFileSync(pidFile, '999999999\n')
    const result = sourceDevScript(`wait_http http://127.0.0.1:1 backend '${pidFile}'`, {
      DEV_READY_TIMEOUT_SECONDS: '30',
    })
    assert.notEqual(result.status, 0)
    assert.ok(result.stderr.includes('backend exited before becoming ready'), output(result))
  })

  const start = functionBody(DEV_SCRIPT, 'start_all')
  assert.ok(start.includes('"$BACKEND_PID_FILE"; then'))
  assert.ok(start.includes('if ! wait_http "$FRONTEND_URL/threads" frontend "$FRONTEND_PID_FILE"; then'))
  assert.equal(
    (start.match(/print_logs all >&2\s+stop_all\s+return 1/g) ?? []).length,
    2,
    'both Backend and Frontend readiness failures must log and clean up',
  )
})
