import { assert, assertDecimalVersion, cid, envelopeData, expectHttpError } from '../lib/http.mjs'
import { registerCase } from '../lib/registry.mjs'

const UUID_TEXT = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

registerCase({
  id: 'canvas.api_version_contract',
  level: 'L1',
  title: 'Canvas UUID/version/command patch/snapshot HTTP 契约',
  docs:
    '免费 L1：create/list/get/commands 的 canonical UUID id 与十进制字符串 long version，expectedVersion CAS 409，同 idempotencyKey 精确回放空 patch 与不同内容 409，标准 snapshot 收敛且旧 changes 端点不存在，Group 重命名与成员子集解绑，删除后 404',
  async run(ctx) {
    const { json: createJson } = await ctx.call('POST', '/api/canvases', {
      title: 'e2e-version-contract',
    })
    const canvas = envelopeData(createJson)
    assert(UUID_TEXT.test(canvas.id), JSON.stringify(canvas))
    assertDecimalVersion(canvas.version, 'canvas.version')
    assert(canvas.version === '0', JSON.stringify(canvas))
    assert(!('threadId' in canvas), `Canvas must not expose threadId: ${JSON.stringify(canvas)}`)
    assert(!('graphVersion' in canvas), JSON.stringify(canvas))

    const nodeId = cid()
    const remainingNodeId = cid()
    const firstCommandId = cid()
    const textCommand = {
      type: 'CREATE_TEXT_NODE',
      nodeId,
      name: 'note',
      markdown: 'hello',
      transform: { x: 1, y: 2, width: 100, height: 80 },
    }
    const remainingTextCommand = {
      type: 'CREATE_TEXT_NODE',
      nodeId: remainingNodeId,
      name: 'remaining',
      markdown: 'member',
      transform: { x: 120, y: 2, width: 100, height: 80 },
    }
    const { json: commandJson } = await ctx.call(
      'POST',
      `/api/canvases/${canvas.id}/commands`,
      {
        expectedVersion: '0',
        idempotencyKey: firstCommandId,
        commands: [textCommand, remainingTextCommand],
      },
    )
    const patch = envelopeData(commandJson)
    assertDecimalVersion(patch.baseVersion, 'patch.baseVersion')
    assertDecimalVersion(patch.version, 'patch.version')
    assert(patch.baseVersion === '0' && patch.version === '1', JSON.stringify(patch))
    const upsert = patch.nodes.find((item) => item.op === 'UPSERT' && item.node.id === nodeId)
    assert(upsert?.node.resources?.[0]?.kind === 'TEXT', JSON.stringify(patch.nodes))

    // 同 idempotencyKey 精确回放：返回当前版本的确定性空 patch，version 不再前进。
    const { json: replayJson } = await ctx.call(
      'POST',
      `/api/canvases/${canvas.id}/commands`,
      {
        expectedVersion: '0',
        idempotencyKey: firstCommandId,
        commands: [textCommand, remainingTextCommand],
      },
    )
    const replay = envelopeData(replayJson)
    assert(replay.baseVersion === '1' && replay.version === '1', JSON.stringify(replay))
    assert(
      replay.groups.length === 0 && replay.nodes.length === 0 && replay.links.length === 0,
      JSON.stringify(replay),
    )

    // 同 idempotencyKey 不同内容：IDEMPOTENCY_CONFLICT 409；stale expectedVersion：VERSION_CONFLICT 409。
    await expectHttpError(
      () =>
        ctx.call('POST', `/api/canvases/${canvas.id}/commands`, {
          expectedVersion: '1',
          idempotencyKey: firstCommandId,
          commands: [
            {
              type: 'RENAME_NODE',
              nodeId,
              name: 'renamed',
            },
          ],
        }),
      { status: 409 },
    )
    await expectHttpError(
      () =>
        ctx.call('POST', `/api/canvases/${canvas.id}/commands`, {
          expectedVersion: '0',
          idempotencyKey: cid(),
          commands: [{ type: 'DELETE_NODE', nodeId }],
        }),
      { status: 409 },
    )

    // RENAME_GROUP：标题更新只发 group UPSERT patch；空白 title 与未知 group 都是 400。
    const groupId = cid()
    await expectHttpError(
      () =>
        ctx.call('POST', `/api/canvases/${canvas.id}/commands`, {
          expectedVersion: '1',
          idempotencyKey: cid(),
          commands: [
            {
              type: 'CREATE_GROUP',
              groupId,
              title: 'empty',
              transform: { x: 0, y: 0, width: 300, height: 200 },
              memberNodeIds: [],
            },
          ],
        }),
      { status: 400 },
    )
    await ctx.call('POST', `/api/canvases/${canvas.id}/commands`, {
      expectedVersion: '1',
      idempotencyKey: cid(),
      commands: [
        {
          type: 'CREATE_GROUP',
          groupId,
          title: 'G',
          transform: { x: 0, y: 0, width: 300, height: 200 },
          memberNodeIds: [nodeId, remainingNodeId],
        },
      ],
    })
    const { json: renameJson } = await ctx.call(
      'POST',
      `/api/canvases/${canvas.id}/commands`,
      {
        expectedVersion: '2',
        idempotencyKey: cid(),
        commands: [{ type: 'RENAME_GROUP', groupId, title: 'Renamed' }],
      },
    )
    const renamePatch = envelopeData(renameJson)
    assert(
      renamePatch.baseVersion === '2' && renamePatch.version === '3',
      JSON.stringify(renamePatch),
    )
    const groupUpsert = renamePatch.groups.find(
      (item) => item.op === 'UPSERT' && item.group.id === groupId,
    )
    assert(groupUpsert?.group.title === 'Renamed', JSON.stringify(renamePatch.groups))
    assert(
      renamePatch.nodes.length === 0 && renamePatch.links.length === 0,
      JSON.stringify(renamePatch),
    )
    await expectHttpError(
      () =>
        ctx.call('POST', `/api/canvases/${canvas.id}/commands`, {
          expectedVersion: '3',
          idempotencyKey: cid(),
          commands: [{ type: 'RENAME_GROUP', groupId, title: ' ' }],
        }),
      { status: 400 },
    )
    await expectHttpError(
      () =>
        ctx.call('POST', `/api/canvases/${canvas.id}/commands`, {
          expectedVersion: '3',
          idempotencyKey: cid(),
          commands: [{ type: 'RENAME_GROUP', groupId: cid(), title: 'x' }],
        }),
      { status: 400 },
    )

    // UNGROUP 接受当前成员的非空子集：只解绑指定成员，Group 与其余成员继续存在。
    const { json: partialUngroupJson } = await ctx.call(
      'POST',
      `/api/canvases/${canvas.id}/commands`,
      {
        expectedVersion: '3',
        idempotencyKey: cid(),
        commands: [{ type: 'UNGROUP', groupId, memberNodeIds: [nodeId] }],
      },
    )
    const partialUngroupPatch = envelopeData(partialUngroupJson)
    assert(
      partialUngroupPatch.baseVersion === '3' && partialUngroupPatch.version === '4',
      JSON.stringify(partialUngroupPatch),
    )
    assert(partialUngroupPatch.groups.length === 0, JSON.stringify(partialUngroupPatch.groups))
    const detachedNode = partialUngroupPatch.nodes.find(
      (item) => item.op === 'UPSERT' && item.node.id === nodeId,
    )
    assert(detachedNode?.node.groupId === null, JSON.stringify(partialUngroupPatch.nodes))

    // snapshot/list：canonical UUID id、version 与 threadId 契约；删除后 404。
    const { json: snapshotJson } = await ctx.call('GET', `/api/canvases/${canvas.id}`)
    const snapshot = envelopeData(snapshotJson)
    assertDecimalVersion(snapshot.document.version, 'snapshot.document.version')
    assert(snapshot.document.version === '4', JSON.stringify(snapshot.document))
    assert(
      snapshot.nodes.some((item) => item.id === nodeId && item.resources[0].kind === 'TEXT'),
      JSON.stringify(snapshot.nodes),
    )
    assert(
      snapshot.nodes.some((item) => item.id === nodeId && item.groupId === null),
      JSON.stringify(snapshot.nodes),
    )
    assert(
      snapshot.nodes.some((item) => item.id === remainingNodeId && item.groupId === groupId),
      JSON.stringify(snapshot.nodes),
    )
    assert(
      snapshot.groups.some(
        (item) => item.id === groupId && item.title === 'Renamed',
      ),
      JSON.stringify(snapshot.groups),
    )
    const { json: listJson } = await ctx.call('GET', '/api/canvases')
    const list = envelopeData(listJson)
    assert(list.some((item) => item.id === canvas.id), JSON.stringify(list))
    const listed = list.find((item) => item.id === canvas.id)
    assertDecimalVersion(listed?.version, 'listed canvas.version')
    assert(listed?.version === '4', JSON.stringify(listed))

    // 跨窗口/version/reconnect 恢复只允许标准 Snapshot；旧 changes 兼容端点必须不存在。
    await expectHttpError(
      () => ctx.call('GET', `/api/canvases/${canvas.id}/changes?afterVersion=0`),
      { status: 404 },
    )

    await ctx.call('DELETE', `/api/canvases/${canvas.id}`)
    await expectHttpError(() => ctx.call('GET', `/api/canvases/${canvas.id}`), { status: 404 })
  },
})
