import { assert, assertDecimalVersion, cid, envelopeData, sleep } from '../lib/http.mjs'
import { registerCase } from '../lib/registry.mjs'

registerCase({
  id: 'canvas.function_fake_runtime',
  level: 'L1',
  requires: ['canvas-function'],
  title: 'Canvas fake Function 完整免费运行链路',
  docs:
    '需 backend 启用 S3 与 kk-studio.canvas.function.fake-enabled；验证 create Function node（客户端 UUID nodeId）-> start -> poll -> snapshot Resource 替换 -> preview signed GET，且 document.version 按命令、Run start 与 terminal 状态前进、run 不泄漏 stateJson',
  async run(ctx) {
    const { json: modelsJson } = await ctx.call('GET', '/api/canvas-function-models')
    const models = envelopeData(modelsJson)
    const fakeImage = models.find((model) => model.key === 'fake-image')
    assert(fakeImage?.available === true, JSON.stringify(models))
    assert(!('endpoint' in fakeImage) && !('workflow' in fakeImage), JSON.stringify(fakeImage))

    const { json: createJson } = await ctx.call('POST', '/api/canvases', {
      title: 'e2e-function-runtime',
    })
    const canvas = envelopeData(createJson)
    const configJson = JSON.stringify({
      prompt: { segments: [{ type: 'TEXT', text: 'free deterministic image' }] },
      parameters: { ratio: '16:9' },
    })
    const expectedTransform = { x: 100, y: 100, width: 320, height: 260 }
    const nodeId = cid()
    const { json: commandJson } = await ctx.call(
      'POST',
      `/api/canvases/${canvas.id}/commands`,
      {
        expectedVersion: '0',
        commandId: cid(),
        commands: [
          {
            type: 'CREATE_FUNCTION_NODE',
            nodeId,
            name: 'generated',
            modelKey: 'fake-image',
            configJson,
            transform: expectedTransform,
          },
        ],
      },
    )
    const created = envelopeData(commandJson)
    assertDecimalVersion(created.baseVersion, 'created.baseVersion')
    assertDecimalVersion(created.version, 'created.version')
    assert(created.baseVersion === '0' && created.version === '1', JSON.stringify(created))
    const upsert = created.nodes.find((item) => item.op === 'UPSERT' && item.node.id === nodeId)
    assert(upsert, JSON.stringify(created.nodes))
    const node = upsert.node
    assert(
      Object.keys(node.transform).length === Object.keys(expectedTransform).length
        && Object.entries(expectedTransform).every(([key, value]) => node.transform[key] === value),
      JSON.stringify(node.transform),
    )

    const { json: startJson } = await ctx.call(
      'POST',
      `/api/canvases/${canvas.id}/nodes/${node.id}/runs`,
      { requestId: cid() },
    )
    let run = envelopeData(startJson)
    for (let attempt = 0; attempt < 120 && run.status === 'RUNNING'; attempt++) {
      await sleep(250)
      const { json } = await ctx.call(
        'GET',
        `/api/canvases/${canvas.id}/nodes/${node.id}/run`,
      )
      run = envelopeData(json)
    }
    assert(run.status === 'SUCCEEDED', JSON.stringify(run))
    assert(!('stateJson' in run), JSON.stringify(run))

    const { json: snapshotJson } = await ctx.call('GET', `/api/canvases/${canvas.id}`)
    const snapshot = envelopeData(snapshotJson)
    const generated = snapshot.nodes.find((item) => item.id === node.id)
    assertDecimalVersion(snapshot.document.version, 'snapshot.document.version')
    assert(snapshot.document.version === '3', JSON.stringify(snapshot.document))
    assert(generated?.resources?.length === 1, JSON.stringify(generated))
    assert(generated.resources[0].kind === 'IMAGE', JSON.stringify(generated.resources[0]))
    assert(generated.resources[0].blobId != null, JSON.stringify(generated.resources[0]))
    const { json: previewJson } = await ctx.call(
      'POST',
      `/api/canvases/${canvas.id}/resources/${generated.resources[0].id}/preview-url`,
    )
    const preview = envelopeData(previewJson)
    const response = await fetch(preview.url, { headers: preview.headers || {} })
    const bytes = new Uint8Array(await response.arrayBuffer())
    assert(response.ok, `preview HTTP ${response.status}`)
    assert(
      bytes.length > 12 &&
        String.fromCharCode(...bytes.slice(0, 4)) === 'RIFF' &&
        String.fromCharCode(...bytes.slice(8, 12)) === 'WEBP',
      'preview is not WEBP',
    )
  },
})
