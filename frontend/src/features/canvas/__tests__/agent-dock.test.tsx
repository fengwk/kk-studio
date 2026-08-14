import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
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
import type { CanvasSnapshotDTO, CanvasThreadFirstSendRequestDTO, UUIDString } from '@/shared/api/contracts/studio'
import { agentService } from '@/shared/api/agent-service'
import { environmentService } from '@/shared/api/environment-service'
import { harnessService } from '@/shared/api/harness-service'
import type {
  HarnessBranchSettingsDTO,
  HarnessThreadDTO,
  HarnessThreadSnapshotDTO,
} from '@/shared/api/contracts/ai-runtime'
import { setLocale } from '@/shared/i18n'

const CANVAS_ID = '8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f'
const THREAD_ID = 'a1b2c3d4-5e6f-4a7b-8c9d-0e1f2a3b4c5d'

const { sendCanvasThreadFirstSend } = vi.hoisted(() => ({
  sendCanvasThreadFirstSend: vi.fn(),
}))

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
    getThreadSnapshot: vi.fn(),
    enqueueCommands: vi.fn(),
    updateThreadHead: vi.fn(),
    stopThread: vi.fn(),
    decideApproval: vi.fn(),
  },
}))
vi.mock('@/shared/api/studio-service', () => ({
  sendCanvasThreadFirstSend,
}))

const assistantAgent = {
  name: 'assistant',
  description: null,
  systemPrompt: null,
  model: 'minimax/MiniMax',
  variant: 'default',
  config: { tools: [], skills: [], subagents: [] },
  version: '0',
  createTime: null,
  updateTime: null,
}

const threadFixture: HarnessThreadDTO = {
  threadId: THREAD_ID,
  sessionId: 's1',
  headEntryId: 'h1',
  yoloEnabled: false,
  nextCommandSequence: '1',
  revision: '0',
  status: 'IDLE',
  processing: false,
  branchSettings: {
    environmentName: null,
    agentName: 'assistant',
    model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
    activeTools: [],
  } as HarnessBranchSettingsDTO,
  createTime: null,
  updateTime: null,
}

function threadSnapshot(): HarnessThreadSnapshotDTO {
  return {
    revision: threadFixture.revision,
    thread: threadFixture,
    entries: [],
    queuedCommands: [],
    modelInvocation: null,
    toolInvocations: [],
    modelAttemptFailures: [],
  }
}

function canvasSnapshot(threadId: UUIDString | null): CanvasSnapshotDTO {
  return {
    document: {
      id: CANVAS_ID,
      title: 'Board',
      version: '3',
      threadId,
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
    results: [assistantAgent],
    total: 1,
  })
  vi.mocked(agentService.listModels).mockResolvedValue({
    results: [{
      providerName: 'minimax',
      name: 'MiniMax',
      description: null,
      config: {
        limit: { context: 100000, output: 1000 },
        abilities: { tools: true, reasoning: false, inputModalities: ['TEXT'] },
        pricing: {
          currency: 'CNY', pricingTier: 't1', serviceTier: 'standard', serviceTierMultiplier: 1,
          inputPerMillionTokens: 1, outputPerMillionTokens: 2, cacheReadPerMillionTokens: 0,
          cacheWritePerMillionTokens: 0, cacheWriteLongPerMillionTokens: 0, reasoningPerMillionTokens: 0,
          version: '0',
        },
        defaultVariant: 'default',
        variants: [{ id: 'default' }],
      },
      version: '0',
      createTime: null,
      updateTime: null,
    }],
    total: 1,
  })
  vi.mocked(environmentService.listEnvironments).mockResolvedValue([])
  vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(threadSnapshot())
})

describe('Canvas add menu', () => {
  it('supports all keyboard navigation and dispatches non-file actions', async () => {
    const user = userEvent.setup()
    const harness = renderHarness(
      <CanvasAddMenu menuId="canvas-add-menu" />,
      { addMenuOpen: true, addMenuIndex: 0 },
    )
    const menu = screen.getByRole('menu')
    expect(screen.getByRole('menuitem', { name: /图片资源/ })).toHaveFocus()

    fireEvent.keyDown(menu, { key: 'ArrowDown' })
    expect(harness.controller.setAddMenuIndex).toHaveBeenLastCalledWith(1)
    fireEvent.keyDown(menu, { key: 'ArrowUp' })
    expect(harness.controller.setAddMenuIndex).toHaveBeenLastCalledWith(5)
    fireEvent.keyDown(menu, { key: 'Home' })
    expect(harness.controller.setAddMenuIndex).toHaveBeenLastCalledWith(0)
    fireEvent.keyDown(menu, { key: 'End' })
    expect(harness.controller.setAddMenuIndex).toHaveBeenLastCalledWith(5)
    fireEvent.keyDown(menu, { key: 'Escape' })
    expect(harness.controller.closeAddMenu).toHaveBeenCalled()

    harness.rerender({ addMenuOpen: true, addMenuIndex: 3 })
    fireEvent.keyDown(screen.getByRole('menu'), { key: 'Enter' })
    expect(harness.controller.handleAddAction).toHaveBeenCalledWith('text-resource')

    await user.hover(screen.getByRole('menuitem', { name: /视频生成/ }))
    expect(harness.controller.setAddMenuIndex).toHaveBeenCalledWith(5)
    expect(screen.queryByRole('menuitem', { name: /分组/ })).not.toBeInTheDocument()

    harness.rerender({ addMenuOpen: true, addMenuIndex: 99 })
    fireEvent.keyDown(screen.getByRole('menu'), { key: 'Enter' })
    expect(harness.controller.handleAddAction).toHaveBeenCalledTimes(1)
  })

  it('opens the correct file picker and uploads only non-empty selections', async () => {
    const user = userEvent.setup()
    const inputClick = vi.spyOn(HTMLInputElement.prototype, 'click')
    const harness = renderHarness(
      <CanvasAddMenu menuId="canvas-add-menu" />,
      { addMenuOpen: true },
    )
    const container = screen.getByRole('menu')
    const input = container.querySelector('input[type="file"]') as HTMLInputElement

    await user.click(screen.getByRole('menuitem', { name: /视频资源/ }))
    expect(inputClick).toHaveBeenCalled()
    expect(input.accept).toBe('video/mp4,video/quicktime')

    const file = new File(['video'], 'clip.mp4', { type: 'video/mp4' })
    fireEvent.change(input, { target: { files: [file] } })
    expect(harness.controller.uploadFiles).toHaveBeenCalledWith([file])
    expect(harness.controller.closeAddMenu).toHaveBeenCalled()

    vi.mocked(harness.controller.uploadFiles).mockClear()
    fireEvent.change(input, { target: { files: [] } })
    expect(harness.controller.uploadFiles).not.toHaveBeenCalled()
    inputClick.mockRestore()
  })

  it('keeps the menu inert while closed', () => {
    renderHarness(<CanvasAddMenu menuId="canvas-add-menu" />)
    const menu = screen.getByRole('menu', { hidden: true })
    expect(menu).toHaveAttribute('aria-hidden', 'true')
    expect(within(menu).getAllByRole('menuitem', { hidden: true })[0]).toHaveAttribute('tabindex', '-1')
  })

  it('localizes every visible menu item in English', () => {
    setLocale('en-US')
    renderHarness(<CanvasAddMenu menuId="canvas-add-menu" />, { addMenuOpen: true })

    expect(screen.getByRole('menuitem', { name: /Image resource/ })).toBeInTheDocument()
    expect(screen.getByRole('menuitem', { name: /Video generation/ })).toBeInTheDocument()
    expect(screen.queryByText('图片资源')).not.toBeInTheDocument()
  })
})

describe('Canvas blank thread', () => {
  // 空 Thread：共享 Attachment Pill Composer + compact Agent/Environment/YOLO
  // 选择；首次发送走 canvas-scoped 原子端点，成功后绑定返回的 document。
  it('materializes a compact draft and sends the atomic first message', async () => {
    const user = userEvent.setup()
    const boundDocument = canvasSnapshot(THREAD_ID).document
    vi.mocked(sendCanvasThreadFirstSend).mockResolvedValue({
      threadId: THREAD_ID,
      document: boundDocument,
    })
    const harness = renderHarness(<CanvasAgentThread />, { threadOpen: true })

    // compact footer 展示 agent/model 选择；composer 为共享 Attachment Pill Composer。
    await waitFor(() => expect(screen.getByRole('button', { name: 'agent:assistant' })).toBeInTheDocument())
    expect(screen.getByText('minimax/MiniMax · default')).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: /消息|Message/i })).toBeInTheDocument()

    await user.type(screen.getByRole('textbox', { name: /消息|Message/i }), '请检查这张画布')
    await user.click(screen.getByRole('button', { name: /发送|Send/i }))

    await waitFor(() => {
      expect(sendCanvasThreadFirstSend).toHaveBeenCalledWith(CANVAS_ID, expect.objectContaining<CanvasThreadFirstSendRequestDTO>({
        commandId: expect.stringMatching(/^[0-9a-f-]{36}$/i),
        yoloEnabled: false,
        branchSettings: {
          environmentName: null,
          agentName: 'assistant',
          model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
          activeTools: [],
        },
        contents: [{ type: 'TEXT', text: '请检查这张画布' }],
      }))
    })
    expect(harness.controller.bindThreadDocument).toHaveBeenCalledWith(boundDocument)
  })

  it('keeps the composer blocked and opens the agent picker when no agent is resolvable', async () => {
    const user = userEvent.setup()
    vi.mocked(agentService.listAgents).mockResolvedValue({ results: [], total: 0 })
    renderHarness(<CanvasAgentThread />, { threadOpen: true })

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'agent:（无 Agent）' })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('button', { name: 'agent:（无 Agent）' }))
    expect(screen.getByText('暂无可用 Agent')).toBeInTheDocument()
    expect(screen.queryByText('暂无可解析的 Agent 配置，请先选择一个 Agent。')).not.toBeInTheDocument()
  })

  it('keeps /shortcuts available and /thread disabled in the canvas blank scene', async () => {
    const user = userEvent.setup()
    renderHarness(<CanvasAgentThread />, { threadOpen: true })
    const composer = await screen.findByLabelText('给 AI 发送消息')

    // canvas-blank 场景：/thread（无 Chat-scoped picker）保持禁用，/shortcuts 可用。
    await user.click(composer)
    await user.keyboard('/thread')
    const threadOption = await screen.findByRole('option', { name: /^thread/ })
    expect(threadOption.getAttribute('aria-disabled')).toBe('true')
    await user.keyboard('{Escape}')

    await user.keyboard('/shortcuts{Enter}')
    expect(await screen.findByRole('region', { name: '键盘快捷键' })).toBeInTheDocument()
    await user.keyboard('{Escape}')
    await waitFor(() =>
      expect(document.activeElement?.classList.contains('composer-editor')).toBe(true),
    )
  })
})

describe('Canvas bound thread', () => {
  // document.threadId 存在时复用真实 Harness Thread：controller 查询快照、
  // 订阅应用事件并把消息/工作状态渲染到共享 ChatPanel。
  it('renders the real thread transcript and composer without an implicit canvas switcher', async () => {
    const user = userEvent.setup()
    renderHarness(<CanvasAgentThread />, {
      threadOpen: true,
      snapshot: canvasSnapshot(THREAD_ID),
    })

    await waitFor(() => {
      expect(harnessService.getThreadSnapshot).toHaveBeenCalledWith(THREAD_ID)
    })
    await waitFor(() => {
      expect(fakeApplicationEvents.useApplicationEvents().subscribe).toHaveBeenCalledWith(
        { kind: 'thread', id: THREAD_ID },
        expect.any(Object),
      )
    })
    expect(document.querySelector('.chat-shell.thread-panel')).not.toBeNull()
    expect(screen.getByRole('textbox', { name: /消息|Message/i })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /当前选区|整张画布/ })).not.toBeInTheDocument()

    // 发送走 harness command batch（真实 Thread 的消息路径）。
    vi.mocked(harnessService.enqueueCommands).mockResolvedValue([])
    await user.type(screen.getByRole('textbox', { name: /消息|Message/i }), 'hello')
    await user.click(screen.getByRole('button', { name: /发送|Send/i }))
    await waitFor(() => {
      expect(harnessService.enqueueCommands).toHaveBeenCalledWith(
        THREAD_ID,
        expect.objectContaining({
          expectedHeadEntryId: 'h1',
          expectedNextCommandSequence: '1',
        }),
      )
    })
  })

  it('switches between /events and /conversation without losing the composer draft', async () => {
    const user = userEvent.setup()
    renderHarness(<CanvasAgentThread />, {
      threadOpen: true,
      snapshot: canvasSnapshot(THREAD_ID),
    })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await waitFor(() => expect(harnessService.getThreadSnapshot).toHaveBeenCalledWith(THREAD_ID))

    await user.type(composer, 'canvas draft')
    // 非空草稿下通过 + 菜单切换到 events 主视图。
    await user.click(screen.getByRole('button', { name: '打开命令表' }))
    await user.click(await screen.findByRole('option', { name: /^events/ }))
    await screen.findByRole('listbox', { name: '事件' })
    expect(document.querySelector('.thread-dialogue')).toBeNull()
    // 切换不丢 Composer draft：composer 保持挂载且内容不变。
    expect(composer.textContent).toBe('canvas draft')
    expect(composer.closest('.thread-composer')).not.toHaveAttribute('hidden')

    await user.click(screen.getByRole('button', { name: '打开命令表' }))
    await user.click(await screen.findByRole('option', { name: /^conversation/ }))
    await waitFor(() => expect(document.querySelector('.thread-dialogue')).not.toBeNull())
    expect(screen.queryByRole('listbox', { name: '事件' })).not.toBeInTheDocument()
    expect(composer.textContent).toBe('canvas draft')
  })

  it('opens the read-only /shortcuts panel in the canvas bound scene', async () => {
    const user = userEvent.setup()
    renderHarness(<CanvasAgentThread />, {
      threadOpen: true,
      snapshot: canvasSnapshot(THREAD_ID),
    })
    const composer = await screen.findByLabelText('给 AI 发送消息')
    await waitFor(() => expect(harnessService.getThreadSnapshot).toHaveBeenCalledWith(THREAD_ID))

    await user.click(composer)
    await user.keyboard('/shortcuts{Enter}')
    expect(await screen.findByRole('region', { name: '键盘快捷键' })).toBeInTheDocument()
    await user.keyboard('{Escape}')
    await waitFor(() =>
      expect(document.activeElement?.classList.contains('composer-editor')).toBe(true),
    )
  })
})

describe('Canvas agent panel', () => {
  it('renders one panel-level collapse action only while the conversation is open', async () => {
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
    fireEvent.pointerMove(resize, { pointerId: 99, clientX: 100 })
    expect(panel).toHaveStyle({ '--agent-panel-width': '480px' })
    fireEvent.pointerDown(resize, { pointerId: 1, clientX: 800 })
    fireEvent.pointerMove(resize, { pointerId: 1, clientX: 720 })
    fireEvent.pointerUp(resize, { pointerId: 1, clientX: 720 })
    expect(panel).toHaveStyle({ '--agent-panel-width': '560px' })

    fireEvent.keyDown(resize, { key: 'ArrowRight' })
    expect(panel).toHaveStyle({ '--agent-panel-width': '536px' })
    fireEvent.keyDown(resize, { key: 'ArrowLeft' })
    expect(panel).toHaveStyle({ '--agent-panel-width': '560px' })
    fireEvent.keyDown(resize, { key: 'Home' })
    expect(panel).toHaveStyle({ '--agent-panel-width': '360px' })
    fireEvent.keyDown(resize, { key: 'End' })
    expect(panel).toHaveStyle({ '--agent-panel-width': '720px' })
    fireEvent.keyDown(resize, { key: 'PageDown' })
    expect(panel).toHaveStyle({ '--agent-panel-width': '720px' })
    expect(localStorage.getItem('kkstudio.canvas.chat-panel-width.v1')).toBe('720')

    fireEvent.pointerDown(resize, { pointerId: 2, clientX: 800 })
    expect(document.body).toHaveClass('canvas-chat-panel-resizing')
    fireEvent.pointerCancel(resize, { pointerId: 2 })
    expect(document.body).not.toHaveClass('canvas-chat-panel-resizing')
    fireEvent.lostPointerCapture(resize, { pointerId: 2 })

    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1_024 })
    fireEvent(window, new Event('resize'))
    expect(panel).toHaveStyle({ '--agent-panel-width': '544px' })

    harness.rerender({ threadOpen: false })
    expect(screen.queryByRole('separator', { name: '调整对话面板宽度' })).not.toBeInTheDocument()
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: originalWidth })
  })
})

describe('Canvas tool rail', () => {
  it('separates the centered add launcher from bottom-left zoom controls', async () => {
    const user = userEvent.setup()
    const fitAll = vi.fn()
    const zoomTo = vi.fn()
    const harness = renderHarness(<CanvasToolRail />, {}, {
      fitViewRef: { current: fitAll },
      zoomRef: { current: zoomTo },
    })

    const rail = document.querySelector('.canvas-tool-rail') as HTMLElement
    expect(rail).not.toBeNull()
    // 专用 18px 图标类，避免命中全局 .plus-icon {44px}。
    expect(rail.querySelector('.canvas-plus-icon')).not.toBeNull()
    expect(rail.querySelector('.plus-icon')).toBeNull()
    expect(document.querySelector('.canvas-zoom-controls')).not.toBeNull()

    const add = screen.getByRole('button', { name: /添加资源/ })
    expect(add).toHaveAttribute('aria-expanded', 'false')
    expect(add.getAttribute('aria-controls')).toBeTruthy()
    await user.click(add)
    expect(harness.controller.toggleAddMenu).toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: '适应全部内容' }))
    expect(fitAll).toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: '缩小' }))
    expect(zoomTo).toHaveBeenCalledWith(expect.any(Number))
    expect(screen.queryByText('V')).not.toBeInTheDocument()
    expect(screen.queryByText('H')).not.toBeInTheDocument()
  })

  it('keeps the add menu keyboard navigation reachable from the rail launcher', () => {
    const harness = renderHarness(<CanvasToolRail />, { addMenuOpen: true, addMenuIndex: 0 })
    const menu = screen.getByRole('menu')
    expect(screen.getByRole('menuitem', { name: /图片资源/ })).toHaveFocus()
    fireEvent.keyDown(menu, { key: 'ArrowDown' })
    expect(harness.controller.setAddMenuIndex).toHaveBeenCalledWith(1)
    harness.rerender({ addMenuOpen: true, addMenuIndex: 1 })
    expect(screen.getByRole('menuitem', { name: /视频资源/ })).toHaveFocus()
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
    bindThreadDocument: vi.fn(),
  }
  let state = { ...INITIAL_STATE, ...initialState } as CanvasLocalState
  let snapshotValue = (initialState as { snapshot?: CanvasSnapshotDTO | null }).snapshot ?? canvasSnapshot(null)

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
    rerender(patch: Partial<CanvasLocalState> & { snapshot?: CanvasSnapshotDTO | null }) {
      state = { ...state, ...patch } as CanvasLocalState
      if (patch.snapshot !== undefined) {
        snapshotValue = patch.snapshot
      }
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
