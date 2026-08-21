import { createHash } from 'node:crypto'
import { createServer } from 'node:http'

import {
  assert,
  cid,
  envelopeData,
  expectHttpError,
} from '../lib/http.mjs'
import {
  acceptCommandBatch,
  branchSettingsOf,
  chatOwner,
  createChat,
  getThreadSnapshot,
  materializeNewSession,
  stopThread,
  threadTarget,
  waitForQuiescentThread,
} from '../lib/harness.mjs'
import { baseModelConfig } from '../lib/fixtures.mjs'
import { getCase, registerCase } from '../lib/registry.mjs'

/**
 * USER_MESSAGE ATTACHMENT 消费与 requestHash 契约（L1，需 backend 启用 S3）。
 *
 * <p>通用存储端点 reserve -> 真实 presigned PUT -> complete -> READY uploadId 经命令 batch 的
 * ATTACHMENT(uploadId) 消费：入队响应携带 canonical requestHash、durable payload 为
 * resource(blobId,name,preview)；同 batch 整批重放返回既有命令且不二次消费；同 Session 可用 RESOURCE
 * 重新提交该 blob 且不要求 upload handle，跨/新 Session RESOURCE 确定性拒绝；upload 行消费后再次提交同一
 * uploadId 确定性 400；IMAGE/AUDIO/VIDEO 内容类型与 PENDING upload 均 400。
 */
registerCase({
  id: 'chat.attachment_upload_contract',
  level: 'L1',
  requires: ['canvas-storage'],
  title: 'USER_MESSAGE ATTACHMENT 消费与 requestHash 契约',
  docs: '需 backend 启用 S3：reserve -> presigned PUT -> complete -> ATTACHMENT(uploadId) 作为 NEW_SESSION 首条消息原子物化；响应 requestHash 为 64 位小写 hex，durable payload 为 resource(blobId,name,preview)；同批精确重放 replayed=true 不二次消费；同 Session RESOURCE 可重提且跨/新 Session 拒绝；已消费/未 READY upload 与 IMAGE/AUDIO/VIDEO 内容类型确定性 400',
  async run(ctx) {
    if (!ctx.vars.agent) await getCase('seed.agent_and_provider').run(ctx)
    if (!ctx.vars.seedModel) await getCase('seed.structured_model_config').run(ctx)
    const chat = await createChat(ctx, {
      title: `e2e-attachment-${cid().slice(0, 8)}`,
      agentName: ctx.vars.agent.name,
      yoloEnabled: false,
    })
    // 使用不存在的 Agent，让附件消息确定性物化后在 Provider 调用前 PLANNING_FAILED。
    const rootSettings = branchSettingsOf(
      { name: `e2e-attachment-missing-${cid().slice(0, 8)}` },
      {
        providerName: 'minimax',
        modelName: 'MiniMax-M2.7',
        variant: 'default',
      },
      { activeTools: [] },
    )

    // 1. reserve：PENDING + PUT + 不暴露 bucket/key。
    const content = Buffer.from(`e2e chat attachment payload ${cid()}`, 'utf8')
    const sha256 = createHash('sha256').update(content).digest('hex')
    const { json: reserveJson } = await ctx.call('POST', '/api/storage/uploads', {
      filename: 'e2e-attachment.txt',
      mediaType: 'text/plain',
      sizeBytes: content.length,
      sha256,
    })
    const pending = envelopeData(reserveJson)
    assert(pending.state === 'PENDING', JSON.stringify(pending))
    assert(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(String(pending.id)), JSON.stringify(pending))
    assert(pending.presignedPut?.method === 'PUT', JSON.stringify(pending))
    assert(/^https?:\/\//.test(pending.presignedPut.url), JSON.stringify(pending))
    const ifNoneMatch = Object.entries(pending.presignedPut.headers || {}).find(
      ([name]) => name.toLowerCase() === 'if-none-match',
    )
    assert(ifNoneMatch?.[1] === '*', JSON.stringify(pending))
    const reserveText = JSON.stringify(reserveJson)
    assert(!reserveText.includes('"bucket"') && !reserveText.includes('"key"'), reserveText)

    // 2. 真实 presigned PUT（MinIO path-style public endpoint）。
    const putRes = await fetch(pending.presignedPut.url, {
      method: 'PUT',
      headers: pending.presignedPut.headers || {},
      body: content,
    })
    assert(
      putRes.status === 200 || putRes.status === 201,
      `presigned PUT failed: ${putRes.status} ${await putRes.text()}`,
    )

    // 3. complete：READY + blobId。
    const uploadId = String(pending.id)
    const { json: completeJson } = await ctx.call(
      'POST',
      `/api/storage/uploads/${encodeURIComponent(uploadId)}/complete`,
    )
    const ready = envelopeData(completeJson)
    assert(ready.state === 'READY', JSON.stringify(ready))
    const blobId = String(ready.blobId)
    assert(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(blobId), JSON.stringify(ready))

    // 4. ATTACHMENT 作为 NEW_SESSION 首条消息原子物化：durable resource + requestHash。
    const sessionId = cid()
    const threadId = cid()
    const clientCommandId = cid()
    const attachmentCommand = {
      type: 'USER_MESSAGE',
      clientCommandId,
      contents: [{ type: 'ATTACHMENT', uploadId }],
    }
    const accepted = await materializeNewSession(ctx, {
      owner: chatOwner(chat.id),
      sessionId,
      threadId,
      rootSettings,
      yoloEnabled: false,
      commands: [attachmentCommand],
    })
    const command = accepted.acceptedCommands[0]
    assert(command.type === 'USER_MESSAGE', JSON.stringify(command))
    assert(/^[0-9a-f]{64}$/.test(String(command.requestHash)), JSON.stringify(command))
    assert(String(command.clientCommandId) === clientCommandId, JSON.stringify(command))
    const payload = JSON.parse(command.payloadJson)
    assert(payload.message.contents[0].type === 'resource', JSON.stringify(payload))
    assert(String(payload.message.contents[0].blobId) === blobId, JSON.stringify(payload))
    assert(payload.message.contents[0].name === 'e2e-attachment.txt', JSON.stringify(payload))

    // 5. 整批精确重放：replayed=true，返回既有命令（requestHash 一致），不二次消费。
    const replayed = await materializeNewSession(ctx, {
      owner: chatOwner(chat.id),
      sessionId,
      threadId,
      rootSettings,
      yoloEnabled: false,
      commands: [attachmentCommand],
    })
    assert(replayed.replayed === true, JSON.stringify(replayed))
    assert(String(replayed.acceptedCommands[0].requestHash) === String(command.requestHash), JSON.stringify(replayed))
    await waitForQuiescentThread(ctx, String(threadId), {
      timeoutMs: 60_000,
      intervalMs: 100,
    })
    const snapshotAfterReplay = await getThreadSnapshot(ctx, String(threadId))
    assert(snapshotAfterReplay.queuedCommands.length === 0, JSON.stringify(snapshotAfterReplay.queuedCommands))

    // 6. Stop/草稿恢复使用的 RESOURCE 可在同 Session 重提，不需要已消费的 upload handle。
    const resourceCommand = {
      type: 'USER_MESSAGE',
      clientCommandId: cid(),
      contents: [{
        type: 'RESOURCE',
        blobId,
        name: 'e2e-attachment.txt',
        preview: 'restored resource',
      }],
    }
    const resourceAccepted = await acceptCommandBatch(ctx, {
      owner: chatOwner(chat.id),
      target: threadTarget({
        threadId,
        expectedHeadEntryId: snapshotAfterReplay.thread.headEntryId,
        expectedNextCommandSequence: snapshotAfterReplay.thread.nextCommandSequence,
      }),
      commands: [resourceCommand],
    })
    const resourcePayload = JSON.parse(resourceAccepted.acceptedCommands[0].payloadJson)
    assert(resourcePayload.message.contents[0].type === 'resource', JSON.stringify(resourcePayload))
    assert(String(resourcePayload.message.contents[0].blobId) === blobId, JSON.stringify(resourcePayload))
    assert(resourcePayload.message.contents[0].name === 'e2e-attachment.txt', JSON.stringify(resourcePayload))
    assert(resourcePayload.message.contents[0].preview === 'restored resource', JSON.stringify(resourcePayload))
    await waitForQuiescentThread(ctx, String(threadId), {
      timeoutMs: 60_000,
      intervalMs: 100,
    })
    const snapshotAfterResource = await getThreadSnapshot(ctx, String(threadId))

    // 7. 相同 blob 不能借 RESOURCE wire 注入没有该 Session ref 的 NEW_SESSION。
    const rejectedSessionId = cid()
    const rejectedThreadId = cid()
    await expectHttpError(
      () =>
        materializeNewSession(ctx, {
          owner: chatOwner(chat.id),
          sessionId: rejectedSessionId,
          threadId: rejectedThreadId,
          rootSettings,
          yoloEnabled: false,
          commands: [{ ...resourceCommand, clientCommandId: cid() }],
        }),
      { status: 400 },
    )
    await expectHttpError(
      () => ctx.call('GET', `/api/ai/runtime/threads/${rejectedThreadId}/snapshot`),
      { status: 404 },
    )

    // 8. upload 行已消费：同一 uploadId 的 NEW 命令确定性 400。
    await expectHttpError(
      () =>
        acceptCommandBatch(ctx, {
          owner: chatOwner(chat.id),
          target: threadTarget({
            threadId,
            expectedHeadEntryId: snapshotAfterResource.thread.headEntryId,
            expectedNextCommandSequence: snapshotAfterResource.thread.nextCommandSequence,
          }),
          commands: [
            { type: 'USER_MESSAGE', clientCommandId: cid(), contents: [{ type: 'ATTACHMENT', uploadId }] },
          ],
        }),
      { status: 400 },
    )

    // 9. IMAGE/AUDIO/VIDEO 结构化内容类型全部 400。
    for (const media of ['IMAGE', 'AUDIO', 'VIDEO']) {
      await expectHttpError(
        () =>
          acceptCommandBatch(ctx, {
            owner: chatOwner(chat.id),
            target: threadTarget({
              threadId,
              expectedHeadEntryId: snapshotAfterResource.thread.headEntryId,
              expectedNextCommandSequence: snapshotAfterResource.thread.nextCommandSequence,
            }),
            commands: [
              {
                type: 'USER_MESSAGE',
                clientCommandId: cid(),
                contents: [{ type: media, mediaType: 'image/png', source: 'https://cdn.example.com/x.png' }],
              },
            ],
          }),
        { status: 400 },
      )
    }

    // 10. PENDING（未 complete）upload 同样确定性 400。
    const { json: pendingReserveJson } = await ctx.call('POST', '/api/storage/uploads', {
      filename: 'pending.bin',
      mediaType: 'application/octet-stream',
      sizeBytes: 1,
      sha256: createHash('sha256').update(Buffer.from([1])).digest('hex'),
    })
    const pendingUploadId = String(envelopeData(pendingReserveJson).id)
    await expectHttpError(
      () =>
        acceptCommandBatch(ctx, {
          owner: chatOwner(chat.id),
          target: threadTarget({
            threadId,
            expectedHeadEntryId: snapshotAfterResource.thread.headEntryId,
            expectedNextCommandSequence: snapshotAfterResource.thread.nextCommandSequence,
          }),
          commands: [
            {
              type: 'USER_MESSAGE',
              clientCommandId: cid(),
              contents: [{ type: 'ATTACHMENT', uploadId: pendingUploadId }],
            },
          ],
        }),
      { status: 400 },
    )
  },
})

registerCase({
  id: 'chat.attachment_inline_image_latest_agent_tools',
  level: 'L1',
  requires: ['canvas-storage'],
  title: '本地 Blob 图片内联且现有 Thread 使用最新 Agent 工具',
  docs: '本地 OpenAI Responses mock：Thread 创建后更新同名 Agent 工具，下一 turn 必须忽略历史 branch activeTools 并发送最新工具；本地 MinIO 图片必须转换为 data:image/...;base64 source，Provider 请求不得包含 127.0.0.1/localhost 预签名 URL',
  async run(ctx) {
    const suffix = cid().slice(0, 8)
    const mock = await startResponsesProbe()
    let provider = null
    let model = null
    let agent = null
    let chat = null
    let threadId = null
    let primaryError = null
    const cleanupErrors = []
    try {
      provider = envelopeData(
        (
          await ctx.call('POST', '/api/ai/catalog/providers', {
            name: `e2e-provider-image-${suffix}`,
            description: 'Local Responses probe for attachment materialization.',
            providerType: 'openai_response',
            baseUrl: mock.baseUrl,
            credential: `e2e-image-${suffix}`,
            modelCallTimeoutMillis: 30_000,
            modelCallIdleTimeoutMillis: 10_000,
          })
        ).json,
      )
      model = envelopeData(
        (
          await ctx.call('POST', '/api/ai/catalog/models', {
            providerName: provider.name,
            name: `e2e-model-image-${suffix}`,
            description: 'Local image-capable Responses model.',
            config: baseModelConfig({
              limit: { context: 4096, output: 128 },
              abilities: {
                tools: true,
                reasoning: false,
                inputModalities: ['TEXT', 'IMAGE'],
              },
              variants: [{ id: 'default', temperature: 0 }],
            }),
          })
        ).json,
      )
      agent = envelopeData(
        (
          await ctx.call('POST', '/api/ai/catalog/agents', {
            name: `e2e-agent-image-${suffix}`,
            description: 'Agent starts with one tool.',
            systemPrompt: 'Complete without calling tools.',
            model: `${model.providerName}/${model.name}`,
            variant: 'default',
            config: { tools: ['read'], skills: [], subagents: [] },
          })
        ).json,
      )
      chat = await createChat(ctx, {
        title: `e2e-inline-image-${suffix}`,
        agentName: agent.name,
        yoloEnabled: false,
      })
      const image = Buffer.from(
        'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=',
        'base64',
      )
      const uploadId = await uploadReadyFile(ctx, {
        filename: 'pixel.png',
        mediaType: 'image/png',
        content: image,
      })
      // NEW_SESSION 首条消息即携带 TEXT+ATTACHMENT 原子物化。
      const sessionId = cid()
      const createdThreadId = cid()
      const accepted = await materializeNewSession(ctx, {
        owner: chatOwner(chat.id),
        sessionId,
        threadId: createdThreadId,
        rootSettings: branchSettingsOf(
          agent,
          {
            providerName: model.providerName,
            modelName: model.name,
            variant: 'default',
          },
          { activeTools: ['read'] },
        ),
        yoloEnabled: false,
        commands: [
          {
            type: 'USER_MESSAGE',
            clientCommandId: cid(),
            contents: [
              { type: 'TEXT', text: `inspect inline image ${suffix}` },
              { type: 'ATTACHMENT', uploadId },
            ],
          },
        ],
      })
      threadId = String(createdThreadId)

      // Thread 已冻结旧 activeTools 后再更新 Agent；下一 turn 必须读取最新 Agent 配置。
      agent = envelopeData(
        (
          await ctx.call('PUT', `/api/ai/catalog/agents/${encodeURIComponent(agent.name)}`, {
            description: 'Agent now has the latest tools.',
            systemPrompt: 'Complete without calling tools.',
            variant: 'default',
            config: { tools: ['read', 'grep'], skills: [], subagents: [] },
            expectedVersion: agent.version,
          })
        ).json,
      )
      assert(accepted.acceptedCommands.length === 1, JSON.stringify(accepted.acceptedCommands))
      await waitForQuiescentThread(ctx, threadId, { timeoutMs: 45_000, intervalMs: 100 })

      assert(mock.requests.length === 1, JSON.stringify(mock.requests))
      const request = mock.requests[0]
      const bodyText = JSON.stringify(request.body)
      const strings = collectStrings(request.body)
      const imageSources = strings.filter((value) => value.startsWith('data:image/png;base64,'))
      assert(imageSources.length === 1, bodyText)
      assert(
        imageSources[0] === `data:image/png;base64,${image.toString('base64')}`,
        imageSources[0],
      )
      assert(!bodyText.includes('127.0.0.1') && !bodyText.includes('localhost'), bodyText)
      const toolNames = collectToolNames(request.body)
      assert(toolNames.includes('read'), JSON.stringify({ toolNames, tools: request.body.tools }))
      assert(
        toolNames.includes('grep'),
        `latest Agent tool missing from existing Thread request: ${JSON.stringify({
          toolNames,
          tools: request.body.tools,
        })}`,
      )

      const finalSnapshot = await getThreadSnapshot(ctx, threadId)
      assert(
        !(finalSnapshot.entries ?? []).some(
          (entry) => String(entry.entryType ?? '').toUpperCase() === 'ASSISTANT_ERROR',
        ),
        JSON.stringify(finalSnapshot.entries),
      )
      ctx.writeArtifact(
        'inline-image-latest-tools-request.json',
        JSON.stringify({ toolNames, imageSource: imageSources[0], request: request.body }, null, 2),
      )
    } catch (error) {
      primaryError = error
    } finally {
      await cleanup('stop active thread', cleanupErrors, async () => {
        if (!threadId) return
        const snapshot = await getThreadSnapshot(ctx, threadId)
        if (
          snapshot.thread.status !== 'IDLE'
          || snapshot.thread.processing
          || snapshot.queuedCommands.length > 0
          || snapshot.modelInvocation !== null
        ) {
          await stopThread(ctx, threadId, {
            stopRequestId: cid(),
            expectedRevision: snapshot.thread.revision,
          })
        }
      })
      await cleanup('mock server', cleanupErrors, () => mock.close())
      await cleanup('chat', cleanupErrors, async () => {
        if (!chat?.id) return
        await ctx.call(
          'DELETE',
          `/api/ai/chat/${encodeURIComponent(chat.id)}?expectedVersion=${encodeURIComponent(chat.version)}`,
        )
      })
      await cleanup('agent', cleanupErrors, async () => {
        if (!agent?.name) return
        await ctx.call(
          'DELETE',
          `/api/ai/catalog/agents/${encodeURIComponent(agent.name)}?expectedVersion=${encodeURIComponent(agent.version)}`,
        )
      })
      await cleanup('model', cleanupErrors, async () => {
        if (!model?.providerName || !model?.name) return
        await ctx.call(
          'DELETE',
          `/api/ai/catalog/models?providerName=${encodeURIComponent(model.providerName)}&modelName=${encodeURIComponent(model.name)}&expectedVersion=${encodeURIComponent(model.version)}`,
        )
      })
      await cleanup('provider', cleanupErrors, async () => {
        if (!provider?.name) return
        await ctx.call(
          'DELETE',
          `/api/ai/catalog/providers/${encodeURIComponent(provider.name)}?expectedVersion=${encodeURIComponent(provider.version)}`,
        )
      })
      if (cleanupErrors.length > 0 && primaryError == null) {
        primaryError = new Error(`E2E cleanup failed: ${cleanupErrors.join(' | ')}`)
      }
    }
    if (primaryError != null) throw primaryError
  },
})

async function uploadReadyFile(ctx, { filename, mediaType, content }) {
  const sha256 = createHash('sha256').update(content).digest('hex')
  const pending = envelopeData(
    (
      await ctx.call('POST', '/api/storage/uploads', {
        filename,
        mediaType,
        sizeBytes: content.length,
        sha256,
      })
    ).json,
  )
  const put = await fetch(pending.presignedPut.url, {
    method: 'PUT',
    headers: pending.presignedPut.headers || {},
    body: content,
  })
  assert(put.ok, `presigned PUT failed: ${put.status} ${await put.text()}`)
  const ready = envelopeData(
    (
      await ctx.call(
        'POST',
        `/api/storage/uploads/${encodeURIComponent(String(pending.id))}/complete`,
      )
    ).json,
  )
  assert(ready.state === 'READY', JSON.stringify(ready))
  return String(ready.id)
}

async function startResponsesProbe() {
  const requests = []
  const server = createServer(async (request, response) => {
    const chunks = []
    for await (const chunk of request) chunks.push(chunk)
    const rawBody = Buffer.concat(chunks).toString('utf8')
    const isResponsesPost = request.method === 'POST' && String(request.url ?? '').includes('/responses')
    if (isResponsesPost) {
      requests.push({
        method: request.method,
        url: request.url,
        body: rawBody ? JSON.parse(rawBody) : null,
      })
      response.writeHead(200, { 'content-type': 'text/event-stream' })
      response.end(
        'event: response.completed\n'
          + 'data: {"type":"response.completed","sequence_number":1,"response":{"id":"resp-inline-image","created_at":1.0,"model":"e2e","object":"response","output":[],"parallel_tool_calls":true,"status":"completed","tool_choice":"auto","usage":{"input_tokens":1,"output_tokens":0,"total_tokens":1}}}\n\n',
      )
      return
    }
    response.writeHead(request.method === 'POST' ? 404 : 405, { connection: 'close' })
    response.end()
  })
  await new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(0, '127.0.0.1', resolve)
  })
  const address = server.address()
  assert(address && typeof address === 'object', 'responses probe did not bind')
  return {
    requests,
    baseUrl: `http://127.0.0.1:${address.port}/v1`,
    close: () => new Promise((resolve, reject) => {
      server.close((error) => (error ? reject(error) : resolve()))
    }),
  }
}

function collectToolNames(value, result = []) {
  if (Array.isArray(value)) {
    for (const item of value) collectToolNames(item, result)
  } else if (value && typeof value === 'object') {
    const name = value.name ?? value.function?.name
    if (typeof name === 'string' && name.trim() && (value.type === 'function' || value.function || value.parameters)) {
      result.push(name)
    }
    for (const item of Object.values(value)) collectToolNames(item, result)
  }
  return result
}

function collectStrings(value, result = []) {
  if (typeof value === 'string') {
    result.push(value)
  } else if (Array.isArray(value)) {
    for (const item of value) collectStrings(item, result)
  } else if (value && typeof value === 'object') {
    for (const item of Object.values(value)) collectStrings(item, result)
  }
  return result
}

async function cleanup(label, errors, action) {
  try {
    await action()
  } catch (error) {
    errors.push(`${label}: ${error instanceof Error ? error.message : String(error)}`)
  }
}
