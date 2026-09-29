import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import { useState, type PropsWithChildren } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useCanvasController } from '@/features/canvas/useCanvasController'
import { canvasViewportStorageKey } from '@/features/canvas/viewport-storage'
import { loadCanvasDrafts, saveCanvasDraft } from '@/features/canvas/canvas-draft-storage'
import * as draftStorageModule from '@/features/canvas/canvas-draft-storage'
import { loadCanvasOperations } from '@/features/canvas/canvas-operation-storage'
import { createMockIDBFactory } from './mock-idb'
import type { CanvasNodeDraft } from '@/features/canvas/canvas-drafts'
import type {
  ApplyCanvasCommandsRequestDTO,
  CanvasCommandDTO,
  CanvasFunctionRunDTO,
  CanvasPatchDTO,
  CanvasResourceKind,
  CanvasSnapshotDTO,
  UUIDString,
} from '@/shared/api/contracts/studio'
import {
  cancelCanvasFunctionRun,
  getCanvas,
  getCanvasFunctionRun,
  listCanvasFunctions,
  postCanvasCommands,
  startCanvasFunctionRun,
} from '@/shared/api/studio-service'
import { ApiError } from '@/shared/api/client'
import { storageService } from '@/shared/api/storage-service'

const CANVAS_ID = '8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f' as UUIDString
const NODE_NOTE = 'aaaaaaaa-0000-4000-8000-000000000002' as UUIDString
const NODE_FN = 'aaaaaaaa-0000-4000-8000-000000000003' as UUIDString
const NODE_IMG = 'aaaaaaaa-0000-4000-8000-000000000005' as UUIDString
const GROUP_A = 'bbbbbbbb-0000-4000-8000-000000000004' as UUIDString
const RES_NOTE = 'cccccccc-0000-4000-8000-000000000020' as UUIDString
const RES_OUTPUT = 'cccccccc-0000-4000-8000-000000000030' as UUIDString
const RES_IMG = 'cccccccc-0000-4000-8000-000000000050' as UUIDString
const UPLOAD_ID = 'dddddddd-0000-4000-8000-000000000009' as UUIDString
const REQUEST_STARTED = 'eeeeeeee-0000-4000-8000-000000000001' as UUIDString
const REQUEST_CURRENT = 'eeeeeeee-0000-4000-8000-000000000002' as UUIDString
const REQUEST_OLD = 'eeeeeeee-0000-4000-8000-000000000003' as UUIDString
const REQUEST_NEW = 'eeeeeeee-0000-4000-8000-000000000004' as UUIDString
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
  getCanvasFunctionRun: vi.fn(),
  listCanvasFunctions: vi.fn(),
  startCanvasFunctionRun: vi.fn(),
}))

vi.mock('@/shared/api/storage-service', () => ({
  storageService: {
    reserveUpload: vi.fn(),
    uploadFile: vi.fn(),
    completeUpload: vi.fn(),
    deleteUpload: vi.fn(),
    getBlobDownloadUrl: vi.fn(),
    getBlobPreviewUrl: vi.fn(),
  },
}))

vi.mock('@/features/ai/composer', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/features/ai/composer')>()
  return { ...actual, createWorkerHasher: () => async () => FAKE_SHA256 }
})

/** 模拟应用事件通道的 canvas revision 事件（携带 canonical 十进制 revision）。 */
function emitCanvasRevision(revision: string) {
  act(() => {
    for (const subscription of canvasEventSubscriptions) {
      subscription.onEvent?.('revision', { revision }, revision)
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

/** 模拟首次订阅或断线重连后的 subscribed ack。 */
function emitCanvasSubscribed(cursor: string) {
  act(() => {
    for (const subscription of canvasEventSubscriptions) {
      subscription.onSubscribed?.(cursor)
    }
  })
}

/** 在权威快照上投影指定 FunctionRun（测试夹具便捷方法）。 */
function snapshotWithRun(
  revision: number | string,
  run: CanvasFunctionRunDTO,
): CanvasSnapshotDTO {
  const next = snapshot(revision)
  next.nodes = next.nodes.map((node) => (
    node.id === NODE_FN ? { ...node, run } : node
  ))
  return next
}

function snapshot(revision: number | string = 0): CanvasSnapshotDTO {
  return {
    document: {
      id: CANVAS_ID,
      title: 'Board',
      revision: String(revision),
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
        name: 'fake-image',
        args: {
          prompt: { segments: [{ type: 'TEXT', text: 'old prompt' }] },
          parameters: { ratio: 'AUTO' },
        },
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
    references: [{
      canvasId: CANVAS_ID,
      sourceNodeId: NODE_NOTE,
      targetNodeId: NODE_FN,
      index: 0,
    }],
  }
}

/** 测试内版本前进：bigint-safe，避免 JS number 精度问题。 */
function nextRevision(revision: string): string {
  return String(BigInt(revision) + 1n)
}

/**
 * 全量 upsert patch：前进到 snapshot 的 revision。
 */
function diffPatch(snapshotValue: CanvasSnapshotDTO): CanvasPatchDTO {
  return {
    revision: snapshotValue.document.revision,
    groups: snapshotValue.groups.map((group) => ({ op: 'UPSERT', group })),
    nodes: snapshotValue.nodes.map((node) => ({ op: 'UPSERT', node })),
  }
}

/** 模拟服务端命令应用：创建节点 + 版本前进（与命令携带的客户端 UUID 保持一致）。 */
function applyCommandBatch(current: CanvasSnapshotDTO, commands: CanvasCommandDTO[]): CanvasSnapshotDTO {
  let nodes = current.nodes
  let groups = current.groups
  for (const command of commands) {
    if (command.type === 'CREATE_NODE') {
      nodes = [...nodes, {
        id: command.nodeId,
        canvasId: current.document.id,
        name: command.name,
        transform: command.transform,
        groupId: command.groupId ?? null,
        resources: (command.resources ?? []).map((r, idx) => ({
          id: (r.kind === 'BLOB' ? r.blobId : `res-${idx}`) as UUIDString,
          canvasId: current.document.id,
          ownerNodeId: command.nodeId,
          resourceIndex: idx,
          blobId: r.kind === 'BLOB' ? r.blobId : null,
          name: r.name,
          textContent: r.kind === 'TEXT' ? (r.textContent ?? null) : null,
          kind: r.kind === 'BLOB' ? 'IMAGE' : (r.kind as CanvasResourceKind),
          mediaType: r.kind === 'BLOB' ? 'image/png' : 'text/plain',
          sizeBytes: 100,
          width: null,
          height: null,
          durationMs: null,
          createdAt: '2026-08-10T00:00:00Z',
        })),
        function: command.function ?? null,
        run: null,
      }]
    } else if (command.type === 'DELETE_NODE') {
      nodes = nodes.filter((n) => n.id !== command.nodeId)
    } else if (command.type === 'RENAME_NODE') {
      nodes = nodes.map((n) => n.id === command.nodeId ? { ...n, name: command.name } : n)
    } else if (command.type === 'SET_NODE_RESOURCES') {
      nodes = nodes.map((n) => n.id === command.nodeId ? {
        ...n,
        resources: command.resources.map((r, idx) => ({
          id: `res-${idx}` as UUIDString,
          canvasId: current.document.id,
          ownerNodeId: command.nodeId,
          resourceIndex: idx,
          blobId: r.kind === 'BLOB' ? r.blobId : null,
          name: r.name,
          textContent: r.kind === 'TEXT' ? (r.textContent ?? null) : null,
          kind: r.kind === 'BLOB' ? 'IMAGE' : (r.kind as CanvasResourceKind),
          mediaType: 'text/plain',
          sizeBytes: 100,
          width: null,
          height: null,
          durationMs: null,
          createdAt: '2026-08-10T00:00:00Z',
        })),
      } : n)
    } else if (command.type === 'SET_NODE_FUNCTION') {
      nodes = nodes.map((n) => n.id === command.nodeId ? { ...n, function: command.function } : n)
    } else if (command.type === 'SET_NODE_GROUP') {
      nodes = nodes.map((n) => n.id === command.nodeId ? { ...n, groupId: command.groupId } : n)
    } else if (command.type === 'UPDATE_NODE_TRANSFORM') {
      nodes = nodes.map((n) => n.id === command.nodeId ? { ...n, transform: command.transform } : n)
    } else if (command.type === 'CREATE_GROUP') {
      groups = [...groups, {
        id: command.groupId,
        canvasId: current.document.id,
        title: command.title,
        transform: command.transform,
      }]
    } else if (command.type === 'RENAME_GROUP') {
      groups = groups.map((g) => g.id === command.groupId ? { ...g, title: command.title } : g)
    } else if (command.type === 'DELETE_GROUP') {
      groups = groups.filter((g) => g.id !== command.groupId)
    } else if (command.type === 'UPDATE_GROUP_TRANSFORM') {
      groups = groups.map((g) => g.id === command.groupId ? { ...g, transform: command.transform } : g)
    }
  }
  return {
    ...current,
    document: { ...current.document, revision: nextRevision(current.document.revision) },
    nodes,
    groups,
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
    vi.mocked(listCanvasFunctions).mockResolvedValue([{
      name: 'fake-image',
      description: 'Fake Image',
      outputs: [{ kind: 'IMAGE', name: null }],
      argsSchema: {
        type: 'object',
        properties: {
          ratio: { type: 'string', enum: ['AUTO'] },
        },
      },
      referencePolicy: { allowedKinds: ['IMAGE'], maxReferences: 1, maxByKind: {} },
      available: true,
      unavailableReason: null,
    }])
    vi.mocked(postCanvasCommands).mockImplementation(async (_canvasId, request) => {
      commands.push(request)
      current = applyCommandBatch(current, request.commands)
      return diffPatch(current)
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
      request.commands[0]?.type === 'SET_NODE_RESOURCES'
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
      request.commands[0]?.type === 'CREATE_NODE'
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
      request.commands[0]?.type === 'CREATE_NODE'
      && request.commands[0]?.resources?.[0]?.kind === 'BLOB'
    ))).toBe(true)
    const uploadedNode = commands
      .flatMap((request) => request.commands)
      .find((command) => command.type === 'CREATE_NODE' && command.resources?.[0]?.kind === 'BLOB')
    expect(uploadedNode).toMatchObject({
      transform: { width: 320, height: 246 },
      resources: [{
        kind: 'BLOB',
        name: 'upload.png',
        blobId: 'blob-upload',
      }],
      nodeId: expect.stringMatching(/^[0-9a-f-]{36}$/i),
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
    const { result } = renderHook(() => useCanvasController(), { wrapper: Wrapper })
    act(() => result.current.openEditor(CANVAS_ID))
    await waitFor(() => expect(result.current.snapshot?.document.id).toBe(CANVAS_ID))

    act(() => result.current.ungroupGroup(GROUP_A))
    await waitFor(() => expect(commands.some((request) => (
      request.commands[0]?.type === 'SET_NODE_GROUP'
    ))).toBe(true))
    const ungroupBodies = commands.filter((request) => (
      request.commands[0]?.type === 'SET_NODE_GROUP'
    ))
    expect(ungroupBodies).toHaveLength(1)
    expect(ungroupBodies[0]?.commands).toEqual(expect.arrayContaining([
      { type: 'SET_NODE_GROUP', nodeId: NODE_FN, expectedGroupId: GROUP_A, groupId: null },
      { type: 'SET_NODE_GROUP', nodeId: NODE_IMG, expectedGroupId: GROUP_A, groupId: null },
    ]))
    expect(ungroupBodies[0]?.commands.some((command) => (
      command.type === 'DELETE_GROUP'
    ))).toBe(false)

    act(() => result.current.renameGroup(GROUP_A, '  新分组  '))
    await waitFor(() => expect(commands.some((request) => (
      request.commands[0]?.type === 'RENAME_GROUP'
    ))).toBe(true))
    expect(commands.find((request) => (
      request.commands[0]?.type === 'RENAME_GROUP'
    ))?.commands).toEqual([{ type: 'RENAME_GROUP', groupId: GROUP_A, expectedTitle: 'Group', title: '新分组' }])

    act(() => result.current.renameGroup(GROUP_A, '   '))
    expect(commands.filter((request) => (
      request.commands[0]?.type === 'RENAME_GROUP'
    ))).toHaveLength(1)
  })

  it('ungroups a member only after its dragged bounds fully leave the Group body', async () => {
    const { result } = renderHook(() => useCanvasController(), { wrapper: Wrapper })
    act(() => result.current.openEditor(CANVAS_ID))
    await waitFor(() => expect(result.current.snapshot?.document.id).toBe(CANVAS_ID))

    act(() => {
      result.current.moveNodes([{
        id: NODE_FN,
        kind: 'resource',
        transform: { x: 760, y: 100, width: 320, height: 260 },
      }])
      result.current.commitTransforms()
    })
    await waitFor(() => expect(commands.some((request) => (
      request.commands[0]?.type === 'UPDATE_NODE_TRANSFORM'
    ))).toBe(true))
    expect(commands.some((request) => (
      request.commands.some((command) => command.type === 'SET_NODE_GROUP')
    ))).toBe(false)

    act(() => {
      result.current.moveNodes([{
        id: NODE_FN,
        kind: 'resource',
        transform: { x: 800, y: 100, width: 320, height: 260 },
      }])
      result.current.commitTransforms()
    })
    await waitFor(() => expect(commands.some((request) => (
      request.commands.some((command) => command.type === 'SET_NODE_GROUP')
    ))).toBe(true))
    const detachBatch = commands.find((request) => (
      request.commands.some((command) => command.type === 'SET_NODE_GROUP')
    ))
    expect(detachBatch?.commands).toEqual([
      {
        type: 'UPDATE_NODE_TRANSFORM',
        nodeId: NODE_FN,
        transform: { x: 800, y: 100, width: 320, height: 260 },
      },
      {
        type: 'SET_NODE_GROUP',
        nodeId: NODE_FN,
        expectedGroupId: GROUP_A,
        groupId: null,
      },
    ])
  })

  it('persists a text-editor rename together with the markdown update', async () => {
    const { result } = renderHook(() => useCanvasController(), { wrapper: Wrapper })
    act(() => result.current.openEditor(CANVAS_ID))
    await waitFor(() => expect(result.current.snapshot?.document.id).toBe(CANVAS_ID))
    const node = result.current.snapshot?.nodes.find((item) => item.id === NODE_NOTE)
    expect(node?.name).toBe('Note')

    act(() => result.current.nodeCallbacks.editTextNode(result.current.snapshot?.nodes[0] as never))
    act(() => result.current.setTextEditorDraft({ name: 'Note v2', markdown: 'updated' }))
    act(() => result.current.saveTextEditor())
    await waitFor(() => expect(commands.some((request) => (
      request.commands[0]?.type === 'SET_NODE_RESOURCES'
    ))).toBe(true))
    const body = commands.find((request) => (
      request.commands[0]?.type === 'SET_NODE_RESOURCES'
    ))
    expect(body?.commands).toEqual([
      {
        type: 'SET_NODE_RESOURCES',
        nodeId: NODE_NOTE,
        expectedResourceIds: [RES_NOTE],
        resources: [{ kind: 'TEXT', name: 'text', textContent: 'updated' }],
      },
      { type: 'RENAME_NODE', nodeId: NODE_NOTE, expectedName: 'Note', name: 'Note v2' },
    ])

    act(() => result.current.nodeCallbacks.editTextNode(result.current.snapshot?.nodes[0] as never))
    act(() => result.current.setTextEditorDraft({ name: 'Note v2', markdown: 'again' }))
    act(() => result.current.saveTextEditor())
    await waitFor(() => expect(commands.filter((request) => (
      request.commands[0]?.type === 'SET_NODE_RESOURCES'
    ))).toHaveLength(2))
    const second = commands.filter((request) => (
      request.commands[0]?.type === 'SET_NODE_RESOURCES'
    ))[1]
    expect(second?.commands).toEqual([
      {
        type: 'SET_NODE_RESOURCES',
        nodeId: NODE_NOTE,
        expectedResourceIds: expect.any(Array),
        resources: [{ kind: 'TEXT', name: 'text', textContent: 'again' }],
      },
    ])
  })

  it('allocates unique aliases from the command queue snapshot across rapid creates and uploads', async () => {
    vi.mocked(storageService.reserveUpload).mockResolvedValue({
      id: 'eeeeeeee-0000-4000-8000-000000000090' as UUIDString,
      state: 'READY',
      blobId: 'blob-existing' as UUIDString,
      presignedPut: null,
      expiresAt: '2026-08-10T00:15:00Z',
    })
    vi.mocked(storageService.completeUpload).mockImplementation(async (uploadId) => ({
      id: uploadId,
      state: 'READY',
      blobId: 'blob-dedup' as UUIDString,
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
      request.commands[0]?.type === 'CREATE_NODE'
    ))).toHaveLength(2))

    act(() => {
      result.current.createTextNode()
      result.current.setTextEditorDraft({ name: '  图片生成  ' })
    })
    act(() => result.current.saveTextEditor())
    await waitFor(() => expect(commands.filter((request) => (
      request.commands[0]?.type === 'CREATE_NODE'
    ))).toHaveLength(3))

    await act(async () => {
      await result.current.uploadFiles([
        new File(['one'], 'same.heic'),
        new File(['two'], 'same.heic'),
      ])
    })

    const aliases = commands.flatMap((request) => request.commands.flatMap((command) => (
      command.type === 'CREATE_NODE'
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
  })

  it('flushes config before start, projects RUNNING without polling, converges via version events, and cancels', async () => {
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
      type: 'SET_NODE_FUNCTION',
      nodeId: NODE_FN,
      expectedFunction: {
        name: 'fake-image',
        args: {
          prompt: { segments: [{ type: 'TEXT', text: 'old prompt' }] },
          parameters: { ratio: 'AUTO' },
        },
      },
      function: {
        name: 'fake-image',
        args: {
          prompt: { segments: [{ type: 'TEXT', text: 'new prompt' }] },
          parameters: { ratio: 'AUTO' },
        },
      },
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
      expect(result.current.snapshot?.nodes.find((node) => node.id === NODE_FN)?.run?.status).toBe('RUNNING')
    })
    expect(getCanvasFunctionRun).not.toHaveBeenCalled()

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

    current = snapshotWithRun(2, steps['2']!)
    emitCanvasRevision('2')
    await waitFor(() => {
      expect(result.current.snapshot?.nodes.find((node) => node.id === NODE_FN)?.run?.stage).toBe('CHECKPOINTED')
    })
    expect(getCanvasFunctionRun).not.toHaveBeenCalled()

    current = snapshotWithRun(3, steps['3']!)
    emitCanvasRevision('3')
    await waitFor(() => {
      expect(result.current.snapshot?.nodes.find((node) => node.id === NODE_FN)?.run?.status).toBe('FAILED')
    })
    expect(getCanvasFunctionRun).not.toHaveBeenCalled()

    await act(async () => {
      await result.current.cancelFunctionRun(NODE_FN)
    })
    expect(cancelCanvasFunctionRun).toHaveBeenCalledWith(
      CANVAS_ID,
      NODE_FN,
      { requestId: REQUEST_STARTED },
    )
  })

  it('does not poll RUNNING nodes loaded from the authoritative snapshot', async () => {
    current = snapshotWithRun(1, {
      nodeId: NODE_FN,
      requestId: REQUEST_STARTED,
      status: 'RUNNING',
      stage: 'QUEUED',
      error: null,
      updatedAt: '2026-08-10T00:00:01Z',
    })
    const { result } = renderHook(() => useCanvasController(), { wrapper: Wrapper })
    act(() => result.current.openEditor(CANVAS_ID))
    await waitFor(() => expect(result.current.snapshot?.document.id).toBe(CANVAS_ID))

    await new Promise((resolve) => setTimeout(resolve, 60))
    expect(getCanvasFunctionRun).not.toHaveBeenCalled()
  })

  it('replays the create/start/checkpoint/terminal version sequence and converges replaced output', async () => {
    current = snapshot(1)
    const { result } = renderHook(() => useCanvasController(), { wrapper: Wrapper })
    act(() => result.current.openEditor(CANVAS_ID))
    await waitFor(() => expect(result.current.snapshot?.document.revision).toBe('1'))

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
        id: 'ffffffff-0000-4000-8000-000000000031' as UUIDString,
        canvasId: CANVAS_ID,
        ownerNodeId: NODE_FN,
        resourceIndex: 0,
        blobId: 'blob-new-output' as UUIDString,
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

    current = terminal
    emitCanvasRevision('4')
    await waitFor(() => {
      expect(result.current.snapshot?.document.revision).toBe('4')
    })
    const node = result.current.snapshot?.nodes.find((item) => item.id === NODE_FN)
    expect(node?.resources).toHaveLength(1)
    expect(node?.resources[0]?.id).toBe('ffffffff-0000-4000-8000-000000000031')
  })

  it('ignores a stale local cancel response for an older request', async () => {
    current = snapshotWithRun(1, {
      nodeId: NODE_FN,
      requestId: REQUEST_CURRENT,
      status: 'RUNNING',
      stage: 'QUEUED',
      error: null,
      updatedAt: '2026-08-10T00:00:01Z',
    })
    const cancelDeferred = deferred<CanvasFunctionRunDTO>()
    vi.mocked(cancelCanvasFunctionRun).mockImplementationOnce(() => cancelDeferred.promise)

    const { result } = renderHook(() => useCanvasController(), { wrapper: Wrapper })
    act(() => result.current.openEditor(CANVAS_ID))
    await waitFor(() => expect(result.current.snapshot?.document.id).toBe(CANVAS_ID))

    void result.current.cancelFunctionRun(NODE_FN)
    await waitFor(() => expect(cancelCanvasFunctionRun).toHaveBeenCalledTimes(1))

    act(() => {
      cancelDeferred.resolve({
        nodeId: NODE_FN,
        requestId: REQUEST_OLD,
        status: 'CANCELLED',
        stage: 'CANCELLED',
        error: null,
        updatedAt: '2026-08-10T00:00:00Z',
      })
    })

    expect(result.current.snapshot?.nodes.find((node) => node.id === NODE_FN)?.run).toMatchObject({
      requestId: REQUEST_CURRENT,
      status: 'RUNNING',
    })
  })

  it('projects a fresh start B over the terminal run A (basis-CAS)', async () => {
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
    expect(result.current.snapshot?.document.revision).toBe('1')
  })

  it('ignores an in-flight start B response once an authoritative C run arrived', async () => {
    current = snapshotWithRun(1, {
      nodeId: NODE_FN,
      requestId: REQUEST_STARTED,
      status: 'SUCCEEDED',
      stage: 'SUCCEEDED',
      error: null,
      updatedAt: '2026-08-10T00:00:01Z',
    })
    const { result } = renderHook(() => useCanvasController(CANVAS_ID), { wrapper: Wrapper })
    await waitFor(() => expect(result.current.snapshot?.document.id).toBe(CANVAS_ID))

    const startDeferred = deferred<CanvasFunctionRunDTO>()
    vi.mocked(startCanvasFunctionRun).mockImplementationOnce(() => startDeferred.promise)
    void result.current.startFunctionRun(NODE_FN)
    await waitFor(() => expect(startCanvasFunctionRun).toHaveBeenCalledTimes(1))

    current = snapshotWithRun(2, {
      nodeId: NODE_FN,
      requestId: REQUEST_CURRENT,
      status: 'RUNNING',
      stage: 'CHECKPOINTED',
      error: null,
      updatedAt: '2026-08-10T00:00:05Z',
    })
    emitCanvasRevision('2')
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
    expect(result.current.snapshot?.document.revision).toBe('2')
  })

  it('skips the current version event and refreshes snapshots for newer versions, reconnect, and resync', async () => {
    const { result } = renderHook(() => useCanvasController(CANVAS_ID), { wrapper: Wrapper })
    await waitFor(() => expect(result.current.snapshot?.document.id).toBe(CANVAS_ID))
    expect(getCanvas).toHaveBeenCalledTimes(1)

    emitCanvasRevision('0')
    await new Promise((resolve) => setTimeout(resolve, 30))
    expect(getCanvas).toHaveBeenCalledTimes(1)

    current = snapshot(2)
    emitCanvasRevision('2')
    await waitFor(() => expect(getCanvas).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(result.current.snapshot?.document.revision).toBe('2'))

    current = snapshot(3)
    emitCanvasSubscribed('3')
    await waitFor(() => expect(getCanvas).toHaveBeenCalledTimes(3))

    current = snapshot(4)
    emitCanvasResync()
    await waitFor(() => expect(getCanvas).toHaveBeenCalledTimes(4))
  })

  it('retains a failed config draft so start retries the save before posting the run', async () => {
    vi.mocked(postCanvasCommands).mockRejectedValueOnce(new ApiError('save failed', 400))
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
      type: 'SET_NODE_FUNCTION',
      nodeId: NODE_FN,
      expectedFunction: {
        name: 'fake-image',
        args: {
          prompt: { segments: [{ type: 'TEXT', text: 'old prompt' }] },
          parameters: { ratio: 'AUTO' },
        },
      },
      function: {
        name: 'fake-image',
        args: {
          prompt: { segments: [{ type: 'TEXT', text: 'retry prompt' }] },
          parameters: { ratio: 'AUTO' },
        },
      },
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

    let firstFlush!: Promise<unknown>
    let duplicateFlush!: Promise<unknown>
    let start!: Promise<unknown>
    act(() => {
      firstFlush = result.current.flushFunctionConfig(NODE_FN)
      duplicateFlush = result.current.flushFunctionConfig(NODE_FN)
      result.current.scheduleFunctionConfig(NODE_FN, 'fake-image', {
        prompt: { segments: [{ type: 'TEXT', text: 'latest prompt' }] },
        parameters: { ratio: 'AUTO' },
      })
      start = result.current.startFunctionRun(NODE_FN)
    })

    await waitFor(() => expect(postCanvasCommands).toHaveBeenCalledTimes(1))

    await act(async () => {
      firstSave.resolve(diffPatch(snapshot(1)))
      await firstSave.promise
    })
    await waitFor(() => expect(postCanvasCommands).toHaveBeenCalledTimes(2))
    expect(startCanvasFunctionRun).not.toHaveBeenCalled()
    expect(commands.map((request) => request.commands)).toEqual([
      [{
        type: 'SET_NODE_FUNCTION',
        nodeId: NODE_FN,
        expectedFunction: {
          name: 'fake-image',
          args: {
            prompt: { segments: [{ type: 'TEXT', text: 'old prompt' }] },
            parameters: { ratio: 'AUTO' },
          },
        },
        function: {
          name: 'fake-image',
          args: {
            prompt: { segments: [{ type: 'TEXT', text: 'first prompt' }] },
            parameters: { ratio: 'AUTO' },
          },
        },
      }],
      [{
        type: 'SET_NODE_FUNCTION',
        nodeId: NODE_FN,
        expectedFunction: {
          name: 'fake-image',
          args: {
            prompt: { segments: [{ type: 'TEXT', text: 'old prompt' }] },
            parameters: { ratio: 'AUTO' },
          },
        },
        function: {
          name: 'fake-image',
          args: {
            prompt: { segments: [{ type: 'TEXT', text: 'latest prompt' }] },
            parameters: { ratio: 'AUTO' },
          },
        },
      }],
    ])

    await act(async () => {
      secondSave.resolve(diffPatch(snapshot(2)))
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

  it('deletes selected nodes on deleteSelection', async () => {
    const { result } = renderHook(() => useCanvasController(CANVAS_ID), { wrapper: Wrapper })
    await waitFor(() => expect(result.current.snapshot?.document.id).toBe(CANVAS_ID))
    act(() => {
      result.current.setSelection([NODE_NOTE])
    })
    act(() => {
      result.current.deleteSelection()
    })

    await waitFor(() => expect(commands.some((c) => c.commands.some((cmd) => cmd.type === 'DELETE_NODE'))).toBe(true))
    const delCmd = commands.flatMap((c) => c.commands).find((cmd) => cmd.type === 'DELETE_NODE')
    expect(delCmd).toMatchObject({
      type: 'DELETE_NODE',
      nodeId: NODE_NOTE,
    })
  })

  it('handles 409 conflict: preserves local draft alongside remote authoritative state, supports saveDraftAsNewNode and dismissDraft', async () => {
    const { result } = renderHook(() => useCanvasController(CANVAS_ID), { wrapper: Wrapper })
    await waitFor(() => expect(result.current.snapshot?.document.id).toBe(CANVAS_ID))

    act(() => result.current.nodeCallbacks.editTextNode(result.current.snapshot?.nodes[0] as never))
    act(() => result.current.setTextEditorDraft({ name: 'Conflict Note', markdown: 'local text' }))

    const conflictError = new ApiError('Conflict', 409, 'CANVAS_CONFLICT', {
      conflicts: [{
        kind: 'STALE_NODE',
        nodeId: NODE_NOTE,
        group: 'CONTENT',
        expected: 'old',
        current: 'remote modified text',
      }],
    })
    vi.mocked(postCanvasCommands).mockRejectedValueOnce(conflictError)

    await act(async () => {
      result.current.saveTextEditor()
    })

    await waitFor(() => expect(result.current.state.conflictMessage).toContain('Canvas changed on the server'))
    expect(result.current.state.drafts[NODE_NOTE]?.conflict).toBeDefined()
    expect(result.current.state.drafts[NODE_NOTE]?.conflict?.kind).toBe('STALE_NODE')

    await act(async () => {
      await result.current.saveDraftAsNewNode(NODE_NOTE)
    })

    const createNewCmd = commands.flatMap((c) => c.commands).find(
      (cmd) => cmd.type === 'CREATE_NODE' && cmd.name === 'Conflict Note',
    )
    expect(createNewCmd).toBeDefined()
    expect(createNewCmd?.nodeId).not.toBe(NODE_NOTE)
    expect(result.current.state.drafts[NODE_NOTE]).toBeUndefined()
  })

  it('dismisses a conflicted draft and clears conflictMessage', async () => {
    const { result } = renderHook(() => useCanvasController(CANVAS_ID), { wrapper: Wrapper })
    await waitFor(() => expect(result.current.snapshot?.document.id).toBe(CANVAS_ID))

    act(() => result.current.nodeCallbacks.editTextNode(result.current.snapshot?.nodes[0] as never))
    act(() => result.current.setTextEditorDraft({ name: 'Dismiss Me', markdown: 'draft' }))

    const conflictError = new ApiError('Conflict', 409, 'CANVAS_CONFLICT', {
      conflicts: [{
        kind: 'STALE_NODE',
        nodeId: NODE_NOTE,
        group: 'CONTENT',
        expected: 'old',
        current: 'remote',
      }],
    })
    vi.mocked(postCanvasCommands).mockRejectedValueOnce(conflictError)

    await act(async () => {
      result.current.saveTextEditor()
    })

    await waitFor(() => expect(result.current.state.conflictMessage).toBeDefined())
    expect(result.current.state.drafts[NODE_NOTE]).toBeDefined()

    act(() => {
      result.current.dismissDraft(NODE_NOTE)
    })

    expect(result.current.state.drafts[NODE_NOTE]).toBeUndefined()
    expect(result.current.state.conflictMessage).toBeNull()
  })

  it('初始草稿加载与保存 effect 不竞争：重新打开同一画布不会先清掉已落盘草稿', async () => {
    const seeded: CanvasNodeDraft = {
      generation: 1,
      updatedAt: Date.now(),
      text: { name: 'Note', markdown: 'persisted draft' },
    }
    await saveCanvasDraft(CANVAS_ID, NODE_NOTE, seeded)

    const { result } = renderHook(() => useCanvasController(CANVAS_ID), { wrapper: Wrapper })
    await waitFor(() => expect(result.current.snapshot?.document.id).toBe(CANVAS_ID))
    await waitFor(() => expect(result.current.state.drafts[NODE_NOTE]?.text?.markdown).toBe('persisted draft'))

    // 重新打开同一画布：会先把内存草稿重置为空，再异步从 IDB 恢复。
    act(() => result.current.openEditor(CANVAS_ID))
    await waitFor(() => expect(result.current.state.drafts[NODE_NOTE]?.text?.markdown).toBe('persisted draft'))

    // 关键回归：已落盘草稿没有被“空状态 diff”删除。
    const stored = await loadCanvasDrafts(CANVAS_ID)
    expect(stored[NODE_NOTE]?.text?.markdown).toBe('persisted draft')
  })

  it('旧 ACK 只按 operation/generation 清除匹配草稿，不清掉在途期间追加的新输入', async () => {
    const release = deferred<CanvasPatchDTO>()
    vi.mocked(postCanvasCommands).mockImplementationOnce(async (_canvasId, request) => {
      commands.push(request)
      return release.promise
    })
    const { result } = renderHook(() => useCanvasController(), { wrapper: Wrapper })
    act(() => result.current.openEditor(CANVAS_ID))
    await waitFor(() => expect(result.current.snapshot?.document.id).toBe(CANVAS_ID))

    act(() => result.current.nodeCallbacks.editTextNode(result.current.snapshot?.nodes[0] as never))
    act(() => result.current.setTextEditorDraft({ name: 'Note', markdown: 'first' }))
    act(() => result.current.saveTextEditor())
    await waitFor(() => expect(commands).toHaveLength(1))

    // 在途请求返回前，用户继续输入：generation 前进。
    act(() => result.current.setTextEditorDraft({ markdown: 'second' }))

    await act(async () => {
      release.resolve(diffPatch(snapshot(1)))
      await release.promise
    })

    await waitFor(() => expect(result.current.state.drafts[NODE_NOTE]).toBeDefined())
    // ACK（对应 generation 1）不得清掉 generation 2 的新输入。
    expect(result.current.state.drafts[NODE_NOTE]?.text?.markdown).toBe('second')
  })

  it('恢复已落盘草稿时同步还原位置草稿，避免丢失未提交的拖拽', async () => {
    const seeded: CanvasNodeDraft = {
      generation: 1,
      updatedAt: Date.now(),
      position: { x: 12, y: 34 },
    }
    await saveCanvasDraft(CANVAS_ID, NODE_NOTE, seeded)

    const { result } = renderHook(() => useCanvasController(CANVAS_ID), { wrapper: Wrapper })
    await waitFor(() => expect(result.current.state.positionDrafts[NODE_NOTE]).toEqual({ x: 12, y: 34 }))
  })

  it('本地存储不可用时明确告警：绝不假装已落盘，并暴露 storageError 与提示', async () => {
    Object.defineProperty(window, 'indexedDB', {
      configurable: true,
      writable: true,
      value: createMockIDBFactory({ shouldFailOpen: true }),
    })

    const { result } = renderHook(() => useCanvasController(), { wrapper: Wrapper })
    act(() => result.current.openEditor(CANVAS_ID))

    await waitFor(() => expect(result.current.state.storageError).toBeTruthy())
    expect(result.current.state.toast).toBeTruthy()
  })

  it('队列未知错误阻塞后提示恢复入口，点击 retryRecovery 成功重放并清除 conflictMessage', async () => {
    // 意图：验证当 controller 遇到未知错误使队列 blocked 时，conflictMessage 正确提示重试，
    // 调用 retryRecovery 能完成重放并清除提示。
    vi.mocked(postCanvasCommands)
      .mockRejectedValueOnce(new ApiError('network down', 503))
      .mockImplementationOnce(async () => diffPatch(snapshot(2)))

    const { result } = renderHook(() => useCanvasController(CANVAS_ID), { wrapper: Wrapper })
    await waitFor(() => expect(result.current.snapshot?.document.id).toBe(CANVAS_ID))

    act(() => result.current.nodeCallbacks.editTextNode(result.current.snapshot?.nodes[0] as never))
    act(() => result.current.setTextEditorDraft({ name: 'Blocked Note', markdown: 'draft' }))

    await act(async () => {
      result.current.saveTextEditor()
    })

    // 再次尝试保存，此时队列已经被阻塞，抛出 CanvasQueueBlockedError 并设置 conflictMessage
    await act(async () => {
      result.current.saveTextEditor()
    })
    await waitFor(() => expect(result.current.state.conflictMessage).toContain('存在未确认操作阻塞队列'))

    // 触发 retryRecovery 恢复
    await act(async () => {
      await result.current.retryRecovery()
    })

    await waitFor(() => expect(result.current.state.conflictMessage).toBeNull())
  })

  it('I03 保存状态 fail-closed：草稿落盘失败时不推进基线，维持 dirty 状态与 storageError', async () => {
    const { result } = renderHook(() => useCanvasController(CANVAS_ID), { wrapper: Wrapper })
    await waitFor(() => expect(result.current.snapshot?.document.id).toBe(CANVAS_ID))

    // 注入底层 IDB 失败
    Object.defineProperty(window, 'indexedDB', {
      configurable: true,
      writable: true,
      value: createMockIDBFactory({ shouldFailOpen: true }),
    })

    act(() => {
      result.current.moveNodes([{
        id: NODE_NOTE,
        kind: 'text',
        transform: { x: 50, y: 60, width: 320, height: 260 },
      }])
    })

    await waitFor(() => expect(result.current.state.storageError).toBeTruthy())
    // 关键断言：草稿依然保留在内存 dirty 状态，绝不由于失败而丢弃
    expect(result.current.state.drafts[NODE_NOTE]?.position).toEqual({ x: 50, y: 60 })
    expect(result.current.state.draftPersistPending).toBe(false)
  })

  it('I04 变换批次递增世代并携带世代 ACK，落盘 ACK 只精准清除对应世代', async () => {
    const release = deferred<CanvasPatchDTO>()
    vi.mocked(postCanvasCommands).mockImplementationOnce(async (_canvasId, request) => {
      commands.push(request)
      return release.promise
    })

    const { result } = renderHook(() => useCanvasController(CANVAS_ID), { wrapper: Wrapper })
    await waitFor(() => expect(result.current.snapshot?.document.id).toBe(CANVAS_ID))

    // 第一次移动：世代 1
    act(() => {
      result.current.moveNodes([{
        id: NODE_NOTE,
        kind: 'text',
        transform: { x: 100, y: 200, width: 320, height: 260 },
      }])
      result.current.commitTransforms()
    })
    expect(result.current.state.drafts[NODE_NOTE]?.generation).toBe(1)
    expect(result.current.state.positionDrafts[NODE_NOTE]).toEqual({ x: 100, y: 200 })

    await waitFor(() => expect(commands).toHaveLength(1))

    // 在途请求未返回前，用户再次移动：世代 2
    act(() => {
      result.current.moveNodes([{
        id: NODE_NOTE,
        kind: 'text',
        transform: { x: 110, y: 210, width: 320, height: 260 },
      }])
    })
    expect(result.current.state.drafts[NODE_NOTE]?.generation).toBe(2)

    // 释放第一次请求：完成落盘与世代 1 的 ACK 清理
    await act(async () => {
      release.resolve(diffPatch(snapshot(1)))
      await release.promise
    })

    // 关键断言：在途期间的新修改（世代 2）未被世代 1 的 ACK 冲掉
    expect(result.current.state.drafts[NODE_NOTE]?.position).toEqual({ x: 110, y: 210 })
    expect(result.current.state.positionDrafts[NODE_NOTE]).toEqual({ x: 110, y: 210 })
  })

  it('I05 函数配置编辑立即持久化草稿，防抖冲刷前即使崩溃也保留最新输入', async () => {
    const { result } = renderHook(() => useCanvasController(CANVAS_ID), { wrapper: Wrapper })
    await waitFor(() => expect(result.current.snapshot?.document.id).toBe(CANVAS_ID))

    const newConfig = {
      prompt: { segments: [{ type: 'TEXT' as const, text: 'sunset' }] },
      parameters: { ratio: '16:9' },
    }

    // 调用 scheduleFunctionConfig（模拟面板输入）：立即写入本地草稿，无需等待 320ms 网络防抖
    act(() => {
      result.current.scheduleFunctionConfig(NODE_FN, 'fake-image', newConfig)
    })

    // 立即断言内存草稿已就绪并分配了世代
    expect(result.current.state.drafts[NODE_FN]?.function?.name).toBe('fake-image')
    expect(result.current.state.drafts[NODE_FN]?.function?.args).toEqual(newConfig)
    expect(result.current.state.drafts[NODE_FN]?.generation).toBeGreaterThanOrEqual(1)

    // 等待持久化落盘
    await waitFor(async () => {
      const stored = await loadCanvasDrafts(CANVAS_ID)
      expect(stored[NODE_FN]?.function?.name).toBe('fake-image')
    })
  })

  it('实例卸载边界安全：卸载后正在排队的异步 persist 任务绝不覆写或删除底层存储', async () => {
    const { result, unmount } = renderHook(() => useCanvasController(CANVAS_ID), { wrapper: Wrapper })
    await waitFor(() => expect(result.current.snapshot?.document.id).toBe(CANVAS_ID))

    // 产生草稿并立即卸载组件
    act(() => {
      result.current.moveNodes([{
        id: NODE_NOTE,
        kind: 'text',
        transform: { x: 999, y: 888, width: 320, height: 260 },
      }])
    })
    unmount()

    // 卸载后等待异步微任务全部跑完，确认已卸载实例不会引发未捕获异常或非法状态写入
    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(result.current.state.draftPersistPending).toBe(false)
  })

  it('rapid edits generations 同步性：连续快速修改同步自增世代，绝不因 React 延迟 updater 返回旧值', async () => {
    const { result } = renderHook(() => useCanvasController(CANVAS_ID), { wrapper: Wrapper })
    await waitFor(() => expect(result.current.snapshot?.document.id).toBe(CANVAS_ID))

    act(() => {
      result.current.scheduleFunctionConfig(NODE_FN, 'fake-image', { prompt: { segments: [{ type: 'TEXT', text: '1' }] } })
      result.current.scheduleFunctionConfig(NODE_FN, 'fake-image', { prompt: { segments: [{ type: 'TEXT', text: '2' }] } })
      result.current.scheduleFunctionConfig(NODE_FN, 'fake-image', { prompt: { segments: [{ type: 'TEXT', text: '3' }] } })
    })

    expect(result.current.state.drafts[NODE_FN]?.generation).toBe(3)
    expect(result.current.state.drafts[NODE_FN]?.function?.args.prompt.segments[0].text).toBe('3')
  })

  it('canvas 切换 queued writes 隔离：画布切换时排队的持久化任务严格被 scope/epoch 拦截，绝不跨画布写入', async () => {
    const CANVAS_2 = '99999999-9999-4000-8000-000000000099'
    const { result } = renderHook(() => useCanvasController(CANVAS_ID), { wrapper: Wrapper })
    await waitFor(() => expect(result.current.snapshot?.document.id).toBe(CANVAS_ID))

    act(() => {
      // 在画布 1 产生草稿
      result.current.moveNodes([{
        id: NODE_NOTE,
        kind: 'text',
        transform: { x: 555, y: 666, width: 320, height: 260 },
      }])
      // 紧接着立即切换至画布 2，此时前序 persist 任务仍在异步队列中
      result.current.openEditor(CANVAS_2)
    })

    await new Promise((resolve) => setTimeout(resolve, 60))

    const draftsCanvas2 = await loadCanvasDrafts(CANVAS_2)
    expect(draftsCanvas2[NODE_NOTE]).toBeUndefined()
  })

  it('ACK 后紧接 persist 不复活：ACK 成功后同一边界更新权威草稿，后续 persist 绝不复活已确认草稿', async () => {
    const release = deferred<CanvasPatchDTO>()
    vi.mocked(postCanvasCommands).mockImplementationOnce(async (_canvasId, request) => {
      commands.push(request)
      return release.promise
    })

    const { result } = renderHook(() => useCanvasController(CANVAS_ID), { wrapper: Wrapper })
    await waitFor(() => expect(result.current.snapshot?.document.id).toBe(CANVAS_ID))

    // 1. 移动节点产生草稿并提交命令
    act(() => {
      result.current.moveNodes([{
        id: NODE_NOTE,
        kind: 'text',
        transform: { x: 300, y: 400, width: 320, height: 260 },
      }])
      result.current.commitTransforms()
    })

    await waitFor(() => expect(commands).toHaveLength(1))

    // 命令在途期间，草稿已持久化落盘
    await waitFor(async () => {
      const stored = await loadCanvasDrafts(CANVAS_ID)
      expect(stored[NODE_NOTE]?.position).toEqual({ x: 300, y: 400 })
    })

    // 2. 服务端响应返回，命令完成，触发队列 settle 及 ACK 清理
    await act(async () => {
      release.resolve(diffPatch(snapshot(1)))
      await release.promise
    })

    await waitFor(async () => {
      const storedAfterAck = await loadCanvasDrafts(CANVAS_ID)
      expect(storedAfterAck[NODE_NOTE]?.position).toBeUndefined()
    })

    // 3. 紧接着在同一组件实例中触发另一次草稿持久化（编辑另一节点）
    act(() => {
      result.current.scheduleFunctionConfig(NODE_FN, 'fake-image', { prompt: { segments: [{ type: 'TEXT', text: 'another' }] } })
    })

    await waitFor(async () => {
      const stored = await loadCanvasDrafts(CANVAS_ID)
      expect(stored[NODE_FN]?.function?.args.prompt.segments[0].text).toBe('another')
      // 关键断言：NODE_NOTE 的 position 绝不被复活写回 IDB！
      expect(stored[NODE_NOTE]?.position).toBeUndefined()
    })
  })

  it('先 queue persist A、ACK、queued persist 严格测试：旧 target 不在执行时复活已 ACK 草稿', async () => {
    const { result } = renderHook(() => useCanvasController(CANVAS_ID), { wrapper: Wrapper })
    await waitFor(() => expect(result.current.snapshot?.document.id).toBe(CANVAS_ID))

    // 1. 产生草稿，persist A 入队
    act(() => {
      result.current.moveNodes([{
        id: NODE_NOTE,
        kind: 'text',
        transform: { x: 777, y: 888, width: 320, height: 260 },
      }])
    })

    // 2. 紧接着入队 ACK 任务与后续的 persist 任务
    const ackPromise = result.current.ackDurableDrafts([{
      nodeId: NODE_NOTE,
      field: 'position',
      generation: 1,
    }])

    result.current.retryDraftPersist()

    await act(async () => {
      await ackPromise
    })

    await waitFor(async () => {
      const stored = await loadCanvasDrafts(CANVAS_ID)
      expect(stored[NODE_NOTE]?.position).toBeUndefined()
    })
  })

  it('canvas unmount 期间 ACK 排队：原草稿落盘清理成功后才 remove 原 op', async () => {
    // 测试意图：验证命令发送后在 ACK 排队处理期间组件被 unmount，
    // 队列专属 onDraftAcks 针对原 identity 即使卸载也必须 await 实际 IDB commit 清理原草稿，
    // 且只有在持久草稿被成功清除后，queue 才能 settle 并 remove 该 operation。
    const releasePost = deferred<CanvasPatchDTO>()
    vi.mocked(postCanvasCommands).mockImplementationOnce(async (_canvasId, request) => {
      commands.push(request)
      current = applyCommandBatch(current, request.commands)
      return releasePost.promise
    })

    const { result, unmount } = renderHook(() => useCanvasController(CANVAS_ID), { wrapper: Wrapper })
    await waitFor(() => expect(result.current.snapshot?.document.id).toBe(CANVAS_ID))

    // 1. 产生本地草稿并提交命令（命令在途挂起）
    act(() => {
      result.current.moveNodes([{
        id: NODE_NOTE,
        kind: 'text',
        transform: { x: 555, y: 666, width: 320, height: 260 },
      }])
      result.current.commitTransforms()
    })

    await waitFor(() => expect(commands).toHaveLength(1))

    // 验证 operation 已经写入 operation store
    await waitFor(async () => {
      const ops = await loadCanvasOperations(CANVAS_ID)
      expect(ops.length).toBeGreaterThan(0)
    })

    // 2. 在服务端响应未返回前卸载组件（unmount）
    unmount()

    // 3. 服务端响应返回，触发队列 settle -> onDraftAcks
    await act(async () => {
      releasePost.resolve(diffPatch(current))
      await releasePost.promise
    })

    // 验证：即使卸载，原 canvas 的 draft 也必须在 IDB 中被清除
    await waitFor(async () => {
      const stored = await loadCanvasDrafts(CANVAS_ID)
      expect(stored[NODE_NOTE]?.position).toBeUndefined()
    })

    // 验证：原 operation 也在持久草稿清除后被成功 remove
    await waitFor(async () => {
      const ops = await loadCanvasOperations(CANVAS_ID)
      expect(ops.length).toBe(0)
    })
  })

  it('canvas 切换期间 ACK 排队：不动新 canvas 草稿（同 nodeId 双 canvas 隔离）', async () => {
    // 测试意图：验证存在同 nodeId 的两个画布 CANVAS_A 和 CANVAS_B，
    // CANVAS_A 提交操作在途时切换到 CANVAS_B 并产生同 nodeId 的新草稿，
    // CANVAS_A 的迟到 ACK 响应仅清理 CANVAS_A 的持久草稿，绝不误删或回滚 CANVAS_B 的内存与持久草稿。
    const CANVAS_B = '99999999-4b9f-4c5d-9e6f-1a2b3c4d5e6f' as UUIDString
    const snapshotB: CanvasSnapshotDTO = {
      ...snapshot(0),
      document: { ...snapshot(0).document, id: CANVAS_B },
      nodes: snapshot(0).nodes.map((n) => ({ ...n, canvasId: CANVAS_B })),
      groups: snapshot(0).groups.map((g) => ({ ...g, canvasId: CANVAS_B })),
    }

    vi.mocked(getCanvas).mockImplementation(async (id) => {
      if (id === CANVAS_B) return snapshotB
      return current
    })

    const releasePostA = deferred<CanvasPatchDTO>()
    vi.mocked(postCanvasCommands).mockImplementation(async (canvasId, request) => {
      commands.push(request)
      if (canvasId === CANVAS_ID) {
        current = applyCommandBatch(current, request.commands)
        return releasePostA.promise
      }
      return diffPatch(applyCommandBatch(snapshotB, request.commands))
    })

    const { result } = renderHook(() => useCanvasController(CANVAS_ID), { wrapper: Wrapper })
    await waitFor(() => expect(result.current.snapshot?.document.id).toBe(CANVAS_ID))

    // 1. 在 CANVAS_A 产生 NODE_NOTE 草稿并提交，请求挂起
    act(() => {
      result.current.moveNodes([{
        id: NODE_NOTE,
        kind: 'text',
        transform: { x: 111, y: 111, width: 320, height: 260 },
      }])
      result.current.commitTransforms()
    })
    await waitFor(() => expect(commands).toHaveLength(1))

    // 2. 切换至 CANVAS_B
    act(() => {
      result.current.openEditor(CANVAS_B)
    })
    await waitFor(() => expect(result.current.snapshot?.document.id).toBe(CANVAS_B))

    // 3. 在 CANVAS_B 下为同名 NODE_NOTE 产生新草稿
    act(() => {
      result.current.moveNodes([{
        id: NODE_NOTE,
        kind: 'text',
        transform: { x: 999, y: 999, width: 320, height: 260 },
      }])
    })
    await waitFor(async () => {
      const storedB = await loadCanvasDrafts(CANVAS_B)
      expect(storedB[NODE_NOTE]?.position).toEqual({ x: 999, y: 999 })
    })
    expect(result.current.state.drafts[NODE_NOTE]?.position).toEqual({ x: 999, y: 999 })

    // 4. 此时 CANVAS_A 的慢响应返回并执行 ACK
    await act(async () => {
      releasePostA.resolve(diffPatch(current))
      await releasePostA.promise
    })

    // 5. 验证：CANVAS_A 的草稿已被清除
    await waitFor(async () => {
      const storedA = await loadCanvasDrafts(CANVAS_ID)
      expect(storedA[NODE_NOTE]?.position).toBeUndefined()
    })

    // 关键验证：CANVAS_B 的内存草稿与 IDB 草稿绝不被误删或改动！
    expect(result.current.state.drafts[NODE_NOTE]?.position).toEqual({ x: 999, y: 999 })
    const storedBAfter = await loadCanvasDrafts(CANVAS_B)
    expect(storedBAfter[NODE_NOTE]?.position).toEqual({ x: 999, y: 999 })
  })

  it('durable ACK 失败时必须 reject 并保留 operation，不可伪装成功删除操作', async () => {
    // 测试意图：验证若 ackCanvasDrafts 在持久提交过程中失败抛错，
    // onDraftAcks 必须向队列 reject 抛错，阻止队列删除 operation，避免持久草稿未清理却丢失待确认操作。
    const { result } = renderHook(() => useCanvasController(CANVAS_ID), { wrapper: Wrapper })
    await waitFor(() => expect(result.current.snapshot?.document.id).toBe(CANVAS_ID))

    // 模拟 ackCanvasDrafts 抛出 IDB 错误
    const ackSpy = vi.spyOn(draftStorageModule, 'ackCanvasDrafts').mockRejectedValueOnce(
      new Error('IndexedDB commit failed'),
    )

    act(() => {
      result.current.moveNodes([{
        id: NODE_NOTE,
        kind: 'text',
        transform: { x: 333, y: 444, width: 320, height: 260 },
      }])
      result.current.commitTransforms()
    })

    await waitFor(() => expect(commands).toHaveLength(1))

    // 验证：由于 ACK 失败，operation 依然保留在 operation store 中，未被删除
    await waitFor(async () => {
      const ops = await loadCanvasOperations(CANVAS_ID)
      expect(ops.length).toBeGreaterThan(0)
    })

    ackSpy.mockRestore()
  })
})
