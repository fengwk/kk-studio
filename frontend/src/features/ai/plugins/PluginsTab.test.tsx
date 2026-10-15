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
    updateConfiguration: vi.fn(),
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
    configuration: {
      revision: '1',
      valuesJson: '{"enabled":true}',
      valuesSchema:
        '{"type":"object","properties":{"enabled":{"type":"boolean"}},"required":[],"additionalProperties":false}',
      defaultsJson: '{"enabled":true}',
      secrets: {},
    },
    desiredEpoch: '1',
    appliedEpoch: '1',
    desiredRevision: '1',
    appliedRevision: '1',
    connectionCheckSupported: false,
    harnessToolNames: ['search'],
    canvasFunctionNames: ['draw'],
    applicationError: null,
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

  it('renders installed plugin list with safe fields, active capabilities, and no secrets', async () => {
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
      // Active capabilities rendered
      expect(screen.getByText('search, draw')).toBeInTheDocument()
      // Epoch & revision coordinates rendered (desired and applied)
      expect(screen.getAllByText('Epoch 1 · Rev 1').length).toBe(2)
    })

    // Confirm no secret tokens exist in document
    expect(screen.queryByText(/token/i)).not.toHaveTextContent(/secret|bearer|key_val/i)
  })

  it('formats HTTP epoch seconds including zero and shows static key rejection diagnostics', async () => {
    vi.mocked(pluginsService.listPlugins).mockResolvedValue([
      samplePlugin({
        status: 'CONNECTED',
        region: 'CN',
        expiresAt: 1777574730,
        nextRefreshAt: 0,
      }),
      samplePlugin({
        pluginId: 'rejected-static',
        name: 'Rejected Static Key',
        authKind: { type: 'STATIC_KEY', secretField: 'apiKey' },
        status: 'REAUTH_REQUIRED',
        lastRefreshError: 'provider rejected the stored static key',
      }),
    ])
    renderTab()
    expect(await screen.findByText(new Date(1777574730 * 1000).toLocaleString('zh-CN')))
      .toBeInTheDocument()
    expect(screen.getByText(new Date(0).toLocaleString('zh-CN'))).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('provider rejected the stored static key')
  })

  it('renders none/staticKey/deepLink plugin variants accurately', async () => {
    const noAuthPlugin = samplePlugin({
      pluginId: 'no-auth-plugin',
      name: 'No Auth Plugin',
      authKind: null,
      status: null,
      region: null,
      harnessToolNames: ['util'],
      canvasFunctionNames: [],
    })

    const staticKeyPlugin = samplePlugin({
      pluginId: 'static-key-plugin',
      name: 'Static Key Plugin',
      authKind: {
        type: 'STATIC_KEY',
        secretField: 'apiKey',
      },
      status: 'NOT_CONNECTED',
      region: null,
      configuration: {
        revision: '2',
        valuesJson: '{}',
        valuesSchema: '{"type":"object","properties":{},"required":[],"additionalProperties":false}',
        defaultsJson: '{}',
        secrets: {
          apiKey: {
            description: 'API key secret',
            configured: true,
          },
        },
      },
    })

    vi.mocked(pluginsService.listPlugins).mockResolvedValue([noAuthPlugin, staticKeyPlugin])
    renderTab()

    await waitFor(() => {
      expect(screen.getByText('No Auth Plugin')).toBeInTheDocument()
      expect(screen.getByText('Static Key Plugin')).toBeInTheDocument()
    })

    // None auth plugin has no connect/disconnect buttons and no status pill
    expect(
      screen.queryByRole('button', { name: /连接.*No Auth Plugin|Connect.*No Auth Plugin/i }),
    ).not.toBeInTheDocument()

    // StaticKey plugin has no connect/disconnect button, only Configure
    expect(
      screen.queryByRole('button', { name: /连接.*Static Key Plugin|Connect.*Static Key Plugin/i }),
    ).not.toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: /配置.*Static Key Plugin|Configure.*Static Key Plugin/i }),
    ).toBeInTheDocument()
  })

  it('shows pending application warning when coordinates differ or applicationError is present', async () => {
    vi.mocked(pluginsService.listPlugins).mockResolvedValue([
      samplePlugin({
        desiredEpoch: '5',
        appliedEpoch: '4',
        applicationError: 'configuration is saved but this node has not applied it yet',
      }),
    ])

    renderTab()

    await waitFor(() => {
      expect(screen.getByTestId('plugin-app-status-minimax-mavis')).toHaveTextContent('待应用')
      expect(screen.getByTestId('plugin-app-error-minimax-mavis')).toHaveTextContent(
        'configuration is saved but this node has not applied it yet',
      )
    })
  })

  it('opens configuration modal when clicking configure button and handles save success', async () => {
    const user = userEvent.setup()
    vi.mocked(pluginsService.listPlugins).mockResolvedValue([samplePlugin()])
    vi.mocked(pluginsService.updateConfiguration).mockResolvedValue(samplePlugin())
    renderTab()

    await waitFor(() => {
      expect(screen.getByText('MiniMax Mavis')).toBeInTheDocument()
    })

    const configureBtn = screen.getByRole('button', {
      name: /配置.*MiniMax Mavis|Configure.*MiniMax Mavis/i,
    })
    await user.click(configureBtn)

    expect(await screen.findByRole('dialog')).toBeInTheDocument()
    expect(screen.getByText(/配置插件：MiniMax Mavis/i)).toBeInTheDocument()

    const saveBtn = screen.getByRole('button', { name: '保存配置' })
    await user.click(saveBtn)

    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
  })

  it('displays query error in StateBlock when listPlugins fails', async () => {
    vi.mocked(pluginsService.listPlugins).mockRejectedValue(new Error('Failed to load plugins'))
    renderTab()

    await waitFor(() => {
      expect(screen.getByText('Failed to load plugins')).toBeInTheDocument()
    })
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

    const connectBtn = screen.getByRole('button', {
      name: /连接.*MiniMax Mavis|Connect.*MiniMax Mavis/i,
    })
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

  it('connects via deep link: selects region, prepares URL, clears password input, and completes with nonce', async () => {
    const user = userEvent.setup()
    vi.mocked(pluginsService.listPlugins).mockResolvedValue([samplePlugin()])
    vi.mocked(pluginsService.prepareAuth).mockResolvedValue({
      authAttemptId: 'attempt-nonce-xyz',
      loginUrl: 'https://api.minimax.chat/login',
    })
    vi.mocked(pluginsService.completeAuth).mockResolvedValue(samplePlugin({ status: 'CONNECTED' }))

    renderTab()

    await waitFor(() => {
      expect(screen.getByText('MiniMax Mavis')).toBeInTheDocument()
    })

    const connectBtn = screen.getByRole('button', {
      name: /连接.*MiniMax Mavis|Connect.*MiniMax Mavis/i,
    })
    await user.click(connectBtn)

    const modal = screen.getByRole('dialog')
    expect(modal).toBeInTheDocument()

    // Step 1: Open login URL
    const openLoginBtn = within(modal).getByRole('button', {
      name: /打开官方登录页面|Open Login Page/i,
    })
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
    const submitBtn = within(modal).getByRole('button', {
      name: /完成连接|Complete Connection/i,
    })
    await user.click(submitBtn)

    await waitFor(() => {
      expect(pluginsService.completeAuth).toHaveBeenCalledWith('minimax-mavis', {
        authAttemptId: 'attempt-nonce-xyz',
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
    vi.mocked(pluginsService.prepareAuth).mockResolvedValue({
      authAttemptId: 'attempt-123',
      loginUrl: 'https://login.example.com',
    })
    vi.mocked(pluginsService.completeAuth).mockRejectedValue(new Error('Invalid deep link'))

    renderTab()

    await waitFor(() => {
      expect(screen.getByText('MiniMax Mavis')).toBeInTheDocument()
    })

    await user.click(
      screen.getByRole('button', { name: /连接.*MiniMax Mavis|Connect.*MiniMax Mavis/i }),
    )
    const modal = screen.getByRole('dialog')

    // First prepare
    await user.click(
      within(modal).getByRole('button', { name: /打开官方登录页面|Open Login Page/i }),
    )

    const callbackInput = within(modal).getByTestId('plugin-callback-input')
    await user.type(callbackInput, 'invalid-url')

    const submitBtn = within(modal).getByRole('button', {
      name: /完成连接|Complete Connection/i,
    })
    await user.click(submitBtn)

    await waitFor(() => {
      expect(within(modal).getByRole('alert')).toHaveTextContent('Invalid deep link')
    })
    // Immediately cleared
    expect(callbackInput).toHaveValue('')
  })

  it('disconnects with confirmation dialog and handles error', async () => {
    const user = userEvent.setup()
    vi.mocked(pluginsService.listPlugins).mockResolvedValue([
      samplePlugin({
        status: 'CONNECTED',
        region: 'CN',
      }),
    ])
    vi.mocked(pluginsService.disconnectAuth).mockRejectedValueOnce(
      new Error('Disconnect network failure'),
    )

    renderTab()

    await waitFor(() => {
      expect(screen.getByText('MiniMax Mavis')).toBeInTheDocument()
    })

    const disconnectBtn = screen.getByRole('button', {
      name: /断开连接.*MiniMax Mavis|Disconnect.*MiniMax Mavis/i,
    })
    await user.click(disconnectBtn)

    const confirmModal = await screen.findByRole('alertdialog')
    expect(confirmModal).toBeInTheDocument()

    const confirmBtn = within(confirmModal).getByRole('button', { name: /断开连接|Disconnect/i })
    await user.click(confirmBtn)

    await waitFor(() => {
      expect(screen.getByText('Disconnect network failure')).toBeInTheDocument()
    })

    // Cancel modal closes it and clears error
    const cancelBtn = within(confirmModal).getByRole('button', { name: /取消|Cancel/i })
    await user.click(cancelBtn)
    await waitFor(() => {
      expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    })
  })

  it('disconnects successfully and closes confirm modal', async () => {
    const user = userEvent.setup()
    vi.mocked(pluginsService.listPlugins).mockResolvedValue([
      samplePlugin({
        status: 'CONNECTED',
        region: 'CN',
      }),
    ])
    vi.mocked(pluginsService.disconnectAuth).mockResolvedValueOnce(undefined)

    renderTab()

    await waitFor(() => {
      expect(screen.getByText('MiniMax Mavis')).toBeInTheDocument()
    })

    const disconnectBtn = screen.getByRole('button', {
      name: /断开连接.*MiniMax Mavis|Disconnect.*MiniMax Mavis/i,
    })
    await user.click(disconnectBtn)

    const confirmModal = await screen.findByRole('alertdialog')
    const confirmBtn = within(confirmModal).getByRole('button', { name: /断开连接|Disconnect/i })
    await user.click(confirmBtn)

    await waitFor(() => {
      expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    })
  })

  it('closes configure and connect modals when clicking close buttons', async () => {
    const user = userEvent.setup()
    vi.mocked(pluginsService.listPlugins).mockResolvedValue([samplePlugin()])
    renderTab()

    await waitFor(() => {
      expect(screen.getByText('MiniMax Mavis')).toBeInTheDocument()
    })

    // Open & close config modal
    const configureBtn = screen.getByRole('button', {
      name: /配置.*MiniMax Mavis|Configure.*MiniMax Mavis/i,
    })
    await user.click(configureBtn)
    expect(await screen.findByRole('dialog')).toBeInTheDocument()

    const closeBtn = screen.getByRole('button', { name: '关闭' })
    await user.click(closeBtn)
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })

    // Open & close connect modal
    const connectBtn = screen.getByRole('button', {
      name: /连接.*MiniMax Mavis|Connect.*MiniMax Mavis/i,
    })
    await user.click(connectBtn)
    expect(await screen.findByRole('dialog')).toBeInTheDocument()

    const connectCloseBtn = screen.getByRole('button', { name: '关闭' })
    await user.click(connectCloseBtn)
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
  })
})
