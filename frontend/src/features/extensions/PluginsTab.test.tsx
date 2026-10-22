import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { PluginsTab } from '@/features/extensions/PluginsTab'
import { pluginsService } from '@/shared/api/plugins-service'
import { ApiError } from '@/shared/api/client'
import { queryKeys } from '@/shared/lib/query-keys'
import type { PluginConnectionCheckDTO, PluginDTO } from '@/shared/api/generated/contracts'

vi.mock('@/shared/api/plugins-service', () => ({
  pluginsService: {
    listPlugins: vi.fn(),
    getPlugin: vi.fn(),
    prepareAuth: vi.fn(),
    completeAuth: vi.fn(),
    updateConfiguration: vi.fn(),
    disconnectAuth: vi.fn(),
    checkConnection: vi.fn(),
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
        expiresAt: 1790856000,
        nextRefreshAt: 1790337600,
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
      expect(screen.getAllByText('Rev 1').length).toBe(2)
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

  function checkablePlugin(overrides: Partial<PluginDTO> = {}): PluginDTO {
    return samplePlugin({
      pluginId: 'opencli',
      name: 'OpenCLI Probe',
      status: 'CONNECTED',
      region: 'CN',
      connectionCheckSupported: true,
      desiredRevision: '8',
      appliedRevision: '8',
      ...overrides,
    })
  }

  function checkResult(
    overrides: Partial<PluginConnectionCheckDTO> = {},
  ): PluginConnectionCheckDTO {
    return {
      pluginId: 'opencli',
      appliedRevision: '8',
      checkedAt: 1772366400,
      reachability: 'REACHABLE',
      authentication: 'ACCEPTED',
      browserSession: 'UNKNOWN',
      ...overrides,
    }
  }

  function deferred<T>() {
    let resolve!: (value: T) => void
    let reject!: (error: unknown) => void
    const promise = new Promise<T>((res, rej) => {
      resolve = res
      reject = rej
    })
    return { promise, resolve, reject }
  }

  function checkButton(name: string): HTMLElement {
    return screen.getByRole('button', { name: `检查连接 ${name}` })
  }

  it('never issues a connection check from render, list load, or for instances without the capability', async () => {
    vi.mocked(pluginsService.listPlugins).mockResolvedValue([
      checkablePlugin(),
      samplePlugin({ pluginId: 'unsupported', name: 'Unsupported Plugin' }),
    ])

    renderTab()

    await waitFor(() => {
      expect(screen.getByText('OpenCLI Probe')).toBeInTheDocument()
      expect(screen.getByText('Unsupported Plugin')).toBeInTheDocument()
    })
    // 挂载、列表加载与渲染都不得触发检查。
    expect(pluginsService.checkConnection).not.toHaveBeenCalled()
    expect(checkButton('OpenCLI Probe')).toBeEnabled()
    // 能力来自实例 metadata：未声明的实例连入口都不展示。
    expect(
      screen.queryByRole('button', { name: '检查连接 Unsupported Plugin' }),
    ).not.toBeInTheDocument()

    // 打开其它弹层同样不触发检查。
    await userEvent.setup().click(
      screen.getByRole('button', { name: '配置 Unsupported Plugin' }),
    )
    expect(await screen.findByRole('dialog')).toBeInTheDocument()
    expect(pluginsService.checkConnection).not.toHaveBeenCalled()
  })

  it('disables the entry with an explanation while the local coordinates are unusable', async () => {
    const user = userEvent.setup()
    vi.mocked(pluginsService.listPlugins).mockResolvedValue([
      checkablePlugin({ desiredRevision: '9', applicationError: 'configuration is not applied yet' }),
      checkablePlugin({
        pluginId: 'no-applied',
        name: 'No Applied Plugin',
        appliedRevision: null,
      }),
      checkablePlugin({
        pluginId: 'zero-revision',
        name: 'Zero Revision Plugin',
        desiredRevision: '0',
        appliedRevision: '0',
      }),
    ])

    renderTab()

    await waitFor(() => {
      expect(screen.getByText('No Applied Plugin')).toBeInTheDocument()
      expect(screen.getByText('Zero Revision Plugin')).toBeInTheDocument()
    })

    for (const name of ['OpenCLI Probe', 'No Applied Plugin', 'Zero Revision Plugin']) {
      expect(checkButton(name)).toBeDisabled()
    }
    expect(screen.getByTestId('plugin-check-blocked-opencli')).toHaveTextContent('尚未应用当前配置')
    expect(screen.getByTestId('plugin-check-blocked-no-applied')).toHaveTextContent('尚未应用当前配置')
    expect(screen.getByTestId('plugin-check-blocked-zero-revision')).toHaveTextContent('尚未应用当前配置')

    // 禁用入口绝不发出请求，也绝不提交 null 或 0 坐标。
    await user.click(checkButton('No Applied Plugin'))
    expect(pluginsService.checkConnection).not.toHaveBeenCalled()
  })

  it('runs exactly one POST with the applied coordinates and shows the three dimensions independently', async () => {
    const user = userEvent.setup()
    vi.mocked(pluginsService.listPlugins).mockResolvedValue([checkablePlugin()])
    const pending = deferred<PluginConnectionCheckDTO>()
    vi.mocked(pluginsService.checkConnection).mockReturnValue(pending.promise)

    renderTab()
    await waitFor(() => expect(screen.getByText('OpenCLI Probe')).toBeInTheDocument())

    const opener = checkButton('OpenCLI Probe')
    await user.click(opener)

    expect(pluginsService.checkConnection).toHaveBeenCalledTimes(1)
    expect(pluginsService.checkConnection).toHaveBeenCalledWith('opencli', {
      expectedRevision: '8',
    })

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByRole('heading', { name: '连接检查：OpenCLI Probe' })).toBeInTheDocument()
    expect(within(dialog).getByTestId('plugin-check-pending')).toBeInTheDocument()
    expect(within(dialog).getByTestId('plugin-check-coordinates')).toHaveTextContent('Rev 8')

    // 进行中的检查禁用入口并阻止关闭，重复激活不产生第二次请求。
    expect(opener).toBeDisabled()
    expect(opener).toHaveAttribute('aria-busy', 'true')
    fireEvent.click(opener)
    fireEvent.click(within(dialog).getByTestId('plugin-check-close'))
    expect(pluginsService.checkConnection).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('dialog')).toBeInTheDocument()

    await act(async () => {
      pending.resolve(checkResult())
    })

    await waitFor(() => {
      expect(within(dialog).getByTestId('plugin-check-reachability')).toHaveTextContent('可达')
    })
    expect(within(dialog).getByTestId('plugin-check-authentication')).toHaveTextContent('凭据已接受')
    expect(within(dialog).getByTestId('plugin-check-browser-session')).toHaveTextContent(
      '未知（本检查不核验浏览器登录态）',
    )
    expect(within(dialog).getByTestId('plugin-check-checked-at')).toHaveTextContent(
      new Date(1772366400 * 1000).toLocaleString('zh-CN'),
    )
    // 三个维度各自独立呈现，绝不把认证结论说成浏览器登录。
    expect(within(dialog).getByTestId('plugin-check-authentication')).not.toHaveTextContent('浏览器')
    expect(within(dialog).getByTestId('plugin-check-browser-session')).not.toHaveTextContent('已登录')
    expect(pluginsService.checkConnection).toHaveBeenCalledTimes(1)
  })

  it('discards a late response for a plugin the user has left', async () => {
    const user = userEvent.setup()
    vi.mocked(pluginsService.listPlugins).mockResolvedValue([
      checkablePlugin(),
      checkablePlugin({ pluginId: 'other', name: 'Other Plugin' }),
    ])
    const first = deferred<PluginConnectionCheckDTO>()
    const second = deferred<PluginConnectionCheckDTO>()
    vi.mocked(pluginsService.checkConnection).mockReturnValueOnce(first.promise)
    vi.mocked(pluginsService.checkConnection).mockReturnValueOnce(second.promise)

    renderTab()
    await waitFor(() => expect(screen.getByText('Other Plugin')).toBeInTheDocument())

    await user.click(checkButton('OpenCLI Probe'))
    await user.click(checkButton('Other Plugin'))
    expect(pluginsService.checkConnection).toHaveBeenNthCalledWith(2, 'other', {
      expectedRevision: '8',
    })

    // 迟到的旧插件响应必须被丢弃：既不显示维度，也不冒充当前检查的健康状态。
    await act(async () => {
      first.resolve(checkResult())
    })
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).queryByTestId('plugin-check-reachability')).toBeNull()
    expect(within(dialog).getByTestId('plugin-check-pending')).toBeInTheDocument()

    await act(async () => {
      second.resolve(checkResult({ pluginId: 'other' }))
    })
    await waitFor(() => {
      expect(within(dialog).getByTestId('plugin-check-reachability')).toHaveTextContent('可达')
    })
    expect(pluginsService.checkConnection).toHaveBeenCalledTimes(2)
  })

  it('rejects a response whose identity or coordinates differ from the request', async () => {
    const user = userEvent.setup()
    vi.mocked(pluginsService.listPlugins).mockResolvedValue([checkablePlugin()])

    // 跨 ID：服务端回了别的安装身份。
    vi.mocked(pluginsService.checkConnection).mockResolvedValueOnce(
      checkResult({ pluginId: 'someone-else' }),
    )
    renderTab()
    await waitFor(() => expect(screen.getByText('OpenCLI Probe')).toBeInTheDocument())
    await user.click(checkButton('OpenCLI Probe'))

    let dialog = await screen.findByRole('dialog')
    await waitFor(() => {
      expect(within(dialog).getByTestId('plugin-check-mismatch')).toHaveTextContent(
        '结果与本次请求不一致，已丢弃。',
      )
    })
    expect(within(dialog).queryByTestId('plugin-check-reachability')).toBeNull()

    // 跨坐标：同 ID 但坐标与本次请求不同。
    await user.click(within(dialog).getByTestId('plugin-check-close'))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    vi.mocked(pluginsService.checkConnection).mockResolvedValueOnce(
      checkResult({ appliedRevision: '99' }),
    )
    await user.click(checkButton('OpenCLI Probe'))

    dialog = await screen.findByRole('dialog')
    await waitFor(() => {
      expect(within(dialog).getByTestId('plugin-check-mismatch')).toBeInTheDocument()
    })
    expect(within(dialog).queryByTestId('plugin-check-authentication')).toBeNull()
  })

  it('keeps one in-flight check per plugin across plugin switches and allows an explicit re-check after settling', async () => {
    const user = userEvent.setup()
    vi.mocked(pluginsService.listPlugins).mockResolvedValue([
      checkablePlugin(),
      checkablePlugin({ pluginId: 'other', name: 'Other Plugin' }),
    ])
    const firstA = deferred<PluginConnectionCheckDTO>()
    const b = deferred<PluginConnectionCheckDTO>()
    const secondA = deferred<PluginConnectionCheckDTO>()
    vi.mocked(pluginsService.checkConnection)
      .mockReturnValueOnce(firstA.promise)
      .mockReturnValueOnce(b.promise)
      .mockReturnValueOnce(secondA.promise)

    renderTab()
    await waitFor(() => expect(screen.getByText('Other Plugin')).toBeInTheDocument())

    // A 未完成前切到 B：B 是另一次显式点击，A 仍只有一次请求。
    await user.click(checkButton('OpenCLI Probe'))
    expect(pluginsService.checkConnection).toHaveBeenCalledTimes(1)
    await user.click(checkButton('Other Plugin'))
    expect(pluginsService.checkConnection).toHaveBeenCalledTimes(2)
    expect(pluginsService.checkConnection).toHaveBeenNthCalledWith(1, 'opencli', {
      expectedRevision: '8',
    })
    expect(pluginsService.checkConnection).toHaveBeenNthCalledWith(2, 'other', {
      expectedRevision: '8',
    })

    // A 仍在途：即使视图已切到 B，A 的入口也必须保持禁用，再次激活不产生第二次 A 请求。
    expect(checkButton('OpenCLI Probe')).toBeDisabled()
    fireEvent.click(checkButton('OpenCLI Probe'))
    expect(pluginsService.checkConnection).toHaveBeenCalledTimes(2)

    // A 的迟到结果被丢弃，既不复活维度也不解除 B 的在途状态。
    await act(async () => {
      firstA.resolve(checkResult())
    })
    let dialog = screen.getByRole('dialog')
    expect(within(dialog).queryByTestId('plugin-check-reachability')).toBeNull()
    expect(within(dialog).getByTestId('plugin-check-pending')).toBeInTheDocument()
    // A 请求结束后其入口重新可用（在途标记随 finally 解除）。
    expect(checkButton('OpenCLI Probe')).toBeEnabled()

    await act(async () => {
      b.resolve(checkResult({ pluginId: 'other' }))
    })
    await waitFor(() => {
      expect(within(dialog).getByTestId('plugin-check-reachability')).toHaveTextContent('可达')
    })
    await user.click(within(dialog).getByTestId('plugin-check-close'))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())

    // 关闭之后唯一的新请求来自用户显式再次点击。
    await user.click(checkButton('OpenCLI Probe'))
    expect(pluginsService.checkConnection).toHaveBeenCalledTimes(3)
    dialog = await screen.findByRole('dialog')
    await act(async () => {
      secondA.resolve(checkResult())
    })
    await waitFor(() => {
      expect(within(dialog).getByTestId('plugin-check-reachability')).toHaveTextContent('可达')
    })
    expect(within(dialog).getByTestId('plugin-check-authentication')).toHaveTextContent('凭据已接受')
    expect(pluginsService.checkConnection).toHaveBeenCalledTimes(3)
  })

  it('shows a completed result only as stale once the catalogue applies new coordinates', async () => {
    const user = userEvent.setup()
    vi.mocked(pluginsService.listPlugins).mockResolvedValue([checkablePlugin()])
    vi.mocked(pluginsService.checkConnection).mockResolvedValue(checkResult())

    renderTab()
    await waitFor(() => expect(screen.getByText('OpenCLI Probe')).toBeInTheDocument())
    await user.click(checkButton('OpenCLI Probe'))

    const dialog = await screen.findByRole('dialog')
    await waitFor(() => {
      expect(within(dialog).getByTestId('plugin-check-reachability')).toBeInTheDocument()
    })

    // 异步换版：列表刷新为新的已应用坐标。
    vi.mocked(pluginsService.listPlugins).mockResolvedValue([
      checkablePlugin({ desiredRevision: '9', appliedRevision: '9' }),
    ])
    await act(async () => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.plugins.all })
    })

    await waitFor(() => {
      expect(within(dialog).getByTestId('plugin-check-stale')).toHaveTextContent('此后配置已改变')
    })
    expect(within(dialog).queryByTestId('plugin-check-reachability')).toBeNull()
    expect(within(dialog).queryByTestId('plugin-check-authentication')).toBeNull()
    // 陈旧结果绝不自动重查。
    expect(pluginsService.checkConnection).toHaveBeenCalledTimes(1)
  })

  it('drops the result on close and never probes again on its own', async () => {
    const user = userEvent.setup()
    vi.mocked(pluginsService.listPlugins).mockResolvedValue([checkablePlugin()])
    vi.mocked(pluginsService.checkConnection).mockResolvedValue(checkResult())

    renderTab()
    await waitFor(() => expect(screen.getByText('OpenCLI Probe')).toBeInTheDocument())
    await user.click(checkButton('OpenCLI Probe'))

    const dialog = await screen.findByRole('dialog')
    await waitFor(() => {
      expect(within(dialog).getByTestId('plugin-check-reachability')).toBeInTheDocument()
    })

    await user.click(within(dialog).getByTestId('plugin-check-close'))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(screen.queryByTestId('plugin-check-reachability')).toBeNull()

    // 关闭后列表换版：既不复活旧结果，也不自动重新检查。
    vi.mocked(pluginsService.listPlugins).mockResolvedValue([checkablePlugin({ desiredRevision: '9' })])
    await act(async () => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.plugins.all })
    })
    expect(screen.queryByTestId('plugin-check-stale')).toBeNull()
    expect(screen.queryByTestId('plugin-check-reachability')).toBeNull()
    expect(pluginsService.checkConnection).toHaveBeenCalledTimes(1)
  })

  it('reports a 409 as a conflict, refreshes the list, and never retries the check', async () => {
    const user = userEvent.setup()
    vi.mocked(pluginsService.listPlugins).mockResolvedValue([checkablePlugin()])
    vi.mocked(pluginsService.checkConnection).mockRejectedValueOnce(
      new ApiError('配置已改变', 409),
    )

    renderTab()
    await waitFor(() => expect(screen.getByText('OpenCLI Probe')).toBeInTheDocument())
    await user.click(checkButton('OpenCLI Probe'))

    const dialog = await screen.findByRole('dialog')
    await waitFor(() => {
      expect(within(dialog).getByTestId('plugin-check-conflict')).toHaveTextContent(
        '配置已改变，请刷新列表后重新检查。',
      )
    })
    // 冲突只刷新列表，绝不自动重发检查。
    await waitFor(() => expect(pluginsService.listPlugins).toHaveBeenCalledTimes(2))
    expect(pluginsService.checkConnection).toHaveBeenCalledTimes(1)
    expect(within(dialog).queryByTestId('plugin-check-reachability')).toBeNull()
  })

  it('never lets a rejected or unauthorized check overwrite the local authentication projection', async () => {
    const user = userEvent.setup()
    vi.mocked(pluginsService.listPlugins).mockResolvedValue([checkablePlugin()])

    // 远端明确拒绝本次凭据：本地认证状态不受影响。
    vi.mocked(pluginsService.checkConnection).mockResolvedValueOnce(
      checkResult({ authentication: 'REJECTED', reachability: 'REACHABLE' }),
    )
    renderTab()
    await waitFor(() => expect(screen.getByText('OpenCLI Probe')).toBeInTheDocument())
    await user.click(checkButton('OpenCLI Probe'))

    let dialog = await screen.findByRole('dialog')
    await waitFor(() => {
      expect(within(dialog).getByTestId('plugin-check-authentication')).toHaveTextContent(
        '凭据被拒绝',
      )
    })
    expect(screen.getByTestId('plugin-status-opencli')).toHaveTextContent('已连接')
    expect(pluginsService.listPlugins).toHaveBeenCalledTimes(1)

    await user.click(within(dialog).getByTestId('plugin-check-close'))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())

    // 传输层 401 只作为可控失败呈现，既不刷新列表也不改写本地认证投影。
    vi.mocked(pluginsService.checkConnection).mockRejectedValueOnce(
      new ApiError('Unauthorized', 401),
    )
    await user.click(checkButton('OpenCLI Probe'))

    dialog = await screen.findByRole('dialog')
    await waitFor(() => {
      expect(within(dialog).getByTestId('plugin-check-failed')).toHaveTextContent('Unauthorized')
    })
    expect(screen.getByTestId('plugin-status-opencli')).toHaveTextContent('已连接')
    expect(within(dialog).queryByTestId('plugin-check-conflict')).toBeNull()
    expect(pluginsService.listPlugins).toHaveBeenCalledTimes(1)
    expect(pluginsService.checkConnection).toHaveBeenCalledTimes(2)
  })
})
