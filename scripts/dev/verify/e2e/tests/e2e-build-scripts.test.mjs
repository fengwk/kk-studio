/** Permanent guards for E2E clean package, runtime work directory, and Node daemon contracts. */

import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { chmodSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import test from 'node:test'

import { REPOSITORY_ROOT, functionBody, readText, runBash } from '../../../tests/support/shell.mjs'

const E2E_ROOT = path.join(REPOSITORY_ROOT, 'scripts/dev/verify/e2e')
const E2E_LIB = path.join(E2E_ROOT, 'lib.sh')
const E2E_DAEMON_BOOTSTRAP = path.join(E2E_ROOT, 'lib/daemon-bootstrap.sh')
const E2E_ENTRY = path.join(E2E_ROOT, 'run.sh')
const MATRIX_RUNNER = path.join(E2E_ROOT, 'run-matrix.mjs')

const output = (result) => result.stdout + result.stderr

function withTempDir(body) {
  const directory = mkdtempSync(path.join(tmpdir(), 'kk-e2e-'))
  try {
    return body(directory)
  } finally {
    rmSync(directory, { recursive: true, force: true })
  }
}

/** Run the E2E backend Maven path against a recorder fixture. */
function runE2eBackendMaven(offline) {
  return withTempDir((root) => {
    const binDir = path.join(root, 'bin')
    const workDir = path.join(root, 'work')
    mkdirSync(binDir)
    mkdirSync(workDir)
    const mavenLog = path.join(root, 'maven.log')
    const fakeMaven = path.join(binDir, 'mvn')
    writeFileSync(fakeMaven, '#!/usr/bin/env bash\nset -eu\nprintf \'%s\\n\' "$*" >> "$MAVEN_LOG"\n')
    chmodSync(fakeMaven, 0o755)

    const env = {
      ...process.env,
      PATH: `${binDir}${path.delimiter}${process.env.PATH}`,
      MAVEN_LOG: mavenLog,
      E2E_WORK_DIR: workDir,
    }
    delete env.E2E_MAVEN_OFFLINE
    if (offline !== 'unset') {
      env.E2E_MAVEN_OFFLINE = offline
    }
    const result = runBash(`source ${E2E_LIB}\npackage_backend /fake/jdk`, { env })
    let calls = []
    try {
      calls = readFileSync(mavenLog, 'utf8').split('\n').filter(Boolean)
    } catch {
      // Maven never ran.
    }
    return { result, calls }
  })
}

// Deleted classes must not survive a script-requested rebuild, and the daemon contract is now Node.

test('test intent: the E2E backend rebuild uses a clean package', () => {
  assert.match(functionBody(E2E_LIB, 'package_backend'), /(?:\bmvn\b|\brun_maven\b).*clean\b\s+\bpackage\b/)
})

test('test intent: the daemon rebuild installs and builds the Node product', () => {
  // Daemon 重建走 npm ci + build（无 JVM），并显式产出编译入口。
  const body = functionBody(E2E_LIB, 'package_daemon')
  assert.ok(body.includes('npm --prefix daemon ci'))
  assert.ok(body.includes('npm --prefix daemon run build'))
  assert.ok(!body.includes('mvn'))
  assert.ok(body.includes('$DAEMON_DIST/main.js'))
})

test('test intent: the E2E runtime work directory survives maven clean', () => {
  // The E2E runtime directory must stay outside the Maven build output.
  assert.ok(readText(E2E_LIB).includes('WORK_DIR=${E2E_WORK_DIR:-"$REPO_ROOT/runtime/e2e"}'))
  assert.ok(readText(MATRIX_RUNNER).includes("path.join(REPO_ROOT, 'runtime', 'e2e')"))
})

test('test intent: E2E Maven is online by default and offline only by opt-in', () => {
  // E2E 唯一的 Maven 重建路径（backend）必须共享显式离线开关；Daemon 不再走 Maven。
  let { result, calls } = runE2eBackendMaven('unset')
  assert.equal(result.status, 0, output(result))
  assert.equal(calls.length, 1)
  assert.ok(calls.every((call) => !call.split(' ').includes('-o')))
  assert.ok(calls[0].includes('-pl web'))

  ;({ result, calls } = runE2eBackendMaven('true'))
  assert.equal(result.status, 0, output(result))
  assert.equal(calls.length, 1)
  assert.ok(calls.every((call) => call.split(' ').includes('-o')))

  const daemonBody = functionBody(E2E_LIB, 'package_daemon')
  assert.ok(!daemonBody.includes('mvn'))
  assert.ok(!daemonBody.includes('E2E_MAVEN_OFFLINE'))
})

test('test intent: the daemon distribution is a Node entry without JVM or classpath machinery', () => {
  // Daemon 是 Node 产品：没有 JAR/lib/classpath，只由 Node 启动编译入口。
  const lib = readText(E2E_LIB)
  assert.ok(lib.includes('DAEMON_DIST=${DAEMON_DIST:-"$REPO_ROOT/daemon/dist"}'))
  for (const removed of ['DAEMON_JAR', 'DAEMON_TOOL_JAR', 'DAEMON_CP_FILE', 'daemon.classpath']) {
    assert.ok(!lib.includes(removed), removed)
  }
  for (const machinery of ['dependency:build-classpath', 'dependency:copy-dependencies']) {
    assert.ok(!lib.includes(machinery), machinery)
  }

  // 启动逻辑集中在 bootstrap helper：daemon 只通过 `node <dist>/main.js --config` 启动。
  const bootstrap = readText(E2E_DAEMON_BOOTSTRAP)
  assert.ok(bootstrap.includes('exec "$node_bin" "$entry" --config "$config_file"'))
  for (const removedJvm of ['-jar', 'DaemonMain', 'JAVA_HOME', '-cp ']) {
    assert.ok(!bootstrap.includes(removedJvm), removedJvm)
  }
  // 旧 CLI/裸配置来源必须保持删除状态。
  for (const removedFlag of ['--gateway-uri', '--data-dir', '--registration-token-file', '--note ']) {
    assert.ok(!lib.includes(removedFlag), removedFlag)
    assert.ok(!bootstrap.includes(removedFlag), removedFlag)
  }

  const startDaemon = functionBody(E2E_LIB, 'start_daemon')
  assert.ok(startDaemon.includes('daemon-bootstrap.sh'))
  assert.ok(startDaemon.includes('DAEMON_DIST="$DAEMON_DIST"'))
  assert.ok(!startDaemon.includes('JAVA_HOME'))

  // 进程命令行里只有编译入口，所以 `kill_daemon` 必须匹配配置的 `$DAEMON_DIST/main.js`。
  const killDaemon = functionBody(E2E_LIB, 'kill_daemon')
  assert.ok(killDaemon.includes('pgrep -f --'))
  assert.ok(killDaemon.includes('$DAEMON_DIST/main.js'))
  assert.ok(!killDaemon.includes('DaemonMain'))
  assert.ok(!killDaemon.includes('DAEMON_JAR'))
})

/** Stand-in for the daemon: any process whose argv carries the given path. */
const standIn = (marker) => spawn(process.execPath, ['-e', 'setTimeout(() => {}, 120000)', marker], { stdio: 'ignore' })

const exited = (child, timeoutMillis) =>
  new Promise((resolve) => {
    if (child.exitCode !== null || child.signalCode !== null) {
      resolve(true)
      return
    }
    const timer = setTimeout(() => resolve(false), timeoutMillis)
    child.once('exit', () => {
      clearTimeout(timer)
      resolve(true)
    })
  })

test('test intent: kill_daemon terminates the configured entry process only', async () => {
  const temporary = mkdtempSync(path.join(tmpdir(), 'kk-kill-daemon-'))
  const dist = path.join(temporary, 'dist')
  mkdirSync(dist)
  const entry = path.join(dist, 'main.js')
  writeFileSync(entry, '// daemon entry\n')
  const daemon = standIn(entry)
  const bystander = standIn(path.join(temporary, 'other-main.js'))
  try {
    // Give both children time to start so their argv is visible to pgrep.
    await new Promise((resolve) => setTimeout(resolve, 500))
    const result = runBash(`source ${E2E_LIB}\nkill_daemon\n`, { env: { ...process.env, DAEMON_DIST: dist } })
    assert.equal(result.status, 0, output(result))
    assert.ok(await exited(daemon, 30_000), 'the process holding the configured entry path must be terminated')
    assert.equal(bystander.exitCode, null, 'a process that does not carry the configured entry path must survive')
    assert.equal(bystander.signalCode, null)
  } finally {
    for (const child of [daemon, bystander]) {
      child.kill('SIGKILL')
    }
    rmSync(temporary, { recursive: true, force: true })
  }
})

test('test intent: ambiguous E2E_MAVEN_OFFLINE values fail before a Maven child can run', () => {
  const { result, calls } = runE2eBackendMaven('yes')
  assert.notEqual(result.status, 0)
  assert.match(result.stderr, /E2E_MAVEN_OFFLINE must be exactly true or false/)
  assert.deepEqual(calls, [])
})

test('test intent: the daemon environment root is exported to the Node matrix as an absolute path', () => {
  // The real Node case must receive the task-local work directory as an absolute path.
  withTempDir((temporary) => {
    const workDir = path.join(temporary, 'e2e')
    const env = { ...process.env, E2E_WORK_DIR: workDir }
    delete env.DAEMON_ENV_ROOT
    const probe = `source ${E2E_LIB}\nnode -e 'process.stdout.write(process.env.DAEMON_ENV_ROOT || "")'`

    let result = runBash(probe, { env })
    assert.equal(result.status, 0, output(result))
    assert.equal(result.stdout, path.join(workDir, 'environment'))

    const override = path.join(temporary, 'custom-environment')
    result = runBash(probe, { env: { ...env, DAEMON_ENV_ROOT: override } })
    assert.equal(result.status, 0, output(result))
    assert.equal(result.stdout, override)
  })

  const realCase = readText(path.join(E2E_ROOT, 'cases/real.mjs'))
  assert.ok(realCase.includes('const envRoot = process.env.DAEMON_ENV_ROOT'))
  assert.ok(!realCase.includes('/tmp/kk-studio-e2e-env'))
})

test('test intent: the free Canvas Function rebuild loads the e2e and canvas-test seeds', () => {
  // The documented free Function command must enable S3 on a fresh database.
  const script = readText(E2E_ENTRY)
  assert.ok(script.includes('if [ "$WITH_CANVAS_FUNCTION" = "true" ]; then'))
  assert.ok(script.includes('classpath:db/migration,classpath:db/seed/e2e,classpath:db/seed/canvas-test'))
  assert.ok(script.includes('SPRING_FLYWAY_LOCATIONS=${SPRING_FLYWAY_LOCATIONS:-'))
})

test('test intent: provider credentials are synchronized only for real mode', () => {
  // Free stack setup must never consume host real-provider credentials.
  const script = readText(E2E_ENTRY)
  const ensureStack = functionBody(E2E_LIB, 'ensure_stack')
  const syncCalls = script.match(/^\s*sync_e2e_provider_credentials\s*$/gm) ?? []

  assert.equal(syncCalls.length, 1)
  assert.match(script, /if \[ "\$REAL" = "true" \]; then\n {2}sync_e2e_provider_credentials\nfi/)
  assert.ok(!ensureStack.includes('sync_e2e_provider_credentials'))
})
