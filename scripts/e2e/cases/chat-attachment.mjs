import { createHash } from 'node:crypto'

import {
  assert,
  cid,
  envelopeData,
  expectHttpError,
} from '../lib/http.mjs'
import {
  branchSettingsOf,
  createChat,
  createChatThread,
  enqueueCommands,
  getThreadSnapshot,
} from '../lib/harness.mjs'
import { getCase, registerCase } from '../lib/registry.mjs'

/**
 * USER_MESSAGE ATTACHMENT 消费与 requestHash 契约（L1，需 backend 启用 S3）。
 *
 * <p>通用存储端点 reserve -> 真实 presigned PUT -> complete -> READY uploadId 经命令 batch 的
 * ATTACHMENT(uploadId) 消费：入队响应携带 canonical requestHash、durable payload 为
 * resource(blobId/name)；同 batch 整批重放返回既有命令且不二次消费；upload 行消费后再次提交
 * 同一 uploadId 确定性 400；IMAGE/AUDIO/VIDEO 内容类型与 PENDING upload 均 400。
 */
registerCase({
  id: 'chat.attachment_upload_contract',
  level: 'L1',
  requires: ['canvas-storage'],
  title: 'USER_MESSAGE ATTACHMENT 消费与 requestHash 契约',
  docs: '需 backend 启用 S3：reserve -> presigned PUT -> complete -> ATTACHMENT(uploadId) 原子消费；响应 requestHash 为 64 位小写 hex，durable payload 为 resource(blobId/name)；整批重放幂等不二次消费；已消费/未 READY upload 与 IMAGE/AUDIO/VIDEO 内容类型确定性 400',
  async run(ctx) {
    if (!ctx.vars.agent) await getCase('seed.agent_and_provider').run(ctx)
    if (!ctx.vars.seedModel) await getCase('seed.structured_model_config').run(ctx)
    const chat = await createChat(ctx, {
      title: `e2e-attachment-${cid().slice(0, 8)}`,
      agentName: ctx.vars.agent.name,
      yoloEnabled: false,
    })
    const snapshot = await createChatThread(ctx, chat.id, {
      title: null,
      yoloEnabled: false,
      branchSettings: branchSettingsOf(ctx.vars.agent, {
        providerName: 'minimax',
        modelName: 'MiniMax-M2.7',
        variant: 'default',
      }, { activeTools: [] }),
    })
    const thread = snapshot.thread

    // 1. reserve：PENDING + PUT + 不暴露 bucket/key。
    const content = Buffer.from('e2e chat attachment payload', 'utf8')
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
      headers: { ...pending.presignedPut.headers, 'Content-Type': 'text/plain' },
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

    // 4. ATTACHMENT 命令原子消费：durable resource + requestHash。
    const clientCommandId = cid()
    const batch = {
      expectedHeadEntryId: String(thread.headEntryId),
      expectedNextCommandSequence: String(thread.nextCommandSequence),
      commands: [
        { type: 'USER_MESSAGE', clientCommandId, contents: [{ type: 'ATTACHMENT', uploadId }] },
      ],
    }
    const enqueued = await enqueueCommands(ctx, String(thread.threadId), batch)
    const command = enqueued[0]
    assert(command.type === 'USER_MESSAGE', JSON.stringify(command))
    assert(/^[0-9a-f]{64}$/.test(String(command.requestHash)), JSON.stringify(command))
    assert(String(command.clientCommandId) === clientCommandId, JSON.stringify(command))
    const payload = JSON.parse(command.payloadJson)
    assert(payload.message.contents[0].type === 'resource', JSON.stringify(payload))
    assert(String(payload.message.contents[0].blobId) === blobId, JSON.stringify(payload))
    assert(payload.message.contents[0].name === 'e2e-attachment.txt', JSON.stringify(payload))

    // 5. 整批重放：返回既有命令（requestHash 一致），不二次消费。
    const replayed = await enqueueCommands(ctx, String(thread.threadId), batch)
    assert(String(replayed[0].requestHash) === String(command.requestHash), JSON.stringify(replayed))
    const snapshotAfterReplay = await getThreadSnapshot(ctx, String(thread.threadId))
    assert(snapshotAfterReplay.queuedCommands.length === 1, JSON.stringify(snapshotAfterReplay.queuedCommands))

    // 6. upload 行已消费：同一 uploadId 的 NEW 命令确定性 400。
    await expectHttpError(
      () =>
        enqueueCommands(ctx, String(thread.threadId), {
          expectedHeadEntryId: String(snapshotAfterReplay.thread.headEntryId),
          expectedNextCommandSequence: String(snapshotAfterReplay.thread.nextCommandSequence),
          commands: [
            { type: 'USER_MESSAGE', clientCommandId: cid(), contents: [{ type: 'ATTACHMENT', uploadId }] },
          ],
        }),
      { status: 400 },
    )

    // 7. IMAGE/AUDIO/VIDEO 结构化内容类型全部 400。
    for (const media of ['IMAGE', 'AUDIO', 'VIDEO']) {
      await expectHttpError(
        () =>
          enqueueCommands(ctx, String(thread.threadId), {
            expectedHeadEntryId: String(snapshotAfterReplay.thread.headEntryId),
            expectedNextCommandSequence: String(snapshotAfterReplay.thread.nextCommandSequence),
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

    // 8. PENDING（未 complete）upload 同样确定性 400。
    const { json: pendingReserveJson } = await ctx.call('POST', '/api/storage/uploads', {
      filename: 'pending.bin',
      mediaType: 'application/octet-stream',
      sizeBytes: 1,
      sha256: createHash('sha256').update(Buffer.from([1])).digest('hex'),
    })
    const pendingUploadId = String(envelopeData(pendingReserveJson).id)
    await expectHttpError(
      () =>
        enqueueCommands(ctx, String(thread.threadId), {
          expectedHeadEntryId: String(snapshotAfterReplay.thread.headEntryId),
          expectedNextCommandSequence: String(snapshotAfterReplay.thread.nextCommandSequence),
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
