import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  cancelCanvasFunctionRun,
  createCanvas,
  getCanvas,
  getCanvasFunctionRun,
  getCanvasResourceOriginalUrl,
  getCanvasResourcePreviewUrl,
  listCanvasFunctionModels,
  listCanvases,
  postCanvasCommands,
  startCanvasFunctionRun,
} from '@/shared/api/studio-service'
import { ApiError } from '@/shared/api/client'

const ID = '11111111-1111-4111-8111-111111111111'
const CANVAS_ID = '22222222-2222-4222-8222-222222222222'
const NODE_ID = '33333333-3333-4333-8333-333333333333'
const REQUEST_ID = '55555555-5555-4555-8555-555555555555'

const fetchMock = vi.fn()

beforeEach(() => {
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

function envelope(data: unknown, overrides: Record<string, unknown> = {}) {
  return {
    status: 200,
    code: 'OK',
    message: '',
    data,
    ...overrides,
  }
}

function response(data: unknown, overrides: { ok?: boolean; status?: number } = {}) {
  return {
    ok: overrides.ok ?? true,
    status: overrides.status ?? 200,
    json: vi.fn().mockResolvedValue(data),
  }
}

function documentPayload() {
  return {
    id: CANVAS_ID,
    title: 'Canvas',
    version: '3',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-02T00:00:00Z',
  }
}

function snapshotPayload() {
  return {
    document: documentPayload(),
    nodes: [],
    groups: [],
    links: [],
  }
}

function patchPayload() {
  return {
    baseVersion: '3',
    version: '4',
    groups: [],
    nodes: [],
    links: [],
  }
}

describe('studio service transport adapter', () => {
  it('exercises every exported canvas request through fetch', async () => {
    fetchMock
      .mockResolvedValueOnce(response(envelope([documentPayload()])))
      .mockResolvedValueOnce(response(envelope(documentPayload())))
      .mockResolvedValueOnce(response(envelope(documentPayload())))
      .mockResolvedValueOnce(response(envelope(snapshotPayload())))
      .mockResolvedValueOnce(response(envelope(patchPayload())))
      .mockResolvedValueOnce(response(envelope([{ key: 'image' }])))
      .mockResolvedValueOnce(response(envelope({ method: 'GET', url: 'https://download', headers: {}, expiresAt: 'later' })))
      .mockResolvedValueOnce(response(envelope({ method: 'GET', url: 'https://preview', headers: {}, expiresAt: 'later' })))
      .mockResolvedValueOnce(response(envelope({ nodeId: NODE_ID, requestId: REQUEST_ID, status: 'RUNNING', stage: 'start', error: null, updatedAt: 'now' })))
      .mockResolvedValueOnce(response(envelope({ nodeId: NODE_ID, requestId: REQUEST_ID, status: 'CANCELLED', stage: 'cancelled', error: 'stop', updatedAt: 'now' })))
      .mockResolvedValueOnce(response(envelope({ nodeId: NODE_ID, requestId: REQUEST_ID, status: 'SUCCEEDED', stage: 'done', error: null, updatedAt: 'now' })))

    expect(await listCanvases()).toHaveLength(1)
    expect((await createCanvas()).title).toBe('Canvas')
    expect((await createCanvas('Named')).title).toBe('Canvas')
    expect((await getCanvas(CANVAS_ID)).document.id).toBe(CANVAS_ID)
    expect((await postCanvasCommands(CANVAS_ID, {
      expectedVersion: '3',
      commandId: ID,
      commands: [],
    })).version).toBe('4')
    expect(await listCanvasFunctionModels()).toEqual([{ key: 'image' }])
    expect((await getCanvasResourceOriginalUrl(CANVAS_ID, ID)).method).toBe('GET')
    expect((await getCanvasResourcePreviewUrl(CANVAS_ID, ID)).url).toContain('preview')
    expect((await startCanvasFunctionRun(CANVAS_ID, NODE_ID, { requestId: REQUEST_ID })).status)
      .toBe('RUNNING')
    expect((await cancelCanvasFunctionRun(CANVAS_ID, NODE_ID, { requestId: REQUEST_ID })).status)
      .toBe('CANCELLED')
    expect((await getCanvasFunctionRun(CANVAS_ID, NODE_ID)).status).toBe('SUCCEEDED')
    expect(fetchMock).toHaveBeenCalledTimes(11)
  })

  it('issues the expected endpoints, methods, bodies, and signals', async () => {
    fetchMock
      .mockResolvedValueOnce(response(envelope(documentPayload())))
      .mockResolvedValueOnce(response(envelope(snapshotPayload())))
      .mockResolvedValueOnce(response(envelope(patchPayload())))

    const controller = new AbortController()
    await createCanvas('Named', { signal: controller.signal })
    await getCanvas(CANVAS_ID, { signal: controller.signal })
    await postCanvasCommands(CANVAS_ID, { expectedVersion: '3', commandId: ID, commands: [] }, { signal: controller.signal })

    const calls = fetchMock.mock.calls.map(([url, init]: [string, RequestInit]) => ({
      url,
      method: init.method ?? 'GET',
      body: init.body,
      signal: init.signal,
    }))
    expect(calls[0]).toMatchObject({ url: '/api/canvases', method: 'POST', body: JSON.stringify({ title: 'Named' }) })
    expect(calls[1]).toMatchObject({ url: `/api/canvases/${CANVAS_ID}`, method: 'GET' })
    expect(calls[2]).toMatchObject({
      url: `/api/canvases/${CANVAS_ID}/commands`,
      method: 'POST',
      body: JSON.stringify({ expectedVersion: '3', commandId: ID, commands: [] }),
    })
    for (const call of calls) {
      expect(call.signal).toBe(controller.signal)
    }
  })

  it('sends Accept/Accept-Language headers and Content-Type only when a body exists', async () => {
    fetchMock
      .mockResolvedValueOnce(response(envelope([documentPayload()])))
      .mockResolvedValueOnce(response(envelope(documentPayload())))

    await listCanvases()
    await createCanvas('Named')

    const getInit = fetchMock.mock.calls[0]?.[1] as RequestInit
    expect(getInit.headers).toEqual({ Accept: 'application/json', 'Accept-Language': 'zh-CN' })
    expect(getInit.body).toBeUndefined()

    const postInit = fetchMock.mock.calls[1]?.[1] as RequestInit
    expect(postInit.headers).toEqual({
      Accept: 'application/json',
      'Accept-Language': 'zh-CN',
      'Content-Type': 'application/json',
    })
  })

  it('handles transport, response envelope, and HTTP failures without weakening errors', async () => {
    fetchMock.mockRejectedValueOnce(new Error('network'))
    await expect(listCanvases()).rejects.toThrow('network')

    fetchMock.mockRejectedValueOnce(Object.assign(new Error('aborted'), { name: 'AbortError' }))
    await expect(listCanvases()).rejects.toThrow('aborted')

    fetchMock.mockRejectedValueOnce('unknown transport')
    await expect(listCanvases()).rejects.toThrow('请求失败')

    fetchMock.mockResolvedValueOnce(response('not-json', { status: 502 }))
    await expect(listCanvases()).rejects.toThrow('invalid Result envelope')

    fetchMock.mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: vi.fn().mockRejectedValue(new Error('bad json')),
    })
    await expect(listCanvases()).rejects.toThrow('invalid JSON')

    fetchMock.mockResolvedValueOnce(response(envelope([], { status: 400, code: 'BAD', message: 'bad request' }), { ok: false, status: 400 }))
    await expect(listCanvases()).rejects.toMatchObject({ status: 400, code: 'BAD' })

    fetchMock.mockResolvedValueOnce(response(envelope([], { status: 400, code: 'BAD', message: '' })))
    await expect(listCanvases()).rejects.toThrow('请求失败')
  })

  it('fails closed when the envelope is malformed or missing its data field', async () => {
    fetchMock.mockResolvedValueOnce(response({ status: 200, code: 'OK', message: '' }))
    await expect(listCanvases()).rejects.toBeInstanceOf(ApiError)

    fetchMock.mockResolvedValueOnce(response(envelope(null)))
    await expect(listCanvases()).rejects.toBeInstanceOf(ApiError)
  })
})
