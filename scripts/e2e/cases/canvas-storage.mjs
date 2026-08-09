import { assert, envelopeData, expectHttpError } from '../lib/http.mjs'
import { registerCase } from '../lib/registry.mjs'

registerCase({
  id: 'canvas.storage_upload_contract',
  level: 'L1',
  requires: ['canvas-storage'],
  title: 'Canvas Resource reserve 边界与隐藏存储坐标',
  docs:
    '需 backend 启用 S3；创建 Canvas 后验证 IMAGE reserve 返回 uploadId/PUT/public URL 且不暴露 bucket/key，TEXT 与客户端 key 注入均 400',
  async run(ctx) {
    const { json: createJson } = await ctx.call('POST', '/api/canvases', {
      title: 'e2e-resource-storage',
    })
    const canvas = envelopeData(createJson)
    assert(/^[1-9][0-9]*$/.test(canvas.id), JSON.stringify(canvas))

    const { json: reserveJson } = await ctx.call(
      'POST',
      `/api/canvases/${canvas.id}/uploads`,
      {
        kind: 'IMAGE',
        filename: 'tiny.png',
        mediaType: 'image/png',
        size: '1',
      },
    )
    const reservation = envelopeData(reserveJson)
    assert(/^[1-9][0-9]*$/.test(reservation.uploadId), JSON.stringify(reservation))
    assert(reservation.method === 'PUT', JSON.stringify(reservation))
    assert(/^https?:\/\//.test(reservation.url), JSON.stringify(reservation))
    const ifNoneMatch = Object.entries(reservation.headers || {}).find(
      ([name]) => name.toLowerCase() === 'if-none-match',
    )
    assert(ifNoneMatch?.[1] === '*', JSON.stringify(reservation))
    assert(!('bucket' in reservation) && !('key' in reservation), JSON.stringify(reservation))

    await expectHttpError(
      () =>
        ctx.call('POST', `/api/canvases/${canvas.id}/uploads`, {
          kind: 'TEXT',
          filename: 'note.md',
          mediaType: 'text/markdown',
          size: '1',
        }),
      { status: 400 },
    )
    await expectHttpError(
      () =>
        ctx.call('POST', `/api/canvases/${canvas.id}/uploads`, {
          kind: 'IMAGE',
          filename: 'tiny.png',
          mediaType: 'image/png',
          size: '1',
          key: 'client-controlled',
        }),
      { status: 400 },
    )
  },
})
