import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createRef, type ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { CanvasAddMenu } from '@/features/canvas/agent/CanvasAddMenu'
import { CanvasAgentDock } from '@/features/canvas/agent/CanvasAgentDock'
import { CanvasAgentThread } from '@/features/canvas/agent/CanvasAgentThread'
import { CanvasToolRail } from '@/features/canvas/CanvasToolRail'
import {
  CanvasRuntimeContext,
  useCanvasRuntime,
} from '@/features/canvas/CanvasRuntimeContext'
import type { CanvasLocalState } from '@/features/canvas/types'
import type { CanvasController } from '@/features/canvas/useCanvasController'
import type { CanvasSnapshotDTO } from '@/shared/api/contracts/studio'
import { agentService } from '@/shared/api/agent-service'
import { environmentService } from '@/shared/api/environment-service'
import { harnessService } from '@/shared/api/harness-service'
import type {
  HarnessSessionEntryDTO,
  HarnessThreadDTO,
  HarnessThreadSnapshotDTO,
} from '@/shared/api/contracts/ai-runtime'
import { setLocale } from '@/shared/i18n'

const CANVAS_ID = '8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f'
const THREAD_ID = 'a1b2c3d4-5e6f-4a7b-8c9d-0e1f2a3b4c5d'

const { fakeApplicationEvents } = vi.hoisted(() => {
  const manager = { subscribe: vi.fn(() => () => undefined) }
  return { fakeApplicationEvents: { useApplicationEvents: () => manager } }
})

vi.mock('@/shared/app-events', () => ({
  useApplicationEvents: fakeApplicationEvents.useApplicationEvents,
}))
vi.mock('@/shared/api/agent-service', () => ({
  agentService: {
    listAgents: vi.fn(),
    listModels: vi.fn(),
    listProviders: vi.fn(),
  },
}))
vi.mock('@/shared/api/environment-service', () => ({
  environmentService: {
    listEnvironments: vi.fn(),
  },
}))
vi.mock('@/shared/api/harness-service', () => ({
  harnessService: {
    acceptCommandBatch: vi.fn(),
    listSessionThreads: vi.fn(),
    listSessionEntries: vi.fn(),
    getThreadSnapshot: vi.fn(),
    compactThread: vi.fn(),
    setThreadYolo: vi.fn(),
    stopThread: vi.fn(),
    decideApproval: vi.fn(),
  },
}))

const assistantAgent = {
  name: 'assistant',
  description: null,
  systemPrompt: null,
  model: 'minimax/MiniMax',
  variant: 'default',
  config: { inheritParentEnvironment: true, tools: [], skills: [], subagents: [] },
  version: '0',
  createTime: null,
  updateTime: null,
}

const model = {
  providerName: 'minimax',
  name: 'MiniMax',
  description: null,
  config: {
    limit: { context: 100000, output: 1000 },
    abilities: { tools: true, reasoning: false, inputModalities: ['TEXT'] },
    pricing: {
      currency: 'CNY',
      pricingTier: 't1',
      serviceTier: 'standard',
      serviceTierMultiplier: 1,
      inputPerMillionTokens: 1,
      outputPerMillionTokens: 2,
      cacheReadPerMillionTokens: 0,
      cacheWritePerMillionTokens: 0,
      cacheWriteLongPerMillionTokens: 0,
      reasoningPerMillionTokens: 0,
      version: '0',
    },
    defaultVariant: 'default',
    variants: [{ id: 'default' }],
  },
  version: '0',
  createTime: null,
  updateTime: null,
}

function threadFixture(overrides: Partial<HarnessThreadDTO> = {}): HarnessThreadDTO {
  return {
    /** Thread 名称（服务端权威必填非空）。 */
    name: 'thread-name',
    threadId: THREAD_ID,
    sessionId: 's1',
    headEntryId: 'h1',
    yoloEnabled: false,
    nextCommandSequence: '1',
    version: '0',
    status: 'IDLE',
    processing: false,
    branchSettings: {
      agentName: 'assistant',
      model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
      environmentName: null,
    },
    createTime: null,
    updateTime: null,
    ...overrides,
  }
}

function snapshotOf(
  currentThread: HarnessThreadDTO = threadFixture(),
  overrides: Partial<HarnessThreadSnapshotDTO> = {},
): HarnessThreadSnapshotDTO {
  return {
    version: currentThread.version,
    thread: currentThread,
    entries: [],
    queuedCommands: [],
    modelInvocation: null,
    toolInvocations: [],
    modelAttemptFailures: [],
    manualCompaction: { available: false, disabledReason: 'not available' },
    ...overrides,
  }
}

function canvasSnapshot(): CanvasSnapshotDTO {
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
  }
}

const INITIAL_STATE: CanvasLocalState = {
  view: 'editor',
  canvasId: CANVAS_ID,
  selectedIds: [],
  selectedLinks: [],
  positionDrafts: {},
  viewport: { x: 0, y: 0, zoom: 1 },
  toast: null,
  addMenuOpen: false,
  addMenuIndex: 0,
  threadOpen: false,
  uploadProgress: {},
  commandPending: false,
  conflictMessage: null,
  textEditor: null,
}

beforeEach(() => {
  localStorage.clear()
  setLocale('zh-CN')
  vi.clearAllMocks()
  vi.mocked(agentService.listAgents).mockResolvedValue({
    pageNumber: 1,
    pageSize: 50,
    totalCount: 1,
    results: [assistantAgent],
  })
  vi.mocked(agentService.listModels).mockResolvedValue({
    pageNumber: 1,
    pageSize: 50,
    totalCount: 1,
    results: [model],
  })
  vi.mocked(environmentService.listEnvironments).mockResolvedValue([])
  vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshotOf())
  vi.mocked(harnessService.acceptCommandBatch).mockResolvedValue(acceptedResponse())
  vi.mocked(harnessService.listSessionEntries).mockResolvedValue([])
  vi.mocked(harnessService.setThreadYolo).mockImplementation((threadId, data) =>
    Promise.resolve(threadFixture({ threadId, yoloEnabled: data.yoloEnabled, version: '1' })),
  )
})

describe('Canvas add menu', () => {
  it('supports keyboard navigation and dispatches non-file actions', async () => {
    const user = userEvent.setup()
    const harness = renderHarness(
      <CanvasAddMenu menuId="canvas-add-menu" />,
      { addMenuOpen: true, addMenuIndex: 0 },
    )
    const menu = screen.getByRole('menu')
    expect(screen.getByRole('menuitem', { name: /图片资源/ })).toHaveFocus()

    fireEvent.keyDown(menu, { key: 'ArrowDown' })
    expect(harness.controller.setAddMenuIndex).toHaveBeenLastCalledWith(1)
    fireEvent.keyDown(menu, { key: 'End' })
    expect(harness.controller.setAddMenuIndex).toHaveBeenLastCalledWith(5)
    fireEvent.keyDown(menu, { key: 'Escape' })
    expect(harness.controller.closeAddMenu).toHaveBeenCalled()

    harness.rerender({ addMenuOpen: true, addMenuIndex: 3 })
    fireEvent.keyDown(screen.getByRole('menu'), { key: 'Enter' })
    expect(harness.controller.handleAddAction).toHaveBeenCalledWith('text-resource')

    await user.hover(screen.getByRole('menuitem', { name: /视频生成/ }))
    expect(harness.controller.setAddMenuIndex).toHaveBeenCalledWith(5)
  })

  it('opens the correct file picker and ignores empty selections', async () => {
    const user = userEvent.setup()
    const inputClick = vi.spyOn(HTMLInputElement.prototype, 'click')
    const harness = renderHarness(
      <CanvasAddMenu menuId="canvas-add-menu" />,
      { addMenuOpen: true },
    )
    const input = screen.getByRole('menu').querySelector('input[type="file"]') as HTMLInputElement

    await user.click(screen.getByRole('menuitem', { name: /视频资源/ }))
    expect(inputClick).toHaveBeenCalled()
    const file = new File(['video'], 'clip.mp4', { type: 'video/mp4' })
    fireEvent.change(input, { target: { files: [file] } })
    expect(harness.controller.uploadFiles).toHaveBeenCalledWith([file])
    vi.mocked(harness.controller.uploadFiles).mockClear()
    fireEvent.change(input, { target: { files: [] } })
    expect(harness.controller.uploadFiles).not.toHaveBeenCalled()
    inputClick.mockRestore()
  })
})

describe('Canvas shared AgentPane', () => {
  it('sends NEW_SESSION as one command-batches request and persists the bound target', async () => {
    const user = userEvent.setup()
    renderHarness(<CanvasAgentThread />, { threadOpen: true })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await user.type(composer, 'start canvas thread')
    await user.click(screen.getByRole('button', { name: '发送消息' }))

    await waitFor(() => expect(harnessService.acceptCommandBatch).toHaveBeenCalledTimes(1))
    const [request] = vi.mocked(harnessService.acceptCommandBatch).mock.calls[0]!
    expect(request.target.type).toBe('NEW_SESSION')
    expect(request.target).not.toHaveProperty('kind')
    expect(request.commands).toHaveLength(1)
    expect(request.commands[0]?.type).toBe('USER_MESSAGE')
    await waitFor(() =>
      expect(localStorage.getItem(
        `kk-studio.agent-pane-target.CANVAS:${CANVAS_ID}:canvas-agent`,
      )).toContain('BOUND_THREAD'),
    )
  })

  it('uses the persisted BOUND_THREAD target, keeps the target stable, and exposes debug/compact state', async () => {
    const user = userEvent.setup()
    localStorage.setItem(
      `kk-studio.agent-pane-target.CANVAS:${CANVAS_ID}:canvas-agent`,
      JSON.stringify({ kind: 'BOUND_THREAD', threadId: THREAD_ID }),
    )
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(
      snapshotOf(threadFixture(), {
        manualCompaction: { available: false, disabledReason: 'busy' },
      }),
    )
    renderHarness(<CanvasAgentThread />, { threadOpen: true })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await waitFor(() => expect(harnessService.getThreadSnapshot).toHaveBeenCalledWith(THREAD_ID))

    await user.click(composer)
    await user.keyboard('/')
    expect(screen.getByRole('option', { name: /^debug/ })).toHaveAttribute('aria-disabled', 'false')
    expect(screen.getByRole('option', { name: /^compact/ })).toHaveAttribute('aria-disabled', 'true')
    await user.keyboard('{Escape}')
    await user.type(composer, 'continue')
    await user.click(screen.getByRole('button', { name: '发送消息' }))

    await waitFor(() => expect(harnessService.acceptCommandBatch).toHaveBeenCalledTimes(1))
    const [request] = vi.mocked(harnessService.acceptCommandBatch).mock.calls[0]!
    expect(request.target).toMatchObject({
      type: 'THREAD',
      threadId: THREAD_ID,
      expectedHeadEntryId: 'h1',
      expectedNextCommandSequence: '1',
    })
  })
})

describe('Canvas agent panel', () => {
  it('renders one collapse action only while the panel is open', async () => {
    const user = userEvent.setup()
    const harness = renderHarness(<CanvasAgentDock />, { threadOpen: true })

    expect(screen.getByLabelText('Canvas 对话面板')).toBeInTheDocument()
    const collapse = screen.getAllByRole('button', { name: '收起对话面板' })
    expect(collapse).toHaveLength(1)
    await user.click(collapse[0]!)
    expect(harness.controller.collapseThread).toHaveBeenCalledOnce()

    harness.rerender({ threadOpen: false })
    expect(screen.queryByLabelText('Canvas 对话面板')).not.toBeInTheDocument()
  })

  it('resizes from the left edge with pointer and keyboard input', () => {
    const originalWidth = window.innerWidth
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1_400 })
    const harness = renderHarness(<CanvasAgentDock />, { threadOpen: true })
    const panel = screen.getByLabelText('Canvas 对话面板')
    const resize = screen.getByRole('separator', { name: '调整对话面板宽度' })

    expect(panel).toHaveStyle({ '--agent-panel-width': '480px' })
    fireEvent.pointerDown(resize, { pointerId: 1, clientX: 800 })
    fireEvent.pointerMove(resize, { pointerId: 1, clientX: 720 })
    fireEvent.pointerUp(resize, { pointerId: 1, clientX: 720 })
    expect(panel).toHaveStyle({ '--agent-panel-width': '560px' })
    fireEvent.keyDown(resize, { key: 'Home' })
    expect(panel).toHaveStyle({ '--agent-panel-width': '360px' })
    fireEvent.keyDown(resize, { key: 'End' })
    expect(panel).toHaveStyle({ '--agent-panel-width': '720px' })
    expect(localStorage.getItem('kkstudio.canvas.chat-panel-width.v1')).toBe('720')
    harness.rerender({ threadOpen: false })
    expect(screen.queryByRole('separator', { name: '调整对话面板宽度' })).not.toBeInTheDocument()
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: originalWidth })
  })
})

describe('Canvas tool rail', () => {
  it('separates the centered add launcher from zoom controls', async () => {
    const user = userEvent.setup()
    const fitAll = vi.fn()
    const zoomTo = vi.fn()
    const harness = renderHarness(<CanvasToolRail />, {}, {
      fitViewRef: { current: fitAll },
      zoomRef: { current: zoomTo },
    })
    const rail = document.querySelector('.canvas-tool-rail') as HTMLElement
    expect(rail.querySelector('.canvas-plus-icon')).not.toBeNull()
    expect(document.querySelector('.canvas-zoom-controls')).not.toBeNull()
    await user.click(screen.getByRole('button', { name: /添加资源/ }))
    expect(harness.controller.toggleAddMenu).toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: '适应全部内容' }))
    expect(fitAll).toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: '缩小' }))
    expect(zoomTo).toHaveBeenCalledWith(expect.any(Number))
  })

  it('keeps add-menu keyboard navigation reachable from the rail launcher', () => {
    const harness = renderHarness(<CanvasToolRail />, { addMenuOpen: true, addMenuIndex: 0 })
    const menu = screen.getByRole('menu')
    expect(screen.getByRole('menuitem', { name: /图片资源/ })).toHaveFocus()
    fireEvent.keyDown(menu, { key: 'ArrowDown' })
    expect(harness.controller.setAddMenuIndex).toHaveBeenCalledWith(1)
  })
})

describe('Canvas runtime context boundary', () => {
  it('fails closed outside CanvasRuntimeProvider', () => {
    function MissingProviderConsumer() {
      useCanvasRuntime()
      return null
    }
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    expect(() => render(<MissingProviderConsumer />)).toThrow('CanvasRuntimeProvider is required')
    consoleError.mockRestore()
  })
})

function acceptedResponse(thread = threadFixture()) {
  const rootEntry: HarnessSessionEntryDTO = {
    entryId: 'root-entry',
    sessionId: thread.sessionId,
    parentEntryId: null,
    entryType: 'ROOT',
    payloadJson: '{}',
    createTime: null,
  }
  return {
    session: { sessionId: thread.sessionId, createdAt: null },
    rootEntry,
    thread,
    acceptedCommands: [],
    replayed: false,
  }
}

function renderHarness(
  children: ReactNode,
  initialState: Partial<CanvasLocalState> = {},
  extraController: Record<string, unknown> = {},
) {
  const dockAddRef = createRef<HTMLButtonElement>()
  const actions = {
    closeAddMenu: vi.fn(),
    setAddMenuIndex: vi.fn(),
    handleAddAction: vi.fn(),
    uploadFiles: vi.fn(async () => undefined),
    toggleAddMenu: vi.fn(),
    collapseThread: vi.fn(),
  }
  let state = { ...INITIAL_STATE, ...initialState } as CanvasLocalState
  const snapshotValue = canvasSnapshot()
  const controller = {
    state,
    snapshot: snapshotValue,
    dockAddRef,
    ...actions,
    ...extraController,
  } as unknown as CanvasController
  const value = () => ({
    ...controller,
    state,
    snapshot: snapshotValue,
  }) as CanvasController
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const view = render(
    <QueryClientProvider client={client}>
      <CanvasRuntimeContext.Provider value={value()}>
        {children}
      </CanvasRuntimeContext.Provider>
    </QueryClientProvider>,
  )
  return {
    controller,
    rerender(patch: Partial<CanvasLocalState>) {
      state = { ...state, ...patch } as CanvasLocalState
      view.rerender(
        <QueryClientProvider client={client}>
          <CanvasRuntimeContext.Provider value={value()}>
            {children}
          </CanvasRuntimeContext.Provider>
        </QueryClientProvider>,
      )
    },
  }
}
