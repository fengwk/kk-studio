import {
  assert,
  assertDecimalVersion,
  assertExactFields,
  cid,
  envelopeData,
  expectHttpError,
} from '../lib/http.mjs'
import { registerCase } from '../lib/registry.mjs'

const UUID_TEXT = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

/** CanvasDocumentDTO 精确字段集合：revision 是同步坐标系，不再是整图前置版本。 */
const DOCUMENT_FIELDS = ['id', 'title', 'revision', 'createdAt', 'updatedAt']

/** CanvasPatchDTO 精确字段集合。 */
const PATCH_FIELDS = ['revision', 'groups', 'nodes']

/** CanvasSnapshotDTO 精确字段集合：连线由 Function args 投影，不存在独立 links 写模型。 */
const SNAPSHOT_FIELDS = ['document', 'nodes', 'groups', 'references']

/** CanvasResourceNodeDTO 精确字段集合。 */
const NODE_FIELDS = [
  'id',
  'canvasId',
  'name',
  'transform',
  'groupId',
  'resources',
  'function',
  'run',
]

/** CanvasGroupDTO 精确字段集合。 */
const GROUP_FIELDS = ['id', 'canvasId', 'title', 'transform']

/** CanvasReferenceDTO 精确字段集合。 */
const REFERENCE_FIELDS = ['canvasId', 'sourceNodeId', 'targetNodeId', 'index']

function transform(x, y, width = 100, height = 80) {
  return { x, y, width, height }
}

/** 创建空画布并断言 document 契约（HTTP 201 + envelope code CREATED）。 */
async function createCanvas(ctx, title) {
  const created = await ctx.call('POST', '/api/canvases', { title })
  assert(created.status === 201, `create canvas status ${created.status}`)
  assert(created.json?.code === 'CREATED', JSON.stringify(created.json))
  const canvas = envelopeData(created.json)
  assertExactFields(canvas, DOCUMENT_FIELDS, 'canvas document')
  assert(UUID_TEXT.test(canvas.id), JSON.stringify(canvas))
  assertDecimalVersion(canvas.revision, 'canvas.revision')
  assert(canvas.revision === '0', JSON.stringify(canvas))
  assert(canvas.title === title, JSON.stringify(canvas))
  assert(
    !('version' in canvas) && !('threadId' in canvas) && !('graphVersion' in canvas),
    `canvas document must expose revision only: ${JSON.stringify(canvas)}`,
  )
  return canvas
}

/**
 * 提交一个 command 批并断言接受回执：HTTP 200 的 CanvasPatchDTO{revision,groups,nodes}。
 *
 * 返回 patch 供调用方断言本次前进的完整变化集。
 */
async function applyCommands(ctx, canvasId, { idempotencyKey, commands }) {
  const accepted = await ctx.call('POST', `/api/canvases/${canvasId}/commands`, {
    idempotencyKey,
    commands,
  })
  assert(accepted.status === 200, `apply commands status ${accepted.status}`)
  const patch = envelopeData(accepted.json)
  assertExactFields(patch, PATCH_FIELDS, 'canvas patch')
  assertDecimalVersion(patch.revision, 'patch.revision')
  assert(Array.isArray(patch.nodes) && Array.isArray(patch.groups), JSON.stringify(patch))
  return patch
}

/** 断言 patch 是「接受位置 + 空变化集」的精确回执。 */
function assertEmptyPatch(patch, revision, label) {
  assert(
    patch.revision === revision && patch.nodes.length === 0 && patch.groups.length === 0,
    `${label}: ${JSON.stringify(patch)}`,
  )
}

/** 读取快照并断言读模型字段集合与 document.revision。 */
async function readSnapshot(ctx, canvasId) {
  const snapshot = envelopeData((await ctx.call('GET', `/api/canvases/${canvasId}`)).json)
  assertExactFields(snapshot, SNAPSHOT_FIELDS, 'canvas snapshot')
  assertExactFields(snapshot.document, DOCUMENT_FIELDS, 'snapshot document')
  for (const node of snapshot.nodes) assertExactFields(node, NODE_FIELDS, 'snapshot node')
  for (const group of snapshot.groups) assertExactFields(group, GROUP_FIELDS, 'snapshot group')
  for (const reference of snapshot.references) {
    assertExactFields(reference, REFERENCE_FIELDS, 'snapshot reference')
    assert(UUID_TEXT.test(reference.sourceNodeId) && UUID_TEXT.test(reference.targetNodeId))
    assert(Number.isSafeInteger(reference.index) && reference.index >= 0, JSON.stringify(reference))
  }
  return snapshot
}

function nodeById(snapshot, nodeId) {
  return snapshot.nodes.find((candidate) => candidate.id === nodeId)
}

function upsertedNode(patch, nodeId) {
  return patch.nodes.find((item) => item.op === 'UPSERT' && item.node.id === nodeId)
}

registerCase({
  id: 'canvas.command_revision_contract',
  level: 'L1',
  title: 'Canvas typed command、revision 坐标与 patch 变化集',
  docs:
    '免费 L1：POST /api/canvases 的 document 只携带 revision（canonical 十进制字符串，HTTP 200 + code CREATED，无 version/threadId）；POST /api/canvases/{id}/commands 接受 11 种 typed command，前置条件是各语义组旧值而非整图版本；有实际变化的批让 revision 精确 +1 并返回该批完整变化集（UPSERT 全投影 / REMOVE 只带身份），纯 no-op 批与同幂等键精确回放都不推进 revision 且返回空变化集；snapshot 读模型无 links、由 Function args 投影 references；删除后 404',
  async run(ctx) {
    const canvas = await createCanvas(ctx, `e2e-canvas-contract-${cid().slice(0, 8)}`)
    const nodeId = cid()
    const secondNodeId = cid()

    // 一个批内创建两个节点：nodeId 由客户端生成，TEXT 资源内联在命令里。
    const createPatch = await applyCommands(ctx, canvas.id, {
      idempotencyKey: cid(),
      commands: [
        {
          type: 'CREATE_NODE',
          nodeId,
          name: 'note',
          transform: transform(1, 2),
          resources: [{ kind: 'TEXT', name: 'body', textContent: 'hello' }],
        },
        {
          type: 'CREATE_NODE',
          nodeId: secondNodeId,
          name: 'second',
          transform: transform(120, 2),
          resources: [{ kind: 'TEXT', name: 'body', textContent: 'second node' }],
        },
      ],
    })
    assert(createPatch.revision === '1', JSON.stringify(createPatch))
    assert(createPatch.groups.length === 0, JSON.stringify(createPatch))
    assert(
      createPatch.nodes.length === 2 && createPatch.nodes.every((item) => item.op === 'UPSERT'),
      JSON.stringify(createPatch.nodes),
    )
    const createdNode = upsertedNode(createPatch, nodeId)?.node
    const secondResourceId = upsertedNode(createPatch, secondNodeId)?.node.resources[0].id
    assertExactFields(createdNode, NODE_FIELDS, 'created node')
    assert(
      createdNode.canvasId === canvas.id
        && createdNode.name === 'note'
        && createdNode.groupId === null
        && createdNode.function === null
        && createdNode.run === null,
      JSON.stringify(createdNode),
    )
    assert(
      Object.entries(transform(1, 2)).every(([key, value]) => createdNode.transform[key] === value),
      JSON.stringify(createdNode.transform),
    )
    const textResource = createdNode.resources[0]
    assertExactFields(
      textResource,
      [
        'id',
        'canvasId',
        'ownerNodeId',
        'resourceIndex',
        'blobId',
        'name',
        'textContent',
        'kind',
        'mediaType',
        'sizeBytes',
        'width',
        'height',
        'durationMs',
        'createdAt',
      ],
      'text resource',
    )
    assert(
      textResource.kind === 'TEXT'
        && textResource.textContent === 'hello'
        && textResource.name === 'body'
        && textResource.ownerNodeId === nodeId
        && textResource.canvasId === canvas.id
        && textResource.resourceIndex === 0
        && textResource.blobId === null
        && textResource.mediaType === null
        && textResource.sizeBytes === null
        && textResource.width === null
        && textResource.height === null
        && textResource.durationMs === null,
      JSON.stringify(textResource),
    )

    // 纯 no-op 批：保留同一槽位、顺序与内容都不变，不推进 revision，也不返回变化集。
    const noopPatch = await applyCommands(ctx, canvas.id, {
      idempotencyKey: cid(),
      commands: [
        {
          type: 'SET_NODE_RESOURCES',
          nodeId,
          expectedResourceIds: [textResource.id],
          resources: [{ kind: 'KEEP', resourceId: textResource.id }],
        },
      ],
    })
    assertEmptyPatch(noopPatch, '1', 'no-op batch must not advance revision')

    // 同幂等键 + 同请求指纹的精确回放：只返回当时记录的接受位置与空变化集。
    const replayKey = cid()
    const replayCommands = [
      {
        type: 'RENAME_NODE',
        nodeId: secondNodeId,
        expectedName: 'second',
        name: 'second-renamed',
      },
    ]
    const renamed = await applyCommands(ctx, canvas.id, {
      idempotencyKey: replayKey,
      commands: replayCommands,
    })
    assert(renamed.revision === '2', JSON.stringify(renamed))
    assert(upsertedNode(renamed, secondNodeId)?.node.name === 'second-renamed', JSON.stringify(renamed))
    const replay = await applyCommands(ctx, canvas.id, {
      idempotencyKey: replayKey,
      commands: replayCommands,
    })
    assertEmptyPatch(replay, '2', 'exact replay must not re-execute')

    // 语义组前置条件过期：整批不写入，409 返回服务端权威值，revision 不前进。
    const staleRename = await expectHttpError(
      () =>
        ctx.call('POST', `/api/canvases/${canvas.id}/commands`, {
          idempotencyKey: cid(),
          commands: [
            { type: 'RENAME_NODE', nodeId, expectedName: 'stale-name', name: 'renamed' },
          ],
        }),
      { status: 409 },
    )
    const staleBody = JSON.parse(staleRename.body)
    assert(staleBody.code === 'CANVAS_COMMAND_CONFLICT', staleRename.body)
    assert(
      staleBody.errors?.detail === 'command prerequisites are stale; no write was applied'
        && Array.isArray(staleBody.errors?.conflicts)
        && staleBody.errors.conflicts.length === 1
        && staleBody.errors.conflicts[0].kind === 'STALE_NODE'
        && staleBody.errors.conflicts[0].nodeId === nodeId
        && staleBody.errors.conflicts[0].group === 'NAME'
        && staleBody.errors.conflicts[0].current.name === 'note',
      staleRename.body,
    )

    // 布局语义组：expectedTransform 是离线积压的编辑起点，过期即冲突，基线正确才推进。
    const layoutConflict = await expectHttpError(
      () =>
        ctx.call('POST', `/api/canvases/${canvas.id}/commands`, {
          idempotencyKey: cid(),
          commands: [
            {
              type: 'UPDATE_NODE_TRANSFORM',
              nodeId,
              transform: transform(9, 9, 10, 10),
              expectedTransform: transform(9, 9, 10, 10),
            },
          ],
        }),
      { status: 409 },
    )
    assert(
      JSON.parse(layoutConflict.body).errors?.conflicts?.[0]?.group === 'LAYOUT',
      layoutConflict.body,
    )
    const moved = await applyCommands(ctx, canvas.id, {
      idempotencyKey: cid(),
      commands: [
        {
          type: 'UPDATE_NODE_TRANSFORM',
          nodeId,
          transform: transform(30, 40, 200, 160),
          expectedTransform: transform(1, 2),
        },
      ],
    })
    assert(moved.revision === '3', JSON.stringify(moved))
    assert(
      upsertedNode(moved, nodeId)?.node.transform.width === 200
        && upsertedNode(moved, nodeId)?.node.transform.height === 160,
      JSON.stringify(moved),
    )

    // 分组：CREATE_GROUP 创建视觉分组，SET_NODE_GROUP 建立成员关系。
    const groupId = cid()
    const createdGroup = await applyCommands(ctx, canvas.id, {
      idempotencyKey: cid(),
      commands: [
        {
          type: 'CREATE_GROUP',
          groupId,
          title: 'Group A',
          transform: transform(0, 0, 400, 300),
        },
      ],
    })
    assert(createdGroup.revision === '4' && createdGroup.nodes.length === 0, JSON.stringify(createdGroup))
    assert(
      createdGroup.groups.length === 1
        && createdGroup.groups[0].op === 'UPSERT'
        && createdGroup.groups[0].group.title === 'Group A'
        && createdGroup.groups[0].group.canvasId === canvas.id,
      JSON.stringify(createdGroup.groups),
    )
    const grouped = await applyCommands(ctx, canvas.id, {
      idempotencyKey: cid(),
      commands: [{ type: 'SET_NODE_GROUP', nodeId, expectedGroupId: null, groupId }],
    })
    assert(grouped.revision === '5', JSON.stringify(grouped))
    assert(upsertedNode(grouped, nodeId)?.node.groupId === groupId, JSON.stringify(grouped))

    // 分组改名 + 分组几何各自是可比较的语义组。
    const renamedGroup = await applyCommands(ctx, canvas.id, {
      idempotencyKey: cid(),
      commands: [
        {
          type: 'UPDATE_GROUP_TRANSFORM',
          groupId,
          transform: transform(0, 0, 500, 300),
          expectedTransform: transform(0, 0, 400, 300),
        },
        { type: 'RENAME_GROUP', groupId, expectedTitle: 'Group A', title: 'Renamed Group' },
      ],
    })
    assert(renamedGroup.revision === '6', JSON.stringify(renamedGroup))
    assert(
      renamedGroup.nodes.length === 0
        && renamedGroup.groups.length === 1
        && renamedGroup.groups[0].group.title === 'Renamed Group'
        && renamedGroup.groups[0].group.transform.width === 500,
      JSON.stringify(renamedGroup),
    )

    // 显式建立引用连线：Function args 的保留引用形状被投影成 references，不是独立可写边。
    const referenceFunction = {
      name: 'image.crop',
      args: { source: { type: 'resource', nodeId, index: 0 } },
    }
    const referenced = await applyCommands(ctx, canvas.id, {
      idempotencyKey: cid(),
      commands: [
        {
          type: 'SET_NODE_FUNCTION',
          nodeId: secondNodeId,
          expectedFunction: null,
          function: referenceFunction,
        },
      ],
    })
    assert(referenced.revision === '7', JSON.stringify(referenced))
    assert(
      upsertedNode(referenced, secondNodeId)?.node.function?.name === 'image.crop',
      JSON.stringify(referenced.nodes),
    )

    const snapshot = await readSnapshot(ctx, canvas.id)
    assert(snapshot.document.revision === '7', JSON.stringify(snapshot.document))
    assert(snapshot.nodes.length === 2 && snapshot.groups.length === 1, JSON.stringify(snapshot))
    assert(nodeById(snapshot, nodeId)?.groupId === groupId, JSON.stringify(snapshot.nodes))
    assert(
      snapshot.references.length === 1
        && snapshot.references[0].canvasId === canvas.id
        && snapshot.references[0].sourceNodeId === nodeId
        && snapshot.references[0].targetNodeId === secondNodeId
        && snapshot.references[0].index === 0,
      JSON.stringify(snapshot.references),
    )
    assert(!('links' in snapshot), JSON.stringify(snapshot))

    // 深度删除画布：先释放同批引用，再删除两个节点、分组与 document。
    const cleared = await applyCommands(ctx, canvas.id, {
      idempotencyKey: cid(),
      commands: [
        { type: 'SET_NODE_FUNCTION', nodeId: secondNodeId, expectedFunction: referenceFunction, function: null },
        {
          type: 'DELETE_NODE',
          nodeId,
          expectedResourceIds: [textResource.id],
          expectedFunction: null,
        },
        {
          type: 'DELETE_NODE',
          nodeId: secondNodeId,
          expectedResourceIds: [secondResourceId],
          expectedFunction: null,
        },
        { type: 'DELETE_GROUP', groupId, expectedMemberNodeIds: [] },
      ],
    })
    assert(cleared.revision === '8', JSON.stringify(cleared))
    assert(
      cleared.nodes.length === 2 && cleared.nodes.every((item) => item.op === 'REMOVE'),
      JSON.stringify(cleared.nodes),
    )
    assert(
      cleared.groups.length === 1 && cleared.groups[0].op === 'REMOVE' && cleared.groups[0].groupId === groupId,
      JSON.stringify(cleared.groups),
    )

    const listed = envelopeData((await ctx.call('GET', '/api/canvases')).json)
    assert(Array.isArray(listed), JSON.stringify(listed))
    const listedDocument = listed.find((candidate) => candidate.id === canvas.id)
    assertExactFields(listedDocument, DOCUMENT_FIELDS, 'listed document')
    assert(listedDocument.revision === '8', JSON.stringify(listedDocument))

    await ctx.call('DELETE', `/api/canvases/${canvas.id}`)
    await expectHttpError(() => ctx.call('GET', `/api/canvases/${canvas.id}`), { status: 404 })
  },
})

registerCase({
  id: 'canvas.command_conflict_contract',
  level: 'L1',
  title: 'Canvas typed command 冲突与严格请求边界',
  docs:
    '免费 L1：整批 409 不写入任何行——TARGET_MISSING/TARGET_PRESENT/STALE_NODE(MEMBERSHIP)/STALE_GROUP/NODE_REFERENCED 各自携带受影响对象与服务端权威值，不返回新编辑基线；同幂等键绑定不同请求指纹 409、画布不存在 404；未知命令类型（已删除的 CREATE_LINK）、命令级未知字段、空命令批、缺失或非 canonical 幂等键、非法 UUID 都在触达写入前 400',
  async run(ctx) {
    const canvas = await createCanvas(ctx, `e2e-canvas-conflict-${cid().slice(0, 8)}`)
    const upstreamNodeId = cid()
    const consumerNodeId = cid()
    const groupId = cid()

    const created = await applyCommands(ctx, canvas.id, {
      idempotencyKey: cid(),
      commands: [
        {
          type: 'CREATE_NODE',
          nodeId: upstreamNodeId,
          name: 'upstream',
          transform: transform(0, 0),
          resources: [{ kind: 'TEXT', name: 'body', textContent: 'source' }],
        },
      ],
    })
    assert(created.revision === '1', JSON.stringify(created))
    const upstreamResourceId = upsertedNode(created, upstreamNodeId).node.resources[0].id
    await applyCommands(ctx, canvas.id, {
      idempotencyKey: cid(),
      commands: [
        {
          type: 'CREATE_NODE',
          nodeId: consumerNodeId,
          name: 'consumer',
          transform: transform(200, 0),
          resources: [{ kind: 'TEXT', name: 'body', textContent: 'consumer' }],
        },
        { type: 'CREATE_GROUP', groupId, title: 'Conflict Group', transform: transform(0, 0, 400, 300) },
        { type: 'SET_NODE_GROUP', nodeId: consumerNodeId, expectedGroupId: null, groupId },
      ],
    })

    const base = await readSnapshot(ctx, canvas.id)
    assert(base.document.revision === '2', JSON.stringify(base.document))

    const conflictsFor = async (commands) => {
      const failure = await expectHttpError(
        () =>
          ctx.call('POST', `/api/canvases/${canvas.id}/commands`, {
            idempotencyKey: cid(),
            commands,
          }),
        { status: 409 },
      )
      const body = JSON.parse(failure.body)
      assert(body.code === 'CANVAS_COMMAND_CONFLICT', failure.body)
      assert(Array.isArray(body.errors?.conflicts) && body.errors.conflicts.length > 0, failure.body)
      for (const conflict of body.errors.conflicts) {
        assert(!('revision' in conflict) && !('baseline' in conflict), failure.body)
      }
      return body.errors.conflicts
    }

    // 目标不存在 / 已存在分别是不同判别值，且冲突批绝不部分写入。
    const missingConflicts = await conflictsFor([
      { type: 'RENAME_NODE', nodeId: cid(), expectedName: 'whatever', name: 'renamed' },
      { type: 'SET_NODE_GROUP', nodeId: cid(), expectedGroupId: null, groupId: null },
      { type: 'RENAME_GROUP', groupId: cid(), expectedTitle: 'whatever', title: 'renamed' },
    ])
    assertExactFields(missingConflicts[0], ['kind', 'targetId', 'target'], 'target missing conflict')
    assert(
      missingConflicts.every(
        (conflict) =>
          (conflict.kind === 'TARGET_MISSING' && ['NODE', 'GROUP'].includes(conflict.target)),
      ),
      JSON.stringify(missingConflicts),
    )

    const presentConflicts = await conflictsFor([
      {
        type: 'CREATE_NODE',
        nodeId: upstreamNodeId,
        name: 'duplicate',
        transform: transform(0, 0),
        resources: [{ kind: 'TEXT', name: 'body', textContent: 'duplicate' }],
      },
      { type: 'CREATE_GROUP', groupId, title: 'duplicate group', transform: transform(0, 0, 10, 10) },
    ])
    assert(
      presentConflicts.length === 2
        && presentConflicts.every(
          (conflict) =>
            conflict.kind === 'TARGET_PRESENT' && ['NODE', 'GROUP'].includes(conflict.target),
        ),
      JSON.stringify(presentConflicts),
    )

    // 成员关系前置条件过期：DELETE_GROUP 必须显式声明编辑起点的成员集合。
    const membershipConflicts = await conflictsFor([
      {
        type: 'DELETE_GROUP',
        groupId,
        expectedMemberNodeIds: [upstreamNodeId],
      },
    ])
    assert(
      membershipConflicts.some(
        (conflict) =>
          conflict.kind === 'STALE_NODE'
          && conflict.nodeId === consumerNodeId
          && conflict.group === 'MEMBERSHIP',
      ),
      JSON.stringify(membershipConflicts),
    )

    const staleGroupConflicts = await conflictsFor([
      { type: 'RENAME_GROUP', groupId, expectedTitle: 'stale title', title: 'Renamed' },
      {
        type: 'UPDATE_GROUP_TRANSFORM',
        groupId,
        transform: transform(0, 0, 10, 10),
        expectedTransform: transform(0, 0, 10, 10),
      },
    ])
    assert(
      staleGroupConflicts.every((conflict) => conflict.kind === 'STALE_GROUP' && conflict.groupId === groupId),
      JSON.stringify(staleGroupConflicts),
    )
    assertExactFields(
      staleGroupConflicts[0],
      ['kind', 'groupId', 'current'],
      'stale group conflict',
    )
    assert(staleGroupConflicts[0].current.title === 'Conflict Group', JSON.stringify(staleGroupConflicts))

    // 冲突批不写入：快照与写前完全一致。
    const afterConflicts = await readSnapshot(ctx, canvas.id)
    assert(
      afterConflicts.document.revision === base.document.revision
        && JSON.stringify(afterConflicts.nodes) === JSON.stringify(base.nodes),
      JSON.stringify({ base, afterConflicts }),
    )

    // 被引用节点必须先在同一批解除引用：单独删除返回 NODE_REFERENCED 与服务端权威引用者列表。
    const referenceFunction = {
      name: 'image.crop',
      args: { source: { type: 'resource', nodeId: upstreamNodeId, index: 0 } },
    }
    await applyCommands(ctx, canvas.id, {
      idempotencyKey: cid(),
      commands: [
        {
          type: 'SET_NODE_FUNCTION',
          nodeId: consumerNodeId,
          expectedFunction: null,
          function: referenceFunction,
        },
      ],
    })
    const referencedConflicts = await conflictsFor([
      {
        type: 'DELETE_NODE',
        nodeId: upstreamNodeId,
        expectedResourceIds: [upstreamResourceId],
        expectedFunction: null,
      },
    ])
    assert(
      referencedConflicts.length === 1
        && referencedConflicts[0].kind === 'NODE_REFERENCED'
        && referencedConflicts[0].nodeId === upstreamNodeId
        && referencedConflicts[0].referencingNodeIds.length === 1
        && referencedConflicts[0].referencingNodeIds[0] === consumerNodeId,
      JSON.stringify(referencedConflicts),
    )
    const released = await applyCommands(ctx, canvas.id, {
      idempotencyKey: cid(),
      commands: [
        {
          type: 'SET_NODE_FUNCTION',
          nodeId: consumerNodeId,
          expectedFunction: referenceFunction,
          function: null,
        },
        {
          type: 'DELETE_NODE',
          nodeId: upstreamNodeId,
          expectedResourceIds: [upstreamResourceId],
          expectedFunction: null,
        },
      ],
    })
    assert(
      released.nodes.some((item) => item.op === 'REMOVE' && item.nodeId === upstreamNodeId)
        && upsertedNode(released, consumerNodeId)?.node.function === null,
      JSON.stringify(released.nodes),
    )

    // 同幂等键绑定不同请求指纹：确定性 409，且不冒充新的执行状态。
    const boundKey = cid()
    await applyCommands(ctx, canvas.id, {
      idempotencyKey: boundKey,
      commands: [{ type: 'RENAME_NODE', nodeId: consumerNodeId, expectedName: 'consumer', name: 'consumer-2' }],
    })
    await expectHttpError(
      () =>
        ctx.call('POST', `/api/canvases/${canvas.id}/commands`, {
          idempotencyKey: boundKey,
          commands: [{ type: 'RENAME_NODE', nodeId: consumerNodeId, expectedName: 'consumer', name: 'other' }],
        }),
      { status: 409 },
    )
    await expectHttpError(
      () =>
        ctx.call('POST', `/api/canvases/${cid()}/commands`, {
          idempotencyKey: cid(),
          commands: [
            { type: 'RENAME_NODE', nodeId: consumerNodeId, expectedName: 'consumer-2', name: 'x' },
          ],
        }),
      { status: 404 },
    )

    // 严格请求校验：全部在触达写入前 400。
    for (const request of [
      { idempotencyKey: cid(), commands: [{ type: 'CREATE_LINK', nodeId: consumerNodeId }] },
      {
        idempotencyKey: cid(),
        commands: [
          {
            type: 'DELETE_NODE',
            nodeId: consumerNodeId,
            expectedResourceIds: [],
            expectedFunction: null,
            unexpected: true,
          },
        ],
      },
      {
        idempotencyKey: cid(),
        commands: [
          {
            type: 'CREATE_NODE',
            nodeId: cid(),
            name: 'n',
            transform: transform(0, 0, 1, 1),
            resources: [{ kind: 'UPLOAD', name: 'legacy.png', uploadId: cid() }],
          },
        ],
      },
      { idempotencyKey: cid(), commands: [] },
      { commands: [{ type: 'RENAME_NODE', nodeId: consumerNodeId, expectedName: 'consumer-2', name: 'y' }] },
      {
        idempotencyKey: 'not-a-uuid',
        commands: [
          { type: 'RENAME_NODE', nodeId: consumerNodeId, expectedName: 'consumer-2', name: 'z' },
        ],
      },
      { idempotencyKey: cid(), commands: [{ type: 'RENAME_NODE', nodeId: consumerNodeId, expectedName: 'consumer-2', name: 'w' }], extra: true },
    ]) {
      await expectHttpError(
        () => ctx.call('POST', `/api/canvases/${canvas.id}/commands`, request),
        { status: 400 },
      )
    }
    await expectHttpError(() => ctx.call('GET', '/api/canvases/not-a-uuid'), { status: 400 })

    const finalSnapshot = await readSnapshot(ctx, canvas.id)
    assert(
      finalSnapshot.document.revision === '5' && finalSnapshot.nodes.length === 1,
      JSON.stringify(finalSnapshot.document),
    )
    await ctx.call('DELETE', `/api/canvases/${canvas.id}`)
    await expectHttpError(() => ctx.call('GET', `/api/canvases/${canvas.id}`), { status: 404 })
  },
})
