import { assert, assertDecimalVersion, cid, expectHttpError } from '../lib/http.mjs'
import { TINY_PNG, uploadPng } from '../lib/image-attachment.mjs'
import { createCaseAdder } from '../lib/registry.mjs'
import { createCanvas } from './canvas-api.mjs'

export const cases = []
const registerCase = createCaseAdder(cases)

const RESOURCE_FIELDS = [
  'type',
  'id',
  'canvasId',
  'ownerNodeId',
  'resourceIndex',
  'blobId',
  'name',
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
  assert(value && typeof value === 'object', `${label}: missing signing response`)
  assert(
    Object.keys(value).every((key) => PRESIGNED_KEYS.includes(key)),
    `${label} exposes unexpected signing fields: ${Object.keys(value).join(',')}`,
  )
  assert(value.method === 'GET', `${label} method ${value.method}`)
  assert(/^https?:\/\//.test(value.url), `${label}: invalid signing URL`)
  return value
}

registerCase({
  id: 'canvas.storage_upload_contract',
  level: 'L1',
  title: 'Canvas 持有者 Blob 签名与上传契约',
  docs:
    '验证 upload reserve->PUT->complete 后须先经 typed command CREATE_NODE 的 BLOB 槽位持有 READY upload（uploadId），再由持有该 Blob 的 Canvas 经 POST /api/canvas/canvases/{canvasId}/blobs/{blobId}/download-url|preview-url 获取预签名 URL，typed command KEEP 复用同一 Resource 且不推进 revision，Canvas blob download/preview-url 只暴露 method/url/headers/expiresAt，TEXT、不存在的 Blob 与无持有关系的 Canvas 明确拒绝',
  async run(ctx) {
    const canvas = await createCanvas(ctx, `e2e-resource-storage-${cid().slice(0, 8)}`)
    const upload = await uploadPng(ctx)

    // BLOB 资源槽位消费 READY uploadId：服务端生成 Resource 并派生 blob 媒体事实。
    const nodeId = cid()
    const { json: commandJson } = await ctx.call(
      'POST',
      `/api/canvas/canvases/${canvas.id}/commands`,
      {
        idempotencyKey: cid(),
        commands: [
          {
            type: 'CREATE_NODE',
            nodeId,
            name: 'tiny',
            transform: { x: 0, y: 0, width: 100, height: 100 },
            resources: [{ type: 'BLOB', name: 'tiny.png', uploadId: upload.id }],
          },
        ],
      },
    )
    const patch = commandJson
    assert(patch.revision === '1', JSON.stringify(patch))
    const upsert = patch.nodes.find((item) => item.type === 'UPSERT' && item.node.id === nodeId)
    assert(upsert, JSON.stringify(patch.nodes))
    const resource = upsert.node.resources[0]
    assert(
      Object.keys(resource).sort().join(',') === [...RESOURCE_FIELDS].sort().join(','),
      JSON.stringify(resource),
    )
    assert(
      resource.blobId === upload.blobId
        && resource.type === 'BLOB'
        && resource.kind === 'IMAGE'
        && resource.mediaType === 'image/png'
        && resource.name === 'tiny.png'
        && resource.ownerNodeId === nodeId
        && resource.canvasId === canvas.id
        && resource.resourceIndex === 0,
      JSON.stringify(resource),
    )
    // wire long：sizeBytes 是十进制字符串（非 JS number），值等于上传字节数。
    assertDecimalVersion(resource.sizeBytes, 'resource.sizeBytes')
    assert(resource.sizeBytes === String(TINY_PNG.length), JSON.stringify(resource))

    // KEEP 复用同一 Resource 槽位且内容不变：批不推进 revision。
    const { json: keepJson } = await ctx.call('POST', `/api/canvas/canvases/${canvas.id}/commands`, {
      idempotencyKey: cid(),
      commands: [
        {
          type: 'SET_NODE_RESOURCES',
          nodeId,
          expectedResourceIds: [resource.id],
          resources: [{ type: 'KEEP', resourceId: resource.id }],
        },
      ],
    })
    const keepPatch = keepJson
    assert(
      keepPatch.revision === '1' && keepPatch.nodes.length === 0,
      JSON.stringify(keepPatch),
    )

    // Canvas 持有的 Blob 直读预签名：只暴露 method/url/headers/expiresAt，字节与上传一致。
    const download = assertPresigned(
      (await ctx.call('POST', `/api/canvas/canvases/${canvas.id}/blobs/${resource.blobId}/download-url`)).json,
      'canvas blob download url',
    )
    const downloadResponse = await fetch(download.url, { headers: download.headers || {} })
    assert(downloadResponse.ok, `download HTTP ${downloadResponse.status}`)
    const downloaded = new Uint8Array(await downloadResponse.arrayBuffer())
    assert(
      downloaded.length === TINY_PNG.length &&
        downloaded.every((byte, index) => byte === TINY_PNG[index]),
      'downloaded bytes differ from uploaded bytes',
    )
    assertPresigned(
      (await ctx.call('POST', `/api/canvas/canvases/${canvas.id}/blobs/${resource.blobId}/preview-url`)).json,
      'canvas blob preview url',
    )

    // TEXT 资源不产生 Blob；未知 Blob 与未持有该 Blob 的有效 Canvas 均不可取址。
    const textNodeId = cid()
    const { json: textJson } = await ctx.call('POST', `/api/canvas/canvases/${canvas.id}/commands`, {
      idempotencyKey: cid(),
      commands: [
        {
          type: 'CREATE_NODE',
          nodeId: textNodeId,
          name: 'note',
          transform: { x: 0, y: 0, width: 100, height: 100 },
          resources: [{ type: 'TEXT', name: 'body', textContent: 'hello' }],
        },
      ],
    })
    const textPatch = textJson
    assert(textPatch.revision === '2', JSON.stringify(textPatch))
    assert(textPatch.nodes.find((item) => item.node?.id === textNodeId).node.resources[0].type === 'TEXT')
    await expectHttpError(
      () => ctx.call('POST', `/api/canvas/canvases/${canvas.id}/blobs/not-a-uuid/download-url`),
      { status: 400, code: 'validation' },
    )
    await expectHttpError(
      () => ctx.call('POST', `/api/canvas/canvases/${canvas.id}/blobs/${cid()}/download-url`),
      { status: 404, code: 'not_found' },
    )
    const otherCanvas = await createCanvas(ctx, `e2e-unrelated-canvas-${cid().slice(0, 8)}`)
    await expectHttpError(
      () => ctx.call('POST', `/api/canvas/canvases/${otherCanvas.id}/blobs/${resource.blobId}/download-url`),
      { status: 404, code: 'not_found' },
    )

  },
})
