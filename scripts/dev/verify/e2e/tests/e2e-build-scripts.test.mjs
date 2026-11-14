/** Permanent guards for E2E clean package, runtime work directory, and Go daemon contracts. */

import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { chmodSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import test from 'node:test'

import { REPOSITORY_ROOT, cleanEnvironment, functionBody, readText, runBash } from '../../../tests/support/shell.mjs'

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

// Deleted build products must not survive rebuilds, and the daemon contract is Go.

test('test intent: the E2E backend rebuild uses a clean package', () => {
  assert.match(functionBody(E2E_LIB, 'package_backend'), /(?:\bmvn\b|\brun_maven\b).*clean\b\s+\bpackage\b/)
})

test('test intent: the daemon rebuild compiles the Go product and stages its extension', () => {
  const body = functionBody(E2E_LIB, 'package_daemon')
  assert.ok(body.includes('Go 1.27.4'))
  assert.ok(body.includes('CGO_ENABLED=0'))
  assert.ok(body.includes('"$GO_BIN" build -o "$DAEMON_BINARY" ./cmd/kk-studio-daemon'))
  assert.ok(body.includes('daemon-go/ide-extension'))
  assert.ok(!body.includes('mvn'))
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

test('test intent: the Go daemon distribution has no JVM or classpath machinery', () => {
  const lib = readText(E2E_LIB)
  assert.ok(lib.includes('DAEMON_BINARY=${DAEMON_BINARY:-"$DAEMON_DIST/kk-studio-daemon"}'))
  for (const machinery of ['dependency:build-classpath', 'dependency:copy-dependencies']) {
    assert.ok(!lib.includes(machinery), machinery)
  }

  const bootstrap = readText(E2E_DAEMON_BOOTSTRAP)
  assert.ok(bootstrap.includes('exec "$daemon_binary" --config "$config_file"'))

  const startDaemon = functionBody(E2E_LIB, 'start_daemon')
  assert.ok(startDaemon.includes('daemon-bootstrap.sh'))
  assert.ok(startDaemon.includes('DAEMON_BINARY="$DAEMON_BINARY"'))
  assert.ok(!startDaemon.includes('JAVA_HOME'))

  const killDaemon = functionBody(E2E_LIB, 'kill_daemon')
  assert.ok(killDaemon.includes('pgrep -f --'))
  assert.ok(killDaemon.includes('$DAEMON_BINARY'))
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
  const daemonBinary = path.join(dist, 'kk-studio-daemon')
  writeFileSync(daemonBinary, '#!/bin/bash\n')
  const daemon = standIn(daemonBinary)
  const bystander = standIn(path.join(temporary, 'other-daemon'))
  try {
    // Give both children time to start so their argv is visible to pgrep.
    await new Promise((resolve) => setTimeout(resolve, 500))
    const result = runBash(`source ${E2E_LIB}\nkill_daemon\n`, { env: { ...process.env, DAEMON_BINARY: daemonBinary } })
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

test('test intent: an overridden database endpoint can never reset the shared local compose postgres', () => {
  // 隔离规则：只有未被覆盖的默认端点才能回退到 deploy/local；其它端点缺 psql 时必须先失败。
  const overrideProbe = `source ${E2E_LIB}
command() {
  if [ "$1" = -v ] && [ "$2" = psql ]; then
    return 1
  fi
  builtin command "$@"
}
reset_e2e_database`
  const override = runBash(overrideProbe, {
    env: {
      ...cleanEnvironment(),
      KK_STUDIO_DB_URL: 'jdbc:postgresql://127.0.0.1:55432/postgres',
    },
  })
  assert.notEqual(override.status, 0)
  assert.match(output(override), /host psql is required unless the endpoint is the local compose postgres/)
  assert.ok(!output(override).includes('DROP DATABASE'))

  // 默认端点保留原有 docker compose 回退路径。
  const dockerProbe = `source ${E2E_LIB}
command() {
  if [ "$1" = -v ] && [ "$2" = psql ]; then
    return 1
  fi
  builtin command "$@"
}
docker() {
  printf 'docker %s\\n' "$*"
  [ "$FAKE_COMPOSE_STATE" = running ] && printf 'postgres-container\\n'
  return 0
}
reset_e2e_database`
  const fallback = runBash(dockerProbe, {
    env: {
      ...cleanEnvironment(),
      KK_STUDIO_DB_URL: 'jdbc:postgresql://127.0.0.1:5432/postgres',
      FAKE_COMPOSE_STATE: 'running',
    },
  })
  assert.equal(fallback.status, 0, output(fallback))
  assert.match(output(fallback), /compose -f .*deploy\/local\/compose\.yaml exec -T .* postgres psql/)
  assert.match(output(fallback), /DROP DATABASE IF EXISTS kk_studio_e2e_matrix/)
})

test('test intent: S3 bucket isolation rules for local MinIO vs external S3 endpoint', () => {
  // 本地 MinIO (127.0.0.1:9000) 固定专用名 kk-studio-e2e-matrix，忽略外部环境变量覆盖
  const localProbe = `source ${E2E_LIB}
echo "$STORAGE_S3_BUCKET"
`
  const localResult = runBash(localProbe, {
    env: {
      ...process.env,
      KK_STUDIO_STORAGE_S3_ENDPOINT: 'http://127.0.0.1:9000',
      KK_STUDIO_STORAGE_S3_BUCKET: 'dev-user-bucket',
    },
  })
  assert.equal(localResult.status, 0, output(localResult))
  assert.equal(localResult.stdout.trim(), 'kk-studio-e2e-matrix')

  // 外部 S3 endpoint 允许覆盖 bucket，但跳过清理
  const externalProbe = `source ${E2E_LIB}
echo "$STORAGE_S3_BUCKET"
ensure_dedicated_s3_bucket
`
  const externalResult = runBash(externalProbe, {
    env: {
      ...process.env,
      KK_STUDIO_STORAGE_S3_ENDPOINT: 'https://s3.us-west-2.amazonaws.com',
      KK_STUDIO_STORAGE_S3_BUCKET: 'custom-external-bucket',
    },
  })
  assert.equal(externalResult.status, 0, output(externalResult))
  assert.match(externalResult.stdout, /custom-external-bucket/)
  assert.match(externalResult.stdout, /External S3 endpoint configured.*skipping bucket cleanup/)
})

test('test intent: host mc and Docker fallback preserve dedicated bucket behavior', () => {
  // Record both entry paths without Docker or a real bucket; an old image must use the checkout script.
  withTempDir((root) => {
    const mcDir = path.join(root, 'mc-bin')
    const dockerDir = path.join(root, 'docker-bin')
    mkdirSync(mcDir)
    mkdirSync(dockerDir)
    const mcLog = path.join(root, 'mc.log')
    const dockerLog = path.join(root, 'docker.log')
    writeFileSync(path.join(mcDir, 'mc'), [
      '#!/bin/sh',
      'printf "%s\\n" "$*" >> "$MC_LOG"',
      'if [ "${MC_VERIFY_EOF-}" = yes ] && IFS= read -r unexpected; then exit 25; fi',
      'if [ "${MC_FAIL_ON-}" = "$1" ]; then exit 1; fi',
      'if [ "$1" = ls ] && [ "$MC_BUCKET_STATE" = absent ]; then exit 1; fi',
      'exit 0',
      '',
    ].join('\n'), { mode: 0o755 })
    writeFileSync(path.join(dockerDir, 'docker'), [
      '#!/bin/sh',
      'printf "%s\\n" "$@" > "$DOCKER_LOG"',
      // Execute the requested entrypoint with streamed stdin and a fake mc.
      'while [ "$#" -gt 0 ] && [ "$1" != kk-studio-minio-client:RELEASE.2025-04-16T18-13-26Z ]; do shift; done',
      '[ "$#" -gt 0 ] || exit 2',
      'shift',
      'PATH="$FAKE_MC_DIR:$PATH" exec /bin/sh "$@"',
      '',
    ].join('\n'), { mode: 0o755 })

    const run = (viaDocker, bucketState, failOn = '') => {
      writeFileSync(mcLog, '')
      const result = runBash(`source ${E2E_LIB}
ensure_s3_storage() { :; }
command() {
  if [ "$1" = -v ] && [ "$2" = mc ]; then
    [ "$FAKE_ROUTE" = host ]
    return
  fi
  builtin command "$@"
}
ensure_dedicated_s3_bucket`, {
        env: {
          ...cleanEnvironment(),
          PATH: `${viaDocker ? dockerDir : mcDir}:/usr/bin:/bin`,
          FAKE_MC_DIR: mcDir,
          FAKE_ROUTE: viaDocker ? 'docker' : 'host',
          MC_LOG: mcLog,
          MC_BUCKET_STATE: bucketState,
          MC_FAIL_ON: failOn,
          MC_VERIFY_EOF: viaDocker ? 'yes' : 'no',
          DOCKER_LOG: dockerLog,
          KK_STUDIO_STORAGE_S3_ENDPOINT: 'http://127.0.0.1:9000',
          KK_STUDIO_STORAGE_S3_ACCESS_KEY: 'e2e-access',
          KK_STUDIO_STORAGE_S3_SECRET_KEY: 'e2e-secret',
          KK_STUDIO_STORAGE_S3_BUCKET: 'dev-user-bucket',
        },
      })
      return { result, calls: readFileSync(mcLog, 'utf8').split('\n').filter(Boolean) }
    }

    for (const viaDocker of [false, true]) {
      for (const bucketState of ['existing', 'absent']) {
        const { result, calls } = run(viaDocker, bucketState)
        assert.equal(result.status, 0, output(result))
        assert.ok(!output(result).includes('e2e-secret'))
        assert.deepEqual(calls, [
          'alias set e2e-local http://127.0.0.1:9000 e2e-access e2e-secret',
          'ls e2e-local/kk-studio-e2e-matrix',
          ...(bucketState === 'existing' ? ['rm --recursive --force e2e-local/kk-studio-e2e-matrix/'] : []),
          'mb --ignore-existing e2e-local/kk-studio-e2e-matrix',
          'anonymous set none e2e-local/kk-studio-e2e-matrix',
          'stat e2e-local/kk-studio-e2e-matrix',
        ])
      }
      for (const [bucketState, failOn] of [
        ['existing', 'alias'], ['existing', 'rm'], ['absent', 'mb'], ['absent', 'anonymous'], ['absent', 'stat'],
      ]) {
        const { result, calls } = run(viaDocker, bucketState, failOn)
        assert.notEqual(result.status, 0, `${viaDocker ? 'docker' : 'host'} ${failOn}`)
        assert.equal(calls.at(-1).split(' ')[0], failOn)
      }
    }
    assert.ok(!readFileSync(dockerLog, 'utf8').includes('e2e-secret'))
  })
})
