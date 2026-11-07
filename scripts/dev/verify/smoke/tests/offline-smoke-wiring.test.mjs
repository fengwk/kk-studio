/**
 * offline-smoke.mjs 的闭环必须真正使用共享契约并发出当前 wire：createAndRun 与 opencli 初始化
 * 被直接执行，请求被记录、应答按固定序列返回，因此 payload、revision 推进与 replay 幂等都是真实
 * 行为，而不是源码字符串匹配。
 */

import assert from 'node:assert/strict'
import { createHash, randomUUID } from 'node:crypto'
import { existsSync } from 'node:fs'
import { createServer } from 'node:http'
import path from 'node:path'
import test from 'node:test'

import { repositoryRoot } from '../../../lib/repo-root.mjs'
import { functionBatch } from '../offline-canvas-contract.mjs'
import { createAndRun, createClient, createStubCatalog, initializeOpencliPlugin, uploadBlob } from '../offline-smoke.mjs'
import { CANVAS_ID, documentDto, functionDefinitions, functionVersion, patchDto, runDto } from './support/canvas-dto.mjs'

const REPOSITORY_ROOT = repositoryRoot()
const clone = (value) => structuredClone(value)

test('application client reads resource bodies and empty 204 responses', async () => {
  // Exercise the actual fetch path rather than a substituted jsonCall.
  const server = createServer((request, response) => {
    if (request.url === '/empty') {
      response.writeHead(204).end()
    } else {
      response.writeHead(200, { 'Content-Type': 'application/json' }).end('{"id":"canvas"}')
    }
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  try {
    const client = createClient(`http://127.0.0.1:${server.address().port}`)
    assert.deepEqual(await client.jsonCall('GET', '/canvas'), { id: 'canvas' })
    assert.equal(await client.jsonCall('DELETE', '/empty'), null)
  } finally {
    await new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()))
  }
})

/**
 * 按固定顺序应答 createAndRun 的请求，并记录它真正发出的 wire payload。
 *
 * 序列覆盖目录版本、声明、启动、轮询成功、快照和重放。每次应答都是独立副本，
 * 因此脚本对 replay 的相等比较无法被共享引用绕过。
 */
class CreateAndRunHarness {
  static LIFECYCLE_REVISIONS = 4 // start、beginSubmit、confirmSubmitted、complete

  constructor(functionName, checkpointCount) {
    this.functionName = functionName
    this.nodeId = randomUUID()
    this.requestId = randomUUID()
    // 脚本按 nodeId、idempotencyKey、requestId 的顺序取三个 id。
    this.ids = [this.nodeId, randomUUID(), this.requestId]
    this.completed = runDto(this.nodeId, this.requestId)
    this.node = {
      id: this.nodeId,
      name: `mock-${functionName}`,
      canvasId: CANVAS_ID,
      groupId: null,
      transform: { x: 100, y: 100, width: 320, height: 260 },
      function: { name: functionName, args: {} },
      run: this.completed,
      resources: [
        {
          id: randomUUID(),
          name: 'generated.png',
          kind: functionName.startsWith('seedance') ? 'VIDEO' : 'IMAGE',
        },
      ],
    }
    const settled = documentDto(1 + CreateAndRunHarness.LIFECYCLE_REVISIONS + checkpointCount)
    this.responses = [
      functionDefinitions([functionName]),
      { document: documentDto(0), nodes: [], groups: [], references: [] },
      patchDto('1', [{ type: 'UPSERT', node: { ...this.node, resources: [], run: null } }]),
      runDto(this.nodeId, this.requestId, { status: 'READY' }),
      this.completed,
      this.snapshot(settled),
      this.completed,
      this.snapshot(settled),
    ]
    this.requests = []
  }

  snapshot(document) {
    return { document, nodes: [this.node], groups: [], references: [] }
  }

  async jsonCall(method, requestPath, body = null) {
    this.requests.push([method, requestPath, body])
    const response = this.responses.shift()
    if (method === 'POST' && requestPath.endsWith('/commands')) {
      // 回读投影保留实际声明的名称与函数参数，不凭空生成另一份配置。
      this.node.name = body.commands[0].name
      this.node.function = body.commands[1].function
      Object.assign(response.nodes[0].node, { name: this.node.name, function: this.node.function })
    }
    return clone(response)
  }

  /** 执行脚本自带的 createAndRun：builders 与断言都是真实共享契约。 */
  run(functionName, name, parameters, checkpointCount) {
    const ids = [...this.ids]
    const client = {
      jsonCall: (...args) => this.jsonCall(...args),
      newId: () => ids.shift(),
      sleep: async () => {},
    }
    return createAndRun(client, CANVAS_ID, functionName, name, parameters, checkpointCount)
  }
}

test('test intent: createAndRun sends flat args, settles the revision and replays without side effects', async () => {
  for (const [functionName, name, parameters, checkpoints] of [
    ['gpt-image-2', 'mock-gpt', { ratio: '1:1' }, 4],
    ['seedance2.0fast', 'mock-seedance', { ratio: '16:9', duration: 4 }, 6],
  ]) {
    const harness = new CreateAndRunHarness(functionName, checkpoints)
    const node = await harness.run(functionName, name, parameters, checkpoints)

    const batch = harness.requests.find(([, requestPath]) => requestPath.endsWith('/commands'))[2]
    const [create, assign] = batch.commands
    assert.deepEqual([create.type, create.name, create.resources], ['CREATE_NODE', name, []])
    assert.deepEqual(assign, {
      type: 'SET_NODE_FUNCTION',
      nodeId: harness.nodeId,
      expectedFunction: null,
      function: { name: functionName, args: { prompt: `mock ${functionName}`, ...parameters } },
    })
    assert.deepEqual(
      batch,
      functionBatch(harness.nodeId, name, functionName, assign.function.args, batch.idempotencyKey),
    )
    // run 请求必须回传目录项的真实版本；收尾后相同 requestId 的重放必须原样返回。
    assert.deepEqual(
      harness.requests
        .filter(([method, requestPath]) => method === 'POST' && requestPath.endsWith('/function-run'))
        .map(([, , body]) => body),
      Array(2).fill({ requestId: harness.requestId, expectedVersion: functionVersion(harness.functionName) }),
    )
    assert.deepEqual(node.resources, harness.node.resources)
    const canvasPath = `/api/canvas/canvases/${CANVAS_ID}`
    const runPath = `${canvasPath}/nodes/${harness.nodeId}/function-run`
    assert.deepEqual(
      harness.requests.map(([method, requestPath]) => [method, requestPath]),
      [
        ['GET', '/api/canvas/functions'],
        ['GET', canvasPath],
        ['POST', `${canvasPath}/commands`],
        ['POST', runPath],
        ['GET', runPath],
        ['GET', canvasPath],
        ['POST', runPath],
        ['GET', canvasPath],
      ],
      functionName,
    )
    assert.deepEqual(harness.responses, [])
  }
})

test('test intent: createAndRun fails when the replay returns a different run', async () => {
  const harness = new CreateAndRunHarness('gpt-image-2', 4)
  harness.responses[6] = runDto(harness.nodeId, harness.requestId, { updatedAt: '2026-01-01T00:00:01Z' })
  await assert.rejects(harness.run('gpt-image-2', 'mock-gpt', { ratio: '1:1' }, 4), assert.AssertionError)
})

test('test intent: createAndRun rejects revision or resource changes on replay', async () => {
  for (const mutation of [{ document: documentDto(10) }, { nodes: [] }]) {
    const harness = new CreateAndRunHarness('gpt-image-2', 4)
    harness.responses[7] = { ...harness.responses[7], ...mutation }
    await assert.rejects(
      harness.run('gpt-image-2', 'mock-gpt', { ratio: '1:1' }, 4),
      assert.AssertionError,
      JSON.stringify(Object.keys(mutation)),
    )
  }
})

test('test intent: the smoke script lives outside the deploy runtime assets', () => {
  // Human host runners belong to scripts/; deploy/ keeps only compose/images/runtime assets.
  assert.ok(existsSync(path.join(REPOSITORY_ROOT, 'scripts/dev/verify/smoke/offline-chat.sh')))
  assert.ok(!existsSync(path.join(REPOSITORY_ROOT, 'deploy/test/run.sh')))
})

test('test intent: the stub catalog uses the unified SecretUpdate credential and current model/agent DTOs', async () => {
  const requests = []
  const jsonCall = async (method, requestPath, body = null) => {
    requests.push([method, requestPath, body])
    return requestPath === '/api/platform/providers' ? { credential: { configured: true } } : {}
  }

  await createStubCatalog({ jsonCall })

  assert.deepEqual(
    requests.map(([method, requestPath]) => [method, requestPath]),
    [
      ['POST', '/api/platform/providers'],
      ['POST', '/api/platform/models'],
      ['POST', '/api/platform/agents'],
    ],
  )
  const provider = requests[0][2]
  assert.deepEqual(provider.credential, { operation: 'SET', value: 'stub-key' })
  assert.equal(provider.modelCallTimeoutMillis, '1800000')
  assert.equal(provider.modelCallIdleTimeoutMillis, '120000')
  const model = requests[1][2]
  assert.equal(model.type, 'MODEL')
  assert.deepEqual(model.config.limit, { context: 128000, output: 4096 })
  assert.deepEqual(model.config.variants, [{ id: 'default', promptCacheRetention: 'SHORT' }])
  const agent = requests[2][2]
  assert.deepEqual(agent.execution, {
    driver: 'builtin',
    settings: { modelName: 'acceptance-stub', variant: 'default' },
  })
  assert.deepEqual(agent.config, { tools: [], skills: [], subagents: [] })
})

test('test intent: blob upload reads the current PENDING/READY union and enforces create-only PUT', async () => {
  const image = Buffer.from([0x89, 0x50, 0x4e, 0x47])
  const sha256 = createHash('sha256').update(image).digest('hex')
  const uploadId = randomUUID()
  const blobId = randomUUID()
  const reservation = {
    type: 'PENDING',
    id: uploadId,
    expiresAt: 1700000000000,
    presignedPut: {
      method: 'PUT',
      url: 'http://uploads.local/object',
      headers: {
        'if-none-match': '*',
        'x-amz-checksum-sha256': Buffer.from(sha256, 'hex').toString('base64'),
      },
      expiresAt: 1700000001000,
      mediaType: 'image/png',
      sizeBytes: String(image.length),
    },
  }
  const completed = { type: 'READY', id: uploadId, blobId, expiresAt: 1700000002000 }
  const requests = []
  const jsonCall = async (method, requestPath, body = null) => {
    requests.push([method, requestPath, body])
    return requestPath.endsWith('/complete') ? completed : reservation
  }
  const uploads = []
  const statuses = [200, 409, 400]
  const fetchImpl = async (url, init) => {
    uploads.push([url, init])
    return { status: statuses.shift() }
  }

  const result = await uploadBlob({ jsonCall }, image, fetchImpl)

  assert.equal(result.blobId, blobId)
  assert.deepEqual(requests, [
    [
      'POST',
      '/api/platform/storage/uploads',
      { filename: 'tiny.png', mediaType: 'image/png', sizeBytes: String(image.length), sha256 },
    ],
    ['POST', `/api/platform/storage/uploads/${uploadId}/complete`, null],
  ])
  assert.deepEqual(
    uploads.map(([url, init]) => [url, init.method, init.headers['if-none-match']]),
    [
      ['http://uploads.local/object', 'PUT', '*'],
      ['http://uploads.local/object', 'PUT', '*'],
      ['http://uploads.local/object', 'PUT', '*'],
    ],
  )
})

// 统一初始化契约：GET 坐标 -> PUT 秘密补丁 -> 状态轮询。

const noSleep = { sleep: async () => {} }

test('test intent: opencli initialization takes coordinates from the GET and sends the secret patch', async () => {
  const requests = []
  const jsonCall = async (method, requestPath, body = null) => {
    requests.push([method, requestPath, body])
    if (method === 'GET' && requestPath === '/api/platform/plugins/opencli') {
      return requests.length === 1
        ? {
            configuration: { version: '99' },
            appliedVersion: '98',
            status: 'NOT_CONNECTED',
            applicationError: null,
          }
        : {
            configuration: { version: '100' },
            appliedVersion: '100',
            status: 'CONNECTED',
            applicationError: null,
          }
    }
    if (method === 'PUT' && requestPath === '/api/platform/plugins/opencli/configuration') {
      return { configuration: { version: '100' } }
    }
    throw new assert.AssertionError({ message: `unexpected request ${method} ${requestPath}` })
  }

  await initializeOpencliPlugin({ jsonCall, ...noSleep })

  assert.ok(requests.length >= 3)
  assert.deepEqual(requests[0], ['GET', '/api/platform/plugins/opencli', null])
  const [putMethod, putPath, putBody] = requests[1]
  assert.equal(putMethod, 'PUT')
  assert.equal(putPath, '/api/platform/plugins/opencli/configuration')
  // 验证从 GET 实际取得坐标
  assert.equal(putBody.expectedVersion, '99')
  assert.deepEqual(putBody.secretPatch, { apiKey: { operation: 'SET', value: 'synthetic-opencli-key' } })
  const values = JSON.parse(putBody.valuesJson)
  assert.equal(values.enabled, true)
  assert.equal(values.baseUrl, 'http://opencli-hub:8080')
  assert.equal(values.gptImage2.paidEnabled, true)
  assert.equal(values.seedance.enabled, true)
  assert.equal(values.seedance.workspaceId, 'mock-workspace')
})

test('test intent: opencli initialization fails when the poll never converges', async () => {
  const jsonCall = async (method) =>
    method === 'GET'
      ? {
          configuration: { version: '1' },
          appliedVersion: '0',
          status: 'NOT_CONNECTED',
          applicationError: 'saving',
        }
      : {}
  await assert.rejects(initializeOpencliPlugin({ jsonCall, ...noSleep }), /opencli plugin configuration did not converge/)
})
