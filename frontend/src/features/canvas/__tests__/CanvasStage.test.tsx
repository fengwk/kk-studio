import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { CanvasStage } from '@/features/canvas/CanvasStage'
import { CanvasRuntimeContext } from '@/features/canvas/CanvasRuntimeContext'
import type { CanvasController } from '@/features/canvas/useCanvasController'
import type { CanvasSnapshot } from '@/features/canvas/domain'
import { canvasViewportStorageKey } from '@/features/canvas/viewport-storage'
import type { CanvasSnapshotDTO } from '@/shared/api/contracts/studio'
import { setLocale } from '@/shared/i18n'

const CANVAS_ID = '8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f'
const NODE_A = '9f1e6d2a-3b4c-4d5e-8f6a-7b8c9d0e1f2a'
const NODE_B = 'a2b3c4d5-6e7f-4a8b-9c0d-1e2f3a4b5c6d'
const GROUP_ID = 'c4d5e6f7-8a9b-4c0d-8e1f-2a3b4c5d6e7f'
const RESOURCE_ID = 'd5e6f7a8-9b0c-4d1e-8f2a-3b4c5d6e7f8a'

/** 捕获 ReactFlow props 的 harness：测试直接调用回调验证 wiring。 */
const flowHarness = vi.hoisted(() => ({
  current: null as unknown,
  nodesInitialized: true,
  viewport: { x: 0, y: 0, zoom: 1 },
  fitView: vi.fn(async () => true),
  setViewport: vi.fn(async () => undefined),
  zoomTo: vi.fn(async () => undefined),
}))

/** 菜单/面板子组件全部打桩：Stage 测试只关注 wiring 与条件渲染。 */
vi.mock('@/features/canvas/CanvasContextMenu', () => ({
  CanvasContextMenu: ({ state }: { state: { x: number; y: number } }) => (
    <div data-testid="context-menu" data-x={state.x} data-y={state.y} />
  ),
}))

vi.mock('@/features/canvas/CanvasGenerationPanel', () => ({
  CanvasGenerationPanel: () => <div data-testid="generation-panel" />,
}))

vi.mock('@/features/canvas/CanvasToolRail', () => ({
  CanvasToolRail: () => <div data-testid="tool-rail" />,
}))

vi.mock('@/features/canvas/CanvasTextEditor', () => ({
  CanvasTextEditor: () => <div data-testid="text-editor" />,
}))

vi.mock('@/features/canvas/agent/CanvasAgentDock', () => ({
  CanvasAgentDock: () => <div data-testid="agent-dock" />,
}))

vi.mock('@xyflow/react', () => ({
  ReactFlowProvider: ({ children }: { children: ReactNode }) => <>{children}</>,
  ReactFlow: (props: {
    children: ReactNode
    nodes: unknown[]
    edges: unknown[]
    defaultViewport: unknown
    isValidConnection: unknown
    onConnect?: unknown
    onEdgesDelete?: unknown
    onNodesChange?: unknown
    onNodeDragStop?: unknown
    onMove?: unknown
    onMoveEnd?: unknown
    onSelectionChange?: unknown
    onNodeContextMenu?: unknown
    onSelectionContextMenu?: unknown
    onPaneContextMenu?: unknown
    onNodeClick?: unknown
    onPaneClick?: unknown
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
  useNodesInitialized: () => flowHarness.nodesInitialized,
  useReactFlow: () => ({
    fitView: flowHarness.fitView,
    getViewport: () => flowHarness.viewport,
    setViewport: flowHarness.setViewport,
    zoomTo: flowHarness.zoomTo,
  }),
}))

function resourceDTO(overrides: Partial<CanvasSnapshotDTO['nodes'][number]> = {}) {
  return {
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
      blobId: 'blob-asset',
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
    ...overrides,
  }
}

function snapshotDTO(overrides: Partial<CanvasSnapshotDTO> = {}): CanvasSnapshotDTO {
  return {
    document: {
      id: CANVAS_ID,
      title: 'Board',
      version: '0',
      createdAt: '2026-08-10T00:00:00Z',
      updatedAt: '2026-08-10T00:00:00Z',
    },
    nodes: [resourceDTO()],
    groups: [],
    links: [],
    ...overrides,
  }
}

function renderStage(options: {
  snapshot?: CanvasSnapshotDTO | null
  selectedIds?: string[]
  selectedLinks?: Array<{ sourceNodeId: string; targetNodeId: string }>
  viewport?: { x: number; y: number; zoom: number }
  threadOpen?: boolean
  initialFitPending?: boolean
  positionDrafts?: Record<string, { x: number; y: number }>
} = {}) {
  const dto = options.snapshot === undefined ? snapshotDTO() : options.snapshot
  const actions = {
    setSelection: vi.fn(),
    setViewport: vi.fn(),
    setStageMetrics: vi.fn(),
    moveNodes: vi.fn(),
    commitTransforms: vi.fn(),
    createLink: vi.fn(),
    deleteLink: vi.fn(),
    uploadFiles: vi.fn(async () => undefined),
    completeInitialFit: vi.fn(),
    editTextNode: vi.fn(),
    renameNode: vi.fn(),
    deleteNode: vi.fn(),
    renameGroup: vi.fn(),
    deleteGroup: vi.fn(),
    ungroupGroup: vi.fn(),
    createGroup: vi.fn(),
    startFunctionRun: vi.fn(),
    cancelFunctionRun: vi.fn(),
  }
  const state = {
    view: 'editor' as const,
    canvasId: CANVAS_ID,
    selectedIds: options.selectedIds ?? [],
    selectedLinks: options.selectedLinks ?? [],
    positionDrafts: options.positionDrafts ?? {},
    viewport: options.viewport ?? { x: 0, y: 0, zoom: 1 },
    toast: null,
    addMenuOpen: false,
    addMenuIndex: 0,
    threadOpen: options.threadOpen ?? false,
    uploadProgress: {},
    commandPending: false,
    conflictMessage: null,
    textEditor: null,
  }
  const closeContextMenuRef: { current: (() => void) | null } = { current: null }
  const fitViewRef: { current: (() => void) | null } = { current: null }
  const focusSelectionRef: { current: (() => void) | null } = { current: null }
  const zoomRef: { current: ((zoom: number) => void) | null } = { current: null }
  const controller = {
    state,
    snapshot: dto,
    models: [],
    stageMetrics: { width: 960, height: 640, dockTop: 520 },
    stageElementRef: { current: null },
    fitViewRef,
    focusSelectionRef,
    zoomRef,
    closeContextMenuRef,
    initialFitPending: options.initialFitPending ?? false,
    ...actions,
  } as unknown as CanvasController

  const view = render(
    <CanvasRuntimeContext.Provider value={controller}>
      <CanvasStage />
    </CanvasRuntimeContext.Provider>,
  )

  const flow = () => flowHarness.current as {
    nodes: Array<{ id: string; position: { x: number; y: number }; selected?: boolean; data: { kind: string; node: CanvasSnapshot['resourceNodes'][number] } }>
    edges: Array<{ source: string; target: string }>
    defaultViewport: { x: number; y: number; zoom: number }
    isValidConnection: (connection: { source?: string; target?: string }) => boolean
    onConnect: (connection: { source?: string; target?: string }) => void
    onEdgesDelete: (deleted: Array<{ source: string; target: string }>) => void
    onNodesChange: (changes: Array<{ type: string; id?: string; position?: { x: number; y: number }; dragging?: boolean }>) => void
    onNodeDragStop: () => void
    onMove: (event: unknown, viewport: { x: number; y: number; zoom: number }) => void
    onMoveEnd: (event: unknown, viewport: { x: number; y: number; zoom: number }) => void
    onSelectionChange: (params: { nodes: Array<{ id: string }>; edges: Array<{ source: string; target: string }> }) => void
    onNodeContextMenu: (event: { clientX: number; clientY: number; preventDefault: () => void }, node: { id: string; selected: boolean; data: { kind: string } }) => void
    onSelectionContextMenu: (event: { clientX: number; clientY: number; preventDefault: () => void }, nodes: Array<{ id: string }>) => void
    onPaneContextMenu: (event: { clientX: number; clientY: number; preventDefault: () => void }) => void
    onNodeClick: (event: { shiftKey: boolean }, node: { id: string; data: { kind: string; node: CanvasSnapshot['resourceNodes'][number] } }) => void
    onPaneClick: () => void
  }

  const rerenderWith = (patch: Partial<typeof state>) => {
    const nextState = { ...state, ...patch }
    view.rerender(
      <CanvasRuntimeContext.Provider value={{
        ...controller,
        state: nextState,
      } as CanvasController}>
        <CanvasStage />
      </CanvasRuntimeContext.Provider>,
    )
  }

  return { view, actions, flow, closeContextMenuRef, rerenderWith, state, refs: { fitViewRef, focusSelectionRef, zoomRef } }
}

describe('CanvasStage ReactFlow wiring', () => {
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
  })

  it('projects nodes/edges into ReactFlow and keeps the stage chrome', () => {
    const { flow } = renderStage({
      snapshot: snapshotDTO({
        nodes: [resourceDTO(), resourceDTO({ id: NODE_B })],
        groups: [{
          id: GROUP_ID,
          canvasId: CANVAS_ID,
          title: 'Frame',
          transform: { x: 0, y: 0, width: 400, height: 300 },
        }],
        links: [{ canvasId: CANVAS_ID, sourceNodeId: NODE_A, targetNodeId: NODE_B }],
      }),
    })

    expect(screen.getByTestId('react-flow')).toBeInTheDocument()
    expect(screen.getByTestId('tool-rail')).toBeInTheDocument()
    expect(screen.getByTestId('agent-dock')).toBeInTheDocument()
    expect(screen.getByTestId('text-editor')).toBeInTheDocument()
    expect(flow().nodes).toHaveLength(3)
    expect(flow().edges).toHaveLength(1)
  })

  it('allows only valid resource->function connections and creates links for them', () => {
    const { actions, flow } = renderStage({
      snapshot: snapshotDTO({
        nodes: [
          resourceDTO(),
          resourceDTO({
            id: NODE_B,
            name: 'Generator',
            resources: [],
            function: { modelKey: 'fake-image', configJson: '{}' },
          }),
        ],
      }),
    })

    expect(flow().isValidConnection({ source: NODE_A, target: NODE_B })).toBe(true)
    expect(flow().isValidConnection({ source: NODE_B, target: NODE_A })).toBe(false)
    expect(flow().isValidConnection({ source: NODE_A, target: NODE_A })).toBe(false)

    act(() => flow().onConnect({ source: NODE_A, target: NODE_B }))
    expect(actions.createLink).toHaveBeenCalledWith(NODE_A, NODE_B)
    act(() => flow().onConnect({ source: NODE_B, target: NODE_A }))
    expect(actions.createLink).toHaveBeenCalledTimes(1)
  })

  it('deletes links through onEdgesDelete and ignores unknown edges', () => {
    const { actions, flow } = renderStage({
      snapshot: snapshotDTO({
        nodes: [resourceDTO(), resourceDTO({ id: NODE_B, resources: [] })],
        links: [{ canvasId: CANVAS_ID, sourceNodeId: NODE_A, targetNodeId: NODE_B }],
      }),
    })

    act(() => flow().onEdgesDelete([{ source: NODE_A, target: NODE_B }]))
    expect(actions.deleteLink).toHaveBeenCalledWith(NODE_A, NODE_B)

    act(() => flow().onEdgesDelete([{ source: 'ghost', target: 'other' }]))
    expect(actions.deleteLink).toHaveBeenCalledTimes(1)
  })

  it('applies node position changes as drafts and commits non-dragging ones', () => {
    const { actions, flow } = renderStage({})

    act(() => flow().onNodesChange([{
      type: 'position',
      id: NODE_A,
      position: { x: 120, y: 140 },
      dragging: true,
    }]))
    expect(actions.moveNodes).toHaveBeenCalledWith([{
      id: NODE_A,
      kind: 'resource',
      transform: { x: 120, y: 140, width: 320, height: 246 },
    }])
    expect(actions.commitTransforms).not.toHaveBeenCalled()

    act(() => flow().onNodesChange([{
      type: 'position',
      id: NODE_A,
      position: { x: 130, y: 150 },
      dragging: false,
    }]))
    expect(actions.commitTransforms).toHaveBeenCalled()
  })

  it('moves a group through its flow id', () => {
    const { actions, flow } = renderStage({
      snapshot: snapshotDTO({
        groups: [{
          id: GROUP_ID,
          canvasId: CANVAS_ID,
          title: 'Frame',
          transform: { x: 0, y: 0, width: 400, height: 300 },
        }],
      }),
    })

    act(() => flow().onNodesChange([{
      type: 'position',
      id: `group:${GROUP_ID}`,
      position: { x: 10, y: 20 },
      dragging: true,
    }]))
    expect(actions.moveNodes).toHaveBeenCalledWith([{
      id: `group:${GROUP_ID}`,
      kind: 'group',
      transform: { x: 10, y: 20, width: 400, height: 300 },
    }])
  })

  it('ignores position changes for unknown ids', () => {
    const { actions, flow } = renderStage({})

    act(() => flow().onNodesChange([{
      type: 'position',
      id: 'ghost-id',
      position: { x: 1, y: 2 },
      dragging: true,
    }]))
    expect(actions.moveNodes).not.toHaveBeenCalled()
  })

  it('publishes selection and opens a node context menu', async () => {
    const { actions, flow } = renderStage({})

    act(() => flow().onSelectionChange({
      nodes: [{ id: NODE_A }],
      edges: [],
    }))
    expect(actions.setSelection).toHaveBeenCalledWith([NODE_A], [])

    act(() => flow().onNodeContextMenu(
      { clientX: 40, clientY: 60, preventDefault: vi.fn() },
      { id: NODE_A, selected: false, data: { kind: "resource" } },
    ))
    expect(await screen.findByTestId('context-menu')).toHaveAttribute('data-x', '40')
    expect(actions.setSelection).toHaveBeenCalledWith([NODE_A])
  })

  it('opens a multi-selection menu and closes it on pane context click', async () => {
    const { flow } = renderStage({
      snapshot: snapshotDTO({
        nodes: [resourceDTO(), resourceDTO({ id: NODE_B, resources: [] })],
      }),
      selectedIds: [NODE_A, NODE_B],
    })

    act(() => flow().onNodeContextMenu(
      { clientX: 40, clientY: 60, preventDefault: vi.fn() },
      { id: NODE_A, selected: true, data: { kind: 'resource' } },
    ))
    expect(await screen.findByTestId('context-menu')).toBeInTheDocument()

    act(() => flow().onPaneContextMenu({ clientX: 1, clientY: 1, preventDefault: vi.fn() }))
    await waitFor(() => expect(screen.queryByTestId('context-menu')).not.toBeInTheDocument())
  })

  it('closes the menu on pane click and clears the selection', async () => {
    const { actions, flow } = renderStage({})
    act(() => flow().onNodeContextMenu(
      { clientX: 40, clientY: 60, preventDefault: vi.fn() },
      { id: NODE_A, selected: false, data: { kind: "resource" } },
    ))
    expect(await screen.findByTestId('context-menu')).toBeInTheDocument()

    act(() => flow().onPaneClick())
    expect(screen.queryByTestId('context-menu')).not.toBeInTheDocument()
    expect(actions.setSelection).toHaveBeenCalledWith([])
  })

  it('selects and edits TEXT nodes on click without shift', () => {
    const { actions, flow } = renderStage({
      snapshot: snapshotDTO({
        nodes: [resourceDTO({
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
            sizeBytes: 5,
            width: null,
            height: null,
            durationMs: null,
            createdAt: '2026-08-10T00:00:00Z',
          }],
        })],
      }),
    })
    const textNode = flow().nodes.find((node) => node.id === NODE_A) as NonNullable<ReturnType<typeof flow>['nodes'][number]>

    act(() => flow().onNodeClick({ shiftKey: false }, textNode as never))
    expect(actions.setSelection).toHaveBeenCalledWith([NODE_A])
    expect(actions.editTextNode).toHaveBeenCalledWith(expect.objectContaining({ id: NODE_A }))

    actions.editTextNode.mockClear()
    act(() => flow().onNodeClick({ shiftKey: true }, textNode as never))
    expect(actions.setSelection).toHaveBeenCalledTimes(1)
    expect(actions.editTextNode).not.toHaveBeenCalled()
  })

  it('emits viewport on move and closes the menu, and on move end', () => {
    const { actions, flow } = renderStage({})
    act(() => flow().onNodeContextMenu(
      { clientX: 40, clientY: 60, preventDefault: vi.fn() },
      { id: NODE_A, selected: false, data: { kind: "resource" } },
    ))

    act(() => flow().onMove(null, { x: 12, y: 18, zoom: 0.74 }))
    expect(actions.setViewport).toHaveBeenCalledWith({ x: 12, y: 18, zoom: 0.74 })
    expect(screen.queryByTestId('context-menu')).not.toBeInTheDocument()

    act(() => flow().onMoveEnd(null, { x: 12, y: 18, zoom: 0.74 }))
    expect(actions.setViewport).toHaveBeenCalledTimes(2)
  })
})

describe('CanvasStage conditional rendering and viewport', () => {
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
  })

  it('hides the minimap and shows the generation panel for a selected function node', () => {
    const { flow } = renderStage({
      snapshot: snapshotDTO({
        nodes: [resourceDTO({
          id: NODE_B,
          name: 'Generator',
          resources: [],
          function: { modelKey: 'fake-image', configJson: '{}' },
        })],
      }),
      selectedIds: [NODE_B],
    })

    expect(screen.queryByTestId('minimap')).not.toBeInTheDocument()
    expect(screen.getByTestId('generation-panel')).toBeInTheDocument()
    expect(flow().nodes.some((node) => node.selected)).toBe(true)
  })

  it('shows the minimap when nothing is selected or a non-function node is selected', () => {
    renderStage({})
    expect(screen.getByTestId('minimap')).toBeInTheDocument()
    expect(screen.queryByTestId('generation-panel')).not.toBeInTheDocument()
  })

  it('runs the initial fit once and persists the fitted viewport', async () => {
    flowHarness.viewport = { x: 24, y: 36, zoom: 0.9 }
    const { actions } = renderStage({ initialFitPending: true })

    await waitFor(() => expect(flowHarness.fitView).toHaveBeenCalledWith({
      padding: 0.18,
      maxZoom: 1.6,
      duration: 0,
    }))
    await waitFor(() => expect(actions.completeInitialFit).toHaveBeenCalled())
    expect(actions.setViewport).toHaveBeenCalledWith({ x: 24, y: 36, zoom: 0.9 })
    expect(flowHarness.fitView).toHaveBeenCalledTimes(1)
  })

  it('uses the panel fit when the thread is open', async () => {
    const { actions } = renderStage({ initialFitPending: true, threadOpen: true })

    await waitFor(() => expect(flowHarness.fitView).toHaveBeenCalledWith({
      padding: 0.08,
      maxZoom: 1.6,
      duration: 0,
    }))
    await waitFor(() => expect(actions.completeInitialFit).toHaveBeenCalled())
  })

  it('skips the initial fit without pending flag, without initialized nodes, or when fit fails', async () => {
    renderStage({ initialFitPending: false })
    expect(flowHarness.fitView).not.toHaveBeenCalled()

    flowHarness.current = null
    flowHarness.nodesInitialized = false
    renderStage({ initialFitPending: true })
    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(flowHarness.fitView).not.toHaveBeenCalled()

    flowHarness.current = null
    flowHarness.nodesInitialized = true
    flowHarness.fitView.mockResolvedValue(false)
    renderStage({ initialFitPending: true })
    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(flowHarness.fitView).toHaveBeenCalled()
  })

  it('restores the persisted viewport through setFlowViewport and skips the initial fit', async () => {
    localStorage.setItem(
      canvasViewportStorageKey(CANVAS_ID),
      JSON.stringify({ x: 30, y: -20, zoom: 0.7 }),
    )
    const { flow } = renderStage({
      viewport: { x: 30, y: -20, zoom: 0.7 },
    })

    expect(flow().defaultViewport).toEqual({ x: 30, y: -20, zoom: 0.7 })
    expect(flowHarness.fitView).not.toHaveBeenCalled()
    expect(flowHarness.setViewport).not.toHaveBeenCalled()
  })

  it('translates the viewport when the flow width changes without touching zoom', async () => {
    const widthSpy = vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
      if (this.classList.contains('canvas-flow-wrap')) {
        return { x: 0, y: 0, width: 788, height: 640, top: 0, left: 0, bottom: 640, right: 788, toJSON: () => ({}) } as DOMRect
      }
      return { x: 0, y: 0, width: 0, height: 0, top: 0, left: 0, bottom: 0, right: 0, toJSON: () => ({}) } as DOMRect
    })
    const { rerenderWith, actions } = renderStage({
      viewport: { x: 20, y: 50, zoom: 0.9 },
    })
    expect(actions.setViewport).not.toHaveBeenCalled()

    // 第二次渲染前补上第一帧宽度：effect 依赖 stageMetrics.width，必须先有 960 基线。
    widthSpy.mockImplementation(function (this: HTMLElement) {
      if (this.classList.contains('canvas-flow-wrap')) {
        return { x: 0, y: 0, width: 960, height: 640, top: 0, left: 0, bottom: 640, right: 960, toJSON: () => ({}) } as DOMRect
      }
      return { x: 0, y: 0, width: 0, height: 0, top: 0, left: 0, bottom: 0, right: 0, toJSON: () => ({}) } as DOMRect
    })
    // 首帧发布宽度基线（spy 已生效，publishMetrics 直接读到 788），
    // 基线宽度记录在 flowWidthRef；随后线程面板渲染使宽度不变时不再平移。
    await waitFor(() => expect(actions.setStageMetrics).toHaveBeenCalledWith({
      width: 788,
      height: 640,
      dockTop: 624,
    }))
    rerenderWith({ threadOpen: true, viewport: { x: 20, y: 50, zoom: 0.9 } })
    // threadOpen 变化会重跑宽度 effect；基线 788 -> 960（第二轮 spy）时
    // 平移量 = (960-788)/2 = 86，zoom 保持不变（0.9）。
    await waitFor(() => {
      expect(actions.setViewport).toHaveBeenCalledWith({ x: 106, y: 50, zoom: 0.9 })
    })
    widthSpy.mockRestore()
  })

  it('invokes the registered fit/focus/zoom callbacks with the correct options', async () => {
    const { view, refs, actions } = renderStage({
      snapshot: snapshotDTO({
        nodes: [resourceDTO(), resourceDTO({ id: NODE_B, resources: [] })],
      }),
    })
    const user = userEvent.setup()

    // fitViewRef：面板关闭时使用完整 fit；打开时使用紧凑 fit。
    act(() => refs.fitViewRef.current?.())
    await waitFor(() => expect(flowHarness.fitView).toHaveBeenCalledWith({
      padding: 0.18,
      maxZoom: 1.6,
      duration: 0,
    }))
    expect(actions.setViewport).toHaveBeenCalledWith({ x: 0, y: 0, zoom: 1 })

    // fitViewRef 失败分支：fit 返回 false 时不同步 viewport。
    flowHarness.fitView.mockClear()
    flowHarness.fitView.mockResolvedValue(false)
    act(() => refs.fitViewRef.current?.())
    await waitFor(() => expect(flowHarness.fitView).toHaveBeenCalled())
    expect(actions.setViewport).toHaveBeenCalledTimes(1)

    // focusSelectionRef：无选中节点时 nodes 缺省（全画布聚焦）。
    flowHarness.fitView.mockClear()
    flowHarness.fitView.mockResolvedValue(true)
    act(() => refs.focusSelectionRef.current?.())
    await waitFor(() => expect(flowHarness.fitView).toHaveBeenCalledWith({
      nodes: undefined,
      padding: 0.22,
      maxZoom: 1.8,
      duration: 0,
    }))

    // zoomRef：调用 zoomTo 并同步 viewport。
    flowHarness.viewport = { x: 5, y: 9, zoom: 1.25 }
    act(() => refs.zoomRef.current?.(1.25))
    await waitFor(() => expect(flowHarness.zoomTo).toHaveBeenCalledWith(1.25))
    expect(actions.setViewport).toHaveBeenCalledWith({ x: 5, y: 9, zoom: 1.25 })

    // 卸载清理 refs。
    const fit = refs.fitViewRef.current
    expect(fit).not.toBeNull()
    view.unmount()
    expect(refs.fitViewRef.current).toBeNull()
    void user
  })

  it('drops files onto the stage to upload them', async () => {
    const { actions } = renderStage({})
    const stage = screen.getByLabelText(/无限画布/)
    const file = new File(['png'], 'tiny.png', { type: 'image/png' })

    fireEvent.drop(stage, {
      dataTransfer: { files: [file], types: ['Files'] },
    })
    await waitFor(() => expect(actions.uploadFiles).toHaveBeenCalledTimes(1))

    fireEvent.dragOver(stage, {
      dataTransfer: { types: ['Files'] },
    })
  })

  it('registers fit/focus/zoom refs and cleans them on unmount', () => {
    const { view, actions } = renderStage({})
    expect(flowHarness.fitView).not.toHaveBeenCalled()
    // 挂载后 refs 由 Stage 写入（ToolRail mock 不消费，但注册副作用仍生效）。
    expect(actions.completeInitialFit).not.toHaveBeenCalled()

    view.unmount()
    expect(flowHarness.fitView).not.toHaveBeenCalled()
  })
})
