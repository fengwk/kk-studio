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

const RESOURCE_FIELDS = [
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
]

/** 预签名响应只允许暴露这些键，绝不出现 bucket / 对象 key / 内部 URI。 */
const PRESIGNED_KEYS = ['method', 'url', 'headers', 'expiresAt']

function assertPresigned(value, label) {
  assert(value && typeof value === 'object', `${label}: ${JSON.stringify(value)}`)
  assert(
    Object.keys(value).every((key) => PRESIGNED_KEYS.includes(key)),
    `${label} exposes only presign facts: ${JSON.stringify(value)}`,
  )
  assert(value.method === 'GET', `${label} method ${value.method}`)
  assert(/^https?:\/\//.test(value.url), `${label} url ${value.url}`)
  return value
}

/** 预约并直传一份 PNG，返回 READY 上传（含 blobId）；同内容已去重命中时直接复用既有 blob。 */
async function uploadPng(ctx) {
  const sha256 = createHash('sha256').update(PNG_BYTES).digest('hex')
  const reserved = await ctx.call('POST', '/api/storage/uploads', {
    filename: 'tiny.png',
    mediaType: 'image/png',
    sizeBytes: PNG_BYTES.length,
    sha256,
  })
  const upload = envelopeData(reserved.json)
  assert(UUID_TEXT.test(upload.id), JSON.stringify(upload))
  assert(!('bucket' in upload) && !('key' in upload), JSON.stringify(upload))
  if (upload.state === 'READY') {
    // 内容哈希去重命中：同一份 ACTIVE 内容不重复上传。
    assert(UUID_TEXT.test(upload.blobId) && upload.presignedPut === null, JSON.stringify(upload))
    return upload
  }
  assert(upload.state === 'PENDING' && upload.blobId === null, JSON.stringify(upload))
  assert(upload.presignedPut?.method === 'PUT', JSON.stringify(upload))
  assert(/^https?:\/\//.test(upload.presignedPut.url), JSON.stringify(upload))
  const putResponse = await fetch(upload.presignedPut.url, {
    method: 'PUT',
    headers: upload.presignedPut.headers || {},
    body: PNG_BYTES,
  })
  assert(putResponse.ok, `direct PUT HTTP ${putResponse.status}`)
  const completed = envelopeData(
    (await ctx.call('POST', `/api/storage/uploads/${upload.id}/complete`)).json,
  )
  assert(
    completed.state === 'READY'
      && UUID_TEXT.test(completed.blobId)
      && completed.presignedPut === null,
    JSON.stringify(completed),
  )
  return completed
}

registerCase({
  id: 'canvas.storage_upload_contract',
  level: 'L1',
  requires: ['canvas-storage'],
  title: 'Canvas Resource 直读预签名与全局 Blob 存储边界',
  docs:
    '需 backend 启用 S3；验证公开 /api/s3/** 已移除，全局 upload reserve->PUT->complete 后可通过 /api/storage/blobs/{blobId}/download-url|preview-url 获取预签名 URL（原件 URL 另带权威 mediaType/sizeBytes），typed command CREATE_NODE 的 BLOB 资源槽位引用 READY blob、KEEP 复用同一 Resource 且不推进 revision，Resource download/preview-url 只暴露 method/url/headers/expiresAt，TEXT 资源与未知资源明确拒绝，画布深删除后 404',
  async run(ctx) {
    const created = await ctx.call('POST', '/api/canvases', {
      title: `e2e-resource-storage-${cid().slice(0, 8)}`,
    })
    assert(created.status === 201, `create canvas status ${created.status}`)
    const canvas = envelopeData(created.json)
    assert(UUID_TEXT.test(canvas.id), JSON.stringify(canvas))
    assertDecimalVersion(canvas.revision, 'canvas.revision')
    assert(canvas.revision === '0', JSON.stringify(canvas))
    assert(
      !('version' in canvas) && !('threadId' in canvas),
      `Canvas must expose revision only: ${JSON.stringify(canvas)}`,
    )

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

    const upload = await uploadPng(ctx)

    // Storage blob URL：POST download-url 携带 blob 权威媒体事实，preview-url 只签发 GET。
    const blobDownload = envelopeData(
      (await ctx.call('POST', `/api/storage/blobs/${upload.blobId}/download-url`)).json,
    )
    assert(
      blobDownload.method === 'GET'
        && /^https?:\/\//.test(blobDownload.url)
        && blobDownload.mediaType === 'image/png',
      JSON.stringify(blobDownload),
    )
    assertDecimalVersion(blobDownload.sizeBytes, 'blob sizeBytes')
    assert(blobDownload.sizeBytes === String(PNG_BYTES.length), JSON.stringify(blobDownload))
    const blobPreview = envelopeData(
      (await ctx.call('POST', `/api/storage/blobs/${upload.blobId}/preview-url`)).json,
    )
    assert(blobPreview.method === 'GET' && /^https?:\/\//.test(blobPreview.url), JSON.stringify(blobPreview))
    assert(blobPreview.mediaType === null && blobPreview.sizeBytes === null, JSON.stringify(blobPreview))

    // BLOB 资源槽位引用 READY blob：服务端生成 Resource id 并派生媒体事实。
    const nodeId = cid()
    const { json: commandJson } = await ctx.call(
      'POST',
      `/api/canvases/${canvas.id}/commands`,
      {
        idempotencyKey: cid(),
        commands: [
          {
            type: 'CREATE_NODE',
            nodeId,
            name: 'tiny',
            transform: { x: 0, y: 0, width: 100, height: 100 },
            resources: [{ kind: 'BLOB', name: 'tiny.png', blobId: upload.blobId }],
          },
        ],
      },
    )
    const patch = envelopeData(commandJson)
    assert(patch.revision === '1', JSON.stringify(patch))
    const upsert = patch.nodes.find((item) => item.op === 'UPSERT' && item.node.id === nodeId)
    assert(upsert, JSON.stringify(patch.nodes))
    const resource = upsert.node.resources[0]
    assert(
      Object.keys(resource).sort().join(',') === [...RESOURCE_FIELDS].sort().join(','),
      JSON.stringify(resource),
    )
    assert(
      resource.blobId === upload.blobId
        && resource.kind === 'IMAGE'
        && resource.mediaType === 'image/png'
        && resource.textContent === null
        && resource.name === 'tiny.png'
        && resource.ownerNodeId === nodeId
        && resource.canvasId === canvas.id
        && resource.resourceIndex === 0,
      JSON.stringify(resource),
    )
    // wire long：sizeBytes 是十进制字符串（非 JS number），值等于上传字节数。
    assertDecimalVersion(resource.sizeBytes, 'resource.sizeBytes')
    assert(resource.sizeBytes === String(PNG_BYTES.length), JSON.stringify(resource))

    // KEEP 复用同一 Resource 槽位且内容不变：批不推进 revision。
    const { json: keepJson } = await ctx.call('POST', `/api/canvases/${canvas.id}/commands`, {
      idempotencyKey: cid(),
      commands: [
        {
          type: 'SET_NODE_RESOURCES',
          nodeId,
          expectedResourceIds: [resource.id],
          resources: [{ kind: 'KEEP', resourceId: resource.id }],
        },
      ],
    })
    const keepPatch = envelopeData(keepJson)
    assert(
      keepPatch.revision === '1' && keepPatch.nodes.length === 0,
      JSON.stringify(keepPatch),
    )

    // Resource 直读预签名：只暴露 method/url/headers/expiresAt，字节与上传一致。
    const download = assertPresigned(
      envelopeData((await ctx.call('POST', `/api/canvases/${canvas.id}/resources/${resource.id}/download-url`)).json),
      'resource download url',
    )
    const downloadResponse = await fetch(download.url, { headers: download.headers || {} })
    assert(downloadResponse.ok, `download HTTP ${downloadResponse.status}`)
    const downloaded = new Uint8Array(await downloadResponse.arrayBuffer())
    assert(
      downloaded.length === PNG_BYTES.length &&
        downloaded.every((byte, index) => byte === PNG_BYTES[index]),
      'downloaded bytes differ from uploaded bytes',
    )
    assertPresigned(
      envelopeData((await ctx.call('POST', `/api/canvases/${canvas.id}/resources/${resource.id}/preview-url`)).json),
      'resource preview url',
    )

    // TEXT 资源没有 blob 内容；未知 resource/canvas 与非法 UUID 明确拒绝。
    const textNodeId = cid()
    const { json: textJson } = await ctx.call('POST', `/api/canvases/${canvas.id}/commands`, {
      idempotencyKey: cid(),
      commands: [
        {
          type: 'CREATE_NODE',
          nodeId: textNodeId,
          name: 'note',
          transform: { x: 0, y: 0, width: 100, height: 100 },
          resources: [{ kind: 'TEXT', name: 'body', textContent: 'hello' }],
        },
      ],
    })
    const textPatch = envelopeData(textJson)
    assert(textPatch.revision === '2', JSON.stringify(textPatch))
    const textResourceId = textPatch.nodes.find((item) => item.node?.id === textNodeId).node.resources[0].id
    await expectHttpError(
      () => ctx.call('POST', `/api/canvases/${canvas.id}/resources/${textResourceId}/download-url`),
      { status: 400 },
    )
    await expectHttpError(
      () => ctx.call('POST', `/api/canvases/${canvas.id}/resources/${cid()}/download-url`),
      { status: 404 },
    )
    await expectHttpError(
      () => ctx.call('POST', `/api/canvases/${cid()}/resources/${resource.id}/download-url`),
      { status: 404 },
    )

    // 深删除画布后资源 URL 与画布本身都不可达。
    await ctx.call('DELETE', `/api/canvases/${canvas.id}`)
    await expectHttpError(() => ctx.call('GET', `/api/canvases/${canvas.id}`), { status: 404 })
    await expectHttpError(
      () => ctx.call('POST', `/api/canvases/${canvas.id}/resources/${resource.id}/download-url`),
      { status: 404 },
    )
  },
})
