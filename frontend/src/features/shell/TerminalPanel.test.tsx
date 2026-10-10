/**
 * TerminalPanel 测试。
 *
 * 覆盖：仅可见时打开权威环境 query；tabs 只允许 READY 新选、离线会话只读查看；
 * 标题展示真实 executable/控制态；terminate 走统一确认弹窗；pending 禁用并发动作按钮；
 * 有会话时渲染唯一 TerminalViewport。
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

const { terminalApi } = vi.hoisted(() => ({
  terminalApi: { current: null as unknown },
}))

vi.mock('@/features/shell/terminal-context', () => ({
  useTerminal: () => terminalApi.current,
}))

vi.mock('@/shared/app-events', () => ({
  useApplicationEvents: () => ({ subscribe: () => () => undefined }),
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
  }
}

function setTerminal(
  options: {
    visible: boolean
    sessions?: Map<string, TerminalSessionSnapshot>
    activeEnvironmentId?: string | null
  },
  controller: ReturnType<typeof fakeController> = fakeController(),
) {
  terminalApi.current = {
    controller,
    snapshot: {
      visible: options.visible,
      activeEnvironmentId: options.activeEnvironmentId ?? null,
      connectionStatus: 'open',
      sessions: options.sessions ?? new Map(),
    },
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

  it('shows the real error instead of an empty state', async () => {
    vi.mocked(environmentService.listEnvironments).mockRejectedValue(new Error('env boom'))
    setTerminal({ visible: true })
    renderPanel()

    expect(await screen.findByText('env boom')).toBeInTheDocument()
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
    expect(screen.queryByRole('button', { name: '终止终端' })).toBeNull()
    // 只读刷新与收起仍可用。
    expect(screen.getByRole('button', { name: '刷新并重新同步' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '收起终端' })).toBeInTheDocument()
  })

  it('cancels termination when the frozen target changes while the dialog is open', async () => {
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([card({ id: READY_ID })])
    const controller = fakeController()
    const writer = (epoch: string) => ({
      writerEpoch: epoch,
      lastWrittenSeq: 0,
      lastWrittenDigest: null,
      lastResolvedSeq: 0,
      lastResolvedDigest: null,
      lastResolvedOutcome: null,
      pendingSeq: 0,
      pendingDigest: null,
      frozen: false,
    })
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

    // 弹窗期间同一环境切换到了新 terminal/writer。
    const second = session({
      status: 'RUNNING',
      identity: { daemonInstanceId: 'd', terminalId: 't2' },
      writer: writer('e2'),
      streamId: 'stream-2',
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
})
