import { assert, envelopeData, expectHttpError } from '../lib/http.mjs'
import { registerCase } from '../lib/registry.mjs'

registerCase({
  id: 'canvas.storage_upload_contract',
  level: 'L1',
  requires: ['canvas-storage'],
  title: 'Canvas Resource 与通用 S3 预签名命名空间边界',
  docs:
    '需 backend 启用 S3；验证 ComfyUI 临时输入可预签名，Canvas key 无法通过通用 S3 端点签名，IMAGE reserve 隐藏 bucket/key 且保持 create-only，TEXT 与客户端 key 注入均 400',
  async run(ctx) {
    const { json: createJson } = await ctx.call('POST', '/api/canvases', {
      title: 'e2e-resource-storage',
    })
    const canvas = envelopeData(createJson)
    assert(/^[1-9][0-9]*$/.test(canvas.id), JSON.stringify(canvas))

    const { json: comfyuiPresignJson } = await ctx.call(
      'POST',
      '/api/s3/presigned-uploads',
      {
        key: 'comfyui-inputs/e2e/upload/input.png',
        contentType: 'image/png',
      },
    )
    const comfyuiPresign = envelopeData(comfyuiPresignJson)
    assert(comfyuiPresign.key === 'comfyui-inputs/e2e/upload/input.png', JSON.stringify(comfyuiPresign))
    assert(comfyuiPresign.method === 'PUT', JSON.stringify(comfyuiPresign))
    await expectHttpError(
      () =>
        ctx.call('POST', '/api/s3/presigned-uploads', {
          key: `canvases/${canvas.id}/resources/1/original`,
          contentType: 'image/png',
        }),
      { status: 400 },
    )
    await expectHttpError(
      () =>
        ctx.call('POST', '/api/s3/presigned-downloads', {
          key: `canvases/${canvas.id}/resources/1/preview.webp`,
        }),
      { status: 400 },
    )

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
