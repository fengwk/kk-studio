import { assert, assertDecimalVersion, cid, envelopeData, expectHttpError } from '../lib/http.mjs'
import { registerCase } from '../lib/registry.mjs'

const UUID_TEXT = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

registerCase({
  id: 'canvas.api_version_contract',
  level: 'L1',
  title: 'Canvas UUID/version/patch/changes HTTP 契约',
  docs:
    '免费 L1：create/list/get/commands 的 canonical UUID id 与十进制字符串 long version，expectedVersion CAS 409，同 commandId 精确回放空 patch 与不同内容 409，changes 连续 patches 或权威 snapshot，删除后 404',
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
    const firstCommandId = cid()
    const textCommand = {
      type: 'CREATE_TEXT_NODE',
      nodeId,
      name: 'note',
      markdown: 'hello',
      transform: { x: 1, y: 2, width: 100, height: 80 },
    }
    const { json: commandJson } = await ctx.call(
      'POST',
      `/api/canvases/${canvas.id}/commands`,
      {
        expectedVersion: '0',
        commandId: firstCommandId,
        commands: [textCommand],
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
        commands: [textCommand],
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

    // changes：afterVersion=0 总是权威 snapshot；缓存存在时返回连续 patches，否则同样回退 snapshot。
    const { json: changesJson } = await ctx.call(
      'GET',
      `/api/canvases/${canvas.id}/changes?afterVersion=0`,
    )
    const changes = envelopeData(changesJson)
    assertDecimalVersion(changes.snapshot?.document?.version, 'snapshot.document.version')
    assert(changes.snapshot?.document?.version === '1', JSON.stringify(changes))
    assert(changes.patches.length === 0, JSON.stringify(changes))
    const { json: tailChangesJson } = await ctx.call(
      'GET',
      `/api/canvases/${canvas.id}/changes?afterVersion=1`,
    )
    const tailChanges = envelopeData(tailChangesJson)
    const tailPatchesOk =
      tailChanges.patches.length === 1
      && assertDecimalVersionSafe(tailChanges.patches[0].version)
      && tailChanges.patches[0].version === '1'
    const tailSnapshotOk =
      tailChanges.snapshot !== null
      && assertDecimalVersionSafe(tailChanges.snapshot.document.version)
      && tailChanges.snapshot.document.version === '1'
    assert(tailPatchesOk || tailSnapshotOk, JSON.stringify(tailChanges))

    // snapshot/list：canonical UUID id、version 与 threadId 契约；删除后 404。
    const { json: snapshotJson } = await ctx.call('GET', `/api/canvases/${canvas.id}`)
    const snapshot = envelopeData(snapshotJson)
    assertDecimalVersion(snapshot.document.version, 'snapshot.document.version')
    assert(snapshot.document.version === '1', JSON.stringify(snapshot.document))
    assert(
      snapshot.nodes.some((item) => item.id === nodeId && item.resources[0].kind === 'TEXT'),
      JSON.stringify(snapshot.nodes),
    )
    const { json: listJson } = await ctx.call('GET', '/api/canvases')
    const list = envelopeData(listJson)
    assert(list.some((item) => item.id === canvas.id), JSON.stringify(list))
    const listed = list.find((item) => item.id === canvas.id)
    assertDecimalVersion(listed?.version, 'listed canvas.version')
    assert(listed?.version === '1', JSON.stringify(listed))

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
