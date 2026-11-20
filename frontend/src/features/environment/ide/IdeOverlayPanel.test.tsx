import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useRef, useState } from 'react'
import { ApiError } from '@/shared/api/client'
import { useIdeCloseCommands } from '@/features/environment/ide/useIdeCloseCommands'
import type { IdeBrowserSessionState } from '@/features/environment/ide/ide-browser-session'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { IdeOverlayPanel, type IdeOverlayPanelProps } from '@/features/environment/ide/IdeOverlayPanel'
import type { IdeViewState } from '@/features/environment/ide/ide-view-state'

const { listIdeDirectories, closeIdeView } = vi.hoisted(() => ({ listIdeDirectories: vi.fn(), closeIdeView: vi.fn() }))

vi.mock('@/features/environment/ide/environment-ide-service', () => ({ listIdeDirectories, closeIdeView }))

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
    selectedViewId: VIEW_A,
    openedEnvironmentIds: [ENV_A, ENV_B],
    viewsByEnvironment: { [ENV_A]: [VIEW_A], [ENV_B]: [VIEW_B] },
    views: {
      [VIEW_A]: view({ environmentId: ENV_A, viewId: VIEW_A }),
      [VIEW_B]: view({ environmentId: ENV_B, viewId: VIEW_B }),
    },
    environments: [
      { id: ENV_A, name: 'local-node', ready: true },
      { id: ENV_B, name: 'remote-node', ready: true },
    ],
    environmentsLoading: false,
    environmentsError: false,
    sessionStatus: 'ready',
    storageUnavailable: false,
    recoveryError: false,
    onSelectView: vi.fn(),
    connectionState: { status: 'ready', reason: null },
    onSelectEnvironment: vi.fn(),
    onCollapse: vi.fn(),
    onCloseEnvironment: vi.fn().mockResolvedValue({ ok: true, requiresForceConfirmation: false }),
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

  it('switches views within one Environment without unmounting either iframe', async () => {
    const user = userEvent.setup()
    const onSelectView = vi.fn()
    const viewB = view({
      environmentId: ENV_A,
      viewId: VIEW_B,
      directory: '/workspace/projects/b',
      entryUrl: `/ide/${ENV_A}/${VIEW_B}/__ide/bootstrap?token=fixture-b`,
    })
    const props = baseProps({
      selectedViewId: VIEW_A,
      openedEnvironmentIds: [ENV_A],
      viewsByEnvironment: { [ENV_A]: [VIEW_A, VIEW_B] },
      views: { [VIEW_A]: view({ environmentId: ENV_A, viewId: VIEW_A }), [VIEW_B]: viewB },
      onSelectView,
    })
    const { rerender } = render(<IdeOverlayPanel {...props} />)

    const firstFrame = screen.getAllByTestId('ide-iframe')[0]
    const secondFrame = screen.getAllByTestId('ide-iframe')[1]
    expect(screen.getAllByTestId('ide-view-layer')[0]).not.toHaveAttribute('hidden')
    expect(screen.getAllByTestId('ide-view-layer')[1]).toHaveAttribute('hidden')
    await user.click(screen.getByTestId('ide-tab-strip').querySelector(`[data-ide-view="${VIEW_B}"]`) as HTMLElement)

    expect(onSelectView).toHaveBeenCalledWith(VIEW_B)
    rerender(<IdeOverlayPanel {...props} selectedViewId={VIEW_B} />)
    expect(screen.getAllByTestId('ide-iframe')[0]).toBe(firstFrame)
    expect(screen.getAllByTestId('ide-iframe')[1]).toBe(secondFrame)
    expect(screen.getAllByTestId('ide-view-layer')[0]).toHaveAttribute('hidden')
    expect(screen.getAllByTestId('ide-view-layer')[1]).not.toHaveAttribute('hidden')
  })

  it('dispatches explicit environment, terminal, directory and collapse actions', async () => {
    const user = userEvent.setup()
    const props = baseProps()
    render(<IdeOverlayPanel {...props} />)

    await user.click(screen.getByRole('button', { name: '环境' }))
    await user.click(screen.getByRole('option', { name: 'remote-node' }))
    expect(props.onSelectEnvironment).toHaveBeenCalledWith(ENV_B)

    await user.click(screen.getByTestId('ide-terminal-btn'))
    expect(props.onOpenTerminal).toHaveBeenCalledWith(VIEW_A)

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
    expect(onChangeDirectory).toHaveBeenLastCalledWith(VIEW_A, DIRECTORY_A)
    expect(recordRecentDirectory).not.toHaveBeenCalled()

    await user.click(within(dialog).getByTestId('ide-directory-confirm'))
    await waitFor(() => {
      expect(recordRecentDirectory).toHaveBeenCalledWith(ENV_A, DIRECTORY_A)
    })
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: '打开宿主目录' })).not.toBeInTheDocument()
    })
  })

  it('copies the directory path with in-place feedback instead of a new banner row', async () => {
    vi.useFakeTimers()
    try {
      const write = vi.spyOn(navigator.clipboard, 'writeText')
        .mockResolvedValueOnce(undefined)
        .mockRejectedValueOnce(new Error('denied'))
      render(<IdeOverlayPanel {...baseProps()} />)
      const panel = screen.getByTestId('ide-overlay-panel')
      const panelChildren = panel.children.length
      const button = screen.getByTestId('ide-copy-directory-btn')

      fireEvent.click(button)
      await act(async () => {})

      // 反馈落在按钮原地：图标变 ✓、悬停说明与读屏状态同步，面板不插入横幅行。
      expect(write).toHaveBeenCalledWith(DIRECTORY_A)
      expect(button.querySelector('.lucide-check')).not.toBeNull()
      expect(button).toHaveAttribute('title', '目录路径已复制')
      expect(screen.getByRole('status')).toHaveTextContent('目录路径已复制')
      expect(screen.getByRole('status')).toHaveClass('sr-only')
      expect(panel.children.length).toBe(panelChildren)
      expect(
        screen.queryByText('目录路径已复制', { selector: '.ide-panel-warning-banner' }),
      ).toBeNull()

      act(() => {
        vi.advanceTimersByTime(1500)
      })
      expect(button).toHaveAttribute('title', '复制目录路径')
      expect(button.querySelector('.lucide-copy')).not.toBeNull()

      // 剪贴板与兜底都失败时同样是原地红色 !，仍然不新增行。
      fireEvent.click(button)
      await act(async () => {})
      expect(button.querySelector('.lucide-circle-alert')).not.toBeNull()
      expect(button).toHaveClass('is-copy-failed')
      expect(button).toHaveAttribute('title', '无法复制目录路径')
      expect(panel.children.length).toBe(panelChildren)

      act(() => {
        vi.advanceTimersByTime(3000)
      })
      expect(button).toHaveAttribute('title', '复制目录路径')
      expect(button).not.toHaveClass('is-copy-failed')
    } finally {
      vi.useRealTimers()
    }
  })

  it('distinguishes unknown and unavailable control in explicit force-close confirmation', async () => {
    const user = userEvent.setup()
    const onCloseEnvironment = vi.fn().mockResolvedValueOnce({
      ok: false,
      requiresForceConfirmation: true,
      message: 'close-control-unavailable',
    })
    render(<IdeOverlayPanel {...baseProps({ onCloseEnvironment })} />)

    await user.click(screen.getByTestId('ide-close-btn'))
    expect(await screen.findByRole('alertdialog')).toHaveTextContent('IDE 控制通道不可用，关闭请求未发送。')
    expect(onCloseEnvironment).toHaveBeenCalledWith(VIEW_A, false)
  })

  it.each([
    ['IDE_OUTCOME_UNKNOWN', '命令结果未知，请先在 IDE 中确认再继续。'],
    ['IDE_CONTROL_UNAVAILABLE', 'IDE 控制通道不可用，关闭请求未发送。'],
  ])('keeps a late %s result on the original tab without opening a force dialog', async (code, message) => {
    // 用真实关闭命令接收受控的迟到回执；切标签不会改变请求的绑定或错误归属。
    const user = userEvent.setup()
    let rejectClose!: (error: ApiError) => void
    closeIdeView.mockImplementationOnce(() => new Promise((_, reject) => { rejectClose = reject }))
    const props = baseProps({
      openedEnvironmentIds: [ENV_A],
      viewsByEnvironment: { [ENV_A]: [VIEW_A, VIEW_B] },
      views: {
        [VIEW_A]: view({ environmentId: ENV_A, viewId: VIEW_A }),
        [VIEW_B]: view({ environmentId: ENV_A, viewId: VIEW_B, directory: DIRECTORY_B }),
      },
    })
    function Harness() {
      const [selected, setSelected] = useState(VIEW_A)
      const [views, setViews] = useState(props.views)
      const viewsRef = useRef(views)
      const sessionRef = useRef<IdeBrowserSessionState>({ browserSessionId: 'session', status: 'ready', sessionSaved: true })
      function commitViews(next: typeof views) {
        viewsRef.current = next
        setViews(next)
      }
      const commands = useIdeCloseCommands({
        viewsRef, sessionRef, commitViews,
        updateView: (id, attempt, patch) => {
          const current = viewsRef.current[id]
          if (current?.attempt !== attempt) return null
          const updated = { ...current, ...patch }
          commitViews({ ...viewsRef.current, [id]: updated })
          return updated
        },
        forgetView: vi.fn(), writeSession: () => true,
        registerUsageNow: async () => undefined, registerUsageQuiet: vi.fn(),
      })
      return <IdeOverlayPanel {...props} views={views} selectedViewId={selected} onSelectView={setSelected}
        onCloseEnvironment={commands.closeView} onDismissCloseError={commands.dismissCloseError} />
    }
    render(<Harness />)
    const tab = (id: string) => screen.getByTestId('ide-tab-strip').querySelector(`[data-ide-view="${id}"]`) as HTMLElement
    await user.click(screen.getByTestId('ide-close-btn'))
    expect(closeIdeView).toHaveBeenCalledWith(ENV_A, VIEW_A, expect.objectContaining({ discard: false }))
    await user.click(tab(VIEW_B))
    await act(async () => { rejectClose(new ApiError('test close error', 503, 'unavailable', undefined, code)) })
    expect(screen.queryByTestId('ide-close-error')).not.toBeInTheDocument()
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()

    await user.click(tab(VIEW_A))
    expect(screen.getByTestId('ide-close-error')).toHaveTextContent(message)
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(screen.getByTestId('ide-close-btn')).not.toBeDisabled()
    expect(screen.getAllByTestId('ide-iframe')).toHaveLength(2)
    expect(closeIdeView).toHaveBeenCalledTimes(1)
  })

  it('invalidates force confirmation when the Daemon instance changes even if the epoch is reused', async () => {
    const user = userEvent.setup()
    const onCloseEnvironment = vi.fn().mockResolvedValue({ ok: false, requiresForceConfirmation: true })
    const props = baseProps({ onCloseEnvironment })
    const { rerender } = render(<IdeOverlayPanel {...props} />)
    await user.click(screen.getByTestId('ide-close-btn'))
    expect(await screen.findByRole('alertdialog')).toBeVisible()

    rerender(<IdeOverlayPanel {...props} views={{
      ...props.views, [VIEW_A]: { ...props.views[VIEW_A], daemonInstanceId: 'replacement-instance' },
    }} />)
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(onCloseEnvironment).toHaveBeenCalledTimes(1)
  })

  it('keeps the iframe after an unknown close and only forces from explicit confirmation', async () => {
    const user = userEvent.setup()
    const onCloseEnvironment = vi.fn()
      .mockResolvedValueOnce({ ok: false, requiresForceConfirmation: true })
      .mockResolvedValueOnce({ ok: true, requiresForceConfirmation: false })
    render(<IdeOverlayPanel {...baseProps({ onCloseEnvironment })} />)

    await user.click(screen.getByTestId('ide-close-btn'))

    const dialog = await screen.findByRole('alertdialog', { name: '强制关闭 IDE 视图？' })
    expect(dialog).toHaveTextContent('关闭结果未知。')
    expect(screen.getAllByTestId('ide-iframe')).toHaveLength(2)
    expect(onCloseEnvironment).toHaveBeenNthCalledWith(1, VIEW_A, false)

    await user.click(within(dialog).getByRole('button', { name: '强制关闭' }))
    await waitFor(() => {
      expect(onCloseEnvironment).toHaveBeenNthCalledWith(2, VIEW_A, true)
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
        views: { [VIEW_A]: reconciling },
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
        views: { [VIEW_A]: gone },
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
        views: { [VIEW_A]: detached },
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
        views: { [VIEW_A]: waiting },
        openedEnvironmentIds: [ENV_A],
        onRetryClaim,
      })} />,
    )
    expect(screen.getByTestId('ide-waiting-ready')).toBeVisible()

    rerender(
      <IdeOverlayPanel {...baseProps({
        views: {
          [VIEW_A]: view({
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

  it('presents the browser event connection state separately from the IDE session', () => {
    const { rerender } = render(
      <IdeOverlayPanel {...baseProps({
        connectionState: { status: 'connecting', reason: { kind: 'connecting', transportOpen: true } },
      })} />,
    )
    expect(screen.getByTestId('ide-connecting')).toHaveTextContent('正在建立实时事件连接')

    rerender(<IdeOverlayPanel {...baseProps({
      connectionState: {
        status: 'backoff',
        reason: { kind: 'backoff', cause: 'liveness-timeout', retryAt: 0 },
      },
    })} />)
    expect(screen.getByTestId('ide-reconnecting')).toHaveTextContent('失去响应')

    // terminal 停止显示需要人工介入的原因，不显示持续重连。
    rerender(<IdeOverlayPanel {...baseProps({
      connectionState: { status: 'stopped', reason: { kind: 'stopped', cause: 'policy' } },
    })} />)
    expect(screen.getByTestId('ide-connection-stopped')).toHaveTextContent('策略限制')
    expect(screen.queryByTestId('ide-reconnecting')).not.toBeInTheDocument()

    // ready 无横幅。
    rerender(<IdeOverlayPanel {...baseProps()} />)
    expect(screen.queryByTestId('ide-connecting')).not.toBeInTheDocument()
    expect(screen.queryByTestId('ide-reconnecting')).not.toBeInTheDocument()
    expect(screen.queryByTestId('ide-connection-stopped')).not.toBeInTheDocument()
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
