import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { IdeOverlayPanel, type IdeOverlayPanelProps } from '@/features/environment/ide/IdeOverlayPanel'
import type { IdeViewState } from '@/features/environment/ide/IdePanelProvider'

const ENV_A = 'a1000000-0000-4000-8000-000000000001'
const ENV_B = 'a1000000-0000-4000-8000-000000000002'
const VIEW_A = 'c1000000-0000-4000-8000-000000000001'
const VIEW_B = 'c2000000-0000-4000-8000-000000000002'
const DIRECTORY_A = '/workspace/projects/a'
const DIRECTORY_B = '/workspace/projects/b'

function view(partial: Partial<IdeViewState> & Pick<IdeViewState, 'environmentId' | 'viewId'>): IdeViewState {
  const { environmentId, viewId, ...overrides } = partial
  return {
    environmentId,
    viewId,
    claimId: '99999999-9999-4999-8999-999999999999',
    mode: 'resume',
    status: 'ready',
    attempt: 1,
    claimInFlight: false,
    entryUrl: `/environment-ide/${environmentId}/${viewId}/__ide/bootstrap?token=fixture`,
    daemonInstanceId: 'd1000000-0000-4000-8000-000000000001',
    bindingEpoch: '7',
    directory: environmentId === ENV_A ? DIRECTORY_A : DIRECTORY_B,
    workspacePath: environmentId === ENV_A ? DIRECTORY_A : DIRECTORY_B,
    iframeLoaded: true,
    pendingShell: false,
    ...overrides,
  }
}

function baseProps(overrides: Partial<IdeOverlayPanelProps> = {}): IdeOverlayPanelProps {
  return {
    isOpen: true,
    selectedEnvironmentId: ENV_A,
    openedEnvironmentIds: [ENV_A, ENV_B],
    views: {
      [ENV_A]: view({ environmentId: ENV_A, viewId: VIEW_A }),
      [ENV_B]: view({ environmentId: ENV_B, viewId: VIEW_B }),
    },
    environments: [
      { id: ENV_A, name: 'local-node', ready: true },
      { id: ENV_B, name: 'remote-node', ready: true },
    ],
    environmentsLoading: false,
    environmentsError: false,
    identityReady: true,
    storageUnavailable: false,
    onSelectEnvironment: vi.fn(),
    onCollapse: vi.fn(),
    onCloseEnvironment: vi.fn().mockResolvedValue({ ok: true, dirty: false }),
    onDismissCloseError: vi.fn(),
    onChangeDirectory: vi.fn().mockResolvedValue({ ok: true }),
    onOpenTerminal: vi.fn(),
    onIframeLoaded: vi.fn(),
    onOpenNewView: vi.fn(),
    onRetryClaim: vi.fn(),
    onReloadEnvironments: vi.fn(),
    ...overrides,
  }
}

describe('IdeOverlayPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('keeps every opened iframe mounted while only the selected environment is visible', () => {
    render(<IdeOverlayPanel {...baseProps()} />)

    const layers = screen.getAllByTestId('ide-view-layer')
    expect(layers).toHaveLength(2)
    expect(screen.getAllByTestId('ide-iframe')).toHaveLength(2)

    const layerA = layers.find((layer) => layer.getAttribute('data-ide-environment') === ENV_A)
    const layerB = layers.find((layer) => layer.getAttribute('data-ide-environment') === ENV_B)
    expect(layerA).not.toHaveAttribute('hidden')
    expect(layerB).toHaveAttribute('hidden')
    expect(layerB).toHaveAttribute('inert')
  })

  it('dispatches explicit environment, terminal, directory and collapse actions', async () => {
    const user = userEvent.setup()
    const props = baseProps()
    render(<IdeOverlayPanel {...props} />)

    await user.click(screen.getByRole('button', { name: '环境' }))
    await user.click(screen.getByRole('option', { name: 'remote-node' }))
    expect(props.onSelectEnvironment).toHaveBeenCalledWith(ENV_B)

    await user.click(screen.getByTestId('ide-terminal-btn'))
    expect(props.onOpenTerminal).toHaveBeenCalledWith(ENV_A)

    await user.click(screen.getByTestId('ide-open-directory-btn'))
    expect(screen.getByTestId('ide-directory-input')).toHaveValue(DIRECTORY_A)
    await user.click(screen.getByRole('button', { name: '取消' }))
    expect(screen.queryByTestId('ide-directory-input')).not.toBeInTheDocument()

    await user.click(screen.getByTestId('ide-collapse-btn'))
    expect(props.onCollapse).toHaveBeenCalledTimes(1)
  })

  it('keeps the iframe after a dirty close and only sends discard from the explicit confirmation', async () => {
    const user = userEvent.setup()
    const onCloseEnvironment = vi.fn()
      .mockResolvedValueOnce({ ok: false, dirty: true })
      .mockResolvedValueOnce({ ok: true, dirty: false })
    render(<IdeOverlayPanel {...baseProps({ onCloseEnvironment })} />)

    await user.click(screen.getByTestId('ide-close-btn'))

    const dialog = await screen.findByTestId('ide-dirty-close-dialog')
    expect(within(dialog).getByText('IDE 中存在未保存的修改，已拒绝关闭。放弃这些修改并关闭视图？')).toBeVisible()
    expect(screen.getAllByTestId('ide-iframe')).toHaveLength(2)
    expect(onCloseEnvironment).toHaveBeenNthCalledWith(1, ENV_A, false)

    await user.click(screen.getByTestId('ide-discard-close-btn'))
    await waitFor(() => {
      expect(onCloseEnvironment).toHaveBeenNthCalledWith(2, ENV_A, true)
    })
  })

  it('exposes recovery and offline states without silently creating a replacement view', async () => {
    const user = userEvent.setup()
    const onRetryClaim = vi.fn()
    const onOpenNewView = vi.fn()
    const onReloadEnvironments = vi.fn()
    const unknown = view({
      environmentId: ENV_A,
      viewId: VIEW_A,
      status: 'unknown',
      entryUrl: undefined,
      bindingEpoch: undefined,
    })
    const { rerender } = render(
      <IdeOverlayPanel {...baseProps({
        views: { [ENV_A]: unknown },
        openedEnvironmentIds: [ENV_A],
        environments: [{ id: ENV_A, name: 'local-node', ready: true }],
        onRetryClaim,
        onOpenNewView,
        onReloadEnvironments,
      })} />,
    )

    expect(screen.getByTestId('ide-claim-unknown')).toBeVisible()
    await user.click(screen.getByTestId('ide-retry-claim-btn'))
    expect(onRetryClaim).toHaveBeenCalledWith(ENV_A)

    const gone = view({
      environmentId: ENV_A,
      viewId: VIEW_A,
      status: 'gone',
      entryUrl: undefined,
      bindingEpoch: undefined,
    })
    rerender(
      <IdeOverlayPanel {...baseProps({
        views: { [ENV_A]: gone },
        openedEnvironmentIds: [ENV_A],
        environments: [{ id: ENV_A, name: 'local-node', ready: true }],
        onRetryClaim,
        onOpenNewView,
        onReloadEnvironments,
      })} />,
    )
    expect(screen.getByTestId('ide-view-gone')).toBeVisible()
    await user.click(screen.getByTestId('ide-open-new-view-btn'))
    expect(onOpenNewView).toHaveBeenCalledWith(ENV_A)

    rerender(
      <IdeOverlayPanel {...baseProps({
        selectedEnvironmentId: ENV_B,
        openedEnvironmentIds: [ENV_B],
        views: {},
        environments: [{ id: ENV_B, name: 'remote-node', ready: false }],
        onReloadEnvironments,
      })} />,
    )
    expect(screen.getByTestId('ide-offline-state')).toBeVisible()
    expect(screen.queryByTestId('ide-open-new-view-btn')).not.toBeInTheDocument()
  })

  it('renders the environment selector when no environment was supplied', () => {
    render(
      <IdeOverlayPanel {...baseProps({
        selectedEnvironmentId: null,
        openedEnvironmentIds: [],
        views: {},
      })} />,
    )

    expect(screen.getByTestId('ide-select-environment-state')).toBeVisible()
    expect(screen.getByText('请选择一个环境以打开 IDE')).toBeVisible()
  })

  it('does not mount any panel surface while collapsed but keeps iframe DOM addressable', () => {
    render(<IdeOverlayPanel {...baseProps({ isOpen: false })} />)

    expect(screen.getByTestId('ide-overlay-panel')).toHaveAttribute('hidden')
    expect(screen.getAllByTestId('ide-iframe')).toHaveLength(2)
  })
})
