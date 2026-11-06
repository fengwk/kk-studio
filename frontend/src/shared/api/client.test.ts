import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  ApiError, apiClient, apiErrorDetail, isConflictError, isConflictReason, isNotFoundError, request, url,
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
    expect(url('GET /api/canvases/{canvasId}', { path: { canvasId: 'a/b c' } }))
      .toBe('/api/canvases/a%2Fb%20c')
    // 类型断言由 tsc 检查，不执行无效请求。
    // eslint-disable-next-line no-constant-condition
    if (false) {
      // @ts-expect-error 未知路由不能调用。
      request('GET /api/not-a-route', {})
      // @ts-expect-error 必填查询参数不能省略。
      request('DELETE /api/providers/{name}', { path: { name: 'x' } })
      // @ts-expect-error 路径参数类型由路由表约束。
      url('GET /api/canvases/{canvasId}', { path: { canvasId: 1 } })
    }
  })

  // 成功体就是资源，空体无值；外观与核心共享语言、序列化及解析。
  it('reads resource bodies and supports the temporary facade', async () => {
    setLocale('en-US')
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response('{"title":"A"}', { headers: { 'Content-Type': 'application/json' } }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)
    expect(await request('POST /api/canvases', { body: { title: 'A' } }))
      .toEqual({ title: 'A' })
    expect(fetchMock.mock.calls[0]![0]).toBe('/api/canvases')
    expect(fetchMock.mock.calls[0]![1]).toMatchObject({
      method: 'POST', body: '{"title":"A"}',
      headers: { 'Accept-Language': 'en-US', 'Content-Type': 'application/json' },
    })
    await expect(apiClient.delete('/platform/models/a', { params: { v: 1 }, data: { x: 2 } }))
      .resolves.toBeUndefined()
    expect(fetchMock.mock.calls[1]![0]).toBe('/api/platform/models/a?v=1')
    expect(fetchMock.mock.calls[1]![1].body).toBe('{"x":2}')
  })

  // 外观保留已有的 GET 参数、请求头、无损解析与 PUT/POST 序列化。
  it('retains facade request options and mutations', async () => {
    const transport = vi.fn(async (_url: string, _init: RequestInit) =>
      new Response('{"n":9007199254740993}', { headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', transport)
    const parser = vi.fn((raw: string) => raw)
    expect(await apiClient.get('/platform/models?existing=1', {
      params: { q: 'a b', omit: undefined, enabled: false },
      headers: { 'Cache-Control': 'no-store' },
      jsonParser: parser,
    })).toBe('{"n":9007199254740993}')
    expect(transport.mock.calls[0]![0]).toBe('/api/platform/models?existing=1&q=a+b&enabled=false')
    expect(transport.mock.calls[0]![1].headers).toMatchObject({ 'Cache-Control': 'no-store' })
    expect(parser).toHaveBeenCalledOnce()
    await apiClient.post('/models', { name: 'x' }, { timeout: 0 })
    await apiClient.put('/models/x', { name: 'y' }, { headers: { 'Content-Type': 'application/json' } })
    expect(transport.mock.calls[1]![1]).toMatchObject({ method: 'POST', body: '{"name":"x"}' })
    expect(transport.mock.calls[2]![1]).toMatchObject({ method: 'PUT', body: '{"name":"y"}' })
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
      await request('GET /api/canvases', {})
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
    await expect(request('GET /api/canvases', {})).rejects.toMatchObject({
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
    expect(await request('GET /api/canvases', {}, { jsonParser: parser })).toEqual(['parsed'])
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
    await expect(request('GET /api/canvases', {})).resolves.toBeUndefined()
    await expect(request('GET /api/canvases', {})).resolves.toBeUndefined()
    await expect(request('GET /api/platform/daemon-uninstall-scripts/{operatingSystem}', {
      path: { operatingSystem: 'linux' },
    })).resolves.toBeUndefined()
    await expect(request('GET /api/harness/sessions/{sessionId}/files/download', {
      path: { sessionId: 's' }, query: { path: 'x' },
    })).resolves.toBeUndefined()
    await expect(request('GET /api/canvases', {}))
      .rejects.toMatchObject({ status: 404, code: 'not_found' })
  })

  // 过渡外观保留二进制传输，不误用 JSON.stringify。
  it('preserves binary bodies and text responses in the facade', async () => {
    const transport = vi.fn(async (_url: string, _init: RequestInit) =>
      new Response('plain', { headers: { 'Content-Type': 'text/plain' } }))
    vi.stubGlobal('fetch', transport)
    const form = new FormData()
    form.set('field', 'value')
    await expect(apiClient.post('/form', form)).resolves.toBe('plain')
    expect(transport.mock.calls[0]![1].body).toBe(form)
    await apiClient.post('/blob', new Blob(['data']))
    await apiClient.post('/buffer', new ArrayBuffer(2))
    await apiClient.post('/form-url', new URLSearchParams({ a: 'b' }))
    expect(transport.mock.calls[3]![1].body).toBeInstanceOf(URLSearchParams)
  })

  // 本地取消在 fetch 前生效；缺省 problem 成员从状态回退。
  it('handles already-aborted requests and incomplete problem details', async () => {
    const transport = vi.fn().mockResolvedValue(new Response('{}', {
      status: 409, headers: { 'Content-Type': 'application/problem+json' },
    }))
    vi.stubGlobal('fetch', transport)
    await expect(request('GET /api/canvases', {}, { signal: AbortSignal.abort() }))
      .rejects.toMatchObject({ name: 'AbortError' })
    expect(transport).not.toHaveBeenCalled()
    await expect(request('GET /api/canvases', {}))
      .rejects.toMatchObject({ status: 409, code: 'conflict' })
  })

  // 超时和调用方取消在响应体读取期间仍生效。
  it('aborts timed out and externally cancelled requests', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('fetch', vi.fn((_url, init: RequestInit) => new Promise((_resolve, reject) => {
      init.signal?.addEventListener('abort', () => reject(init.signal?.reason))
    })))
    const timed = request('GET /api/canvases', {})
    const assertion = expect(timed).rejects.toMatchObject({ code: 'timeout' })
    await vi.advanceTimersByTimeAsync(60000)
    await assertion
    const controller = new AbortController()
    const aborted = request('GET /api/canvases', {}, { signal: controller.signal, timeoutMs: 0 })
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
    const pending = request('GET /api/canvases', {}, { timeoutMs: 5 })
    const assertion = expect(pending).rejects.toMatchObject({ code: 'timeout' })
    await vi.advanceTimersByTimeAsync(5)
    await assertion
  })

  // 网络失败不依赖服务端错误信封。
  it('normalizes network failures', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('offline')))
    await expect(request('GET /api/canvases', {}))
      .rejects.toMatchObject({ name: 'ApiError', code: 'network_error', message: 'offline' })
  })
})
