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

  it('keeps the tooltip bound to the open view when the thread environment changes', async () => {
    vi.mocked(environmentService.createIdeView).mockResolvedValueOnce({
      viewId: 'v-env-label',
      environmentId: readyEnv.id,
      directory: '/home/user/project',
      entryUrl: 'https://ide.example.com/?workspace=env-label',
    })
    const props = {
      isOpen: true,
      viewId: 'v-env-label',
      availableEnvironments,
      action: { id: 1, mode: 'files' } as const,
      onCollapse: vi.fn(),
      onClose: vi.fn(),
    }
    const { rerender } = render(<IdeOverlayPanel {...props} environment={readyEnv} />)
    const iframe = await screen.findByTestId('ide-iframe')
    rerender(<IdeOverlayPanel {...props} environment={offlineEnv} />)
    expect(screen.getByTestId('ide-open-directory-btn'))
      .toHaveAttribute('title', 'local-node · /home/user/project')
    expect(screen.getByTestId('ide-iframe')).toBe(iframe)
    expect(environmentService.createIdeView).toHaveBeenCalledOnce()
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
    render(
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

    // Now directory change resolves late
    resolveChangeDir({
      viewId: 'v-race',
      environmentId: readyEnv.id,
      directory: '/home/user/new-dir',
      entryUrl: null,
    })

    // Closed view must NOT be resurrected!
    expect(screen.queryByTestId('ide-iframe')).not.toBeInTheDocument()
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

  it('changes directory via PUT, displays error on failure, and retains entryUrl', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.createIdeView).mockResolvedValueOnce({
      viewId: 'v-dir',
      environmentId: readyEnv.id,
      directory: '/home/user',
      entryUrl: 'https://ide.example.com/?workspace=dir',
    })

    render(
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

    await screen.findByTestId('ide-iframe')
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

    // Successful PUT returns entryUrl: null; iframe src is preserved
    vi.mocked(environmentService.changeIdeViewDirectory).mockResolvedValueOnce({
      viewId: 'v-dir',
      environmentId: readyEnv.id,
      directory: '/home/user/project',
      entryUrl: null,
    })
    await user.click(screen.getByTestId('ide-directory-confirm'))

    await waitFor(() => {
      expect(screen.queryByTestId('ide-directory-input')).not.toBeInTheDocument()
    })
    expect(screen.getByTestId('ide-directory-badge')).toHaveTextContent('/home/user/project')
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
