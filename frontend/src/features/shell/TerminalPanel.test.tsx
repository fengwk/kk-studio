/**
 * TerminalPanel 测试。
 *
 * 覆盖：仅可见时打开权威环境 query；tabs 只允许 READY 新选、离线会话只读查看；
 * 控制资格（READY + 连接 open + 查询未失败）只降不升，末屏在离线/回读失败时保留只读；
 * 标题展示真实 executable/控制态；终止确认按控制器当前快照复验完整冻结目标；
 * 切换环境重新挂载唯一 TerminalViewport。
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { EnvironmentCardDTO } from '@/shared/api/contracts/ai-environment'
import { environmentService } from '@/shared/api/environment-service'
import type { TerminalSessionSnapshot } from '@/features/shell/terminal-controller'
import type { TerminalMirrorState } from '@/features/shell/terminal-view-mirror'
import { TerminalPanel } from '@/features/shell/TerminalPanel'
import { gridFromCapturedView } from '@/features/shell/__fixtures__/captured-view'

const { terminalApi, appEvents } = vi.hoisted(() => ({
  terminalApi: { current: null as unknown },
  appEvents: {
    handlers: null as null | {
      onSubscribed?: () => void
      onEvent?: (name: string) => void
      onResync?: () => void
    },
  },
}))

vi.mock('@/features/shell/terminal-context', () => ({
  useTerminal: () => terminalApi.current,
}))

vi.mock('@/shared/app-events', () => ({
  useApplicationEvents: () => ({
    subscribe: (_reference: unknown, handlers: unknown) => {
      appEvents.handlers = handlers as typeof appEvents.handlers
      return () => undefined
    },
  }),
}))

vi.mock('@/shared/api/environment-service', () => ({
  environmentService: { listEnvironments: vi.fn() },
}))

const READY_ID = 'aaaaaaaa-0000-4000-8000-000000000001'
const OFFLINE_ID = 'aaaaaaaa-0000-4000-8000-000000000002'

function card(overrides: Partial<EnvironmentCardDTO>): EnvironmentCardDTO {
  return {
    id: READY_ID,
    name: 'ready-env',
    status: 'READY',
    ready: true,
    statusExpiresAt: null,
    lastSeen: null,
    capabilities: [],
    version: '1',
    createTime: '2026-10-10T00:00:00Z',
    updateTime: '2026-10-10T00:00:00Z',
    ...overrides,
  }
}

function session(overrides: Partial<TerminalSessionSnapshot> = {}): TerminalSessionSnapshot {
  return {
    environmentId: READY_ID,
    identity: null,
    streamId: null,
    executable: null,
    status: null,
    exitCode: null,
    view: null,
    viewApplied: false,
    writer: null,
    hasControl: false,
    pending: false,
    notice: null,
    ...overrides,
  }
}

function writer(epoch: string): TerminalSessionSnapshot['writer'] {
  return {
    writerEpoch: epoch,
    lastWrittenSeq: 0,
    lastWrittenDigest: null,
    lastResolvedSeq: 0,
    lastResolvedDigest: null,
    lastResolvedOutcome: null,
    pendingSeq: 0,
    pendingDigest: null,
    frozen: false,
  }
}

function mirror(streamId: string): TerminalMirrorState {
  const grid = gridFromCapturedView({
    cols: 4,
    rows: 1,
    cursorX: 0,
    cursorY: 0,
    alternate: false,
    history: 0,
    lines: [{ wrapped: false, text: 'bye' }],
  })
  return {
    ...grid,
    terminalId: '11111111-1111-4111-8111-111111111111',
    streamId,
    version: 2,
    cursorVisible: true,
    cursorShape: null,
    inputModeRevision: 0,
    inputModes: {
      applicationCursor: false,
      applicationKeypad: false,
      bracketedPaste: false,
      autoNewLine: false,
      altSendsEscape: true,
      mouseMode: 'NONE',
      mouseFormat: 'XTERM',
    },
    lines: grid.lines.map((line, id) => ({ ...line, id })),
  }
}

function fakeController() {
  let snapshot: {
    visible: boolean
    activeEnvironmentId: string | null
    connectionStatus: string
    sessions: Map<string, TerminalSessionSnapshot>
  } = { visible: false, activeEnvironmentId: null, connectionStatus: 'open', sessions: new Map() }
  return {
    show: vi.fn(),
    hide: vi.fn(),
    selectEnvironment: vi.fn(),
    refresh: vi.fn(),
    claim: vi.fn(),
    takeover: vi.fn(),
    release: vi.fn(),
    restart: vi.fn(),
    terminate: vi.fn(),
    sendInput: vi.fn(() => true),
    resize: vi.fn(() => true),
    applied: vi.fn(),
    getSnapshot: () => snapshot,
    setSnapshot(next: typeof snapshot) {
      snapshot = next
    },
  }
}

function setTerminal(
  options: {
    visible: boolean
    sessions?: Map<string, TerminalSessionSnapshot>
    activeEnvironmentId?: string | null
    connectionStatus?: 'open' | 'closed' | 'connecting'
  },
  controller: ReturnType<typeof fakeController> = fakeController(),
) {
  const snapshot = {
    visible: options.visible,
    activeEnvironmentId: options.activeEnvironmentId ?? null,
    connectionStatus: options.connectionStatus ?? 'open',
    sessions: options.sessions ?? new Map<string, TerminalSessionSnapshot>(),
  }
  controller.setSnapshot(snapshot)
  terminalApi.current = {
    controller,
    snapshot,
    show: vi.fn(),
    hide: vi.fn(),
  }
  return controller
}

function renderPanel() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const tree = () => (
    <QueryClientProvider client={client}>
      <TerminalPanel />
    </QueryClientProvider>
  )
  const view = render(tree())
  // 每次重建元素，避免 React 因 element 引用相同而跳过重渲染。
  return { ...view, rerenderPanel: () => view.rerender(tree()) }
}

describe('TerminalPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    appEvents.handlers = null
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([])
  })

  afterEach(() => {
    terminalApi.current = null
  })

  it('renders nothing and does not query while hidden', () => {
    setTerminal({ visible: false })
    renderPanel()

    expect(screen.queryByTestId('terminal-panel')).toBeNull()
    expect(environmentService.listEnvironments).not.toHaveBeenCalled()
  })

  it('prompts when visible without any configured environment', async () => {
    setTerminal({ visible: true })
    renderPanel()

    expect(await screen.findByText('未配置环境')).toBeInTheDocument()
    expect(environmentService.listEnvironments).toHaveBeenCalledTimes(1)
  })

  it('enables READY tabs and disables offline tabs without an existing session', async () => {
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      card({ id: READY_ID, name: 'ready-env' }),
      card({ id: OFFLINE_ID, name: 'offline-env', status: 'OFFLINE', ready: false }),
    ])
    setTerminal({ visible: true })
    renderPanel()

    expect(await screen.findByRole('tab', { name: 'ready-env' })).toBeEnabled()
    expect(screen.getByRole('tab', { name: 'offline-env' })).toBeDisabled()
  })

  it('keeps an offline environment with an existing session selectable for read-only view', async () => {
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      card({ id: OFFLINE_ID, name: 'offline-env', status: 'OFFLINE', ready: false }),
    ])
    const offlineSession = session({
      environmentId: OFFLINE_ID,
      status: 'EXITED',
      streamId: 'cccccccc-0000-4000-8000-000000000001',
      view: mirror('cccccccc-0000-4000-8000-000000000001'),
    })
    setTerminal({
      visible: true,
      sessions: new Map([[OFFLINE_ID, offlineSession]]),
      activeEnvironmentId: OFFLINE_ID,
    })
    renderPanel()

    expect(await screen.findByRole('tab', { name: 'offline-env' })).toBeEnabled()
    expect(document.querySelector('.terminal-viewport')).not.toBeNull()
  })

  it('selects an environment through the controller when a tab is chosen', async () => {
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      card({ id: READY_ID, name: 'ready-env' }),
    ])
    const controller = setTerminal({ visible: true })
    renderPanel()

    fireEvent.click(await screen.findByRole('tab', { name: 'ready-env' }))
    expect(controller.selectEnvironment).toHaveBeenCalledWith(READY_ID)
  })

  it('shows executable/status/control and claims control when observing', async () => {
    const observing = session({
      status: 'RUNNING',
      executable: '/bin/bash',
      identity: { daemonInstanceId: 'd', terminalId: 't' },
      streamId: 'stream',
      viewApplied: true,
      hasControl: false,
    })
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([card({ id: READY_ID })])
    const controller = setTerminal({
      visible: true,
      sessions: new Map([[READY_ID, observing]]),
      activeEnvironmentId: READY_ID,
    })
    renderPanel()

    expect(await screen.findByText(/\/bin\/bash/)).toBeInTheDocument()
    expect(screen.getByText(/观察中/)).toBeInTheDocument()
    await screen.findByRole('tab', { name: 'ready-env' })
    fireEvent.click(screen.getByRole('button', { name: '获取控制' }))
    expect(controller.claim).toHaveBeenCalledTimes(1)
  })

  it('confirms terminate before calling the controller', async () => {
    const running = session({
      status: 'RUNNING',
      executable: '/bin/bash',
      identity: { daemonInstanceId: 'd', terminalId: 't' },
      streamId: 'stream',
      viewApplied: true,
      hasControl: true,
    })
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([card({ id: READY_ID })])
    const controller = setTerminal({
      visible: true,
      sessions: new Map([[READY_ID, running]]),
      activeEnvironmentId: READY_ID,
    })
    renderPanel()

    fireEvent.click(await screen.findByRole('button', { name: '终止终端' }))
    expect(controller.terminate).not.toHaveBeenCalled()
    fireEvent.click(await screen.findByRole('button', { name: '确认终止' }))

    await waitFor(() => expect(controller.terminate).toHaveBeenCalledTimes(1))
  })

  it('disables session action buttons while pending', async () => {
    const pendingSession = session({
      status: 'RUNNING',
      identity: { daemonInstanceId: 'd', terminalId: 't' },
      streamId: 'stream',
      viewApplied: true,
      hasControl: true,
      pending: true,
    })
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([card({ id: READY_ID })])
    setTerminal({
      visible: true,
      sessions: new Map([[READY_ID, pendingSession]]),
      activeEnvironmentId: READY_ID,
    })
    renderPanel()

    await screen.findByRole('tab', { name: 'ready-env' })
    expect(await screen.findByRole('button', { name: '释放控制' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '刷新并重新同步' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '终止终端' })).toBeDisabled()
  })

  it('surfaces a user-facing notice without internal terminology', async () => {
    const noticed = session({
      status: 'RUNNING',
      identity: { daemonInstanceId: 'd', terminalId: 't' },
      streamId: 'stream',
      viewApplied: true,
      hasControl: true,
      notice: 'backpressure',
    })
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([card({ id: READY_ID })])
    setTerminal({
      visible: true,
      sessions: new Map([[READY_ID, noticed]]),
      activeEnvironmentId: READY_ID,
    })
    renderPanel()

    const notice = await screen.findByRole('status')
    expect(notice.textContent).not.toContain('背压')
    expect(notice.textContent).toBeTruthy()
  })

  it('shows a loading state instead of an empty state', async () => {
    vi.mocked(environmentService.listEnvironments).mockReturnValue(new Promise(() => undefined))
    setTerminal({ visible: true })
    renderPanel()

    expect(await screen.findByText('正在加载环境…')).toBeInTheDocument()
    expect(screen.queryByText('未配置环境')).toBeNull()
  })

  it('shows the fixed error copy without echoing the message and keeps the empty state honest', async () => {
    vi.mocked(environmentService.listEnvironments).mockRejectedValue(new Error('env boom payload'))
    setTerminal({ visible: true })
    renderPanel()

    expect(await screen.findByText('环境列表加载失败，请重试')).toBeInTheDocument()
    expect(screen.queryByText(/env boom payload/)).toBeNull()
    expect(screen.queryByText('未配置环境')).toBeNull()
  })

  it('hides control actions for an offline environment', async () => {
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      card({ id: OFFLINE_ID, name: 'offline-env', status: 'OFFLINE', ready: false }),
    ])
    const running = session({
      environmentId: OFFLINE_ID,
      status: 'RUNNING',
      identity: { daemonInstanceId: 'd', terminalId: 't1' },
      streamId: 'stream',
      viewApplied: true,
      hasControl: false,
    })
    setTerminal({
      visible: true,
      sessions: new Map([[OFFLINE_ID, running]]),
      activeEnvironmentId: OFFLINE_ID,
    })
    renderPanel()

    await screen.findByRole('tab', { name: 'offline-env' })
    expect(screen.queryByRole('button', { name: '获取控制' })).toBeNull()
    expect(screen.queryByRole('button', { name: '接管控制' })).toBeNull()
    expect(screen.queryByRole('button', { name: '释放控制' })).toBeNull()
    expect(screen.queryByRole('button', { name: '终止终端' })).toBeNull()
    // 只读刷新与收起仍可用。
    expect(screen.getByRole('button', { name: '刷新并重新同步' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '收起终端' })).toBeInTheDocument()
  })

  it('renders an offline session with stale control as read-only observing, keeping its last screen', async () => {
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      card({ id: OFFLINE_ID, name: 'offline-env', status: 'OFFLINE', ready: false }),
    ])
    const staleControl = session({
      environmentId: OFFLINE_ID,
      status: 'RUNNING',
      executable: '/bin/bash',
      identity: { daemonInstanceId: 'd', terminalId: 't1' },
      streamId: 'stream',
      view: mirror('stream'),
      viewApplied: true,
      hasControl: true,
    })
    setTerminal({
      visible: true,
      sessions: new Map([[OFFLINE_ID, staleControl]]),
      activeEnvironmentId: OFFLINE_ID,
    })
    renderPanel()

    await screen.findByRole('tab', { name: 'offline-env' })
    // 控制资格不足：末屏保留为只读，标题不得再声称控制中，释放/终止一律隐藏。
    const viewport = document.querySelector('.terminal-viewport')
    expect(viewport).not.toBeNull()
    expect(viewport?.getAttribute('data-readonly')).toBe('true')
    expect(screen.getByText(/观察中/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '释放控制' })).toBeNull()
    expect(screen.queryByRole('button', { name: '终止终端' })).toBeNull()
  })

  it('treats a closed application event connection as no control even with a READY card', async () => {
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([card({ id: READY_ID })])
    const controlled = session({
      status: 'RUNNING',
      executable: '/bin/bash',
      identity: { daemonInstanceId: 'd', terminalId: 't1' },
      streamId: 'stream',
      view: mirror('stream'),
      viewApplied: true,
      hasControl: true,
    })
    setTerminal({
      visible: true,
      sessions: new Map([[READY_ID, controlled]]),
      activeEnvironmentId: READY_ID,
      connectionStatus: 'closed',
    })
    renderPanel()

    await screen.findByRole('tab', { name: 'ready-env' })
    expect(document.querySelector('.terminal-viewport')?.getAttribute('data-readonly')).toBe('true')
    expect(screen.getByText(/观察中/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '释放控制' })).toBeNull()
    expect(screen.queryByRole('button', { name: '终止终端' })).toBeNull()
  })

  it('keeps the cached last screen read-only when the environments refetch fails', async () => {
    vi.mocked(environmentService.listEnvironments).mockRejectedValue(new Error('refetch payload'))
    const cached = session({
      status: 'RUNNING',
      executable: '/bin/bash',
      identity: { daemonInstanceId: 'd', terminalId: 't1' },
      streamId: 'stream',
      view: mirror('stream'),
      viewApplied: true,
      hasControl: true,
    })
    setTerminal({
      visible: true,
      sessions: new Map([[READY_ID, cached]]),
      activeEnvironmentId: READY_ID,
    })
    renderPanel()

    // 回读失败不销毁屏幕：固定本地提示 + 只读末屏，且不回显 Error.message。
    expect(await screen.findByText('环境列表加载失败，请重试')).toBeInTheDocument()
    expect(screen.queryByText(/refetch payload/)).toBeNull()
    expect(document.querySelector('.terminal-viewport')?.getAttribute('data-readonly')).toBe('true')
  })

  it('does not keep the previous environment grid when the active environment has no view', async () => {
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      card({ id: READY_ID, name: 'ready-env' }),
      card({ id: OFFLINE_ID, name: 'offline-env', status: 'OFFLINE', ready: false }),
    ])
    const first = session({
      status: 'RUNNING',
      identity: { daemonInstanceId: 'd', terminalId: 't1' },
      streamId: 'stream-a',
      view: mirror('stream-a'),
      viewApplied: true,
      hasControl: true,
    })
    const controller = fakeController()
    setTerminal(
      { visible: true, sessions: new Map([[READY_ID, first]]), activeEnvironmentId: READY_ID },
      controller,
    )
    const { rerenderPanel } = renderPanel()
    await screen.findByRole('tab', { name: 'ready-env' })
    const scrollA = document.querySelector('.terminal-viewport__scroll')
    expect(scrollA).not.toBeNull()
    expect(document.querySelector('.terminal-viewport')?.getAttribute('data-stream')).toBe('stream-a')

    // 切到另一个环境：viewport 必须重新挂载，绝不复用 A 的容器/网格。
    const second = session({
      environmentId: OFFLINE_ID,
      status: 'EXITED',
      identity: { daemonInstanceId: 'd', terminalId: 't2' },
      streamId: 'stream-b',
      view: mirror('stream-b'),
      hasControl: false,
    })
    setTerminal(
      {
        visible: true,
        sessions: new Map([
          [READY_ID, first],
          [OFFLINE_ID, second],
        ]),
        activeEnvironmentId: OFFLINE_ID,
      },
      controller,
    )
    rerenderPanel()

    await screen.findByRole('tab', { name: 'offline-env' })
    const scrollB = document.querySelector('.terminal-viewport__scroll')
    expect(scrollB).not.toBeNull()
    expect(scrollB).not.toBe(scrollA)
    expect(document.querySelector('.terminal-viewport')?.getAttribute('data-stream')).toBe('stream-b')
  })

  it('cancels termination when the active environment switches away from the frozen target', async () => {
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      card({ id: READY_ID, name: 'ready-env' }),
      card({ id: OFFLINE_ID, name: 'offline-env' }),
    ])
    const controller = fakeController()
    const frozen = session({
      status: 'RUNNING',
      identity: { daemonInstanceId: 'd', terminalId: 't1' },
      writer: writer('e1'),
      streamId: 'stream-1',
      viewApplied: true,
      hasControl: true,
    })
    setTerminal(
      { visible: true, sessions: new Map([[READY_ID, frozen]]), activeEnvironmentId: READY_ID },
      controller,
    )
    const { rerenderPanel } = renderPanel()

    fireEvent.click(await screen.findByRole('button', { name: '终止终端' }))

    // 弹窗期间切到 B：A 的 session 仍在 map 里，但活动环境已不是 A。
    const other = session({
      environmentId: OFFLINE_ID,
      status: 'RUNNING',
      identity: { daemonInstanceId: 'd', terminalId: 't2' },
      writer: writer('e2'),
      streamId: 'stream-2',
      viewApplied: true,
      hasControl: true,
    })
    setTerminal(
      { visible: true, sessions: new Map([[OFFLINE_ID, other]]), activeEnvironmentId: OFFLINE_ID },
      controller,
    )
    rerenderPanel()

    fireEvent.click(await screen.findByRole('button', { name: '确认终止' }))

    await waitFor(() => expect(screen.getByText(/终端目标已变化/)).toBeInTheDocument())
    expect(controller.terminate).not.toHaveBeenCalled()
  })

  it('cancels termination when the frozen daemon identity changes', async () => {
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([card({ id: READY_ID })])
    const controller = fakeController()
    const first = session({
      status: 'RUNNING',
      identity: { daemonInstanceId: 'daemon-1', terminalId: 't1' },
      writer: writer('e1'),
      streamId: 'stream-1',
      viewApplied: true,
      hasControl: true,
    })
    setTerminal(
      { visible: true, sessions: new Map([[READY_ID, first]]), activeEnvironmentId: READY_ID },
      controller,
    )
    const { rerenderPanel } = renderPanel()

    fireEvent.click(await screen.findByRole('button', { name: '终止终端' }))

    // 同 terminalId/epoch，但 daemon 已重启到新实例。
    const second = session({
      status: 'RUNNING',
      identity: { daemonInstanceId: 'daemon-2', terminalId: 't1' },
      writer: writer('e1'),
      streamId: 'stream-1',
      viewApplied: true,
      hasControl: true,
    })
    setTerminal(
      { visible: true, sessions: new Map([[READY_ID, second]]), activeEnvironmentId: READY_ID },
      controller,
    )
    rerenderPanel()

    fireEvent.click(await screen.findByRole('button', { name: '确认终止' }))

    await waitFor(() => expect(screen.getByText(/终端目标已变化/)).toBeInTheDocument())
    expect(controller.terminate).not.toHaveBeenCalled()
  })

  it('cancels termination when the frozen writer epoch changes', async () => {
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([card({ id: READY_ID })])
    const controller = fakeController()
    const first = session({
      status: 'RUNNING',
      identity: { daemonInstanceId: 'd', terminalId: 't1' },
      writer: writer('e1'),
      streamId: 'stream-1',
      viewApplied: true,
      hasControl: true,
    })
    setTerminal(
      { visible: true, sessions: new Map([[READY_ID, first]]), activeEnvironmentId: READY_ID },
      controller,
    )
    const { rerenderPanel } = renderPanel()

    fireEvent.click(await screen.findByRole('button', { name: '终止终端' }))

    const second = session({
      status: 'RUNNING',
      identity: { daemonInstanceId: 'd', terminalId: 't1' },
      writer: writer('e2'),
      streamId: 'stream-1',
      viewApplied: true,
      hasControl: true,
    })
    setTerminal(
      { visible: true, sessions: new Map([[READY_ID, second]]), activeEnvironmentId: READY_ID },
      controller,
    )
    rerenderPanel()

    fireEvent.click(await screen.findByRole('button', { name: '确认终止' }))

    await waitFor(() => expect(screen.getByText(/终端目标已变化/)).toBeInTheDocument())
    expect(controller.terminate).not.toHaveBeenCalled()
  })

  it('drops the frozen target when the dialog is dismissed so reopening re-freezes', async () => {
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([card({ id: READY_ID })])
    const controller = fakeController()
    const running = session({
      status: 'RUNNING',
      identity: { daemonInstanceId: 'd', terminalId: 't1' },
      writer: writer('e1'),
      streamId: 'stream-1',
      viewApplied: true,
      hasControl: true,
    })
    setTerminal(
      { visible: true, sessions: new Map([[READY_ID, running]]), activeEnvironmentId: READY_ID },
      controller,
    )
    renderPanel()

    fireEvent.click(await screen.findByRole('button', { name: '终止终端' }))
    fireEvent.click(await screen.findByRole('button', { name: '取消' }))
    await waitFor(() => expect(screen.queryByRole('button', { name: '确认终止' })).toBeNull())

    // 重新打开：冻结的是当前目标，确认后正常终止。
    fireEvent.click(screen.getByRole('button', { name: '终止终端' }))
    fireEvent.click(await screen.findByRole('button', { name: '确认终止' }))
    await waitFor(() => expect(controller.terminate).toHaveBeenCalledTimes(1))
  })

  it('runs takeover, release, refresh and restart through the controller', async () => {
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([card({ id: READY_ID })])
    const controller = fakeController()
    const observing = session({
      status: 'RUNNING',
      identity: { daemonInstanceId: 'd', terminalId: 't1' },
      streamId: 'stream',
      viewApplied: true,
      hasControl: false,
    })
    setTerminal(
      { visible: true, sessions: new Map([[READY_ID, observing]]), activeEnvironmentId: READY_ID },
      controller,
    )
    const { rerenderPanel } = renderPanel()
    await screen.findByRole('tab', { name: 'ready-env' })
    fireEvent.click(screen.getByRole('button', { name: '接管控制' }))
    fireEvent.click(screen.getByRole('button', { name: '刷新并重新同步' }))
    expect(controller.takeover).toHaveBeenCalledTimes(1)
    expect(controller.refresh).toHaveBeenCalledTimes(1)

    const controlled = session({
      status: 'RUNNING',
      identity: { daemonInstanceId: 'd', terminalId: 't1' },
      streamId: 'stream',
      viewApplied: true,
      hasControl: true,
    })
    setTerminal(
      { visible: true, sessions: new Map([[READY_ID, controlled]]), activeEnvironmentId: READY_ID },
      controller,
    )
    rerenderPanel()
    fireEvent.click(await screen.findByRole('button', { name: '释放控制' }))
    expect(controller.release).toHaveBeenCalledTimes(1)

    const exited = session({
      status: 'EXITED',
      identity: { daemonInstanceId: 'd', terminalId: 't1' },
      streamId: 'stream',
      viewApplied: true,
      hasControl: false,
    })
    setTerminal(
      { visible: true, sessions: new Map([[READY_ID, exited]]), activeEnvironmentId: READY_ID },
      controller,
    )
    rerenderPanel()
    fireEvent.click(await screen.findByRole('button', { name: '重新启动' }))
    expect(controller.restart).toHaveBeenCalledTimes(1)
  })

  it('closes the panel and clears any open confirmation', async () => {
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([card({ id: READY_ID })])
    const running = session({
      status: 'RUNNING',
      identity: { daemonInstanceId: 'd', terminalId: 't1' },
      streamId: 'stream',
      viewApplied: true,
      hasControl: true,
    })
    setTerminal({
      visible: true,
      sessions: new Map([[READY_ID, running]]),
      activeEnvironmentId: READY_ID,
    })
    renderPanel()

    fireEvent.click(await screen.findByRole('button', { name: '终止终端' }))
    fireEvent.click(screen.getByRole('button', { name: '收起终端' }))

    const api = terminalApi.current as { hide: ReturnType<typeof vi.fn> }
    expect(api.hide).toHaveBeenCalledTimes(1)
    await waitFor(() => expect(screen.queryByRole('button', { name: '确认终止' })).toBeNull())
  })

  it('refetches environments on subscribed, changed and resync', async () => {
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([card({ id: READY_ID })])
    setTerminal({ visible: true })
    renderPanel()
    await screen.findByRole('tab', { name: 'ready-env' })
    expect(appEvents.handlers).not.toBeNull()

    vi.mocked(environmentService.listEnvironments).mockClear()
    appEvents.handlers!.onSubscribed!()
    appEvents.handlers!.onEvent!('changed')
    appEvents.handlers!.onEvent!('other')
    appEvents.handlers!.onResync!()

    await waitFor(() => expect(environmentService.listEnvironments).toHaveBeenCalled())
  })

  it('derives the earliest expiry across null, numeric and invalid timestamps', async () => {
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      card({ id: READY_ID, name: 'alpha', statusExpiresAt: null }),
      card({ id: OFFLINE_ID, name: 'bravo', statusExpiresAt: 'not-a-date' }),
      card({ id: 'cccccccc-0000-4000-8000-000000000003', name: 'charlie', statusExpiresAt: 2000 }),
      card({ id: 'cccccccc-0000-4000-8000-000000000004', name: 'delta', statusExpiresAt: 1000 }),
    ])
    setTerminal({ visible: true })
    renderPanel()

    expect(await screen.findByRole('tab', { name: 'alpha' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'delta' })).toBeInTheDocument()
  })
})
