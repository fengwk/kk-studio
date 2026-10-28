import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApplicationEventProvider } from '@/shared/app-events'
import { FakeWebSocketHarness, type FakeWebSocket } from '@/shared/app-events/__tests__/fake-websocket'
import { ApiError } from '@/shared/api/client'
import type {
  EnvironmentCardDTO,
  EnvironmentIdeOpenViewDTO,
} from '@/shared/api/generated/contracts'
import { queryKeys } from '@/shared/lib/query-keys'
import { IdePanelProvider } from '@/features/environment/ide/IdePanelProvider'
import { useIdePanel } from '@/features/environment/ide/ide-panel-context'
import {
  IDE_TAB_LAST_KEY,
  IDE_TAB_SESSION_KEY,
  type IdePresentation,
  type IdeRecovery,
} from '@/features/environment/ide/ide-tab-state'

const service = vi.hoisted(() => ({
  listEnvironments: vi.fn(),
  openIdeView: vi.fn(),
  changeIdeViewDirectory: vi.fn(),
  openIdeViewTerminal: vi.fn(),
  closeIdeView: vi.fn(),
}))

vi.mock('@/shared/api/environment-service', () => ({ environmentService: service }))

const ENV_A = 'a1000000-0000-4000-8000-000000000001'
const ENV_B = 'a1000000-0000-4000-8000-000000000002'
const VIEW_A = 'c1000000-0000-4000-8000-000000000001'
const DAEMON_A = 'd1000000-0000-4000-8000-000000000001'
const DAEMON_B = 'd2000000-0000-4000-8000-000000000002'
const EVENT_NODE = 'e1000000-0000-4000-8000-000000000001'
const CONNECTION = 'f1000000-0000-4000-8000-000000000001'
const CONNECTION_2 = 'f1000000-0000-4000-8000-000000000002'
const DIRECTORY_A = '/workspace/projects/a'
const DIRECTORY_B = '/workspace/projects/b'

function card(
  id: string,
  name: string,
  ready = true,
): EnvironmentCardDTO {
  return {
    id,
    name,
    ready,
    status: ready ? 'READY' : 'OFFLINE',
    version: '1',
    createTime: 1790812800,
    updateTime: 1790812800,
    capabilities: [],
    statusExpiresAt: null,
    lastSeen: null,
  }
}

const CARD_A = card(ENV_A, 'local-node')
const CARD_B = card(ENV_B, 'remote-node')

function ideViewResponse(
  environmentId: string,
  request: EnvironmentIdeOpenViewDTO,
  sequence: number,
) {
  return {
    viewId: request.viewId,
    environmentId,
    documentId: request.documentId,
    eventNodeId: request.eventNodeId,
    connectionId: request.connectionId,
    claimId: request.claimId,
    daemonInstanceId: environmentId === ENV_A ? DAEMON_A : DAEMON_B,
    bindingEpoch: String(sequence + 10),
    directory: environmentId === ENV_A ? DIRECTORY_A : DIRECTORY_B,
    workspacePath: environmentId === ENV_A ? DIRECTORY_A : DIRECTORY_B,
    state: 'OPEN',
    entryUrl: `/environment-ide/${environmentId}/${request.viewId}/__ide/bootstrap?token=t-${sequence}`,
  }
}

function usageFrames(socket: FakeWebSocket) {
  return socket.sentMessages().filter((message) => message.type === 'ide-use')
}

function sessionSnapshot(
  presentation: IdePresentation,
  recoveryByEnvironment: Record<string, IdeRecovery>,
): string {
  return JSON.stringify({
    version: 1,
    presentation,
    recoveryByEnvironment,
  })
}

function lastSnapshot(presentation: IdePresentation): string {
  return JSON.stringify({ version: 1, presentation })
}

function Harness() {
  const { openEnvironment } = useIdePanel()
  return (
    <div>
      <button
        data-testid="open-a"
        onClick={() => openEnvironment({
          environment: { id: ENV_A, name: 'local-node', ready: true },
          mode: 'files',
        })}
      >
        A
      </button>
      <button
        data-testid="open-b"
        onClick={() => openEnvironment({
          environment: { id: ENV_B, name: 'remote-node', ready: true },
          mode: 'files',
        })}
      >
        B
      </button>
      <button
        data-testid="open-b-shell"
        onClick={() => openEnvironment({
          environment: { id: ENV_B, name: 'remote-node', ready: true },
          mode: 'shell',
        })}
      >
        B shell
      </button>
    </div>
  )
}

function renderPanel() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
  })
  const sockets = new FakeWebSocketHarness()
  const view = render(
    <QueryClientProvider client={queryClient}>
      <ApplicationEventProvider url="ws://test/api/events/v2" socketFactory={sockets.factory}>
        <IdePanelProvider storage={{ sessionStorage, localStorage }}>
          <Harness />
        </IdePanelProvider>
      </ApplicationEventProvider>
    </QueryClientProvider>,
  )
  return { ...view, queryClient, sockets }
}

function connect(
  sockets: FakeWebSocketHarness,
  connectionId = CONNECTION,
): FakeWebSocket {
  const socket = sockets.openLatest()
  const originalSend = socket.send.bind(socket)
  socket.send = (data: string) => {
    const frame = JSON.parse(data) as { type?: string; revision?: string }
    originalSend(data)
    if (frame.type === 'ide-use' && frame.revision != null) {
      socket.onmessage?.({
        data: JSON.stringify({
          version: 2,
          type: 'ide-use-accepted',
          revision: frame.revision,
        }),
      })
    }
  }
  socket.emitServer({ type: 'connected', eventNodeId: EVENT_NODE, connectionId })
  return socket
}

async function clickOpenA(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByTestId('open-a'))
}

async function openA(user: ReturnType<typeof userEvent.setup>) {
  await clickOpenA(user)
  await waitFor(() => {
    expect(screen.getByTestId('ide-iframe')).toBeInTheDocument()
  })
}

describe('IdePanelProvider', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    service.listEnvironments.mockReset()
    service.openIdeView.mockReset()
    service.changeIdeViewDirectory.mockReset()
    service.openIdeViewTerminal.mockReset()
    service.closeIdeView.mockReset()
    service.listEnvironments.mockResolvedValue([CARD_A, CARD_B])
    service.closeIdeView.mockResolvedValue(undefined)
    service.openIdeViewTerminal.mockResolvedValue(undefined)
    service.changeIdeViewDirectory.mockResolvedValue({
      viewId: VIEW_A,
      environmentId: ENV_A,
      documentId: '00000000-0000-4000-8000-000000000001',
      eventNodeId: EVENT_NODE,
      connectionId: CONNECTION,
      claimId: '99999999-9999-4999-8999-999999999999',
      daemonInstanceId: DAEMON_A,
      bindingEpoch: '11',
      directory: '/workspace/projects/next',
      workspacePath: '/workspace/projects/next',
      state: 'OPEN',
    })
    let sequence = 0
    service.openIdeView.mockImplementation(
      async (environmentId: string, request: EnvironmentIdeOpenViewDTO) => {
        sequence += 1
        return ideViewResponse(environmentId, request, sequence)
      },
    )
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('restores the session viewId through an ack, resume claim and holding epoch without touching local', async () => {
    const presentation: IdePresentation = {
      panelVisible: true,
      selectedEnvironmentId: ENV_A,
      openedEnvironmentIds: [ENV_A],
    }
    const localValue = lastSnapshot({
      panelVisible: true,
      selectedEnvironmentId: ENV_B,
      openedEnvironmentIds: [ENV_B],
    })
    sessionStorage.setItem(IDE_TAB_SESSION_KEY, sessionSnapshot(presentation, {
      [ENV_A]: {
        viewId: VIEW_A,
        daemonInstanceId: DAEMON_A,
        directory: DIRECTORY_A,
      },
    }))
    localStorage.setItem(IDE_TAB_LAST_KEY, localValue)

    const { sockets } = renderPanel()
    const socket = connect(sockets)

    await waitFor(() => {
      expect(screen.getByTestId('ide-iframe')).toBeInTheDocument()
    })

    expect(service.openIdeView).toHaveBeenCalledTimes(1)
    const [environmentId, request] = service.openIdeView.mock.calls[0] as [
      string,
      EnvironmentIdeOpenViewDTO,
    ]
    expect(environmentId).toBe(ENV_A)
    expect(request).toMatchObject({
      viewId: VIEW_A,
      mode: 'resume',
      eventNodeId: EVENT_NODE,
      connectionId: CONNECTION,
    })
    expect(request.claimId).toMatch(/^[0-9a-f-]{36}$/)
    expect(request.documentId).toMatch(/^[0-9a-f-]{36}$/)
    expect(request).not.toHaveProperty('directory')

    await waitFor(() => {
      const usage = usageFrames(socket).at(-1)
      expect(usage?.views).toEqual([{
        environmentId: ENV_A,
        viewId: VIEW_A,
        claimId: request.claimId,
        bindingEpoch: '11',
      }])
    })
    expect(localStorage.getItem(IDE_TAB_LAST_KEY)).toBe(localValue)
    expect(JSON.parse(sessionStorage.getItem(IDE_TAB_SESSION_KEY) ?? '{}')).toMatchObject({
      presentation,
      recoveryByEnvironment: {
        [ENV_A]: {
          viewId: VIEW_A,
          daemonInstanceId: DAEMON_A,
          directory: DIRECTORY_A,
        },
      },
    })
    expect(screen.getByTestId('ide-iframe')).toHaveAttribute(
      'src',
      `/environment-ide/${ENV_A}/${VIEW_A}/__ide/bootstrap?token=t-1`,
    )
  })

  it('keeps one iframe per environment mounted and switches visibility without creating duplicate views', async () => {
    const user = userEvent.setup()
    const { sockets } = renderPanel()
    const socket = connect(sockets)

    await openA(user)
    await user.click(screen.getByTestId('open-b'))
    await waitFor(() => {
      expect(screen.getAllByTestId('ide-iframe')).toHaveLength(2)
    })

    const calls = service.openIdeView.mock.calls as Array<[string, EnvironmentIdeOpenViewDTO]>
    expect(calls.map(([environmentId]) => environmentId)).toEqual([ENV_A, ENV_B])
    expect(calls.map(([, request]) => request.mode)).toEqual(['create', 'create'])
    expect(calls[0]?.[1].viewId).not.toBe(calls[1]?.[1].viewId)
    expect(calls[0]?.[1].documentId).toBe(calls[1]?.[1].documentId)
    expect(calls[0]?.[1]).not.toHaveProperty('directory')

    const layerA = document.querySelector(`[data-testid="ide-view-layer"][data-ide-environment="${ENV_A}"]`)
    const layerB = document.querySelector(`[data-testid="ide-view-layer"][data-ide-environment="${ENV_B}"]`)
    expect(layerA).toHaveAttribute('hidden')
    expect(layerB).not.toHaveAttribute('hidden')

    await waitFor(() => {
      const usage = usageFrames(socket).at(-1)
      expect(usage?.views).toHaveLength(2)
      expect(usage?.views.find((entry) => entry.environmentId === ENV_A)?.bindingEpoch).toBe('11')
      expect(usage?.views.find((entry) => entry.environmentId === ENV_B)?.bindingEpoch).toBe('12')
    })

    await user.click(screen.getByTestId('open-a'))
    expect(service.openIdeView).toHaveBeenCalledTimes(2)
    expect(document.querySelector(`[data-testid="ide-view-layer"][data-ide-environment="${ENV_A}"]`))
      .not.toHaveAttribute('hidden')
    expect(screen.getAllByTestId('ide-iframe')).toHaveLength(2)
  })

  it('replaces a copied-tab session view only after VIEW_IN_USE and never claims the original view', async () => {
    sessionStorage.setItem(IDE_TAB_SESSION_KEY, sessionSnapshot({
      panelVisible: true,
      selectedEnvironmentId: ENV_A,
      openedEnvironmentIds: [ENV_A],
    }, {
      [ENV_A]: {
        viewId: VIEW_A,
        daemonInstanceId: DAEMON_A,
        directory: DIRECTORY_A,
      },
    }))
    service.openIdeView
      .mockRejectedValueOnce(new ApiError('IDE view is in use', 409, 'IDE_UNAVAILABLE', {
        detail: 'VIEW_IN_USE',
      }))

    const { sockets } = renderPanel()
    connect(sockets)
    await waitFor(() => {
      expect(service.openIdeView).toHaveBeenCalledTimes(2)
    })
    await waitFor(() => {
      expect(screen.getByTestId('ide-iframe')).toBeInTheDocument()
    })

    const calls = service.openIdeView.mock.calls as Array<[string, EnvironmentIdeOpenViewDTO]>
    expect(calls[0]?.[1]).toMatchObject({ viewId: VIEW_A, mode: 'resume' })
    expect(calls[1]?.[1].mode).toBe('create')
    expect(calls[1]?.[1].viewId).not.toBe(VIEW_A)
    expect(calls[1]?.[1].directory).toBe(DIRECTORY_A)
    expect(calls[1]?.[1].claimId).not.toBe(calls[0]?.[1].claimId)

    const recovery = JSON.parse(sessionStorage.getItem(IDE_TAB_SESSION_KEY) ?? '{}')
      .recoveryByEnvironment[ENV_A] as IdeRecovery
    expect(recovery.viewId).toBe(calls[1]?.[1].viewId)
    expect(recovery.daemonInstanceId).toBe(DAEMON_A)
    expect(localStorage.getItem(IDE_TAB_LAST_KEY)).toBeNull()
  })

  it('reconciles an unknown HTTP result only with the original claimId and request identity', async () => {
    const user = userEvent.setup()
    service.openIdeView.mockRejectedValueOnce(new ApiError('network unavailable'))

    const { sockets } = renderPanel()
    connect(sockets)
    await clickOpenA(user)

    await waitFor(() => {
      expect(screen.getByTestId('ide-claim-unknown')).toBeVisible()
    })
    expect(service.openIdeView).toHaveBeenCalledTimes(1)
    const firstRequest = service.openIdeView.mock.calls[0]?.[1] as EnvironmentIdeOpenViewDTO

    await user.click(screen.getByTestId('ide-retry-claim-btn'))
    await waitFor(() => {
      expect(service.openIdeView).toHaveBeenCalledTimes(2)
    })
    const secondRequest = service.openIdeView.mock.calls[1]?.[1] as EnvironmentIdeOpenViewDTO
    expect(secondRequest).toEqual(firstRequest)
    expect(secondRequest.mode).toBe('create')
    await waitFor(() => {
      expect(screen.getByTestId('ide-iframe')).toBeInTheDocument()
    })
    expect(service.changeIdeViewDirectory).not.toHaveBeenCalled()
    expect(service.openIdeViewTerminal).not.toHaveBeenCalled()
  })

  it('resumes the same view on a new connection and detaches usage on pagehide without deleting', async () => {
    const user = userEvent.setup()
    const { sockets } = renderPanel()
    const socket = connect(sockets)
    await openA(user)
    expect(service.openIdeView).toHaveBeenCalledTimes(1)
    const firstViewId = (service.openIdeView.mock.calls[0]?.[1] as EnvironmentIdeOpenViewDTO).viewId
    const firstClaimId = (service.openIdeView.mock.calls[0]?.[1] as EnvironmentIdeOpenViewDTO).claimId

    socket.emitServer({ type: 'connected', eventNodeId: EVENT_NODE, connectionId: CONNECTION_2 })
    await waitFor(() => {
      expect(service.openIdeView).toHaveBeenCalledTimes(2)
    })
    const reconnect = service.openIdeView.mock.calls[1]?.[1] as EnvironmentIdeOpenViewDTO
    expect(reconnect).toMatchObject({
      viewId: firstViewId,
      mode: 'resume',
      connectionId: CONNECTION_2,
    })
    expect(reconnect.claimId).not.toBe(firstClaimId)
    expect(reconnect).not.toHaveProperty('directory')

    const closeCallsBeforeHide = service.closeIdeView.mock.calls.length
    const event = new Event('pagehide')
    Object.defineProperty(event, 'persisted', { value: true })
    act(() => {
      window.dispatchEvent(event)
    })
    await waitFor(() => {
      const usage = usageFrames(socket).at(-1)
      expect(usage?.views).toEqual([])
    })
    expect(service.closeIdeView).toHaveBeenCalledTimes(closeCallsBeforeHide)

    const pageShow = new Event('pageshow')
    Object.defineProperty(pageShow, 'persisted', { value: true })
    act(() => {
      window.dispatchEvent(pageShow)
    })
    await waitFor(() => {
      const usage = usageFrames(socket).at(-1)
      expect(usage?.views).toHaveLength(1)
      expect(usage?.views[0]?.viewId).toBe(firstViewId)
    })
  })

  it('keeps the iframe on a dirty close and sends discard only after explicit confirmation', async () => {
    const user = userEvent.setup()
    service.closeIdeView
      .mockRejectedValueOnce(new ApiError('unsaved changes; save or discard', 409, 'IDE_UNAVAILABLE', {
        detail: 'DIRTY: save or discard',
      }))
      .mockResolvedValueOnce(undefined)
    const { sockets } = renderPanel()
    connect(sockets)
    await openA(user)

    await user.click(screen.getByTestId('ide-close-btn'))
    await waitFor(() => {
      expect(screen.getByTestId('ide-dirty-close-dialog')).toBeInTheDocument()
    })
    expect(screen.getByTestId('ide-iframe')).toBeInTheDocument()
    expect(service.closeIdeView).toHaveBeenNthCalledWith(1, ENV_A, expect.any(String), expect.objectContaining({
      bindingEpoch: '11',
      discard: false,
    }))

    await user.click(screen.getByTestId('ide-discard-close-btn'))
    await waitFor(() => {
      expect(service.closeIdeView).toHaveBeenCalledTimes(2)
    })
    expect(service.closeIdeView).toHaveBeenNthCalledWith(2, ENV_A, expect.any(String), expect.objectContaining({
      bindingEpoch: '11',
      discard: true,
    }))
    await waitFor(() => {
      expect(screen.queryByTestId('ide-iframe')).not.toBeInTheDocument()
    })
    expect(screen.getByTestId('ide-overlay-panel')).toHaveAttribute('hidden')
    const last = JSON.parse(localStorage.getItem(IDE_TAB_LAST_KEY) ?? '{}') as {
      presentation: IdePresentation
    }
    expect(last.presentation.openedEnvironmentIds).toEqual([])
  })

  it('retains an offline view and its iframe, but removes an authoritative deletion without DELETE', async () => {
    const user = userEvent.setup()
    const { sockets, queryClient } = renderPanel()
    connect(sockets)
    await openA(user)

    act(() => {
      queryClient.setQueryData(queryKeys.environments.list, [card(ENV_A, 'local-node', false)])
    })
    await waitFor(() => {
      expect(screen.getByTestId('ide-offline-banner')).toBeVisible()
    })
    expect(screen.getByTestId('ide-iframe')).toBeInTheDocument()
    expect(service.closeIdeView).not.toHaveBeenCalled()

    act(() => {
      queryClient.setQueryData(queryKeys.environments.list, [])
    })
    await waitFor(() => {
      expect(screen.queryByTestId('ide-iframe')).not.toBeInTheDocument()
    })
    expect(screen.getByTestId('ide-overlay-panel')).toHaveAttribute('hidden')
    expect(service.closeIdeView).not.toHaveBeenCalled()
  })
})
