import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode } from 'react'
import {
  MemoryRouter,
  Route,
  Routes,
  useLocation,
} from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { CanvasPage } from '@/features/canvas/CanvasPage'
import type { ResourceNode } from '@/features/canvas/domain'
import { canvasViewportStorageKey } from '@/features/canvas/viewport-storage'
import { applyEntityPatch } from '@/features/canvas/entity-patch'
import type {
  CanvasCommandDTO,
  CanvasDocumentDTO,
  CanvasGroupDTO,
  CanvasLinkDTO,
  CanvasNodePatchDTO,
  CanvasPatchDTO,
  CanvasResourceNodeDTO,
  CanvasSnapshotDTO,
} from '@/shared/api/contracts/studio'
import { setLocale } from '@/shared/i18n'
import { ApplicationEventProvider, createApplicationEventUrl } from '@/shared/app-events'
import { FakeWebSocketHarness } from '@/shared/app-events/__tests__/fake-websocket'

const CANVAS_ID = 'd1b2c3d4-5e6f-4a7b-8c9d-0e1f2a3b4c5d'
const CREATED_CANVAS_ID = 'e2f3a4b5-6c7d-4e8f-9a0b-1c2d3e4f5a6b'
const MISSING_CANVAS_ID = 'f3a4b5c6-7d8e-4f9a-8b0c-1d2e3f4a5b6c'
const NODE_A = '9f1e6d2a-3b4c-4d5e-8f6a-7b8c9d0e1f2a'
const NODE_B = 'a2b3c4d5-6e7f-4a8b-9c0d-1e2f3a4b5c6d'
const NODE_C = 'b3c4d5e6-7f8a-4b9c-8d0e-1f2a3b4c5d6e'
const GROUP_ID = 'c4d5e6f7-8a9b-4c0d-8e1f-2a3b4c5d6e7f'
const RESOURCE_ID = 'd5e6f7a8-9b0c-4d1e-8f2a-3b4c5d6e7f8a'
const STORAGE_UPLOAD_ID = 'e6f7a8b9-0c1d-4e2f-8a3b-4c5d6e7f8a9b'

const flowHarness = vi.hoisted(() => ({
  current: null as unknown,
  nodesInitialized: true,
  viewport: { x: 0, y: 0, zoom: 1 },
  fitView: vi.fn(async () => true),
  setViewport: vi.fn(async () => undefined),
  zoomTo: vi.fn(async () => undefined),
}))

/** 记录每个被创建的 WebSocket；测试可打开连接并派发 server 帧。 */
const socketHarness = vi.hoisted(() => ({
  harness: null as unknown as import('@/shared/app-events/__tests__/fake-websocket').FakeWebSocketHarness,
}))

vi.mock('@xyflow/react', () => ({
  ReactFlowProvider: ({ children }: { children: ReactNode }) => <>{children}</>,
  ReactFlow: (props: {
    children: ReactNode
    nodes: unknown[]
    edges: unknown[]
    onNodesChange?: (changes: unknown[]) => void
    onNodeDragStop?: () => void
  }) => {
    flowHarness.current = props
    return (
      <div
        data-testid="react-flow"
        data-node-count={props.nodes.length}
        data-edge-count={props.edges.length}
      >
        {props.children}
      </div>
    )
  },
  Background: () => null,
  BackgroundVariant: { Dots: 'dots' },
  MiniMap: () => <div data-testid="minimap" />,
  SelectionMode: { Partial: 'partial' },
  Handle: () => null,
  Position: { Left: 'left', Right: 'right' },
  useNodesInitialized: () => flowHarness.nodesInitialized,
  useReactFlow: () => ({
    fitView: flowHarness.fitView,
    getViewport: () => flowHarness.viewport,
    setViewport: flowHarness.setViewport,
    zoomTo: flowHarness.zoomTo,
  }),
}))

// jsdom 没有 Worker：hasher 在画布上传路径上以确定性 fake 代替，
// 存储 wire 契约仍由 installBackend 的 fetch 桩完整验证。
vi.mock('@/features/ai/composer', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/features/ai/composer')>()
  return { ...actual, createWorkerHasher: () => async () => 'b'.repeat(64) }
})

// 真实 storageService 的 reserve/complete 走 axios（XHR），jsdom 无后端；
// 这里用真实 createStorageService + fetch 桩 HttpClient 复现 wire 契约，
// uploadFile（直传面）与 header 过滤仍是生产实现。
vi.mock('@/shared/api/storage-service', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/shared/api/storage-service')>()
  const client = {
    get: async (url: string) => {
      const response = await fetch(`/api${url}`, { method: 'GET' })
      return (await response.json() as { data: unknown }).data
    },
    post: async (url: string, data?: unknown) => {
      const response = await fetch(`/api${url}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
      })
      return (await response.json() as { data: unknown }).data
    },
    put: async () => {
      throw new Error('unused storage PUT via client')
    },
    delete: async () => undefined,
  }
  return { ...actual, storageService: actual.createStorageService(client) }
})

function canvasDocument(id = CANVAS_ID, title = '真实画布'): CanvasDocumentDTO {
  return {
    id,
    title,
    version: '0',
    threadId: null,
    createdAt: '2026-08-10T00:00:00Z',
    updatedAt: '2026-08-10T00:00:00Z',
  }
}

function baseSnapshot(document = canvasDocument()): CanvasSnapshotDTO {
  return {
    document,
    nodes: [],
    groups: [],
    links: [],
  }
}

function envelope(data: unknown, status = 200, message = 'ok') {
  return new Response(JSON.stringify({
    status,
    code: status === 201
      ? 'CREATED'
      : status === 202
        ? 'ACCEPTED'
        : status === 404
          ? 'NOT_FOUND'
          : status >= 400
            ? 'ERROR'
            : 'OK',
    message,
    data,
    errors: status >= 400 ? { detail: message } : null,
  }), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

interface BackendOptions {
  documents?: CanvasDocumentDTO[]
  listFailures?: number
  missingCanvasIds?: string[]
}

function installBackend(options: BackendOptions = {}) {
  const snapshots = new Map<string, CanvasSnapshotDTO>()
  for (const document of options.documents ?? [canvasDocument()]) {
    snapshots.set(document.id, baseSnapshot(document))
  }
  let listFailures = options.listFailures ?? 0
  const missing = new Set(options.missingCanvasIds ?? [])
  const commandBodies: Array<{
    expectedVersion: string
    commandId: string
    commands: CanvasCommandDTO[]
  }> = []
  const createBodies: unknown[] = []
  const changesQueries: Array<{ canvasId: string; afterVersion: string }> = []

  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    if (url === '/api/canvases' && method === 'GET') {
      if (listFailures > 0) {
        listFailures -= 1
        return envelope(null, 503, 'temporary outage')
      }
      return envelope([...snapshots.values()].map((snapshot) => snapshot.document))
    }
    if (url === '/api/canvases' && method === 'POST') {
      createBodies.push(JSON.parse(String(init?.body)))
      const document = canvasDocument(CREATED_CANVAS_ID, '未命名画布')
      snapshots.set(document.id, baseSnapshot(document))
      return envelope(document, 201)
    }
    const resourceUrlMatch = /^\/api\/canvases\/([^/]+)\/resources\/([^/]+)\/(preview-url|download-url)$/.exec(url)
    if (resourceUrlMatch && method === 'POST') {
      return envelope({
        method: 'GET',
        url: resourceUrlMatch[3] === 'download-url'
          ? 'https://s3.example/original'
          : 'https://s3.example/preview',
        headers: {},
        expiresAt: '2026-08-10T00:15:00Z',
      })
    }
    if (url === '/api/canvas-function-models') {
      return envelope([{
        key: 'fake-image',
        label: 'Fake Image',
        outputKind: 'IMAGE',
        referencePolicy: {
          allowedKinds: ['IMAGE'],
          maxReferences: 12,
          maxByKind: {},
        },
        parameters: [{
          key: 'ratio',
          label: '比例',
          type: 'ENUM',
          required: false,
          defaultValue: 'AUTO',
          options: ['AUTO', '16:9'],
          min: null,
          max: null,
        }],
        available: true,
        unavailableReason: null,
      }])
    }
    const canvasMatch = /^\/api\/canvases\/([^/]+)$/.exec(url)
    if (canvasMatch && method === 'GET') {
      const canvasId = canvasMatch[1] as string
      if (missing.has(canvasId) || !snapshots.has(canvasId)) {
        return envelope(null, 404, 'canvas not found')
      }
      return envelope(snapshots.get(canvasId))
    }
    const changesMatch = /^\/api\/canvases\/([^/]+)\/changes\?afterVersion=(\d+)$/.exec(url)
    if (changesMatch && method === 'GET') {
      const canvasId = changesMatch[1] as string
      const afterVersion = changesMatch[2] as string
      changesQueries.push({ canvasId, afterVersion })
      return envelope({ patches: [], snapshot: null })
    }
    const commandMatch = /^\/api\/canvases\/([^/]+)\/commands$/.exec(url)
    if (commandMatch && method === 'POST') {
      const canvasId = commandMatch[1] as string
      const body = JSON.parse(String(init?.body)) as {
        expectedVersion: string
        commandId: string
        commands: CanvasCommandDTO[]
      }
      commandBodies.push(body)
      const current = snapshots.get(canvasId)
      if (!current) {
        return envelope(null, 404, 'canvas not found')
      }
      const patch = buildPatch(current, body.commands)
      const next = applyEntityPatch(current, patch)
      if (next) {
        snapshots.set(canvasId, next)
      }
      return envelope(patch)
    }
    // 共享存储上传：reserve（PENDING）→ 浏览器直传 PUT → complete（READY）。
    if (url === '/api/storage/uploads' && method === 'POST') {
      const body = JSON.parse(String(init?.body)) as { sha256?: string }
      if (typeof body.sha256 !== 'string' || !/^[0-9a-f]{64}$/.test(body.sha256)) {
        return envelope(null, 400, 'invalid sha256')
      }
      return envelope({
        id: STORAGE_UPLOAD_ID,
        state: 'PENDING',
        blobId: null,
        presignedPut: {
          method: 'PUT',
          url: 'https://s3.example/direct',
          headers: {
            Host: 's3.internal',
            'If-None-Match': '*',
            'Content-Type': 'image/png',
          },
        },
        expiresAt: '2026-08-10T00:15:00Z',
      }, 201)
    }
    if (url === 'https://s3.example/direct' && method === 'PUT') {
      return new Response(null, { status: 200 })
    }
    if (url === `/api/storage/uploads/${STORAGE_UPLOAD_ID}/complete` && method === 'POST') {
      return envelope({
        id: STORAGE_UPLOAD_ID,
        state: 'READY',
        blobId: '00000000-0000-4000-8000-0000000000aa',
        presignedPut: null,
        expiresAt: '2026-08-10T00:15:00Z',
      })
    }
    // Canvas 空 Thread 面板的 catalog/environment 查询（空数据即可）。
    if (url.startsWith('/api/ai/catalog/agents') && method === 'GET') {
      return envelope({ results: [], total: 0 })
    }
    if (url.startsWith('/api/ai/catalog/models') && method === 'GET') {
      return envelope({ results: [], total: 0 })
    }
    if (url === '/api/ai/environment' && method === 'GET') {
      return envelope([])
    }
    throw new Error(`Unexpected request ${method} ${url}`)
  }))

  return { commandBodies, createBodies, snapshots, changesQueries }
}

/**
 * 把命令批折叠为一张连续 patch（baseVersion -> version+1）。只覆盖本测试
 * 实际发送的命令类型；CREATE_TEXT_NODE 等新命令一律携带客户端 UUID。
 * wire 版本是十进制字符串，前进用 bigint-safe 避免 JS number 精度问题。
 */
function nextVersion(version: string): string {
  return String(BigInt(version) + 1n)
}

function buildPatch(
  current: CanvasSnapshotDTO,
  commands: CanvasCommandDTO[],
): CanvasPatchDTO {
  let nodes = current.nodes
  let groups = current.groups
  let links = current.links
  const nodePatches: CanvasNodePatchDTO[] = []
  const groupPatches: CanvasPatchDTO['groups'] = []
  const linkPatches: CanvasPatchDTO['links'] = []

  const upsertNode = (node: CanvasResourceNodeDTO) => {
    nodes = nodes.map((item) => item.id === node.id ? node : item)
    if (!nodes.some((item) => item.id === node.id)) {
      nodes = [...nodes, node]
    }
    nodePatches.push({ op: 'UPSERT', node })
  }

  for (const command of commands) {
    if (command.type === 'CREATE_TEXT_NODE') {
      upsertNode({
        id: command.nodeId,
        canvasId: current.document.id,
        name: command.name,
        transform: command.transform,
        groupId: null,
        resources: [{
          id: crypto.randomUUID(),
          canvasId: current.document.id,
          ownerNodeId: command.nodeId,
          resourceIndex: 0,
          blobId: null,
          name: command.name,
          textContent: command.markdown,
          kind: 'TEXT',
          mediaType: 'text/markdown',
          // wire long：十进制字符串（studio-service adapter 归一化为 number）。
          sizeBytes: String(command.markdown.length),
          width: null,
          height: null,
          durationMs: null,
          createdAt: '2026-08-10T00:00:00Z',
        }],
        function: null,
        run: null,
      })
    } else if (command.type === 'UPDATE_TEXT_NODE') {
      const node = nodes.find((item) => item.id === command.nodeId)
      if (node) {
        upsertNode({
          ...node,
          resources: node.resources.map((resource) => (
            resource.kind === 'TEXT'
              ? { ...resource, textContent: command.markdown, sizeBytes: String(command.markdown.length) }
              : resource
          )),
        })
      }
    } else if (command.type === 'CREATE_FUNCTION_NODE') {
      upsertNode({
        id: command.nodeId,
        canvasId: current.document.id,
        name: command.name,
        transform: command.transform,
        groupId: null,
        resources: [],
        function: { modelKey: command.modelKey, configJson: command.configJson },
        run: null,
      })
    } else if (command.type === 'CREATE_RESOURCE_NODE') {
      const uploadId = command.uploadIds[0] as string
      upsertNode({
        id: command.nodeId,
        canvasId: current.document.id,
        name: command.name,
        transform: command.transform,
        groupId: null,
        resources: [{
          id: uploadId,
          canvasId: current.document.id,
          ownerNodeId: command.nodeId,
          resourceIndex: 0,
          blobId: '00000000-0000-4000-8000-0000000000aa',
          name: command.name,
          textContent: null,
          kind: 'IMAGE',
          mediaType: 'image/png',
          // wire long：十进制字符串（studio-service adapter 归一化为 number）。
          sizeBytes: '3',
          width: 1,
          height: 1,
          durationMs: null,
          createdAt: '2026-08-10T00:00:00Z',
        }],
        function: null,
        run: null,
      })
    } else if (command.type === 'UPDATE_FUNCTION') {
      const node = nodes.find((item) => item.id === command.nodeId)
      if (node) {
        upsertNode({
          ...node,
          function: { modelKey: command.modelKey, configJson: command.configJson },
        })
      }
    } else if (command.type === 'UPDATE_NODE_TRANSFORMS') {
      for (const update of command.updates) {
        const node = nodes.find((item) => item.id === update.nodeId)
        if (node) {
          upsertNode({ ...node, transform: update.transform })
        }
      }
    } else if (command.type === 'RENAME_NODE') {
      const node = nodes.find((item) => item.id === command.nodeId)
      if (node) {
        upsertNode({ ...node, name: command.name })
      }
    } else if (command.type === 'DELETE_NODE') {
      nodes = nodes.filter((item) => item.id !== command.nodeId)
      nodePatches.push({ op: 'REMOVE', nodeId: command.nodeId })
    } else if (command.type === 'CREATE_LINK') {
      const link: CanvasLinkDTO = {
        canvasId: current.document.id,
        sourceNodeId: command.sourceNodeId,
        targetNodeId: command.targetNodeId,
      }
      links = [...links, link]
      linkPatches.push({ op: 'UPSERT', link })
    } else if (command.type === 'DELETE_LINK') {
      links = links.filter((link) => (
        link.sourceNodeId !== command.sourceNodeId || link.targetNodeId !== command.targetNodeId
      ))
      linkPatches.push({
        op: 'REMOVE',
        sourceNodeId: command.sourceNodeId,
        targetNodeId: command.targetNodeId,
      })
    } else if (command.type === 'CREATE_GROUP') {
      const group: CanvasGroupDTO = {
        id: command.groupId,
        canvasId: current.document.id,
        title: command.title,
        transform: command.transform,
      }
      groups = [...groups, group]
      groupPatches.push({ op: 'UPSERT', group })
      for (const memberNodeId of command.memberNodeIds) {
        const node = nodes.find((item) => item.id === memberNodeId)
        if (node && node.groupId === null) {
          upsertNode({ ...node, groupId: command.groupId })
        }
      }
    } else if (command.type === 'MOVE_GROUP') {
      const group = groups.find((item) => item.id === command.groupId)
      if (group) {
        const deltaX = command.x - group.transform.x
        const deltaY = command.y - group.transform.y
        for (const node of nodes.filter((item) => item.groupId === group.id)) {
          upsertNode({
            ...node,
            transform: {
              ...node.transform,
              x: node.transform.x + deltaX,
              y: node.transform.y + deltaY,
            },
          })
        }
        const next: CanvasGroupDTO = {
          ...group,
          transform: { ...group.transform, x: command.x, y: command.y },
        }
        groups = groups.map((item) => item.id === next.id ? next : item)
        groupPatches.push({ op: 'UPSERT', group: next })
      }
    } else if (command.type === 'DELETE_GROUP') {
      groups = groups.filter((item) => item.id !== command.groupId)
      groupPatches.push({ op: 'REMOVE', groupId: command.groupId })
      for (const node of nodes.filter((item) => item.groupId === command.groupId)) {
        upsertNode({ ...node, groupId: null })
      }
    } else if (command.type === 'RENAME_GROUP') {
      const group = groups.find((item) => item.id === command.groupId)
      if (group) {
        const next: CanvasGroupDTO = { ...group, title: command.title }
        groups = groups.map((item) => item.id === next.id ? next : item)
        groupPatches.push({ op: 'UPSERT', group: next })
      }
    } else if (command.type === 'UNGROUP') {
      const group = groups.find((item) => item.id === command.groupId)
      if (group) {
        for (const memberId of command.memberNodeIds) {
          const node = nodes.find((item) => item.id === memberId)
          if (node?.groupId === group.id) {
            upsertNode({ ...node, groupId: null })
          }
        }
        if (!nodes.some((node) => node.groupId === group.id)) {
          groups = groups.filter((item) => item.id !== group.id)
          groupPatches.push({ op: 'REMOVE', groupId: group.id })
        }
      }
    }
  }

  return {
    baseVersion: current.document.version,
    version: nextVersion(current.document.version),
    groups: groupPatches,
    nodes: nodePatches,
    links: linkPatches,
  }
}

function PathnameProbe() {
  const location = useLocation()
  return <output data-testid="pathname">{location.pathname}</output>
}

function renderCanvasPage(initialEntries = ['/canvas']) {
  return render(
    <ApplicationEventProvider
      url={createApplicationEventUrl()}
      socketFactory={socketHarness.harness.factory}
    >
      <MemoryRouter initialEntries={initialEntries}>
        <PathnameProbe />
        <Routes>
          <Route path="/canvas" element={<CanvasPage />} />
          <Route path="/canvas/:canvasId" element={<CanvasPage />} />
        </Routes>
      </MemoryRouter>
    </ApplicationEventProvider>,
  )
}

describe('CanvasPage real list/create/load integration', () => {
  beforeEach(() => {
    localStorage.clear()
    setLocale('zh-CN')
    flowHarness.current = null
    flowHarness.nodesInitialized = true
    flowHarness.viewport = { x: 0, y: 0, zoom: 1 }
    flowHarness.fitView.mockReset()
    flowHarness.fitView.mockResolvedValue(true)
    flowHarness.setViewport.mockReset()
    flowHarness.setViewport.mockResolvedValue(undefined)
    flowHarness.zoomTo.mockReset()
    flowHarness.zoomTo.mockResolvedValue(undefined)
    socketHarness.harness = new FakeWebSocketHarness()
  })

  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('loads the authoritative snapshot and sends typed text/function commands', async () => {
    const { commandBodies } = installBackend()
    const user = userEvent.setup()
    flowHarness.viewport = { x: 24, y: 36, zoom: 0.9 }
    renderCanvasPage()

    await user.click(await screen.findByRole('button', { name: /真实画布/ }))
    expect(screen.getByTestId('pathname')).toHaveTextContent(`/canvas/${CANVAS_ID}`)
    expect(await screen.findByLabelText(/无限画布/)).toBeInTheDocument()
    expect(screen.getByText('v0')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '添加资源或 Function' }))
    expect(within(screen.getByRole('menu')).queryByRole('menuitem', { name: /分组/ })).not.toBeInTheDocument()
    await user.click(within(screen.getByRole('menu')).getByRole('menuitem', { name: /文本资源/ }))
    await user.clear(screen.getByLabelText('Markdown 内容'))
    await user.type(screen.getByLabelText('Markdown 内容'), '# E2E 文本')
    await user.click(screen.getByRole('button', { name: '保存' }))
    await waitFor(() => {
      expect(commandBodies.some((body) => body.commands[0]?.type === 'CREATE_TEXT_NODE')).toBe(true)
    })
    // 每个新命令都携带客户端 UUID：nodeId/commandId 均 shape 校验通过。
    expect(commandBodies.every((body) => (
      body.commands.every((command) => (
        'nodeId' in command
        ? /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(command.nodeId)
        : true
      ))
      && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(body.commandId)
      && typeof body.expectedVersion === 'string'
      && /^(0|[1-9][0-9]*)$/.test(body.expectedVersion)
    ))).toBe(true)
    // An empty canvas waits for its first measured node, then performs the initial fit exactly once.
    await waitFor(() => {
      expect(flowHarness.fitView).toHaveBeenCalledOnce()
    })
    expect(flowHarness.fitView).toHaveBeenCalledWith({
      padding: 0.18,
      maxZoom: 1.6,
      duration: 0,
    })
    // 底部不再覆盖 composer，因此初始 fit 直接采用 React Flow 的居中结果。
    await waitFor(() => {
      expect(JSON.parse(localStorage.getItem(canvasViewportStorageKey(CANVAS_ID)) ?? '')).toEqual({
        x: 24,
        y: 36,
        zoom: 0.9,
      })
    })

    await user.click(screen.getByRole('button', { name: '添加资源或 Function' }))
    await user.click(within(screen.getByRole('menu')).getByRole('menuitem', { name: /图片生成/ }))
    await waitFor(() => {
      expect(commandBodies.some((body) => body.commands[0]?.type === 'CREATE_FUNCTION_NODE')).toBe(true)
    })
  })

  it('shows the empty state, creates a canvas, and loads its empty snapshot', async () => {
    const { createBodies } = installBackend({ documents: [] })
    const user = userEvent.setup()
    renderCanvasPage()

    expect(await screen.findByText('还没有画布')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '创建新画布' }))

    // 导航发生在 create mutation 成功回调中，因此先等编辑器就绪再断言 URL。
    expect(await screen.findByText('未命名画布')).toBeInTheDocument()
    expect(await screen.findByLabelText(/无限画布/)).toBeInTheDocument()
    expect(screen.getByTestId('pathname')).toHaveTextContent(`/canvas/${CREATED_CANVAS_ID}`)
    expect(createBodies).toEqual([{ title: '未命名画布' }])
  })

  it('renders a real list error and recovers only after the user retries', async () => {
    installBackend({ listFailures: 1 })
    const user = userEvent.setup()
    renderCanvasPage()

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('temporary outage')
    expect(screen.queryByRole('button', { name: /真实画布/ })).not.toBeInTheDocument()

    await user.click(within(alert).getByRole('button', { name: '重试' }))
    expect(await screen.findByRole('button', { name: /真实画布/ })).toBeInTheDocument()
  })

  it('renders a real editor 404 without offering a retry loop', async () => {
    installBackend({
      documents: [canvasDocument(MISSING_CANVAS_ID, '已删除画布')],
      missingCanvasIds: [MISSING_CANVAS_ID],
    })
    const user = userEvent.setup()
    renderCanvasPage()

    await user.click(await screen.findByRole('button', { name: /已删除画布/ }))
    expect(screen.getByTestId('pathname')).toHaveTextContent(`/canvas/${MISSING_CANVAS_ID}`)
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('画布不存在')
    expect(within(alert).queryByRole('button', { name: '重试' })).not.toBeInTheDocument()
    await user.click(within(alert).getByRole('button', { name: '返回画布库' }))
    expect(screen.getByTestId('pathname')).toHaveTextContent('/canvas')
    expect(await screen.findByText('你的画布')).toBeInTheDocument()
  })

  it('loads a direct editor URL and derives the zoom label from the live viewport', async () => {
    // A direct route is refresh-equivalent: it queries the snapshot without a library click.
    installBackend()
    renderCanvasPage([`/canvas/${CANVAS_ID}`])

    expect(await screen.findByLabelText(/无限画布/)).toBeInTheDocument()
    expect(screen.getByTestId('pathname')).toHaveTextContent(`/canvas/${CANVAS_ID}`)
    const resetZoom = screen.getByRole('button', { name: '重置缩放为 100%' })
    expect(resetZoom).toHaveTextContent('100%')

    act(() => {
      ;(flowHarness.current as {
        onMove: (
          event: unknown,
          viewport: { x: number; y: number; zoom: number },
        ) => void
      }).onMove(null, { x: 12, y: 18, zoom: 0.74 })
    })
    expect(resetZoom).toHaveTextContent('74%')
  })

  it('uses the same icon-only back control as the AI Chat workspace', async () => {
    installBackend()
    const user = userEvent.setup()
    renderCanvasPage([`/canvas/${CANVAS_ID}`])

    await screen.findByLabelText(/无限画布/)
    const back = screen.getByRole('link', { name: '返回画布库' })
    expect(back).toHaveClass('sidebar-icon-btn', 'canvas-back-button')
    expect(back).toHaveAttribute('href', '/canvas')
    await user.click(back)
    expect(screen.getByTestId('pathname')).toHaveTextContent('/canvas')
  })

  it.each(['/canvas/0', '/canvas/01', '/canvas/-1', '/canvas/not-a-number'])(
    'redirects invalid canvas id %s to the library',
    async (path) => {
      // 非 canonical UUID（包括旧十进制 id）在任何快照请求前被拒绝。
      installBackend()
      renderCanvasPage([path])

      expect(await screen.findByText('你的画布')).toBeInTheDocument()
      expect(screen.getByTestId('pathname')).toHaveTextContent('/canvas')
      const canvasId = path.slice('/canvas/'.length)
      expect(vi.mocked(fetch).mock.calls.some(([url]) => (
        String(url) === `/api/canvases/${canvasId}`
      ))).toBe(false)
    },
  )

  it('restores a valid persisted viewport without running the initial fit', async () => {
    // A measured node must not override a viewport explicitly restored for this canvas.
    const { snapshots } = installBackend()
    const current = snapshots.get(CANVAS_ID) as CanvasSnapshotDTO
    snapshots.set(CANVAS_ID, {
      ...current,
      nodes: [{
        id: NODE_A,
        canvasId: CANVAS_ID,
        name: 'note',
        transform: { x: 100, y: 100, width: 320, height: 260 },
        groupId: null,
        resources: [],
        function: null,
        run: null,
      }],
    })
    localStorage.setItem(
      canvasViewportStorageKey(CANVAS_ID),
      JSON.stringify({ x: 30, y: -20, zoom: 0.7 }),
    )

    renderCanvasPage([`/canvas/${CANVAS_ID}`])

    expect(await screen.findByLabelText(/无限画布/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '重置缩放为 100%' })).toHaveTextContent('70%')
    expect(flowHarness.fitView).not.toHaveBeenCalled()
    expect((flowHarness.current as {
      defaultViewport: { x: number; y: number; zoom: number }
    }).defaultViewport).toEqual({ x: 30, y: -20, zoom: 0.7 })
  })

  it('subscribes the canvas resource over the shared WebSocket and fetches changes on version events', async () => {
    // subscribed ack（首次订阅建立）即按当前版本同步一次 changes；'version'
    // 事件再触发一次，但重复/旧版本事件不产生额外请求。
    const { changesQueries } = installBackend()
    renderCanvasPage([`/canvas/${CANVAS_ID}`])

    await screen.findByLabelText(/无限画布/)
    const socket = socketHarness.harness.latest as NonNullable<
      typeof socketHarness.harness.latest
    >
    // 生产 URL 构造：ws(s)://<origin>/api/events/v1。
    expect(socket.url).toBe(createApplicationEventUrl())

    act(() => socket.open())
    expect(socket.sentMessages()).toEqual([
      { version: 1, type: 'subscribe', resource: { kind: 'canvas', id: CANVAS_ID } },
    ])
    // subscribed ack 关闭「快照 GET 与 wire 建立之间」的版本缺口。
    act(() => socket.emitServer({ type: 'subscribed', resource: { kind: 'canvas', id: CANVAS_ID }, cursor: '0' }))
    await waitFor(() => {
      expect(changesQueries).toHaveLength(1)
    })
    expect(changesQueries[0]).toEqual({ canvasId: CANVAS_ID, afterVersion: '0' })

    act(() =>
      socket.emitServer({
        type: 'event',
        resource: { kind: 'canvas', id: CANVAS_ID },
        name: 'version',
        data: { version: '2' },
        cursor: '2',
      }),
    )
    act(() =>
      socket.emitServer({
        type: 'event',
        resource: { kind: 'canvas', id: CANVAS_ID },
        name: 'version',
        data: { version: '0' },
        cursor: '0',
      }),
    )
    await waitFor(() => {
      expect(changesQueries).toHaveLength(2)
    })
    expect(changesQueries[1]).toEqual({ canvasId: CANVAS_ID, afterVersion: '0' })
  })

  it('collapses the Chat panel by default and toggles it from the editor header', async () => {
    // 默认只展示画布；空 Thread 面板复用共享 Attachment Pill Composer。
    const user = userEvent.setup()
    installBackend()
    renderCanvasPage([`/canvas/${CANVAS_ID}`])

    await screen.findByLabelText(/无限画布/)
    expect(screen.queryByLabelText('Canvas 对话面板')).not.toBeInTheDocument()
    expect(document.querySelector('.chat-shell.thread-panel')).toBeNull()

    const toggle = screen.getByRole('button', { name: /切换对话面板/ })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(toggle).toHaveAttribute('aria-controls', 'agentPanel')

    await user.click(toggle)
    expect(await screen.findByLabelText('Canvas 对话面板')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /切换对话面板/ })).toHaveAttribute(
      'aria-expanded',
      'true',
    )
    expect(document.querySelector('.canvas-blank-thread')).not.toBeNull()
    expect(screen.getByRole('textbox', { name: /消息/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '收起对话面板' })).toBeInTheDocument()

    await user.click(toggle)
    await waitFor(() => {
      expect(screen.queryByLabelText('Canvas 对话面板')).not.toBeInTheDocument()
    })
    expect(document.querySelector('.chat-shell.thread-panel')).toBeNull()
  })

  it('keeps the canvas zoom stable while the Agent panel changes available width', async () => {
    // The panel may translate the viewport to preserve world center, but opening it must not resize cards.
    const makeRect = (width: number, height: number, x = 0, y = 0) => ({
      x,
      y,
      width,
      height,
      top: y,
      right: x + width,
      bottom: y + height,
      left: x,
      toJSON: () => ({}),
    }) as DOMRect
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function () {
      if (this.classList.contains('canvas-flow-wrap')) {
        return makeRect(document.querySelector('#agentPanel') ? 788 : 1_268, 699, 0, 44)
      }
      return makeRect(0, 0)
    })
    const { snapshots } = installBackend()
    const current = snapshots.get(CANVAS_ID) as CanvasSnapshotDTO
    snapshots.set(CANVAS_ID, {
      ...current,
      nodes: [{
        id: NODE_A,
        canvasId: CANVAS_ID,
        name: 'note',
        transform: { x: 100, y: 80, width: 320, height: 260 },
        groupId: null,
        resources: [],
        function: null,
        run: null,
      }],
    })
    localStorage.setItem(
      canvasViewportStorageKey(CANVAS_ID),
      JSON.stringify({ x: 20, y: 50, zoom: 0.9 }),
    )
    const user = userEvent.setup()
    renderCanvasPage([`/canvas/${CANVAS_ID}`])

    await screen.findByLabelText(/无限画布/)
    expect(flowHarness.fitView).not.toHaveBeenCalled()
    flowHarness.setViewport.mockClear()

    const toggle = screen.getByRole('button', { name: /切换对话面板/ })
    await user.click(toggle)
    await waitFor(() => {
      expect(flowHarness.setViewport).toHaveBeenCalledWith({ x: -220, y: 50, zoom: 0.9 })
    })
    expect(flowHarness.fitView).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: '重置缩放为 100%' })).toHaveTextContent('90%')

    act(() => {
      ;(flowHarness.current as {
        onMove: (
          event: unknown,
          viewport: { x: number; y: number; zoom: number },
        ) => void
      }).onMove(null, { x: -100, y: 70, zoom: 1.1 })
    })
    expect(screen.getByRole('button', { name: '重置缩放为 100%' })).toHaveTextContent('110%')
    flowHarness.setViewport.mockClear()

    await user.click(toggle)
    await waitFor(() => {
      expect(flowHarness.setViewport).toHaveBeenLastCalledWith({ x: 140, y: 70, zoom: 1.1 })
    })
    expect(screen.getByRole('button', { name: '重置缩放为 100%' })).toHaveTextContent('110%')
  })

  it('caps manual fit and focus zoom at their readable visual scales', async () => {
    // UI and keyboard paths exercise the real React Flow options rather than a pure helper.
    installBackend()
    localStorage.setItem(
      canvasViewportStorageKey(CANVAS_ID),
      JSON.stringify({ x: 0, y: 0, zoom: 1 }),
    )
    const user = userEvent.setup()
    renderCanvasPage([`/canvas/${CANVAS_ID}`])
    const stage = await screen.findByLabelText(/无限画布/)

    await user.click(screen.getByRole('button', { name: '适应全部内容' }))
    expect(flowHarness.fitView).toHaveBeenNthCalledWith(1, {
      padding: 0.18,
      maxZoom: 1.6,
      duration: 0,
    })

    await user.click(screen.getByRole('button', { name: /切换对话面板/ }))
    await user.click(screen.getByRole('button', { name: '适应全部内容' }))
    expect(flowHarness.fitView).toHaveBeenNthCalledWith(2, {
      padding: 0.08,
      maxZoom: 1.6,
      duration: 0,
    })

    act(() => {
      stage.focus()
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'f' }))
    })
    await waitFor(() => {
      expect(flowHarness.fitView).toHaveBeenNthCalledWith(3, {
        nodes: undefined,
        padding: 0.22,
        maxZoom: 1.8,
        duration: 0,
      })
    })
  })

  it('keeps React Flow selection publication stable for an unchanged empty selection', async () => {
    // React Flow effects depend on callback identity, so unchanged selection must not cause a render loop.
    installBackend()
    const user = userEvent.setup()
    renderCanvasPage()

    await user.click(await screen.findByRole('button', { name: /真实画布/ }))
    await screen.findByLabelText(/无限画布/)
    const flow = flowHarness.current as {
      nodes: unknown[]
      onSelectionChange: (params: { nodes: unknown[]; edges: unknown[] }) => void
    }
    const initialNodes = flow.nodes
    const initialHandler = flow.onSelectionChange

    act(() => initialHandler({ nodes: [], edges: [] }))

    const current = flowHarness.current as typeof flow
    expect(current.onSelectionChange).toBe(initialHandler)
    expect(current.nodes).toBe(initialNodes)
  })

  it('completes drop upload as reserve -> direct PUT -> complete -> CREATE_RESOURCE_NODE', async () => {
    const { commandBodies } = installBackend()
    const user = userEvent.setup()
    renderCanvasPage()
    await user.click(await screen.findByRole('button', { name: /真实画布/ }))
    const stage = await screen.findByLabelText(/无限画布/)
    const file = new File(['png'], 'tiny.png', { type: 'image/png' })

    fireEvent.drop(stage, {
      dataTransfer: {
        files: [file],
        types: ['Files'],
      },
    })

    await waitFor(() => {
      expect(commandBodies.some((body) => body.commands.some(
        (command) => command.type === 'CREATE_RESOURCE_NODE',
      ))).toBe(true)
    })
    // 共享存储预留：服务端生成 upload 句柄，客户端只提交 filename/mediaType/sizeBytes/sha256。
    const reserve = vi.mocked(fetch).mock.calls.find(([url]) => (
      String(url) === '/api/storage/uploads'
    ))
    const reserveBody = JSON.parse(String(reserve?.[1]?.body)) as {
      filename: string
      mediaType: string
      sizeBytes: number
      sha256: string
    }
    expect(reserveBody).toEqual({
      filename: 'tiny.png',
      mediaType: 'image/png',
      sizeBytes: 3,
      sha256: 'b'.repeat(64),
    })
    expect(reserveBody).not.toHaveProperty('uploadId')
    const directPut = vi.mocked(fetch).mock.calls.find(([url]) => (
      String(url) === 'https://s3.example/direct'
    ))
    expect(directPut?.[1]).toEqual(expect.objectContaining({
      method: 'PUT',
      headers: {
        'If-None-Match': '*',
        'Content-Type': 'image/png',
      },
    }))
    // complete 返回同一 upload 句柄；CREATE_RESOURCE_NODE 只引用 uploadIds，
    // 不发送 resourceIds 或 canvas-scoped 上传 id。
    expect(vi.mocked(fetch).mock.calls.some(([url, init]) => (
      String(url) === `/api/storage/uploads/${STORAGE_UPLOAD_ID}/complete`
      && (init?.method ?? 'GET') === 'POST'
    ))).toBe(true)
    const createNode = commandBodies
      .flatMap((body) => body.commands)
      .find((command) => command.type === 'CREATE_RESOURCE_NODE')
    expect(createNode).toMatchObject({
      nodeId: expect.stringMatching(/^[0-9a-f-]{36}$/i),
      name: 'tiny.png',
      uploadIds: [STORAGE_UPLOAD_ID],
    })
    expect(createNode).not.toHaveProperty('resourceIds')
  })

  it('keeps drag positions as a local draft and submits them after drag stop debounce', async () => {
    const { commandBodies, snapshots } = installBackend()
    const current = snapshots.get(CANVAS_ID) as CanvasSnapshotDTO
    snapshots.set(CANVAS_ID, {
      ...current,
      nodes: [{
        id: NODE_A,
        canvasId: CANVAS_ID,
        name: 'note',
        transform: { x: 20, y: 30, width: 320, height: 260 },
        groupId: null,
        resources: [{
          id: RESOURCE_ID,
          canvasId: CANVAS_ID,
          ownerNodeId: NODE_A,
          resourceIndex: 0,
          blobId: null,
          name: 'note.md',
          textContent: 'note',
          kind: 'TEXT',
          mediaType: 'text/markdown',
          sizeBytes: '4',
          width: null,
          height: null,
          durationMs: null,
          createdAt: '2026-08-10T00:00:00Z',
        }],
        function: null,
        run: null,
      }],
    })
    const user = userEvent.setup()
    renderCanvasPage()
    await user.click(await screen.findByRole('button', { name: /真实画布/ }))
    await screen.findByLabelText(/无限画布/)

    const flow = flowHarness.current as {
      nodes: Array<{ id: string; position: { x: number; y: number } }>
      onNodesChange: (changes: unknown[]) => void
      onNodeDragStop: () => void
    }
    act(() => {
      flow.onNodesChange([{
        type: 'position',
        id: NODE_A,
        position: { x: 120, y: 140 },
        dragging: true,
      }])
    })

    await waitFor(() => {
      const latest = flowHarness.current as typeof flow
      expect(latest.nodes.find((node) => node.id === NODE_A)?.position).toEqual({ x: 120, y: 140 })
    })
    expect(commandBodies.some((body) => body.commands[0]?.type === 'UPDATE_NODE_TRANSFORMS')).toBe(false)

    act(() => {
      (flowHarness.current as typeof flow).onNodeDragStop()
    })
    await waitFor(() => {
      expect(commandBodies.some((body) => body.commands[0]?.type === 'UPDATE_NODE_TRANSFORMS')).toBe(true)
    })
  })

  it('detaches a member dragged fully outside and excludes it from later Group moves', async () => {
    const { commandBodies, snapshots } = installBackend()
    const current = snapshots.get(CANVAS_ID) as CanvasSnapshotDTO
    snapshots.set(CANVAS_ID, {
      ...current,
      nodes: [{
        id: NODE_A,
        canvasId: CANVAS_ID,
        name: 'Audio',
        transform: { x: 20, y: 50, width: 320, height: 138 },
        groupId: GROUP_ID,
        resources: [{
          id: RESOURCE_ID,
          canvasId: CANVAS_ID,
          ownerNodeId: NODE_A,
          resourceIndex: 0,
          blobId: '00000000-0000-4000-8000-0000000000bb',
          name: 'audio.mp3',
          textContent: null,
          kind: 'AUDIO',
          mediaType: 'audio/mpeg',
          sizeBytes: '1024',
          width: null,
          height: null,
          durationMs: '65000',
          createdAt: '2026-08-10T00:00:00Z',
        }],
        function: null,
        run: null,
      }, {
        id: NODE_B,
        canvasId: CANVAS_ID,
        name: 'Image',
        transform: { x: 360, y: 80, width: 320, height: 246 },
        groupId: GROUP_ID,
        resources: [],
        function: null,
        run: null,
      }],
      groups: [{
        id: GROUP_ID,
        canvasId: CANVAS_ID,
        title: 'Frame',
        transform: { x: 0, y: 0, width: 700, height: 400 },
      }],
    })
    const user = userEvent.setup()
    renderCanvasPage()
    await user.click(await screen.findByRole('button', { name: /真实画布/ }))
    await screen.findByLabelText(/无限画布/)

    type FlowHarness = {
      nodes: Array<{
        id: string
        position: { x: number; y: number }
        data: {
          kind: 'resource' | 'group'
          node?: { groupId: string | null }
        }
      }>
      onNodesChange: (changes: unknown[]) => void
      onNodeDragStop: () => void
    }
    act(() => {
      const flow = flowHarness.current as FlowHarness
      flow.onNodesChange([{
        type: 'position',
        id: NODE_A,
        // 节点左边界恰好贴住 Group 右边界，没有正面积交集。
        position: { x: 700, y: 100 },
        dragging: true,
      }])
      flow.onNodeDragStop()
    })

    await waitFor(() => {
      const detachBatch = commandBodies.find((body) => (
        body.commands.some((command) => command.type === 'UNGROUP')
      ))
      expect(detachBatch?.commands).toEqual([
        {
          type: 'UPDATE_NODE_TRANSFORMS',
          updates: [{
            nodeId: NODE_A,
            transform: { x: 700, y: 100, width: 320, height: 138 },
          }],
        },
        {
          type: 'UNGROUP',
          groupId: GROUP_ID,
          memberNodeIds: [NODE_A],
        },
      ])
    })
    await waitFor(() => {
      const flow = flowHarness.current as FlowHarness
      const detached = flow.nodes.find((node) => node.id === NODE_A)
      const remaining = flow.nodes.find((node) => node.id === NODE_B)
      expect(detached?.data.node?.groupId).toBeNull()
      expect(remaining?.data.node?.groupId).toBe(GROUP_ID)
      expect(flow.nodes.some((node) => node.id === `group:${GROUP_ID}`)).toBe(true)
    })

    act(() => {
      const flow = flowHarness.current as FlowHarness
      flow.onNodesChange([{
        type: 'position',
        id: `group:${GROUP_ID}`,
        position: { x: 100, y: 50 },
        dragging: true,
      }])
    })
    await waitFor(() => {
      const flow = flowHarness.current as FlowHarness
      expect(flow.nodes.find((node) => node.id === NODE_A)?.position).toEqual({ x: 700, y: 100 })
      expect(flow.nodes.find((node) => node.id === NODE_B)?.position).toEqual({ x: 460, y: 130 })
    })
    act(() => {
      ;(flowHarness.current as FlowHarness).onNodeDragStop()
    })
    await waitFor(() => {
      expect(commandBodies.some((body) => body.commands.some((command) => (
        command.type === 'MOVE_GROUP'
        && command.groupId === GROUP_ID
        && command.x === 100
        && command.y === 50
      )))).toBe(true)
    })
  })

  it('keeps graph commands available without rendering a selection toolbar', async () => {
    // Selection remains useful for link deletion and context-menu grouping without extra chrome.
    const { commandBodies, snapshots } = installBackend()
    const current = snapshots.get(CANVAS_ID) as CanvasSnapshotDTO
    snapshots.set(CANVAS_ID, {
      ...current,
      nodes: [{
        id: NODE_A,
        canvasId: CANVAS_ID,
        name: 'Image',
        transform: { x: 20, y: 30, width: 320, height: 260 },
        groupId: null,
        resources: [{
          id: RESOURCE_ID,
          canvasId: CANVAS_ID,
          ownerNodeId: NODE_A,
          resourceIndex: 0,
          blobId: '00000000-0000-4000-8000-0000000000bb',
          name: 'image.png',
          textContent: null,
          kind: 'IMAGE',
          mediaType: 'image/png',
          sizeBytes: '3',
          width: null,
          height: null,
          durationMs: null,
          createdAt: '2026-08-10T00:00:00Z',
        }],
        function: null,
        run: null,
      }, {
        id: NODE_B,
        canvasId: CANVAS_ID,
        name: 'Generator',
        transform: { x: 400, y: 30, width: 320, height: 260 },
        groupId: null,
        resources: [],
        function: {
          modelKey: 'fake-image',
          configJson: JSON.stringify({
            prompt: { segments: [{ type: 'TEXT', text: 'generate' }] },
            parameters: { ratio: 'AUTO' },
          }),
        },
        run: null,
      }],
      // A real group proves Delete cannot bypass the group context-menu confirmation.
      groups: [{
        id: GROUP_ID,
        canvasId: CANVAS_ID,
        title: 'Frame',
        transform: { x: 800, y: 30, width: 400, height: 300 },
      }],
    })
    const user = userEvent.setup()
    renderCanvasPage()
    await user.click(await screen.findByRole('button', { name: /真实画布/ }))
    await screen.findByLabelText(/无限画布/)
    const flow = flowHarness.current as {
      isValidConnection: (connection: { source: string; target: string }) => boolean
      onConnect: (connection: { source: string; target: string }) => void
      onSelectionChange: (params: {
        nodes: Array<{ id: string }>
        edges: Array<{ source: string; target: string }>
      }) => void
    }
    expect(flow.isValidConnection({ source: NODE_A, target: NODE_B })).toBe(true)
    expect(flow.isValidConnection({ source: NODE_B, target: NODE_A })).toBe(false)
    act(() => flow.onConnect({ source: NODE_A, target: NODE_B }))
    await waitFor(() => expect(commandBodies.some((body) => (
      body.commands[0]?.type === 'CREATE_LINK'
    ))).toBe(true))

    act(() => flow.onSelectionChange({
      nodes: [],
      edges: [{ source: NODE_A, target: NODE_B }],
    }))
    expect(screen.queryByRole('toolbar', { name: '选区操作' })).not.toBeInTheDocument()
    fireEvent.keyDown(window, { key: 'Delete' })
    await waitFor(() => expect(commandBodies.some((body) => (
      body.commands[0]?.type === 'DELETE_LINK'
    ))).toBe(true))

    // 框选右键：菜单提供「打组」并调用现有 createGroup（AddMenu 不再有 Group）。
    act(() => flow.onSelectionChange({ nodes: [{ id: NODE_A }, { id: NODE_B }], edges: [] }))
    act(() => {
      ;(flow as typeof flow & {
        onSelectionContextMenu: (
          event: { clientX: number; clientY: number; preventDefault: () => void },
          nodes: Array<{ id: string }>,
        ) => void
      }).onSelectionContextMenu(
        { clientX: 200, clientY: 200, preventDefault: vi.fn() },
        [{ id: NODE_A }, { id: NODE_B }],
      )
    })
    const groupMenu = await screen.findByRole('menu', { name: '画布节点操作' })
    await user.click(within(groupMenu).getByRole('menuitem', { name: '打组' }))
    await waitFor(() => expect(commandBodies.some((body) => (
      body.commands[0]?.type === 'CREATE_GROUP'
    ))).toBe(true))
    expect(screen.queryByRole('menu', { name: '画布节点操作' })).not.toBeInTheDocument()
    // 节点/Group 删除必须经右键确认；Delete 不得绕过二次确认。
    act(() => flow.onSelectionChange({ nodes: [{ id: `group:${GROUP_ID}` }], edges: [] }))
    const deleteGroupCount = commandBodies.filter((body) => (
      body.commands[0]?.type === 'DELETE_GROUP'
    )).length
    fireEvent.keyDown(window, { key: 'Delete' })
    expect(commandBodies.filter((body) => (
      body.commands[0]?.type === 'DELETE_GROUP'
    ))).toHaveLength(deleteGroupCount)

    act(() => {
      flow.onSelectionChange({ nodes: [{ id: NODE_A }, { id: NODE_B }], edges: [] })
      ;(flow as typeof flow & {
        onNodeClick: (event: { shiftKey: boolean }, node: { id: string }) => void
      }).onNodeClick({ shiftKey: true }, { id: NODE_B })
    })
    await waitFor(() => {
      const latest = flowHarness.current as {
        nodes: Array<{ id: string; selected?: boolean }>
      }
      expect(latest.nodes.filter((node) => node.selected).map((node) => node.id)).toEqual([
        NODE_A,
        NODE_B,
      ])
    })
  })

  it('defers every canvas global shortcut to a blocking overlay', async () => {
    const { commandBodies, snapshots } = installBackend()
    const current = snapshots.get(CANVAS_ID) as CanvasSnapshotDTO
    snapshots.set(CANVAS_ID, {
      ...current,
      nodes: [{
        id: NODE_A,
        canvasId: CANVAS_ID,
        name: 'Image',
        transform: { x: 20, y: 30, width: 320, height: 260 },
        groupId: null,
        resources: [{
          id: RESOURCE_ID,
          canvasId: CANVAS_ID,
          ownerNodeId: NODE_A,
          resourceIndex: 0,
          blobId: '00000000-0000-4000-8000-0000000000bb',
          name: 'image.png',
          textContent: null,
          kind: 'IMAGE',
          mediaType: 'image/png',
          sizeBytes: '3',
          width: null,
          height: null,
          durationMs: null,
          createdAt: '2026-08-10T00:00:00Z',
        }],
        function: null,
        run: null,
      }, {
        id: NODE_B,
        canvasId: CANVAS_ID,
        name: 'Generator',
        transform: { x: 400, y: 30, width: 320, height: 260 },
        groupId: null,
        resources: [],
        function: {
          modelKey: 'fake-image',
          configJson: JSON.stringify({
            prompt: { segments: [{ type: 'TEXT', text: 'generate' }] },
            parameters: { ratio: 'AUTO' },
          }),
        },
        run: null,
      }],
    })
    const user = userEvent.setup()
    renderCanvasPage()
    await user.click(await screen.findByRole('button', { name: /真实画布/ }))
    await screen.findByLabelText(/无限画布/)
    const flow = flowHarness.current as {
      onConnect: (connection: { source: string; target: string }) => void
    }
    act(() => flow.onConnect({ source: NODE_A, target: NODE_B }))
    await waitFor(() => expect(commandBodies.some((body) => (
      body.commands[0]?.type === 'CREATE_LINK'
    ))).toBe(true))
    act(() => flow.onSelectionChange({
      nodes: [],
      edges: [{ source: NODE_A, target: NODE_B }],
    }))

    const overlay = document.createElement('div')
    overlay.className = 'modal-backdrop'
    document.body.appendChild(overlay)
    try {
      const before = commandBodies.filter((body) => (
        body.commands[0]?.type === 'DELETE_LINK'
      )).length
      // blocking overlay 存在时所有全局快捷键让路：Delete、T、Escape 全部不生效。
      fireEvent.keyDown(window, { key: 'Delete' })
      fireEvent.keyDown(window, { key: 't' })
      fireEvent.keyDown(window, { key: 'Escape' })
      expect(commandBodies.filter((body) => (
        body.commands[0]?.type === 'DELETE_LINK'
      ))).toHaveLength(before)
      expect(commandBodies.some((body) => (
        body.commands[0]?.type === 'CREATE_RESOURCE_NODE'
      ))).toBe(false)
      // Escape 被让路：link 选择未被清空（overlay 移除后 Delete 仍能删除该 link）。
      expect(commandBodies.filter((body) => (
        body.commands[0]?.type === 'DELETE_LINK'
      ))).toHaveLength(before)
    } finally {
      overlay.remove()
    }
    fireEvent.keyDown(window, { key: 'Delete' })
    await waitFor(() => expect(commandBodies.some((body) => (
      body.commands[0]?.type === 'DELETE_LINK'
    ))).toBe(true))
  })

  it('renames, ungroups, and deletes a group from its right-click menu', async () => {
    const { commandBodies, snapshots } = installBackend()
    const current = snapshots.get(CANVAS_ID) as CanvasSnapshotDTO
    snapshots.set(CANVAS_ID, {
      ...current,
      nodes: [{
        id: NODE_A,
        canvasId: CANVAS_ID,
        name: 'Image',
        transform: { x: 20, y: 30, width: 320, height: 260 },
        groupId: GROUP_ID,
        resources: [{
          id: RESOURCE_ID,
          canvasId: CANVAS_ID,
          ownerNodeId: NODE_A,
          resourceIndex: 0,
          blobId: '00000000-0000-4000-8000-0000000000bb',
          name: 'image.png',
          textContent: null,
          kind: 'IMAGE',
          mediaType: 'image/png',
          sizeBytes: '3',
          width: null,
          height: null,
          durationMs: null,
          createdAt: '2026-08-10T00:00:00Z',
        }],
        function: null,
        run: null,
      }, {
        id: NODE_B,
        canvasId: CANVAS_ID,
        name: 'Generator',
        transform: { x: 400, y: 30, width: 320, height: 260 },
        groupId: GROUP_ID,
        resources: [],
        function: {
          modelKey: 'fake-image',
          configJson: JSON.stringify({
            prompt: { segments: [{ type: 'TEXT', text: 'generate' }] },
            parameters: { ratio: 'AUTO' },
          }),
        },
        run: null,
      }],
      groups: [{
        id: GROUP_ID,
        canvasId: CANVAS_ID,
        title: 'Frame',
        transform: { x: 800, y: 30, width: 400, height: 300 },
      }],
    })
    const user = userEvent.setup()
    renderCanvasPage()
    await user.click(await screen.findByRole('button', { name: /真实画布/ }))
    await screen.findByLabelText(/无限画布/)

    act(() => {
      ;(flowHarness.current as {
        onNodeContextMenu: (
          event: { clientX: number; clientY: number; preventDefault: () => void },
          node: { id: string; selected: boolean },
        ) => void
      }).onNodeContextMenu(
        { clientX: 200, clientY: 200, preventDefault: vi.fn() },
        { id: NODE_A, selected: false },
      )
    })
    const groupedNodeMenu = await screen.findByRole('menu', { name: '画布节点操作' })
    expect(within(groupedNodeMenu).queryByRole('menuitem', { name: '打组' })).not.toBeInTheDocument()
    fireEvent.keyDown(window, { key: 'Escape' })

    const rightClickGroup = () => act(() => {
      ;(flowHarness.current as {
        onNodeContextMenu: (
          event: { clientX: number; clientY: number; preventDefault: () => void },
          node: { id: string; selected: boolean },
        ) => void
      }).onNodeContextMenu(
        { clientX: 120, clientY: 140, preventDefault: vi.fn() },
        { id: `group:${GROUP_ID}`, selected: false },
      )
    })
    const openGroupMenu = async () => {
      rightClickGroup()
      return screen.findByRole('menu', { name: '画布节点操作' })
    }

    // Rename 通过菜单内编辑持久化 RENAME_GROUP。
    let menu = await openGroupMenu()
    await user.click(within(menu).getByRole('menuitem', { name: '重命名' }))
    const renameInput = within(menu).getByRole('textbox', { name: '新名称' })
    await user.clear(renameInput)
    await user.type(renameInput, '重命名分组')
    await user.click(within(menu).getByRole('button', { name: '保存' }))
    await waitFor(() => expect(commandBodies.some((body) => (
      body.commands[0]?.type === 'RENAME_GROUP'
    ))).toBe(true))
    expect(commandBodies.find((body) => (
      body.commands[0]?.type === 'RENAME_GROUP'
    ))?.commands).toEqual([{ type: 'RENAME_GROUP', groupId: GROUP_ID, title: '重命名分组' }])
    expect(screen.queryByRole('menu', { name: '画布节点操作' })).not.toBeInTheDocument()

    // Delete 必须先确认：菜单内 alertdialog，确认后才发 DELETE_GROUP。
    // 重命名已回显，组标题为「重命名分组」。
    menu = await openGroupMenu()
    await user.click(within(menu).getByRole('menuitem', { name: '删除' }))
    const confirm = await within(menu).findByRole('alertdialog', { name: '删除分组「重命名分组」？' })
    expect(commandBodies.some((body) => (
      body.commands[0]?.type === 'DELETE_GROUP'
    ))).toBe(false)
    await user.click(within(confirm).getByRole('button', { name: '确认删除' }))
    await waitFor(() => expect(commandBodies.some((body) => (
      body.commands[0]?.type === 'DELETE_GROUP'
    ))).toBe(true))
    expect(commandBodies.find((body) => (
      body.commands[0]?.type === 'DELETE_GROUP'
    ))?.commands).toEqual([{ type: 'DELETE_GROUP', groupId: GROUP_ID }])

    // Ungroup：重新框选打组后解组，只发 UNGROUP（服务端同时删除边界）。
    act(() => {
      ;(flowHarness.current as {
        onSelectionChange: (params: {
          nodes: Array<{ id: string }>
          edges: Array<{ source: string; target: string }>
        }) => void
      }).onSelectionChange({ nodes: [{ id: NODE_A }, { id: NODE_B }], edges: [] })
      ;(flowHarness.current as {
        onSelectionContextMenu: (
          event: { clientX: number; clientY: number; preventDefault: () => void },
          nodes: Array<{ id: string }>,
        ) => void
      }).onSelectionContextMenu(
        { clientX: 200, clientY: 200, preventDefault: vi.fn() },
        [{ id: NODE_A }, { id: NODE_B }],
      )
    })
    const regroup = await screen.findByRole('menu', { name: '画布节点操作' })
    await user.click(within(regroup).getByRole('menuitem', { name: '打组' }))
    await waitFor(() => expect(commandBodies.filter((body) => (
      body.commands[0]?.type === 'CREATE_GROUP'
    ))).toHaveLength(1))
    const createdGroup = commandBodies
      .flatMap((body) => body.commands)
      .filter((command) => command.type === 'CREATE_GROUP')[0]
    expect(createdGroup?.type).toBe('CREATE_GROUP')
    expect(createdGroup?.memberNodeIds).toEqual([NODE_A, NODE_B])
    const newGroupId = createdGroup?.groupId as string

    act(() => {
      ;(flowHarness.current as {
        onNodeContextMenu: (
          event: { clientX: number; clientY: number; preventDefault: () => void },
          node: { id: string; selected: boolean },
        ) => void
      }).onNodeContextMenu(
        { clientX: 120, clientY: 140, preventDefault: vi.fn() },
        { id: `group:${newGroupId}`, selected: false },
      )
    })
    menu = await screen.findByRole('menu', { name: '画布节点操作' })
    await user.click(within(menu).getByRole('menuitem', { name: '解组' }))
    await waitFor(() => expect(commandBodies.some((body) => (
      body.commands[0]?.type === 'UNGROUP'
    ))).toBe(true))
    const ungroupBody = commandBodies.find((body) => (
      body.commands[0]?.type === 'UNGROUP'
    ))
    expect(ungroupBody?.commands).toEqual([{
      type: 'UNGROUP',
      groupId: newGroupId,
      memberNodeIds: [NODE_A, NODE_B],
    }])
    expect(ungroupBody?.commands.some((command) => command.type === 'DELETE_GROUP')).toBe(false)
  })

  it('groups a single ungrouped resource node from its right-click menu', async () => {
    const { commandBodies, snapshots } = installBackend()
    const current = snapshots.get(CANVAS_ID) as CanvasSnapshotDTO
    snapshots.set(CANVAS_ID, {
      ...current,
      nodes: [{
        id: NODE_A,
        canvasId: CANVAS_ID,
        name: 'Image',
        transform: { x: 20, y: 30, width: 320, height: 260 },
        groupId: null,
        resources: [{
          id: RESOURCE_ID,
          canvasId: CANVAS_ID,
          ownerNodeId: NODE_A,
          resourceIndex: 0,
          blobId: '00000000-0000-4000-8000-0000000000bb',
          name: 'image.png',
          textContent: null,
          kind: 'IMAGE',
          mediaType: 'image/png',
          sizeBytes: '3',
          width: null,
          height: null,
          durationMs: null,
          createdAt: '2026-08-10T00:00:00Z',
        }],
        function: null,
        run: null,
      }],
    })
    const user = userEvent.setup()
    renderCanvasPage()
    await user.click(await screen.findByRole('button', { name: /真实画布/ }))
    await screen.findByLabelText(/无限画布/)

    await user.click(screen.getByRole('button', { name: '添加资源或 Function' }))
    expect(within(screen.getByRole('menu')).queryByRole('menuitem', { name: /分组/ })).not.toBeInTheDocument()
    fireEvent.keyDown(window, { key: 'Escape' })

    act(() => {
      ;(flowHarness.current as {
        onNodeContextMenu: (
          event: { clientX: number; clientY: number; preventDefault: () => void },
          node: { id: string; selected: boolean },
        ) => void
      }).onNodeContextMenu(
        { clientX: 200, clientY: 200, preventDefault: vi.fn() },
        { id: NODE_A, selected: false },
      )
    })
    const menu = await screen.findByRole('menu', { name: '画布节点操作' })
    expect(within(menu).getByRole('menuitem', { name: '打组' })).toBeInTheDocument()
    await user.click(within(menu).getByRole('menuitem', { name: '打组' }))

    await waitFor(() => expect(commandBodies.filter((body) => (
      body.commands[0]?.type === 'CREATE_GROUP'
    ))).toHaveLength(1))
    expect(commandBodies.find((body) => body.commands[0]?.type === 'CREATE_GROUP')?.commands).toEqual([
      expect.objectContaining({
        type: 'CREATE_GROUP',
        memberNodeIds: [NODE_A],
      }),
    ])
  })

  it('synchronizes selection state before grouping from a selection context menu', async () => {
    const { commandBodies, snapshots } = installBackend()
    const current = snapshots.get(CANVAS_ID) as CanvasSnapshotDTO
    snapshots.set(CANVAS_ID, {
      ...current,
      nodes: [{
        id: NODE_A,
        canvasId: CANVAS_ID,
        name: 'Image',
        transform: { x: 20, y: 30, width: 320, height: 260 },
        groupId: null,
        resources: [],
        function: null,
        run: null,
      }, {
        id: NODE_B,
        canvasId: CANVAS_ID,
        name: 'Image 2',
        transform: { x: 400, y: 30, width: 320, height: 260 },
        groupId: null,
        resources: [],
        function: null,
        run: null,
      }],
    })
    const user = userEvent.setup()
    renderCanvasPage()
    await user.click(await screen.findByRole('button', { name: /真实画布/ }))
    await screen.findByLabelText(/无限画布/)

    act(() => {
      ;(flowHarness.current as {
        onSelectionContextMenu: (
          event: { clientX: number; clientY: number; preventDefault: () => void },
          nodes: Array<{ id: string }>,
        ) => void
      }).onSelectionContextMenu(
        { clientX: 200, clientY: 200, preventDefault: vi.fn() },
        [{ id: NODE_A }, { id: NODE_B }],
      )
    })
    const menu = await screen.findByRole('menu', { name: '画布节点操作' })
    expect(within(menu).getByRole('menuitem', { name: '打组' })).toBeInTheDocument()
    await user.click(within(menu).getByRole('menuitem', { name: '打组' }))

    await waitFor(() => expect(commandBodies.filter((body) => (
      body.commands[0]?.type === 'CREATE_GROUP'
    ))).toHaveLength(1))
    expect(commandBodies.find((body) => body.commands[0]?.type === 'CREATE_GROUP')?.commands).toEqual([
      expect.objectContaining({
        type: 'CREATE_GROUP',
        memberNodeIds: [NODE_A, NODE_B],
      }),
    ])
  })

  it('closes the context menu on Escape, outside pointer, pane click, and viewport move', async () => {
    const { snapshots } = installBackend()
    const current = snapshots.get(CANVAS_ID) as CanvasSnapshotDTO
    snapshots.set(CANVAS_ID, {
      ...current,
      nodes: [{
        id: NODE_A,
        canvasId: CANVAS_ID,
        name: 'Image',
        transform: { x: 20, y: 30, width: 320, height: 260 },
        groupId: null,
        resources: [],
        function: null,
        run: null,
      }],
    })
    const user = userEvent.setup()
    renderCanvasPage()
    await user.click(await screen.findByRole('button', { name: /真实画布/ }))
    await screen.findByLabelText(/无限画布/)

    const openMenu = () => act(() => {
      ;(flowHarness.current as {
        onNodeContextMenu: (
          event: { clientX: number; clientY: number; preventDefault: () => void },
          node: { id: string; selected: boolean },
        ) => void
      }).onNodeContextMenu(
        { clientX: 300, clientY: 300, preventDefault: vi.fn() },
        { id: NODE_A, selected: false },
      )
    })

    openMenu()
    expect(await screen.findByRole('menu', { name: '画布节点操作' })).toBeInTheDocument()
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(screen.queryByRole('menu', { name: '画布节点操作' })).not.toBeInTheDocument()

    openMenu()
    await screen.findByRole('menu', { name: '画布节点操作' })
    fireEvent.pointerDown(document.body)
    expect(screen.queryByRole('menu', { name: '画布节点操作' })).not.toBeInTheDocument()

    openMenu()
    await screen.findByRole('menu', { name: '画布节点操作' })
    act(() => {
      ;(flowHarness.current as {
        onMove: (
          event: unknown,
          viewport: { x: number; y: number; zoom: number },
        ) => void
      }).onMove(null, { x: 12, y: 18, zoom: 0.74 })
    })
    expect(screen.queryByRole('menu', { name: '画布节点操作' })).not.toBeInTheDocument()

    openMenu()
    await screen.findByRole('menu', { name: '画布节点操作' })
    act(() => {
      ;(flowHarness.current as {
        onPaneClick: () => void
      }).onPaneClick()
    })
    expect(screen.queryByRole('menu', { name: '画布节点操作' })).not.toBeInTheDocument()
  })

  it('consumes Escape on the stage surface and keeps focus on the stage instead of the agent composer', async () => {
    const { snapshots, commandBodies } = installBackend()
    const current = snapshots.get(CANVAS_ID) as CanvasSnapshotDTO
    snapshots.set(CANVAS_ID, {
      ...current,
      nodes: [{
        id: NODE_A,
        canvasId: CANVAS_ID,
        name: 'Image',
        transform: { x: 20, y: 30, width: 320, height: 260 },
        groupId: null,
        resources: [],
        function: null,
        run: null,
      }],
    })
    const user = userEvent.setup()
    renderCanvasPage()
    await user.click(await screen.findByRole('button', { name: /真实画布/ }))
    await screen.findByLabelText(/无限画布/)

    // 打开 Agent 面板：ThreadComposer 挂载且 focusOnEscape=true（存在异步焦点恢复）。
    await user.click(screen.getByRole('button', { name: '切换对话面板' }))
    expect(await screen.findByLabelText('给 AI 发送消息')).toBeInTheDocument()

    // 选中节点并聚焦 stage。
    act(() => {
      ;(flowHarness.current as {
        onSelectionChange: (params: { nodes: Array<{ id: string }>; edges: unknown[] }) => void
      }).onSelectionChange({ nodes: [{ id: NODE_A }], edges: [] })
    })
    const stage = screen.getByLabelText(/无限画布/)
    stage.focus()
    expect(document.activeElement).toBe(stage)

    await user.keyboard('{Escape}')

    // Canvas 消费：清选 + 焦点留在 stage；Agent panel 是侧栏而非 overlay，保持打开。
    expect(document.activeElement).toBe(stage)
    expect(document.querySelector('.agent-panel')).not.toBeNull()
    expect((flowHarness.current as { nodes: Array<{ selected?: boolean }> }).nodes.some((node) => node.selected)).toBe(false)
    // ThreadComposer 的焦点恢复是异步重试（setTimeout）；等待其窗口期后焦点仍必须在 stage。
    await new Promise((resolve) => setTimeout(resolve, 120))
    expect(document.activeElement).toBe(stage)

    // 选区已清空：随后 Delete 不再产生删除命令。
    fireEvent.keyDown(window, { key: 'Delete' })
    expect(commandBodies.some((body) => body.commands[0]?.type === 'DELETE_NODE')).toBe(false)
  })

  it.each([
    { name: 'body', dispatchTarget: () => document.body },
    { name: 'no concrete focus', dispatchTarget: () => window },
  ])('treats Escape from $name as canvas surface without collapsing the agent panel', async ({ dispatchTarget }) => {
    const { snapshots } = installBackend()
    const current = snapshots.get(CANVAS_ID) as CanvasSnapshotDTO
    snapshots.set(CANVAS_ID, {
      ...current,
      nodes: [{
        id: NODE_A,
        canvasId: CANVAS_ID,
        name: 'Image',
        transform: { x: 20, y: 30, width: 320, height: 260 },
        groupId: null,
        resources: [],
        function: null,
        run: null,
      }],
    })
    const user = userEvent.setup()
    renderCanvasPage()
    await user.click(await screen.findByRole('button', { name: /真实画布/ }))
    await screen.findByLabelText(/无限画布/)
    await user.click(screen.getByRole('button', { name: '切换对话面板' }))
    expect(await screen.findByLabelText('给 AI 发送消息')).toBeInTheDocument()

    act(() => {
      ;(flowHarness.current as {
        onNodeContextMenu: (
          event: { clientX: number; clientY: number; preventDefault: () => void },
          node: { id: string; selected: boolean },
        ) => void
      }).onNodeContextMenu(
        { clientX: 220, clientY: 180, preventDefault: vi.fn() },
        { id: NODE_A, selected: true },
      )
    })
    expect(await screen.findByRole('menu', { name: '画布节点操作' })).toBeInTheDocument()
    act(() => {
      ;(flowHarness.current as {
        onSelectionChange: (params: { nodes: Array<{ id: string }>; edges: unknown[] }) => void
      }).onSelectionChange({ nodes: [{ id: NODE_A }], edges: [] })
    })

    if (dispatchTarget() === document.body) {
      document.body.focus()
    } else if (document.activeElement instanceof HTMLElement) {
      document.activeElement.blur()
    }
    fireEvent.keyDown(dispatchTarget(), { key: 'Escape' })

    expect(screen.queryByRole('menu', { name: '画布节点操作' })).not.toBeInTheDocument()
    expect(document.querySelector('.agent-panel')).not.toBeNull()
    expect((flowHarness.current as { nodes: Array<{ selected?: boolean }> }).nodes.some((node) => node.selected)).toBe(false)
    expect(document.activeElement).toBe(screen.getByLabelText(/无限画布/))
  })

  it('leaves Escape inside the agent panel to the composer and keeps the canvas selection', async () => {
    const { snapshots } = installBackend()
    const current = snapshots.get(CANVAS_ID) as CanvasSnapshotDTO
    snapshots.set(CANVAS_ID, {
      ...current,
      nodes: [{
        id: NODE_A,
        canvasId: CANVAS_ID,
        name: 'Image',
        transform: { x: 20, y: 30, width: 320, height: 260 },
        groupId: null,
        resources: [],
        function: null,
        run: null,
      }],
    })
    const user = userEvent.setup()
    renderCanvasPage()
    await user.click(await screen.findByRole('button', { name: /真实画布/ }))
    await screen.findByLabelText(/无限画布/)
    await user.click(screen.getByRole('button', { name: '切换对话面板' }))
    const composer = await screen.findByLabelText('给 AI 发送消息')

    act(() => {
      ;(flowHarness.current as {
        onSelectionChange: (params: { nodes: Array<{ id: string }>; edges: unknown[] }) => void
      }).onSelectionChange({ nodes: [{ id: NODE_A }], edges: [] })
    })
    await user.click(composer)
    await user.keyboard('{Escape}')

    // Canvas 全局 handler 不抢：panel 保持打开、选区保持、焦点留在 composer。
    expect(document.querySelector('.agent-panel')).not.toBeNull()
    expect(document.activeElement).toBe(composer)
    expect((flowHarness.current as { nodes: Array<{ selected?: boolean }> }).nodes.some((node) => node.selected)).toBe(true)
  })

  it('offers only applicable resource actions and confirms node deletion in the menu', async () => {
    const { commandBodies, snapshots } = installBackend()
    const current = snapshots.get(CANVAS_ID) as CanvasSnapshotDTO
    const textResourceId = 'eeeeeeee-0000-4000-8000-0000000000cc'
    const readyFunctionId = 'f0e1d2c3-b4a5-4678-89ab-cdef01234567'
    snapshots.set(CANVAS_ID, {
      ...current,
      nodes: [{
        id: NODE_A,
        canvasId: CANVAS_ID,
        name: 'Note',
        transform: { x: 20, y: 30, width: 320, height: 260 },
        groupId: null,
        resources: [{
          id: textResourceId,
          canvasId: CANVAS_ID,
          ownerNodeId: NODE_A,
          resourceIndex: 0,
          blobId: null,
          name: 'note.md',
          textContent: 'hello',
          kind: 'TEXT',
          mediaType: 'text/markdown',
          sizeBytes: '5',
          width: null,
          height: null,
          durationMs: null,
          createdAt: '2026-08-10T00:00:00Z',
        }],
        function: null,
        run: null,
      }, {
        id: NODE_B,
        canvasId: CANVAS_ID,
        name: 'Generator',
        transform: { x: 400, y: 30, width: 320, height: 260 },
        groupId: null,
        resources: [],
        function: {
          modelKey: 'fake-image',
          configJson: JSON.stringify({
            prompt: { segments: [{ type: 'TEXT', text: 'generate' }] },
            parameters: { ratio: 'AUTO' },
          }),
        },
        run: {
          nodeId: NODE_B,
          requestId: 'f1f2f3f4-0000-4000-8000-000000000001',
          status: 'RUNNING',
          stage: 'RUNNING',
          error: null,
          updatedAt: '2026-08-10T00:00:00Z',
        },
      }, {
        id: NODE_C,
        canvasId: CANVAS_ID,
        name: 'Track',
        transform: { x: 780, y: 30, width: 320, height: 260 },
        groupId: null,
        resources: [{
          id: 'a9b8c7d6-0000-4000-8000-0000000000dd',
          canvasId: CANVAS_ID,
          ownerNodeId: NODE_C,
          resourceIndex: 0,
          blobId: '00000000-0000-4000-8000-0000000000dd',
          name: 'track.mp3',
          textContent: null,
          kind: 'AUDIO',
          mediaType: 'audio/mpeg',
          sizeBytes: '9',
          width: null,
          height: null,
          durationMs: '65000',
          createdAt: '2026-08-10T00:00:00Z',
        }],
        function: null,
        run: null,
      }, {
        id: readyFunctionId,
        canvasId: CANVAS_ID,
        name: 'Ready generator',
        transform: { x: 1160, y: 30, width: 320, height: 260 },
        groupId: null,
        resources: [],
        function: {
          modelKey: 'fake-image',
          configJson: JSON.stringify({
            prompt: { segments: [{ type: 'TEXT', text: 'generate' }] },
            parameters: { ratio: 'AUTO' },
          }),
        },
        run: null,
      }],
    })
    const user = userEvent.setup()
    renderCanvasPage()
    await user.click(await screen.findByRole('button', { name: /真实画布/ }))
    await screen.findByLabelText(/无限画布/)

    const rightClickNode = (id: string, selected = false) => act(() => {
      ;(flowHarness.current as {
        onNodeContextMenu: (
          event: { clientX: number; clientY: number; preventDefault: () => void },
          node: { id: string; selected: boolean },
        ) => void
      }).onNodeContextMenu(
        { clientX: 200, clientY: 200, preventDefault: vi.fn() },
        { id, selected },
      )
    })

    // TEXT 节点：只有重命名/编辑文本/删除。
    rightClickNode(NODE_A)
    let menu = await screen.findByRole('menu', { name: '画布节点操作' })
    expect(within(menu).getByRole('menuitem', { name: '重命名' })).toBeInTheDocument()
    expect(within(menu).getByRole('menuitem', { name: '编辑文本' })).toBeInTheDocument()
    expect(within(menu).queryByRole('menuitem', { name: '运行' })).not.toBeInTheDocument()
    expect(within(menu).queryByRole('menuitem', { name: '打开原件' })).not.toBeInTheDocument()
    expect(within(menu).queryByRole('menuitem', { name: '下载' })).not.toBeInTheDocument()
    fireEvent.keyDown(window, { key: 'Escape' })

    // 未运行 Function：运行只经右键菜单。
    rightClickNode(readyFunctionId)
    menu = await screen.findByRole('menu', { name: '画布节点操作' })
    expect(within(menu).getByRole('menuitem', { name: '运行' })).toBeInTheDocument()
    fireEvent.keyDown(window, { key: 'Escape' })

    // RUNNING Function：取消生成而非运行。
    rightClickNode(NODE_B)
    menu = await screen.findByRole('menu', { name: '画布节点操作' })
    expect(within(menu).getByRole('menuitem', { name: '取消生成' })).toBeInTheDocument()
    expect(within(menu).queryByRole('menuitem', { name: '运行' })).not.toBeInTheDocument()
    fireEvent.keyDown(window, { key: 'Escape' })

    // AUDIO 节点：无运行/编辑文本，原件操作只经右键菜单（播放器内不提供常驻下载）。
    rightClickNode(NODE_C)
    menu = await screen.findByRole('menu', { name: '画布节点操作' })
    expect(within(menu).getByRole('menuitem', { name: '打开原件' })).toBeInTheDocument()
    expect(within(menu).getByRole('menuitem', { name: '下载' })).toBeInTheDocument()
    expect(within(menu).queryByRole('menuitem', { name: '运行' })).not.toBeInTheDocument()
    expect(within(menu).queryByRole('menuitem', { name: '编辑文本' })).not.toBeInTheDocument()
    fireEvent.keyDown(window, { key: 'Escape' })

    // 删除必须有菜单内确认，且不立即执行。
    rightClickNode(NODE_A)
    menu = await screen.findByRole('menu', { name: '画布节点操作' })
    await user.click(within(menu).getByRole('menuitem', { name: '删除' }))
    const confirm = await within(menu).findByRole('alertdialog', { name: '删除「Note」？' })
    expect(commandBodies.some((body) => (
      body.commands[0]?.type === 'DELETE_NODE'
    ))).toBe(false)
    await user.click(within(confirm).getByRole('button', { name: '确认删除' }))
    await waitFor(() => expect(commandBodies.some((body) => (
      body.commands[0]?.type === 'DELETE_NODE'
    ))).toBe(true))
    expect(commandBodies.find((body) => (
      body.commands[0]?.type === 'DELETE_NODE'
    ))?.commands).toEqual([{ type: 'DELETE_NODE', nodeId: NODE_A }])
  })

  it('opens and downloads originals through the right-click menu after signing', async () => {
    const { snapshots } = installBackend()
    const current = snapshots.get(CANVAS_ID) as CanvasSnapshotDTO
    snapshots.set(CANVAS_ID, {
      ...current,
      nodes: [{
        id: NODE_A,
        canvasId: CANVAS_ID,
        name: 'Image',
        transform: { x: 20, y: 30, width: 320, height: 260 },
        groupId: null,
        resources: [{
          id: RESOURCE_ID,
          canvasId: CANVAS_ID,
          ownerNodeId: NODE_A,
          resourceIndex: 0,
          blobId: '00000000-0000-4000-8000-0000000000bb',
          name: 'image.png',
          textContent: null,
          kind: 'IMAGE',
          mediaType: 'image/png',
          sizeBytes: '3',
          width: null,
          height: null,
          durationMs: null,
          createdAt: '2026-08-10T00:00:00Z',
        }],
        function: null,
        run: null,
      }],
    })
    const user = userEvent.setup()
    const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
    renderCanvasPage()
    await user.click(await screen.findByRole('button', { name: /真实画布/ }))
    await screen.findByLabelText(/无限画布/)

    act(() => {
      ;(flowHarness.current as {
        onNodeContextMenu: (
          event: { clientX: number; clientY: number; preventDefault: () => void },
          node: { id: string; selected: boolean },
        ) => void
      }).onNodeContextMenu(
        { clientX: 200, clientY: 200, preventDefault: vi.fn() },
        { id: NODE_A, selected: false },
      )
    })
    const menu = await screen.findByRole('menu', { name: '画布节点操作' })
    await user.click(within(menu).getByRole('menuitem', { name: '打开原件' }))
    // 签名完成后自动触发新窗口打开并关闭菜单。
    await waitFor(() => expect(clickSpy).toHaveBeenCalledOnce())
    const anchor = clickSpy.mock.instances[0] as HTMLAnchorElement
    expect(anchor.href).toBe('https://s3.example/original')
    expect(anchor.target).toBe('_blank')
    await waitFor(() => {
      expect(screen.queryByRole('menu', { name: '画布节点操作' })).not.toBeInTheDocument()
    })

    act(() => {
      ;(flowHarness.current as {
        onNodeContextMenu: (
          event: { clientX: number; clientY: number; preventDefault: () => void },
          node: { id: string; selected: boolean },
        ) => void
      }).onNodeContextMenu(
        { clientX: 200, clientY: 200, preventDefault: vi.fn() },
        { id: NODE_A, selected: false },
      )
    })
    const menu2 = await screen.findByRole('menu', { name: '画布节点操作' })
    clickSpy.mockClear()
    await user.click(within(menu2).getByRole('menuitem', { name: '下载' }))
    await waitFor(() => expect(clickSpy).toHaveBeenCalledOnce())
    const downloadAnchor = clickSpy.mock.instances[0] as HTMLAnchorElement
    expect(downloadAnchor.href).toBe('https://s3.example/original')
    expect(downloadAnchor.download).toBe('image.png')
    expect(vi.mocked(fetch).mock.calls.filter(([input, init]) => (
      String(input).endsWith('/download-url') && init?.method === 'POST'
    ))).toHaveLength(2)
  })

  it('edits text in a non-modal anchored panel without changing the node transform', async () => {
    const { commandBodies, snapshots } = installBackend()
    const current = snapshots.get(CANVAS_ID) as CanvasSnapshotDTO
    snapshots.set(CANVAS_ID, {
      ...current,
      nodes: [{
        id: NODE_A,
        canvasId: CANVAS_ID,
        name: 'Note',
        transform: { x: 100, y: 80, width: 320, height: 260 },
        groupId: null,
        resources: [{
          id: RESOURCE_ID,
          canvasId: CANVAS_ID,
          ownerNodeId: NODE_A,
          resourceIndex: 0,
          blobId: null,
          name: 'note.md',
          textContent: 'hello',
          kind: 'TEXT',
          mediaType: 'text/markdown',
          sizeBytes: '5',
          width: null,
          height: null,
          durationMs: null,
          createdAt: '2026-08-10T00:00:00Z',
        }],
        function: null,
        run: null,
      }],
    })
    const user = userEvent.setup()
    renderCanvasPage()
    await user.click(await screen.findByRole('button', { name: /真实画布/ }))
    await screen.findByLabelText(/无限画布/)

    act(() => {
      ;(flowHarness.current as {
        onSelectionChange: (params: {
          nodes: Array<{ id: string }>
          edges: Array<{ source: string; target: string }>
        }) => void
      }).onSelectionChange({ nodes: [{ id: NODE_A }], edges: [] })
    })
    // 聚焦文本节点直接打开非模态编辑面板；右键 Edit 仍是同一路径的显式入口。
    act(() => {
      ;(flowHarness.current as {
        onNodeClick: (event: { shiftKey: boolean }, node: {
          id: string
          data: {
            kind: 'resource'
            node: ResourceNode
          }
        }) => void
        nodes: Array<{
          id: string
          data: {
            kind: 'resource'
            node: ResourceNode
          }
        }>
      }).onNodeClick(
        { shiftKey: false },
        (flowHarness.current as {
          nodes: Array<{
            id: string
            data: {
              kind: 'resource'
              node: ResourceNode
            }
          }>
        }).nodes.find((node) => node.id === NODE_A)!,
      )
    })
    const input = await screen.findByRole('textbox', { name: 'Markdown 内容' })
    // 非模态：不存在 dialog/showModal，面板内联在 stage 中。
    expect(document.querySelector('dialog')).toBeNull()
    expect(screen.getByLabelText('编辑 Markdown 文本「Note」')).toBeInTheDocument()
    expect(screen.getByText('5 字')).toBeInTheDocument()
    await user.clear(input)
    await user.type(input, '# updated')
    expect(screen.getByText('9 字')).toBeInTheDocument()
    // 打开/编辑面板不改动节点 transform 尺寸。
    const flow = flowHarness.current as {
      nodes: Array<{ id: string; style: { width: number; height: number } }>
    }
    expect(flow.nodes.find((node) => node.id === NODE_A)?.style).toEqual({
      width: 320,
      height: 246,
    })
    await user.click(screen.getByRole('button', { name: '保存' }))
    await waitFor(() => expect(commandBodies.some((body) => (
      body.commands[0]?.type === 'UPDATE_TEXT_NODE'
    ))).toBe(true))
    expect(commandBodies.find((body) => (
      body.commands[0]?.type === 'UPDATE_TEXT_NODE'
    ))?.commands[0]).toEqual({ type: 'UPDATE_TEXT_NODE', nodeId: NODE_A, markdown: '# updated' })
    expect(screen.queryByLabelText('编辑 Markdown 文本「Note」')).not.toBeInTheDocument()
  })

  it('keeps the generation input focused while its debounced config snapshot is adopted', async () => {
    // A stable node key must survive UPDATE_FUNCTION echoes so continuous typing is not interrupted.
    const { commandBodies, snapshots } = installBackend()
    const current = snapshots.get(CANVAS_ID) as CanvasSnapshotDTO
    snapshots.set(CANVAS_ID, {
      ...current,
      nodes: [{
        id: NODE_B,
        canvasId: CANVAS_ID,
        name: 'Generator',
        transform: { x: 400, y: 30, width: 320, height: 260 },
        groupId: null,
        resources: [],
        function: {
          modelKey: 'fake-image',
          configJson: JSON.stringify({
            prompt: { segments: [{ type: 'TEXT', text: 'start' }] },
            parameters: { ratio: 'AUTO' },
          }),
        },
        run: null,
      }],
    })
    const user = userEvent.setup()
    renderCanvasPage()
    await user.click(await screen.findByRole('button', { name: /真实画布/ }))
    await screen.findByLabelText(/无限画布/)
    expect(screen.getByTestId('minimap')).toBeInTheDocument()
    act(() => {
      ;(flowHarness.current as {
        onSelectionChange: (params: {
          nodes: Array<{ id: string }>
          edges: Array<{ source: string; target: string }>
        }) => void
      }).onSelectionChange({ nodes: [{ id: NODE_B }], edges: [] })
    })

    const input = await screen.findByRole('textbox', { name: '提示词片段 1' })
    expect(screen.queryByTestId('minimap')).not.toBeInTheDocument()
    expect(screen.queryByRole('toolbar', { name: '选区操作' })).not.toBeInTheDocument()
    await user.click(input)
    await user.type(input, ' one')
    await waitFor(() => expect(commandBodies.some((body) => (
      body.commands[0]?.type === 'UPDATE_FUNCTION'
    ))).toBe(true), { timeout: 1_500 })
    expect(input).toHaveFocus()
    expect(input).toHaveValue('start one')
    await user.type(input, ' two')
    expect(input).toHaveValue('start one two')
    expect(input).toHaveFocus()
    await waitFor(() => expect(commandBodies.filter((body) => (
      body.commands[0]?.type === 'UPDATE_FUNCTION'
    ))).toHaveLength(2), { timeout: 1_500 })
  })
})
