import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { CanvasEditor } from '@/features/canvas/CanvasEditor'
import { CanvasRuntimeContext } from '@/features/canvas/CanvasRuntimeContext'
import type { CanvasController } from '@/features/canvas/useCanvasController'
import { QueryClient, QueryObserver, type UseQueryResult } from '@tanstack/react-query'
import { ApiError } from '@/shared/api/client'
import type { CanvasSnapshotDTO } from '@/shared/api/generated/contracts'

const CANVAS_ID: string = '8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f'

/** Stage 依赖 @xyflow/react，Editor 测试只验证状态渲染、导航与线程开关 wiring。 */
vi.mock('@/features/canvas/CanvasStage', () => ({
  CanvasStage: () => <div data-testid="canvas-stage" />,
}))

interface EditorQueryState {
  isError: boolean
  error: Error | null
  isLoading: boolean
  refetch: () => Promise<unknown>
}

function buildSnapshotQuery(
  options: Partial<EditorQueryState> = {},
  snapshot: CanvasSnapshotDTO | null = null,
): UseQueryResult<CanvasSnapshotDTO, Error> {
  const client = new QueryClient({ defaultOptions: { queries: { gcTime: Infinity } } })
  const queryOptions = {
    queryKey: ['editor-snapshot'],
    queryFn: async () => snapshot ?? snapshotDTO(),
  }
  const query = client.getQueryCache().build(client, queryOptions)
  if (options.isError) {
    query.setState({ status: 'error', error: options.error ?? new Error('Query failed') })
  } else if (snapshot && options.isLoading === false) {
    query.setData(snapshot)
  } else {
    query.setState({ fetchStatus: options.isLoading === false ? 'idle' : 'fetching' })
  }
  const result = new QueryObserver(client, queryOptions).getCurrentResult()
  return {
    ...result,
    refetch: async () => {
      await options.refetch?.()
      return result
    },
  }
}

function createController(
  state: CanvasController['state'],
  snapshot: CanvasSnapshotDTO | null,
  snapshotQuery: UseQueryResult<CanvasSnapshotDTO, Error>,
): CanvasController {
  return {
    state,
    snapshot,
    projectedSnapshot: null,
    stageMetrics: { width: 1000, height: 800, dockTop: 100 },
    setStageMetrics: vi.fn(),
    fitViewRef: { current: vi.fn() },
    focusSelectionRef: { current: vi.fn() },
    zoomRef: { current: vi.fn() },
    stageElementRef: { current: null },
    dockAddRef: { current: null },
    closeContextMenuRef: { current: vi.fn() },
    snapshotQuery,
    modelsQuery: new QueryObserver<CanvasController['models'], Error>(
      new QueryClient({ defaultOptions: { queries: { gcTime: Infinity } } }),
      { queryKey: ['editor-models'], initialData: [] },
    ).getCurrentResult(),
    models: [],
    nodeCallbacks: { editTextNode: vi.fn() },
    initialFitPending: false,
    completeInitialFit: vi.fn(),
    openEditor: vi.fn(),
    openLibrary: vi.fn(),
    setToast: vi.fn(),
    setViewport: vi.fn(),
    setSelection: vi.fn(),
    moveNodes: vi.fn(),
    commitTransforms: vi.fn(),
    createLink: vi.fn(),
    deleteLink: vi.fn(),
    deleteSelection: vi.fn(),
    createTextNode: vi.fn(),
    setTextEditorDraft: vi.fn(),
    closeTextEditor: vi.fn(),
    saveTextEditor: vi.fn(),
    createFunctionNode: vi.fn(),
    scheduleFunctionConfig: vi.fn(),
    flushFunctionConfig: vi.fn(),
    startFunctionRun: vi.fn(),
    cancelFunctionRun: vi.fn(),
    resolveFunctionRun: vi.fn(),
    discardPendingRun: vi.fn(() => false),
    localPendingErrors: {},
    isNodeInFlight: vi.fn(() => false),
    createGroup: vi.fn(),
    ungroupGroup: vi.fn(),
    renameGroup: vi.fn(),
    deleteGroup: vi.fn(),
    renameNode: vi.fn(),
    editTextNode: vi.fn(),
    deleteNode: vi.fn(),
    uploadFiles: vi.fn(),
    handleAddAction: vi.fn(),
    toggleAddMenu: vi.fn(),
    closeAddMenu: vi.fn(),
    setAddMenuIndex: vi.fn(),
    dismissDraft: vi.fn(),
    retryDraft: vi.fn(),
    retryDraftPersist: vi.fn(async () => {}),
    ackDurableDrafts: vi.fn(async () => {}),
    retryRecovery: vi.fn(),
    dismissConflictMessage: vi.fn(),
    saveDraftAsNewNode: vi.fn(),
    restoreDeletedDraftAsNewNode: vi.fn(),
  }
}

function snapshotDTO(overrides: Partial<CanvasSnapshotDTO> = {}): CanvasSnapshotDTO {
  return {
    document: {
      id: CANVAS_ID,
      title: 'Board',
      revision: '3',
      createdAt: '2026-08-10T00:00:00Z',
      updatedAt: '2026-08-10T00:00:00Z',
    },
    nodes: [],
    groups: [],
    references: [],
    ...overrides,
  }
}

function renderEditor(options: {
  snapshot?: CanvasSnapshotDTO | null
  query?: Partial<EditorQueryState>
  commandPending?: boolean
  withLibraryRoute?: boolean
} = {}) {
  const refetch = vi.fn(async () => undefined)
  const state = {
    view: 'editor' as const,
    canvasId: CANVAS_ID,
    selectedIds: [],
    selectedLinks: [],
    positionDrafts: {},
    drafts: {},
    storageError: null,
    draftPersistPending: false,
    viewport: { x: 0, y: 0, zoom: 1 },
    toast: null,
    addMenuOpen: false,
    addMenuIndex: 0,
    uploadProgress: {},
    commandPending: options.commandPending ?? false,
    conflictMessage: null,
    textEditor: null,
  }
  const query: EditorQueryState = {
    isError: false,
    error: null,
    isLoading: true,
    refetch,
    ...options.query,
  }
  const tree = (nextState: typeof state, nextQuery: EditorQueryState) => {
    const controller = createController(
      nextState,
      options.snapshot ?? null,
      buildSnapshotQuery(nextQuery, options.snapshot ?? null),
    )
    return (
      <MemoryRouter initialEntries={['/canvas/abc']}>
        <CanvasRuntimeContext.Provider value={controller}>
          {options.withLibraryRoute ? (
            <Routes>
              <Route path="/canvas" element={<div data-testid="library" />} />
              <Route path="/canvas/:canvasId" element={<CanvasEditor />} />
            </Routes>
          ) : (
            <CanvasEditor />
          )}
        </CanvasRuntimeContext.Provider>
      </MemoryRouter>
    )
  }
  const view = render(tree(state, query))
  return {
    refetch,
    rerenderWith: (patch: Partial<typeof state>, queryPatch: Partial<EditorQueryState> = {}) => {
      view.rerender(tree({ ...state, ...patch }, { ...query, ...queryPatch }))
    },
  }
}

describe('CanvasEditor state rendering', () => {
  it('shows the loading state while the snapshot query is pending', () => {
    renderEditor()
    expect(screen.getByRole('status')).toHaveTextContent('正在加载画布…')
    expect(screen.queryByTestId('canvas-stage')).not.toBeInTheDocument()
  })

  it('stays in the loading state when the query settled without a snapshot', () => {
    renderEditor({ query: { isLoading: false } })
    expect(screen.getByRole('status')).toHaveTextContent('正在加载画布…')
  })

  it('renders the not-found state without a retry action', () => {
    renderEditor({
      query: { isError: true, error: new ApiError('canvas gone', 404) },
    })
    expect(screen.getByRole('alert')).toHaveTextContent('画布不存在')
    expect(screen.getByText('canvas gone')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '返回画布库' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '重试' })).not.toBeInTheDocument()
  })

  it('renders the load-failed state and retries on demand', async () => {
    const user = userEvent.setup()
    const { refetch } = renderEditor({
      query: { isError: true, error: new Error('network down') },
    })
    expect(screen.getByRole('alert')).toHaveTextContent('画布加载失败')
    expect(screen.getByText('network down')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '重试' }))
    expect(refetch).toHaveBeenCalledTimes(1)
  })

  it('navigates back to the library from an error state', async () => {
    const user = userEvent.setup()
    renderEditor({
      query: { isError: true, error: new ApiError('canvas gone', 404) },
      withLibraryRoute: true,
    })
    await user.click(screen.getByRole('button', { name: '返回画布库' }))
    expect(await screen.findByTestId('library')).toBeInTheDocument()
  })

  it('renders the loaded document without an unsupported Agent dock', () => {
    const { rerenderWith } = renderEditor({
      snapshot: snapshotDTO(),
      query: { isLoading: false },
    })
    expect(screen.getByLabelText('Canvas 资源编辑器')).toBeInTheDocument()
    expect(screen.getByText('Board')).toBeInTheDocument()
    expect(screen.getByText('已保存')).toBeInTheDocument()
    expect(screen.getByText('v3')).toBeInTheDocument()
    expect(screen.getByTestId('canvas-stage')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '返回画布库' })).toHaveAttribute('href', '/canvas')

    expect(screen.queryByRole('button', { name: '切换对话面板' })).not.toBeInTheDocument()

    rerenderWith({ commandPending: true })
    expect(screen.getByText('保存中…')).toBeInTheDocument()
  })
})
