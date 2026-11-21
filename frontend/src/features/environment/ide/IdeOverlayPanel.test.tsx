import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useRef, useState } from 'react'
import { ApiError } from '@/shared/api/client'
import { useIdeCloseCommands } from '@/features/environment/ide/useIdeCloseCommands'
import type { IdeBrowserSessionState } from '@/features/environment/ide/ide-browser-session'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { IdeOverlayPanel, type IdeOverlayPanelProps } from '@/features/environment/ide/IdeOverlayPanel'
import type { IdeViewState } from '@/features/environment/ide/ide-view-state'

const { closeIdeView } = vi.hoisted(() => ({ closeIdeView: vi.fn() }))

vi.mock('@/features/environment/ide/environment-ide-service', () => ({ closeIdeView }))

const ENV_A = 'a1000000-0000-4000-8000-000000000001'
const ENV_B = 'a1000000-0000-4000-8000-000000000002'
const VIEW_A = 'c1000000-0000-4000-8000-000000000001'
const VIEW_B = 'c2000000-0000-4000-8000-000000000002'
const DAEMON_A = 'd1000000-0000-4000-8000-000000000001'
const DIRECTORY_A = '/workspace/projects/a'
const DIRECTORY_B = '/workspace/projects/b'
const NATIVE_ACTION_TYPE = 'kk-studio.ide.native-action'

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
    onIframeLoaded: vi.fn(),
    onOpenNewView: vi.fn(),
    onRetryClaim: vi.fn(),
    ...overrides,
  }
}

function tabFor(viewId: string): HTMLElement {
  const element = screen.getAllByTestId('ide-tab').find(
    (candidate) => candidate.getAttribute('data-ide-view') === viewId,
  )
  if (element == null) {
    throw new Error(`Missing tab for ${viewId}`)
  }
  return element
}

/** 标签容器只是外壳，选中态与关闭都在它内部的两个按钮上。 */
function tabSelectButton(viewId: string): HTMLElement {
  return within(tabFor(viewId)).getAllByRole('button')[0]!
}

function tabCloseButton(viewId: string): HTMLElement {
  return within(tabFor(viewId)).getByTestId('ide-close-btn')
}

function frameFor(viewId: string): HTMLIFrameElement {
  const element = screen.getAllByTestId('ide-iframe').find(
    (candidate) => candidate.getAttribute('data-ide-view') === viewId,
  )
  if (element == null) {
    throw new Error(`Missing iframe for ${viewId}`)
  }
  return element as HTMLIFrameElement
}

function nativeAction(action: string, viewId = VIEW_A, directory?: string): Record<string, unknown> {
  return {
    type: NATIVE_ACTION_TYPE,
    viewId,
    bindingEpoch: '7',
    action,
    ...(directory == null ? {} : { directory }),
  }
}

/**
 * 面板只接受同源且来自当前可见 iframe 的 Native 消息；这里显式构造来源与 origin，
 * 真实 postMessage 链路由 Chromium 验收覆盖。
 */
function postNative(
  data: unknown,
  options: { frame?: HTMLIFrameElement; origin?: string; source?: Window | null } = {},
): void {
  const source = options.source === undefined ? (options.frame ?? frameFor(VIEW_A)).contentWindow : options.source
  act(() => {
    window.dispatchEvent(new MessageEvent('message', {
      data,
      origin: options.origin ?? window.location.origin,
      source,
    }))
  })
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

    // 工具栏只列当前 Environment 的标签。
    expect(screen.getAllByTestId('ide-tab')).toHaveLength(1)
    expect(tabFor(VIEW_A)).toBeVisible()
  })

  it('switches views within one Environment without unmounting either iframe', async () => {
    const user = userEvent.setup()
    const onSelectView = vi.fn()
    const props = baseProps({
      openedEnvironmentIds: [ENV_A],
      viewsByEnvironment: { [ENV_A]: [VIEW_A, VIEW_B] },
      views: {
        [VIEW_A]: view({ environmentId: ENV_A, viewId: VIEW_A }),
        [VIEW_B]: view({ environmentId: ENV_A, viewId: VIEW_B, directory: DIRECTORY_B }),
      },
      onSelectView,
    })
    const { rerender } = render(<IdeOverlayPanel {...props} />)

    const firstFrame = screen.getAllByTestId('ide-iframe')[0]
    const secondFrame = screen.getAllByTestId('ide-iframe')[1]
    expect(screen.getAllByTestId('ide-view-layer')[0]).not.toHaveAttribute('hidden')
    expect(screen.getAllByTestId('ide-view-layer')[1]).toHaveAttribute('hidden')
    // 每个标签是 ide-tab 外壳：内部一个选择按钮 + 一个关闭按钮。
    const tabWrappers = screen.getAllByTestId('ide-tab')
    expect(tabWrappers).toHaveLength(2)
    expect(within(tabWrappers[1]!).getAllByRole('button')).toHaveLength(2)
    expect(tabSelectButton(VIEW_B)).toHaveTextContent('b')
    expect(tabCloseButton(VIEW_B)).toHaveAccessibleName('关闭 · b')
    expect(tabSelectButton(VIEW_A)).toHaveAttribute('aria-current', 'page')
    expect(tabSelectButton(VIEW_B)).not.toHaveAttribute('aria-current')

    await user.click(tabSelectButton(VIEW_B))

    expect(onSelectView).toHaveBeenCalledWith(VIEW_B)
    rerender(<IdeOverlayPanel {...props} selectedViewId={VIEW_B} />)
    expect(screen.getAllByTestId('ide-iframe')[0]).toBe(firstFrame)
    expect(screen.getAllByTestId('ide-iframe')[1]).toBe(secondFrame)
    expect(screen.getAllByTestId('ide-view-layer')[0]).toHaveAttribute('hidden')
    expect(screen.getAllByTestId('ide-view-layer')[1]).not.toHaveAttribute('hidden')
    expect(tabSelectButton(VIEW_B)).toHaveAttribute('aria-current', 'page')
    expect(tabSelectButton(VIEW_A)).not.toHaveAttribute('aria-current')
  })

  it('dispatches environment, explicit new view and collapse from the toolbar', async () => {
    const user = userEvent.setup()
    const props = baseProps()
    render(<IdeOverlayPanel {...props} />)

    await user.click(screen.getByRole('button', { name: '环境' }))
    await user.click(screen.getByRole('option', { name: 'remote-node' }))
    expect(props.onSelectEnvironment).toHaveBeenCalledWith(ENV_B)

    await user.click(screen.getByTestId('ide-open-workspace-btn'))
    expect(props.onOpenNewView).toHaveBeenCalledWith(ENV_A)

    await user.click(screen.getByTestId('ide-collapse-btn'))
    expect(props.onCollapse).toHaveBeenCalledTimes(1)
  })

  it('keeps only the toolbar row when no view is open and offers an explicit new view', async () => {
    const user = userEvent.setup()
    const props = baseProps({
      selectedViewId: null,
      openedEnvironmentIds: [ENV_A],
      viewsByEnvironment: {},
      views: {},
    })
    render(<IdeOverlayPanel {...props} />)

    expect(screen.queryAllByTestId('ide-tab')).toHaveLength(0)
    expect(screen.queryByTestId('ide-iframe')).not.toBeInTheDocument()
    // 无视图时正文不再渲染说明页。
    const body = screen.getByTestId('ide-panel-body')
    expect(body.querySelector('.ide-global-view-stack')).toBeEmptyDOMElement()
    expect(screen.queryByTestId('ide-opening-state')).not.toBeInTheDocument()
    expect(screen.queryByTestId('ide-offline-state')).not.toBeInTheDocument()

    expect(screen.getByRole('button', { name: '环境' })).toBeVisible()
    expect(screen.getByTestId('ide-collapse-btn')).toBeVisible()
    const openWorkspace = screen.getByTestId('ide-open-workspace-btn')
    expect(openWorkspace).toBeEnabled()
    await user.click(openWorkspace)
    expect(props.onOpenNewView).toHaveBeenCalledWith(ENV_A)
  })

  it('keeps the toolbar without a workspace body when no environment is selected', () => {
    render(<IdeOverlayPanel {...baseProps({
      selectedEnvironmentId: null,
      selectedViewId: null,
      openedEnvironmentIds: [],
      viewsByEnvironment: {},
      views: {},
    })} />)

    expect(screen.getByRole('button', { name: '环境' })).toBeVisible()
    expect(screen.queryByTestId('ide-open-workspace-btn')).not.toBeInTheDocument()
    expect(screen.queryAllByTestId('ide-tab')).toHaveLength(0)
    expect(screen.getByTestId('ide-panel-body').querySelector('.ide-global-view-stack')).toBeEmptyDOMElement()
  })

  it('disables the explicit new view while the environment is offline', () => {
    render(<IdeOverlayPanel {...baseProps({
      environments: [{ id: ENV_A, name: 'local-node', ready: false }],
    })} />)

    expect(screen.getByTestId('ide-open-workspace-btn')).toBeDisabled()
  })

  it('maps native open-folder to the selected view and reports a failed change on that view', async () => {
    const user = userEvent.setup()
    const onChangeDirectory = vi.fn()
      .mockResolvedValueOnce({ ok: false, message: 'directory-failed' })
      .mockResolvedValueOnce({ ok: true })
    render(<IdeOverlayPanel {...baseProps({ onChangeDirectory })} />)

    postNative(nativeAction('open-folder', VIEW_A, DIRECTORY_B))
    expect(onChangeDirectory).toHaveBeenCalledWith(VIEW_A, DIRECTORY_B)
    expect(await screen.findByRole('alert')).toHaveTextContent('切换目录失败')

    await user.click(screen.getByRole('button', { name: '忽略' }))
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()

    postNative(nativeAction('open-folder', VIEW_A, '/workspace/projects/c'))
    await waitFor(() => {
      expect(onChangeDirectory).toHaveBeenCalledTimes(2)
    })
    expect(onChangeDirectory).toHaveBeenLastCalledWith(VIEW_A, '/workspace/projects/c')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('maps native new-window to an explicit view and close-workspace to the tab close', async () => {
    const props = baseProps()
    render(<IdeOverlayPanel {...props} />)

    postNative(nativeAction('new-window'))
    expect(props.onOpenNewView).toHaveBeenCalledWith(ENV_A)

    postNative(nativeAction('close-workspace'))
    await waitFor(() => {
      expect(props.onCloseEnvironment).toHaveBeenCalledWith(VIEW_A, false)
    })
  })

  it('does not create a view for native new-window while the environment is offline', () => {
    const props = baseProps({ environments: [{ id: ENV_A, name: 'local-node', ready: false }] })
    render(<IdeOverlayPanel {...props} />)

    postNative(nativeAction('new-window'))
    expect(props.onOpenNewView).not.toHaveBeenCalled()
  })

  it('accepts native actions only from the visible frame on this origin with the current binding', () => {
    const props = baseProps({
      openedEnvironmentIds: [ENV_A],
      viewsByEnvironment: { [ENV_A]: [VIEW_A, VIEW_B] },
      views: {
        [VIEW_A]: view({ environmentId: ENV_A, viewId: VIEW_A }),
        [VIEW_B]: view({ environmentId: ENV_A, viewId: VIEW_B }),
      },
    })
    render(<IdeOverlayPanel {...props} />)

    const rejected = '/workspace/projects/rejected'
    // Studio 页面自身而不是 iframe。
    postNative(nativeAction('open-folder', VIEW_A, rejected), { source: window })
    // 同源之外的来源。
    postNative(nativeAction('open-folder', VIEW_A, rejected), { origin: 'https://evil.example' })
    // 隐藏标签的 iframe 不是当前工作台。
    postNative(nativeAction('open-folder', VIEW_B, rejected), { frame: frameFor(VIEW_B) })
    // 过期 bindingEpoch。
    postNative({ ...nativeAction('open-folder', VIEW_A, rejected), bindingEpoch: '6' })
    // 额外的命令字段不符合协议形状。
    postNative({ ...nativeAction('open-folder', VIEW_A, rejected), command: 'workbench.action.terminal.new' })
    // 控制字符路径不是可接受的目录。
    postNative(nativeAction('open-folder', VIEW_A, '/workspace/projects/\u0001crafted'))

    expect(props.onChangeDirectory).not.toHaveBeenCalled()
    expect(props.onOpenNewView).not.toHaveBeenCalled()
    expect(props.onCloseEnvironment).not.toHaveBeenCalled()

    // 正向对照：当前可见工作台的有效动作照常生效。
    postNative(nativeAction('open-folder', VIEW_A, '/workspace/projects/accepted'))
    expect(props.onChangeDirectory).toHaveBeenCalledWith(VIEW_A, '/workspace/projects/accepted')
  })

  it('distinguishes unknown and unavailable control in explicit force-close confirmation', async () => {
    const user = userEvent.setup()
    const onCloseEnvironment = vi.fn().mockResolvedValueOnce({
      ok: false,
      requiresForceConfirmation: true,
      message: 'close-control-unavailable',
    })
    render(<IdeOverlayPanel {...baseProps({ onCloseEnvironment })} />)

    await user.click(tabCloseButton(VIEW_A))
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
    await user.click(tabCloseButton(VIEW_A))
    expect(closeIdeView).toHaveBeenCalledWith(ENV_A, VIEW_A, expect.objectContaining({ discard: false }))
    await user.click(tabSelectButton(VIEW_B))
    await act(async () => { rejectClose(new ApiError('test close error', 503, 'unavailable', undefined, code)) })
    expect(screen.queryByTestId('ide-close-error')).not.toBeInTheDocument()
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()

    await user.click(tabSelectButton(VIEW_A))
    expect(screen.getByTestId('ide-close-error')).toHaveTextContent(message)
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(tabCloseButton(VIEW_A)).not.toBeDisabled()
    expect(screen.getAllByTestId('ide-iframe')).toHaveLength(2)
    expect(closeIdeView).toHaveBeenCalledTimes(1)
  })

  it('invalidates force confirmation when the Daemon instance changes even if the epoch is reused', async () => {
    const user = userEvent.setup()
    const onCloseEnvironment = vi.fn().mockResolvedValue({ ok: false, requiresForceConfirmation: true })
    const props = baseProps({ onCloseEnvironment })
    const { rerender } = render(<IdeOverlayPanel {...props} />)
    await user.click(tabCloseButton(VIEW_A))
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

    await user.click(tabCloseButton(VIEW_A))

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

  it('does not mount any panel surface while collapsed but keeps iframe DOM addressable', () => {
    render(<IdeOverlayPanel {...baseProps({ isOpen: false })} />)

    expect(screen.getByTestId('ide-overlay-panel')).toHaveAttribute('hidden')
    expect(screen.getAllByTestId('ide-iframe')).toHaveLength(2)
  })
})
