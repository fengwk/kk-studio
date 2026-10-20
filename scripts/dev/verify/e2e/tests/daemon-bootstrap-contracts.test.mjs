/** Permanent contract regression for the E2E daemon bootstrap and its disposable fixtures. */

import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { chmodSync, mkdirSync, mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import test from 'node:test'

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

function withTempDir(body) {
  const directory = mkdtempSync(path.join(tmpdir(), 'kk-bootstrap-'))
  try {
    return body(directory)
  } finally {
    rmSync(directory, { recursive: true, force: true })
  }
}

/** A minimal Node distribution directory; resolves to `{ dist, node }`. */
function nodeDist(temporary) {
  const dist = path.join(temporary, 'dist')
  mkdirSync(dist, { recursive: true })
  writeFileSync(path.join(dist, 'main.js'), '// daemon entry\n')
  return { dist, node: process.execPath }
}

const mode = (file) => statSync(file).mode & 0o777

function runBootstrap(env, { script = BOOTSTRAP } = {}) {
  return spawnSync('bash', [script], { env, encoding: 'utf8' })
}

test('test intent: the E2E seed pre-seeds exactly four disposable Environment Cards and stays re-runnable', () => {
  const content = readFileSync(SCHEMA_SEED, 'utf8')

  // 1. Four Cards check
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
  ]) {
    assert.ok(content.includes(expected), expected)
  }

  // 2. Re-runnable without deleting existing environments with FK/lease
  assert.ok(content.includes('on conflict (id) do update set'))
  assert.ok(!content.includes('delete from environment'))

  // 3. default-assistant is NOT bound to any topology-specific environment
  const match = /insert into agent_definition\s*\([^)]*\)\s*values\s*\([^)]*'default-assistant'[^;]*;/i.exec(content)
  assert.ok(match)
  assert.ok(!match[0].includes('environmentId'))
  assert.ok(!match[0].includes('11111111-1111-1111-1111-111111111111'))
})

test('test intent: bootstrap materializes the shared config and launches the Node entry with --config only', () => {
  const lib = readText(E2E_LIB_SH)
  const bootstrap = readText(BOOTSTRAP)

  // 旧 CLI/数据目录/JVM 参数不得复活；启动路径只走 bootstrap helper 启动 Node 入口。
  for (const removed of ['--gateway-uri', '--data-dir', '--registration-token-file', '--environment-root']) {
    assert.ok(!lib.includes(removed), removed)
  }
  assert.ok(lib.includes('daemon-bootstrap.sh'))
  assert.ok(lib.includes('DAEMON_ROOT'))
  assert.ok(!lib.includes('DAEMON_DATA_DIR'))
  assert.ok(!lib.includes('DAEMON_JAR'))
  assert.ok(lib.includes('DAEMON_DIST'))
  assert.ok(lib.includes('Daemon 运行数据目录'))
  // 凭证经 assignment 前缀进入子进程环境，绝不作为 `env` 的 argv 参数出现。
  assert.ok(lib.includes('DAEMON_REGISTRATION_TOKEN="$DAEMON_REGISTRATION_TOKEN" nohup env'))
  assert.ok(bootstrap.includes('--config'))
  assert.ok(!bootstrap.includes('--data-dir'))
  assert.ok(!bootstrap.includes('-jar'))
  assert.ok(!bootstrap.includes('JAVA_HOME'))
  assert.ok(lib.includes('$DAEMON_DIST/main.js'))

  // 执行 recorder：证明 config/secret 落盘布局与 daemon 启动参数，而非静态假通过。
  withTempDir((temporary) => {
    const { dist, node } = nodeDist(temporary)
    const probeDir = path.join(temporary, 'bin')
    mkdirSync(probeDir, { recursive: true })
    const argvFile = path.join(temporary, 'argv.txt')
    const environFile = path.join(temporary, 'environ.txt')
    const probe = path.join(probeDir, 'node-recorder')
    writeFileSync(
      probe,
      '#!/bin/bash\n' +
        `if [ "$1" = "-e" ]; then exec "${node}" "$@"; fi\n` +
        'printf \'%s\\n\' "$@" >"$PROBE_ARGV"\n' +
        'env >"$PROBE_ENVIRON"\n',
    )
    chmodSync(probe, 0o755)

    const root = path.join(temporary, 'daemon')
    const secret = 'e2e-token-host-tool'
    const note = 'E2E daemon environment — 日本語'
    const result = runBootstrap({
      ...process.env,
      PROBE_ARGV: argvFile,
      PROBE_ENVIRON: environFile,
      DAEMON_ROOT: root,
      DAEMON_STUDIO_URL: 'http://127.0.0.1:18081',
      DAEMON_NOTE: note,
      DAEMON_DIST: dist,
      DAEMON_NODE: probe,
      DAEMON_REGISTRATION_TOKEN: secret,
    })
    assert.equal(result.status, 0, result.stderr)

    const argv = readFileSync(argvFile, 'utf8').split('\n').filter(Boolean)
    assert.deepEqual(argv.slice(0, 2), [path.join(dist, 'main.js'), '--config'])
    const configPath = argv[argv.indexOf('--config') + 1]
    assert.ok(path.isAbsolute(configPath))
    assert.equal(configPath, path.join(root, 'daemon.json'))
    assert.ok(!argv.includes(secret))
    assert.ok(!readFileSync(environFile, 'utf8').includes(secret))
    assert.ok(!(result.stdout + result.stderr).includes(secret))
    assert.ok(!readFileSync(configPath, 'utf8').includes(secret))

    const config = JSON.parse(readFileSync(configPath, 'utf8'))
    assert.deepEqual(new Set(Object.keys(config)), new Set(['studioUrl', 'note']))
    assert.equal(config.studioUrl, 'http://127.0.0.1:18081')
    assert.equal(config.note, note)
    assert.equal(mode(root), 0o700)
    assert.equal(mode(configPath), 0o600)
    const tokenPath = path.join(root, 'daemon.token')
    assert.equal(mode(tokenPath), 0o600)
    assert.equal(readFileSync(tokenPath, 'utf8').trim(), secret)
  })
})

test('test intent: bootstrap fails closed without a registration token', () => {
  // 缺少注册凭证时 bootstrap 必须立即失败，而不是启动一个无法注册的 daemon。
  withTempDir((temporary) => {
    const { dist, node } = nodeDist(temporary)
    const env = {
      ...process.env,
      DAEMON_ROOT: path.join(temporary, 'daemon'),
      DAEMON_STUDIO_URL: 'http://127.0.0.1:18081',
      DAEMON_DIST: dist,
      DAEMON_NODE: node,
    }
    delete env.DAEMON_REGISTRATION_TOKEN
    const result = runBootstrap(env)
    assert.notEqual(result.status, 0)
    assert.ok(result.stderr.includes('DAEMON_REGISTRATION_TOKEN'))
  })
})

test('test intent: bootstrap rejects a relative DAEMON_ROOT', () => {
  withTempDir((temporary) => {
    const { dist, node } = nodeDist(temporary)
    const result = runBootstrap({
      ...process.env,
      DAEMON_ROOT: 'relative/daemon',
      DAEMON_STUDIO_URL: 'http://127.0.0.1:18081',
      DAEMON_DIST: dist,
      DAEMON_NODE: node,
      DAEMON_REGISTRATION_TOKEN: 'e2e-token-host-tool',
    })
    assert.notEqual(result.status, 0)
    assert.ok(result.stderr.includes('DAEMON_ROOT'))
    assert.ok(result.stderr.includes('absolute'))
  })
})

test('test intent: bootstrap creates its files owner-only without relying on chmod', () => {
  // 共享配置必须在写入时即 owner-only：桩掉 chmod 并把 umask 全开也不能出现 world-readable 窗口。
  withTempDir((temporary) => {
    const binDir = path.join(temporary, 'bin')
    mkdirSync(binDir)
    // chmod 桩为 no-op：只有 writeFileSync 的显式 mode 与 umask 能收敛权限。
    writeFileSync(path.join(binDir, 'chmod'), '#!/bin/bash\nexit 0\n')
    chmodSync(path.join(binDir, 'chmod'), 0o755)
    const { dist, node } = nodeDist(temporary)

    const root = path.join(temporary, 'daemon')
    const result = spawnSync('bash', ['-c', `umask 000; exec bash '${BOOTSTRAP}'`], {
      encoding: 'utf8',
      env: {
        ...process.env,
        PATH: `${binDir}${path.delimiter}${process.env.PATH ?? ''}`,
        DAEMON_ROOT: root,
        DAEMON_STUDIO_URL: 'http://127.0.0.1:18081',
        DAEMON_DIST: dist,
        DAEMON_NODE: node,
        DAEMON_REGISTRATION_TOKEN: 'e2e-token-host-tool',
      },
    })
    assert.equal(result.status, 0, result.stderr)
    assert.equal(mode(path.join(root, 'daemon.json')), 0o600)
    assert.equal(mode(path.join(root, 'daemon.token')), 0o600)
  })
})

test('test intent: distributed A and B never share a registration token', () => {
  const compose = readText(DISTRIBUTED_COMPOSE)
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
    'model: REAL_UI_MODEL_ID',
    'agentCreateRes.status === 201',
    "!Object.hasOwn(agentCreateRes.json.data, 'environmentId')",
    "!Object.hasOwn(agentCreateRes.json.data, 'branchSettings')",
    'button[aria-label="工作区路径"]',
    'button.environment-binding-trigger',
    'Create Chat still rendered a workspace path selector',
    "environmentText === '未选择环境'",
    // 当前契约：Create Chat 渲染默认 Environment 选择器，并显式投影 required-nullable environmentName。
    "name: '默认环境', exact: true",
    'created.environmentName === null',
    'required-nullable environmentName:null',
    // 已删除的 workspace/environment 字段仍必须被拒绝。
    "for (const field of ['workspacePath', 'environment', 'environmentId'])",
    '!Object.hasOwn(created, field)',
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
  assert.ok(!content.includes('"workspacePath"'))
  assert.ok(content.includes('{"settings": {"agentName": "lifecycle-test"'))
})
