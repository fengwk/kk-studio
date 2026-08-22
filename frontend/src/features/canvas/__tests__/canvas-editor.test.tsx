import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { CanvasEditor } from '@/features/canvas/CanvasEditor'
import { CanvasRuntimeContext } from '@/features/canvas/CanvasRuntimeContext'
import type { CanvasController } from '@/features/canvas/useCanvasController'
import type { CanvasSnapshotDTO } from '@/shared/api/contracts/studio'

const CANVAS_ID = '8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f'

/** Stage 依赖 @xyflow/react，Editor 测试只验证状态渲染、导航与线程开关 wiring。 */
vi.mock('@/features/canvas/CanvasStage', () => ({
  CanvasStage: () => <div data-testid="canvas-stage" />,
}))

interface EditorQueryState {
  isError: boolean
  error: unknown
  isLoading: boolean
  refetch: () => Promise<unknown>
}

function snapshotDTO(overrides: Partial<CanvasSnapshotDTO> = {}): CanvasSnapshotDTO {
  return {
    document: {
      id: CANVAS_ID,
      title: 'Board',
      version: '3',
      createdAt: '2026-08-10T00:00:00Z',
      updatedAt: '2026-08-10T00:00:00Z',
    },
    nodes: [],
    groups: [],
    links: [],
    ...overrides,
  }
}

function renderEditor(options: {
  snapshot?: CanvasSnapshotDTO | null
  query?: Partial<EditorQueryState>
  commandPending?: boolean
  threadOpen?: boolean
  withLibraryRoute?: boolean
} = {}) {
  const refetch = vi.fn(async () => undefined)
  const openThread = vi.fn()
  const collapseThread = vi.fn()
  const state = {
    view: 'editor' as const,
    canvasId: CANVAS_ID,
    selectedIds: [],
    selectedLinks: [],
    positionDrafts: {},
    viewport: { x: 0, y: 0, zoom: 1 },
    toast: null,
    addMenuOpen: false,
    addMenuIndex: 0,
    threadOpen: options.threadOpen ?? false,
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
  const runtime = {
    state,
    snapshot: options.snapshot ?? null,
    snapshotQuery: query,
    openThread,
    collapseThread,
  } as unknown as CanvasController
  const tree = (nextState: typeof state, nextQuery: EditorQueryState) => (
    <MemoryRouter initialEntries={['/canvas/abc']}>
      <CanvasRuntimeContext.Provider
        value={{ ...runtime, state: nextState, snapshotQuery: nextQuery } as CanvasController}
      >
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
  const view = render(tree(state, query))
  return {
    refetch,
    openThread,
    collapseThread,
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
      query: { isError: true, error: { status: 404, message: 'canvas gone' } },
    })
    expect(screen.getByRole('alert')).toHaveTextContent('画布不存在')
    expect(screen.getByText('canvas gone')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '返回画布库' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '重试' })).not.toBeInTheDocument()
  })

  it('renders the load-failed state and retries on demand', async () => {
    const user = userEvent.setup()
    const { refetch } = renderEditor({
      query: { isError: true, error: { message: 'network down' } },
    })
    expect(screen.getByRole('alert')).toHaveTextContent('画布加载失败')
    expect(screen.getByText('network down')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '重试' }))
    expect(refetch).toHaveBeenCalledTimes(1)
  })

  it('navigates back to the library from an error state', async () => {
    const user = userEvent.setup()
    renderEditor({
      query: { isError: true, error: { status: 404, message: 'canvas gone' } },
      withLibraryRoute: true,
    })
    await user.click(screen.getByRole('button', { name: '返回画布库' }))
    expect(await screen.findByTestId('library')).toBeInTheDocument()
  })

  it('renders the loaded document and toggles the thread panel', async () => {
    const user = userEvent.setup()
    const { openThread, collapseThread, rerenderWith } = renderEditor({
      snapshot: snapshotDTO(),
      query: { isLoading: false },
    })
    expect(screen.getByLabelText('Canvas 资源编辑器')).toBeInTheDocument()
    expect(screen.getByText('Board')).toBeInTheDocument()
    expect(screen.getByText('已保存')).toBeInTheDocument()
    expect(screen.getByText('v3')).toBeInTheDocument()
    expect(screen.getByTestId('canvas-stage')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '返回画布库' })).toHaveAttribute('href', '/canvas')

    const toggle = screen.getByRole('button', { name: '切换对话面板' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    await user.click(toggle)
    expect(openThread).toHaveBeenCalledTimes(1)

    rerenderWith({ threadOpen: true })
    expect(screen.getByRole('button', { name: '切换对话面板' })).toHaveAttribute(
      'aria-expanded',
      'true',
    )
    await user.click(screen.getByRole('button', { name: '切换对话面板' }))
    expect(collapseThread).toHaveBeenCalledTimes(1)

    rerenderWith({ commandPending: true })
    expect(screen.getByText('保存中…')).toBeInTheDocument()
  })
})
