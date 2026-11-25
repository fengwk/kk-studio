import { createHash } from 'node:crypto'
import { assert } from './http.mjs'

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

export const TINY_PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==',
  'base64',
)

export async function uploadPng(ctx, content = TINY_PNG, filename = 'tiny.png') {
  // PNG IHDR dimensions; these fixtures are uploaded without resizing.
  const width = content.readUInt32BE(16)
  const height = content.readUInt32BE(20)
  const sha256 = createHash('sha256').update(content).digest('hex')
  const { json: pending } = await ctx.call('POST', '/api/platform/storage/uploads', {
    filename,
    mediaType: 'image/png',
    format: 'png',
    originalWidth: width,
    originalHeight: height,
    storedWidth: width,
    storedHeight: height,
    sizeBytes: String(content.length),
    sha256,
  })
  assert(UUID.test(pending.id) && !('bucket' in pending) && !('key' in pending), 'invalid PNG upload reservation')
  if (pending.type === 'READY') {
    return assertReady(pending)
  }
  assert(pending.type === 'PENDING' && pending.presignedPut?.method === 'PUT' && !('blobId' in pending), 'expected a pending PNG PUT upload')
  const put = await fetch(pending.presignedPut.url, {
    method: 'PUT',
    headers: pending.presignedPut.headers || {},
    body: content,
    signal: AbortSignal.timeout(15_000),
  })
  assert(put.ok, `presigned PNG PUT failed: ${put.status}`)
  const { json: ready } = await ctx.call(
    'POST',
    `/api/platform/storage/uploads/${encodeURIComponent(String(pending.id))}/complete`,
  )
  return assertReady(ready)
}

function assertReady(upload) {
  assert(upload.type === 'READY' && UUID.test(upload.id) && UUID.test(upload.blobId) && !('presignedPut' in upload), 'expected a READY PNG upload with a blob ID')
  return upload
}
