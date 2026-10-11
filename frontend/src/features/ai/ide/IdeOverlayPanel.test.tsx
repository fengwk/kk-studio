import { createContext, StrictMode, useContext, useState, type ReactNode } from 'react'
import { fireEvent, render as rtlRender, screen, waitFor, within } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi, beforeEach } from 'vitest'
import {
  IdeOverlayPanel,
  type IdeCommandAction,
  type ResolvedEnvironmentInfo,
} from '@/features/ai/ide/IdeOverlayPanel'
import { validateIdeEntryUrl } from '@/features/ai/ide/ide-url'
import { environmentService } from '@/shared/api/environment-service'
import type { EnvironmentCardDTO } from '@/shared/api/contracts/ai-environment'
import { IdeLifecycleProvider } from '@/features/ai/ide/IdeLifecycleProvider'

function TestRouter({ children }: { children: ReactNode }) {
  const [ctx] = useState(() => {
    const ContentContext = createContext<ReactNode>(children)
    function RouteContainer() {
      const c = useContext(ContentContext)
      return <IdeLifecycleProvider>{c}</IdeLifecycleProvider>
    }
    const router = createMemoryRouter([
      {
        path: '*',
        Component: RouteContainer,
      },
    ])
    return { ContentContext, router }
  })

  return (
    <ctx.ContentContext.Provider value={children}>
      <RouterProvider router={ctx.router} />
    </ctx.ContentContext.Provider>
  )
}

function render(ui: ReactNode) {
  return rtlRender(ui, { wrapper: TestRouter })
}

vi.mock('@/shared/api/environment-service', () => ({
  environmentService: {
    createIdeView: vi.fn().mockResolvedValue({}),
    changeIdeViewDirectory: vi.fn().mockResolvedValue({}),
    openIdeViewTerminal: vi.fn().mockResolvedValue(undefined),
    closeIdeView: vi.fn().mockResolvedValue(undefined),
  },
}))

describe('validateIdeEntryUrl', () => {
  it('accepts valid distinct-origin HTTP/HTTPS URLs without userinfo', () => {
    expect(validateIdeEntryUrl('https://node-1.ide.example.com/')).toBe('https://node-1.ide.example.com/')
    expect(validateIdeEntryUrl('http://192.168.1.100:8080/path')).toBe('http://192.168.1.100:8080/path')
  })

  it('rejects empty or non-string entryUrl', () => {
    expect(() => validateIdeEntryUrl(null)).toThrow('Initial IDE view did not return entry URL')
    expect(() => validateIdeEntryUrl('')).toThrow('Initial IDE view did not return entry URL')
  })

  it('rejects relative or non-HTTP protocols', () => {
    expect(() => validateIdeEntryUrl('/relative/path')).toThrow('must be an absolute URL')
    expect(() => validateIdeEntryUrl('javascript:alert(1)')).toThrow('protocol must be http or https')
    expect(() => validateIdeEntryUrl('data:text/html,test')).toThrow('protocol must be http or https')
  })

  it('rejects URLs containing userinfo', () => {
    expect(() => validateIdeEntryUrl('https://user:pass@ide.example.com/')).toThrow('userinfo must be empty')
  })

  it('rejects URLs sharing origin with Studio app window', () => {
    const sameOrigin = `${window.location.origin}/ide`
    expect(() => validateIdeEntryUrl(sameOrigin)).toThrow('IDE origin must not equal Studio origin')
  })
})

describe('IdeOverlayPanel', () => {
  const readyEnv: ResolvedEnvironmentInfo = {
    id: 'a1000000-0000-4000-8000-000000000001',
    name: 'local-node',
    ready: true,
    homeDirectory: '/home/user',
  }

  const secondReadyEnv: ResolvedEnvironmentInfo = {
    id: 'a1000000-0000-4000-8000-000000000003',
    name: 'remote-ready',
    ready: true,
    homeDirectory: '/srv/app',
  }

  const offlineEnv: ResolvedEnvironmentInfo = {
    id: 'a1000000-0000-4000-8000-000000000002',
    name: 'remote-offline',
    ready: false,
  }

  const availableEnvironments: EnvironmentCardDTO[] = [
    {
      id: 'a1000000-0000-4000-8000-000000000001',
      name: 'local-node',
      ready: true,
      status: 'READY',
      version: '1',
      createTime: '2026-10-01T00:00:00Z',
      updateTime: '2026-10-01T00:00:00Z',
      capabilities: [],
      statusExpiresAt: null,
      lastSeen: null,
      homeDirectory: '/home/user',
    },
    {
      id: 'a1000000-0000-4000-8000-000000000002',
      name: 'remote-offline',
      ready: false,
      status: 'OFFLINE',
      version: '1',
      createTime: '2026-10-01T00:00:00Z',
      updateTime: '2026-10-01T00:00:00Z',
      capabilities: [],
      statusExpiresAt: null,
      lastSeen: null,
    },
    {
      id: 'a1000000-0000-4000-8000-000000000003',
      name: 'remote-ready',
      ready: true,
      status: 'READY',
      version: '1',
      createTime: '2026-10-01T00:00:00Z',
      updateTime: '2026-10-01T00:00:00Z',
      capabilities: [],
      statusExpiresAt: null,
      lastSeen: null,
      homeDirectory: '/srv/app',
    },
  ]

  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('uses the directory as its compact open action and labels icon-only controls', async () => {
    const user = userEvent.setup()
    const onCollapse = vi.fn()
    vi.mocked(environmentService.createIdeView).mockResolvedValueOnce({
      viewId: 'v-toolbar',
      environmentId: readyEnv.id,
      directory: '/home/user/project',
      entryUrl: 'https://ide.example.com/?workspace=toolbar',
    })
    render(
      <IdeOverlayPanel
        isOpen
        viewId="v-toolbar"
        environment={readyEnv}
        availableEnvironments={availableEnvironments}
        action={{ id: 1, mode: 'files' }}
        onCollapse={onCollapse}
        onClose={vi.fn()}
      />,
    )
    await screen.findByTestId('ide-iframe')
    const toolbar = within(screen.getByTestId('ide-toolbar'))
    expect(toolbar.getAllByRole('button')).toHaveLength(4)
    const directory = toolbar.getByRole('button', { name: '打开目录' })
    expect(directory).toHaveTextContent('/home/user/project')
    expect(directory).toHaveAttribute('title', 'local-node · /home/user/project')
    for (const name of ['打开终端', '收起', '关闭']) {
      const button = toolbar.getByRole('button', { name })
      expect(button.textContent).toBe('')
      expect(button).toHaveAttribute('title', name)
    }
    await user.click(directory)
    expect(screen.getByTestId('ide-directory-input')).toHaveValue('/home/user/project')
    await user.click(screen.getByRole('button', { name: '取消' }))
    await user.click(toolbar.getByRole('button', { name: '打开终端' }))
    expect(environmentService.openIdeViewTerminal).toHaveBeenCalledWith(readyEnv.id, 'v-toolbar')
    await user.click(toolbar.getByRole('button', { name: '收起' }))
    expect(onCollapse).toHaveBeenCalledOnce()
    expect(environmentService.closeIdeView).not.toHaveBeenCalled()
  })

  it('keeps the open view bound to its environment when prop changes, and only switches after explicit close and reopen', async () => {
    // 测试意图：已打开的 view 必须绑定初始环境，Thread/environment prop 变化或再次收到命令不自动销毁或跨环境重开；
    // 真正切换环境必须先显式关闭当前 view 再重新打开。
    const user = userEvent.setup()
    vi.mocked(environmentService.createIdeView)
      .mockResolvedValueOnce({
        viewId: 'v-env-label',
        environmentId: readyEnv.id,
        directory: '/home/user/project',
        entryUrl: 'https://ide.example.com/?workspace=env-label',
      })
      .mockResolvedValueOnce({
        viewId: 'v-env-label',
        environmentId: secondReadyEnv.id,
        directory: '/srv/app',
        entryUrl: 'https://ide.example.com/?workspace=env-second',
      })
    vi.mocked(environmentService.closeIdeView).mockResolvedValue(undefined)

    const onClose = vi.fn()
    const props = {
      isOpen: true,
      viewId: 'v-env-label',
      availableEnvironments,
      onCollapse: vi.fn(),
      onClose,
    }
    const { rerender } = render(
      <IdeOverlayPanel {...props} environment={readyEnv} action={{ id: 1, mode: 'files' }} />,
    )
    const iframe = await screen.findByTestId('ide-iframe')
    fireEvent.load(iframe)

    // Prop changes to offlineEnv or secondReadyEnv, and a new /shell action arrives while view is open
    rerender(<IdeOverlayPanel {...props} environment={offlineEnv} action={{ id: 1, mode: 'files' }} />)
    rerender(<IdeOverlayPanel {...props} environment={secondReadyEnv} action={{ id: 2, mode: 'shell' }} />)

    expect(screen.getByTestId('ide-open-directory-btn'))
      .toHaveAttribute('title', 'local-node · /home/user/project')
    expect(screen.getByTestId('ide-iframe')).toBe(iframe)
    expect(screen.queryByTestId('ide-select-environment-state')).not.toBeInTheDocument()
    expect(screen.queryByTestId('ide-confirm-switch-btn')).not.toBeInTheDocument()
    expect(environmentService.createIdeView).toHaveBeenCalledOnce()
    expect(environmentService.closeIdeView).not.toHaveBeenCalled()
    await waitFor(() => {
      expect(environmentService.openIdeViewTerminal).toHaveBeenCalledWith(readyEnv.id, 'v-env-label')
    })

    // Explicitly close the view, then reopen under secondReadyEnv
    await user.click(screen.getByTestId('ide-close-btn'))
    await user.click(screen.getByTestId('ide-confirm-close-btn'))
    await waitFor(() => {
      expect(environmentService.closeIdeView).toHaveBeenCalledWith(readyEnv.id, 'v-env-label')
    })
    expect(onClose).toHaveBeenCalledOnce()

    rerender(<IdeOverlayPanel {...props} environment={secondReadyEnv} action={{ id: 3, mode: 'files' }} />)
    const reopenedIframe = await screen.findByTestId('ide-iframe')
    expect(reopenedIframe).toHaveAttribute('src', 'https://ide.example.com/?workspace=env-second')
    expect(screen.getByTestId('ide-open-directory-btn'))
      .toHaveAttribute('title', 'remote-ready · /srv/app')
    expect(environmentService.createIdeView).toHaveBeenNthCalledWith(2, secondReadyEnv.id, {
      viewId: 'v-env-label',
    })
  })

  it('allows manual environment selection only when status is select-environment and no view is open', async () => {
    // 测试意图：当未绑定环境且 view == null 时展示环境选择器，确认后按所选环境打开并在建视图后隐藏选择器。
    const user = userEvent.setup()
    vi.mocked(environmentService.createIdeView).mockResolvedValueOnce({
      viewId: 'v-manual-env',
      environmentId: readyEnv.id,
      directory: '/home/user',
      entryUrl: 'https://ide.example.com/?workspace=manual-env',
    })

    render(
      <IdeOverlayPanel
        isOpen={true}
        viewId="v-manual-env"
        environment={null}
        availableEnvironments={availableEnvironments}
        action={{ id: 1, mode: 'files' }}
        onCollapse={vi.fn()}
        onClose={vi.fn()}
      />,
    )

    const selectState = await screen.findByTestId('ide-select-environment-state')
    expect(selectState).toBeInTheDocument()
    expect(environmentService.createIdeView).not.toHaveBeenCalled()

    await user.click(within(selectState).getByRole('button', { name: '确认' }))

    const iframe = await screen.findByTestId('ide-iframe')
    expect(iframe).toHaveAttribute('src', 'https://ide.example.com/?workspace=manual-env')
    expect(environmentService.createIdeView).toHaveBeenCalledWith(readyEnv.id, { viewId: 'v-manual-env' })
    expect(screen.queryByTestId('ide-select-environment-state')).not.toBeInTheDocument()
  })

  it('handles React StrictMode double effect without duplicate requests and becomes ready', async () => {
    let resolveCreate: (value: unknown) => void = () => {}
    const pendingPromise = new Promise((resolve) => {
      resolveCreate = resolve
    })
    vi.mocked(environmentService.createIdeView).mockReturnValueOnce(pendingPromise as never)

    render(
      <StrictMode>
        <IdeOverlayPanel
          isOpen={true}
          viewId="v-strict"
          environment={readyEnv}
          availableEnvironments={availableEnvironments}
          action={{ id: 1, mode: 'files' }}
          onCollapse={vi.fn()}
          onClose={vi.fn()}
        />
      </StrictMode>,
    )

    // In StrictMode, exactly 1 create request is sent despite double-effect mount
    expect(environmentService.createIdeView).toHaveBeenCalledTimes(1)
    expect(screen.getByTestId('ide-opening-state')).toBeInTheDocument()

    // Now resolve the in-flight POST
    resolveCreate({
      viewId: 'v-strict',
      environmentId: readyEnv.id,
      directory: '/home/user',
      entryUrl: 'https://ide.example.com/?workspace=strict',
    })

    // Transitions cleanly to ready
    const iframe = await screen.findByTestId('ide-iframe')
    expect(iframe).toHaveAttribute('src', 'https://ide.example.com/?workspace=strict')
  })

  it('cleans up late-resolving view on actual unmount without leaks', async () => {
    let resolveCreate: (value: unknown) => void = () => {}
    const pendingPromise = new Promise((resolve) => {
      resolveCreate = resolve
    })
    vi.mocked(environmentService.createIdeView).mockReturnValueOnce(pendingPromise as never)

    const { unmount } = render(
      <IdeOverlayPanel
        isOpen={true}
        viewId="v-unmount"
        environment={readyEnv}
        availableEnvironments={availableEnvironments}
        action={{ id: 1, mode: 'files' }}
        onCollapse={vi.fn()}
        onClose={vi.fn()}
      />,
    )

    expect(environmentService.createIdeView).toHaveBeenCalledTimes(1)

    // Unmount before POST resolves
    unmount()

    // Resolve POST after unmount
    resolveCreate({
      viewId: 'v-unmount',
      environmentId: readyEnv.id,
      directory: '/home/user',
      entryUrl: 'https://ide.example.com/?workspace=unmount',
    })

    await waitFor(() => {
      // Must call closeIdeView to clean up the leaked server view
      expect(environmentService.closeIdeView).toHaveBeenCalledWith(readyEnv.id, 'v-unmount')
    })
  })

  it('logs fixed safe console.error without leaking sensitive details when unmount cleanup closeIdeView fails', async () => {
    // 测试意图：unmount cleanup 失败时必须输出固定安全日志，严禁空 catch 吞错或泄露异常原文/URL/token。
    const consoleErrorSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    try {
      vi.mocked(environmentService.createIdeView).mockResolvedValueOnce({
        viewId: 'v-unmount-err',
        environmentId: readyEnv.id,
        directory: '/home/user',
        entryUrl: 'https://ide.example.com/?workspace=unmount-err',
      })
      vi.mocked(environmentService.closeIdeView).mockRejectedValueOnce(
        new Error('DELETE https://node.internal:8443/ide?token=secret-token-999 failed in /home/user/secret'),
      )

      const { unmount } = render(
        <IdeOverlayPanel
          isOpen={true}
          viewId="v-unmount-err"
          environment={readyEnv}
          availableEnvironments={availableEnvironments}
          action={{ id: 1, mode: 'files' }}
          onCollapse={vi.fn()}
          onClose={vi.fn()}
        />,
      )

      await screen.findByTestId('ide-iframe')
      unmount()

      await waitFor(() => {
        expect(environmentService.closeIdeView).toHaveBeenCalledWith(readyEnv.id, 'v-unmount-err')
        expect(consoleErrorSpy).toHaveBeenCalledWith('Failed to close IDE view during unmount cleanup')
      })
      const serializedLogs = JSON.stringify(consoleErrorSpy.mock.calls)
      expect(serializedLogs).not.toContain('secret-token-999')
      expect(serializedLogs).not.toContain('node.internal')
      expect(serializedLogs).not.toContain('/home/user/secret')
    } finally {
      consoleErrorSpy.mockRestore()
    }
  })

  it('cleans up newly created server view if URL validation fails', async () => {
    vi.mocked(environmentService.createIdeView).mockResolvedValueOnce({
      viewId: 'v-invalid-url',
      environmentId: readyEnv.id,
      directory: '/home/user',
      entryUrl: 'javascript:alert(1)',
    })

    render(
      <IdeOverlayPanel
        isOpen={true}
        viewId="v-invalid-url"
        environment={readyEnv}
        availableEnvironments={availableEnvironments}
        action={{ id: 1, mode: 'files' }}
        onCollapse={vi.fn()}
        onClose={vi.fn()}
      />,
    )

    await waitFor(() => {
      // Must call closeIdeView because entryUrl validation failed
      expect(environmentService.closeIdeView).toHaveBeenCalledWith(readyEnv.id, 'v-invalid-url')
    })
    expect(await screen.findByTestId('ide-error-state')).toBeInTheDocument()
  })

  it('manages shell command actions: does not pre-call terminal before iframe load, and triggers on action', async () => {
    vi.mocked(environmentService.createIdeView).mockResolvedValueOnce({
      viewId: 'v-shell',
      environmentId: readyEnv.id,
      directory: '/home/user',
      entryUrl: 'https://ide.example.com/?workspace=shell',
    })

    const shellAction: IdeCommandAction = { id: 1, mode: 'shell' }

    const { rerender } = render(
      <IdeOverlayPanel
        isOpen={true}
        viewId="v-shell"
        environment={readyEnv}
        availableEnvironments={availableEnvironments}
        action={shellAction}
        onCollapse={vi.fn()}
        onClose={vi.fn()}
      />,
    )

    const iframe = await screen.findByTestId('ide-iframe')
    // Terminal must NOT be called before iframe fires onLoad
    expect(environmentService.openIdeViewTerminal).not.toHaveBeenCalled()

    // Iframe finishes loading
    fireEvent.load(iframe)

    // Terminal is triggered once after iframe load
    await waitFor(() => {
      expect(environmentService.openIdeViewTerminal).toHaveBeenCalledTimes(1)
    })

    // Repeated /files action does not reopen or call terminal
    const filesAction: IdeCommandAction = { id: 2, mode: 'files' }
    rerender(
      <IdeOverlayPanel
        isOpen={true}
        viewId="v-shell"
        environment={readyEnv}
        availableEnvironments={availableEnvironments}
        action={filesAction}
        onCollapse={vi.fn()}
        onClose={vi.fn()}
      />,
    )
    expect(environmentService.openIdeViewTerminal).toHaveBeenCalledTimes(1)
    expect(environmentService.createIdeView).toHaveBeenCalledTimes(1)

    // Repeated /shell action calls terminal once per user action
    const secondShellAction: IdeCommandAction = { id: 3, mode: 'shell' }
    rerender(
      <IdeOverlayPanel
        isOpen={true}
        viewId="v-shell"
        environment={readyEnv}
        availableEnvironments={availableEnvironments}
        action={secondShellAction}
        onCollapse={vi.fn()}
        onClose={vi.fn()}
      />,
    )
    await waitFor(() => {
      expect(environmentService.openIdeViewTerminal).toHaveBeenCalledTimes(2)
    })
  })

  it('cancels pane close when user clicks cancel in confirmation dialog', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.createIdeView).mockResolvedValueOnce({
      viewId: 'v-cancel-close',
      environmentId: readyEnv.id,
      directory: '/home/user',
      entryUrl: 'https://ide.example.com/?workspace=cancel',
    })

    const onClose = vi.fn()
    render(
      <IdeOverlayPanel
        isOpen={true}
        viewId="v-cancel-close"
        environment={readyEnv}
        availableEnvironments={availableEnvironments}
        action={{ id: 1, mode: 'files' }}
        onCollapse={vi.fn()}
        onClose={onClose}
      />,
    )

    await screen.findByTestId('ide-iframe')
    await user.click(screen.getByTestId('ide-close-btn'))

    // Dialog appears with confirmation prompt
    expect(screen.getByText('可能存在未保存的修改，确认关闭工作区 IDE？')).toBeInTheDocument()

    // Click cancel
    await user.click(screen.getByRole('button', { name: '取消' }))

    // Dialog closes, view stays open, closeIdeView was NOT called
    expect(screen.queryByText('可能存在未保存的修改，确认关闭工作区 IDE？')).not.toBeInTheDocument()
    expect(environmentService.closeIdeView).not.toHaveBeenCalled()
    expect(onClose).not.toHaveBeenCalled()
    expect(screen.getByTestId('ide-iframe')).toBeInTheDocument()
  })

  it('preserves view and displays error when confirmed close fails on backend', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.createIdeView).mockResolvedValueOnce({
      viewId: 'v-close-fail',
      environmentId: readyEnv.id,
      directory: '/home/user',
      entryUrl: 'https://ide.example.com/?workspace=fail',
    })
    vi.mocked(environmentService.closeIdeView).mockRejectedValueOnce(new Error('Cannot close view with unsaved changes'))

    const onClose = vi.fn()
    render(
      <IdeOverlayPanel
        isOpen={true}
        viewId="v-close-fail"
        environment={readyEnv}
        availableEnvironments={availableEnvironments}
        action={{ id: 1, mode: 'files' }}
        onCollapse={vi.fn()}
        onClose={onClose}
      />,
    )

    await screen.findByTestId('ide-iframe')
    await user.click(screen.getByTestId('ide-close-btn'))

    // Confirm close in dialog
    await user.click(screen.getByTestId('ide-confirm-close-btn'))

    // Close failure must NOT call onClose or clear view
    expect(onClose).not.toHaveBeenCalled()
    expect(screen.getByTestId('ide-iframe')).toBeInTheDocument()
    expect(screen.getByText('Cannot close view with unsaved changes')).toBeInTheDocument()
  })

  it('prevents late directory change from resurrecting closed view', async () => {
    // 测试意图：关闭视图与目录 PUT 竞争时，晚到达的 PUT 响应（即使携带新 entryUrl）不得复活已关闭的视图或弹窗。
    const user = userEvent.setup()
    let resolveChangeDir: (value: unknown) => void = () => {}
    const changeDirPromise = new Promise((resolve) => {
      resolveChangeDir = resolve
    })
    vi.mocked(environmentService.createIdeView).mockResolvedValueOnce({
      viewId: 'v-race',
      environmentId: readyEnv.id,
      directory: '/home/user',
      entryUrl: 'https://ide.example.com/?workspace=race',
    })
    vi.mocked(environmentService.changeIdeViewDirectory).mockReturnValueOnce(changeDirPromise as never)
    vi.mocked(environmentService.closeIdeView).mockResolvedValue(undefined)

    const onClose = vi.fn()
    const { rerender } = render(
      <IdeOverlayPanel
        isOpen={true}
        viewId="v-race"
        environment={readyEnv}
        availableEnvironments={availableEnvironments}
        action={{ id: 1, mode: 'files' }}
        onCollapse={vi.fn()}
        onClose={onClose}
      />,
    )

    await screen.findByTestId('ide-iframe')
    await user.click(screen.getByTestId('ide-open-directory-btn'))

    // Submit directory change (remains in-flight)
    const input = screen.getByTestId('ide-directory-input')
    await user.clear(input)
    await user.type(input, '/home/user/new-dir')
    await user.click(screen.getByTestId('ide-directory-confirm'))

    // While directory change is in-flight, user closes the view
    await user.click(screen.getByTestId('ide-close-btn'))
    await user.click(screen.getByTestId('ide-confirm-close-btn'))

    await waitFor(() => {
      expect(onClose).toHaveBeenCalled()
    })
    rerender(
      <IdeOverlayPanel
        isOpen={false}
        viewId="v-race"
        environment={readyEnv}
        availableEnvironments={availableEnvironments}
        action={{ id: 1, mode: 'files' }}
        onCollapse={vi.fn()}
        onClose={onClose}
      />,
    )

    // Now directory change resolves late (even with a non-null entryUrl)
    resolveChangeDir({
      viewId: 'v-race',
      environmentId: readyEnv.id,
      directory: '/home/user/new-dir',
      entryUrl: 'https://ide.example.com/?bootstrap=late-resurrect',
    })

    // Closed view must NOT be resurrected!
    expect(screen.queryByTestId('ide-iframe')).not.toBeInTheDocument()
    expect(screen.queryByTestId('ide-overlay-panel')).not.toBeInTheDocument()
  })

  it('displays terminal creation error in UI when terminal request fails', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.createIdeView).mockResolvedValueOnce({
      viewId: 'v-term-err',
      environmentId: readyEnv.id,
      directory: '/home/user',
      entryUrl: 'https://ide.example.com/?workspace=term',
    })
    vi.mocked(environmentService.openIdeViewTerminal).mockRejectedValueOnce(new Error('PTY spawn failed'))

    render(
      <IdeOverlayPanel
        isOpen={true}
        viewId="v-term-err"
        environment={readyEnv}
        availableEnvironments={availableEnvironments}
        action={{ id: 1, mode: 'files' }}
        onCollapse={vi.fn()}
        onClose={vi.fn()}
      />,
    )

    await screen.findByTestId('ide-iframe')
    await user.click(screen.getByTestId('ide-terminal-btn'))

    const err = await screen.findByTestId('ide-terminal-error')
    expect(err).toHaveTextContent('PTY spawn failed')
  })

  it('changes directory via PUT, updates only directory metadata, and retains original entryUrl, iframe DOM, and environment binding even if PUT returns a new bootstrap URL', async () => {
    // 测试意图：CHANGE_DIRECTORY 只更新同一 view 的目录元数据，保留已验证的原始 entryUrl、原 iframe DOM 节点及环境绑定，绝不使用 PUT 返回的新 URL 重载 iframe。
    const user = userEvent.setup()
    vi.mocked(environmentService.createIdeView).mockResolvedValueOnce({
      viewId: 'v-dir',
      environmentId: readyEnv.id,
      directory: '/home/user',
      entryUrl: 'https://ide.example.com/?workspace=dir',
    })

    const { rerender } = render(
      <IdeOverlayPanel
        isOpen={true}
        viewId="v-dir"
        environment={readyEnv}
        availableEnvironments={availableEnvironments}
        action={{ id: 1, mode: 'files' }}
        onCollapse={vi.fn()}
        onClose={vi.fn()}
      />,
    )

    const iframe = await screen.findByTestId('ide-iframe')
    // Simulate thread environment prop changing while view is open
    rerender(
      <IdeOverlayPanel
        isOpen={true}
        viewId="v-dir"
        environment={secondReadyEnv}
        availableEnvironments={availableEnvironments}
        action={{ id: 1, mode: 'files' }}
        onCollapse={vi.fn()}
        onClose={vi.fn()}
      />,
    )

    await user.click(screen.getByTestId('ide-open-directory-btn'))

    const input = screen.getByTestId('ide-directory-input')
    await user.clear(input)
    await user.type(input, '/home/user/project')

    vi.mocked(environmentService.changeIdeViewDirectory).mockRejectedValueOnce(
      new Error('Path does not exist'),
    )
    await user.click(screen.getByTestId('ide-directory-confirm'))

    const error = await screen.findByTestId('ide-directory-error')
    expect(error).toHaveTextContent('Path does not exist')

    // Even if PUT returns a new bootstrap entryUrl, iframe src and DOM node must NOT change
    vi.mocked(environmentService.changeIdeViewDirectory).mockResolvedValueOnce({
      viewId: 'v-dir',
      environmentId: readyEnv.id,
      directory: '/home/user/project',
      entryUrl: 'https://ide.example.com/?bootstrap=new-token-do-not-reload',
    })
    await user.click(screen.getByTestId('ide-directory-confirm'))

    await waitFor(() => {
      expect(screen.queryByTestId('ide-directory-input')).not.toBeInTheDocument()
    })
    expect(environmentService.changeIdeViewDirectory).toHaveBeenLastCalledWith(readyEnv.id, 'v-dir', {
      directory: '/home/user/project',
    })
    expect(screen.getByTestId('ide-directory-badge')).toHaveTextContent('/home/user/project')
    expect(screen.getByTestId('ide-open-directory-btn'))
      .toHaveAttribute('title', 'local-node · /home/user/project')
    expect(screen.getByTestId('ide-iframe')).toBe(iframe)
    expect(screen.getByTestId('ide-iframe')).toHaveAttribute('src', 'https://ide.example.com/?workspace=dir')
  })

  it('renders offline state when environment is not ready', async () => {
    render(
      <IdeOverlayPanel
        isOpen={true}
        viewId="v-off"
        environment={offlineEnv}
        availableEnvironments={availableEnvironments}
        action={{ id: 1, mode: 'files' }}
        onCollapse={vi.fn()}
        onClose={vi.fn()}
      />,
    )

    expect(environmentService.createIdeView).not.toHaveBeenCalled()
    const state = screen.getByTestId('ide-offline-state')
    expect(state).toHaveTextContent('环境“remote-offline”尚未就绪')
  })

  it('handles keyboard Escape by collapsing panel and restoring composer focus', async () => {
    const user = userEvent.setup()
    const onCollapse = vi.fn()
    const onRestoreFocus = vi.fn()

    render(
      <IdeOverlayPanel
        isOpen={true}
        viewId="v-esc"
        environment={offlineEnv}
        availableEnvironments={availableEnvironments}
        action={{ id: 1, mode: 'files' }}
        onCollapse={onCollapse}
        onClose={vi.fn()}
        onRestoreFocus={onRestoreFocus}
      />,
    )

    const panel = screen.getByTestId('ide-overlay-panel')
    panel.focus()
    await user.keyboard('{Escape}')

    expect(onCollapse).toHaveBeenCalled()
    expect(onRestoreFocus).toHaveBeenCalled()
  })

  it('renders with narrow layout when isNarrow is true', async () => {
    render(
      <IdeOverlayPanel
        isOpen={true}
        viewId="v-narrow"
        environment={offlineEnv}
        availableEnvironments={availableEnvironments}
        action={{ id: 1, mode: 'files' }}
        isNarrow={true}
        onCollapse={vi.fn()}
        onClose={vi.fn()}
      />,
    )

    const panel = screen.getByTestId('ide-overlay-panel')
    expect(panel).toHaveAttribute('data-layout', 'narrow')
  })
})
