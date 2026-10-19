/** Permanent guards for the distributed two-node E2E test base. */

import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { existsSync, readFileSync, readdirSync } from 'node:fs'
import path from 'node:path'
import test from 'node:test'

import { repositoryRoot } from '../../../lib/repo-root.mjs'
import { TopologyError, validate } from '../validate-distributed.mjs'

const REPOSITORY_ROOT = repositoryRoot()
const E2E_ROOT = path.join(REPOSITORY_ROOT, 'scripts/dev/verify/e2e')
const PACKAGE_TESTS = path.join(E2E_ROOT, 'tests')
const read = (...parts) => readFileSync(path.join(...parts), 'utf8')
const COMPOSE = read(REPOSITORY_ROOT, 'deploy/distributed/compose.yaml')
const RUN_SCRIPT = read(E2E_ROOT, 'distributed.sh')
const E2E_ENTRY = read(E2E_ROOT, 'run.sh')
const MATRIX_RUNNER = read(E2E_ROOT, 'run-matrix.mjs')

// --distributed must be an explicit orthogonal capability, off by default.

test('test intent: the e2e entry keeps the single-instance path by default', () => {
  assert.ok(E2E_ENTRY.includes('DISTRIBUTED=false'))
  // The matrix invocation only carries distributed args behind the flag.
  assert.ok(
    E2E_ENTRY.includes(
      'if [ "$DISTRIBUTED" = "true" ]; then\n' +
        '  MATRIX_ARGS+=(--distributed --base-url-b "http://127.0.0.1:$APP_B_PORT")\n' +
        'fi',
    ),
  )
  // Distributed mode must not boot the host single-instance stack.
  assert.ok(E2E_ENTRY.includes('if [ "$DISTRIBUTED" != "true" ]; then\n  ensure_stack'))
})

test('test intent: the matrix runner keeps single-node defaults without --distributed', () => {
  assert.ok(MATRIX_RUNNER.includes("baseUrlB: ''"))
  assert.ok(MATRIX_RUNNER.includes('distributed: false'))
  assert.ok(MATRIX_RUNNER.includes("if (c.requires.has('distributed') && !args.distributed) return false"))
})

test('test intent: partial distributed arguments fail closed', () => {
  for (const argv of ['--distributed', '--base-url-b http://127.0.0.1:18083']) {
    const result = spawnSync(process.execPath, [path.join(E2E_ROOT, 'run-matrix.mjs'), ...argv.split(' ')], {
      cwd: REPOSITORY_ROOT,
      encoding: 'utf8',
    })
    assert.notEqual(result.status, 0, argv)
    assert.ok(result.stderr.includes('requires'), argv)
  }
})

test('test intent: distributed stays a free mock topology and rejects paid or host-stack capabilities', () => {
  assert.ok(
    E2E_ENTRY.includes(
      'for flag in REAL WITH_TOOLS WITH_BRANCH WITH_CANVAS_STORAGE WITH_CANVAS_FUNCTION WITH_UI; do',
    ),
  )
  assert.ok(E2E_ENTRY.includes('cannot be combined with'))
})

// The distributed stack must never touch host TEST_MINIMAX_* credentials.

test('test intent: neither the compose file nor the runner references TEST_MINIMAX', () => {
  assert.ok(!COMPOSE.includes('TEST_MINIMAX'))
  assert.ok(!RUN_SCRIPT.includes('TEST_MINIMAX'))
})

test('test intent: container logs entering reports pass the redaction helper', () => {
  assert.ok(MATRIX_RUNNER.includes('maybeCopyDistributedContainerLogs(runDir)'))
  assert.ok(MATRIX_RUNNER.includes('redactSecrets(logs)'))
})

// The static validator plus compose must encode the A/B isolation.

test('test intent: the topology validator is E2E implementation code invoked by verify', () => {
  assert.ok(existsSync(path.join(E2E_ROOT, 'validate-distributed.mjs')))
  assert.ok(RUN_SCRIPT.includes('validate-distributed.mjs'))
})

test('test intent: DB fault injection never touches a daemon network', () => {
  assert.ok(RUN_SCRIPT.includes('docker network disconnect "$NODE_A_DB_NETWORK" "$APP_A_CONTAINER"'))
  assert.ok(RUN_SCRIPT.includes('docker network connect "$NODE_A_DB_NETWORK" "$APP_A_CONTAINER"'))
  assert.ok(RUN_SCRIPT.includes('NODE_A_DB_NETWORK=kk-studio-distributed_node-a-db'))
  assert.ok(RUN_SCRIPT.includes('APP_A_CONTAINER=kk-studio-distributed-app-a-1'))
  for (const line of RUN_SCRIPT.split('\n')) {
    if (line.includes('docker network disconnect') || line.includes('docker network connect')) {
      assert.ok(!line.includes('daemon'), line)
    }
  }
})

test('test intent: repeated fault helpers are state-guarded and idempotent', () => {
  assert.ok(RUN_SCRIPT.includes('already disconnected'))
  assert.ok(RUN_SCRIPT.includes('already connected'))
})

test('test intent: both apps share one PostgreSQL database and MinIO bucket', () => {
  assert.ok(COMPOSE.includes('KK_STUDIO_DB_URL: jdbc:postgresql://postgres:5432/kk_studio_distributed'))
  assert.ok(COMPOSE.includes('KK_STUDIO_STORAGE_S3_BUCKET: kk-studio-distributed'))
  assert.ok(!COMPOSE.includes('app-b-db'))
})

test('test intent: workspace init prepares distinct daemon volumes for A/B owner isolation', () => {
  assert.ok(COMPOSE.includes('daemon-workspace-a:/workspace-a'))
  assert.ok(COMPOSE.includes('daemon-workspace-b:/workspace-b'))
  assert.ok(COMPOSE.includes('chown -R 10001:10001'))
  assert.ok(!COMPOSE.includes('distributed-a-only'))
  assert.ok(!COMPOSE.includes('distributed-b-only'))
})

test('test intent: three L5 distributed cases are registered in the matrix', () => {
  const casesFile = path.join(E2E_ROOT, 'cases/distributed.mjs')
  assert.ok(existsSync(casesFile))
  const content = readFileSync(casesFile, 'utf8')
  for (const id of ['distributed.shared_state', 'distributed.lease_routing', 'distributed.db_loss_fail_closed']) {
    assert.ok(content.includes(id), id)
  }
  assert.ok(content.includes("level: 'L5'"))
  assert.ok(content.includes("requires: ['distributed']"))
  // The removed directory-browsing mailbox case must not come back.
  assert.ok(!content.includes('distributed.lease_mailbox_routing'))
  assert.ok(!content.includes('/directories'))
})

test('test intent: node identity defaults are fixed and the old CLI flags stay removed', () => {
  assert.ok(COMPOSE.includes('${DISTRIBUTED_DAEMON_A_REGISTRATION_TOKEN:-e2e-token-dist-a}'))
  assert.ok(COMPOSE.includes('${DISTRIBUTED_DAEMON_B_REGISTRATION_TOKEN:-e2e-token-dist-b}'))
  assert.ok(COMPOSE.includes('KK_STUDIO_DAEMON_STUDIO_URL: http://app-a:8080'))
  assert.ok(COMPOSE.includes('KK_STUDIO_DAEMON_STUDIO_URL: http://app-b:8080'))
  // Configuration flows only through the shared daemon.json.
  for (const removed of ['--gateway-uri', '--data-dir', '--environment-name', '--gateway-token', '--daemon-id']) {
    assert.ok(!COMPOSE.includes(removed), removed)
  }
  assert.ok(!COMPOSE.includes('DISTRIBUTED_DAEMON_TOKEN'))
})

test('test intent: the distributed node:test file lives where CI Node discovery looks for it', () => {
  // CI runs `node --test <capability>/tests/*.test.mjs`; the distributed contract must match.
  assert.ok(existsSync(path.join(PACKAGE_TESTS, 'distributed.test.mjs')))
  assert.deepEqual(
    readdirSync(PACKAGE_TESTS)
      .filter((name) => /^distributed.*\.test\.mjs$/.test(name))
      .sort(),
    ['distributed.test.mjs'],
  )
})

// validate() has no container dependency, so its negative paths run here without Docker.

function topology(overrides = {}) {
  const service = (networks, extra = {}) => ({
    networks: Object.fromEntries(networks.map((network) => [network, {}])),
    ...extra,
  })
  const shared = {
    environment: {
      KK_STUDIO_DB_URL: 'jdbc:postgresql://postgres:5432/kk_studio_distributed',
      KK_STUDIO_STORAGE_S3_BUCKET: 'kk-studio-distributed',
    },
  }
  const port = (published) => ({ ports: [{ published }] })
  return {
    networks: Object.fromEntries(
      ['node-a-db', 'node-b-db', 'daemon-a', 'daemon-b', 'app-ingress-a', 'app-ingress-b'].map((name) => [name, {}]),
    ),
    services: {
      postgres: service(['node-a-db', 'node-b-db'], port(15433)),
      minio: service(['node-a-db', 'node-b-db'], port(19001)),
      'http-mock': service(['node-a-db', 'node-b-db'], port(18090)),
      'minio-init': service(['node-a-db']),
      'workspace-init': service(['daemon-a']),
      'app-a': service(['node-a-db', 'daemon-a', 'app-ingress-a'], { ...port(18082), ...shared }),
      'app-b': service(['node-b-db', 'daemon-b', 'app-ingress-b'], { ...port(18083), ...shared }),
      'daemon-a': service(['daemon-a']),
      'daemon-b': service(['daemon-b']),
      ...overrides,
    },
  }
}

test('test intent: a compliant topology passes validation', () => {
  validate(topology())
})

test('test intent: an app joining the other node network is rejected', () => {
  const config = topology()
  config.services['app-b'].networks['node-a-db'] = {}
  assert.throws(() => validate(config), TopologyError)
})

test('test intent: an unexpected host port is rejected', () => {
  const config = topology()
  config.services['daemon-a'].ports = [{ published: 9999 }]
  assert.throws(() => validate(config), /daemon-a host ports/)
})
