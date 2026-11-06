import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  cancelCanvasFunctionRun,
  createCanvas,
  getCanvas,
  getCanvasFunctionRun,
  getCanvasResourceOriginalUrl,
  getCanvasResourcePreviewUrl,
  listCanvasFunctions,
  listCanvases,
  postCanvasCommands,
  resolveCanvasFunctionRun,
  startCanvasFunctionRun,
} from '@/features/canvas/canvas-api'
import { ApiError } from '@/shared/api/client'

const ID = '11111111-1111-4111-8111-111111111111'
const CANVAS_ID = '22222222-2222-4222-8222-222222222222'
const NODE_ID = '33333333-3333-4333-8333-333333333333'
const REQUEST_ID = '55555555-5555-4555-8555-555555555555'
/** 目录版本夹具：start 必须回传同形状 expectedVersion，cancel 只发 requestId。 */
const FUNCTION_VERSION = { definitionFingerprint: 'e'.repeat(64) }

const fetchMock = vi.fn<typeof fetch>()

beforeEach(() => {
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

/** 新 HTTP wire 契约：成功响应无信封，直接返回资源本身。 */
function jsonResponse(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

/** 新 HTTP wire 契约：错误响应使用 RFC 9457 application/problem+json。 */
function problemResponse(problem: {
  title: string
  status: number
  code?: string
  reason?: string
  detail?: string
  errors?: Record<string, unknown>
}) {
  return new Response(
    JSON.stringify({
      type: 'about:blank',
      ...problem,
    }),
    {
      status: problem.status,
      headers: { 'Content-Type': 'application/problem+json' },
    },
  )
}

function documentPayload() {
  return {
    id: CANVAS_ID,
    title: 'Canvas',
    revision: '3',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-02T00:00:00Z',
  }
}

function snapshotPayload() {
  return {
    document: documentPayload(),
    nodes: [],
    groups: [],
    references: [],
  }
}

function patchPayload() {
  return {
    revision: '4',
    groups: [],
    nodes: [],
  }
}

describe('canvas api transport adapter', () => {
  /**
   * 测试意图：验证 canvas-api 导出的所有 11 个 Canvas 请求均能通过类型化 fetch 核心传输，
   * 响应遵守无信封的新 wire 契约，正确解包各资源类型。
   */
  it('exercises every exported canvas request through client core without envelope', async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse([documentPayload()]))
      .mockResolvedValueOnce(jsonResponse(documentPayload(), 201))
      .mockResolvedValueOnce(jsonResponse(documentPayload(), 201))
      .mockResolvedValueOnce(jsonResponse(snapshotPayload()))
      .mockResolvedValueOnce(jsonResponse(patchPayload()))
      .mockResolvedValueOnce(
        jsonResponse([
          {
            version: FUNCTION_VERSION,
            name: 'image',
            description: 'Image',
            outputs: [{ kind: 'IMAGE', name: null }],
            argsSchema: { type: 'object' },
            referencePolicy: { allowedKinds: ['IMAGE'], maxReferences: 1, maxByKind: {} },
            available: true,
            unavailableReason: null,
          },
        ]),
      )
      .mockResolvedValueOnce(
        jsonResponse({ method: 'GET', url: 'https://download', headers: {}, expiresAt: 'later' }),
      )
      .mockResolvedValueOnce(
        jsonResponse({ method: 'GET', url: 'https://preview', headers: {}, expiresAt: 'later' }),
      )
      .mockResolvedValueOnce(
        jsonResponse(
          {
            nodeId: NODE_ID,
            requestId: REQUEST_ID,
            status: 'RUNNING',
            stage: 'start',
            error: null,
            updatedAt: 'now',
          },
          202,
        ),
      )
      .mockResolvedValueOnce(
        jsonResponse({
          nodeId: NODE_ID,
          requestId: REQUEST_ID,
          status: 'SUCCEEDED',
          stage: 'done',
          error: null,
          updatedAt: 'now',
        }),
      )
      .mockResolvedValueOnce(
        jsonResponse({
          nodeId: NODE_ID,
          requestId: REQUEST_ID,
          status: 'CANCELLED',
          stage: 'cancelled',
          error: 'stop',
          updatedAt: 'now',
        }),
      )
      .mockResolvedValueOnce(
        jsonResponse({
          nodeId: NODE_ID,
          requestId: REQUEST_ID,
          status: 'SUCCEEDED',
          stage: 'done',
          error: null,
          updatedAt: 'now',
        }),
      )

    expect(await listCanvases()).toHaveLength(1)
    expect((await createCanvas()).title).toBe('Canvas')
    expect((await createCanvas('Named')).title).toBe('Canvas')
    expect((await getCanvas(CANVAS_ID)).document.id).toBe(CANVAS_ID)
    expect(
      (
        await postCanvasCommands(CANVAS_ID, {
          idempotencyKey: ID,
          commands: [],
        })
      ).revision,
    ).toBe('4')
    expect(await listCanvasFunctions()).toHaveLength(1)
    expect((await getCanvasResourceOriginalUrl(CANVAS_ID, ID)).method).toBe('GET')
    expect((await getCanvasResourcePreviewUrl(CANVAS_ID, ID)).url).toContain('preview')
    expect(
      (
        await startCanvasFunctionRun(CANVAS_ID, NODE_ID, {
          requestId: REQUEST_ID,
          expectedVersion: FUNCTION_VERSION,
        })
      ).status,
    ).toBe('RUNNING')
    expect((await getCanvasFunctionRun(CANVAS_ID, NODE_ID)).status).toBe('SUCCEEDED')
    expect(
      (await cancelCanvasFunctionRun(CANVAS_ID, NODE_ID, { requestId: REQUEST_ID })).status,
    ).toBe('CANCELLED')
    expect(
      (
        await resolveCanvasFunctionRun(CANVAS_ID, NODE_ID, {
          requestId: REQUEST_ID,
          resolution: 'RESUME',
          verification: 'manual-check',
        })
      ).status,
    ).toBe('SUCCEEDED')
    expect(fetchMock).toHaveBeenCalledTimes(12)
  })

  /**
   * 测试意图：验证发出的 HTTP 路径、方法、Body 与 AbortSignal，
   * 确保生成的 ApiRoutes 路由路径匹配当前服务器路径，信号正确传递。
   */
  it('issues the expected endpoints, methods, bodies, and signals', async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(documentPayload(), 201))
      .mockResolvedValueOnce(jsonResponse(snapshotPayload()))
      .mockResolvedValueOnce(jsonResponse(patchPayload()))
      .mockResolvedValueOnce(
        jsonResponse({ nodeId: NODE_ID, requestId: REQUEST_ID, status: 'RUNNING' }, 202),
      )
      .mockResolvedValueOnce(
        jsonResponse({ nodeId: NODE_ID, requestId: REQUEST_ID, status: 'SUCCEEDED' }),
      )
      .mockResolvedValueOnce(
        jsonResponse({ nodeId: NODE_ID, requestId: REQUEST_ID, status: 'CANCELLED' }),
      )
      .mockResolvedValueOnce(
        jsonResponse({ nodeId: NODE_ID, requestId: REQUEST_ID, status: 'SUCCEEDED' }),
      )

    const controller = new AbortController()
    await createCanvas('Named', { signal: controller.signal })
    await getCanvas(CANVAS_ID, { signal: controller.signal })
    await postCanvasCommands(
      CANVAS_ID,
      { idempotencyKey: ID, commands: [] },
      { signal: controller.signal },
    )
    await startCanvasFunctionRun(
      CANVAS_ID,
      NODE_ID,
      {
        requestId: REQUEST_ID,
        expectedVersion: FUNCTION_VERSION,
      },
      { signal: controller.signal },
    )
    await getCanvasFunctionRun(CANVAS_ID, NODE_ID, { signal: controller.signal })
    await cancelCanvasFunctionRun(
      CANVAS_ID,
      NODE_ID,
      { requestId: REQUEST_ID },
      { signal: controller.signal },
    )
    await resolveCanvasFunctionRun(
      CANVAS_ID,
      NODE_ID,
      { requestId: REQUEST_ID, resolution: 'RESUME', verification: 'manual-check' },
      { signal: controller.signal },
    )

    const calls = fetchMock.mock.calls.map(([rawUrl, init]) => {
      const urlStr =
        typeof rawUrl === 'string'
          ? rawUrl
          : rawUrl instanceof URL
            ? rawUrl.pathname
            : String(rawUrl)
      return {
        url: urlStr,
        method: init?.method ?? 'GET',
        body: init?.body,
        signal: init?.signal,
      }
    })

    expect(calls[0]).toMatchObject({
      url: '/api/canvases',
      method: 'POST',
      body: JSON.stringify({ title: 'Named' }),
    })
    expect(calls[1]).toMatchObject({
      url: `/api/canvases/${CANVAS_ID}`,
      method: 'GET',
    })
    expect(calls[2]).toMatchObject({
      url: `/api/canvases/${CANVAS_ID}/commands`,
      method: 'POST',
      body: JSON.stringify({ idempotencyKey: ID, commands: [] }),
    })
    expect(calls[3]).toMatchObject({
      url: `/api/canvases/${CANVAS_ID}/nodes/${NODE_ID}/function-run`,
      method: 'POST',
      body: JSON.stringify({ requestId: REQUEST_ID, expectedVersion: FUNCTION_VERSION }),
    })
    expect(calls[4]).toMatchObject({
      url: `/api/canvases/${CANVAS_ID}/nodes/${NODE_ID}/function-run`,
      method: 'GET',
    })
    expect(calls[5]).toMatchObject({
      url: `/api/canvases/${CANVAS_ID}/nodes/${NODE_ID}/function-run/cancel`,
      method: 'POST',
      body: JSON.stringify({ requestId: REQUEST_ID }),
    })
    expect(calls[6]).toMatchObject({
      url: `/api/canvases/${CANVAS_ID}/nodes/${NODE_ID}/function-run/resolve`,
      method: 'POST',
      body: JSON.stringify({ requestId: REQUEST_ID, resolution: 'RESUME', verification: 'manual-check' }),
    })
    for (const call of calls) {
      expect(call.signal).toBeInstanceOf(AbortSignal)
      expect(call.signal?.aborted).toBe(false)
    }
  })

  /**
   * 测试意图：覆盖路径参数逐段编码，验证特殊字符在路径插值时被正确 encodeURIComponent，
   * 避免非法字符破坏 URL 路由结构。
   */
  it('encodes path variables segment-wise', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(snapshotPayload()))
    fetchMock.mockResolvedValueOnce(
      jsonResponse({ method: 'GET', url: 'https://download', headers: {}, expiresAt: 'later' }),
    )

    const rawCanvasId = 'canvas/id with spaces&symbols'
    const rawResourceId = 'res/123#special'

    await getCanvas(rawCanvasId)
    await getCanvasResourceOriginalUrl(rawCanvasId, rawResourceId)

    const calls = fetchMock.mock.calls.map(([rawUrl]) =>
      typeof rawUrl === 'string'
        ? rawUrl
        : rawUrl instanceof URL
          ? rawUrl.pathname
          : String(rawUrl),
    )

    expect(calls[0]).toBe(`/api/canvases/${encodeURIComponent(rawCanvasId)}`)
    expect(calls[1]).toBe(
      `/api/canvases/${encodeURIComponent(rawCanvasId)}/resources/${encodeURIComponent(rawResourceId)}/download-url`,
    )
  })

  /**
   * 测试意图：覆盖 AbortSignal 取消行为，验证 options.signal 无论是预先中止还是在执行中中止，
   * 均能正确联动底层 fetch 取消，并保持取消异常传播。
   */
  it('covers cancellation via AbortSignal', async () => {
    // 1. 预先中止的 signal
    const preAbortedController = new AbortController()
    preAbortedController.abort()

    await expect(listCanvases({ signal: preAbortedController.signal })).rejects.toThrow(
      expect.objectContaining({ name: 'AbortError' }),
    )

    // 2. 挂起中的请求收到取消信号后联动底层 fetch
    const activeController = new AbortController()
    let innerSignal: AbortSignal | undefined
    fetchMock.mockImplementationOnce((_url, init) => {
      innerSignal = init?.signal as AbortSignal
      return new Promise((_resolve, reject) => {
        innerSignal?.addEventListener('abort', () => {
          reject(new DOMException('The operation was aborted.', 'AbortError'))
        })
      })
    })

    const runPromise = listCanvases({ signal: activeController.signal })
    expect(innerSignal).toBeInstanceOf(AbortSignal)
    expect(innerSignal?.aborted).toBe(false)

    activeController.abort()
    expect(innerSignal?.aborted).toBe(true)
    await expect(runPromise).rejects.toThrow(
      expect.objectContaining({ name: 'AbortError' }),
    )
  })

  /**
   * 测试意图：验证 application/problem+json 错误响应被解析为 ApiError，
   * 包含 status、code、reason、detail、errors 结构化字段，message 取 title，
   * 并验证 ApiError 并行约定构造 (message,status?,code?,errors?,reason?,detail?)。
   */
  it('handles application/problem+json error responses and preserves ApiError contract', async () => {
    // 1. 409 Canvas 命令冲突（含 reason、detail 与 errors.conflicts）
    fetchMock.mockResolvedValueOnce(
      problemResponse({
        title: 'Canvas command conflict',
        status: 409,
        code: 'conflict',
        reason: 'CANVAS_COMMAND_CONFLICT',
        detail: 'command prerequisites are stale; no write was applied',
        errors: {
          conflicts: [{ kind: 'GROUP', id: ID, serverRevision: '5' }],
        },
      }),
    )

    await expect(
      postCanvasCommands(CANVAS_ID, { idempotencyKey: ID, commands: [] }),
    ).rejects.toSatisfy((error: unknown) => {
      expect(error).toBeInstanceOf(ApiError)
      const err = error as ApiError & { reason?: string; detail?: string }
      expect(err.message).toBe('Canvas command conflict')
      expect(err.status).toBe(409)
      expect(err.code).toBe('conflict')
      expect(err.reason).toBe('CANVAS_COMMAND_CONFLICT')
      expect(err.detail).toBe('command prerequisites are stale; no write was applied')
      expect(err.errors).toEqual({
        conflicts: [{ kind: 'GROUP', id: ID, serverRevision: '5' }],
      })
      return true
    })

    // 2. 400 Validation 错误
    fetchMock.mockResolvedValueOnce(
      problemResponse({
        title: 'title is required',
        status: 400,
        code: 'validation',
        detail: 'field title must not be blank',
      }),
    )

    await expect(createCanvas('')).rejects.toSatisfy((error: unknown) => {
      expect(error).toBeInstanceOf(ApiError)
      const err = error as ApiError & { reason?: string; detail?: string }
      expect(err.message).toBe('title is required')
      expect(err.status).toBe(400)
      expect(err.code).toBe('validation')
      expect(err.detail).toBe('field title must not be blank')
      return true
    })

    // 3. 404 Not Found 错误
    fetchMock.mockResolvedValueOnce(
      problemResponse({
        title: 'unknown canvas',
        status: 404,
        code: 'not_found',
      }),
    )

    await expect(getCanvas(CANVAS_ID)).rejects.toSatisfy((error: unknown) => {
      expect(error).toBeInstanceOf(ApiError)
      const err = error as ApiError & { reason?: string; detail?: string }
      expect(err.message).toBe('unknown canvas')
      expect(err.status).toBe(404)
      expect(err.code).toBe('not_found')
      return true
    })

    // 4. 非 problem 回退与网络错误
    fetchMock.mockResolvedValueOnce(new Response('Internal Server Error', { status: 500 }))
    await expect(listCanvases()).rejects.toBeInstanceOf(ApiError)

    fetchMock.mockRejectedValueOnce(new Error('network failure'))
    await expect(listCanvases()).rejects.toThrow('network failure')

    // 5. 验证 ApiError 并行约定构造签名：(message,status?,code?,errors?,reason?,detail?)
    const ErrorCtor = ApiError as unknown as new (
      message: string,
      status?: number,
      code?: string,
      errors?: Record<string, unknown>,
      reason?: string,
      detail?: string,
    ) => ApiError & { reason?: string; detail?: string }

    const constructed = new ErrorCtor(
      'title message',
      409,
      'conflict',
      { custom: true },
      'SOME_REASON',
      'detail text',
    )
    expect(constructed.message).toBe('title message')
    expect(constructed.status).toBe(409)
    expect(constructed.code).toBe('conflict')
    expect(constructed.errors).toEqual({ custom: true })
    if ('reason' in constructed) {
      expect(constructed.reason).toBe('SOME_REASON')
    }
    if ('detail' in constructed) {
      expect(constructed.detail).toBe('detail text')
    }
  })
})
