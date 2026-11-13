/** Permanent contract regression for the E2E daemon bootstrap and its disposable fixtures. */

import assert from 'node:assert/strict'
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import test from 'node:test'

import { CARD_ID, runProcess, startCardServer } from '../../tests/support/card-server.mjs'
import { daemonConfigFixture } from '../lib/fixtures.mjs'
import { REPOSITORY_ROOT, readText } from '../../../tests/support/shell.mjs'

const E2E_ROOT = path.join(REPOSITORY_ROOT, 'scripts/dev/verify/e2e')
const BOOTSTRAP = path.join(E2E_ROOT, 'lib/daemon-bootstrap.sh')
const SCHEMA_SEED = path.join(REPOSITORY_ROOT, 'schema/src/main/resources/db/seed/e2e/R__e2e_seed.sql')
const E2E_LIB_SH = path.join(E2E_ROOT, 'lib.sh')
const DISTRIBUTED_COMPOSE = path.join(REPOSITORY_ROOT, 'deploy/distributed/compose.yaml')
const UI_SMOKE = path.join(E2E_ROOT, 'ui-smoke.mjs')
const LIFECYCLE_TEST_JAVA = path.join(
  REPOSITORY_ROOT,
  'web/src/test/java/fun/fengwk/kkstudio/web/runtime/HarnessRuntimePostgresqlLifecycleIntegrationTest.java',
)

async function withTempDir(body) {
  const directory = mkdtempSync(path.join(tmpdir(), 'kk-bootstrap-'))
  try {
    return await body(directory)
  } finally {
    rmSync(directory, { recursive: true, force: true })
  }
}

/** A minimal executable Go-daemon fixture. */
function daemonFixture(temporary) {
  const dist = path.join(temporary, 'dist')
  mkdirSync(dist, { recursive: true })
  const daemonBinary = path.join(dist, 'kk-studio-daemon')
  writeFileSync(daemonBinary, '#!/bin/bash\nexit 0\n')
  chmodSync(daemonBinary, 0o755)
  return { daemonBinary }
}

/** Executable Go-daemon stand-in: records the real exec boundary without launching a service. */
function recorderDaemon(temporary) {
  const daemonBinary = path.join(temporary, 'kk-studio-daemon')
  const argvFile = path.join(temporary, 'argv.txt')
  const environFile = path.join(temporary, 'environ.txt')
  writeFileSync(
    daemonBinary,
    '#!/bin/bash\n' +
      'printf \'%s\\n\' "$@" >"$PROBE_ARGV"\n' +
      'env >"$PROBE_ENVIRON"\n',
  )
  chmodSync(daemonBinary, 0o755)
  return { daemonBinary, argvFile, environFile }
}

const mode = (file) => statSync(file).mode & 0o777

/** Remove ambient daemon inputs so a test cannot pass due to the invoking shell. */
function cleanEnv(overrides = {}) {
  const env = { ...process.env }
  for (const name of Object.keys(env)) {
    if (name.startsWith('DAEMON_')) {
      delete env[name]
    }
  }
  return { ...env, ...overrides }
}

function runBootstrap(env) {
  return runProcess('bash', [BOOTSTRAP], { env, timeoutMillis: 30_000 })
}

test('test intent: the E2E seed pre-seeds exactly five disposable Environments and stays re-runnable', () => {
  const content = readFileSync(SCHEMA_SEED, 'utf8')

  for (const expected of [
    '11111111-1111-1111-1111-111111111111',
    'tool-e2e',
    'e2e-token-host-tool',
    '22222222-2222-2222-2222-222222222222',
    'docker-reliability',
    'e2e-token-reliability',
    '33333333-3333-3333-3333-333333333333',
    'distributed-a',
    'e2e-token-dist-a',
    '44444444-4444-4444-4444-444444444444',
    'distributed-b',
    'e2e-token-dist-b',
    '55555555-5555-5555-5555-555555555555',
    'distributed-c',
    'e2e-token-dist-c',
  ]) {
    assert.ok(content.includes(expected), expected)
  }

  assert.ok(content.includes('on conflict (id) do update set'))
  assert.ok(!content.includes('delete from environment'))

  const match = /insert into agent\s*\([^)]*\)\s*values\s*\([^)]*'default-assistant'[^;]*;/i.exec(content)
  assert.ok(match)
  assert.ok(!match[0].includes('environmentId'))
  assert.ok(!match[0].includes('11111111-1111-1111-1111-111111111111'))
})

test('test intent: bootstrap materializes the card cache and launches the Go binary with --config only', async () => {
  const lib = readText(E2E_LIB_SH)
  const bootstrap = readText(BOOTSTRAP)

  assert.ok(lib.includes('daemon-bootstrap.sh'))
  assert.ok(lib.includes('DAEMON_ROOT'))
  assert.ok(lib.includes('DAEMON_DIST'))
  assert.ok(lib.includes('DAEMON_ENVIRONMENT_NAME'))
  assert.ok(lib.includes('Daemon 运行数据目录'))
  // Token is written to a private file; only the Node selector helper remains in the bootstrap.
  assert.ok(bootstrap.includes('daemon-config-cache.mjs'))
  assert.ok(bootstrap.includes('exec "$daemon_binary" --config "$config_file"'))
  assert.ok(lib.includes('Go 1.27.4'))
  assert.ok(lib.includes('$DAEMON_BINARY'))

  const note = 'E2E daemon environment — 日本語'
  const server = await startCardServer({ name: 'tool-e2e', version: '7', note })
  try {
    await withTempDir(async (temporary) => {
      const { daemonBinary, argvFile, environFile } = recorderDaemon(temporary)
      const root = path.join(temporary, 'daemon')
      const secret = 'e2e-token-host-tool'
      const result = await runBootstrap(cleanEnv({
        PROBE_ARGV: argvFile,
        PROBE_ENVIRON: environFile,
        DAEMON_ROOT: root,
        DAEMON_STUDIO_URL: server.url,
        DAEMON_ENVIRONMENT_NAME: 'tool-e2e',
        DAEMON_BINARY: daemonBinary,
        DAEMON_NODE: process.execPath,
        DAEMON_REGISTRATION_TOKEN: secret,
      }))
      assert.equal(result.status, 0, result.stderr)

      const argv = readFileSync(argvFile, 'utf8').split('\n').filter(Boolean)
      assert.deepEqual(argv, ['--config', path.join(root, 'daemon.json')])
      const configPath = argv[argv.indexOf('--config') + 1]
      assert.ok(path.isAbsolute(configPath))
      assert.equal(configPath, path.join(root, 'daemon.json'))
      assert.ok(!argv.includes(secret))
      assert.ok(!readFileSync(environFile, 'utf8').includes(secret))
      assert.ok(!(result.stdout + result.stderr).includes(secret))
      assert.ok(!readFileSync(configPath, 'utf8').includes(secret))
      assert.ok(!JSON.stringify(server.calls).includes(secret))

      const config = JSON.parse(readFileSync(configPath, 'utf8'))
      assert.deepEqual(Object.keys(config), ['environmentId', 'version', 'daemonConfig'])
      assert.equal(config.environmentId, CARD_ID)
      assert.equal(config.version, '8')
      assert.deepEqual(Object.keys(config.daemonConfig), ['studioUrl', 'bashExecutable', 'lsp', 'proxyUrl'])
      assert.equal(config.daemonConfig.studioUrl, server.url)
      assert.equal(config.daemonConfig.bashExecutable, '/bin/bash')
      assert.deepEqual(config.daemonConfig.lsp, daemonConfigFixture().lsp)
      assert.equal(config.daemonConfig.proxyUrl, 'http://127.0.0.1:7890')
      assert.ok(!Object.hasOwn(config.daemonConfig, 'note'))
      assert.ok(!readFileSync(configPath, 'utf8').includes(note))

      assert.equal(server.card.version, '8')
      assert.equal(server.card.note, note)
      assert.deepEqual(server.card.daemonConfig, config.daemonConfig)
      const put = server.calls.find((call) => call.method === 'PUT')
      assert.ok(put, 'bootstrap must correct the card studioUrl')
      assert.equal(put.path, `/api/platform/environments/${CARD_ID}`)
      assert.equal(put.body.expectedVersion, '7')
      assert.equal(put.body.note, note)
      assert.deepEqual(put.body.daemonConfig, config.daemonConfig)

      assert.equal(mode(root), 0o700)
      assert.equal(mode(configPath), 0o600)
      const tokenPath = path.join(root, 'daemon.token')
      assert.equal(mode(tokenPath), 0o600)
      assert.equal(readFileSync(tokenPath, 'utf8').trim(), secret)
    })
  } finally {
    await server.close()
  }
})

test('test intent: bootstrap selects a card by UUID and does not rewrite an already reachable studioUrl', async () => {
  const server = await startCardServer({ id: CARD_ID, name: 'tool-e2e', version: '3' })
  try {
    const reachable = daemonConfigFixture({ studioUrl: server.url })
    const updated = await fetch(`${server.url}/api/platform/environments/${CARD_ID}`, {
      method: 'PUT',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ expectedVersion: '3', note: 'reachable', daemonConfig: reachable }),
    })
    assert.equal(updated.status, 200)
    server.calls.length = 0

    await withTempDir(async (temporary) => {
      const { daemonBinary } = daemonFixture(temporary)
      const root = path.join(temporary, 'daemon')
      const result = await runBootstrap(cleanEnv({
        DAEMON_ROOT: root,
        DAEMON_STUDIO_URL: server.url,
        DAEMON_ENVIRONMENT_ID: CARD_ID,
        DAEMON_BINARY: daemonBinary,
        DAEMON_NODE: process.execPath,
        DAEMON_REGISTRATION_TOKEN: 'e2e-token-host-tool',
      }))
      assert.equal(result.status, 0, result.stderr)

      const config = JSON.parse(readFileSync(path.join(root, 'daemon.json'), 'utf8'))
      assert.equal(config.environmentId, CARD_ID)
      assert.equal(config.version, '4')
      assert.equal(config.daemonConfig.studioUrl, server.url)
      assert.equal(server.calls.length, 1)
      assert.equal(server.calls[0].method, 'GET')
      assert.equal(server.calls[0].path, `/api/platform/environments/${CARD_ID}`)
    })
  } finally {
    await server.close()
  }
})

test('test intent: bootstrap fails closed without a registration token', async () => {
  await withTempDir(async (temporary) => {
    const { daemonBinary } = daemonFixture(temporary)
    const result = await runBootstrap(cleanEnv({
      DAEMON_ROOT: path.join(temporary, 'daemon'),
      DAEMON_STUDIO_URL: 'http://127.0.0.1:18081',
      DAEMON_ENVIRONMENT_NAME: 'tool-e2e',
      DAEMON_BINARY: daemonBinary,
      DAEMON_NODE: process.execPath,
    }))
    assert.notEqual(result.status, 0)
    assert.ok(result.stderr.includes('DAEMON_REGISTRATION_TOKEN'))
  })
})

test('test intent: bootstrap requires exactly one card selector', async () => {
  await withTempDir(async (temporary) => {
    const { daemonBinary } = daemonFixture(temporary)
    const result = await runBootstrap(cleanEnv({
      DAEMON_ROOT: path.join(temporary, 'daemon'),
      DAEMON_STUDIO_URL: 'http://127.0.0.1:18081',
      DAEMON_BINARY: daemonBinary,
      DAEMON_NODE: process.execPath,
      DAEMON_REGISTRATION_TOKEN: 'e2e-token-host-tool',
    }))
    assert.notEqual(result.status, 0)
    assert.ok(result.stderr.includes('DAEMON_ENVIRONMENT_ID or DAEMON_ENVIRONMENT_NAME'))
  })
})

test('test intent: bootstrap rejects a relative DAEMON_ROOT', async () => {
  await withTempDir(async (temporary) => {
    const { daemonBinary } = daemonFixture(temporary)
    const result = await runBootstrap(cleanEnv({
      DAEMON_ROOT: 'relative/daemon',
      DAEMON_STUDIO_URL: 'http://127.0.0.1:18081',
      DAEMON_ENVIRONMENT_NAME: 'tool-e2e',
      DAEMON_BINARY: daemonBinary,
      DAEMON_NODE: process.execPath,
      DAEMON_REGISTRATION_TOKEN: 'e2e-token-host-tool',
    }))
    assert.notEqual(result.status, 0)
    assert.ok(result.stderr.includes('DAEMON_ROOT'))
    assert.ok(result.stderr.includes('absolute'))
  })
})

test('test intent: bootstrap does not start the daemon when the card cannot be resolved', async () => {
  const server = await startCardServer({ name: 'tool-e2e' })
  try {
    await withTempDir(async (temporary) => {
      const { daemonBinary, argvFile } = recorderDaemon(temporary)
      const secret = 'e2e-token-host-tool'
      const result = await runBootstrap(cleanEnv({
        PROBE_ARGV: argvFile,
        DAEMON_ROOT: path.join(temporary, 'daemon'),
        DAEMON_STUDIO_URL: server.url,
        DAEMON_ENVIRONMENT_NAME: 'missing-card',
        DAEMON_BINARY: daemonBinary,
        DAEMON_NODE: process.execPath,
        DAEMON_REGISTRATION_TOKEN: secret,
      }))
      assert.notEqual(result.status, 0)
      assert.ok(result.stderr.includes('exactly one card'))
      assert.ok(!(result.stdout + result.stderr).includes(secret))
      assert.ok(!existsSync(argvFile), 'daemon must not start without a resolved card')
    })
  } finally {
    await server.close()
  }
})

test('test intent: bootstrap creates its files owner-only without relying on chmod', async () => {
  const server = await startCardServer({ name: 'tool-e2e' })
  try {
    await withTempDir(async (temporary) => {
      const binDir = path.join(temporary, 'bin')
      mkdirSync(binDir)
      // chmod 桩为 no-op：只有写目录的 umask 与写文件的显式 mode 能收敛权限。
      writeFileSync(path.join(binDir, 'chmod'), '#!/bin/bash\nexit 0\n')
      chmodSync(path.join(binDir, 'chmod'), 0o755)
      const { daemonBinary } = daemonFixture(temporary)

      const root = path.join(temporary, 'daemon')
      const result = await runProcess('bash', ['-c', `umask 000; exec bash '${BOOTSTRAP}'`], {
        env: cleanEnv({
          PATH: `${binDir}${path.delimiter}${process.env.PATH ?? ''}`,
          DAEMON_ROOT: root,
          DAEMON_STUDIO_URL: server.url,
          DAEMON_ENVIRONMENT_NAME: 'tool-e2e',
          DAEMON_BINARY: daemonBinary,
          DAEMON_NODE: process.execPath,
          DAEMON_REGISTRATION_TOKEN: 'e2e-token-host-tool',
        }),
      })
      assert.equal(result.status, 0, result.stderr)
      assert.equal(mode(root), 0o700)
      assert.equal(mode(path.join(root, 'daemon.json')), 0o600)
      assert.equal(mode(path.join(root, 'daemon.token')), 0o600)
    })
  } finally {
    await server.close()
  }
})

test('test intent: distributed compose selects each seeded card by name and keeps A/B tokens distinct', () => {
  const compose = readText(DISTRIBUTED_COMPOSE)
  assert.ok(!compose.includes('KK_STUDIO_DAEMON_NOTE'))
  assert.ok(compose.includes('KK_STUDIO_DAEMON_ENVIRONMENT_NAME: ${DISTRIBUTED_ENV_A_NAME:-distributed-a}'))
  assert.ok(compose.includes('KK_STUDIO_DAEMON_ENVIRONMENT_NAME: ${DISTRIBUTED_ENV_B_NAME:-distributed-b}'))
  assert.ok(compose.includes('KK_STUDIO_DAEMON_ENVIRONMENT_NAME: ${DISTRIBUTED_ENV_C_NAME:-distributed-c}'))
  const tokenA = /DISTRIBUTED_DAEMON_A_REGISTRATION_TOKEN:-([^}]+)}/.exec(compose)
  const tokenB = /DISTRIBUTED_DAEMON_B_REGISTRATION_TOKEN:-([^}]+)}/.exec(compose)
  assert.ok(tokenA)
  assert.ok(tokenB)
  assert.notEqual(tokenA[1], tokenB[1])
})

test('test intent: UI smoke creates an agent without an implicit Environment', () => {
  // UI smoke must verify creating a chat with an agent does not auto-assign a default Environment.
  const ui = readText(UI_SMOKE)
  const present = [
    'ui.chat.create_agent_no_implicit_environment',
    '{ requiresTools: true }',
    'tempAgentName',
    "execution: { driver: 'builtin', settings: { modelName: REAL_UI_MODEL_ID",
    'agentCreateRes.status === 201',
    "!Object.hasOwn(agentCreateRes.json, 'environmentId')",
    "!Object.hasOwn(agentCreateRes.json, 'branchSettings')",
    'button[aria-label="工作区路径"]',
    'button.environment-binding-trigger',
    'Create Chat still rendered a workspace path selector',
    "environmentText === '未选择环境'",
    // 当前契约：Create Chat 渲染默认 Environment 选择器，并显式投影 required-nullable environmentName。
    "name: '默认环境', exact: true",
    'created.environmentName === null',
    'required-nullable environmentName:null',
    "apiDeleteByName(args.backendUrl, 'chats', title)",
    "apiDeleteByName(args.backendUrl, 'agents', tempAgentName)",
  ]
  for (const expected of present) {
    assert.ok(ui.includes(expected), expected)
  }
  for (const absent of [
    '选择 Environment',
    'ui.chat.create_agent_bound_environment',
    'environmentId: card.id',
    'modelProviderName',
    'agentCreateRes.status === 200',
  ]) {
    assert.ok(!ui.includes(absent), absent)
  }
})

test('test intent: the lifecycle fixture seeds canonical branch settings', () => {
  // HarnessRuntimePostgresqlLifecycleIntegrationTest must seed canonical branch settings.
  const content = readFileSync(LIFECYCLE_TEST_JAVA, 'utf8')
  const block = content.match(/ROOT_PAYLOAD_JSON\s*=\s*"""([\s\S]*?)"""/u)
  assert.ok(block, 'lifecycle fixture must declare the root payload')
  const payload = JSON.parse(block[1].replaceAll('\\\n', ''))
  assert.deepEqual(Object.keys(payload.settings).sort(), ['agentName', 'environmentName', 'execution'])
  assert.equal(payload.settings.agentName, 'lifecycle-test')
})
