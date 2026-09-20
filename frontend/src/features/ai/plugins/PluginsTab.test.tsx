import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { PluginsTab } from '@/features/ai/plugins/PluginsTab'
import { pluginsService } from '@/shared/api/plugins-service'
import type { PluginDTO } from '@/shared/api/contracts/ai-plugin'

vi.mock('@/shared/api/plugins-service', () => ({
  pluginsService: {
    listPlugins: vi.fn(),
    getPlugin: vi.fn(),
    prepareAuth: vi.fn(),
    completeAuth: vi.fn(),
    disconnectAuth: vi.fn(),
  },
}))

function samplePlugin(overrides: Partial<PluginDTO> = {}): PluginDTO {
  return {
    pluginId: 'minimax-mavis',
    name: 'MiniMax Mavis',
    version: '1.0.0',
    authKind: {
      type: 'DEEP_LINK',
      regionCandidates: ['CN', 'EN'],
    },
    status: 'NOT_CONNECTED',
    region: null,
    expiresAt: null,
    nextRefreshAt: null,
    lastRefreshedAt: null,
    lastRefreshError: null,
    ...overrides,
  }
}

describe('PluginsTab', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    vi.clearAllMocks()
    window.open = vi.fn()
    queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false, gcTime: 0 },
        mutations: { retry: false },
      },
    })
  })

  afterEach(() => {
    queryClient.clear()
  })

  function renderTab() {
    return render(
      <QueryClientProvider client={queryClient}>
        <PluginsTab />
      </QueryClientProvider>,
    )
  }

  it('renders installed plugin list with safe fields and no secrets', async () => {
    vi.mocked(pluginsService.listPlugins).mockResolvedValue([
      samplePlugin({
        status: 'CONNECTED',
        region: 'CN',
        expiresAt: '2026-10-01T12:00:00.000Z',
        nextRefreshAt: '2026-09-25T12:00:00.000Z',
      }),
    ])

    renderTab()

    await waitFor(() => {
      expect(screen.getByText('MiniMax Mavis')).toBeInTheDocument()
      expect(screen.getByText('minimax-mavis')).toBeInTheDocument()
      expect(screen.getByText('v1.0.0')).toBeInTheDocument()
      expect(screen.getByText('CN')).toBeInTheDocument()
    })

    // Confirm no secret tokens exist in document
    expect(screen.queryByText(/token/i)).not.toHaveTextContent(/secret|bearer|key_val/i)
  })

  it('shows error state when KEY_UNAVAILABLE and disables actions', async () => {
    vi.mocked(pluginsService.listPlugins).mockResolvedValue([
      samplePlugin({
        status: 'KEY_UNAVAILABLE',
      }),
    ])

    renderTab()

    await waitFor(() => {
      expect(screen.getByText('MiniMax Mavis')).toBeInTheDocument()
    })

    const connectBtn = screen.getByRole('button', { name: /连接.*MiniMax Mavis|Connect.*MiniMax Mavis/i })
    expect(connectBtn).toBeDisabled()
  })

  it('shows warnings and error details for REFRESH_FAILED and REFRESH_UNCERTAIN', async () => {
    vi.mocked(pluginsService.listPlugins).mockResolvedValue([
      samplePlugin({
        status: 'REFRESH_FAILED',
        lastRefreshError: 'Network timeout during refresh',
      }),
      samplePlugin({
        pluginId: 'plugin-uncertain',
        name: 'Uncertain Plugin',
        status: 'REFRESH_UNCERTAIN',
      }),
    ])

    renderTab()

    await waitFor(() => {
      expect(screen.getByText('Network timeout during refresh')).toBeInTheDocument()
      expect(screen.getAllByText(/刷新状态不确定|Refresh Uncertain/i).length).toBeGreaterThan(0)
    })
  })

  it('connects via deep link: selects region, prepares URL, clears password input, and uses generation fence', async () => {
    const user = userEvent.setup()
    vi.mocked(pluginsService.listPlugins).mockResolvedValue([samplePlugin()])
    vi.mocked(pluginsService.prepareAuth).mockResolvedValue({
      loginUrl: 'https://api.minimax.chat/login',
    })
    vi.mocked(pluginsService.completeAuth).mockResolvedValue(undefined)

    renderTab()

    await waitFor(() => {
      expect(screen.getByText('MiniMax Mavis')).toBeInTheDocument()
    })

    const connectBtn = screen.getByRole('button', { name: /连接.*MiniMax Mavis|Connect.*MiniMax Mavis/i })
    await user.click(connectBtn)

    const modal = screen.getByRole('dialog')
    expect(modal).toBeInTheDocument()

    // Step 1: Open login URL
    const openLoginBtn = within(modal).getByRole('button', { name: /打开官方登录页面|Open Login Page/i })
    await user.click(openLoginBtn)

    expect(pluginsService.prepareAuth).toHaveBeenCalledWith('minimax-mavis', { region: 'CN' })
    expect(window.open).toHaveBeenCalledWith(
      'https://api.minimax.chat/login',
      '_blank',
      'noopener,noreferrer',
    )

    // Step 2: Callback input must be type=password and autoComplete=off
    const callbackInput = within(modal).getByTestId('plugin-callback-input')
    expect(callbackInput).toHaveAttribute('type', 'password')
    expect(callbackInput).toHaveAttribute('autoComplete', 'off')

    await user.type(callbackInput, 'minimax-cn://auth-callback?code=super-secret-one-time-token')

    // Submit complete
    const submitBtn = within(modal).getByRole('button', { name: /完成连接|Complete Connection/i })
    await user.click(submitBtn)

    await waitFor(() => {
      expect(pluginsService.completeAuth).toHaveBeenCalledWith('minimax-mavis', {
        callbackUrl: 'minimax-cn://auth-callback?code=super-secret-one-time-token',
      })
    })

    // Modal closed on success
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
  })

  it('clears password input on completion failure and displays bounded error', async () => {
    const user = userEvent.setup()
    vi.mocked(pluginsService.listPlugins).mockResolvedValue([samplePlugin()])
    vi.mocked(pluginsService.completeAuth).mockRejectedValue(new Error('Invalid deep link'))

    renderTab()

    await waitFor(() => {
      expect(screen.getByText('MiniMax Mavis')).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: /连接.*MiniMax Mavis|Connect.*MiniMax Mavis/i }))
    const modal = screen.getByRole('dialog')

    const callbackInput = within(modal).getByTestId('plugin-callback-input')
    await user.type(callbackInput, 'invalid-url')

    const submitBtn = within(modal).getByRole('button', { name: /完成连接|Complete Connection/i })
    await user.click(submitBtn)

    await waitFor(() => {
      expect(within(modal).getByRole('alert')).toHaveTextContent('Invalid deep link')
    })
    // Immediately cleared
    expect(callbackInput).toHaveValue('')
  })

  it('disconnects with confirmation dialog', async () => {
    const user = userEvent.setup()
    vi.mocked(pluginsService.listPlugins).mockResolvedValue([
      samplePlugin({
        status: 'CONNECTED',
        region: 'CN',
      }),
    ])
    vi.mocked(pluginsService.disconnectAuth).mockResolvedValue(undefined)

    renderTab()

    await waitFor(() => {
      expect(screen.getByText('MiniMax Mavis')).toBeInTheDocument()
    })

    const disconnectBtn = screen.getByRole('button', { name: /断开连接.*MiniMax Mavis|Disconnect.*MiniMax Mavis/i })
    await user.click(disconnectBtn)

    const confirmModal = await screen.findByRole('alertdialog')
    expect(confirmModal).toBeInTheDocument()

    const confirmBtn = within(confirmModal).getByRole('button', { name: /断开连接|Disconnect/i })
    await user.click(confirmBtn)

    await waitFor(() => {
      expect(pluginsService.disconnectAuth).toHaveBeenCalledWith('minimax-mavis')
    })
  })
})
