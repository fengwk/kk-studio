import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  cancelCanvasFunctionRun,
  createCanvas,
  getCanvas,
  getCanvasChanges,
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
const GROUP_ID = '44444444-4444-4444-8444-444444444444'
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

function resourcePayload() {
  return {
    id: ID,
    canvasId: CANVAS_ID,
    ownerNodeId: NODE_ID,
    resourceIndex: 0,
    blobId: null,
    name: 'note',
    textContent: 'hello',
    kind: 'TEXT',
    mediaType: null,
    sizeBytes: '12',
    width: null,
    height: null,
    durationMs: null,
    createdAt: '2026-01-01T00:00:00Z',
  }
}

function nodePayload() {
  return {
    id: NODE_ID,
    canvasId: CANVAS_ID,
    name: 'Node',
    transform: { x: 1, y: 2, width: 100, height: 80 },
    groupId: GROUP_ID,
    resources: [resourcePayload()],
    function: { modelKey: 'image', configJson: '{}' },
    run: {
      nodeId: NODE_ID,
      requestId: REQUEST_ID,
      status: 'SUCCEEDED',
      stage: 'done',
      error: null,
      updatedAt: '2026-01-02T00:00:00Z',
    },
  }
}

function snapshotPayload() {
  return {
    document: documentPayload(),
    nodes: [nodePayload()],
    groups: [{
      id: GROUP_ID,
      canvasId: CANVAS_ID,
      title: 'Group',
      transform: { x: 0, y: 0, width: 200, height: 160 },
    }],
    links: [{
      canvasId: CANVAS_ID,
      sourceNodeId: NODE_ID,
      targetNodeId: ID,
    }],
  }
}

function patchPayload() {
  return {
    baseVersion: '3',
    version: '4',
    groups: [
      { op: 'UPSERT', group: snapshotPayload().groups[0] },
      { op: 'REMOVE', groupId: GROUP_ID },
    ],
    nodes: [
      { op: 'UPSERT', node: nodePayload() },
      { op: 'REMOVE', nodeId: NODE_ID },
    ],
    links: [
      { op: 'UPSERT', link: snapshotPayload().links[0] },
      { op: 'REMOVE', sourceNodeId: NODE_ID, targetNodeId: ID },
    ],
  }
}

describe('studio service wire adapter', () => {
  it('decodes graph payloads and exercises every exported canvas request', async () => {
    fetchMock
      .mockResolvedValueOnce(response(envelope([documentPayload()])))
      .mockResolvedValueOnce(response(envelope(documentPayload())))
      .mockResolvedValueOnce(response(envelope(documentPayload())))
      .mockResolvedValueOnce(response(envelope(snapshotPayload())))
      .mockResolvedValueOnce(response(envelope(patchPayload())))
      .mockResolvedValueOnce(response(envelope({
        patches: [patchPayload()],
        snapshot: snapshotPayload(),
      })))
      .mockResolvedValueOnce(response(envelope([{ key: 'image' }])))
      .mockResolvedValueOnce(response(envelope({ method: 'GET', url: 'https://download', headers: {}, expiresAt: 'later' })))
      .mockResolvedValueOnce(response(envelope({ method: 'GET', url: 'https://preview', headers: {}, expiresAt: 'later' })))
      .mockResolvedValueOnce(response(envelope({ nodeId: NODE_ID, requestId: REQUEST_ID, status: 'RUNNING', stage: 'start', error: null, updatedAt: 'now' })))
      .mockResolvedValueOnce(response(envelope({ nodeId: NODE_ID, requestId: REQUEST_ID, status: 'CANCELLED', stage: 'cancelled', error: 'stop', updatedAt: 'now' })))
      .mockResolvedValueOnce(response(envelope({ nodeId: NODE_ID, requestId: REQUEST_ID, status: 'SUCCEEDED', stage: 'done', error: null, updatedAt: 'now' })))

    expect(await listCanvases()).toHaveLength(1)
    expect((await createCanvas()).title).toBe('Canvas')
    expect((await createCanvas('Named')).title).toBe('Canvas')
    expect((await getCanvas(CANVAS_ID)).nodes[0]?.resources[0]?.sizeBytes).toBe(12)
    expect((await postCanvasCommands(CANVAS_ID, {
      expectedVersion: '3',
      commandId: ID,
      commands: [],
    })).version).toBe('4')
    expect((await getCanvasChanges(CANVAS_ID, '3')).snapshot).not.toBeNull()
    expect(await listCanvasFunctionModels()).toEqual([{ key: 'image' }])
    expect((await getCanvasResourceOriginalUrl(CANVAS_ID, ID)).method).toBe('GET')
    expect((await getCanvasResourcePreviewUrl(CANVAS_ID, ID)).url).toContain('preview')
    expect((await startCanvasFunctionRun(CANVAS_ID, NODE_ID, { requestId: REQUEST_ID })).status)
      .toBe('RUNNING')
    expect((await cancelCanvasFunctionRun(CANVAS_ID, NODE_ID, { requestId: REQUEST_ID })).status)
      .toBe('CANCELLED')
    expect((await getCanvasFunctionRun(CANVAS_ID, NODE_ID)).status).toBe('SUCCEEDED')
    expect(fetchMock).toHaveBeenCalledTimes(12)
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

  it('fails closed for malformed graph payloads and unsafe long values', async () => {
    const malformed = [
      { document: null, nodes: [], groups: [], links: [] },
      { ...snapshotPayload(), document: { ...documentPayload(), id: 'bad' } },
      { ...snapshotPayload(), document: { ...documentPayload(), version: '01' } },
      { ...snapshotPayload(), nodes: [{}] },
      { ...snapshotPayload(), groups: [{}] },
      { ...snapshotPayload(), links: [{}] },
      {
        ...snapshotPayload(),
        nodes: [{
          ...nodePayload(),
          transform: { ...nodePayload().transform, width: 0 },
        }],
      },
      {
        ...snapshotPayload(),
        nodes: [{
          ...nodePayload(),
          resources: [{ ...resourcePayload(), sizeBytes: '9007199254740992' }],
        }],
      },
    ]
    for (const value of malformed) {
      fetchMock.mockResolvedValueOnce(response(envelope(value)))
      await expect(getCanvas(CANVAS_ID)).rejects.toBeInstanceOf(ApiError)
    }
  })

  it('accepts nullable graph fields and both patch operation forms', async () => {
    const value = {
      document: documentPayload(),
      nodes: [{
        ...nodePayload(),
        groupId: null,
        resources: [{
          ...resourcePayload(),
          blobId: null,
          textContent: null,
          mediaType: null,
          sizeBytes: null,
          durationMs: null,
          width: 10,
          height: 20,
        }],
        function: null,
        run: null,
      }],
      groups: [],
      links: [],
    }
    fetchMock.mockResolvedValueOnce(response(envelope(value)))
    const snapshot = await getCanvas(CANVAS_ID)
    expect(snapshot.nodes[0]?.function).toBeNull()
    expect(snapshot.nodes[0]?.resources[0]?.width).toBe(10)

    fetchMock.mockResolvedValueOnce(response(envelope({ patches: [], snapshot: null })))
    expect((await getCanvasChanges(CANVAS_ID, '0')).snapshot).toBeNull()
  })
})
