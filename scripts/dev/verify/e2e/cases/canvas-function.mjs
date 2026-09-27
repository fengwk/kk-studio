import { assert, assertDecimalVersion, assertExactFields, cid, envelopeData, expectHttpError, sleep } from '../lib/http.mjs'
import { registerCase } from '../lib/registry.mjs'

const UUID_TEXT = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

/** CanvasFunctionDefinitionDTO 精确字段集合：只有函数名、说明、args schema 与输入/输出限制，没有 model/provider/endpoint。 */
const DEFINITION_FIELDS = [
  'name',
  'description',
  'argsSchema',
  'outputs',
  'referencePolicy',
  'available',
  'unavailableReason',
]

/** CanvasFunctionRunDTO 精确字段集合：stateJson 等后端执行细节绝不外泄。 */
const RUN_FIELDS = ['nodeId', 'requestId', 'status', 'stage', 'error', 'updatedAt']

function functionById(canvasId, nodeId) {
  return {
    canvasId,
    nodeId,
    runPath: `/api/canvases/${canvasId}/nodes/${nodeId}/function-run`,
  }
}

registerCase({
  id: 'canvas.function_fake_runtime',
  level: 'L1',
  requires: ['canvas-function'],
  title: 'Canvas Function 目录与免费 fake 运行链路',
  docs:
    '需 backend 启用 S3 与 kk-studio.canvas.function.fake-enabled；验证已删除的 /api/canvas-function-models 不再存在、GET /api/canvas-functions 只暴露函数名/说明/严格 args schema/有界输出与引用限制（available + required-nullable unavailableReason）；typed SET_NODE_FUNCTION 冻结函数配置，POST function-run 返回 202 且只在 nodeId/requestId/status/stage/error/updatedAt 上表达运行状态（无 stateJson），非 Function 节点 400、无运行 404、同 requestId 精确重放；RUNNING->SUCCEEDED 后快照按输出槽位替换 Resource（IMAGE+blobId），resource preview-url 可直读 WEBP；resolve 只对 UNKNOWN 有效（非 UNKNOWN 409、非法 resolution/空 verification 400），cancel 幂等',
  async run(ctx) {
    // 旧 ComfyUI 风格的模型目录端点已删除。
    await expectHttpError(() => ctx.call('GET', '/api/canvas-function-models'), { status: 404 })

    const definitions = envelopeData((await ctx.call('GET', '/api/canvas-functions')).json)
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

    const created = await ctx.call('POST', '/api/canvases', {
      title: `e2e-function-${cid().slice(0, 8)}`,
    })
    assert(created.status === 201, `create canvas status ${created.status}`)
    const canvas = envelopeData(created.json)
    assertDecimalVersion(canvas.revision, 'canvas.revision')

    // 一个 batch 内声明 Function 节点与一个纯数据节点：后者用于非 Function 边界。
    const functionNodeId = cid()
    const dataNodeId = cid()
    const functionConfig = {
      prompt: 'free deterministic image',
      ratio: '16:9',
    }
    const declared = envelopeData(
      (
        await ctx.call('POST', `/api/canvases/${canvas.id}/commands`, {
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
              resources: [{ kind: 'TEXT', name: 'body', textContent: 'plain node' }],
            },
          ],
        })
      ).json,
    )
    const functionNode = declared.nodes.find(
      (item) => item.op === 'UPSERT' && item.node.id === functionNodeId,
    )?.node
    assert(
      functionNode?.function?.name === imageFunction.name
        && JSON.stringify(functionNode.function.args) === JSON.stringify(functionConfig),
      JSON.stringify(declared.nodes),
    )
    const revisionAfterDeclare = declared.revision

    // 非 Function 节点不能启动执行；Function 节点在没有运行时查询是 404。
    await expectHttpError(
      () => ctx.call('POST', functionById(canvas.id, dataNodeId).runPath, { requestId: cid() }),
      { status: 400 },
    )
    await expectHttpError(() => ctx.call('GET', functionById(canvas.id, functionNodeId).runPath), {
      status: 404,
    })
    await expectHttpError(
      () => ctx.call('POST', `/api/canvases/${canvas.id}/nodes/${cid()}/function-run`, { requestId: cid() }),
      { status: 404 },
    )

    const requestId = cid()
    const started = await ctx.call('POST', functionById(canvas.id, functionNodeId).runPath, {
      requestId,
    })
    assert(started.status === 202, `function run start status ${started.status}`)
    assert(started.json?.code === 'ACCEPTED', JSON.stringify(started.json))
    let run = envelopeData(started.json)
    assertExactFields(run, RUN_FIELDS, 'function run')
    assert(
      run.nodeId === functionNodeId
        && run.requestId === requestId
        && ['READY', 'RUNNING'].includes(run.status)
        && typeof run.stage === 'string'
        && run.error === null
        && Number.isFinite(Date.parse(run.updatedAt)),
      JSON.stringify(run),
    )
    assert(!('stateJson' in run) && !('attempt' in run) && !('leaseToken' in run), JSON.stringify(run))

    const replay = envelopeData(
      (await ctx.call('POST', functionById(canvas.id, functionNodeId).runPath, { requestId })).json,
    )
    assertExactFields(replay, RUN_FIELDS, 'replayed function run')
    assert(replay.requestId === requestId, JSON.stringify(replay))

    for (
      let attempt = 0;
      attempt < 240 && (run.status === 'READY' || run.status === 'RUNNING');
      attempt++
    ) {
      await sleep(250)
      run = envelopeData((await ctx.call('GET', functionById(canvas.id, functionNodeId).runPath)).json)
      assertExactFields(run, RUN_FIELDS, 'function run poll')
    }
    assert(run.status === 'SUCCEEDED' && run.error === null, JSON.stringify(run))

    // 成功发布按输出槽位替换 Resource：Function node 现在只持有 1 个 IMAGE Resource。
    const snapshot = envelopeData((await ctx.call('GET', `/api/canvases/${canvas.id}`)).json)
    assert(Number(snapshot.document.revision) > Number(revisionAfterDeclare), JSON.stringify(snapshot.document))
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

    const preview = envelopeData(
      (
        await ctx.call(
          'POST',
          `/api/canvases/${canvas.id}/resources/${published.resources[0].id}/preview-url`,
        )
      ).json,
    )
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
    const cancelledTerminal = envelopeData(
      (
        await ctx.call('POST', `${functionById(canvas.id, functionNodeId).runPath}/cancel`, {
          requestId,
        })
      ).json,
    )
    assertExactFields(cancelledTerminal, RUN_FIELDS, 'cancel result')
    assert(cancelledTerminal.status === 'SUCCEEDED', JSON.stringify(cancelledTerminal))

    // 运行已收尾的节点可以正常删除画布。
    await ctx.call('DELETE', `/api/canvases/${canvas.id}`)
    await expectHttpError(() => ctx.call('GET', `/api/canvases/${canvas.id}`), { status: 404 })
    await expectHttpError(() => ctx.call('GET', functionById(canvas.id, functionNodeId).runPath), {
      status: 404,
    })
  },
})
