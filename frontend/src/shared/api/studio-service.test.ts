import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { UUIDString } from '@/shared/api/contracts/studio'
import {
  cancelCanvasFunctionRun,
  createCanvas,
  createCanvasRealtimeStream,
  getCanvas,
  getCanvasChanges,
  getCanvasFunctionRun,
  getCanvasResourceOriginalUrl,
  getCanvasResourcePreviewUrl,
  listCanvasFunctionModels,
  listCanvases,
  postCanvasCommands,
  sendCanvasThreadFirstSend,
  startCanvasFunctionRun,
} from '@/shared/api/studio-service'

const CANVAS_ID = '8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f'
const NODE_ID = 'c9e3b7f1-2a4d-4e6f-8a9b-0c1d2e3f4a5b'
const THREAD_ID = 'a1b2c3d4-5e6f-4a7b-8c9d-0e1f2a3b4c5d'
const RUN_REQUEST_ID = 'b2c3d4e5-6f7a-4b8c-9d0e-1f2a3b4c5d6e'

function result(data: unknown, status = 200) {
  return new Response(JSON.stringify({
    status,
    code: status >= 200 && status < 300 ? 'OK' : 'CONFLICT',
    message: status >= 200 && status < 300 ? 'ok' : 'conflict',
    data,
    errors: null,
  }), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function documentFixture(version = 0, threadId: UUIDString | null = null) {
  return {
    id: CANVAS_ID,
    title: 'Board',
    version,
    threadId,
    createdAt: '2026-08-10T00:00:00Z',
    updatedAt: '2026-08-10T00:00:00Z',
  }
}

describe('studio-service', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('keeps UUID ids and numeric versions as canonical values', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(result([documentFixture(7)]))

    const canvases = await listCanvases()

    expect(canvases[0]?.id).toBe(CANVAS_ID)
    expect(canvases[0]?.version).toBe(7)
    expect(fetch).toHaveBeenCalledWith('/api/canvases', expect.objectContaining({
      method: 'GET',
    }))
  })

  it('creates canvases with a strict title-only body', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(result(documentFixture(0), 201))

    await createCanvas('Board')

    expect(fetch).toHaveBeenCalledWith('/api/canvases', expect.objectContaining({
      method: 'POST',
      body: '{"title":"Board"}',
    }))
  })

  it('sends typed command batches with UUID entity ids and expectedVersion', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(result({
      baseVersion: 3,
      version: 4,
      groups: [],
      nodes: [],
      links: [],
    }))

    await postCanvasCommands(CANVAS_ID, {
      expectedVersion: 3,
      commandId: '11111111-2222-4333-8444-555555555555',
      commands: [{
        type: 'CREATE_TEXT_NODE',
        nodeId: NODE_ID,
        name: 'Note',
        markdown: 'body',
        transform: { x: 0, y: 0, width: 320, height: 260 },
      }],
    })

    const body = JSON.parse(String(vi.mocked(fetch).mock.calls[0]?.[1]?.body))
    expect(body).toEqual({
      expectedVersion: 3,
      commandId: '11111111-2222-4333-8444-555555555555',
      commands: [{
        type: 'CREATE_TEXT_NODE',
        nodeId: NODE_ID,
        name: 'Note',
        markdown: 'body',
        transform: { x: 0, y: 0, width: 320, height: 260 },
      }],
    })
  })

  it('fetches changes with afterVersion and creates the SSE stream URL', async () => {
    class FakeEventSource {
      constructor(readonly url: string) {}
      close(): void {}
    }
    vi.stubGlobal('EventSource', FakeEventSource)
    vi.mocked(fetch).mockResolvedValueOnce(result({
      patches: [],
      snapshot: null,
    }))

    const changes = await getCanvasChanges(CANVAS_ID, 4)

    expect(changes).toEqual({ patches: [], snapshot: null })
    expect(fetch).toHaveBeenCalledWith(`/api/canvases/${CANVAS_ID}/changes?afterVersion=4`, expect.objectContaining({
      method: 'GET',
    }))

    const source = createCanvasRealtimeStream(CANVAS_ID, 4)
    expect(source.url).toBe(`/api/canvases/${CANVAS_ID}/events/stream?afterVersion=4`)
    source.close()
  })

  it('sends the canvas-scoped atomic first-send request with ordered contents', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(result({
      threadId: THREAD_ID,
      document: documentFixture(0, THREAD_ID),
    }, 201))

    const response = await sendCanvasThreadFirstSend(CANVAS_ID, {
      commandId: '22222222-3333-4444-8555-666666666666',
      branchSettings: {
        environmentName: null,
        agentName: 'assistant',
        model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
        activeTools: [],
      },
      yoloEnabled: false,
      contents: [{ type: 'TEXT', text: 'hello' }],
    })

    expect(response.threadId).toBe(THREAD_ID)
    expect(response.document.threadId).toBe(THREAD_ID)
    const body = JSON.parse(String(vi.mocked(fetch).mock.calls[0]?.[1]?.body))
    expect(body).toEqual({
      commandId: '22222222-3333-4444-8555-666666666666',
      branchSettings: {
        environmentName: null,
        agentName: 'assistant',
        model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
        activeTools: [],
      },
      yoloEnabled: false,
      contents: [{ type: 'TEXT', text: 'hello' }],
    })
    expect(fetch).toHaveBeenCalledWith(`/api/canvases/${CANVAS_ID}/thread/messages`, expect.objectContaining({
      method: 'POST',
    }))
  })

  it('requires the Result envelope and preserves backend status', async () => {
    vi.mocked(fetch)
      .mockResolvedValueOnce(new Response(JSON.stringify([]), { status: 200 }))
      .mockResolvedValueOnce(result(null, 409))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        status: 409,
        code: 'CONFLICT',
        message: 'conflict',
        data: null,
        errors: { detail: 'stale' },
      }), { status: 200 }))

    await expect(listCanvases()).rejects.toMatchObject({
      name: 'ApiError',
      status: 200,
    })
    await expect(listCanvases()).rejects.toMatchObject({
      name: 'ApiError',
      status: 409,
      code: 'CONFLICT',
    })
    await expect(listCanvases()).rejects.toMatchObject({
      name: 'ApiError',
      status: 409,
      code: 'CONFLICT',
      errors: { detail: 'stale' },
    })
  })

  it('covers snapshot, models, resource URLs, and run endpoints', async () => {
    const snapshot = {
      document: documentFixture(0),
      nodes: [],
      groups: [],
      links: [],
    }
    const resource = {
      id: 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee',
      canvasId: CANVAS_ID,
      ownerNodeId: NODE_ID,
      resourceIndex: 0,
      blobId: 'blob-upload',
      name: 'upload.png',
      textContent: null,
      kind: 'IMAGE',
      mediaType: 'image/png',
      sizeBytes: 3,
      width: 1122,
      height: 1402,
      durationMs: null,
      createdAt: '2026-08-10T00:00:00Z',
    }
    const run = {
      nodeId: NODE_ID,
      requestId: RUN_REQUEST_ID,
      status: 'RUNNING',
      stage: 'QUEUED',
      error: null,
      updatedAt: '2026-08-10T00:00:00Z',
    }
    vi.mocked(fetch)
      .mockResolvedValueOnce(result(snapshot))
      .mockResolvedValueOnce(result([]))
      .mockResolvedValueOnce(result({ method: 'GET', url: 'https://s3.example/preview', headers: {}, expiresAt: 'x' }))
      .mockResolvedValueOnce(result({ method: 'GET', url: 'https://s3.example/original', headers: {}, expiresAt: 'x' }))
      .mockResolvedValueOnce(result(run, 201))
      .mockResolvedValueOnce(result(run))
      .mockResolvedValueOnce(result(run))

    await getCanvas(CANVAS_ID)
    await listCanvasFunctionModels()
    await getCanvasResourcePreviewUrl(CANVAS_ID, resource.id)
    await getCanvasResourceOriginalUrl(CANVAS_ID, resource.id)
    await startCanvasFunctionRun(CANVAS_ID, NODE_ID, { requestId: RUN_REQUEST_ID })
    await getCanvasFunctionRun(CANVAS_ID, NODE_ID)
    await cancelCanvasFunctionRun(CANVAS_ID, NODE_ID, { requestId: RUN_REQUEST_ID })

    expect(vi.mocked(fetch).mock.calls.map(([url, init]) => [String(url), init?.method ?? 'GET']))
      .toEqual([
        [`/api/canvases/${CANVAS_ID}`, 'GET'],
        ['/api/canvas-function-models', 'GET'],
        [`/api/canvases/${CANVAS_ID}/resources/${resource.id}/preview-url`, 'POST'],
        [`/api/canvases/${CANVAS_ID}/resources/${resource.id}/download-url`, 'POST'],
        [`/api/canvases/${CANVAS_ID}/nodes/${NODE_ID}/runs`, 'POST'],
        [`/api/canvases/${CANVAS_ID}/nodes/${NODE_ID}/run`, 'GET'],
        [`/api/canvases/${CANVAS_ID}/nodes/${NODE_ID}/run/cancel`, 'POST'],
      ])
  })
})
