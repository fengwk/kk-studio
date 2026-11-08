import assert from 'node:assert/strict'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import test from 'node:test'

import {
  ALLOWED_DISTRIBUTED_COMMANDS,
  createBaseUrls,
  createNodeCall,
  copyDistributedAppLogs,
  runDistributedCommand,
} from '../lib/distributed.mjs'
import { redactSecrets } from '../lib/redact.mjs'
import { daemonConfigFixture } from '../lib/fixtures.mjs'
import { problemError } from './fixtures/problem.mjs'
import { ALL_CASES } from '../run-matrix.mjs'
import { REPO_ROOT } from '../../../lib/repo-root.mjs'

test('createBaseUrls requires both node URLs and normalizes trailing slashes', () => {
  // Test intent: distributed capability is only active when both node URLs exist.
  assert.equal(createBaseUrls('', ''), null)
  assert.equal(createBaseUrls('http://a', ''), null)
  assert.equal(createBaseUrls('', 'http://b'), null)
  assert.deepEqual(createBaseUrls('http://a/', 'http://b/'), {
    a: 'http://a',
    b: 'http://b',
  })
})

test('createNodeCall routes by node id and rejects unknown nodes', async () => {
  // Test intent: ctx.callNode('a'|'b') must hit exactly the selected node URL.
  const seen = []
  const originalFetch = globalThis.fetch
  globalThis.fetch = async (url, options) => {
    seen.push({ url, method: options?.method })
    return new Response(JSON.stringify({ ok: true }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })
  }
  try {
    const call = createNodeCall(createBaseUrls('http://node-a.test', 'http://node-b.test'))
    const a = await call('a', 'GET', '/api/platform/agents')
    const b = await call('b', 'GET', '/api/platform/agents')
    assert.equal(seen[0].url, 'http://node-a.test/api/platform/agents')
    assert.equal(seen[1].url, 'http://node-b.test/api/platform/agents')
    assert.equal(a.json.ok, true)
    assert.equal(b.json.ok, true)

    await assert.rejects(() => call('c', 'GET', '/api/platform/agents'), /unknown node 'c'/)
    assert.equal(seen.length, 2, 'unknown node must not trigger a request')
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('createNodeCall forwards the body as JSON like the single-node path', async () => {
  // Test intent: dual-node routing must not change the httpJson wire contract.
  const seen = []
  const originalFetch = globalThis.fetch
  globalThis.fetch = async (url, options) => {
    seen.push({ url, body: options?.body, headers: options?.headers })
    return new Response('{"id":1}', { status: 200, headers: { 'Content-Type': 'application/json' } })
  }
  try {
    const call = createNodeCall(createBaseUrls('http://a.test', 'http://b.test'))
    await call('b', 'POST', '/api/canvas/canvases', { title: 't' }, 5000)
    assert.deepEqual(JSON.parse(seen[0].body), { title: 't' })
    assert.equal(seen[0].headers['Content-Type'], 'application/json')
    assert.equal(seen[0].url, 'http://b.test/api/canvas/canvases')
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('host-mock cases stay in the single-instance matrix and leave distributed', async () => {
  // Test intent: cases with host-loopback mocks cannot run against containerized
  // distributed apps; the capability must be default-on and disabled by --distributed.
  const source = await import('node:fs').then((fs) =>
    fs.readFileSync(path.join(REPO_ROOT, 'scripts/dev/verify/e2e/run-matrix.mjs'), 'utf8'),
  )
  assert.match(source, /c\.requires\.has\('host-mock'\) && args\.distributed\) return false/)
  // 单实例默认路径不受影响：host-mock 只在 distributed 下被排除。
  assert.match(source, /if \(c\.requires\.has\('host-mock'\) && args\.distributed\) return false/)
})

test('redactSecrets masks credential values and keeps ordinary text', () => {
  // Test intent: distributed reports must never carry credentials into reports/e2e.
  assert.match(redactSecrets('TEST_MINIMAX_API_KEY=sk-value-123'), /^TEST_MINIMAX_API_KEY: \[REDACTED\]$/)
  assert.match(
    redactSecrets('{"TEST_MINIMAX_API_KEY": "sk-value-123"}'),
    /"TEST_MINIMAX_API_KEY": "\[REDACTED\]"/,
  )
  assert.equal(redactSecrets('Authorization: Bearer jwt.value'), 'Authorization: [REDACTED]')
  assert.match(redactSecrets('gatewayToken=tok-1'), /^gatewayToken: \[REDACTED\]$/)
  // 非 credential 键（URL、普通文本）不脱敏。
  assert.equal(
    redactSecrets('{"TEST_MINIMAX_BASE_URL": "https://host.example/v1"}'),
    '{"TEST_MINIMAX_BASE_URL": "https://host.example/v1"}',
  )
  assert.equal(
    redactSecrets('plain text and baseUrl=https://host.example/v1 stay'),
    'plain text and baseUrl=https://host.example/v1 stay',
  )
  assert.equal(redactSecrets(null), null)
})

test('app file logs are copied before teardown, redacted, and raw staging is removed', () => {
  // Intent: real filesystem copying covers logs absent from stdout without leaking raw credentials.
  const runDir = mkdtempSync(path.join(tmpdir(), 'distributed-report-test-'))
  const stagingDirs = []
  const invocations = []
  const exec = (cmd, args) => {
    invocations.push({ cmd, args })
    if (args[0] === 'compose') return `${args.at(-1)}-container\n`
    assert.equal(args[0], 'cp')
    assert.match(args[1], /^app-[ab]-container:\/app\/logs\/\.$/)
    const staging = args[2]
    stagingDirs.push(staging)
    mkdirSync(path.join(staging, 'nested'))
    writeFileSync(path.join(staging, 'kk-studio-all.log'), 'PgConnection.isValid\npassword=test-secret')
    writeFileSync(path.join(staging, 'nested', 'trace.log'), 'Authorization: Bearer test-token')
    writeFileSync(path.join(staging, 'archive.log.gz'), 'not safe to copy')
    symlinkSync(path.join(staging, 'kk-studio-all.log'), path.join(staging, 'link.log'))
    return ''
  }
  try {
    copyDistributedAppLogs(runDir, { exec, repoRoot: '/test/repo' })
    assert.equal(invocations.length, 4)
    assert.deepEqual(invocations[0].args,
      ['compose', '-f', '/test/repo/deploy/distributed/compose.yaml', 'ps', '-q', 'app-a'])
    for (const service of ['app-a', 'app-b']) {
      const dest = path.join(runDir, 'logs', `distributed-${service}`)
      assert.equal(readFileSync(path.join(dest, 'kk-studio-all.log'), 'utf8'),
        'PgConnection.isValid\npassword: [REDACTED]')
      assert.equal(readFileSync(path.join(dest, 'nested', 'trace.log'), 'utf8'),
        'Authorization: [REDACTED]')
      assert.equal(existsSync(path.join(dest, 'archive.log.gz')), false)
      assert.equal(existsSync(path.join(dest, 'link.log')), false)
    }
    assert.ok(stagingDirs.every((dir) => !existsSync(dir)))
  } finally {
    rmSync(runDir, { recursive: true, force: true })
  }
})

test('app log collection cleans partial copies and continues after missing nodes', () => {
  // Intent: docker cp can fail after writing raw files; cleanup must also cover this failure path.
  const runDir = mkdtempSync(path.join(tmpdir(), 'distributed-report-test-'))
  let staging
  const services = []
  try {
    copyDistributedAppLogs(runDir, { exec: (cmd, args) => {
      if (args[0] === 'compose') {
        services.push(args.at(-1))
        return args.at(-1) === 'app-a' ? 'app-a-container\n' : ''
      }
      staging = args[2]
      writeFileSync(path.join(staging, 'partial.log'), 'password=test-secret')
      throw new Error('container stopped during copy')
    } })
    assert.deepEqual(services, ['app-a', 'app-b'])
    assert.equal(existsSync(staging), false)
    assert.equal(existsSync(path.join(runDir, 'logs')), false)
  } finally {
    rmSync(runDir, { recursive: true, force: true })
  }
})

test('matrix collects app file logs before publishing reports and shell teardown', async () => {
  // Intent: guard production wiring, not merely the injectable copy helper.
  const fs = await import('node:fs')
  const runner = fs.readFileSync(path.join(REPO_ROOT, 'scripts/dev/verify/e2e/run-matrix.mjs'), 'utf8')
  assert.match(runner, /copyDistributedAppLogs\(runDir\)/)
  const collectAt = runner.indexOf(
    'if (args.distributed) maybeCopyDistributedContainerLogs(runDir)',
    runner.indexOf('async function main'),
  )
  assert.ok(collectAt >= 0, 'distributed collection must be wired in main')
  assert.ok(collectAt < runner.lastIndexOf('publishLatest('), 'collect before publishing')
  const shell = fs.readFileSync(path.join(REPO_ROOT, 'scripts/dev/verify/e2e/run.sh'), 'utf8')
  assert.match(shell, /trap cleanup_distributed EXIT/)
})

test('DB loss case keeps one 10s fault request and restores both READY projections', async () => {
  // Intent: record HTTP timing without retrying a failed operation or changing its budget.
  await import('../cases/distributed.mjs')
  const faultCase = ALL_CASES.find((entry) => entry.id === 'distributed.db_loss_fail_closed')
  for (const transportTimeout of [false, true]) {
    const commands = []
    const recoveredNodes = []
    let disconnected = false
    let faultRequests = 0
    let summary
    const ctx = {
      baseUrls: { a: 'http://a', b: 'http://b' },
      runDistributedCommand(command) {
        commands.push(command)
        disconnected = command === 'disconnect-db-a'
      },
      async callNode(node, method, requestPath, body, timeoutMs) {
        if (disconnected) {
          faultRequests++
          assert.equal(node, 'a')
          assert.equal(timeoutMs, 10_000)
          if (transportTimeout) throw new Error('timeout GET')
          throw problemError(500, 'internal', 'database disconnected', requestPath)
        }
        if (commands.length) recoveredNodes.push(node)
        return { json: { ready: true, status: 'READY', homeDirectory: '/home/test' } }
      },
      writeArtifact(name, data) {
        assert.equal(name, 'recovery-summary.json')
        summary = JSON.parse(data)
      },
    }
    if (transportTimeout) {
      await assert.rejects(() => faultCase.run(ctx), /expected HttpError/)
      assert.equal(summary, undefined)
    } else {
      await faultCase.run(ctx)
      assert.equal(summary.dbLossStatus, 500)
      assert.ok(summary.dbLossDurationMs >= 0)
      assert.deepEqual(recoveredNodes, ['a', 'b'])
      assert.equal(summary.recoveredOnA.status, 'READY')
      assert.equal(summary.recoveredOnB.status, 'READY')
    }
    assert.equal(faultRequests, 1, 'never retry the fault request')
    assert.deepEqual(commands, ['disconnect-db-a', 'reconnect-db-a'])
  }
})

test('shared-state case edits through node B, rejects rename on the card PUT and deletes the latest version', async () => {
  // 测试意图：stub 按真实卡片 PUT 语义解析 body/CAS，而不是只匹配 405/400 状态；证明非法 name
  // 在携带最新 expectedVersion、note、完整 daemonConfig 时仍被 400 拒绝，且有效 node B PUT 让 node A
  // 读到新版本与配置。
  const environmentId = '00000000-0000-4000-8000-000000000123'
  const environmentPath = `/api/platform/environments/${environmentId}`
  let environmentName = null
  let note = null
  let daemonConfig = null
  let version = '0'
  let registrationToken = 'token-0'
  let tokenSequence = 0
  let deleted = false
  const calls = []
  const artifacts = []

  const snapshot = () => ({ id: environmentId, name: environmentName, note, daemonConfig, version })

  function requireExpectedVersion(body, requestPath) {
    if (!body || !Object.hasOwn(body, 'expectedVersion')) {
      throw problemError(400, 'validation', 'expectedVersion is required', requestPath)
    }
    if (String(body.expectedVersion) !== version) {
      throw problemError(409, 'version_conflict', 'version conflict', requestPath)
    }
  }

  function validateDaemonConfig(value, requestPath) {
    if (!value || typeof value !== 'object' || Array.isArray(value)) {
      throw problemError(400, 'validation', 'daemonConfig is invalid', requestPath)
    }
    assert.deepEqual(
      Object.keys(value).sort(),
      ['bashExecutable', 'lsp', 'proxyUrl', 'studioUrl'],
      'PUT daemonConfig must be the complete canonical object',
    )
    assert.equal(typeof value.studioUrl, 'string')
    assert.ok(value.studioUrl.length > 0)
    assert.ok(value.lsp && typeof value.lsp === 'object')
    assert.ok(value.proxyUrl === null || typeof value.proxyUrl === 'string')
    assert.ok(value.bashExecutable === null || typeof value.bashExecutable === 'string')
  }

  const ctx = {
    caseId: 'distributed.shared_state',
    baseUrls: { a: 'http://node-a.test', b: 'http://node-b.test' },
    async callNode(node, method, requestPath, body) {
      calls.push({ node, method, path: requestPath, body })
      const url = new URL(requestPath, 'http://node.test')
      const pathname = url.pathname
      if (method === 'POST' && pathname === '/api/platform/environments') {
        environmentName = body.name
        note = body.note
        daemonConfig = body.daemonConfig
        version = '0'
        return { json: { ...snapshot(), registrationToken } }
      }
      if (method === 'GET' && pathname === environmentPath) {
        if (deleted) throw problemError(404, 'not_found', 'environment not found', pathname)
        return { json: snapshot() }
      }
      if (method === 'POST' && pathname === `${environmentPath}/rotate-token`) {
        requireExpectedVersion(body, pathname)
        registrationToken = `token-${++tokenSequence}`
        version = String(BigInt(version) + 1n)
        return { json: { ...snapshot(), registrationToken } }
      }
      if (method === 'PUT' && pathname === environmentPath) {
        assert.equal(url.search, '', 'card PUT must not use query expectedVersion')
        if (body && Object.hasOwn(body, 'name')) {
          // 严格 HTTP mapper 在领域 CAS 前拒绝未知 name；这里同时证明用例提供了完整真实更新 body。
          assert.ok(Object.hasOwn(body, 'expectedVersion'), 'rename request must include expectedVersion')
          assert.ok(Object.hasOwn(body, 'note'), 'rename request must include note')
          assert.ok(Object.hasOwn(body, 'daemonConfig'), 'rename request must include daemonConfig')
          assert.notEqual(body.name, environmentName, 'rename attempt must actually change name')
          throw problemError(400, 'validation', "unknown field 'name'", pathname)
        }
        if (!body || !Object.hasOwn(body, 'expectedVersion')) {
          throw problemError(400, 'validation', 'expectedVersion is required', pathname)
        }
        const unknown = Object.keys(body).filter(
          (key) => !['expectedVersion', 'note', 'daemonConfig'].includes(key),
        )
        if (unknown.length > 0) {
          throw problemError(400, 'validation', `unknown field '${unknown[0]}'`, pathname)
        }
        requireExpectedVersion(body, pathname)
        validateDaemonConfig(body.daemonConfig, pathname)
        note = body.note
        daemonConfig = body.daemonConfig
        version = String(BigInt(version) + 1n)
        return { json: snapshot() }
      }
      if (method === 'DELETE' && pathname === environmentPath) {
        if (url.searchParams.get('expectedVersion') !== version) {
          throw problemError(409, 'version_conflict', 'version conflict', pathname)
        }
        deleted = true
        return { json: null }
      }
      assert.fail(`unexpected call ${method} ${requestPath}`)
    },
    writeArtifact(name, data) {
      artifacts.push({ name, data })
    },
  }

  const sharedState = ALL_CASES.find((entry) => entry.id === 'distributed.shared_state')
  assert.ok(sharedState, 'distributed.shared_state must be registered')
  await sharedState.run(ctx)

  const puts = calls.filter((call) => call.method === 'PUT')
  assert.equal(puts.length, 2, 'case must perform one valid edit and one rejected rename')
  const validPut = puts.find((call) => call.node === 'b')
  const invalidPut = puts.find((call) => call.node === 'a')
  assert.ok(validPut, 'valid edit must go through node B')
  assert.ok(invalidPut, 'rename rejection must go through node A')

  assert.equal(validPut.path, environmentPath)
  assert.equal(validPut.path.includes('?'), false)
  assert.deepEqual(Object.keys(validPut.body).sort(), ['daemonConfig', 'expectedVersion', 'note'])
  assert.equal(validPut.body.note, 'distributed edited note')
  assert.deepEqual(validPut.body.daemonConfig, daemonConfigFixture({ proxyUrl: 'http://127.0.0.1:7891' }))
  assert.deepEqual(
    Object.keys(validPut.body.daemonConfig).sort(),
    ['bashExecutable', 'lsp', 'proxyUrl', 'studioUrl'],
  )

  assert.equal(invalidPut.path, environmentPath)
  assert.equal(invalidPut.path.includes('?'), false)
  assert.equal(invalidPut.body.name, `${environmentName}-renamed`)
  assert.equal(invalidPut.body.note, validPut.body.note)
  assert.deepEqual(invalidPut.body.daemonConfig, validPut.body.daemonConfig)
  assert.equal(
    invalidPut.body.expectedVersion,
    String(BigInt(validPut.body.expectedVersion) + 1n),
    'rename request must carry the version advanced by the valid edit',
  )
  assert.equal(version, invalidPut.body.expectedVersion, 'rejected rename must not advance version')

  const deleteCall = calls.find((call) => call.method === 'DELETE')
  assert.ok(deleteCall, 'case must delete the environment')
  assert.equal(deleteCall.node, 'b')
  assert.ok(deleteCall.path.includes(`expectedVersion=${version}`), 'delete must use the edited version')

  assert.equal(artifacts.length, 1)
  const artifact = JSON.parse(artifacts[0].data)
  assert.equal(artifact.immutableName, environmentName)
  assert.equal(artifact.finalVersion, version)
  assert.equal(artifact.deleted, true)
  assert.equal(artifacts[0].data.includes(registrationToken), false)
})

test('runDistributedCommand forwards whitelisted commands with exact arguments and repoRoot', () => {
  // Test intent: verify whitelisted commands call scripts/dev/verify/e2e/distributed.sh with exact arguments and execution options.
  const invocations = []
  const mockExec = (cmd, args, opts) => {
    invocations.push({ cmd, args, opts })
    return 'ok\n'
  }
  const customRoot = '/fake/repo/root'

  for (const whitelisted of ALLOWED_DISTRIBUTED_COMMANDS) {
    const out = runDistributedCommand(whitelisted, { repoRoot: customRoot, exec: mockExec, timeout: 5000 })
    assert.equal(out, 'ok\n')
  }

  assert.equal(invocations.length, ALLOWED_DISTRIBUTED_COMMANDS.length)
  assert.equal(
    invocations[0].cmd,
    '/fake/repo/root/scripts/dev/verify/e2e/distributed.sh',
  )
  assert.deepEqual(invocations[0].args, ['disconnect-db-a'])
  assert.equal(invocations[0].opts.cwd, customRoot)
  assert.equal(invocations[0].opts.timeout, 5000)
  assert.deepEqual(invocations[1].args, ['reconnect-db-a'])
  assert.deepEqual(invocations[2].args, ['status'])
  assert.deepEqual(
    invocations.map((i) => i.args[0]),
    ALLOWED_DISTRIBUTED_COMMANDS,
  )
})

test('runDistributedCommand rejects arbitrary or disallowed commands fail-closed', () => {
  // Test intent: runner control helper must enforce a strict whitelist and reject arbitrary shell execution.
  const invocations = []
  const mockExec = (cmd, args, opts) => {
    invocations.push({ cmd, args, opts })
    return 'ok\n'
  }

  for (const disallowed of ['down', 'up', 'rm -rf /', 'sh', 'logs', '']) {
    assert.throws(
      () => runDistributedCommand(disallowed, { exec: mockExec }),
      /is not allowed, expected one of/,
    )
  }
  assert.equal(invocations.length, 0, 'disallowed commands must never reach the exec runner')
})

test('runDistributedCommand whitelist is strictly fixed and ignores any options.allowedCommands', () => {
  // Test intent: verify that caller options cannot expand or replace the immutable whitelist.
  const invocations = []
  const mockExec = (cmd, args, opts) => {
    invocations.push({ cmd, args, opts })
    return 'ok\n'
  }

  assert.throws(
    () => runDistributedCommand('down', { allowedCommands: ['down'], exec: mockExec }),
    /is not allowed, expected one of/,
  )
  assert.equal(invocations.length, 0, 'overriding allowedCommands must be strictly ignored')
})
