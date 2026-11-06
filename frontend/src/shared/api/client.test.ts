import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  ApiError, apiErrorDetail, executeFetch, isConflictError, isConflictReason, isNotFoundError, request, url,
} from './client'
import { setLocale } from '@/shared/i18n'

afterEach(() => {
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

describe('HTTP client', () => {
  // 类型与编码都来自实际生成的路由；通配符只保留段间斜线。
  it('builds typed URLs with path and query encoding', () => {
    expect(url('DELETE /api/platform/models/{*name}', {
      path: { name: 'a/b c' }, query: { expectedVersion: '1' },
    })).toBe('/api/platform/models/a/b%20c?expectedVersion=1')
    expect(url('GET /api/canvas/canvases/{canvasId}', { path: { canvasId: 'a/b c' } }))
      .toBe('/api/canvas/canvases/a%2Fb%20c')
    // 类型断言由 tsc 检查，不执行无效请求。
    // eslint-disable-next-line no-constant-condition
    if (false) {
      // @ts-expect-error 未知路由不能调用。
      request('GET /api/not-a-route', {})
      // @ts-expect-error 必填查询参数不能省略。
      request('DELETE /api/platform/providers/{name}', { path: { name: 'x' } })
      // @ts-expect-error 路径参数类型由路由表约束。
      url('GET /api/canvas/canvases/{canvasId}', { path: { canvasId: 1 } })
    }
  })

  // 成功体就是资源，空体无值。
  it('reads resource bodies', async () => {
    setLocale('en-US')
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response('{"title":"A"}', { headers: { 'Content-Type': 'application/json' } }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)
    expect(await request('POST /api/canvas/canvases', { body: { title: 'A' } }))
      .toEqual({ title: 'A' })
    expect(fetchMock.mock.calls[0]![0]).toBe('/api/canvas/canvases')
    expect(fetchMock.mock.calls[0]![1]).toMatchObject({
      method: 'POST', body: '{"title":"A"}',
      headers: { 'Accept-Language': 'en-US', 'Content-Type': 'application/json' },
    })
    await expect(request('DELETE /api/platform/models/{*name}', {
      path: { name: 'a' }, query: { expectedVersion: '1' },
    })).resolves.toBeUndefined()
    expect(fetchMock.mock.calls[1]![0]).toBe('/api/platform/models/a?expectedVersion=1')
    expect(fetchMock.mock.calls[1]![1]).toMatchObject({ method: 'DELETE' })
  })

  // problem 扩展字段位于顶层，非 problem 不得被当作错误信封解析。
  it('maps problem details and uses a safe fallback for other responses', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({
        title: 'Conflict', code: 'conflict', reason: 'STALE', detail: 'changed',
        errors: { conflicts: [] },
      }), { status: 409, headers: { 'Content-Type': 'application/problem+json' } }))
      .mockResolvedValueOnce(new Response('{"title":"not a problem"}', {
        status: 502, headers: { 'Content-Type': 'application/json' },
      })))
    let error: unknown
    try {
      await request('GET /api/canvas/canvases', {})
    } catch (caught) {
      error = caught
    }
    expect(error).toMatchObject({
      message: 'Conflict', status: 409, code: 'conflict', reason: 'STALE',
      detail: 'changed', errors: { conflicts: [] },
    })
    expect(isConflictReason(error, 'STALE')).toBe(true)
    expect(apiErrorDetail(error)).toBe('changed')
    expect(isConflictReason(new ApiError('x', 409, 'conflict', { reason: 'STALE' }), 'STALE'))
      .toBe(false)
    await expect(request('GET /api/canvas/canvases', {})).rejects.toMatchObject({
      status: 502, code: 'upstream_failed',
    })
  })

  // 状态分类仅接受对应 HTTP 状态的 ApiError，不把普通错误或相似对象当作服务端回执。
  it('classifies conflict and not-found errors by status', () => {
    const conflict = new ApiError('conflict', 409, 'conflict')
    const notFound = new ApiError('missing', 404, 'not_found')
    expect(isConflictError(conflict)).toBe(true)
    expect(isConflictError(notFound)).toBe(false)
    expect(isNotFoundError(notFound)).toBe(true)
    expect(isNotFoundError(conflict)).toBe(false)
    expect(isConflictError(new ApiError('network'))).toBe(false)
    expect(isNotFoundError(new ApiError('network'))).toBe(false)
    expect(isConflictError({ status: 409 })).toBe(false)
    expect(isNotFoundError({ status: 404 })).toBe(false)
    expect(isConflictError(new Error('conflict'))).toBe(false)
    expect(isNotFoundError(null)).toBe(false)
  })

  // 解析器接受原始文本，下载与脚本通过媒体类型区分。
  it('handles custom JSON, text and binary responses', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(new Response('{"n":1}', { headers: { 'Content-Type': 'application/json' } }))
      .mockResolvedValueOnce(new Response('script', { headers: { 'Content-Type': 'application/octet-stream' } }))
      .mockResolvedValueOnce(new Response('bytes', { headers: { 'Content-Type': 'text/plain' } })))
    const parser = vi.fn(() => ['parsed'])
    expect(await request('GET /api/canvas/canvases', {}, { jsonParser: parser })).toEqual(['parsed'])
    expect(parser).toHaveBeenCalledWith('{"n":1}')
    expect(await request('GET /api/platform/daemon-uninstall-scripts/{operatingSystem}', {
      path: { operatingSystem: 'linux' },
    })).toBe('script')
    const blob = await request('GET /api/harness/sessions/{sessionId}/files/download', {
      path: { sessionId: 's' }, query: { path: 'x' },
    })
    expect(await blob.text()).toBe('bytes')
  })

  // 空体和非法 problem 响应均有确定回退，不读取旧信封。
  it('handles empty and malformed HTTP bodies', async () => {
    const transport = vi.fn()
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(new Response('', { headers: { 'Content-Type': 'application/json' } }))
      .mockResolvedValueOnce(new Response('', { headers: { 'Content-Type': 'text/plain' } }))
      .mockResolvedValueOnce(new Response('', { headers: { 'Content-Type': 'application/octet-stream' } }))
      .mockResolvedValueOnce(new Response('{', {
        status: 404, headers: { 'Content-Type': 'application/problem+json; charset=utf-8' },
      }))
    vi.stubGlobal('fetch', transport)
    await expect(request('GET /api/canvas/canvases', {})).resolves.toBeUndefined()
    await expect(request('GET /api/canvas/canvases', {})).resolves.toBeUndefined()
    await expect(request('GET /api/platform/daemon-uninstall-scripts/{operatingSystem}', {
      path: { operatingSystem: 'linux' },
    })).resolves.toBeUndefined()
    await expect(request('GET /api/harness/sessions/{sessionId}/files/download', {
      path: { sessionId: 's' }, query: { path: 'x' },
    })).resolves.toBeUndefined()
    await expect(request('GET /api/canvas/canvases', {}))
      .rejects.toMatchObject({ status: 404, code: 'not_found' })
  })

  // 底层执行器直接透传二进制体，不误用 JSON.stringify。
  it('preserves binary bodies in the fetch executor', async () => {
    const transport = vi.fn(async (_url: string, _init: RequestInit) =>
      new Response('plain', { headers: { 'Content-Type': 'text/plain' } }))
    vi.stubGlobal('fetch', transport)
    const form = new FormData()
    form.set('field', 'value')
    await expect(executeFetch('/api/form', { method: 'POST', body: form })).resolves.toBe('plain')
    expect(transport.mock.calls[0]![1].body).toBe(form)
    await executeFetch('/api/blob', { method: 'POST', body: new Blob(['data']) })
    await executeFetch('/api/buffer', { method: 'POST', body: new ArrayBuffer(2) })
    await executeFetch('/api/form-url', {
      method: 'POST', body: new URLSearchParams({ a: 'b' }),
    })
    expect(transport.mock.calls[3]![1].body).toBeInstanceOf(URLSearchParams)
  })

  // 本地取消在 fetch 前生效；缺省 problem 成员从状态回退。
  it('handles already-aborted requests and incomplete problem details', async () => {
    const transport = vi.fn().mockResolvedValue(new Response('{}', {
      status: 409, headers: { 'Content-Type': 'application/problem+json' },
    }))
    vi.stubGlobal('fetch', transport)
    await expect(request('GET /api/canvas/canvases', {}, { signal: AbortSignal.abort() }))
      .rejects.toMatchObject({ name: 'AbortError' })
    expect(transport).not.toHaveBeenCalled()
    await expect(request('GET /api/canvas/canvases', {}))
      .rejects.toMatchObject({ status: 409, code: 'conflict' })
  })

  // 超时和调用方取消在响应体读取期间仍生效。
  it('aborts timed out and externally cancelled requests', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('fetch', vi.fn((_url, init: RequestInit) => new Promise((_resolve, reject) => {
      init.signal?.addEventListener('abort', () => reject(init.signal?.reason))
    })))
    const timed = request('GET /api/canvas/canvases', {})
    const assertion = expect(timed).rejects.toMatchObject({ code: 'timeout' })
    await vi.advanceTimersByTimeAsync(60000)
    await assertion
    const controller = new AbortController()
    const aborted = request('GET /api/canvas/canvases', {}, { signal: controller.signal, timeoutMs: 0 })
    controller.abort()
    await expect(aborted).rejects.toMatchObject({ name: 'AbortError' })
  })

  // 收到响应头后仍可能等待响应体，超时必须覆盖这段时间。
  it('keeps the timeout active while reading the response body', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init: RequestInit) => ({
      ok: true,
      status: 200,
      headers: new Headers({ 'Content-Type': 'application/json' }),
      text: () => new Promise((_resolve, reject) => {
        init.signal?.addEventListener('abort', () => reject(init.signal?.reason))
      }),
    })))
    const pending = request('GET /api/canvas/canvases', {}, { timeoutMs: 5 })
    const assertion = expect(pending).rejects.toMatchObject({ code: 'timeout' })
    await vi.advanceTimersByTimeAsync(5)
    await assertion
  })

  // 网络失败不依赖服务端错误信封。
  it('normalizes network failures', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('offline')))
    await expect(request('GET /api/canvas/canvases', {}))
      .rejects.toMatchObject({ name: 'ApiError', code: 'network_error', message: 'offline' })
  })
})
