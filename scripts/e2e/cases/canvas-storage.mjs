import { createHash } from 'node:crypto'

import { assert, assertDecimalVersion, cid, envelopeData, expectHttpError } from '../lib/http.mjs'
import { registerCase } from '../lib/registry.mjs'

const UUID_TEXT = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

/** 1x1 透明 PNG（确定性字节，供 SHA-256 与直传校验）。 */
const PNG_BYTES = Uint8Array.from(
  Buffer.from(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==',
    'base64',
  ),
)

registerCase({
  id: 'canvas.storage_upload_contract',
  level: 'L1',
  requires: ['canvas-storage'],
  title: 'Canvas Resource 直读预签名与全局 Blob 存储边界',
  docs:
    '需 backend 启用 S3；验证公开 /api/s3/** 已移除，全局 upload reserve->PUT->complete 后可通过 /api/storage/blobs/{blobId}/download-url|preview-url 获取预签名 URL，CREATE_RESOURCE_NODE 在同一事务消费 READY 上传，Resource download/preview-url 只暴露 method/url/headers/expiresAt，TEXT 资源与未知资源明确拒绝，画布深删除后 404',
  async run(ctx) {
    const { json: createJson } = await ctx.call('POST', '/api/canvases', {
      title: 'e2e-resource-storage',
    })
    const canvas = envelopeData(createJson)
    assert(UUID_TEXT.test(canvas.id), JSON.stringify(canvas))
    assertDecimalVersion(canvas.version, 'canvas.version')
    assert(canvas.version === '0', JSON.stringify(canvas))
    assert(!('threadId' in canvas), `Canvas must not expose threadId: ${JSON.stringify(canvas)}`)

    // 公开 /api/s3/** 端点已删除。
    await expectHttpError(
      () =>
        ctx.call('POST', '/api/s3/presigned-uploads', {
          key: 'comfyui-inputs/e2e/upload/input.png',
          contentType: 'image/png',
        }),
      { status: 404 },
    )
    await expectHttpError(
      () =>
        ctx.call('POST', '/api/s3/presigned-downloads', {
          key: 'blobs/00000000-0000-0000-0000-000000000001/preview.webp',
        }),
      { status: 404 },
    )

    // 全局 upload：reserve -> 浏览器直传 PUT -> complete 绑定 blob。
    const sha256 = createHash('sha256').update(PNG_BYTES).digest('hex')
    const { json: reserveJson } = await ctx.call('POST', '/api/storage/uploads', {
      filename: 'tiny.png',
      mediaType: 'image/png',
      sizeBytes: PNG_BYTES.length,
      sha256,
    })
    const upload = envelopeData(reserveJson)
    assert(upload.state === 'PENDING', JSON.stringify(upload))
    assert(UUID_TEXT.test(upload.id), JSON.stringify(upload))
    assert(upload.presignedPut.method === 'PUT', JSON.stringify(upload))
    assert(/^https?:\/\//.test(upload.presignedPut.url), JSON.stringify(upload))
    assert(!('bucket' in upload) && !('key' in upload), JSON.stringify(upload))
    const putResponse = await fetch(upload.presignedPut.url, {
      method: 'PUT',
      headers: upload.presignedPut.headers || {},
      body: PNG_BYTES,
    })
    assert(putResponse.ok, `direct PUT HTTP ${putResponse.status}`)
    const { json: completeJson } = await ctx.call(
      'POST',
      `/api/storage/uploads/${upload.id}/complete`,
    )
    const completed = envelopeData(completeJson)
    assert(completed.state === 'READY', JSON.stringify(completed))
    assert(UUID_TEXT.test(completed.blobId), JSON.stringify(completed))
    assert(completed.presignedPut === null, JSON.stringify(completed))

    // Storage blob URL：通过 POST download-url 与 preview-url 签发预签名 URL。
    const { json: blobDownloadJson } = await ctx.call(
      'POST',
      `/api/storage/blobs/${completed.blobId}/download-url`,
    )
    const blobDownload = envelopeData(blobDownloadJson)
    assert(blobDownload.method === 'GET', JSON.stringify(blobDownload))
    assert(/^https?:\/\//.test(blobDownload.url), JSON.stringify(blobDownload))

    const { json: blobPreviewJson } = await ctx.call(
      'POST',
      `/api/storage/blobs/${completed.blobId}/preview-url`,
    )
    const blobPreview = envelopeData(blobPreviewJson)
    assert(blobPreview.method === 'GET', JSON.stringify(blobPreview))
    assert(/^https?:\/\//.test(blobPreview.url), JSON.stringify(blobPreview))

    // CREATE_RESOURCE_NODE 在同一事务消费 READY 上传：上传行消失，资源携带 blobId 与媒体事实。
    const nodeId = cid()
    const { json: commandJson } = await ctx.call(
      'POST',
      `/api/canvases/${canvas.id}/commands`,
      {
        expectedVersion: '0',
        idempotencyKey: cid(),
        commands: [
          {
            type: 'CREATE_RESOURCE_NODE',
            nodeId,
            name: 'tiny',
            uploadIds: [upload.id],
            transform: { x: 0, y: 0, width: 100, height: 100 },
          },
        ],
      },
    )
    const patch = envelopeData(commandJson)
    assertDecimalVersion(patch.baseVersion, 'patch.baseVersion')
    assertDecimalVersion(patch.version, 'patch.version')
    assert(patch.baseVersion === '0' && patch.version === '1', JSON.stringify(patch))
    const upsert = patch.nodes.find((item) => item.op === 'UPSERT' && item.node.id === nodeId)
    assert(upsert, JSON.stringify(patch.nodes))
    const resource = upsert.node.resources[0]
    assert(resource.blobId === completed.blobId, JSON.stringify(resource))
    assert(resource.kind === 'IMAGE', JSON.stringify(resource))
    assert(resource.mediaType === 'image/png', JSON.stringify(resource))
    // wire long：sizeBytes 是十进制字符串（非 JS number），值等于上传字节数。
    assertDecimalVersion(resource.sizeBytes, 'resource.sizeBytes')
    assert(resource.sizeBytes === String(PNG_BYTES.length), JSON.stringify(resource))

    // Resource 直读预签名：只暴露 method/url/headers/expiresAt，字节与上传一致。
    const { json: downloadJson } = await ctx.call(
      'POST',
      `/api/canvases/${canvas.id}/resources/${resource.id}/download-url`,
    )
    const download = envelopeData(downloadJson)
    assert(download.method === 'GET', JSON.stringify(download))
    assert(/^https?:\/\//.test(download.url), JSON.stringify(download))
    assert(!('bucket' in download) && !('key' in download), JSON.stringify(download))
    const downloadResponse = await fetch(download.url, { headers: download.headers || {} })
    assert(downloadResponse.ok, `download HTTP ${downloadResponse.status}`)
    const downloaded = new Uint8Array(await downloadResponse.arrayBuffer())
    assert(
      downloaded.length === PNG_BYTES.length &&
        downloaded.every((byte, index) => byte === PNG_BYTES[index]),
      'downloaded bytes differ from uploaded bytes',
    )
    const { json: previewJson } = await ctx.call(
      'POST',
      `/api/canvases/${canvas.id}/resources/${resource.id}/preview-url`,
    )
    const preview = envelopeData(previewJson)
    assert(preview.method === 'GET' && /^https?:\/\//.test(preview.url), JSON.stringify(preview))

    // TEXT 资源没有 blob 内容；未知 resource/canvas 与非法 UUID 明确拒绝。
    const textNodeId = cid()
    await ctx.call('POST', `/api/canvases/${canvas.id}/commands`, {
      expectedVersion: '1',
      idempotencyKey: cid(),
      commands: [
        {
          type: 'CREATE_TEXT_NODE',
          nodeId: textNodeId,
          name: 'note',
          markdown: 'hello',
          transform: { x: 0, y: 0, width: 100, height: 100 },
        },
      ],
    })
    const { json: snapshotJson } = await ctx.call('GET', `/api/canvases/${canvas.id}`)
    const snapshot = envelopeData(snapshotJson)
    const textNode = snapshot.nodes.find((item) => item.id === textNodeId)
    await expectHttpError(
      () =>
        ctx.call(
          'POST',
          `/api/canvases/${canvas.id}/resources/${textNode.resources[0].id}/download-url`,
        ),
      { status: 400 },
    )
    await expectHttpError(
      () =>
        ctx.call(
          'POST',
          `/api/canvases/${canvas.id}/resources/${cid()}/download-url`,
        ),
      { status: 404 },
    )
    await expectHttpError(
      () =>
        ctx.call('POST', `/api/canvases/${cid()}/resources/${resource.id}/download-url`),
      { status: 404 },
    )

    // 深删除画布后资源 URL 与画布本身都不可达。
    await ctx.call('DELETE', `/api/canvases/${canvas.id}`)
    await expectHttpError(() => ctx.call('GET', `/api/canvases/${canvas.id}`), { status: 404 })
  },
})
