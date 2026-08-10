import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode } from 'react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { CanvasPage } from '@/features/canvas/CanvasPage'
import type {
  CanvasCommandDTO,
  CanvasDocumentDTO,
  CanvasSnapshotDTO,
} from '@/shared/api/contracts/studio'

const flowHarness = vi.hoisted(() => ({
  current: null as unknown,
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
  useReactFlow: () => ({
    fitView: vi.fn(async () => undefined),
    getViewport: () => ({ x: 80, y: 20, zoom: 0.6 }),
    setViewport: vi.fn(async () => undefined),
    zoomTo: vi.fn(async () => undefined),
  }),
}))

function canvasDocument(id = '1', title = '真实画布'): CanvasDocumentDTO {
  return {
    id,
    title,
    graphRevision: '0',
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
    expectedRevision: string
    commandId: string
    commands: CanvasCommandDTO[]
  }> = []
  const createBodies: unknown[] = []

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
      const document = canvasDocument('2', '未命名画布')
      snapshots.set(document.id, baseSnapshot(document))
      return envelope(document, 201)
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
    const commandMatch = /^\/api\/canvases\/([^/]+)\/commands$/.exec(url)
    if (commandMatch && method === 'POST') {
      const canvasId = commandMatch[1] as string
      const body = JSON.parse(String(init?.body)) as {
        expectedRevision: string
        commandId: string
        commands: CanvasCommandDTO[]
      }
      commandBodies.push(body)
      const current = snapshots.get(canvasId)
      if (!current) {
        return envelope(null, 404, 'canvas not found')
      }
      const snapshot = applyCommands(current, body.commands)
      snapshots.set(canvasId, snapshot)
      return envelope(snapshot)
    }
    if (url === '/api/canvases/1/uploads' && method === 'POST') {
      return envelope({
        uploadId: '50',
        method: 'PUT',
        url: 'https://s3.example/direct',
        headers: {
          Host: 's3.internal',
          'If-None-Match': '*',
          'Content-Type': 'image/png',
        },
        expiresAt: '2026-08-10T00:15:00Z',
      }, 201)
    }
    if (url === 'https://s3.example/direct' && method === 'PUT') {
      return new Response(null, { status: 200 })
    }
    if (url === '/api/canvases/1/uploads/50/complete' && method === 'POST') {
      return envelope({
        id: '50',
        canvasId: '1',
        kind: 'IMAGE',
        mediaType: 'image/png',
        name: 'tiny.png',
        size: '3',
        textContent: null,
        metadataJson: '{"width":1,"height":1}',
        createdAt: '2026-08-10T00:00:00Z',
      })
    }
    throw new Error(`Unexpected request ${method} ${url}`)
  }))

  return { commandBodies, createBodies, snapshots }
}

function renderCanvasPage() {
  return render(
    <MemoryRouter>
      <CanvasPage />
    </MemoryRouter>,
  )
}

describe('CanvasPage real list/create/load integration', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('loads the authoritative snapshot and sends typed text/function commands', async () => {
    const { commandBodies } = installBackend()
    const user = userEvent.setup()
    renderCanvasPage()

    await user.click(await screen.findByRole('button', { name: /真实画布/ }))
    expect(await screen.findByLabelText(/无限画布/)).toBeInTheDocument()
    expect(screen.getByText('r0')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '添加资源、Function 或分组' }))
    await user.click(within(screen.getByRole('menu')).getByRole('menuitem', { name: /文本资源/ }))
    await user.clear(screen.getByLabelText('Markdown 内容'))
    await user.type(screen.getByLabelText('Markdown 内容'), '# E2E 文本')
    await user.click(screen.getByRole('button', { name: '保存' }))
    await waitFor(() => {
      expect(commandBodies.some((body) => body.commands[0]?.type === 'CREATE_TEXT_NODE')).toBe(true)
    })

    await user.click(screen.getByRole('button', { name: '添加资源、Function 或分组' }))
    await user.click(within(screen.getByRole('menu')).getByRole('menuitem', { name: /图片生成/ }))
    await waitFor(() => {
      expect(commandBodies.some((body) => body.commands[0]?.type === 'CREATE_FUNCTION_NODE')).toBe(true)
    })
    expect(commandBodies.every((body) => (
      typeof body.expectedRevision === 'string'
      && typeof body.commandId === 'string'
      && body.commandId.length > 0
    ))).toBe(true)
  })

  it('shows the empty state, creates a canvas, and loads its empty snapshot', async () => {
    const { createBodies } = installBackend({ documents: [] })
    const user = userEvent.setup()
    renderCanvasPage()

    expect(await screen.findByText('还没有画布')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '创建新画布' }))

    expect(await screen.findByText('未命名画布')).toBeInTheDocument()
    expect(await screen.findByLabelText(/无限画布/)).toBeInTheDocument()
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
      documents: [canvasDocument('404', '已删除画布')],
      missingCanvasIds: ['404'],
    })
    const user = userEvent.setup()
    renderCanvasPage()

    await user.click(await screen.findByRole('button', { name: /已删除画布/ }))
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('画布不存在')
    expect(within(alert).queryByRole('button', { name: '重试' })).not.toBeInTheDocument()
    expect(within(alert).getByRole('button', { name: '返回画布库' })).toBeInTheDocument()
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
  })

  it('keeps drag positions as a local draft and submits them after drag stop debounce', async () => {
    const { commandBodies, snapshots } = installBackend()
    const current = snapshots.get('1') as CanvasSnapshotDTO
    snapshots.set('1', {
      ...current,
      nodes: [{
        id: '10',
        canvasId: '1',
        name: 'note',
        transform: { x: 20, y: 30, width: 320, height: 260 },
        groupId: null,
        resources: [{
          id: '100',
          canvasId: '1',
          kind: 'TEXT',
          mediaType: 'text/markdown',
          name: 'note.md',
          size: '4',
          textContent: 'note',
          metadataJson: '{}',
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
        id: '10',
        position: { x: 120, y: 140 },
        dragging: true,
      }])
    })

    await waitFor(() => {
      const latest = flowHarness.current as typeof flow
      expect(latest.nodes.find((node) => node.id === '10')?.position).toEqual({ x: 120, y: 140 })
    })
    expect(commandBodies.some((body) => body.commands[0]?.type === 'UPDATE_NODE_TRANSFORMS')).toBe(false)

    act(() => {
      (flowHarness.current as typeof flow).onNodeDragStop()
    })
    await waitFor(() => {
      expect(commandBodies.some((body) => body.commands[0]?.type === 'UPDATE_NODE_TRANSFORMS')).toBe(true)
    })
  })

  it('restricts links to Resource -> Function and wires link/group deletion UI', async () => {
    // React Flow callbacks prove selection toolbar and keyboard operations emit typed graph commands.
    const { commandBodies, snapshots } = installBackend()
    const current = snapshots.get('1') as CanvasSnapshotDTO
    snapshots.set('1', {
      ...current,
      nodes: [{
        id: '10',
        canvasId: '1',
        name: 'Image',
        transform: { x: 20, y: 30, width: 320, height: 260 },
        groupId: null,
        resources: [{
          id: '100',
          canvasId: '1',
          kind: 'IMAGE',
          mediaType: 'image/png',
          name: 'image.png',
          size: '3',
          textContent: null,
          metadataJson: '{}',
          createdAt: '2026-08-10T00:00:00Z',
        }],
        function: null,
        run: null,
      }, {
        id: '11',
        canvasId: '1',
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
      isValidConnection: (connection: { source: string; target: string }) => boolean
      onConnect: (connection: { source: string; target: string }) => void
      onSelectionChange: (params: {
        nodes: Array<{ id: string }>
        edges: Array<{ source: string; target: string }>
      }) => void
    }
    expect(flow.isValidConnection({ source: '10', target: '11' })).toBe(true)
    expect(flow.isValidConnection({ source: '11', target: '10' })).toBe(false)
    act(() => flow.onConnect({ source: '10', target: '11' }))
    await waitFor(() => expect(commandBodies.some((body) => (
      body.commands[0]?.type === 'CREATE_LINK'
    ))).toBe(true))

    act(() => flow.onSelectionChange({
      nodes: [],
      edges: [{ source: '10', target: '11' }],
    }))
    await user.click(screen.getByRole('button', { name: '删除' }))
    await waitFor(() => expect(commandBodies.some((body) => (
      body.commands[0]?.type === 'DELETE_LINK'
    ))).toBe(true))

    act(() => flow.onSelectionChange({ nodes: [{ id: '10' }], edges: [] }))
    await user.click(screen.getByRole('button', { name: '分组' }))
    await waitFor(() => expect(commandBodies.some((body) => (
      body.commands[0]?.type === 'CREATE_GROUP'
    ))).toBe(true))
    act(() => flow.onSelectionChange({ nodes: [{ id: 'group:99' }], edges: [] }))
    await user.click(screen.getByRole('button', { name: '解散 / 移出分组' }))
    await waitFor(() => expect(commandBodies.some((body) => (
      body.commands[0]?.type === 'DELETE_GROUP'
    ))).toBe(true))

    act(() => {
      flow.onSelectionChange({ nodes: [{ id: '10' }, { id: '11' }], edges: [] })
      ;(flow as typeof flow & {
        onNodeClick: (event: { shiftKey: boolean }, node: { id: string }) => void
      }).onNodeClick({ shiftKey: true }, { id: '11' })
    })
    await waitFor(() => {
      const latest = flowHarness.current as {
        nodes: Array<{ id: string; selected?: boolean }>
      }
      expect(latest.nodes.filter((node) => node.selected).map((node) => node.id)).toEqual([
        '10',
        '11',
      ])
    })
  })

  it('keeps the generation input focused while its debounced config snapshot is adopted', async () => {
    // A stable node key must survive UPDATE_FUNCTION echoes so continuous typing is not interrupted.
    const { commandBodies, snapshots } = installBackend()
    const current = snapshots.get('1') as CanvasSnapshotDTO
    snapshots.set('1', {
      ...current,
      nodes: [{
        id: '11',
        canvasId: '1',
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
    act(() => {
      ;(flowHarness.current as {
        onSelectionChange: (params: {
          nodes: Array<{ id: string }>
          edges: Array<{ source: string; target: string }>
        }) => void
      }).onSelectionChange({ nodes: [{ id: '11' }], edges: [] })
    })

    const input = await screen.findByRole('textbox', { name: '提示词片段 1' })
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

function applyCommands(
  current: CanvasSnapshotDTO,
  commands: CanvasCommandDTO[],
): CanvasSnapshotDTO {
  let nodes = current.nodes
  for (const command of commands) {
    if (command.type === 'CREATE_TEXT_NODE') {
      nodes = [...nodes, {
        id: String(nodes.length + 1),
        canvasId: current.document.id,
        name: command.name,
        transform: command.transform,
        groupId: null,
        resources: [{
          id: `10${nodes.length}`,
          canvasId: current.document.id,
          kind: 'TEXT',
          mediaType: 'text/markdown',
          name: command.name,
          size: String(command.markdown.length),
          textContent: command.markdown,
          metadataJson: '{}',
          createdAt: '2026-08-10T00:00:00Z',
        }],
        function: null,
        run: null,
      }]
    } else if (command.type === 'CREATE_FUNCTION_NODE') {
      nodes = [...nodes, {
        id: String(nodes.length + 1),
        canvasId: current.document.id,
        name: command.name,
        transform: command.transform,
        groupId: null,
        resources: [],
        function: {
          modelKey: command.modelKey,
          configJson: command.configJson,
        },
        run: null,
      }]
    } else if (command.type === 'CREATE_RESOURCE_NODE') {
      nodes = [...nodes, {
        id: String(nodes.length + 1),
        canvasId: current.document.id,
        name: command.name,
        transform: command.transform,
        groupId: null,
        resources: [{
          id: command.resourceIds[0] as string,
          canvasId: current.document.id,
          kind: 'IMAGE',
          mediaType: 'image/png',
          name: command.name,
          size: '3',
          textContent: null,
          metadataJson: '{}',
          createdAt: '2026-08-10T00:00:00Z',
        }],
        function: null,
        run: null,
      }]
    } else if (command.type === 'UPDATE_FUNCTION') {
      nodes = nodes.map((node) => node.id === command.nodeId ? {
        ...node,
        function: {
          modelKey: command.modelKey,
          configJson: command.configJson,
        },
      } : node)
    }
  }
  return {
    ...current,
    document: {
      ...current.document,
      graphRevision: (BigInt(current.document.graphRevision) + 1n).toString(),
    },
    nodes,
  }
}
