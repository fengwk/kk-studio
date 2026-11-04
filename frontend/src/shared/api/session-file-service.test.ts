import { describe, expect, it, vi } from 'vitest'
import { ApiError, isNotFoundError } from '@/shared/api/client'
import {
  attachmentFileName,
  createSessionFileService,
  type SessionFileOwner,
} from '@/shared/api/session-file-service'
import { createMockHttpClient } from '@/test-support/mock-http-client'

const SESSION_ID = '11111111-2222-4333-8444-555555555555'
const OWNER: SessionFileOwner = { kind: 'CHAT', id: 'a1000000-0000-4000-8000-000000000001' }
const FILE_PATH = `vfs:/sessions/${SESSION_ID}/notes.txt`

describe('sessionFileService', () => {
  /**
   * 测试意图：列表缺省 path 表示会话根目录，参数值必须是 ownerKind/ownerId；
   * 显式目录坐标原样传给后端，sessionId 在路径段中安全转义。
   */
  it('lists session files with owner parameters and an optional vfs directory', async () => {
    const http = createMockHttpClient()
    const service = createSessionFileService(http)

    await service.listSessionFiles(SESSION_ID, OWNER)
    await service.listSessionFiles('session /1', OWNER, 'vfs:/sessions/session /1/docs/')

    expect(http.get).toHaveBeenNthCalledWith(
      1,
      '/sessions/11111111-2222-4333-8444-555555555555/files',
      { params: { ownerKind: 'CHAT', ownerId: OWNER.id, path: undefined } },
    )
    expect(http.get).toHaveBeenNthCalledWith(
      2,
      '/sessions/session%20%2F1/files',
      { params: { ownerKind: 'CHAT', ownerId: OWNER.id, path: 'vfs:/sessions/session /1/docs/' } },
    )
  })

  /**
   * 测试意图：文本窗口把 offset/limit/columnOffset 原样传递，缺省坐标不出现在参数中，
   * 由后端套用 1/2000 的默认值。
   */
  it('reads a bounded text window through the content endpoint', async () => {
    const http = createMockHttpClient()
    const service = createSessionFileService(http)

    await service.readSessionFileContent(SESSION_ID, OWNER, FILE_PATH)
    await service.readSessionFileContent(SESSION_ID, OWNER, FILE_PATH, {
      offset: 401,
      limit: 200,
      columnOffset: 3,
    })

    expect(http.get).toHaveBeenNthCalledWith(
      1,
      '/sessions/11111111-2222-4333-8444-555555555555/files/content',
      {
        params: {
          ownerKind: 'CHAT',
          ownerId: OWNER.id,
          path: FILE_PATH,
          offset: undefined,
          limit: undefined,
          columnOffset: undefined,
        },
      },
    )
    expect(http.get).toHaveBeenNthCalledWith(
      2,
      '/sessions/11111111-2222-4333-8444-555555555555/files/content',
      {
        params: {
          ownerKind: 'CHAT',
          ownerId: OWNER.id,
          path: FILE_PATH,
          offset: 401,
          limit: 200,
          columnOffset: 3,
        },
      },
    )
  })

  /**
   * 测试意图：下载端点返回裸字节而非 API 信封；成功时保留附件文件名，
   * 失败时把 JSON 信封翻译成带 HTTP 状态的 ApiError，供面板区分 404 与 422。
   */
  it('downloads bytes and preserves the attachment file name', async () => {
    const http = createMockHttpClient()
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(
      new Response(new Uint8Array([1, 2, 3]), {
        status: 200,
        headers: {
          'Content-Type': 'text/plain',
          'Content-Disposition': 'attachment; filename="notes.txt"; filename*=UTF-8\'\'notes.txt',
        },
      }),
    )
    const service = createSessionFileService(http, fetchImpl)

    const download = await service.downloadSessionFile(SESSION_ID, OWNER, FILE_PATH)

    expect(fetchImpl).toHaveBeenCalledWith(
      `/api/sessions/${SESSION_ID}/files/download?ownerKind=CHAT&ownerId=${OWNER.id}`
        + `&path=${encodeURIComponent(FILE_PATH).replace(/%20/g, '+')}`,
      { headers: { 'Accept-Language': 'zh-CN' }, signal: undefined },
    )
    expect(download.fileName).toBe('notes.txt')
    expect(Array.from(new Uint8Array(await download.blob.arrayBuffer()))).toEqual([1, 2, 3])
  })

  it('rejects a download with the backend error envelope', async () => {
    const http = createMockHttpClient()
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(
      new Response(JSON.stringify({
        status: 422,
        code: 'UNPROCESSABLE_CONTENT',
        message: 'HTTP error (status 422).',
        data: null,
        errors: { detail: 'file appears to be binary' },
      }), { status: 422, headers: { 'Content-Type': 'application/json' } }),
    )
    const service = createSessionFileService(http, fetchImpl)

    const failure = await service.downloadSessionFile(SESSION_ID, OWNER, FILE_PATH)
      .catch((error: unknown) => error)

    expect(failure).toBeInstanceOf(ApiError)
    expect((failure as ApiError).status).toBe(422)
    expect((failure as ApiError).errors?.detail).toBe('file appears to be binary')
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
