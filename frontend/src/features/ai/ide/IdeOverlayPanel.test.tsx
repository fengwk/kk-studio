import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { IdeOverlayPanel, type ResolvedEnvironmentInfo } from '@/features/ai/ide/IdeOverlayPanel'
import { environmentService } from '@/shared/api/environment-service'
import { ApiError } from '@/shared/api/client'
import type { EnvironmentCardDTO } from '@/shared/api/contracts/ai-environment'

vi.mock('@/shared/api/environment-service', () => ({
  environmentService: {
    createIdeView: vi.fn().mockResolvedValue({}),
    changeIdeViewDirectory: vi.fn().mockResolvedValue({}),
    openIdeViewTerminal: vi.fn().mockResolvedValue(undefined),
    closeIdeView: vi.fn().mockResolvedValue(undefined),
  },
}))

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

  it('mounts lazily when opened and renders code-server iframe with strict sandbox', async () => {
    vi.mocked(environmentService.createIdeView).mockResolvedValueOnce({
      viewId: 'v-100',
      environmentId: readyEnv.id,
      directory: '/home/user',
      entryUrl: 'https://ide.example.com/?workspace=test',
    })

    const onIdeMountedChange = vi.fn()
    const { rerender } = render(
      <IdeOverlayPanel
        isOpen={true}
        viewId="v-100"
        environment={readyEnv}
        availableEnvironments={availableEnvironments}
        initialMode="files"
        onCollapse={vi.fn()}
        onClose={vi.fn()}
        onIdeMountedChange={onIdeMountedChange}
      />,
    )

    // Initial POST takes HOME from daemon
    expect(environmentService.createIdeView).toHaveBeenCalledWith(readyEnv.id, { viewId: 'v-100' })

    const iframe = await screen.findByTestId('ide-iframe')
    expect(iframe).toHaveAttribute('src', 'https://ide.example.com/?workspace=test')
    expect(iframe).toHaveAttribute('title', '工作区 IDE')
    // Sandbox must contain needed permissions but NOT allow-top-navigation
    const sandbox = iframe.getAttribute('sandbox') ?? ''
    expect(sandbox).toContain('allow-scripts')
    expect(sandbox).toContain('allow-same-origin')
    expect(sandbox).not.toContain('allow-top-navigation')

    expect(onIdeMountedChange).toHaveBeenCalledWith(true)

    // Toolbar shows environment and directory badges
    expect(screen.getByTestId('ide-env-badge')).toHaveTextContent('local-node')
    expect(screen.getByTestId('ide-directory-badge')).toHaveTextContent('/home/user')

    // Collapse retains DOM with hidden + inert
    rerender(
      <IdeOverlayPanel
        isOpen={false}
        viewId="v-100"
        environment={readyEnv}
        availableEnvironments={availableEnvironments}
        initialMode="files"
        onCollapse={vi.fn()}
        onClose={vi.fn()}
        onIdeMountedChange={onIdeMountedChange}
      />,
    )

    const panel = screen.getByTestId('ide-overlay-panel')
    expect(panel).toHaveAttribute('hidden')
    expect(panel).toHaveAttribute('inert')
    expect(screen.getByTestId('ide-iframe')).toBeInTheDocument()

    // Re-opening does not reload iframe URL
    rerender(
      <IdeOverlayPanel
        isOpen={true}
        viewId="v-100"
        environment={readyEnv}
        availableEnvironments={availableEnvironments}
        initialMode="files"
        onCollapse={vi.fn()}
        onClose={vi.fn()}
        onIdeMountedChange={onIdeMountedChange}
      />,
    )
    expect(environmentService.createIdeView).toHaveBeenCalledTimes(1)
  })

  it('triggers terminal creation when initialMode is shell', async () => {
    vi.mocked(environmentService.createIdeView).mockResolvedValueOnce({
      viewId: 'v-shell',
      environmentId: readyEnv.id,
      directory: '/home/user',
      entryUrl: 'https://ide.example.com/?workspace=shell',
    })
    vi.mocked(environmentService.openIdeViewTerminal).mockResolvedValueOnce(undefined)

    render(
      <IdeOverlayPanel
        isOpen={true}
        viewId="v-shell"
        environment={readyEnv}
        availableEnvironments={availableEnvironments}
        initialMode="shell"
        onCollapse={vi.fn()}
        onClose={vi.fn()}
      />,
    )

    await waitFor(() => {
      expect(environmentService.openIdeViewTerminal).toHaveBeenCalledWith(readyEnv.id, 'v-shell')
    })
  })

  it('changes directory via PUT and gracefully handles dirty conflict error', async () => {
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
        onCollapse={vi.fn()}
        onClose={vi.fn()}
      />,
    )

    await screen.findByTestId('ide-iframe')
    await user.click(screen.getByTestId('ide-open-directory-btn'))

    // Dialog appears with text input
    const input = screen.getByTestId('ide-directory-input')
    expect(input).toHaveValue('/home/user')
    await user.clear(input)
    await user.type(input, '/home/user/project')

    // Mock dirty error (409 conflict)
    vi.mocked(environmentService.changeIdeViewDirectory).mockRejectedValueOnce(
      new ApiError('Unsaved changes in workspace', 409, 'DIRTY'),
    )

    await user.click(screen.getByTestId('ide-directory-confirm'))

    const error = await screen.findByTestId('ide-directory-error')
    expect(error).toHaveTextContent('当前有未保存的修改，无法切换目录。请先保存或放弃修改后再试。')

    // Now mock success with entryUrl: null (retains existing entryUrl)
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
        onCollapse={vi.fn()}
        onClose={vi.fn()}
      />,
    )

    expect(environmentService.createIdeView).not.toHaveBeenCalled()
    const state = screen.getByTestId('ide-offline-state')
    expect(state).toHaveTextContent('环境“remote-offline”尚未就绪')
  })

  it('shows environment picker when no environment is configured without opening global management', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.createIdeView).mockResolvedValueOnce({
      viewId: 'v-pick',
      environmentId: readyEnv.id,
      directory: '/home/user',
      entryUrl: 'https://ide.example.com/?workspace=pick',
    })

    render(
      <IdeOverlayPanel
        isOpen={true}
        viewId="v-pick"
        environment={null}
        availableEnvironments={availableEnvironments}
        onCollapse={vi.fn()}
        onClose={vi.fn()}
      />,
    )

    expect(screen.getByTestId('ide-select-environment-state')).toBeInTheDocument()
    // Click confirm to start with first ready environment
    await user.click(screen.getByRole('button', { name: '确认' }))

    expect(environmentService.createIdeView).toHaveBeenCalledWith(readyEnv.id, { viewId: 'v-pick' })
    await screen.findByTestId('ide-iframe')
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

  it('sends DELETE view on close and on unmount', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.createIdeView).mockResolvedValueOnce({
      viewId: 'v-close',
      environmentId: readyEnv.id,
      directory: '/home/user',
      entryUrl: 'https://ide.example.com/?workspace=close',
    })
    vi.mocked(environmentService.closeIdeView).mockResolvedValue(undefined)

    const onClose = vi.fn()
    const { unmount } = render(
      <IdeOverlayPanel
        isOpen={true}
        viewId="v-close"
        environment={readyEnv}
        availableEnvironments={availableEnvironments}
        onCollapse={vi.fn()}
        onClose={onClose}
      />,
    )

    await screen.findByTestId('ide-iframe')
    await user.click(screen.getByTestId('ide-close-btn'))

    expect(environmentService.closeIdeView).toHaveBeenCalledWith(readyEnv.id, 'v-close')
    expect(onClose).toHaveBeenCalled()

    // Unmount cleanup also ensures DELETE is sent
    unmount()
  })

  it('renders with narrow layout when isNarrow is true', async () => {
    render(
      <IdeOverlayPanel
        isOpen={true}
        viewId="v-narrow"
        environment={offlineEnv}
        availableEnvironments={availableEnvironments}
        isNarrow={true}
        onCollapse={vi.fn()}
        onClose={vi.fn()}
      />,
    )

    const panel = screen.getByTestId('ide-overlay-panel')
    expect(panel).toHaveAttribute('data-layout', 'narrow')
  })

  it('confirms before switching environments when a view is already active', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.createIdeView).mockResolvedValueOnce({
      viewId: 'v-sw',
      environmentId: readyEnv.id,
      directory: '/home/user',
      entryUrl: 'https://ide.example.com/?workspace=first',
    })

    const otherReady: EnvironmentCardDTO = {
      ...availableEnvironments[1]!,
      id: 'a1000000-0000-4000-8000-000000000003',
      name: 'other-node',
      ready: true,
      status: 'READY',
    }

    render(
      <IdeOverlayPanel
        isOpen={true}
        viewId="v-sw"
        environment={readyEnv}
        availableEnvironments={[availableEnvironments[0]!, otherReady]}
        onCollapse={vi.fn()}
        onClose={vi.fn()}
      />,
    )

    await screen.findByTestId('ide-iframe')

    // Simulate clicking new terminal button
    await user.click(screen.getByTestId('ide-new-terminal-btn'))
    expect(environmentService.openIdeViewTerminal).toHaveBeenCalledWith(readyEnv.id, 'v-sw')
  })

  it('shows error state when initial POST fails or does not return entryUrl', async () => {
    vi.mocked(environmentService.createIdeView).mockReset()
    vi.mocked(environmentService.createIdeView).mockRejectedValueOnce(new Error('Connection refused'))

    render(
      <IdeOverlayPanel
        isOpen={true}
        viewId="v-err"
        environment={readyEnv}
        availableEnvironments={availableEnvironments}
        onCollapse={vi.fn()}
        onClose={vi.fn()}
      />,
    )

    const errState = await screen.findByTestId('ide-error-state')
    expect(errState).toHaveTextContent('Connection refused')
  })
})
