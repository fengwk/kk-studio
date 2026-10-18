import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, Link, RouterProvider, useNavigate } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { IdeLifecycleProvider } from '@/features/ai/ide/IdeLifecycleProvider'
import { useIdeLifecycle } from '@/features/ai/ide/ide-lifecycle-context'
import { IdeOverlayPanel } from '@/features/ai/ide/IdeOverlayPanel'
import { environmentService } from '@/shared/api/environment-service'
import type { EnvironmentCardDTO } from '@/shared/api/contracts/ai-environment'

vi.mock('@/shared/api/environment-service', () => ({
  environmentService: {
    createIdeView: vi.fn(),
    changeIdeViewDirectory: vi.fn(),
    openIdeViewTerminal: vi.fn(),
    closeIdeView: vi.fn(),
  },
}))

describe('IdeLifecycleProvider & DataRouter navigation blocker', () => {
  const readyEnv: EnvironmentCardDTO = {
    id: 'a0000000-0000-4000-8000-000000000001',
    name: 'test-env',
    ready: true,
    status: 'READY',
    version: '1',
    createTime: '2026-10-01T00:00:00Z',
    updateTime: '2026-10-01T00:00:00Z',
    capabilities: [],
    statusExpiresAt: null,
    lastSeen: null,
    homeDirectory: '/home/user',
  }

  beforeEach(() => {
    vi.clearAllMocks()
  })

  function renderTestHarness(options?: {
    initialAction?: { id: number; mode: 'files' | 'shell' }
    initialViewCreated?: boolean
  }) {
    const user = userEvent.setup()

    function Workspace() {
      const navigate = useNavigate()
      return (
        <div>
          <h1>Workspace Page</h1>
          <Link to="/other">Go to Other</Link>
          <button type="button" onClick={() => navigate('/other')}>
            Navigate Imperatively
          </button>
          <IdeOverlayPanel
            isOpen={true}
            viewId="v-lifecycle"
            paneId="pane-1"
            environment={readyEnv}
            availableEnvironments={[readyEnv]}
            action={options?.initialAction ?? { id: 1, mode: 'files' }}
            onCollapse={vi.fn()}
            onClose={vi.fn()}
          />
        </div>
      )
    }

    const router = createMemoryRouter(
      [
        {
          path: '/workspace',
          element: (
            <IdeLifecycleProvider>
              <Workspace />
            </IdeLifecycleProvider>
          ),
        },
        {
          path: '/other',
          element: <h1>Other Page</h1>,
        },
      ],
      { initialEntries: ['/workspace'] },
    )

    const view = render(<RouterProvider router={router} />)
    return { user, router, view }
  }

  it('intercepts Link navigation when IDE is active, cancels with NO DELETE call, and retains DOM', async () => {
    vi.mocked(environmentService.createIdeView).mockResolvedValueOnce({
      viewId: 'v-lifecycle',
      environmentId: readyEnv.id,
      directory: '/home/user',
      entryUrl: `/environment-ide/${readyEnv.id}/__ide/bootstrap?token=1`,
    })

    const { user, router } = renderTestHarness()

    // Wait for IDE iframe to mount
    const iframe = await screen.findByTestId('ide-iframe')
    expect(iframe).toBeInTheDocument()

    // Click Link to navigate away
    const link = screen.getByRole('link', { name: 'Go to Other' })
    await user.click(link)

    // Navigation is blocked, Dialog appears
    expect(await screen.findByRole('alertdialog')).toBeInTheDocument()
    expect(screen.getByText('离开将关闭文件面板，未保存内容需先保存。确认离开？')).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/workspace')

    // Click Cancel in leave dialog
    const cancelBtn = screen.getByRole('button', { name: '取消' })
    await user.click(cancelBtn)

    // Dialog is dismissed, location unchanged, NO DELETE called
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/workspace')
    expect(environmentService.closeIdeView).not.toHaveBeenCalled()
    // Iframe DOM node is retained in the document
    expect(screen.getByTestId('ide-iframe')).toBe(iframe)
  })

  it('on DIRTY close error: retains exact iframe DOM node, displays safe error banner, and refuses route departure', async () => {
    vi.mocked(environmentService.createIdeView).mockResolvedValueOnce({
      viewId: 'v-lifecycle',
      environmentId: readyEnv.id,
      directory: '/home/user',
      entryUrl: `/environment-ide/${readyEnv.id}/__ide/bootstrap?token=1`,
    })
    // Mock DIRTY error with internal path that should be sanitized
    vi.mocked(environmentService.closeIdeView).mockRejectedValueOnce(
      new Error('Dirty unsaved files in /home/user/workspace'),
    )

    const { user, router } = renderTestHarness()
    const iframe = await screen.findByTestId('ide-iframe')

    // Trigger navigation
    await user.click(screen.getByRole('link', { name: 'Go to Other' }))
    expect(await screen.findByRole('alertdialog')).toBeInTheDocument()

    // Confirm leave
    const confirmBtn = screen.getByTestId('ide-confirm-leave-btn')
    await user.click(confirmBtn)

    // Close view failed: safe error banner appears, path sanitized, route NOT departed
    await waitFor(() => {
      expect(screen.getByTestId('ide-route-leave-error')).toBeInTheDocument()
    })
    expect(screen.getByTestId('ide-route-leave-error').textContent).not.toContain('/home/user')
    expect(router.state.location.pathname).toBe('/workspace')

    // Iframe remains the exact same DOM node
    expect(screen.getByTestId('ide-iframe')).toBe(iframe)
  })

  it('awaits in-flight late POST during route leave, sends cleanup DELETE, and proceeds upon success', async () => {
    let resolveCreate: (value: unknown) => void = () => {}
    const createPromise = new Promise((resolve) => {
      resolveCreate = resolve
    })
    vi.mocked(environmentService.createIdeView).mockReturnValueOnce(createPromise as never)
    vi.mocked(environmentService.closeIdeView).mockResolvedValueOnce(undefined)

    const { user, router } = renderTestHarness()

    // IDE is in opening state
    expect(await screen.findByTestId('ide-opening-state')).toBeInTheDocument()

    // User attempts to navigate while creation is in-flight
    await user.click(screen.getByRole('button', { name: 'Navigate Imperatively' }))
    expect(await screen.findByRole('alertdialog')).toBeInTheDocument()

    // User clicks confirm leave
    const confirmBtn = screen.getByTestId('ide-confirm-leave-btn')
    await user.click(confirmBtn)

    // Now late POST resolves
    act(() => {
      resolveCreate({
        viewId: 'v-lifecycle',
        environmentId: readyEnv.id,
        directory: '/home/user',
        entryUrl: `/environment-ide/${readyEnv.id}/__ide/bootstrap?token=late`,
      })
    })

    // requestClose awaits the late resolution and calls closeIdeView
    await waitFor(() => {
      expect(environmentService.closeIdeView).toHaveBeenCalledWith(readyEnv.id, 'v-lifecycle')
    })

    // Navigation succeeds and lands on Other Page
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/other')
    })
    expect(screen.getByRole('heading', { name: 'Other Page' })).toBeInTheDocument()
  })

  it('closing lock prevents Escape/cancel from resetting blocker during in-flight closeIdeView', async () => {
    vi.mocked(environmentService.createIdeView).mockResolvedValueOnce({
      viewId: 'v-lifecycle',
      environmentId: readyEnv.id,
      directory: '/home/user',
      entryUrl: `/environment-ide/${readyEnv.id}/__ide/bootstrap?token=lock`,
    })
    let resolveClose: () => void = () => {}
    const closePromise = new Promise<void>((resolve) => {
      resolveClose = resolve
    })
    vi.mocked(environmentService.closeIdeView).mockReturnValueOnce(closePromise as never)

    const { user, router } = renderTestHarness()
    await screen.findByTestId('ide-iframe')

    // Navigate to trigger leave dialog
    await user.click(screen.getByRole('link', { name: 'Go to Other' }))
    expect(await screen.findByRole('alertdialog')).toBeInTheDocument()

    // Confirm leave
    const confirmBtn = screen.getByTestId('ide-confirm-leave-btn')
    await user.click(confirmBtn)

    // While close is in-flight, try pressing Escape or clicking cancel
    fireEvent.keyDown(document.body, { key: 'Escape' })
    const cancelBtn = screen.getByRole('button', { name: '取消' })
    expect(cancelBtn).toBeDisabled()

    // Now close completes
    act(() => {
      resolveClose()
    })

    // Navigation proceeds cleanly without stale cancel race
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/other')
    })
  })

  it('token isolation: old cleanup does not delete new registration for same viewId, new cleanup removes it', () => {
    let capturedApi!: ReturnType<typeof useIdeLifecycle>
    function Probe() {
      capturedApi = useIdeLifecycle()
      return <div>Probe</div>
    }

    const router = createMemoryRouter([
      {
        path: '/',
        element: (
          <IdeLifecycleProvider>
            <Probe />
          </IdeLifecycleProvider>
        ),
      },
    ])
    render(<RouterProvider router={router} />)

    // Register token A for viewId v1
    let cleanupA!: () => void
    act(() => {
      cleanupA = capturedApi.registerIdeView({
        viewId: 'v1',
        paneId: 'p1',
        active: true,
        requestClose: vi.fn().mockResolvedValue(true),
      })
    })
    expect(capturedApi.hasActiveIdeViews).toBe(true)
    expect(capturedApi.isPaneActive('p1')).toBe(true)

    // Re-register token B for the same viewId v1
    let cleanupB!: () => void
    act(() => {
      cleanupB = capturedApi.registerIdeView({
        viewId: 'v1',
        paneId: 'p1',
        active: true,
        requestClose: vi.fn().mockResolvedValue(true),
      })
    })

    // Calling old cleanupA must NOT delete registration B
    act(() => {
      cleanupA()
    })
    expect(capturedApi.hasActiveIdeViews).toBe(true)
    expect(capturedApi.isPaneActive('p1')).toBe(true)

    // Calling new cleanupB properly removes it
    act(() => {
      cleanupB()
    })
    expect(capturedApi.hasActiveIdeViews).toBe(false)
    expect(capturedApi.isPaneActive('p1')).toBe(false)
  })

  it('history popstate / back button intercepts, cancel preserves route with no DELETE, and confirm DIRTY halts departure', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.createIdeView).mockResolvedValueOnce({
      viewId: 'v-lifecycle',
      environmentId: readyEnv.id,
      directory: '/home/user',
      entryUrl: `/environment-ide/${readyEnv.id}/__ide/bootstrap?token=back`,
    })

    function Workspace() {
      const navigate = useNavigate()
      return (
        <div>
          <h1>Workspace</h1>
          <button type="button" onClick={() => navigate(-1)}>
            Go Back
          </button>
          <IdeOverlayPanel
            isOpen={true}
            viewId="v-lifecycle"
            paneId="pane-1"
            environment={readyEnv}
            availableEnvironments={[readyEnv]}
            action={{ id: 1, mode: 'files' }}
            onCollapse={vi.fn()}
            onClose={vi.fn()}
          />
        </div>
      )
    }

    const router = createMemoryRouter(
      [
        {
          path: '/home',
          element: <h1>Home</h1>,
        },
        {
          path: '/workspace',
          element: (
            <IdeLifecycleProvider>
              <Workspace />
            </IdeLifecycleProvider>
          ),
        },
      ],
      { initialEntries: ['/home', '/workspace'], initialIndex: 1 },
    )

    render(<RouterProvider router={router} />)
    const iframe = await screen.findByTestId('ide-iframe')

    // Trigger back navigation
    await user.click(screen.getByRole('button', { name: 'Go Back' }))

    // Dialog appears, route is blocked at /workspace
    expect(await screen.findByRole('alertdialog')).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/workspace')

    // Click cancel in leave dialog: NO DELETE call, stays on /workspace
    await user.click(screen.getByRole('button', { name: '取消' }))
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/workspace')
    expect(environmentService.closeIdeView).not.toHaveBeenCalled()
    expect(screen.getByTestId('ide-iframe')).toBe(iframe)

    // Trigger back navigation again
    await user.click(screen.getByRole('button', { name: 'Go Back' }))
    expect(await screen.findByRole('alertdialog')).toBeInTheDocument()

    // Mock dirty error on close
    vi.mocked(environmentService.closeIdeView).mockRejectedValueOnce(
      new Error('Dirty buffer in /home/user/file.txt'),
    )
    await user.click(screen.getByTestId('ide-confirm-leave-btn'))

    // Error banner shown, route does NOT leave, iframe remains mounted
    await waitFor(() => {
      expect(screen.getByTestId('ide-route-leave-error')).toBeInTheDocument()
    })
    expect(router.state.location.pathname).toBe('/workspace')
    expect(screen.getByTestId('ide-iframe')).toBe(iframe)
  })

  it('multi-pane: second pane fails with DIRTY error, halts departure and displays safe error', async () => {
    const user = userEvent.setup()
    let capturedApi!: ReturnType<typeof useIdeLifecycle>
    function MultiPaneProbe() {
      capturedApi = useIdeLifecycle()
      const navigate = useNavigate()
      return (
        <div>
          <button type="button" onClick={() => navigate('/other')}>
            Leave
          </button>
        </div>
      )
    }

    const router = createMemoryRouter(
      [
        {
          path: '/workspace',
          element: (
            <IdeLifecycleProvider>
              <MultiPaneProbe />
            </IdeLifecycleProvider>
          ),
        },
        {
          path: '/other',
          element: <h1>Other</h1>,
        },
      ],
      { initialEntries: ['/workspace'] },
    )
    render(<RouterProvider router={router} />)

    const closePane1 = vi.fn().mockResolvedValue(true)
    const closePane2 = vi.fn().mockResolvedValue(false) // pane 2 returns false (dirty/error)

    act(() => {
      capturedApi.registerIdeView({
        viewId: 'v-p1',
        paneId: 'p1',
        active: true,
        requestClose: closePane1,
      })
      capturedApi.registerIdeView({
        viewId: 'v-p2',
        paneId: 'p2',
        active: true,
        requestClose: closePane2,
      })
    })

    // Navigate to trigger leave dialog
    await user.click(screen.getByRole('button', { name: 'Leave' }))
    expect(await screen.findByRole('alertdialog')).toBeInTheDocument()

    // Confirm leave
    await user.click(screen.getByTestId('ide-confirm-leave-btn'))

    // Pane 1 close succeeded, but pane 2 close failed
    expect(closePane1).toHaveBeenCalled()
    expect(closePane2).toHaveBeenCalled()

    // Route departure is halted
    await waitFor(() => {
      expect(screen.getByTestId('ide-route-leave-error')).toBeInTheDocument()
    })
    expect(router.state.location.pathname).toBe('/workspace')
  })
})
