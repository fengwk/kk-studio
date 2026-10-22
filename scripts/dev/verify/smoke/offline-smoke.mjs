#!/usr/bin/env node
/**
 * Container Canvas and offline Chat smoke against a running application.
 *
 * Driven by `offline-chat.sh --with-app`; reads `CANVAS_TEST_APP_URL` and `CANVAS_TEST_IMAGE_FIXTURE`.
 * The reusable steps take an injectable client (`jsonCall`, `newId`, `sleep`) so their wire payloads,
 * revision progression and replay idempotency are exercised for real by the contract tests.
 */

import assert from 'node:assert/strict'
import { createHash, randomUUID } from 'node:crypto'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { pathToFileURL } from 'node:url'

import {
  assertCompletedRun,
  assertFunctionDefinitions,
  assertPatch,
  assertRunRevision,
  definitionVersion,
  documentRevision,
  functionBatch,
  resourceBatch,
} from './offline-canvas-contract.mjs'
import { buildOfflineChatBatchRequest } from './offline-chat-payload.mjs'

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

const sleep = (millis) => new Promise((resolve) => setTimeout(resolve, millis))

/** HTTP client for the application API: resolves to the `data` member of a 2xx JSON body. */
export function createClient(baseUrl) {
  return {
    newId: () => randomUUID(),
    sleep,
    async jsonCall(method, requestPath, body = null) {
      const response = await fetch(baseUrl + requestPath, {
        method,
        headers: body === null ? {} : { 'Content-Type': 'application/json' },
        body: body === null ? undefined : JSON.stringify(body),
        signal: AbortSignal.timeout(30_000),
      })
      assert.ok(response.ok, `${method} ${requestPath} -> HTTP ${response.status}`)
      return (await response.json()).data
    },
  }
}

function header(headers, name) {
  const key = Object.keys(headers).find((candidate) => candidate.toLowerCase() === name.toLowerCase())
  assert.ok(key !== undefined, `missing header ${name}`)
  return headers[key]
}

function upsertedNode(patch, nodeId) {
  const item = patch.nodes.find((candidate) => candidate.op === 'UPSERT' && candidate.node.id === nodeId)
  assert.ok(item, `no upserted node ${nodeId}`)
  return item.node
}

/** Fetch a presigned or signed URL with the given init; resolves to the raw response. */
const rawFetch = (url, init = {}) => fetch(url, { signal: AbortSignal.timeout(30_000), ...init })

/**
 * Create a Function node, run it to completion and verify revision progression and replay idempotency.
 * Resolves to the node as published in the final snapshot.
 */
export async function createAndRun(client, canvasId, functionName, name, parameters, expectedCheckpointCount) {
  const { jsonCall, newId, sleep: wait } = client
  const version = definitionVersion(await jsonCall('GET', '/api/canvas-functions'), functionName)
  const current = await jsonCall('GET', `/api/canvases/${canvasId}`)
  const currentRevision = documentRevision(current.document)
  const nodeId = newId()
  const patch = await jsonCall(
    'POST',
    `/api/canvases/${canvasId}/commands`,
    functionBatch(nodeId, name, functionName, { prompt: `mock ${functionName}`, ...parameters }, newId()),
  )
  const patchRevision = assertPatch(patch, currentRevision)
  const node = upsertedNode(patch, nodeId)
  const requestId = newId()
  const runPath = `/api/canvases/${canvasId}/nodes/${node.id}/function-run`
  let run = await jsonCall('POST', runPath, { requestId, expectedVersion: version })
  for (let attempt = 0; attempt < 160; attempt += 1) {
    if (!['READY', 'RUNNING'].includes(run.status)) {
      break
    }
    await wait(100)
    run = await jsonCall('GET', runPath)
  }
  assertCompletedRun(run, nodeId, requestId)
  const snapshot = await jsonCall('GET', `/api/canvases/${canvasId}`)
  assertRunRevision(snapshot.document, patchRevision, expectedCheckpointCount)
  // 收尾后相同 requestId 重放不能启动新运行或改写资源；重放优先于版本校验。
  const replay = await jsonCall('POST', `/api/canvases/${canvasId}/nodes/${nodeId}/function-run`, {
    requestId,
    expectedVersion: version,
  })
  assert.deepEqual(replay, run)
  assert.deepEqual(await jsonCall('GET', `/api/canvases/${canvasId}`), snapshot)
  return snapshot.nodes.find((item) => item.id === node.id)
}

/**
 * Offline seed/smoke: initialize the opencli plugin through the unified plugin configuration and
 * secret API. Secrets never enter seed or schema. expectedEpoch/expectedRevision come from the GET.
 */
export async function initializeOpencliPlugin(client) {
  const { jsonCall, sleep: wait } = client
  const descriptor = await jsonCall('GET', '/api/plugins/opencli')
  await jsonCall('PUT', '/api/plugins/opencli/configuration', {
    expectedEpoch: descriptor.desiredEpoch,
    expectedRevision: descriptor.desiredRevision,
    valuesJson: JSON.stringify({
      enabled: true,
      baseUrl: 'http://opencli-hub:8080',
      gptImage2: { paidEnabled: true },
      seedance: {
        enabled: true,
        workspaceId: 'mock-workspace',
        statusPollIntervalMillis: 10,
        maxWaitMillis: 30000,
      },
    }),
    secretPatch: { apiKey: { operation: 'SET', value: 'synthetic-opencli-key' } },
  })
  let current
  for (let attempt = 0; attempt < 80; attempt += 1) {
    current = await jsonCall('GET', '/api/plugins/opencli')
    if (
      current.status === 'CONNECTED' &&
      current.appliedEpoch === current.desiredEpoch &&
      current.appliedRevision === current.desiredRevision &&
      (current.applicationError ?? null) === null
    ) {
      return
    }
    await wait(100)
  }
  assert.fail(`opencli plugin configuration did not converge to applied CONNECTED state: ${JSON.stringify(current)}`)
}

/** Upload a Blob through a presigned PUT and verify the create-only guarantees. */
async function uploadBlob(client, image) {
  const sha256 = createHash('sha256').update(image).digest('hex')
  const reservation = await client.jsonCall('POST', '/api/storage/uploads', {
    filename: 'tiny.png',
    mediaType: 'image/png',
    sizeBytes: image.length,
    sha256,
  })
  assert.ok(!('bucket' in reservation) && !('key' in reservation))
  assert.equal(reservation.state, 'PENDING')
  assert.equal(reservation.blobId, null)
  assert.match(reservation.id, UUID_V4)
  const put = reservation.presignedPut
  assert.equal(header(put.headers, 'if-none-match'), '*')
  assert.equal(header(put.headers, 'x-amz-checksum-sha256'), Buffer.from(sha256, 'hex').toString('base64'))
  const upload = (body) => rawFetch(put.url, { method: put.method, headers: put.headers ?? {}, body })
  assert.equal((await upload(image)).status, 200)

  // 同内容重放验证 create-only；不同内容重放同时受 create-only 与 checksum 保护。
  const duplicate = await upload(image)
  assert.ok([409, 412].includes(duplicate.status), `create-only presigned PUT unexpectedly accepted a duplicate: ${duplicate.status}`)
  const replacement = Buffer.concat([Buffer.from([image[0] ^ 0xff]), image.subarray(1)])
  const overwrite = await upload(replacement)
  assert.ok(
    [400, 409, 412].includes(overwrite.status),
    `create-only presigned PUT unexpectedly overwrote original: ${overwrite.status}`,
  )

  const completed = await client.jsonCall('POST', `/api/storage/uploads/${reservation.id}/complete`)
  assert.equal(completed.id, reservation.id)
  assert.equal(completed.state, 'READY')
  assert.equal(completed.presignedPut, null)
  assert.match(completed.blobId, UUID_V4)
  return completed
}

async function assertWebpPreview(url) {
  const response = await rawFetch(url)
  const body = Buffer.from(await response.arrayBuffer())
  assert.equal(response.status, 200)
  assert.ok(body.subarray(0, 4).toString('latin1') === 'RIFF' && body.subarray(0, 16).includes('WEBP'))
}

/** The stack starts with an empty catalog, so the smoke creates a stub provider/model/agent. */
async function createStubCatalog({ jsonCall }) {
  const stub = await jsonCall('POST', '/api/providers', {
    name: 'stub',
    description: 'offline smoke stub',
    providerType: 'openai',
    baseUrl: 'http://stub.local:8080/v1',
    credential: 'stub-key',
    modelCallTimeoutMillis: 1_800_000,
    modelCallIdleTimeoutMillis: 120_000,
  })
  assert.equal(stub.configured, true, JSON.stringify(stub))
  // 缺省 pricing 由服务端归一为完整零价格定义。
  await jsonCall('POST', '/api/models', {
    kind: 'MODEL',
    providerName: 'stub',
    name: 'acceptance-stub',
    modelId: 'acceptance-stub',
    description: 'offline smoke stub model',
    config: {
      limit: { context: 128000, output: 4096 },
      abilities: { tools: true, reasoning: false, inputModalities: ['TEXT'] },
      defaultVariant: 'default',
      variants: [{ id: 'default' }],
    },
  })
  await jsonCall('POST', '/api/agents', {
    name: 'default-assistant',
    description: 'offline smoke stub agent',
    systemPrompt: 'You are the offline smoke assistant.',
    model: 'acceptance-stub',
    variant: 'default',
    config: { tools: [], skills: [], subagents: [] },
  })
}

const entryPayload = (entry) => JSON.parse(entry.payloadJson)
const isMessage = (entry, role) =>
  entry.entryType.toUpperCase() === 'MESSAGE' && entryPayload(entry).message.role === role

/** Offline Chat smoke: one NEW_SESSION batch, then wait for the thread to become quiescent. */
async function offlineChat(client) {
  const { jsonCall, newId, sleep: wait } = client
  await createStubCatalog(client)
  const chat = await jsonCall('POST', '/api/chats', {
    title: 'offline-chat-smoke',
    agentName: 'default-assistant',
    yoloEnabled: false,
  })
  assert.equal(chat.agentName, 'default-assistant', JSON.stringify(chat))
  const marker = `offline-chat-smoke-${randomUUID().replaceAll('-', '').slice(0, 8)}`
  // 唯一产品写入口：NEW_SESSION 一次原子创建 Session + ROOT + Thread 并接受首条 USER_MESSAGE。
  const sessionId = randomUUID()
  const threadId = randomUUID()
  const accepted = await jsonCall(
    'POST',
    '/api/command-batches',
    buildOfflineChatBatchRequest({
      chatId: chat.id,
      sessionId,
      threadId,
      commandId: newId(),
      marker,
    }),
  )
  assert.equal(accepted.replayed, false, JSON.stringify(accepted))
  assert.equal(accepted.acceptedCommands[0].type, 'USER_MESSAGE', JSON.stringify(accepted))
  // 不锁定 accepted 快照的瞬时 status（processor 可能已异步消费）；只锁定 threadId 归属，
  // 随后轮询等待 quiescent 收敛。
  assert.equal(accepted.thread.threadId, threadId, JSON.stringify(accepted.thread))
  let current
  let quiescent = false
  for (let attempt = 0; attempt < 240 && !quiescent; attempt += 1) {
    current = await jsonCall('GET', `/api/threads/${threadId}`)
    const candidate = current.thread
    quiescent =
      candidate.status === 'IDLE' &&
      candidate.processing === false &&
      current.queuedCommands.length === 0 &&
      current.modelInvocation === null &&
      current.toolInvocations.length === 0
    if (!quiescent) {
      await wait(250)
    }
  }
  assert.ok(quiescent, `offline Chat thread did not become quiescent: ${JSON.stringify(current)}`)
  assert.equal(current.thread.status, 'IDLE', JSON.stringify(current))
  assert.ok(
    !current.entries.some((entry) => entry.entryType.toUpperCase() === 'ASSISTANT_ERROR'),
    JSON.stringify(current.entries),
  )
  const userEntries = current.entries.filter((entry) => isMessage(entry, 'USER'))
  assert.ok(userEntries.length > 0, `no durable USER entry: ${JSON.stringify(current.entries)}`)
  assert.ok(JSON.stringify(userEntries.at(-1)).includes(marker), JSON.stringify(current.entries))
  const assistantEntries = current.entries.filter((entry) => isMessage(entry, 'ASSISTANT'))
  assert.ok(assistantEntries.length > 0, `no durable assistant reply: ${JSON.stringify(current.entries)}`)
  const assistantText = entryPayload(assistantEntries.at(-1))
    .message.contents.filter((content) => content.type === 'text')
    .map((content) => content.text ?? '')
    .join('')
  assert.ok(assistantText.includes('offline acceptance stub'), assistantText)
  const turnEnd = current.entries.find((entry) => entry.entryType.toUpperCase() === 'TURN_END')
  assert.ok(turnEnd, 'no TURN_END entry')
  assert.equal(entryPayload(turnEnd).outcome, 'COMPLETED', JSON.stringify(turnEnd))
}

export async function runSmoke({ baseUrl, imageFixture }) {
  const client = createClient(baseUrl)
  const { jsonCall, newId, sleep: wait } = client
  const image = readFileSync(imageFixture)

  const canvas = await jsonCall('POST', '/api/canvases', { title: 'container-resource-smoke' })
  assert.match(canvas.id, UUID_V4)
  assert.equal(documentRevision(canvas), 0)
  const completed = await uploadBlob(client, image)

  const resourceNodeId = newId()
  const resourceRequest = resourceBatch(resourceNodeId, completed.blobId, newId())
  const resourcePatch = await jsonCall('POST', `/api/canvases/${canvas.id}/commands`, resourceRequest)
  assertPatch(resourcePatch, 0)
  // 精确 replay 是空变化集，不再次创建资源或推进 revision。
  assertPatch(await jsonCall('POST', `/api/canvases/${canvas.id}/commands`, resourceRequest), 1, true)
  const resource = upsertedNode(resourcePatch, resourceNodeId).resources[0]
  assert.equal(resource.blobId, completed.blobId)
  assert.equal(resource.mediaType, 'image/png')
  assert.ok(resource.width > 0 && resource.height > 0)
  assert.equal(resource.sizeBytes, String(image.length))

  const resourceSnapshot = await jsonCall('GET', `/api/canvases/${canvas.id}`)
  assert.equal(documentRevision(resourceSnapshot.document), 1)
  assert.equal(resourceSnapshot.nodes.length, 1)
  const snapshotResourceNode = resourceSnapshot.nodes.find((node) => node.id === resourceNodeId)
  assert.equal(snapshotResourceNode.resources[0].id, resource.id)

  const original = await jsonCall('POST', `/api/canvases/${canvas.id}/resources/${resource.id}/download-url`)
  const originalResponse = await rawFetch(original.url)
  assert.equal(originalResponse.status, 200)
  assert.ok(Buffer.from(await originalResponse.arrayBuffer()).equals(image))

  const preview = await jsonCall('POST', `/api/canvases/${canvas.id}/resources/${resource.id}/preview-url`)
  assert.ok(!('bucket' in preview) && !('key' in preview))
  await assertWebpPreview(preview.url)

  const functionNodeId = newId()
  const functionPatch = await jsonCall(
    'POST',
    `/api/canvases/${canvas.id}/commands`,
    functionBatch(
      functionNodeId,
      'generated',
      'fake-image',
      { prompt: 'free deterministic image', ratio: '16:9' },
      newId(),
    ),
  )
  assertPatch(functionPatch, 1)
  const functionNode = upsertedNode(functionPatch, functionNodeId)
  const requestId = newId()
  // start 的 expectedVersion 只能来自真实目录项，绝不硬编码 epoch 或 fingerprint。
  const fakeImageVersion = definitionVersion(await jsonCall('GET', '/api/canvas-functions'), 'fake-image')
  const runPath = `/api/canvases/${canvas.id}/nodes/${functionNode.id}/function-run`
  const started = await jsonCall('POST', runPath, { requestId, expectedVersion: fakeImageVersion })
  assert.equal(started.requestId, requestId)
  let current
  let terminal = false
  for (let attempt = 0; attempt < 120 && !terminal; attempt += 1) {
    current = await jsonCall('GET', runPath)
    terminal = !['READY', 'RUNNING'].includes(current.status)
    if (!terminal) {
      await wait(250)
    }
  }
  assert.ok(terminal, 'fake-image FunctionRun did not reach terminal state')
  assertCompletedRun(current, functionNodeId, requestId)

  const generatedSnapshot = await jsonCall('GET', `/api/canvases/${canvas.id}`)
  const generatedNode = generatedSnapshot.nodes.find((node) => node.id === functionNode.id)
  assert.equal(assertRunRevision(generatedSnapshot.document, 2, 2), 8)
  assert.equal(generatedNode.resources.length, 1)
  const generatedResource = generatedNode.resources[0]
  assert.equal(generatedResource.kind, 'IMAGE')
  assert.equal(generatedResource.name, 'generated.png')
  const generatedPreview = await jsonCall(
    'POST',
    `/api/canvases/${canvas.id}/resources/${generatedResource.id}/preview-url`,
  )
  await assertWebpPreview(generatedPreview.url)

  await initializeOpencliPlugin(client)
  assertFunctionDefinitions(await jsonCall('GET', '/api/canvas-functions'))

  const gptNode = await createAndRun(client, canvas.id, 'gpt-image-2', 'mock-gpt', { ratio: '1:1' }, 4)
  assert.equal(gptNode.resources.length, 1)
  assert.equal(gptNode.resources[0].kind, 'IMAGE')

  // 默认 routes 为空；fake Hub 的首次 status 即 ready，无 generating 重试。
  const seedanceNode = await createAndRun(
    client,
    canvas.id,
    'seedance2.0fast',
    'mock-seedance',
    { ratio: '16:9', duration: 4 },
    6,
  )
  assert.equal(seedanceNode.resources.length, 1)
  assert.equal(seedanceNode.resources[0].kind, 'VIDEO')

  await offlineChat(client)
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  try {
    await runSmoke({
      baseUrl: process.env.CANVAS_TEST_APP_URL,
      imageFixture: process.env.CANVAS_TEST_IMAGE_FIXTURE,
    })
  } catch (error) {
    process.stderr.write(`${error.stack ?? error}\n`)
    process.exitCode = 1
  }
}
