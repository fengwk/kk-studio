import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import { useState, type PropsWithChildren } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useCanvasController } from '@/features/canvas/useCanvasController'
import { canvasViewportStorageKey } from '@/features/canvas/viewport-storage'
import type {
  ApplyCanvasCommandsRequestDTO,
  CanvasCommandDTO,
  CanvasFunctionRunDTO,
  CanvasPatchDTO,
  CanvasSnapshotDTO,
} from '@/shared/api/contracts/studio'
import {
  cancelCanvasFunctionRun,
  getCanvas,
  getCanvasChanges,
  getCanvasFunctionRun,
  listCanvasFunctionModels,
  postCanvasCommands,
  startCanvasFunctionRun,
} from '@/shared/api/studio-service'
import { storageService } from '@/shared/api/storage-service'

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
const REQUEST_NEW = 'eeeeeeee-0000-4000-8000-000000000004'
const { FAKE_SHA256 } = vi.hoisted(() => ({ FAKE_SHA256: 'a'.repeat(64) }))

const { fakeApplicationEvents, canvasEventSubscriptions } = vi.hoisted(() => {
  const subscriptions: Array<{
    resource: unknown
    onSubscribed?: (cursor: string) => void
    onEvent?: (name: string, data: unknown, cursor: string | undefined) => void
    onResync?: () => void
    onError?: (code: string, message: string) => void
  }> = []
  const manager = {
    subscribe: (
      resource: unknown,
      listener: {
        onSubscribed?: (cursor: string) => void
        onEvent?: (name: string, data: unknown, cursor: string | undefined) => void
        onResync?: () => void
        onError?: (code: string, message: string) => void
      },
    ) => {
      const entry = { ...listener, resource }
      subscriptions.push(entry)
      return () => {
        const index = subscriptions.indexOf(entry)
        if (index >= 0) {
          subscriptions.splice(index, 1)
        }
      }
    },
  }
  return {
    fakeApplicationEvents: { useApplicationEvents: () => manager },
    canvasEventSubscriptions: subscriptions,
  }
})
vi.mock('@/shared/app-events', () => ({
  useApplicationEvents: fakeApplicationEvents.useApplicationEvents,
}))

vi.mock('@/shared/api/studio-service', () => ({
  postCanvasCommands: vi.fn(),
  cancelCanvasFunctionRun: vi.fn(),
  getCanvas: vi.fn(),
  getCanvasChanges: vi.fn(),
  getCanvasFunctionRun: vi.fn(),
  listCanvasFunctionModels: vi.fn(),
  startCanvasFunctionRun: vi.fn(),
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

vi.mock('@/features/ai/composer', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/features/ai/composer')>()
  return { ...actual, createWorkerHasher: () => async () => FAKE_SHA256 }
})

/** 模拟应用事件通道的 canvas version 事件（携带 canonical 十进制 version）。 */
function emitCanvasVersion(version: string) {
  act(() => {
    for (const subscription of canvasEventSubscriptions) {
      subscription.onEvent?.('version', { version }, version)
    }
  })
}

/** 模拟应用事件通道的 resync 事件。 */
function emitCanvasResync() {
  act(() => {
    for (const subscription of canvasEventSubscriptions) {
      subscription.onResync?.()
    }
  })
}

/** 在权威快照上投影指定 FunctionRun（测试夹具便捷方法）。 */
function snapshotWithRun(
  version: number | string,
  run: CanvasFunctionRunDTO,
): CanvasSnapshotDTO {
  const next = snapshot(version)
  next.nodes = next.nodes.map((node) => (
    node.id === NODE_FN ? { ...node, run } : node
  ))
  return next
}

function snapshot(version: number | string = 0): CanvasSnapshotDTO {
  return {
    document: {
      id: CANVAS_ID,
      title: 'Board',
      version: String(version),
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
    canvasEventSubscriptions.splice(0)
    localStorage.clear()
    current = snapshot()
    commands = []
    vi.mocked(getCanvas).mockImplementation(async () => current)
    vi.mocked(getCanvasChanges).mockResolvedValue({ patches: [], snapshot: null })
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
    })
    act(() => {
      result.current.setSelection([NODE_NOTE])
    })
    act(() => {
      result.current.createGroup()
    })
    await waitFor(() => expect(commands.some((request) => (
      request.commands[0]?.type === 'CREATE_GROUP'
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
    expect(commands.some((request) => (
      request.commands[0]?.type === 'CREATE_RESOURCE_NODE'
    ))).toBe(true)
    const uploadedNode = commands
      .flatMap((request) => request.commands)
      .find((command) => command.type === 'CREATE_RESOURCE_NODE')
    expect(uploadedNode).toMatchObject({
      transform: { width: 320, height: 246 },
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
    })
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

  it('ungroups a member only after its dragged bounds fully leave the Group body', async () => {
    const { result } = renderHook(() => useCanvasController(), { wrapper: Wrapper })
    act(() => result.current.openEditor(CANVAS_ID))
    await waitFor(() => expect(result.current.snapshot?.document.id).toBe(CANVAS_ID))

    act(() => {
      result.current.moveNodes([{
        id: NODE_FN,
        kind: 'resource',
        // 仍与 x:[0,800] 的 Group body 有正面积交集。
        transform: { x: 760, y: 100, width: 320, height: 260 },
      }])
      result.current.commitTransforms()
    })
    await waitFor(() => expect(commands.some((request) => (
      request.commands[0]?.type === 'UPDATE_NODE_TRANSFORMS'
    ))).toBe(true))
    expect(commands.some((request) => (
      request.commands.some((command) => command.type === 'UNGROUP')
    ))).toBe(false)

    act(() => {
      result.current.moveNodes([{
        id: NODE_FN,
        kind: 'resource',
        // 左边界恰好贴住 Group 右边界：无正面积交集，必须解绑。
        transform: { x: 800, y: 100, width: 320, height: 260 },
      }])
      result.current.commitTransforms()
    })
    await waitFor(() => expect(commands.some((request) => (
      request.commands.some((command) => command.type === 'UNGROUP')
    ))).toBe(true))
    const detachBatch = commands.find((request) => (
      request.commands.some((command) => command.type === 'UNGROUP')
    ))
    expect(detachBatch?.commands).toEqual([
      {
        type: 'UPDATE_NODE_TRANSFORMS',
        updates: [{
          nodeId: NODE_FN,
          transform: { x: 800, y: 100, width: 320, height: 260 },
        }],
      },
      {
        type: 'UNGROUP',
        groupId: GROUP_A,
        memberNodeIds: [NODE_FN],
      },
    ])
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

  it('flushes config before start, projects RUNNING without polling, converges via version events, and cancels', async () => {
    // 调用顺序与不变的 Resource id 证明 config/run 编排不引入第二个快照；run 状态由
    // version 事件驱动的 changes 收敛，绝无固定间隔轮询。
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

    // start 成功：本地即时投影 RUNNING，且不触发任何 run endpoint（无 polling、无 fallback）。
    await waitFor(() => {
      expect(result.current.snapshot?.nodes.find((node) => node.id === NODE_FN)?.run?.status).toBe('RUNNING')
    })
    expect(getCanvasFunctionRun).not.toHaveBeenCalled()

    // 服务端推进真实版本序列：start=2、checkpoint=3、terminal=4（flush 的 UPDATE_FUNCTION 已把
    // 本地推进到 version '1'）。version 事件按 afterVersion 依次回放 patch，不能一步跳到终态。
    const steps: Record<string, CanvasFunctionRunDTO> = {
      '1': {
        nodeId: NODE_FN,
        requestId: REQUEST_STARTED,
        status: 'RUNNING',
        stage: 'QUEUED',
        error: null,
        updatedAt: '2026-08-10T00:00:01Z',
      },
      '2': {
        nodeId: NODE_FN,
        requestId: REQUEST_STARTED,
        status: 'RUNNING',
        stage: 'CHECKPOINTED',
        error: null,
        updatedAt: '2026-08-10T00:00:02Z',
      },
      '3': {
        nodeId: NODE_FN,
        requestId: REQUEST_STARTED,
        status: 'FAILED',
        stage: 'FAILED',
        error: 'fake failure',
        updatedAt: '2026-08-10T00:00:03Z',
      },
    }
    vi.mocked(getCanvasChanges).mockImplementation(async (_canvasId, afterVersion) => {
      const run = steps[afterVersion]
      if (!run) {
        return { patches: [], snapshot: null }
      }
      return {
        patches: [diffPatch(snapshotWithRun(nextVersion(afterVersion), run), afterVersion)],
        snapshot: null,
      }
    })
    emitCanvasVersion('2')
    await waitFor(() => {
      const node = result.current.snapshot?.nodes.find((item) => item.id === NODE_FN)
      expect(node?.run?.status).toBe('RUNNING')
      expect(node?.run?.stage).toBe('QUEUED')
      expect(result.current.snapshot?.document.version).toBe('2')
    })
    emitCanvasVersion('3')
    await waitFor(() => {
      const node = result.current.snapshot?.nodes.find((item) => item.id === NODE_FN)
      expect(node?.run?.stage).toBe('CHECKPOINTED')
      expect(result.current.snapshot?.document.version).toBe('3')
    })
    emitCanvasVersion('4')
    await waitFor(() => {
      const node = result.current.snapshot?.nodes.find((item) => item.id === NODE_FN)
      expect(node?.run?.status).toBe('FAILED')
      expect(node?.run?.stage).toBe('FAILED')
      expect(result.current.snapshot?.document.version).toBe('4')
    })
    // 失败保留旧输出；patch 闭环后不需要再次全量 GET，也没有任何 run polling。
    expect(result.current.snapshot?.nodes.find((node) => node.id === NODE_FN)?.resources[0]?.id).toBe(RES_OUTPUT)
    expect(getCanvas).toHaveBeenCalledTimes(1)
    expect(getCanvasFunctionRun).not.toHaveBeenCalled()

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

  it('does not poll RUNNING nodes loaded from the authoritative snapshot', async () => {
    // 快照自带 RUNNING run 时也不得建立 run 查询：固定轮询已删除。
    vi.mocked(getCanvas).mockResolvedValue(snapshotWithRun(1, {
      nodeId: NODE_FN,
      requestId: REQUEST_CURRENT,
      status: 'RUNNING',
      stage: 'QUEUED',
      error: null,
      updatedAt: '2026-08-10T00:00:01Z',
    }))
    const { result } = renderHook(() => useCanvasController(CANVAS_ID), { wrapper: Wrapper })
    await waitFor(() => expect(result.current.snapshot?.document.id).toBe(CANVAS_ID))
    await waitFor(() => {
      expect(result.current.snapshot?.nodes.find((node) => node.id === NODE_FN)?.run?.status).toBe('RUNNING')
    })

    expect(getCanvasFunctionRun).not.toHaveBeenCalled()
  })

  it('replays the create/start/checkpoint/terminal version sequence and converges replaced output', async () => {
    // 初始快照 = create function 之后的 version '1'；随后严格按 start=2、checkpoint=3、
    // terminal=4 逐版本回放 patch，每一步断言 stage/status/version。
    vi.mocked(getCanvas).mockResolvedValue(snapshot(1))
    const { result } = renderHook(() => useCanvasController(), { wrapper: Wrapper })
    act(() => result.current.openEditor(CANVAS_ID))
    await waitFor(() => expect(result.current.snapshot?.document.version).toBe('1'))

    await act(async () => {
      await result.current.startFunctionRun(NODE_FN)
    })
    await waitFor(() => {
      expect(result.current.snapshot?.nodes.find((node) => node.id === NODE_FN)?.run?.status).toBe('RUNNING')
    })
    expect(getCanvasFunctionRun).not.toHaveBeenCalled()

    const terminal = snapshotWithRun(4, {
      nodeId: NODE_FN,
      requestId: REQUEST_STARTED,
      status: 'SUCCEEDED',
      stage: 'SUCCEEDED',
      error: null,
      updatedAt: '2026-08-10T00:00:03Z',
    })
    terminal.nodes = terminal.nodes.map((node) => node.id === NODE_FN ? {
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
        createdAt: '2026-08-10T00:00:03Z',
      }],
    } : node)
    const steps: Record<string, CanvasPatchDTO> = {
      '1': diffPatch(snapshotWithRun(2, {
        nodeId: NODE_FN,
        requestId: REQUEST_STARTED,
        status: 'RUNNING',
        stage: 'QUEUED',
        error: null,
        updatedAt: '2026-08-10T00:00:01Z',
      }), 1),
      '2': diffPatch(snapshotWithRun(3, {
        nodeId: NODE_FN,
        requestId: REQUEST_STARTED,
        status: 'RUNNING',
        stage: 'CHECKPOINTED',
        error: null,
        updatedAt: '2026-08-10T00:00:02Z',
      }), 2),
      '3': diffPatch(terminal, 3),
    }
    vi.mocked(getCanvasChanges).mockImplementation(async (_canvasId, afterVersion) => {
      const patch = steps[afterVersion]
      return patch ? { patches: [patch], snapshot: null } : { patches: [], snapshot: null }
    })

    emitCanvasVersion('2')
    await waitFor(() => {
      const node = result.current.snapshot?.nodes.find((item) => item.id === NODE_FN)
      expect(node?.run?.status).toBe('RUNNING')
      expect(node?.run?.stage).toBe('QUEUED')
      expect(result.current.snapshot?.document.version).toBe('2')
    })
    emitCanvasVersion('3')
    await waitFor(() => {
      const node = result.current.snapshot?.nodes.find((item) => item.id === NODE_FN)
      expect(node?.run?.stage).toBe('CHECKPOINTED')
      expect(result.current.snapshot?.document.version).toBe('3')
    })
    emitCanvasVersion('4')
    await waitFor(() => {
      const node = result.current.snapshot?.nodes.find((item) => item.id === NODE_FN)
      expect(node?.run?.status).toBe('SUCCEEDED')
      expect(result.current.snapshot?.document.version).toBe('4')
    })
    // terminal patch 携带后端物化后的新输出 Resource；全程只走 changes 闭环，无额外全量 GET。
    expect(result.current.snapshot?.nodes.find((node) => node.id === NODE_FN)?.resources[0]?.id)
      .toBe('ffffffff-0000-4000-8000-000000000031')
    expect(getCanvas).toHaveBeenCalledTimes(1)
  })

  it('ignores a stale local cancel response for an older request', async () => {
    // 快照已收敛到更新 request 的权威 run；旧 request 的迟到 cancel 响应不得覆盖它。
    vi.mocked(getCanvas).mockResolvedValue(snapshotWithRun(1, {
      nodeId: NODE_FN,
      requestId: REQUEST_CURRENT,
      status: 'RUNNING',
      stage: 'QUEUED',
      error: null,
      updatedAt: '2026-08-10T00:00:02Z',
    }))
    vi.mocked(cancelCanvasFunctionRun).mockResolvedValue({
      nodeId: NODE_FN,
      requestId: REQUEST_OLD,
      status: 'CANCELLED',
      stage: 'CANCELLED',
      error: null,
      updatedAt: '2026-08-10T00:00:03Z',
    })
    const { result } = renderHook(() => useCanvasController(CANVAS_ID), { wrapper: Wrapper })
    await waitFor(() => expect(result.current.snapshot?.document.id).toBe(CANVAS_ID))

    await act(async () => {
      await result.current.cancelFunctionRun(NODE_FN, REQUEST_OLD)
    })

    expect(cancelCanvasFunctionRun).toHaveBeenCalledWith(CANVAS_ID, NODE_FN, { requestId: REQUEST_OLD })
    expect(result.current.snapshot?.nodes.find((node) => node.id === NODE_FN)?.run).toMatchObject({
      requestId: REQUEST_CURRENT,
      status: 'RUNNING',
    })
  })

  it('projects a fresh start B over the terminal run A (basis-CAS)', async () => {
    // 当前快照是终态 A；新 start B 发请求前捕获 basis=A，响应 B（缓存仍为 A）应立即替换投影。
    vi.mocked(getCanvas).mockResolvedValue(snapshotWithRun(1, {
      nodeId: NODE_FN,
      requestId: REQUEST_STARTED,
      status: 'SUCCEEDED',
      stage: 'SUCCEEDED',
      error: null,
      updatedAt: '2026-08-10T00:00:01Z',
    }))
    vi.mocked(startCanvasFunctionRun).mockResolvedValue({
      nodeId: NODE_FN,
      requestId: REQUEST_NEW,
      status: 'RUNNING',
      stage: 'QUEUED',
      error: null,
      updatedAt: '2026-08-10T00:00:04Z',
    })
    const { result } = renderHook(() => useCanvasController(CANVAS_ID), { wrapper: Wrapper })
    await waitFor(() => expect(result.current.snapshot?.document.id).toBe(CANVAS_ID))

    await act(async () => {
      await result.current.startFunctionRun(NODE_FN)
    })

    await waitFor(() => {
      expect(result.current.snapshot?.nodes.find((node) => node.id === NODE_FN)?.run).toMatchObject({
        requestId: REQUEST_NEW,
        status: 'RUNNING',
        stage: 'QUEUED',
      })
    })
    expect(result.current.snapshot?.document.version).toBe('1')
  })

  it('ignores an in-flight start B response once an authoritative C run arrived', async () => {
    // start B 在途期间权威 C 到达（basis 仍是旧 A）：B 的迟到响应不能被投影、更不能换掉 C。
    vi.mocked(getCanvas).mockResolvedValue(snapshotWithRun(1, {
      nodeId: NODE_FN,
      requestId: REQUEST_STARTED,
      status: 'SUCCEEDED',
      stage: 'SUCCEEDED',
      error: null,
      updatedAt: '2026-08-10T00:00:01Z',
    }))
    const { result } = renderHook(() => useCanvasController(CANVAS_ID), { wrapper: Wrapper })
    await waitFor(() => expect(result.current.snapshot?.document.id).toBe(CANVAS_ID))

    const startDeferred = deferred<CanvasFunctionRunDTO>()
    vi.mocked(startCanvasFunctionRun).mockImplementationOnce(() => startDeferred.promise)
    void result.current.startFunctionRun(NODE_FN)
    await waitFor(() => expect(startCanvasFunctionRun).toHaveBeenCalledTimes(1))

    // B 响应到达前，version 事件权威带入 C。
    vi.mocked(getCanvasChanges).mockResolvedValue({
      patches: [diffPatch(snapshotWithRun(2, {
        nodeId: NODE_FN,
        requestId: REQUEST_CURRENT,
        status: 'RUNNING',
        stage: 'CHECKPOINTED',
        error: null,
        updatedAt: '2026-08-10T00:00:05Z',
      }), 1)],
      snapshot: null,
    })
    emitCanvasVersion('2')
    await waitFor(() => {
      expect(result.current.snapshot?.nodes.find((node) => node.id === NODE_FN)?.run?.requestId)
        .toBe(REQUEST_CURRENT)
    })

    await act(async () => {
      startDeferred.resolve({
        nodeId: NODE_FN,
        requestId: REQUEST_NEW,
        status: 'RUNNING',
        stage: 'QUEUED',
        error: null,
        updatedAt: '2026-08-10T00:04:00Z',
      })
    })

    expect(result.current.snapshot?.nodes.find((node) => node.id === NODE_FN)?.run).toMatchObject({
      requestId: REQUEST_CURRENT,
      status: 'RUNNING',
      stage: 'CHECKPOINTED',
    })
    expect(result.current.snapshot?.document.version).toBe('2')
  })

  it('replaces the snapshot authoritatively on a resync event', async () => {
    const { result } = renderHook(() => useCanvasController(), { wrapper: Wrapper })
    act(() => result.current.openEditor(CANVAS_ID))
    await waitFor(() => expect(result.current.snapshot?.document.id).toBe(CANVAS_ID))
    expect(getCanvas).toHaveBeenCalledTimes(1)

    emitCanvasResync()

    await waitFor(() => expect(getCanvas).toHaveBeenCalledTimes(2))
    expect(result.current.snapshot?.document.id).toBe(CANVAS_ID)
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

  it('deletes selected links without bypassing node confirmation', async () => {
    // 节点/Group 删除只经右键确认；键盘 Delete 仅处理无确认风险的连线。
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
    expect(commands[0]?.commands).toEqual([{
      type: 'DELETE_LINK',
      sourceNodeId: NODE_NOTE,
      targetNodeId: NODE_FN,
    }])
  })
})
