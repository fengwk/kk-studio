import { assert, assertDecimalVersion, assertExactFields, cid, expectHttpError, sleep } from '../lib/http.mjs'
import { createCaseAdder } from '../lib/registry.mjs'

export const cases = []
const registerCase = createCaseAdder(cases)

const UUID_TEXT = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

/** CanvasFunctionDefinitionDTO 精确字段集合：只有函数名、说明、args schema、输入/输出限制与目录版本，没有 model/provider/endpoint。 */
const DEFINITION_FIELDS = [
  'name',
  'description',
  'argsSchema',
  'outputs',
  'referencePolicy',
  'available',
  'unavailableReason',
  'version',
]

/** CanvasFunctionRunDTO 精确字段集合：stateJson 等后端执行细节绝不外泄。 */
const RUN_FIELDS = ['nodeId', 'requestId', 'status', 'stage', 'error', 'updatedAt']

/** 目录项版本身份必须恰好是 lowercase SHA-256 fingerprint。 */
function assertVersion(version, label) {
  assertExactFields(version, ['definitionFingerprint'], label)
  assert(
    typeof version.definitionFingerprint === 'string'
      && /^[0-9a-f]{64}$/.test(version.definitionFingerprint),
    `${label}.definitionFingerprint must be a lowercase SHA-256: ${JSON.stringify(version)}`,
  )
  return version
}

function functionById(canvasId, nodeId) {
  return {
    canvasId,
    nodeId,
    runPath: `/api/canvas/canvases/${canvasId}/nodes/${nodeId}/function-run`,
  }
}

registerCase({
  id: 'canvas.function_fake_runtime',
  level: 'L1',
  requires: ['canvas-function'],
  title: 'Canvas Function 目录与免费 fake 运行链路',
  docs:
    '需 backend 启用 S3 与 kk-studio.canvas.function.fake-enabled；GET /api/canvas/functions 只暴露函数名/说明/严格 args schema/有界输出/引用限制与目录版本（available + required-nullable unavailableReason + version{definitionFingerprint}）；typed SET_NODE_FUNCTION 冻结函数配置，POST function-run 的 expectedVersion 必填且形状严格（缺失/未知字段/非法 fingerprint 400），目录定义 fingerprint 不匹配返回 409 且不写 Run/pin，精确 requestId 重放优先于版本校验，返回 202 且只在 nodeId/requestId/status/stage/error/updatedAt 上表达运行状态（无 stateJson），非 Function 节点 400、无运行 404；RUNNING->SUCCEEDED 后快照按输出槽位替换 Resource（IMAGE+blobId），resource preview-url 可直读 WEBP；resolve 只对 UNKNOWN 有效（非 UNKNOWN 409、非法 resolution/空 verification 400），cancel 幂等',
  async run(ctx) {
    const definitions = (await ctx.call('GET', '/api/canvas/functions')).json
    assert(Array.isArray(definitions) && definitions.length > 0, JSON.stringify(definitions))
    for (const definition of definitions) {
      assertExactFields(definition, DEFINITION_FIELDS, 'function definition')
      assert(
        typeof definition.name === 'string'
          && definition.name.trim()
          && typeof definition.description === 'string'
          && typeof definition.available === 'boolean'
          && (definition.available ? definition.unavailableReason === null : true),
        JSON.stringify(definition),
      )
      // 目录绝不过滤成后端实现细节：argsSchema 是模型可见的严格 JSON Schema。
      assert(
        definition.argsSchema?.type === 'object' && definition.argsSchema?.additionalProperties === false,
        JSON.stringify(definition.argsSchema),
      )
      assert(
        !('endpoint' in definition) && !('workflow' in definition) && !('parameters' in definition),
        JSON.stringify(definition),
      )
      assertVersion(definition.version, `function ${definition.name} version`)
    }
    const imageFunction = definitions.find((definition) => definition.name === 'fake-image')
    assert(imageFunction, JSON.stringify(definitions.map((definition) => definition.name)))
    assert(
      imageFunction.argsSchema.properties?.ratio?.enum?.includes('16:9'),
      JSON.stringify(imageFunction.argsSchema),
    )
    assert(
      imageFunction.referencePolicy?.allowedKinds?.includes('IMAGE')
        && imageFunction.referencePolicy?.maxReferences === 12,
      JSON.stringify(imageFunction.referencePolicy),
    )
    assert(
      imageFunction.outputs.length === 1 && imageFunction.outputs[0].kind === 'IMAGE',
      JSON.stringify(imageFunction.outputs),
    )

    const created = await ctx.call('POST', '/api/canvas/canvases', {
      title: `e2e-function-${cid().slice(0, 8)}`,
    })
    assert(created.status === 201, `create canvas status ${created.status}`)
    const canvas = created.json
    assertDecimalVersion(canvas.revision, 'canvas.revision')

    // 一个 batch 内声明 Function 节点与一个纯数据节点：后者用于非 Function 边界。
    const functionNodeId = cid()
    const dataNodeId = cid()
    const functionConfig = {
      prompt: 'free deterministic image',
      ratio: '16:9',
    }
    const declared = (
        await ctx.call('POST', `/api/canvas/canvases/${canvas.id}/commands`, {
          idempotencyKey: cid(),
          commands: [
            {
              type: 'CREATE_NODE',
              nodeId: functionNodeId,
              name: 'generated',
              transform: { x: 100, y: 100, width: 320, height: 260 },
              resources: [],
            },
            {
              type: 'SET_NODE_FUNCTION',
              nodeId: functionNodeId,
              expectedFunction: null,
              function: { name: imageFunction.name, args: functionConfig },
            },
            {
              type: 'CREATE_NODE',
              nodeId: dataNodeId,
              name: 'plain',
              transform: { x: 0, y: 0, width: 100, height: 100 },
              resources: [{ type: 'TEXT', name: 'body', textContent: 'plain node' }],
            },
          ],
        })
      ).json
    const functionNode = declared.nodes.find(
      (item) => item.type === 'UPSERT' && item.node.id === functionNodeId,
    )?.node
    assert(
      functionNode?.function?.name === imageFunction.name
        && JSON.stringify(functionNode.function.args) === JSON.stringify(functionConfig),
      JSON.stringify(declared.nodes),
    )
    const revisionAfterDeclare = declared.revision

    // 非 Function 节点不能启动执行；Function 节点在没有运行时查询是 404。
    // 这些请求同样必须回传目录版本，避免用缺失 expectedVersion 掩盖真正的领域错误。
    const imageVersion = assertVersion(imageFunction.version, 'fake-image version')
    await expectHttpError(
      () =>
        ctx.call('POST', functionById(canvas.id, dataNodeId).runPath, {
          requestId: cid(),
          expectedVersion: imageVersion,
        }),
      { status: 400 },
    )
    await expectHttpError(() => ctx.call('GET', functionById(canvas.id, functionNodeId).runPath), {
      status: 404,
    })
    await expectHttpError(
      () =>
        ctx.call('POST', `/api/canvas/canvases/${canvas.id}/nodes/${cid()}/function-run`, {
          requestId: cid(),
          expectedVersion: imageVersion,
        }),
      { status: 404 },
    )

    // 版本围栏：expectedVersion 必填且形状严格；目录已变化时明确 409，绝不 pin、写 Run 或提交外部任务。
    for (const invalid of [
      { requestId: cid() },
      { requestId: cid(), expectedVersion: null },
      { requestId: cid(), expectedVersion: { unknownField: '01', definitionFingerprint: imageVersion.definitionFingerprint } },
      { requestId: cid(), expectedVersion: { definitionFingerprint: imageVersion.definitionFingerprint.toUpperCase() } },
      { requestId: cid(), expectedVersion: { definitionFingerprint: 'abc' } },
    ]) {
      await expectHttpError(
        () => ctx.call('POST', functionById(canvas.id, functionNodeId).runPath, invalid),
        { status: 400 },
      )
    }
    // 构造合法但不同的 fingerprint，确保命中版本围栏而非输入校验。
    const staleVersion = {
      definitionFingerprint:
        (imageVersion.definitionFingerprint[0] === '0' ? '1' : '0')
        + imageVersion.definitionFingerprint.slice(1),
    }
    assertVersion(staleVersion, 'stale expectedVersion')
    const conflict = await expectHttpError(
      () =>
        ctx.call('POST', functionById(canvas.id, functionNodeId).runPath, {
          requestId: cid(),
          expectedVersion: staleVersion,
        }),
      { status: 409 },
    )
    assert(
      !/stateJson/.test(conflict.body),
      `version conflict must not leak run internals: ${conflict.body}`,
    )
    // 拒绝后没有产生任何 Run 事实：查询仍是「无运行」404，快照也没有新增 run 投影。
    await expectHttpError(() => ctx.call('GET', functionById(canvas.id, functionNodeId).runPath), {
      status: 404,
    })
    const unchanged = (await ctx.call('GET', `/api/canvas/canvases/${canvas.id}`)).json
    assert(
      unchanged.document.revision === revisionAfterDeclare
        && unchanged.nodes.every((node) => node.run === null),
      `rejected versions must not write anything: ${JSON.stringify(unchanged)}`,
    )

    const requestId = cid()
    const started = await ctx.call('POST', functionById(canvas.id, functionNodeId).runPath, {
      requestId,
      expectedVersion: imageVersion,
    })
    assert(started.status === 202, `function run start status ${started.status}`)
    let run = started.json
    assertExactFields(run, RUN_FIELDS, 'function run')
    assert(
      run.nodeId === functionNodeId
        && run.requestId === requestId
        && ['READY', 'RUNNING'].includes(run.status)
        && typeof run.stage === 'string'
        && run.error === null
        && Number.isFinite(run.updatedAt),
      JSON.stringify(run),
    )
    assert(!('stateJson' in run) && !('attempt' in run) && !('leaseToken' in run), JSON.stringify(run))

    // 精确 requestId 重放优先于版本校验：即使 expectedVersion 与当前目录不一致也返回已接受结果。
    const replay = (
        await ctx.call('POST', functionById(canvas.id, functionNodeId).runPath, {
          requestId,
          expectedVersion: staleVersion,
        })
      ).json
    assertExactFields(replay, RUN_FIELDS, 'replayed function run')
    assert(replay.requestId === requestId, JSON.stringify(replay))

    for (
      let attempt = 0;
      attempt < 240 && (run.status === 'READY' || run.status === 'RUNNING');
      attempt++
    ) {
      await sleep(250)
      run = (await ctx.call('GET', functionById(canvas.id, functionNodeId).runPath)).json
      assertExactFields(run, RUN_FIELDS, 'function run poll')
    }
    assert(run.status === 'SUCCEEDED' && run.error === null, JSON.stringify(run))

    // 成功发布按输出槽位替换 Resource：Function node 现在只持有 1 个 IMAGE Resource。
    const snapshot = (await ctx.call('GET', `/api/canvas/canvases/${canvas.id}`)).json
    assert(BigInt(snapshot.document.revision) > BigInt(revisionAfterDeclare), JSON.stringify(snapshot.document))
    const published = snapshot.nodes.find((node) => node.id === functionNodeId)
    assert(
      published.resources.length === 1
        && published.resources[0].kind === 'IMAGE'
        && UUID_TEXT.test(published.resources[0].blobId)
        && published.function?.name === imageFunction.name
        && published.run?.status === 'SUCCEEDED',
      JSON.stringify(published),
    )
    assertDecimalVersion(published.resources[0].sizeBytes, 'published.sizeBytes')
    assert(snapshot.references.length === 0, JSON.stringify(snapshot.references))

    const preview = (
        await ctx.call(
          'POST',
          `/api/canvas/canvases/${canvas.id}/resources/${published.resources[0].id}/preview-url`,
        )
      ).json
    const previewResponse = await fetch(preview.url, { headers: preview.headers || {} })
    assert(previewResponse.ok, `preview HTTP ${previewResponse.status}`)
    const bytes = new Uint8Array(await previewResponse.arrayBuffer())
    assert(
      bytes.length > 12
        && String.fromCharCode(...bytes.slice(0, 4)) === 'RIFF'
        && String.fromCharCode(...bytes.slice(8, 12)) === 'WEBP',
      'preview is not WEBP',
    )

    // UNKNOWN 是唯一可被人工核查解除的状态：请求形状仍先严格校验（非法 resolution / 空核查事实 400），
    // 形状合法但运行已收尾时明确以 409 拒绝，且冲突响应绝不泄露 stateJson。
    for (const body of [
      { requestId, resolution: 'NOPE', verification: 'checked the provider console' },
      { requestId, resolution: 'RESUME', verification: '   ' },
      { requestId, resolution: 'RESUME' },
    ]) {
      await expectHttpError(
        () => ctx.call('POST', `${functionById(canvas.id, functionNodeId).runPath}/resolve`, body),
        { status: 400 },
      )
    }
    for (const resolution of ['RESUME', 'FAILED', 'CANCELLED']) {
      const resolveConflict = await expectHttpError(
        () =>
          ctx.call('POST', `${functionById(canvas.id, functionNodeId).runPath}/resolve`, {
            requestId,
            resolution,
            verification: 'checked the provider console',
          }),
        { status: 409 },
      )
      assert(
        !/stateJson/.test(resolveConflict.body),
        `resolve conflict must not leak run internals: ${resolveConflict.body}`,
      )
    }

    // cancel 绑定 requestId：不同 id 拒绝；终态运行的 cancel 是幂等读取。
    await expectHttpError(
      () =>
        ctx.call('POST', `${functionById(canvas.id, functionNodeId).runPath}/cancel`, {
          requestId: cid(),
        }),
      { status: 409 },
    )
    const cancelledTerminal = (
        await ctx.call('POST', `${functionById(canvas.id, functionNodeId).runPath}/cancel`, {
          requestId,
        })
      ).json
    assertExactFields(cancelledTerminal, RUN_FIELDS, 'cancel result')
    assert(cancelledTerminal.status === 'SUCCEEDED', JSON.stringify(cancelledTerminal))

  },
})
