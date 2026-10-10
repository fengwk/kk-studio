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
  waitForNodeHealth,
} from '../lib/distributed.mjs'
import { redactSecrets } from '../lib/redact.mjs'
import { HttpError } from '../lib/http.mjs'
import { ALL_CASES } from '../lib/registry.mjs'
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
    return new Response(JSON.stringify({ data: { ok: true } }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })
  }
  try {
    const call = createNodeCall(createBaseUrls('http://node-a.test', 'http://node-b.test'))
    const a = await call('a', 'GET', '/api/ai/catalog/agents?pageNumber=1&pageSize=1')
    const b = await call('b', 'GET', '/api/ai/catalog/agents?pageNumber=1&pageSize=1')
    assert.equal(seen[0].url, 'http://node-a.test/api/ai/catalog/agents?pageNumber=1&pageSize=1')
    assert.equal(seen[1].url, 'http://node-b.test/api/ai/catalog/agents?pageNumber=1&pageSize=1')
    assert.equal(a.json.data.ok, true)
    assert.equal(b.json.data.ok, true)

    await assert.rejects(() => call('c', 'GET', '/api/ai/catalog/agents'), /unknown node 'c'/)
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
    return new Response('{"data":1}', { status: 200, headers: { 'Content-Type': 'application/json' } })
  }
  try {
    const call = createNodeCall(createBaseUrls('http://a.test', 'http://b.test'))
    await call('b', 'POST', '/api/canvases', { title: 't' }, 5000)
    assert.deepEqual(JSON.parse(seen[0].body), { title: 't' })
    assert.equal(seen[0].headers['Content-Type'], 'application/json')
    assert.equal(seen[0].url, 'http://b.test/api/canvases')
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('recovery health barrier waits for LISTEN health, not only a successful DB read', async () => {
  let calls = 0
  await waitForNodeHealth({
    async callNode(node, method, requestPath, body, timeoutMs) {
      assert.equal(node, 'a')
      assert.equal(method, 'GET')
      assert.equal(requestPath, '/actuator/health')
      assert.ok(timeoutMs > 0 && timeoutMs <= 2_000)
      if (++calls === 1) throw new HttpError(503, 'not ready', requestPath)
      return { json: { status: calls === 2 ? 'DOWN' : 'UP' } }
    },
  }, 'a', 1_000)
  assert.equal(calls, 3)
  await assert.rejects(() => waitForNodeHealth({
    async callNode() { return { json: { status: 'DOWN' } } },
  }, 'a', 1), /health did not recover/)
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
          throw new HttpError(500, 'database disconnected', requestPath)
        }
        if (commands.length) recoveredNodes.push(node)
        return { json: { data: { ready: true, status: 'READY', homeDirectory: '/home/test' } } }
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
