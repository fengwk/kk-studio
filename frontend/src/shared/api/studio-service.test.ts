import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/shared/api/client'
import {
  applyCanvasCommands,
  browserSafePresignedHeaders,
  cancelCanvasFunctionRun,
  completeCanvasUpload,
  createCanvas,
  getCanvas,
  getCanvasFunctionRun,
  getCanvasResourceOriginalUrl,
  getCanvasResourcePreviewUrl,
  listCanvasFunctionModels,
  listCanvases,
  reserveCanvasUpload,
  startCanvasFunctionRun,
  uploadCanvasFile,
} from '@/shared/api/studio-service'

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

describe('studio-service', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('keeps bigint identifiers and revisions as decimal strings', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(result([{
      id: '9223372036854775807',
      title: 'Board',
      graphRevision: '9007199254740993',
      createdAt: '2026-08-10T00:00:00Z',
      updatedAt: '2026-08-10T00:00:00Z',
    }]))

    const canvases = await listCanvases()

    expect(canvases[0]?.id).toBe('9223372036854775807')
    expect(canvases[0]?.graphRevision).toBe('9007199254740993')
    expect(fetch).toHaveBeenCalledWith('/api/canvases', expect.objectContaining({
      method: 'GET',
    }))
  })

  it('creates canvases with a strict title-only body', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(result({
      id: '1',
      title: 'Board',
      graphRevision: '0',
      createdAt: '2026-08-10T00:00:00Z',
      updatedAt: '2026-08-10T00:00:00Z',
    }, 201))

    await createCanvas('Board')

    expect(fetch).toHaveBeenCalledWith('/api/canvases', expect.objectContaining({
      method: 'POST',
      body: '{"title":"Board"}',
    }))
  })

  it('sends typed command batches without numeric bigint coercion', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(result({
      document: {
        id: '9223372036854775807',
        title: 'Board',
        graphRevision: '9007199254740994',
        createdAt: '2026-08-10T00:00:00Z',
        updatedAt: '2026-08-10T00:00:00Z',
      },
      nodes: [],
      groups: [],
      links: [],
    }))

    await applyCanvasCommands('9223372036854775807', {
      expectedRevision: '9007199254740993',
      commandId: 'command-1',
      commands: [{
        type: 'DELETE_NODE',
        nodeId: '9223372036854775806',
      }],
    })

    const body = JSON.parse(String(vi.mocked(fetch).mock.calls[0]?.[1]?.body))
    expect(body).toEqual({
      expectedRevision: '9007199254740993',
      commandId: 'command-1',
      commands: [{
        type: 'DELETE_NODE',
        nodeId: '9223372036854775806',
      }],
    })
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

  it('preserves signed If-None-Match and Content-Type while filtering forbidden headers', async () => {
    expect(browserSafePresignedHeaders({
      Host: 's3.internal',
      'Content-Length': '3',
      'Permissions-Policy': 'interest-cohort=()',
      'User-Agent': 'forbidden',
      'If-None-Match': '*',
      'Content-Type': 'image/png',
      'x-amz-meta-test': 'ok',
      'Proxy-Authorization': 'secret',
      'Sec-Fetch-Site': 'same-site',
    })).toEqual({
      'If-None-Match': '*',
      'Content-Type': 'image/png',
      'x-amz-meta-test': 'ok',
    })
    vi.mocked(fetch).mockResolvedValueOnce(new Response(null, { status: 200 }))

    await uploadCanvasFile({
      uploadId: '9',
      method: 'PUT',
      url: 'https://s3.example/upload',
      headers: {
        Host: 's3.internal',
        'If-None-Match': '*',
        'Content-Type': 'image/png',
      },
      expiresAt: '2026-08-10T00:15:00Z',
    }, new Blob(['png'], { type: 'image/png' }))

    expect(fetch).toHaveBeenCalledWith('https://s3.example/upload', expect.objectContaining({
      method: 'PUT',
      headers: {
        'If-None-Match': '*',
        'Content-Type': 'image/png',
      },
    }))
  })

  it('reports direct upload HTTP failures with status', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(new Response(null, { status: 412 }))

    await expect(uploadCanvasFile({
      uploadId: '9',
      method: 'PUT',
      url: 'https://s3.example/upload',
      headers: { 'If-None-Match': '*' },
      expiresAt: '2026-08-10T00:15:00Z',
    }, new Blob(['png']))).rejects.toEqual(
      expect.objectContaining<ApiError>({ status: 412 }),
    )
  })

  it('covers snapshot, models, upload completion, resource URLs, and run endpoints', async () => {
    const snapshot = {
      document: {
        id: '1',
        title: 'Board',
        graphRevision: '0',
        createdAt: '2026-08-10T00:00:00Z',
        updatedAt: '2026-08-10T00:00:00Z',
      },
      nodes: [],
      groups: [],
      links: [],
    }
    const model = {
      key: 'fake-image',
      label: 'Fake Image',
      outputKind: 'IMAGE',
      referencePolicy: { allowedKinds: ['IMAGE'], maxReferences: 1, maxByKind: {} },
      parameters: [],
      available: true,
      unavailableReason: null,
    }
    const reservation = {
      uploadId: '9',
      method: 'PUT',
      url: 'https://s3.example/upload',
      headers: { 'If-None-Match': '*' },
      expiresAt: '2026-08-10T00:15:00Z',
    }
    const resource = {
      id: '9',
      canvasId: '1',
      kind: 'IMAGE',
      mediaType: 'image/png',
      name: 'image.png',
      size: '3',
      textContent: null,
      metadataJson: '{}',
      createdAt: '2026-08-10T00:00:00Z',
    }
    const presigned = {
      method: 'GET',
      url: 'https://s3.example/read',
      headers: {},
      expiresAt: '2026-08-10T00:15:00Z',
    }
    const run = {
      nodeId: '2',
      requestId: 'request-1',
      status: 'RUNNING',
      stage: 'QUEUED',
      error: null,
      updatedAt: '2026-08-10T00:00:00Z',
    }
    vi.mocked(fetch)
      .mockResolvedValueOnce(result(snapshot))
      .mockResolvedValueOnce(result([model]))
      .mockResolvedValueOnce(result(reservation, 201))
      .mockResolvedValueOnce(result(resource))
      .mockResolvedValueOnce(result(presigned))
      .mockResolvedValueOnce(result(presigned))
      .mockResolvedValueOnce(result(run, 202))
      .mockResolvedValueOnce(result(run))
      .mockResolvedValueOnce(result({ ...run, status: 'CANCELLED' }))
    const controller = new AbortController()

    await expect(getCanvas('1', { signal: controller.signal })).resolves.toEqual(snapshot)
    await expect(listCanvasFunctionModels()).resolves.toEqual([model])
    await expect(reserveCanvasUpload('1', {
      kind: 'IMAGE',
      filename: 'image.png',
      mediaType: 'image/png',
      size: '3',
    })).resolves.toEqual(reservation)
    await expect(completeCanvasUpload('1', '9')).resolves.toEqual(resource)
    await expect(getCanvasResourceOriginalUrl('1', '9')).resolves.toEqual(presigned)
    await expect(getCanvasResourcePreviewUrl('1', '9')).resolves.toEqual(presigned)
    await expect(startCanvasFunctionRun('1', '2', {
      requestId: 'request-1',
    })).resolves.toEqual(run)
    await expect(getCanvasFunctionRun('1', '2')).resolves.toEqual(run)
    await expect(cancelCanvasFunctionRun('1', '2', {
      requestId: 'request-1',
    })).resolves.toMatchObject({ status: 'CANCELLED' })

    expect(fetch).toHaveBeenNthCalledWith(1, '/api/canvases/1', expect.objectContaining({
      method: 'GET',
      signal: controller.signal,
    }))
    expect(fetch).toHaveBeenNthCalledWith(3, '/api/canvases/1/uploads', expect.objectContaining({
      method: 'POST',
      body: '{"kind":"IMAGE","filename":"image.png","mediaType":"image/png","size":"3"}',
    }))
    expect(fetch).toHaveBeenNthCalledWith(4, '/api/canvases/1/uploads/9/complete', expect.objectContaining({
      method: 'POST',
    }))
    expect(fetch).toHaveBeenNthCalledWith(5, '/api/canvases/1/resources/9/download-url', expect.objectContaining({
      method: 'POST',
    }))
    expect(fetch).toHaveBeenNthCalledWith(6, '/api/canvases/1/resources/9/preview-url', expect.objectContaining({
      method: 'POST',
    }))
    expect(fetch).toHaveBeenNthCalledWith(7, '/api/canvases/1/nodes/2/runs', expect.objectContaining({
      method: 'POST',
      body: '{"requestId":"request-1"}',
    }))
    expect(fetch).toHaveBeenNthCalledWith(8, '/api/canvases/1/nodes/2/run', expect.objectContaining({
      method: 'GET',
    }))
    expect(fetch).toHaveBeenNthCalledWith(9, '/api/canvases/1/nodes/2/run/cancel', expect.objectContaining({
      method: 'POST',
      body: '{"requestId":"request-1"}',
    }))
  })

  it('passes AbortSignal through API and direct upload requests', async () => {
    const controller = new AbortController()
    vi.mocked(fetch)
      .mockResolvedValueOnce(result([]))
      .mockResolvedValueOnce(new Response(null, { status: 200 }))

    await listCanvases({ signal: controller.signal })
    await uploadCanvasFile({
      uploadId: '9',
      method: 'PUT',
      url: 'https://s3.example/upload',
      headers: { 'If-None-Match': '*' },
      expiresAt: '2026-08-10T00:15:00Z',
    }, new Blob(['x']), { signal: controller.signal })

    expect(fetch).toHaveBeenNthCalledWith(1, '/api/canvases', expect.objectContaining({
      signal: controller.signal,
    }))
    expect(fetch).toHaveBeenNthCalledWith(2, 'https://s3.example/upload', expect.objectContaining({
      signal: controller.signal,
    }))
  })

  it('normalizes malformed JSON, network failures, and aborts', async () => {
    const abortError = new Error('aborted')
    abortError.name = 'AbortError'
    vi.mocked(fetch)
      .mockResolvedValueOnce(new Response('{', { status: 502 }))
      .mockRejectedValueOnce(new Error('offline'))
      .mockRejectedValueOnce(abortError)

    await expect(listCanvases()).rejects.toMatchObject({
      name: 'ApiError',
      status: 502,
      message: 'Canvas API returned invalid JSON (HTTP 502)',
    })
    await expect(listCanvases()).rejects.toMatchObject({
      name: 'ApiError',
      message: 'offline',
    })
    await expect(listCanvases()).rejects.toBe(abortError)
  })

  it('normalizes direct upload transport failures but preserves aborts', async () => {
    const reservation = {
      uploadId: '9' as const,
      method: 'PUT' as const,
      url: 'https://s3.example/upload',
      headers: {},
      expiresAt: '2026-08-10T00:15:00Z',
    }
    const abortError = new Error('aborted')
    abortError.name = 'AbortError'
    vi.mocked(fetch)
      .mockRejectedValueOnce(new Error('offline'))
      .mockRejectedValueOnce(abortError)

    await expect(uploadCanvasFile(reservation, new Blob())).rejects.toMatchObject({
      name: 'ApiError',
      message: 'offline',
    })
    await expect(uploadCanvasFile(reservation, new Blob())).rejects.toBe(abortError)
  })
})
