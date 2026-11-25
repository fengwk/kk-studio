/** Permanent guards for E2E clean package, runtime work directory, and daemon contracts. */

import assert from 'node:assert/strict'
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

test('test intent: the daemon rebuild compiles the daemon and stages its extension', () => {
  const body = functionBody(E2E_LIB, 'package_daemon')
  assert.ok(body.includes('Go 1.27.4'))
  assert.ok(body.includes('CGO_ENABLED=0'))
  assert.ok(body.includes('"$GO_BIN" build -o "$DAEMON_BINARY" ./cmd/kk-studio-daemon'))
  assert.ok(body.includes('daemon/ide-extension'))
  assert.ok(!body.includes('mvn'))
})

test('test intent: the E2E runtime work directory survives maven clean', () => {
  // The E2E runtime directory must stay outside the Maven build output.
  assert.ok(readText(E2E_LIB).includes('WORK_DIR=${E2E_WORK_DIR:-"$REPO_ROOT/runtime/e2e"}'))
  assert.ok(readText(MATRIX_RUNNER).includes("path.join(REPO_ROOT, 'runtime', 'e2e')"))
})

test('test intent: E2E Maven is online by default and offline only by opt-in', () => {
  // E2E 唯一的 Maven 重建路径（backend）必须共享显式离线开关；Daemon 用 Go 工具链构建。
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

test('test intent: the daemon distribution has no JVM or classpath machinery', () => {
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

  assert.ok(!startDaemon.includes('kill_daemon'))
  assert.ok(!readText(E2E_LIB).includes('pgrep -f -- "$DAEMON_BINARY"'))
  assert.ok(startDaemon.includes('OWNED_DAEMON_PID=$!'))
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
  const syncCalls = script.match(/^\s*sync_e2e_provider_credentials(?:\s+.*)?$/gm) ?? []

  assert.equal(syncCalls.length, 1)
  assert.match(script, /if \[ "\$REAL" = "true" \]; then\n {2}sync_e2e_provider_credentials(?: "\$\{REAL_MODEL_ARGS\[@\]\}")?\nfi/)
  assert.ok(!ensureStack.includes('sync_e2e_provider_credentials'))
})

test('test intent: database endpoint overrides cannot redirect E2E outside its Compose stack', () => {
  const result = runBash(`source ${E2E_LIB}\nprintf '%s' "$DB_HOST:$DB_PORT"`, {
    env: {
      ...cleanEnvironment(),
      KK_STUDIO_DB_URL: 'jdbc:postgresql://user-database:55432/postgres',
      KK_STUDIO_DB_HOST: 'user-database',
      KK_STUDIO_DB_PORT: '55432',
    },
  })
  assert.equal(result.status, 0, output(result))
  assert.equal(result.stdout, '127.0.0.1:')
  assert.ok(!readText(E2E_LIB).includes('ensure_dedicated_s3_bucket'))
  assert.ok(!readText(E2E_LIB).includes('is_local_minio_endpoint'))
})

test('test intent: startup owns fixed bucket credentials and does not honor user S3 overrides', () => {
  const result = runBash(`source ${E2E_LIB}\nprintf '%s\\n' "$STORAGE_S3_BUCKET" "$STORAGE_S3_ENDPOINT" "$STORAGE_S3_ACCESS_KEY"`, {
    env: {
      ...cleanEnvironment(),
      KK_STUDIO_STORAGE_S3_ENDPOINT: 'https://user-s3.invalid',
      KK_STUDIO_STORAGE_S3_BUCKET: 'user-bucket',
      KK_STUDIO_STORAGE_S3_ACCESS_KEY: 'user-key',
    },
  })
  assert.equal(result.status, 0, output(result))
  assert.deepEqual(result.stdout.trim().split('\n'), ['kk-studio-e2e-matrix', '', 'kk-studio'])
})

test('test intent: legacy MinIO bucket cleanup helper is absent', () => {
  assert.ok(!readText(E2E_LIB).includes('ensure_s3_storage'))
  assert.ok(!readText(E2E_LIB).includes('ensure_dedicated_s3_bucket'))
})


test('test intent: the default E2E stack owns its dependencies and never reclaims user ports', () => {
  const lib = readText(E2E_LIB)
  const run = readText(E2E_ENTRY)
  const compose = readText(path.join(E2E_ROOT, 'stack.compose.yaml'))
  const stack = functionBody(E2E_LIB, 'start_owned_stack')
  const cleanup = functionBody(E2E_LIB, 'cleanup_owned_stack')
  const ensure = functionBody(E2E_LIB, 'ensure_stack')

  assert.ok(stack.includes('compose_stack'))
  assert.ok(functionBody(E2E_LIB, 'compose_stack').includes('docker compose -p "$STACK_PROJECT"'))
  assert.ok(stack.includes('compose_stack port postgres 5432'))
  assert.ok(stack.includes('compose_stack port minio 9000'))
  assert.ok(cleanup.includes('down --volumes --remove-orphans'))
  assert.ok(cleanup.includes('$OWNED_DAEMON_PID'))
  assert.ok(ensure.includes('start_frontend'))
  assert.ok(ensure.includes('Frontend dependencies are missing; run npm --prefix frontend ci'))
  assert.ok(readText(E2E_LIB).includes('--strictPort'))
  assert.ok(stack.includes('docker image inspect "$minio_client_image"'))
  assert.ok(stack.includes('compose_stack build minio-init'))
  assert.ok(run.includes('trap cleanup_owned_stack EXIT'))
  assert.ok(!lib.includes('kill_port "$BACKEND_PORT"'))
  assert.ok(!lib.includes('kill_port "$FRONTEND_PORT"'))
  assert.match(compose, /127\.0\.0\.1::5432/)
  assert.match(compose, /127\.0\.0\.1::9000/)
  assert.match(compose, /postgres-data:/)
  assert.match(compose, /minio-data:/)
  assert.match(compose, /pull_policy: never/)
  assert.ok(compose.includes('image: kk-studio-minio-client:RELEASE.2025-04-16T18-13-26Z'))
  assert.ok(!compose.includes('deploy/local/compose.yaml'))
})
