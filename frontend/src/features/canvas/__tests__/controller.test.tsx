import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import { useState, type PropsWithChildren } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useCanvasController } from '@/features/canvas/useCanvasController'
import { canvasViewportStorageKey } from '@/features/canvas/viewport-storage'
import type {
  ApplyCanvasCommandsRequestDTO,
  CanvasCommandDTO,
  CanvasPatchDTO,
  CanvasSnapshotDTO,
} from '@/shared/api/contracts/studio'
import {
  cancelCanvasFunctionRun,
  createCanvasRealtimeStream,
  getCanvas,
  getCanvasChanges,
  getCanvasFunctionRun,
  listCanvasFunctionModels,
  postCanvasCommands,
  startCanvasFunctionRun,
} from '@/shared/api/studio-service'
import { storageService } from '@/shared/api/storage-service'
import { probeCanvasFileMetadata } from '@/features/canvas/canvas-file-metadata'

const CANVAS_ID = '8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f'
const NODE_NOTE = 'aaaaaaaa-0000-4000-8000-000000000002'
const NODE_FN = 'aaaaaaaa-0000-4000-8000-000000000003'
const NODE_IMG = 'aaaaaaaa-0000-4000-8000-000000000005'
const GROUP_A = 'bbbbbbbb-0000-4000-8000-000000000004'
const RES_NOTE = 'cccccccc-0000-4000-8000-000000000020'
const RES_OUTPUT = 'cccccccc-0000-4000-8000-000000000030'
const RES_IMG = 'cccccccc-0000-4000-8000-000000000050'
const UPLOAD_ID = 'dddddddd-0000-4000-8000-000000000009'
const REQUEST_STARTED = 'eeeeeeee-0000-4000-8000-000000000001'
const REQUEST_CURRENT = 'eeeeeeee-0000-4000-8000-000000000002'
const REQUEST_OLD = 'eeeeeeee-0000-4000-8000-000000000003'
const { FAKE_SHA256 } = vi.hoisted(() => ({ FAKE_SHA256: 'a'.repeat(64) }))

vi.mock('@/shared/api/studio-service', () => ({
  postCanvasCommands: vi.fn(),
  cancelCanvasFunctionRun: vi.fn(),
  getCanvas: vi.fn(),
  getCanvasChanges: vi.fn(),
  getCanvasFunctionRun: vi.fn(),
  listCanvasFunctionModels: vi.fn(),
  startCanvasFunctionRun: vi.fn(),
  createCanvasRealtimeStream: vi.fn(),
}))

vi.mock('@/shared/api/storage-service', () => ({
  storageService: {
    reserveUpload: vi.fn(),
    uploadFile: vi.fn(),
    completeUpload: vi.fn(),
    deleteUpload: vi.fn(),
    getBlobOriginalUrl: vi.fn(),
    getBlobPreviewUrl: vi.fn(),
  },
}))

vi.mock('@/features/canvas/canvas-file-metadata', () => ({
  probeCanvasFileMetadata: vi.fn(async () => ({ width: 1122, height: 1402 })),
}))

vi.mock('@/features/ai/composer', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/features/ai/composer')>()
  return { ...actual, createWorkerHasher: () => async () => FAKE_SHA256 }
})

class FakeEventSource {
  private readonly listeners = new Map<string, EventListener[]>()

  addEventListener(type: string, listener: EventListener): void {
    const existing = this.listeners.get(type) ?? []
    existing.push(listener)
    this.listeners.set(type, existing)
  }

  removeEventListener(type: string, listener: EventListener): void {
    const existing = this.listeners.get(type) ?? []
    this.listeners.set(
      type,
      existing.filter((value) => value !== listener),
    )
  }

  close(): void {
    this.listeners.clear()
  }
}

function snapshot(version: number | string = 0): CanvasSnapshotDTO {
  return {
    document: {
      id: CANVAS_ID,
      title: 'Board',
      version: String(version),
      threadId: null,
      createdAt: '2026-08-10T00:00:00Z',
      updatedAt: '2026-08-10T00:00:00Z',
    },
    nodes: [{
      id: NODE_NOTE,
      canvasId: CANVAS_ID,
      name: 'Note',
      transform: { x: 20, y: 30, width: 320, height: 260 },
      groupId: null,
      resources: [{
        id: RES_NOTE,
        canvasId: CANVAS_ID,
        ownerNodeId: NODE_NOTE,
        resourceIndex: 0,
        blobId: null,
        name: 'note.md',
        textContent: 'note',
        kind: 'TEXT',
        mediaType: 'text/markdown',
        sizeBytes: 4,
        width: null,
        height: null,
        durationMs: null,
        createdAt: '2026-08-10T00:00:00Z',
      }],
      function: null,
      run: null,
    }, {
      id: NODE_FN,
      canvasId: CANVAS_ID,
      name: 'Function',
      transform: { x: 400, y: 30, width: 320, height: 260 },
      groupId: GROUP_A,
      resources: [{
        id: RES_OUTPUT,
        canvasId: CANVAS_ID,
        ownerNodeId: NODE_FN,
        resourceIndex: 0,
        blobId: 'blob-output',
        name: 'old-output.png',
        textContent: null,
        kind: 'IMAGE',
        mediaType: 'image/png',
        sizeBytes: 3,
        width: null,
        height: null,
        durationMs: null,
        createdAt: '2026-08-10T00:00:00Z',
      }],
      function: {
        modelKey: 'fake-image',
        configJson: JSON.stringify({
          prompt: { segments: [{ type: 'TEXT', text: 'old prompt' }] },
          parameters: { ratio: 'AUTO' },
        }),
      },
      run: null,
    }, {
      id: NODE_IMG,
      canvasId: CANVAS_ID,
      name: 'Image',
      transform: { x: 60, y: 360, width: 320, height: 260 },
      groupId: GROUP_A,
      resources: [{
        id: RES_IMG,
        canvasId: CANVAS_ID,
        ownerNodeId: NODE_IMG,
        resourceIndex: 0,
        blobId: 'blob-image',
        name: 'image.png',
        textContent: null,
        kind: 'IMAGE',
        mediaType: 'image/png',
        sizeBytes: 3,
        width: null,
        height: null,
        durationMs: null,
        createdAt: '2026-08-10T00:00:00Z',
      }],
      function: null,
      run: null,
    }],
    groups: [{
      id: GROUP_A,
      canvasId: CANVAS_ID,
      title: 'Group',
      transform: { x: 0, y: 0, width: 800, height: 700 },
    }],
    links: [{
      canvasId: CANVAS_ID,
      sourceNodeId: NODE_NOTE,
      targetNodeId: NODE_FN,
    }],
  }
}

/** 测试内版本前进：bigint-safe，避免 JS number 精度问题。 */
function nextVersion(version: string): string {
  return String(BigInt(version) + 1n)
}

/**
 * 全量 upsert patch：从 baseVersion 前进到 snapshot 的版本。
 * 模拟「服务端已把命令效果写进投影」后的连续 patch 载荷。
 */
function diffPatch(snapshotValue: CanvasSnapshotDTO, baseVersion: number | string): CanvasPatchDTO {
  return {
    baseVersion: String(baseVersion),
    version: snapshotValue.document.version,
    groups: snapshotValue.groups.map((group) => ({ op: 'UPSERT', group })),
    nodes: snapshotValue.nodes.map((node) => ({ op: 'UPSERT', node })),
    links: snapshotValue.links.map((link) => ({ op: 'UPSERT', link })),
  }
}

/** 模拟服务端命令应用：创建节点 + 版本前进（与命令携带的客户端 UUID 保持一致）。 */
function applyCommandBatch(current: CanvasSnapshotDTO, commands: CanvasCommandDTO[]): CanvasSnapshotDTO {
  let nodes = current.nodes
  for (const command of commands) {
    if (
      command.type === 'CREATE_FUNCTION_NODE'
      || command.type === 'CREATE_TEXT_NODE'
      || command.type === 'CREATE_RESOURCE_NODE'
    ) {
      nodes = [...nodes, {
        id: command.nodeId,
        canvasId: current.document.id,
        name: command.name,
        transform: command.transform,
        groupId: null,
        resources: [],
        function: command.type === 'CREATE_FUNCTION_NODE'
          ? { modelKey: command.modelKey, configJson: command.configJson }
          : null,
        run: null,
      }]
    }
  }
  return {
    ...current,
    document: { ...current.document, version: nextVersion(current.document.version) },
    nodes,
  }
}

function Wrapper({ children }: PropsWithChildren) {
  const [client] = useState(() => new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  }))
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((nextResolve, nextReject) => {
    resolve = nextResolve
    reject = nextReject
  })
  return { promise, resolve, reject }
}

describe('useCanvasController real snapshot runtime', () => {
  let current: CanvasSnapshotDTO
  let commands: ApplyCanvasCommandsRequestDTO[]

  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    current = snapshot()
    commands = []
    vi.mocked(getCanvas).mockImplementation(async () => current)
    vi.mocked(getCanvasChanges).mockResolvedValue({ patches: [], snapshot: null })
    vi.mocked(createCanvasRealtimeStream).mockImplementation(() => new FakeEventSource())
    vi.mocked(listCanvasFunctionModels).mockResolvedValue([{
      key: 'fake-image',
      label: 'Fake Image',
      outputKind: 'IMAGE',
      referencePolicy: { allowedKinds: ['IMAGE'], maxReferences: 1, maxByKind: {} },
      parameters: [{
        key: 'ratio',
        label: 'Ratio',
        type: 'ENUM',
        required: false,
        defaultValue: 'AUTO',
        options: ['AUTO'],
        min: null,
        max: null,
      }],
      available: true,
      unavailableReason: null,
    }])
    vi.mocked(postCanvasCommands).mockImplementation(async (_canvasId, request) => {
      commands.push(request)
      const before = current.document.version
      current = applyCommandBatch(current, request.commands)
      return diffPatch(current, before)
    })
    vi.mocked(storageService.reserveUpload).mockResolvedValue({
      id: UPLOAD_ID,
      state: 'PENDING',
      blobId: null,
      presignedPut: {
        method: 'PUT',
        url: 'https://s3.example/upload',
        headers: { 'If-None-Match': '*' },
      },
      expiresAt: '2026-08-10T00:15:00Z',
    })
    vi.mocked(storageService.uploadFile).mockResolvedValue()
    vi.mocked(storageService.completeUpload).mockResolvedValue({
      id: UPLOAD_ID,
      state: 'READY',
      blobId: 'blob-upload',
      presignedPut: null,
      expiresAt: '2026-08-10T00:15:00Z',
    })
    vi.mocked(startCanvasFunctionRun).mockResolvedValue({
      nodeId: NODE_FN,
      requestId: REQUEST_STARTED,
      status: 'RUNNING',
      stage: 'QUEUED',
      error: null,
      updatedAt: '2026-08-10T00:00:00Z',
    })
    vi.mocked(getCanvasFunctionRun).mockResolvedValue({
      nodeId: NODE_FN,
      requestId: REQUEST_STARTED,
      status: 'FAILED',
      stage: 'FAILED',
      error: 'fake failure',
      updatedAt: '2026-08-10T00:00:01Z',
    })
    vi.mocked(cancelCanvasFunctionRun).mockResolvedValue({
      nodeId: NODE_FN,
      requestId: REQUEST_STARTED,
      status: 'CANCELLED',
      stage: 'CANCELLED',
      error: null,
      updatedAt: '2026-08-10T00:00:02Z',
    })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('starts an initial canvas in editor mode and queries its snapshot immediately', async () => {
    // Direct routes must not wait for an in-memory openEditor transition before loading data.
    localStorage.removeItem(canvasViewportStorageKey(CANVAS_ID))
    const { result } = renderHook(() => useCanvasController(CANVAS_ID), { wrapper: Wrapper })

    expect(result.current.state.view).toBe('editor')
    expect(result.current.state.canvasId).toBe(CANVAS_ID)
    expect(result.current.initialFitPending).toBe(true)
    await waitFor(() => expect(result.current.snapshot?.document.id).toBe(CANVAS_ID))
    expect(getCanvas).toHaveBeenCalledWith(CANVAS_ID, expect.objectContaining({
      signal: expect.any(AbortSignal),
    }))
  })

  it('drives commands, local UI state, uploads, and keyboard actions from one server snapshot', async () => {
    const { result } = renderHook(() => useCanvasController(), { wrapper: Wrapper })
    act(() => result.current.openEditor(CANVAS_ID))
    await waitFor(() => expect(result.current.snapshot?.document.id).toBe(CANVAS_ID))
    await waitFor(() => expect(result.current.models).toHaveLength(1))

    act(() => {
      result.current.setSelection([NODE_NOTE])
      result.current.renameNode(NODE_NOTE, ' Renamed ')
    })
    await waitFor(() => expect(commands.some((request) => (
      request.commands[0]?.type === 'RENAME_NODE'
    ))).toBe(true))

    act(() => result.current.nodeCallbacks.editTextNode(
      result.current.snapshot ? {
        ...result.current.snapshot.nodes[0],
        resources: [{
          id: RES_NOTE,
          canvasId: CANVAS_ID,
          ownerNodeId: NODE_NOTE,
          resourceIndex: 0,
          blobId: null,
          name: 'note.md',
          textContent: 'note',
          kind: 'TEXT',
          mediaType: 'text/markdown',
          sizeBytes: 4,
          width: null,
          height: null,
          durationMs: null,
          createdAt: '2026-08-10T00:00:00Z',
        }],
        function: null,
        run: null,
      } : (() => {
        throw new Error('missing snapshot')
      })(),
    ))
    act(() => {
      result.current.setTextEditorDraft({ markdown: 'updated' })
    })
    act(() => {
      result.current.saveTextEditor()
    })
    await waitFor(() => expect(commands.some((request) => (
      request.commands[0]?.type === 'UPDATE_TEXT_NODE'
    ))).toBe(true))

    act(() => {
      result.current.createTextNode()
      result.current.createFunctionNode('IMAGE')
      result.current.createFunctionNode('VIDEO')
    })
    act(() => {
      result.current.saveTextEditor()
    })
    await waitFor(() => expect(commands.some((request) => (
      request.commands[0]?.type === 'CREATE_FUNCTION_NODE'
    ))).toBe(true))
    expect(result.current.state.toast).toContain('没有可用的视频模型')

    act(() => {
      result.current.setSelection([])
    })
    act(() => {
      result.current.createGroup()
      result.current.ungroupSelection()
    })
    act(() => {
      result.current.setSelection([NODE_NOTE])
    })
    act(() => {
      result.current.createGroup()
    })
    act(() => {
      result.current.setSelection([NODE_FN, NODE_IMG])
    })
    act(() => {
      result.current.ungroupSelection()
    })
    await waitFor(() => expect(commands.some((request) => (
      request.commands[0]?.type === 'CREATE_GROUP'
    ))).toBe(true))
    await waitFor(() => expect(commands.some((request) => (
      request.commands[0]?.type === 'UNGROUP'
    ))).toBe(true))

    await act(async () => {
      await result.current.uploadFiles([
        new File(['bad'], 'bad.bin', { type: 'application/octet-stream' }),
        new File(['png'], 'upload.png'),
      ])
    })
    expect(storageService.reserveUpload).toHaveBeenCalledOnce()
    expect(storageService.reserveUpload).toHaveBeenCalledWith({
      filename: 'upload.png',
      mediaType: 'image/png',
      sizeBytes: 3,
      sha256: FAKE_SHA256,
    })
    expect(storageService.uploadFile).toHaveBeenCalledOnce()
    expect(storageService.completeUpload).toHaveBeenCalledOnce()
    expect(storageService.completeUpload).toHaveBeenCalledWith(UPLOAD_ID)
    expect(probeCanvasFileMetadata).toHaveBeenCalledWith(expect.any(File), 'IMAGE')
    expect(commands.some((request) => (
      request.commands[0]?.type === 'CREATE_RESOURCE_NODE'
    ))).toBe(true)
    const uploadedNode = commands
      .flatMap((request) => request.commands)
      .find((command) => command.type === 'CREATE_RESOURCE_NODE')
    expect(uploadedNode).toMatchObject({
      transform: { width: 256, height: 344 },
      uploadIds: [UPLOAD_ID],
    })
    // 命令只引用共享存储 upload 句柄，绝不携带 resourceIds 或 canvas-scoped 上传 id。
    expect(uploadedNode).not.toHaveProperty('resourceIds')
    // 创建类命令携带客户端 UUID 实体 id。
    expect(uploadedNode).toMatchObject({
      nodeId: expect.stringMatching(/^[0-9a-f-]{36}$/i),
    })

    act(() => {
      result.current.createLink(NODE_NOTE, NODE_FN)
      result.current.deleteLink(NODE_NOTE, NODE_FN)
      result.current.setSelection([`group:${GROUP_A}`, NODE_NOTE])
    })
    act(() => {
      result.current.deleteSelection()
    })
    await waitFor(() => expect(commands.some((request) => (
      request.commands.some((command) => command.type === 'DELETE_GROUP')
    ))).toBe(true))
    const deleteNodeCommandCount = commands.filter((request) => (
      request.commands.some((command) => command.type === 'DELETE_NODE')
    )).length
    act(() => result.current.deleteNode(NODE_NOTE))
    await waitFor(() => expect(commands.filter((request) => (
      request.commands.some((command) => command.type === 'DELETE_NODE')
    ))).toHaveLength(deleteNodeCommandCount + 1))

    act(() => {
      result.current.toggleAddMenu()
      result.current.closeAddMenu()
      result.current.setAddMenuIndex(2)
      result.current.openThread()
      result.current.collapseThread()
      result.current.setViewport({ x: 1, y: 2, zoom: 0.5 })
    })
    expect(result.current.state.viewport).toEqual({ x: 1, y: 2, zoom: 0.5 })

    const fit = vi.fn()
    const focus = vi.fn()
    const zoom = vi.fn()
    result.current.fitViewRef.current = fit
    result.current.focusSelectionRef.current = focus
    result.current.zoomRef.current = zoom
    act(() => {
      window.dispatchEvent(new KeyboardEvent('keydown', { key: '0' }))
      window.dispatchEvent(new KeyboardEvent('keydown', { key: '1' }))
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'f' }))
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 't' }))
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', metaKey: true }))
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    })
    expect(fit).toHaveBeenCalledOnce()
    expect(focus).toHaveBeenCalledOnce()
    expect(zoom).toHaveBeenCalledWith(1)
    expect(result.current.state.selectedIds).toEqual([])

    act(() => result.current.openLibrary())
    expect(result.current.state.view).toBe('library')
  })

  it('drives group context-menu commands with correct UNGROUP/DELETE_GROUP semantics', async () => {
    // UNGROUP 由服务端同时删除边界：单组解组只发 UNGROUP，绝不追加 DELETE_GROUP。
    const { result } = renderHook(() => useCanvasController(), { wrapper: Wrapper })
    act(() => result.current.openEditor(CANVAS_ID))
    await waitFor(() => expect(result.current.snapshot?.document.id).toBe(CANVAS_ID))

    act(() => result.current.ungroupGroup(GROUP_A))
    await waitFor(() => expect(commands.some((request) => (
      request.commands[0]?.type === 'UNGROUP'
    ))).toBe(true))
    const ungroupBodies = commands.filter((request) => (
      request.commands[0]?.type === 'UNGROUP'
    ))
    expect(ungroupBodies).toHaveLength(1)
    expect(ungroupBodies[0]?.commands).toEqual([{
      type: 'UNGROUP',
      groupId: GROUP_A,
      memberNodeIds: expect.arrayContaining([NODE_FN, NODE_IMG]),
    }])
    expect(ungroupBodies[0]?.commands.some((command) => (
      command.type === 'DELETE_GROUP'
    ))).toBe(false)

    act(() => result.current.renameGroup(GROUP_A, '  新分组  '))
    await waitFor(() => expect(commands.some((request) => (
      request.commands[0]?.type === 'RENAME_GROUP'
    ))).toBe(true))
    expect(commands.find((request) => (
      request.commands[0]?.type === 'RENAME_GROUP'
    ))?.commands).toEqual([{ type: 'RENAME_GROUP', groupId: GROUP_A, title: '新分组' }])

    act(() => result.current.renameGroup(GROUP_A, '   '))
    expect(commands.filter((request) => (
      request.commands[0]?.type === 'RENAME_GROUP'
    ))).toHaveLength(1)

    act(() => result.current.deleteGroup(GROUP_A))
    await waitFor(() => expect(commands.some((request) => (
      request.commands[0]?.type === 'DELETE_GROUP'
    ))).toBe(true))
    expect(commands.find((request) => (
      request.commands[0]?.type === 'DELETE_GROUP'
    ))?.commands).toEqual([{ type: 'DELETE_GROUP', groupId: GROUP_A }])
  })

  it('persists a text-editor rename together with the markdown update', async () => {
    const { result } = renderHook(() => useCanvasController(), { wrapper: Wrapper })
    act(() => result.current.openEditor(CANVAS_ID))
    await waitFor(() => expect(result.current.snapshot?.document.id).toBe(CANVAS_ID))
    const node = result.current.snapshot?.nodes.find((item) => item.id === NODE_NOTE)
    expect(node?.name).toBe('Note')

    act(() => result.current.nodeCallbacks.editTextNode(result.current.snapshot?.nodes[0] as never)
    )
    act(() => result.current.setTextEditorDraft({ name: 'Note v2', markdown: 'updated' }))
    act(() => result.current.saveTextEditor())
    await waitFor(() => expect(commands.some((request) => (
      request.commands[0]?.type === 'UPDATE_TEXT_NODE'
    ))).toBe(true))
    const body = commands.find((request) => (
      request.commands[0]?.type === 'UPDATE_TEXT_NODE'
    ))
    expect(body?.commands).toEqual([
      { type: 'UPDATE_TEXT_NODE', nodeId: NODE_NOTE, markdown: 'updated' },
      { type: 'RENAME_NODE', nodeId: NODE_NOTE, name: 'Note v2' },
    ])
    // 名称未变化时只发 UPDATE_TEXT_NODE。
    act(() => result.current.nodeCallbacks.editTextNode(result.current.snapshot?.nodes[0] as never)
    )
    act(() => result.current.setTextEditorDraft({ name: 'Note', markdown: 'again' }))
    act(() => result.current.saveTextEditor())
    await waitFor(() => expect(commands.filter((request) => (
      request.commands[0]?.type === 'UPDATE_TEXT_NODE'
    ))).toHaveLength(2))
    const second = commands.filter((request) => (
      request.commands[0]?.type === 'UPDATE_TEXT_NODE'
    ))[1]
    expect(second?.commands).toEqual([
      { type: 'UPDATE_TEXT_NODE', nodeId: NODE_NOTE, markdown: 'again' },
    ])
  })

  it('allocates unique aliases from the command queue snapshot across rapid creates and uploads', async () => {
    // Queue-owned snapshots plus in-flight reservations cover both synchronous clicks and sequential files.
    // READY 预留模拟 sha256 命中去重：跳过直传，直接 complete。
    vi.mocked(storageService.reserveUpload).mockResolvedValue({
      id: 'eeeeeeee-0000-4000-8000-000000000090',
      state: 'READY',
      blobId: 'blob-existing',
      presignedPut: null,
      expiresAt: '2026-08-10T00:15:00Z',
    })
    vi.mocked(storageService.completeUpload).mockImplementation(async (uploadId) => ({
      id: uploadId,
      state: 'READY',
      blobId: 'blob-dedup',
      presignedPut: null,
      expiresAt: '2026-08-10T00:15:00Z',
    }))

    const { result } = renderHook(() => useCanvasController(), { wrapper: Wrapper })
    act(() => result.current.openEditor(CANVAS_ID))
    await waitFor(() => expect(result.current.models).toHaveLength(1))

    act(() => {
      result.current.createFunctionNode('IMAGE')
      result.current.createFunctionNode('IMAGE')
    })
    await waitFor(() => expect(commands.filter((request) => (
      request.commands[0]?.type === 'CREATE_FUNCTION_NODE'
    ))).toHaveLength(2))

    act(() => {
      result.current.createTextNode()
      result.current.setTextEditorDraft({ name: '  图片生成  ' })
    })
    act(() => result.current.saveTextEditor())
    await waitFor(() => expect(commands.some((request) => (
      request.commands[0]?.type === 'CREATE_TEXT_NODE'
    ))).toBe(true))

    await act(async () => {
      await result.current.uploadFiles([
        new File(['one'], 'same.heic'),
        new File(['two'], 'same.heic'),
      ])
    })

    const aliases = commands.flatMap((request) => request.commands.flatMap((command) => (
      command.type === 'CREATE_FUNCTION_NODE'
      || command.type === 'CREATE_TEXT_NODE'
      || command.type === 'CREATE_RESOURCE_NODE'
        ? [command.name]
        : []
    )))
    expect(aliases).toEqual([
      '图片生成',
      '图片生成 2',
      '图片生成 3',
      'same.heic',
      'same.heic 2',
    ])
    // READY 预留 = sha256 命中：跳过直传；complete 返回同一句柄，命令只引用 uploadIds。
    expect(storageService.uploadFile).not.toHaveBeenCalled()
    expect(storageService.completeUpload).toHaveBeenCalledTimes(2)
    expect(storageService.reserveUpload).toHaveBeenNthCalledWith(1, {
      filename: 'same.heic',
      mediaType: 'image/heic',
      sizeBytes: 3,
      sha256: FAKE_SHA256,
    })
    expect(storageService.reserveUpload).toHaveBeenNthCalledWith(2, {
      filename: 'same.heic',
      mediaType: 'image/heic',
      sizeBytes: 3,
      sha256: FAKE_SHA256,
    })
    const uploadCommands = commands
      .flatMap((request) => request.commands)
      .filter((command) => command.type === 'CREATE_RESOURCE_NODE')
    expect(uploadCommands).toHaveLength(2)
    expect(uploadCommands.every((command) => (
      command.uploadIds.length === 1 && /^[0-9a-f-]{36}$/i.test(command.uploadIds[0] ?? '')
    ))).toBe(true)
  })

  it('flushes debounced config before UUID start, polls only run state, preserves old output on failure, and cancels', async () => {
    // The call order and unchanged Resource id prove config/run orchestration never introduces a second snapshot.
    const { result } = renderHook(() => useCanvasController(), { wrapper: Wrapper })
    act(() => result.current.openEditor(CANVAS_ID))
    await waitFor(() => expect(result.current.snapshot?.document.id).toBe(CANVAS_ID))

    act(() => {
      result.current.scheduleFunctionConfig(NODE_FN, 'fake-image', {
        prompt: { segments: [{ type: 'TEXT', text: 'new prompt' }] },
        parameters: { ratio: 'AUTO' },
      })
    })
    await act(async () => {
      await result.current.startFunctionRun(NODE_FN)
    })

    expect(commands.at(-1)?.commands).toEqual([{
      type: 'UPDATE_FUNCTION',
      nodeId: NODE_FN,
      modelKey: 'fake-image',
      configJson: JSON.stringify({
        prompt: { segments: [{ type: 'TEXT', text: 'new prompt' }] },
        parameters: { ratio: 'AUTO' },
      }),
    }])
    expect(startCanvasFunctionRun).toHaveBeenCalledWith(
      CANVAS_ID,
      NODE_FN,
      { requestId: expect.stringMatching(/^[0-9a-f-]{36}$/i) },
    )
    expect(vi.mocked(postCanvasCommands).mock.invocationCallOrder[0]).toBeLessThan(
      vi.mocked(startCanvasFunctionRun).mock.invocationCallOrder[0] ?? Number.MAX_SAFE_INTEGER,
    )

    await waitFor(() => {
      expect(result.current.snapshot?.nodes.find((node) => node.id === NODE_FN)?.run?.status).toBe('FAILED')
    })
    expect(result.current.snapshot?.nodes.find((node) => node.id === NODE_FN)?.resources[0]?.id).toBe(RES_OUTPUT)
    expect(getCanvasFunctionRun).toHaveBeenCalledWith(CANVAS_ID, NODE_FN, expect.objectContaining({
      signal: expect.any(AbortSignal),
    }))

    await act(async () => {
      await result.current.cancelFunctionRun(NODE_FN, REQUEST_STARTED)
    })
    expect(cancelCanvasFunctionRun).toHaveBeenCalledWith(
      CANVAS_ID,
      NODE_FN,
      { requestId: REQUEST_STARTED },
    )
    await waitFor(() => {
      expect(result.current.snapshot?.nodes.find((node) => node.id === NODE_FN)?.run?.status).toBe('CANCELLED')
    })
  })

  it('retains a failed config draft so start retries the save before posting the run', async () => {
    vi.mocked(postCanvasCommands).mockRejectedValueOnce(new Error('save failed'))
    const { result } = renderHook(() => useCanvasController(), { wrapper: Wrapper })
    act(() => result.current.openEditor(CANVAS_ID))
    await waitFor(() => expect(result.current.snapshot?.document.id).toBe(CANVAS_ID))

    act(() => {
      result.current.scheduleFunctionConfig(NODE_FN, 'fake-image', {
        prompt: { segments: [{ type: 'TEXT', text: 'retry prompt' }] },
        parameters: { ratio: 'AUTO' },
      })
    })
    await act(async () => {
      await expect(result.current.flushFunctionConfig(NODE_FN)).rejects.toThrow('save failed')
    })
    expect(startCanvasFunctionRun).not.toHaveBeenCalled()

    await act(async () => {
      await result.current.startFunctionRun(NODE_FN)
    })

    expect(postCanvasCommands).toHaveBeenCalledTimes(2)
    expect(commands.at(-1)?.commands).toEqual([{
      type: 'UPDATE_FUNCTION',
      nodeId: NODE_FN,
      modelKey: 'fake-image',
      configJson: JSON.stringify({
        prompt: { segments: [{ type: 'TEXT', text: 'retry prompt' }] },
        parameters: { ratio: 'AUTO' },
      }),
    }])
    expect(vi.mocked(postCanvasCommands).mock.invocationCallOrder[1]).toBeLessThan(
      vi.mocked(startCanvasFunctionRun).mock.invocationCallOrder[0] ?? Number.MAX_SAFE_INTEGER,
    )
  })

  it('deduplicates concurrent flushes and drains a newer draft before start', async () => {
    const firstSave = deferred<CanvasPatchDTO>()
    const secondSave = deferred<CanvasPatchDTO>()
    vi.mocked(postCanvasCommands)
      .mockImplementationOnce(async (_canvasId, request) => {
        commands.push(request)
        return firstSave.promise
      })
      .mockImplementationOnce(async (_canvasId, request) => {
        commands.push(request)
        return secondSave.promise
      })
    const { result } = renderHook(() => useCanvasController(), { wrapper: Wrapper })
    act(() => result.current.openEditor(CANVAS_ID))
    await waitFor(() => expect(result.current.snapshot?.document.id).toBe(CANVAS_ID))

    act(() => {
      result.current.scheduleFunctionConfig(NODE_FN, 'fake-image', {
        prompt: { segments: [{ type: 'TEXT', text: 'first prompt' }] },
        parameters: { ratio: 'AUTO' },
      })
    })
    let firstFlush!: Promise<void>
    let duplicateFlush!: Promise<void>
    act(() => {
      firstFlush = result.current.flushFunctionConfig(NODE_FN)
      duplicateFlush = result.current.flushFunctionConfig(NODE_FN)
    })
    expect(duplicateFlush).toBe(firstFlush)
    await waitFor(() => expect(postCanvasCommands).toHaveBeenCalledTimes(1))

    act(() => {
      result.current.scheduleFunctionConfig(NODE_FN, 'fake-image', {
        prompt: { segments: [{ type: 'TEXT', text: 'latest prompt' }] },
        parameters: { ratio: 'AUTO' },
      })
    })
    let start!: Promise<void>
    act(() => {
      start = result.current.startFunctionRun(NODE_FN)
    })
    expect(startCanvasFunctionRun).not.toHaveBeenCalled()

    await act(async () => {
      firstSave.resolve(diffPatch(snapshot(1), 0))
      await firstSave.promise
    })
    await waitFor(() => expect(postCanvasCommands).toHaveBeenCalledTimes(2))
    expect(startCanvasFunctionRun).not.toHaveBeenCalled()
    expect(commands.map((request) => request.commands)).toEqual([
      [{
        type: 'UPDATE_FUNCTION',
        nodeId: NODE_FN,
        modelKey: 'fake-image',
        configJson: JSON.stringify({
          prompt: { segments: [{ type: 'TEXT', text: 'first prompt' }] },
          parameters: { ratio: 'AUTO' },
        }),
      }],
      [{
        type: 'UPDATE_FUNCTION',
        nodeId: NODE_FN,
        modelKey: 'fake-image',
        configJson: JSON.stringify({
          prompt: { segments: [{ type: 'TEXT', text: 'latest prompt' }] },
          parameters: { ratio: 'AUTO' },
        }),
      }],
    ])

    await act(async () => {
      secondSave.resolve(diffPatch(snapshot(2), 1))
      await Promise.all([firstFlush, duplicateFlush, start])
    })
    expect(startCanvasFunctionRun).toHaveBeenCalledTimes(1)
  })

  it.each(['RUNNING', 'FAILED'] as const)(
    'recovers a lost start response from the matching server run in %s state',
    async (status) => {
      let submittedRequestId = ''
      vi.mocked(startCanvasFunctionRun).mockImplementation(async (_canvasId, _nodeId, request) => {
        submittedRequestId = request.requestId
        throw new Error('start response lost')
      })
      vi.mocked(getCanvasFunctionRun).mockImplementation(async () => ({
        nodeId: NODE_FN,
        requestId: submittedRequestId,
        status,
        stage: status === 'RUNNING' ? 'QUEUED' : 'FAILED',
        error: status === 'RUNNING' ? null : 'safe failure',
        updatedAt: '2026-08-10T00:00:01Z',
      }))
      const { result } = renderHook(() => useCanvasController(), { wrapper: Wrapper })
      act(() => result.current.openEditor(CANVAS_ID))
      await waitFor(() => expect(result.current.snapshot?.document.id).toBe(CANVAS_ID))

      await act(async () => {
        await result.current.startFunctionRun(NODE_FN)
      })

      await waitFor(() => {
        expect(result.current.snapshot?.nodes.find((node) => node.id === NODE_FN)?.run).toEqual(
          expect.objectContaining({ requestId: submittedRequestId, status }),
        )
      })
      expect(submittedRequestId).toMatch(/^[0-9a-f-]{36}$/i)
      expect(result.current.state.toast).toBeNull()
    },
  )

  it('refetches the authoritative snapshot after a successful polled run', async () => {
    // Success is complete only when the snapshot query replaces old output with backend materialized resources.
    vi.mocked(getCanvasFunctionRun).mockResolvedValue({
      nodeId: NODE_FN,
      requestId: REQUEST_STARTED,
      status: 'SUCCEEDED',
      stage: 'SUCCEEDED',
      error: null,
      updatedAt: '2026-08-10T00:00:01Z',
    })
    vi.mocked(getCanvas).mockImplementation(async () => {
      const latest = current
      if (vi.mocked(getCanvas).mock.calls.length < 2) {
        return latest
      }
      return {
        ...latest,
        nodes: latest.nodes.map((node) => node.id === NODE_FN ? {
          ...node,
          resources: [{
            id: 'ffffffff-0000-4000-8000-000000000031',
            canvasId: CANVAS_ID,
            ownerNodeId: NODE_FN,
            resourceIndex: 0,
            blobId: 'blob-new-output',
            name: 'new-output.png',
            textContent: null,
            kind: 'IMAGE',
            mediaType: 'image/png',
            sizeBytes: 4,
            width: null,
            height: null,
            durationMs: null,
            createdAt: '2026-08-10T00:00:01Z',
          }],
          run: {
            nodeId: NODE_FN,
            requestId: REQUEST_STARTED,
            status: 'SUCCEEDED',
            stage: 'SUCCEEDED',
            error: null,
            updatedAt: '2026-08-10T00:00:01Z',
          },
        } : node),
      }
    })
    const { result } = renderHook(() => useCanvasController(), { wrapper: Wrapper })
    act(() => result.current.openEditor(CANVAS_ID))
    await waitFor(() => expect(result.current.snapshot?.document.id).toBe(CANVAS_ID))
    await act(async () => {
      await result.current.startFunctionRun(NODE_FN)
    })
    await waitFor(() => {
      expect(result.current.snapshot?.nodes.find((node) => node.id === NODE_FN)?.resources[0]?.id).toBe('ffffffff-0000-4000-8000-000000000031')
    })
    expect(getCanvas).toHaveBeenCalledTimes(2)
  })

  it('ignores a stale poll response from an older FunctionRun request', async () => {
    // Snapshot 中的 requestId 是运行身份；旧请求的缓存/迟到响应不能覆盖它。
    const currentSnapshot = snapshot(1)
    currentSnapshot.nodes = currentSnapshot.nodes.map((node) => node.id === NODE_FN ? {
      ...node,
      run: {
        nodeId: NODE_FN,
        requestId: REQUEST_CURRENT,
        status: 'RUNNING',
        stage: 'QUEUED',
        error: null,
        updatedAt: '2026-08-10T00:00:02Z',
      },
    } : node)
    const stale = deferred<Awaited<ReturnType<typeof getCanvasFunctionRun>>>()
    vi.mocked(getCanvas).mockResolvedValue(currentSnapshot)
    vi.mocked(getCanvasFunctionRun).mockReturnValue(stale.promise)
    const { result } = renderHook(() => useCanvasController(CANVAS_ID), { wrapper: Wrapper })
    await waitFor(() => expect(
      result.current.snapshot?.nodes.find((node) => node.id === NODE_FN)?.run?.requestId,
    ).toBe(REQUEST_CURRENT))
    await waitFor(() => expect(getCanvasFunctionRun).toHaveBeenCalled())

    await act(async () => {
      stale.resolve({
        nodeId: NODE_FN,
        requestId: REQUEST_OLD,
        status: 'FAILED',
        stage: 'FAILED',
        error: 'old failure',
        updatedAt: '2026-08-10T00:00:01Z',
      })
      await stale.promise
    })

    await waitFor(() => {
      expect(result.current.snapshot?.nodes.find((node) => node.id === NODE_FN)?.run).toMatchObject({
        requestId: REQUEST_CURRENT,
        status: 'RUNNING',
      })
    })
  })

  it('deletes selected links before their incident nodes in one atomic batch', async () => {
    // DELETE_NODE 会移除 incident Link，因此同批显式 DELETE_LINK 必须先执行。
    const { result } = renderHook(() => useCanvasController(CANVAS_ID), { wrapper: Wrapper })
    await waitFor(() => expect(result.current.snapshot?.document.id).toBe(CANVAS_ID))
    act(() => {
      result.current.setSelection(
        [NODE_NOTE],
        [{ sourceNodeId: NODE_NOTE, targetNodeId: NODE_FN }],
      )
    })
    act(() => {
      result.current.deleteSelection()
    })

    await waitFor(() => expect(commands).toHaveLength(1))
    expect(commands[0]?.commands.map((command) => command.type)).toEqual([
      'DELETE_LINK',
      'DELETE_NODE',
    ])
  })
})
