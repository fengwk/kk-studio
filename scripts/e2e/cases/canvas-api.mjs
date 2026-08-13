import { assert, assertDecimalVersion, cid, envelopeData, expectHttpError } from '../lib/http.mjs'
import { registerCase } from '../lib/registry.mjs'

const UUID_TEXT = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

registerCase({
  id: 'canvas.api_version_contract',
  level: 'L1',
  title: 'Canvas UUID/version/patch/changes HTTP 契约',
  docs:
    '免费 L1：create/list/get/commands 的 canonical UUID id 与十进制字符串 long version，expectedVersion CAS 409，同 commandId 精确回放空 patch 与不同内容 409，changes 连续 patches 或权威 snapshot，Group 重命名与成员子集解绑，删除后 404',
  async run(ctx) {
    const { json: createJson } = await ctx.call('POST', '/api/canvases', {
      title: 'e2e-version-contract',
    })
    const canvas = envelopeData(createJson)
    assert(UUID_TEXT.test(canvas.id), JSON.stringify(canvas))
    assertDecimalVersion(canvas.version, 'canvas.version')
    assert(canvas.version === '0', JSON.stringify(canvas))
    assert(canvas.threadId === null, JSON.stringify(canvas))
    assert(!('graphRevision' in canvas), JSON.stringify(canvas))

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
        commandId: firstCommandId,
        commands: [textCommand, remainingTextCommand],
      },
    )
    const patch = envelopeData(commandJson)
    assertDecimalVersion(patch.baseVersion, 'patch.baseVersion')
    assertDecimalVersion(patch.version, 'patch.version')
    assert(patch.baseVersion === '0' && patch.version === '1', JSON.stringify(patch))
    const upsert = patch.nodes.find((item) => item.op === 'UPSERT' && item.node.id === nodeId)
    assert(upsert?.node.resources?.[0]?.kind === 'TEXT', JSON.stringify(patch.nodes))

    // 同 commandId 精确回放：返回当前版本的确定性空 patch，version 不再前进。
    const { json: replayJson } = await ctx.call(
      'POST',
      `/api/canvases/${canvas.id}/commands`,
      {
        expectedVersion: '0',
        commandId: firstCommandId,
        commands: [textCommand, remainingTextCommand],
      },
    )
    const replay = envelopeData(replayJson)
    assert(replay.baseVersion === '1' && replay.version === '1', JSON.stringify(replay))
    assert(
      replay.groups.length === 0 && replay.nodes.length === 0 && replay.links.length === 0,
      JSON.stringify(replay),
    )

    // 同 commandId 不同内容：IDEMPOTENCY_CONFLICT 409；stale expectedVersion：VERSION_CONFLICT 409。
    await expectHttpError(
      () =>
        ctx.call('POST', `/api/canvases/${canvas.id}/commands`, {
          expectedVersion: '1',
          commandId: firstCommandId,
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
          commandId: cid(),
          commands: [{ type: 'DELETE_NODE', nodeId }],
        }),
      { status: 409 },
    )

    // changes：缓存完整时 afterVersion=0 可直接回放 0→1；缓存缺失时回退权威 snapshot。
    const { json: changesJson } = await ctx.call(
      'GET',
      `/api/canvases/${canvas.id}/changes?afterVersion=0`,
    )
    const changes = envelopeData(changesJson)
    const firstPatchOk =
      changes.snapshot === null
      && changes.patches.length === 1
      && changes.patches[0].baseVersion === '0'
      && changes.patches[0].version === '1'
    const firstSnapshotOk =
      changes.patches.length === 0
      && changes.snapshot !== null
      && assertDecimalVersionSafe(changes.snapshot.document.version)
      && changes.snapshot.document.version === '1'
    assert(firstPatchOk || firstSnapshotOk, JSON.stringify(changes))

    // 已处于权威尾部时没有 delta，也无需返回 snapshot。
    const { json: tailChangesJson } = await ctx.call(
      'GET',
      `/api/canvases/${canvas.id}/changes?afterVersion=1`,
    )
    const tailChanges = envelopeData(tailChangesJson)
    assert(tailChanges.patches.length === 0, JSON.stringify(tailChanges))
    assert(tailChanges.snapshot === null, JSON.stringify(tailChanges))

    // RENAME_GROUP：标题更新只发 group UPSERT patch；空白 title 与未知 group 都是 400。
    const groupId = cid()
    await expectHttpError(
      () =>
        ctx.call('POST', `/api/canvases/${canvas.id}/commands`, {
          expectedVersion: '1',
          commandId: cid(),
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
      commandId: cid(),
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
        commandId: cid(),
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
          commandId: cid(),
          commands: [{ type: 'RENAME_GROUP', groupId, title: ' ' }],
        }),
      { status: 400 },
    )
    await expectHttpError(
      () =>
        ctx.call('POST', `/api/canvases/${canvas.id}/commands`, {
          expectedVersion: '3',
          commandId: cid(),
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
        commandId: cid(),
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

    await ctx.call('DELETE', `/api/canvases/${canvas.id}`)
    await expectHttpError(() => ctx.call('GET', `/api/canvases/${canvas.id}`), { status: 404 })
    await expectHttpError(
      () => ctx.call('GET', `/api/canvases/${canvas.id}/changes?afterVersion=0`),
      { status: 400 },
    )
  },
})

/** 只校验而不断言的版本形状检查，供二选一分支使用。 */
function assertDecimalVersionSafe(value) {
  return typeof value === 'string' && /^(0|[1-9][0-9]*)$/.test(value)
}
