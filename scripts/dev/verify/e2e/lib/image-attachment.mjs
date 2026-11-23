import { createHash } from 'node:crypto'
import { assert } from './http.mjs'

export async function uploadPngAttachment(ctx, content) {
  // PNG IHDR dimensions; these fixtures are uploaded without resizing.
  const width = content.readUInt32BE(16)
  const height = content.readUInt32BE(20)
  const sha256 = createHash('sha256').update(content).digest('hex')
  const { json: pending } = await ctx.call('POST', '/api/platform/storage/uploads', {
    filename: 'pixel.png',
    mediaType: 'image/png',
    format: 'png',
    originalWidth: width,
    originalHeight: height,
    storedWidth: width,
    storedHeight: height,
    sizeBytes: String(content.length),
    sha256,
  })
  assert(pending.type === 'PENDING' && pending.presignedPut?.method === 'PUT', 'expected a pending PNG PUT upload')
  const put = await fetch(pending.presignedPut.url, {
    method: 'PUT',
    headers: pending.presignedPut.headers || {},
    body: content,
  })
  assert(put.ok, `presigned PNG PUT failed: ${put.status}`)
  const { json: ready } = await ctx.call(
    'POST',
    `/api/platform/storage/uploads/${encodeURIComponent(String(pending.id))}/complete`,
  )
  assert(ready.type === 'READY' && typeof ready.id === 'string' && ready.id, 'PNG upload did not become READY')
  return { type: 'ATTACHMENT', uploadId: ready.id }
}
