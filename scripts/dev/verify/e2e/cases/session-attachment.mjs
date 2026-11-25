import { assert, cid, expectHttpError } from '../lib/http.mjs'
import { createWorkspaceSession } from '../lib/harness.mjs'
import { TINY_PNG, uploadPng } from '../lib/image-attachment.mjs'
import { createCaseAdder } from '../lib/registry.mjs'

export const cases = []
const registerCase = createCaseAdder(cases)

registerCase({
  id: 'session.attachment_sign_and_stream',
  level: 'L1',
  title: '会话附件持有关系、下载签名与同源流',
  docs: '上传 PNG 并通过 Chat Space 创建持有它的会话；验证下载签名、预览签名和同源流读取，无持有关系时返回 404。无需真实 Provider。',
  async run(ctx) {
    const spaceId = await ctx.createSpace()
    const upload = await uploadPng(ctx)
    const { sessionId } = await createWorkspaceSession(ctx, spaceId, {
      idempotencyKey: cid(),
      message: [{ type: 'TEXT', text: 'Image attachment.' }, { type: 'ATTACHMENT', uploadId: upload.id }],
    })
    const path = `/api/harness/sessions/${sessionId}/blobs/${upload.blobId}`
    for (const operation of ['download-url', 'preview-url']) {
      const { json: signed } = await ctx.call('POST', `${path}/${operation}`)
      assert(signed.method === 'GET' && /^https?:\/\//.test(signed.url) && signed.headers, `invalid ${operation} signing response`)
      const response = await fetch(signed.url, { headers: signed.headers, signal: AbortSignal.timeout(15_000) })
      assert(response.ok, `signed ${operation} returned HTTP ${response.status}`)
      const bytes = Buffer.from(await response.arrayBuffer())
      if (operation === 'download-url') assert(bytes.equals(TINY_PNG), 'signed download differs from uploaded PNG')
      else assert(bytes.length > 0 && response.headers.get('content-type')?.startsWith('image/'), 'signed preview did not return an image')
    }
    const stream = await ctx.call('GET', `/api/harness/sessions/${sessionId}/resources/${upload.blobId}`)
    assert(stream.status === 200 && stream.headers.get('content-type') === 'image/png' && stream.text.startsWith('\uFFFDPNG'),
      'same-origin stream did not return the PNG')

    const unrelated = await createWorkspaceSession(ctx, spaceId, {
      idempotencyKey: cid(), message: [{ type: 'TEXT', text: 'No attachment.' }],
    })
    await expectHttpError(
      () => ctx.call('POST', `/api/harness/sessions/${unrelated.sessionId}/blobs/${upload.blobId}/download-url`),
      { status: 404, code: 'not_found' },
    )
  },
})
