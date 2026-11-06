import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, isNotFoundError } from '@/shared/api/client'
import {
  attachmentFileName,
  downloadSessionFile,
} from '@/shared/api/session-file-service'

const SESSION_ID = '11111111-2222-4333-8444-555555555555'
const FILE_PATH = `vfs:/sessions/${SESSION_ID}/notes.txt`

describe('sessionFileService', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  /**
   * 测试意图：下载返回裸字节而非 API 信封；成功时保留附件文件名。
   */
  it('downloads bytes and preserves the attachment file name', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(
      new Response(new Uint8Array([1, 2, 3]), {
        status: 200,
        headers: {
          'Content-Type': 'text/plain',
          'Content-Disposition': 'attachment; filename="notes.txt"; filename*=UTF-8\'\'notes.txt',
        },
      }),
    )
    vi.stubGlobal('fetch', fetchImpl)

    const download = await downloadSessionFile(SESSION_ID, FILE_PATH)

    expect(fetchImpl).toHaveBeenCalledWith(
      expect.stringContaining(`/api/harness/sessions/${SESSION_ID}/files/download?`),
      expect.objectContaining({ headers: expect.objectContaining({ 'Accept-Language': 'zh-CN' }) }),
    )
    expect(download.fileName).toBe('notes.txt')
    expect(Array.from(new Uint8Array(await download.blob.arrayBuffer()))).toEqual([1, 2, 3])
  })

  // 测试意图：下载失败时服务端返回 problem+json，客户端构造带 status、code 与 detail 的 ApiError
  it('rejects a download with the backend problem+json error', async () => {
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockResolvedValue(
      new Response(JSON.stringify({
        type: 'about:blank',
        title: 'HTTP error (status 422).',
        status: 422,
        code: 'validation',
        detail: 'file appears to be binary',
      }), { status: 422, headers: { 'Content-Type': 'application/problem+json' } }),
    ))

    const failure = await downloadSessionFile(SESSION_ID, FILE_PATH).catch((error: unknown) => error)

    expect(failure).toBeInstanceOf(ApiError)
    expect((failure as ApiError).status).toBe(422)
    expect((failure as ApiError).code).toBe('validation')
    expect((failure as ApiError).detail).toBe('file appears to be binary')
    expect(isNotFoundError(failure)).toBe(false)
  })

  it('falls back to the request path when the attachment header is absent', () => {
    expect(attachmentFileName(null, FILE_PATH)).toBe('notes.txt')
    expect(attachmentFileName('attachment; filename="report.md"', FILE_PATH)).toBe('report.md')
    expect(attachmentFileName(
      "attachment; filename=\"fallback.txt\"; filename*=UTF-8''%E6%8A%A5%E5%91%8A.txt",
      FILE_PATH,
    )).toBe('报告.txt')
  })
})
