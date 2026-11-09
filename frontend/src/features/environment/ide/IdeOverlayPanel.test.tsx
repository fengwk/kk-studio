import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { IdeOverlayPanel, type IdeOverlayPanelProps } from '@/features/environment/ide/IdeOverlayPanel'
import type { IdeViewState } from '@/features/environment/ide/IdePanelProvider'

const listIdeDirectories = vi.hoisted(() => vi.fn())

vi.mock('@/features/environment/ide/environment-ide-service', () => ({ listIdeDirectories }))

const ENV_A = 'a1000000-0000-4000-8000-000000000001'
const ENV_B = 'a1000000-0000-4000-8000-000000000002'
const VIEW_A = 'c1000000-0000-4000-8000-000000000001'
const VIEW_B = 'c2000000-0000-4000-8000-000000000002'
const DAEMON_A = 'd1000000-0000-4000-8000-000000000001'
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
    entryUrl: `/ide/${environmentId}/${viewId}/__ide/bootstrap?token=fixture`,
    daemonInstanceId: DAEMON_A,
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
    sessionStatus: 'ready',
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
    readRecentDirectories: vi.fn().mockReturnValue([]),
    recordRecentDirectory: vi.fn().mockReturnValue([]),
    ...overrides,
  }
}

const HOME_LISTING = {
  path: '/home/user',
  parentPath: '/home',
  truncated: false,
  children: [
    { name: 'projects', path: '/home/user/projects' },
    { name: 'notes', path: '/home/user/notes' },
  ],
}

describe('IdeOverlayPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    listIdeDirectories.mockReset()
    listIdeDirectories.mockResolvedValue(HOME_LISTING)
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
    const dialog = await screen.findByRole('dialog', { name: '打开宿主目录' })
    expect(await within(dialog).findByTestId('ide-directory-location')).toHaveTextContent('/home/user')
    expect(within(dialog).getByText('projects')).toBeVisible()
    await user.click(within(dialog).getByRole('button', { name: '取消' }))
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: '打开宿主目录' })).not.toBeInTheDocument()
    })

    await user.click(screen.getByTestId('ide-collapse-btn'))
    expect(props.onCollapse).toHaveBeenCalledTimes(1)
  })

  it('records the switched directory only after the change succeeds', async () => {
    const user = userEvent.setup()
    const onChangeDirectory = vi.fn()
      .mockResolvedValueOnce({ ok: false, message: 'directory-failed' })
      .mockResolvedValueOnce({ ok: true })
    const readRecentDirectories = vi.fn().mockReturnValue(['/workspace/projects/previous'])
    const recordRecentDirectory = vi.fn().mockReturnValue([DIRECTORY_A, '/workspace/projects/previous'])
    render(
      <IdeOverlayPanel
        {...baseProps({ onChangeDirectory, readRecentDirectories, recordRecentDirectory })}
      />,
    )

    await user.click(screen.getByTestId('ide-open-directory-btn'))
    const dialog = await screen.findByRole('dialog', { name: '打开宿主目录' })
    expect(readRecentDirectories).toHaveBeenCalledWith(ENV_A)
    expect(within(dialog).getByText('/workspace/projects/previous')).toBeVisible()

    await user.click(within(dialog).getByTestId('ide-directory-confirm'))
    expect(await within(dialog).findByTestId('ide-directory-error')).toHaveTextContent('切换目录失败')
    expect(onChangeDirectory).toHaveBeenLastCalledWith(ENV_A, DIRECTORY_A)
    expect(recordRecentDirectory).not.toHaveBeenCalled()

    await user.click(within(dialog).getByTestId('ide-directory-confirm'))
    await waitFor(() => {
      expect(recordRecentDirectory).toHaveBeenCalledWith(ENV_A, DIRECTORY_A)
    })
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: '打开宿主目录' })).not.toBeInTheDocument()
    })
  })

  it('copies the full directory path and reports clipboard errors', async () => {
    const user = userEvent.setup()
    const write = vi.spyOn(navigator.clipboard, 'writeText')
      .mockResolvedValueOnce(undefined)
      .mockRejectedValueOnce(new Error('denied'))
    render(<IdeOverlayPanel {...baseProps()} />)
    await user.click(screen.getByTestId('ide-copy-directory-btn'))
    expect(write).toHaveBeenCalledWith(DIRECTORY_A)
    expect(await screen.findByText('目录路径已复制')).toBeVisible()
    await user.click(screen.getByTestId('ide-copy-directory-btn'))
    expect(await screen.findByText('无法复制目录路径')).toBeVisible()
  })

  it('keeps the iframe after an unknown close and only forces from explicit confirmation', async () => {
    const user = userEvent.setup()
    const onCloseEnvironment = vi.fn()
      .mockResolvedValueOnce({ ok: false, dirty: true })
      .mockResolvedValueOnce({ ok: true, dirty: false })
    render(<IdeOverlayPanel {...baseProps({ onCloseEnvironment })} />)

    await user.click(screen.getByTestId('ide-close-btn'))

    const dialog = await screen.findByRole('alertdialog', { name: '强制关闭 IDE 视图？' })
    expect(screen.getAllByTestId('ide-iframe')).toHaveLength(2)
    expect(onCloseEnvironment).toHaveBeenNthCalledWith(1, ENV_A, false)

    await user.click(within(dialog).getByRole('button', { name: '强制关闭' }))
    await waitFor(() => {
      expect(onCloseEnvironment).toHaveBeenNthCalledWith(2, ENV_A, true)
    })
  })

  it('exposes reconciling, gone and detached states without silently creating a replacement view', async () => {
    const user = userEvent.setup()
    const onRetryClaim = vi.fn()
    const onOpenNewView = vi.fn()
    const onReloadEnvironments = vi.fn()
    const reconciling = view({
      environmentId: ENV_A,
      viewId: VIEW_A,
      status: 'reconciling',
      entryUrl: undefined,
      bindingEpoch: undefined,
    })
    const { rerender } = render(
      <IdeOverlayPanel {...baseProps({
        views: { [ENV_A]: reconciling },
        openedEnvironmentIds: [ENV_A],
        environments: [{ id: ENV_A, name: 'local-node', ready: true }],
        onRetryClaim,
        onOpenNewView,
        onReloadEnvironments,
      })} />,
    )

    expect(screen.getByTestId('ide-reconciling')).toBeVisible()
    expect(screen.queryByTestId('ide-iframe')).not.toBeInTheDocument()

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

    const detached = view({
      environmentId: ENV_A,
      viewId: VIEW_A,
      status: 'detached',
      entryUrl: undefined,
      bindingEpoch: undefined,
    })
    rerender(
      <IdeOverlayPanel {...baseProps({
        views: { [ENV_A]: detached },
        openedEnvironmentIds: [ENV_A],
        environments: [{ id: ENV_A, name: 'local-node', ready: true }],
        onRetryClaim,
        onOpenNewView,
        onReloadEnvironments,
      })} />,
    )
    expect(screen.getByTestId('ide-view-detached')).toBeVisible()
    expect(screen.getByTestId('ide-view-detached')).toHaveTextContent('其他浏览器会话')
    expect(screen.queryByTestId('ide-iframe')).not.toBeInTheDocument()
  })

  it('renders waiting-ready and explicit retry for an error state', async () => {
    const user = userEvent.setup()
    const onRetryClaim = vi.fn()
    const waiting = view({
      environmentId: ENV_A,
      viewId: VIEW_A,
      status: 'waiting-ready',
      entryUrl: undefined,
      bindingEpoch: undefined,
    })
    const { rerender } = render(
      <IdeOverlayPanel {...baseProps({
        views: { [ENV_A]: waiting },
        openedEnvironmentIds: [ENV_A],
        onRetryClaim,
      })} />,
    )
    expect(screen.getByTestId('ide-waiting-ready')).toBeVisible()

    rerender(
      <IdeOverlayPanel {...baseProps({
        views: {
          [ENV_A]: view({
            environmentId: ENV_A,
            viewId: VIEW_A,
            status: 'error',
            entryUrl: undefined,
            bindingEpoch: undefined,
            errorMessage: 'boom',
          }),
        },
        openedEnvironmentIds: [ENV_A],
        onRetryClaim,
      })} />,
    )
    expect(screen.getByTestId('ide-error-banner')).toHaveTextContent('boom')
    await user.click(screen.getByTestId('ide-retry-claim-btn'))
    expect(onRetryClaim).toHaveBeenCalledWith(ENV_A)
  })

  it('reports Web Locks capability and ownership conflicts explicitly', () => {
    const { rerender } = render(
      <IdeOverlayPanel {...baseProps({ sessionStatus: 'unsupported' })} />,
    )
    expect(screen.getByTestId('ide-session-unavailable')).toHaveTextContent('Web Locks')

    rerender(<IdeOverlayPanel {...baseProps({ sessionStatus: 'conflict' })} />)
    expect(screen.getByTestId('ide-session-unavailable')).toHaveTextContent('另一个活动页面')
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
