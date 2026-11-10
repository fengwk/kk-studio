import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApplicationEventProvider, useApplicationEvents } from '@/shared/app-events'
import { FakeWebSocketHarness, type FakeWebSocket } from '@/shared/app-events/__tests__/fake-websocket'
import { ApiError } from '@/shared/api/client'
import type { EnvironmentDTO } from '@/shared/api/generated/contracts'
import { IdePanelProvider } from '@/features/environment/ide/IdePanelProvider'
import { useIdePanel, type IdeEnvironmentInfo } from '@/features/environment/ide/ide-panel-context'
import {
  IDE_TAB_SESSION_KEY,
  saveIdeSession,
  type IdePendingClaim,
  type IdeRecovery,
} from '@/features/environment/ide/ide-tab-state'
import { testEnvironment } from '@/test-support/environment-test-fixtures'

const environmentService = vi.hoisted(() => ({ listEnvironments: vi.fn() }))
vi.mock('@/features/environment/environment-service', () => ({ environmentService }))

const ideService = vi.hoisted(() => ({
  openIdeView: vi.fn(),
  readIdeView: vi.fn(),
  changeIdeViewDirectory: vi.fn(),
  focusIdeTerminal: vi.fn(),
  applyIdeMobileLayout: vi.fn(),
  closeIdeView: vi.fn(),
  listIdeDirectories: vi.fn(),
}))
vi.mock('@/features/environment/ide/environment-ide-service', () => ideService)

const ENV_A = 'a1000000-0000-4000-8000-000000000001'
const ENV_B = 'a1000000-0000-4000-8000-000000000002'
const VIEW_A = 'c1000000-0000-4000-8000-000000000001'
const DAEMON_A = 'd1000000-0000-4000-8000-000000000001'
const EVENT_NODE = 'e1000000-0000-4000-8000-000000000001'
const CONNECTION_1 = 'f1000000-0000-4000-8000-000000000001'
const CONNECTION_2 = 'f1000000-0000-4000-8000-000000000002'
const BROWSER_A = 'b1000000-0000-4000-8000-000000000001'
const CLAIM_A = 'e1000000-0000-4000-8000-000000000011'
const DIRECTORY_A = '/workspace/projects/a'

function card(id: string, name: string, ready = true): EnvironmentDTO {
  return testEnvironment({
    id,
    name,
    ready,
    status: ready ? 'READY' : 'OFFLINE',
    version: '1',
    createdAt: 1790812800000,
    updatedAt: 1790812800000,
    capabilities: [],
    statusExpiresAt: null,
    lastSeenAt: null,
  })
}

const CARD_A = card(ENV_A, 'local-node')
const CARD_B = card(ENV_B, 'remote-node')
const ENV_INFO_A: IdeEnvironmentInfo = { id: ENV_A, name: CARD_A.name, ready: true }

function pending(overrides: Partial<IdePendingClaim> = {}): IdePendingClaim {
  return {
    mode: 'resume',
    viewId: VIEW_A,
    claimId: CLAIM_A,
    expectedBindingEpoch: '10',
    expectedDaemonInstanceId: DAEMON_A,
    directory: DIRECTORY_A,
    ...overrides,
  }
}

function snapshot(
  view: Record<string, unknown> | null,
  overrides: Record<string, unknown> = {},
) {
  return {
    daemonInstanceId: DAEMON_A,
    revision: '20',
    view,
    ...overrides,
  }
}

function viewDto(request: Record<string, unknown>, sequence: number) {
  return {
    viewId: request.viewId,
    environmentId: ENV_A,
    browserSessionId: request.browserSessionId,
    eventNodeId: request.eventNodeId,
    connectionId: request.connectionId,
    claimId: request.claimId,
    daemonInstanceId: request.expectedDaemonInstanceId ?? DAEMON_A,
    bindingEpoch: String(sequence + 10),
    directory: DIRECTORY_A,
    workspacePath: DIRECTORY_A,
    state: 'OPEN',
    entryUrl: `/ide/${ENV_A}/${String(request.viewId)}/__ide/bootstrap?token=t-${sequence}`,
  }
}

function recovery(overrides: Partial<IdeRecovery> = {}): IdeRecovery {
  return {
    viewId: VIEW_A,
    daemonInstanceId: DAEMON_A,
    bindingEpoch: '10',
    directory: DIRECTORY_A,
    ...overrides,
  }
}

function seedSession(browserSessionId = BROWSER_A, recoveryValue: IdeRecovery = recovery()): void {
  saveIdeSession(
    browserSessionId,
    {
      panelVisible: true,
      selectedEnvironmentId: ENV_A,
      openedEnvironmentIds: [ENV_A],
    },
    { [ENV_A]: recoveryValue },
    { sessionStorage, localStorage },
  )
}

function sessionSnapshot(): {
  browserSessionId: string
  recoveryByEnvironment: Record<string, IdeRecovery & { pending?: IdePendingClaim | null }>
} {
  return JSON.parse(sessionStorage.getItem(IDE_TAB_SESSION_KEY) ?? '{}') as {
    browserSessionId: string
    recoveryByEnvironment: Record<string, IdeRecovery & { pending?: IdePendingClaim | null }>
  }
}

function Harness() {
  const { activateChatEnvironment, togglePanel } = useIdePanel()
  const events = useApplicationEvents()
  return (
    <div>
      <button data-testid="activate-a" onClick={() => activateChatEnvironment(ENV_INFO_A)}>
        Activate
      </button>
      <button data-testid="activate-none" onClick={() => activateChatEnvironment(null)}>
        No environment
      </button>
      <button data-testid="toggle-a" onClick={() => togglePanel(ENV_INFO_A)}>
        Toggle
      </button>
      <button
        data-testid="reconnect"
        onClick={() => {
          events.disconnect()
          events.connect()
        }}
      >
        Reconnect
      </button>
      <button data-testid="state-probe" aria-label="state-probe" />
    </div>
  )
}

function renderPanel(options: { active?: boolean } = {}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
  })
  const sockets = new FakeWebSocketHarness()
  const view = render(
    <QueryClientProvider client={queryClient}>
      <ApplicationEventProvider url="ws://test/ws/events" socketFactory={sockets.factory}>
        <IdePanelProvider active={options.active ?? true} storage={{ sessionStorage, localStorage }}>
          <Harness />
        </IdePanelProvider>
      </ApplicationEventProvider>
    </QueryClientProvider>,
  )
  return { ...view, sockets }
}

function connect(
  sockets: FakeWebSocketHarness,
  connectionId = CONNECTION_1,
  shouldAck: (frame: { revision?: string; views?: Array<Record<string, unknown>> }) => boolean = () => true,
): FakeWebSocket {
  const socket = sockets.openLatest()
  const originalSend = socket.send.bind(socket)
  socket.send = (data: string) => {
    const frame = JSON.parse(data) as {
      type?: string
      revision?: string
      views?: Array<Record<string, unknown>>
    }
    originalSend(data)
    if (
      frame.type === 'ide-use'
      && frame.revision != null
      && shouldAck(frame)
    ) {
      socket.onmessage?.({
        data: JSON.stringify({
          type: 'ide-use-accepted',
          revision: frame.revision,
        }),
      })
    }
  }
  socket.emitServer({ type: 'connected', eventNodeId: EVENT_NODE, connectionId })
  return socket
}

function fakeLocks(options: { deny?: string[] } = {}) {
  const held = new Set(options.deny ?? [])
  return {
    held,
    request: vi.fn(async (
      name: string,
      _requestOptions: LockOptions,
      callback: (lock: Lock | null) => Promise<unknown>,
    ) => {
      if (held.has(name)) {
        return callback(null)
      }
      held.add(name)
      try {
        return await callback({ name } as Lock)
      } finally {
        held.delete(name)
      }
    }),
  }
}

function installLocks(locks: unknown): void {
  Object.defineProperty(navigator, 'locks', {
    configurable: true,
    value: locks,
  })
}

const originalLocks = Object.getOwnPropertyDescriptor(navigator, 'locks')

describe('IdePanelProvider recovery state machine', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    sessionStorage.clear()
    localStorage.clear()
    environmentService.listEnvironments.mockReset()
    for (const mock of Object.values(ideService)) {
      mock.mockReset()
    }
    environmentService.listEnvironments.mockResolvedValue([CARD_A, CARD_B])
    ideService.openIdeView.mockImplementation(async (environmentId: string, request: Record<string, unknown>) => (
      viewDto({ ...request, environmentId }, 0)
    ))
    ideService.readIdeView.mockResolvedValue(snapshot(null))
    ideService.closeIdeView.mockResolvedValue(undefined)
    ideService.focusIdeTerminal.mockResolvedValue(undefined)
    ideService.applyIdeMobileLayout.mockResolvedValue(undefined)
    ideService.listIdeDirectories.mockResolvedValue({
      path: '/workspace/projects/a',
      parentPath: '/workspace/projects',
      truncated: false,
      children: [{ name: 'next', path: '/workspace/projects/a/next' }],
    })
    vi.stubGlobal('matchMedia', vi.fn().mockReturnValue({ matches: false }))
    installLocks(fakeLocks())
  })

  afterEach(() => {
    if (originalLocks == null) {
      Reflect.deleteProperty(navigator, 'locks')
    } else {
      Object.defineProperty(navigator, 'locks', originalLocks)
    }
    vi.useRealTimers()
  })

  it('does not auto-create when a Chat environment is activated, and creates once on explicit toggle', async () => {
    const user = userEvent.setup()
    let pendingAtOpen: IdePendingClaim | null | undefined
    ideService.openIdeView.mockImplementationOnce(async (_environmentId: string, request: Record<string, unknown>) => {
      pendingAtOpen = sessionSnapshot().recoveryByEnvironment[ENV_A]?.pending
      return viewDto(request, 0)
    })
    const { sockets } = renderPanel()
    connect(sockets)

    await user.click(screen.getByTestId('activate-a'))
    expect(ideService.readIdeView).not.toHaveBeenCalled()
    expect(ideService.openIdeView).not.toHaveBeenCalled()
    expect(screen.getByTestId('ide-overlay-panel')).toHaveAttribute('hidden')

    await user.click(screen.getByTestId('toggle-a'))
    await waitFor(() => {
      expect(ideService.readIdeView).toHaveBeenCalledTimes(1)
    })
    const [, viewId, readQuery] = ideService.readIdeView.mock.calls[0] as [
      string,
      string,
      Record<string, unknown>,
    ]
    expect(readQuery).toMatchObject({
      browserSessionId: sessionSnapshot().browserSessionId,
      eventNodeId: EVENT_NODE,
      connectionId: CONNECTION_1,
    })
    expect(readQuery).not.toHaveProperty('expectedDaemonInstanceId')

    await waitFor(() => {
      expect(ideService.openIdeView).toHaveBeenCalledTimes(1)
    })
    const [, openRequest] = ideService.openIdeView.mock.calls[0] as [
      string,
      Record<string, unknown>,
    ]
    expect(openRequest).toMatchObject({
      viewId,
      mode: 'create',
      expectedBindingEpoch: '0',
      expectedDaemonInstanceId: DAEMON_A,
      browserSessionId: sessionSnapshot().browserSessionId,
    })
    // pending 必须在 POST 发出时已经存在于 sessionStorage。
    expect(pendingAtOpen).toMatchObject({
      mode: 'create',
      viewId,
      expectedBindingEpoch: '0',
      expectedDaemonInstanceId: DAEMON_A,
    })
    await screen.findByTestId('ide-iframe')

    await user.click(screen.getByTestId('toggle-a'))
    expect(screen.getByTestId('ide-overlay-panel')).toHaveAttribute('hidden')
    await user.click(screen.getByTestId('toggle-a'))
    expect(screen.getByTestId('ide-overlay-panel')).not.toHaveAttribute('hidden')
    expect(screen.getByTestId('ide-iframe')).toBeInTheDocument()
    expect(ideService.openIdeView).toHaveBeenCalledTimes(1)
    expect(ideService.readIdeView).toHaveBeenCalledTimes(1)
  })

  it('persists pending before sending OPEN and restores the same view on refresh', async () => {
    // 刷新场景：session 已有 view/instance/epoch，必须先纯读权威快照再以 CAS 重新绑定当前连接。
    seedSession()
    ideService.readIdeView.mockResolvedValue(snapshot({
      viewId: VIEW_A,
      environmentId: ENV_A,
      browserSessionId: BROWSER_A,
      eventNodeId: EVENT_NODE,
      connectionId: CONNECTION_1,
      claimId: CLAIM_A,
      daemonInstanceId: DAEMON_A,
      bindingEpoch: '11',
      directory: DIRECTORY_A,
      workspacePath: DIRECTORY_A,
      state: 'OPEN',
    }, { revision: '20' }))
    ideService.openIdeView.mockImplementation(async (_environmentId: string, request: Record<string, unknown>) => (
      viewDto(request, 1)
    ))

    const { sockets } = renderPanel()
    connect(sockets)

    await waitFor(() => {
      expect(ideService.readIdeView).toHaveBeenCalledTimes(1)
    })
    const [, viewId, readQuery] = ideService.readIdeView.mock.calls[0] as [
      string,
      string,
      Record<string, unknown>,
    ]
    expect(viewId).toBe(VIEW_A)
    expect(readQuery).toMatchObject({
      browserSessionId: BROWSER_A,
      expectedDaemonInstanceId: DAEMON_A,
      eventNodeId: EVENT_NODE,
      connectionId: CONNECTION_1,
    })

    await waitFor(() => {
      expect(ideService.openIdeView).toHaveBeenCalledTimes(1)
    })
    const [, request] = ideService.openIdeView.mock.calls[0] as [string, Record<string, unknown>]
    expect(request).toMatchObject({
      viewId: VIEW_A,
      mode: 'resume',
      expectedBindingEpoch: '11',
      expectedDaemonInstanceId: DAEMON_A,
    })
    expect(request).not.toHaveProperty('directory')
    expect(sessionSnapshot().browserSessionId).toBe(BROWSER_A)
    expect(sessionSnapshot().recoveryByEnvironment[ENV_A]?.bindingEpoch).toBe('11')
    await screen.findByTestId('ide-iframe')
  })

  it('rebinds immediately on a new WebSocket connection without waiting for the old one', async () => {
    const user = userEvent.setup()
    ideService.readIdeView.mockResolvedValue(snapshot(null))
    ideService.openIdeView.mockImplementation(async (_environmentId: string, request: Record<string, unknown>) => (
      viewDto(request, 0)
    ))
    const { sockets } = renderPanel()
    connect(sockets)
    await user.click(screen.getByTestId('toggle-a'))
    await screen.findByTestId('ide-iframe')
    const openedViewId = sessionSnapshot().recoveryByEnvironment[ENV_A]?.viewId
    const firstEpoch = sessionSnapshot().recoveryByEnvironment[ENV_A]?.bindingEpoch

    ideService.readIdeView.mockResolvedValue(snapshot({
      viewId: openedViewId,
      environmentId: ENV_A,
      browserSessionId: sessionSnapshot().browserSessionId,
      eventNodeId: EVENT_NODE,
      connectionId: CONNECTION_2,
      claimId: CLAIM_A,
      daemonInstanceId: DAEMON_A,
      bindingEpoch: firstEpoch,
      directory: DIRECTORY_A,
      workspacePath: DIRECTORY_A,
      state: 'OPEN',
    }, { revision: '21' }))
    ideService.openIdeView.mockClear()

    await user.click(screen.getByTestId('reconnect'))
    await act(async () => {
      connect(sockets, CONNECTION_2)
    })

    await waitFor(() => {
      expect(ideService.readIdeView).toHaveBeenCalledTimes(2)
    })
    await waitFor(() => {
      expect(ideService.openIdeView).toHaveBeenCalledTimes(1)
    })
    const [, request] = ideService.openIdeView.mock.calls[0] as [string, Record<string, unknown>]
    expect(request).toMatchObject({
      viewId: openedViewId,
      mode: 'resume',
      connectionId: CONNECTION_2,
      expectedBindingEpoch: firstEpoch,
      expectedDaemonInstanceId: DAEMON_A,
    })
  })

  it('reconciles STALE_BINDING with one pure read and one new CAS, never a replacement create', async () => {
    seedSession()
    ideService.readIdeView
      .mockResolvedValueOnce(snapshot({
        viewId: VIEW_A,
        environmentId: ENV_A,
        browserSessionId: BROWSER_A,
        eventNodeId: EVENT_NODE,
        connectionId: CONNECTION_1,
        claimId: CLAIM_A,
        daemonInstanceId: DAEMON_A,
        bindingEpoch: '11',
        directory: DIRECTORY_A,
        workspacePath: DIRECTORY_A,
        state: 'OPEN',
      }, { revision: '20' }))
      .mockResolvedValueOnce(snapshot({
        viewId: VIEW_A,
        environmentId: ENV_A,
        browserSessionId: BROWSER_A,
        eventNodeId: EVENT_NODE,
        connectionId: CONNECTION_1,
        claimId: CLAIM_A,
        daemonInstanceId: DAEMON_A,
        bindingEpoch: '12',
        directory: DIRECTORY_A,
        workspacePath: DIRECTORY_A,
        state: 'OPEN',
      }, { revision: '21' }))
    ideService.openIdeView
      .mockRejectedValueOnce(new ApiError('stale', 409, 'conflict', undefined, 'STALE_BINDING'))
      .mockImplementationOnce(async (_environmentId: string, request: Record<string, unknown>) => (
        viewDto(request, 2)
      ))

    const { sockets } = renderPanel()
    connect(sockets)

    await waitFor(() => {
      expect(ideService.openIdeView).toHaveBeenCalledTimes(2)
    })
    expect(ideService.readIdeView).toHaveBeenCalledTimes(2)
    const first = ideService.openIdeView.mock.calls[0]?.[1] as Record<string, unknown>
    const second = ideService.openIdeView.mock.calls[1]?.[1] as Record<string, unknown>
    expect(first).toMatchObject({ mode: 'resume', expectedBindingEpoch: '11' })
    expect(second).toMatchObject({ mode: 'resume', expectedBindingEpoch: '12' })
    expect(second.viewId).toBe(first.viewId)
    expect(ideService.openIdeView.mock.calls.some(([, request]) => (
      (request as Record<string, unknown>).mode === 'create'
    ))).toBe(false)
    await screen.findByTestId('ide-iframe')
  })

  it('reads the authoritative view after an unknown OPEN result before replaying the same pending claim', async () => {
    seedSession(BROWSER_A, recovery({
      bindingEpoch: undefined,
      pending: pending({ mode: 'create', expectedBindingEpoch: '0' }),
    }))
    ideService.openIdeView.mockRejectedValueOnce(
      new ApiError('network interrupted', undefined, 'network_error'),
    )

    const { sockets } = renderPanel()
    connect(sockets)

    await waitFor(() => {
      expect(ideService.openIdeView).toHaveBeenCalledTimes(2)
    })
    expect(ideService.readIdeView).toHaveBeenCalledTimes(2)
    const first = ideService.openIdeView.mock.calls[0]?.[1] as Record<string, unknown>
    const second = ideService.openIdeView.mock.calls[1]?.[1] as Record<string, unknown>
    expect(second).toEqual(first)
    expect(second).toMatchObject({ mode: 'create', claimId: CLAIM_A, viewId: VIEW_A })
    await screen.findByTestId('ide-iframe')
  })

  it('waits for the new resume claim usage ACK before sending OPEN', async () => {
    seedSession(BROWSER_A, recovery({ pending: pending() }))
    ideService.readIdeView.mockResolvedValue(snapshot({
      viewId: VIEW_A,
      environmentId: ENV_A,
      browserSessionId: BROWSER_A,
      eventNodeId: EVENT_NODE,
      connectionId: CONNECTION_1,
      claimId: 'e1000000-0000-4000-8000-000000000012',
      daemonInstanceId: DAEMON_A,
      bindingEpoch: '11',
      directory: DIRECTORY_A,
      workspacePath: DIRECTORY_A,
      state: 'OPEN',
    }, { revision: '21' }))
    let heldRevision: string | null = null
    let resolveOpen!: (value: unknown) => void
    let usageAtPost: Array<{ type?: string; views?: Array<{ claimId?: string }> }> = []
    ideService.openIdeView.mockImplementationOnce(async (
      _environmentId: string,
      request: Record<string, unknown>,
    ) => {
      usageAtPost = socket.sentMessages() as Array<{
        type?: string
        views?: Array<{ claimId?: string }>
      }>
      return new Promise((resolve) => {
        resolveOpen = (value) => resolve(value)
        void request
      })
    })

    const { sockets } = renderPanel()
    const socket = connect(sockets, CONNECTION_1, (frame) => {
      const isNewClaim = (frame.views ?? []).some((view) => view.claimId !== CLAIM_A)
      if (isNewClaim) {
        heldRevision = frame.revision ?? null
        return false
      }
      return true
    })

    await waitFor(() => {
      expect(ideService.readIdeView).toHaveBeenCalled()
    })
    await waitFor(() => {
      const nextClaim = sessionSnapshot().recoveryByEnvironment[ENV_A]?.pending?.claimId
      expect(nextClaim).toBeTruthy()
      expect(nextClaim).not.toBe(CLAIM_A)
    })
    expect(heldRevision).not.toBeNull()
    expect(ideService.openIdeView).not.toHaveBeenCalled()

    await act(async () => {
      socket.onmessage?.({
        data: JSON.stringify({
          type: 'ide-use-accepted',
          revision: heldRevision,
        }),
      })
    })
    await waitFor(() => {
      expect(ideService.openIdeView).toHaveBeenCalledTimes(1)
    })
    const nextClaim = sessionSnapshot().recoveryByEnvironment[ENV_A]?.pending?.claimId
    const [, pendingRequest] = ideService.openIdeView.mock.calls[0] as [string, Record<string, unknown>]
    await act(async () => {
      resolveOpen(viewDto(pendingRequest, 2))
    })
    const [, request] = ideService.openIdeView.mock.calls[0] as [string, Record<string, unknown>]
    expect(request).toMatchObject({
      mode: 'resume',
      claimId: nextClaim,
      expectedBindingEpoch: '11',
    })
    expect(usageAtPost.some((frame) => (
      frame.type === 'ide-use'
      && (frame.views ?? []).some((view) => view.claimId === nextClaim)
    ))).toBe(true)
  })

  it('shows detached on VIEW_OWNED_BY_OTHER_SESSION without sending OPEN or replacing the view', async () => {
    seedSession()
    ideService.readIdeView.mockRejectedValue(
      new ApiError('owned', 409, 'conflict', undefined, 'VIEW_OWNED_BY_OTHER_SESSION'),
    )
    const { sockets } = renderPanel()
    connect(sockets)

    await waitFor(() => {
      expect(screen.getByTestId('ide-view-detached')).toBeVisible()
    })
    expect(ideService.openIdeView).not.toHaveBeenCalled()
    expect(sessionSnapshot().recoveryByEnvironment[ENV_A]?.viewId).toBe(VIEW_A)
  })

  it('keeps pending on DAEMON_NOT_READY and waits for READY without a timer retry', async () => {
    seedSession(BROWSER_A, recovery({ pending: pending() }))
    ideService.readIdeView.mockRejectedValue(
      new ApiError('not ready', 409, 'unavailable', undefined, 'DAEMON_NOT_READY'),
    )
    const { sockets } = renderPanel()
    connect(sockets)

    await waitFor(() => {
      expect(screen.getByTestId('ide-waiting-ready')).toBeVisible()
    })
    expect(ideService.readIdeView).toHaveBeenCalledTimes(1)
    expect(ideService.openIdeView).not.toHaveBeenCalled()
    expect(sessionSnapshot().recoveryByEnvironment[ENV_A]?.pending?.claimId).toBe(CLAIM_A)
  })

  it('isolates a copied tab through Web Locks and clears copied runtime identity', async () => {
    seedSession()
    installLocks(fakeLocks({ deny: [`kk-studio.ide-session:${BROWSER_A}`] }))
    const { sockets } = renderPanel()
    connect(sockets)

    await waitFor(() => {
      expect(sessionSnapshot().browserSessionId).not.toBe(BROWSER_A)
    })
    expect(sessionSnapshot().recoveryByEnvironment).toEqual({})
    expect(screen.getByTestId('ide-overlay-panel')).toHaveAttribute('hidden')
    expect(ideService.readIdeView).not.toHaveBeenCalled()
    expect(ideService.openIdeView).not.toHaveBeenCalled()
    expect(sessionSnapshot().browserSessionId).toMatch(/^[0-9a-f-]{36}$/)
  })

  it('reports Web Locks capability failure without falling back to shared identity', async () => {
    seedSession()
    Reflect.deleteProperty(navigator, 'locks')
    const { sockets } = renderPanel()
    connect(sockets)

    await waitFor(() => {
      expect(screen.getByTestId('ide-session-unavailable')).toBeVisible()
    })
    expect(ideService.readIdeView).not.toHaveBeenCalled()
    expect(ideService.openIdeView).not.toHaveBeenCalled()
  })

  it('closes with browser session, instance and epoch and removes the recovered view', async () => {
    ideService.readIdeView.mockResolvedValue(snapshot(null))
    ideService.openIdeView.mockImplementation(async (_environmentId: string, request: Record<string, unknown>) => (
      viewDto(request, 0)
    ))
    const user = userEvent.setup()
    const { sockets } = renderPanel()
    connect(sockets)
    await user.click(screen.getByTestId('toggle-a'))
    await screen.findByTestId('ide-iframe')
    const epoch = sessionSnapshot().recoveryByEnvironment[ENV_A]?.bindingEpoch

    await user.click(screen.getByTestId('ide-close-btn'))
    await waitFor(() => {
      expect(ideService.closeIdeView).toHaveBeenCalledTimes(1)
    })
    expect(ideService.closeIdeView.mock.calls[0]?.[2]).toMatchObject({
      browserSessionId: sessionSnapshot().browserSessionId,
      bindingEpoch: epoch,
      expectedDaemonInstanceId: DAEMON_A,
      discard: false,
    })
    await waitFor(() => {
      expect(screen.queryByTestId('ide-iframe')).not.toBeInTheDocument()
    })
    expect(sessionSnapshot().recoveryByEnvironment[ENV_A]).toBeUndefined()
  })

  it('hides an unrelated view in a Chat without an environment and keeps its iframe for return', async () => {
    const user = userEvent.setup()
    const { sockets } = renderPanel()
    connect(sockets)
    await user.click(screen.getByTestId('toggle-a'))
    const frame = await screen.findByTestId('ide-iframe')
    const opens = ideService.openIdeView.mock.calls.length

    await user.click(screen.getByTestId('activate-none'))
    expect(screen.getByTestId('ide-overlay-panel')).not.toBeVisible()
    expect(screen.getByTestId('ide-iframe')).toBe(frame)
    await user.click(screen.getByTestId('activate-a'))
    expect(screen.getByTestId('ide-overlay-panel')).toBeVisible()
    expect(screen.getByTestId('ide-iframe')).toBe(frame)
    expect(ideService.openIdeView).toHaveBeenCalledTimes(opens)
  })

  it('does not carry an old in-flight close flag into a newer connection binding', async () => {
    const user = userEvent.setup()
    const { sockets } = renderPanel()
    connect(sockets)
    await user.click(screen.getByTestId('toggle-a'))
    await screen.findByTestId('ide-iframe')
    const firstRequest = ideService.openIdeView.mock.calls[0]![1] as Record<string, unknown>
    ideService.readIdeView.mockResolvedValue(snapshot({
      ...viewDto(firstRequest, 0), entryUrl: null,
    }, { revision: '21' }))
    let resolveClose!: () => void
    ideService.closeIdeView.mockImplementationOnce(() => new Promise<void>((resolve) => {
      resolveClose = resolve
    }))
    await user.click(screen.getByTestId('ide-close-btn'))
    await waitFor(() => expect(ideService.closeIdeView).toHaveBeenCalledTimes(1))

    await user.click(screen.getByTestId('reconnect'))
    ideService.openIdeView.mockImplementation(async (_id: string, request: Record<string, unknown>) => (
      viewDto(request, 2)
    ))
    await act(async () => { connect(sockets, CONNECTION_2) })
    await waitFor(() => expect(sessionSnapshot().recoveryByEnvironment[ENV_A]?.bindingEpoch).toBe('12'))
    await act(async () => { resolveClose() })

    expect(screen.getByTestId('ide-iframe')).toBeInTheDocument()
    expect(screen.getByTestId('ide-close-btn')).toBeEnabled()
  })

  it('reuses a committed unknown claim to obtain its entry instead of rebinding it again', async () => {
    const user = userEvent.setup()
    const { sockets } = renderPanel()
    connect(sockets)
    ideService.openIdeView.mockImplementationOnce(async (_id: string, request: Record<string, unknown>) => {
      ideService.readIdeView.mockResolvedValue(snapshot({
        ...viewDto(request, 1), entryUrl: null,
      }, { revision: '21' }))
      throw new ApiError('network interrupted', undefined, 'network_error')
    })
    ideService.openIdeView.mockImplementation(async (_id: string, request: Record<string, unknown>) => (
      viewDto(request, 1)
    ))
    await user.click(screen.getByTestId('toggle-a'))
    await screen.findByTestId('ide-iframe')
    expect(ideService.openIdeView).toHaveBeenCalledTimes(2)
    expect(ideService.openIdeView.mock.calls[1]![1]).toEqual(ideService.openIdeView.mock.calls[0]![1])
    expect(sessionSnapshot().recoveryByEnvironment[ENV_A]?.bindingEpoch).toBe('11')
  })

  it('retries DAEMON_NOT_READY on a real new connection instead of freezing the view', async () => {
    seedSession(BROWSER_A, recovery({
      bindingEpoch: undefined,
      pending: pending({
        mode: 'create',
        expectedBindingEpoch: '0',
        directory: undefined,
      }),
    }))
    ideService.readIdeView.mockRejectedValueOnce(
      new ApiError('not ready', 409, 'unavailable', undefined, 'DAEMON_NOT_READY'),
    )
    const user = userEvent.setup()
    const { sockets } = renderPanel()
    connect(sockets, CONNECTION_1)

    await waitFor(() => {
      expect(screen.getByTestId('ide-waiting-ready')).toBeVisible()
    })
    expect(ideService.readIdeView).toHaveBeenCalledTimes(1)
    expect(ideService.openIdeView).not.toHaveBeenCalled()

    ideService.readIdeView.mockResolvedValue(snapshot(null))
    ideService.openIdeView.mockImplementation(async (_environmentId: string, request: Record<string, unknown>) => (
      viewDto(request, 4)
    ))
    await user.click(screen.getByTestId('reconnect'))
    await act(async () => {
      connect(sockets, CONNECTION_2)
    })

    await waitFor(() => {
      expect(ideService.readIdeView).toHaveBeenCalledTimes(2)
    })
    await waitFor(() => {
      expect(ideService.openIdeView).toHaveBeenCalledTimes(1)
    })
    const [, request] = ideService.openIdeView.mock.calls[0] as [string, Record<string, unknown>]
    expect(request).toMatchObject({
      connectionId: CONNECTION_2,
      mode: 'create',
      claimId: CLAIM_A,
      viewId: VIEW_A,
      expectedDaemonInstanceId: DAEMON_A,
    })
    await screen.findByTestId('ide-iframe')
  })

  it('keeps the same epoch when a directory change stays inside the same workspacePath', async () => {
    const user = userEvent.setup()
    const { sockets } = renderPanel()
    connect(sockets)
    await user.click(screen.getByTestId('toggle-a'))
    await screen.findByTestId('ide-iframe')
    const openedViewId = sessionSnapshot().recoveryByEnvironment[ENV_A]?.viewId
    const epoch = sessionSnapshot().recoveryByEnvironment[ENV_A]?.bindingEpoch
    const readCalls = ideService.readIdeView.mock.calls.length

    ideService.changeIdeViewDirectory.mockResolvedValue({
      viewId: openedViewId,
      environmentId: ENV_A,
      browserSessionId: sessionSnapshot().browserSessionId,
      eventNodeId: EVENT_NODE,
      connectionId: CONNECTION_1,
      claimId: CLAIM_A,
      daemonInstanceId: DAEMON_A,
      bindingEpoch: epoch,
      directory: '/workspace/projects/a/native-subdir',
      workspacePath: DIRECTORY_A,
      state: 'OPEN',
    })

    await user.click(screen.getByTestId('ide-open-directory-btn'))
    await waitFor(() => {
      expect(ideService.listIdeDirectories).toHaveBeenCalled()
    })
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByTestId('ide-directory-confirm'))

    await waitFor(() => {
      expect(ideService.changeIdeViewDirectory).toHaveBeenCalledTimes(1)
    })
    expect(ideService.changeIdeViewDirectory.mock.calls[0]?.[2]).toMatchObject({
      browserSessionId: sessionSnapshot().browserSessionId,
      bindingEpoch: epoch,
      expectedDaemonInstanceId: DAEMON_A,
    })
    expect(sessionSnapshot().recoveryByEnvironment[ENV_A]?.bindingEpoch).toBe(epoch)
    expect(ideService.readIdeView).toHaveBeenCalledTimes(readCalls)
    expect(ideService.openIdeView).toHaveBeenCalledTimes(1)
  })

  it('keeps the view when a late binding response belongs to a replaced connection', async () => {
    ideService.readIdeView.mockResolvedValue(snapshot(null))
    let resolveOpen!: (value: unknown) => void
    ideService.openIdeView.mockImplementationOnce(() => new Promise((resolve) => {
      resolveOpen = resolve
    }))
    const user = userEvent.setup()
    const { sockets } = renderPanel()
    connect(sockets)
    await user.click(screen.getByTestId('toggle-a'))
    await waitFor(() => {
      expect(ideService.openIdeView).toHaveBeenCalledTimes(1)
    })
    const request = ideService.openIdeView.mock.calls[0]?.[1] as Record<string, unknown>

    ideService.readIdeView.mockResolvedValue(snapshot(null))
    ideService.openIdeView.mockImplementation(async (_environmentId: string, next: Record<string, unknown>) => (
      viewDto(next, 3)
    ))
    await user.click(screen.getByTestId('reconnect'))
    await act(async () => {
      connect(sockets, CONNECTION_2)
    })
    await waitFor(() => {
      expect(ideService.readIdeView).toHaveBeenCalledTimes(2)
    })
    await act(async () => {
      resolveOpen(viewDto({ ...request, connectionId: CONNECTION_1 }, 9))
    })

    await waitFor(() => {
      expect(ideService.openIdeView).toHaveBeenCalledTimes(2)
    })
    const latest = ideService.openIdeView.mock.calls.at(-1)?.[1] as Record<string, unknown>
    expect(latest.connectionId).toBe(CONNECTION_2)
    expect(sessionSnapshot().recoveryByEnvironment[ENV_A]?.bindingEpoch).toBe('13')
    expect(screen.getByTestId('ide-iframe')).toBeInTheDocument()
  })
})
