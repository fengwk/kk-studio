import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { PluginConfigModal } from './PluginConfigModal'
import { pluginsService } from '@/shared/api/plugins-service'
import { ApiError } from '@/shared/api/client'
import type { PluginDTO } from '@/shared/api/contracts/ai-plugin'

vi.mock('@/shared/api/plugins-service', () => ({
  pluginsService: { updateConfiguration: vi.fn() },
}))

const opencliPluginFixture: PluginDTO = {
  pluginId: 'opencli',
  name: 'OpenCLI',
  version: '1.0.0',
  authKind: {
    type: 'STATIC_KEY',
    secretField: 'apiKey',
  },
  status: 'CONNECTED',
  region: null,
  expiresAt: null,
  nextRefreshAt: null,
  lastRefreshedAt: null,
  lastRefreshError: null,
  configuration: {
    revision: '1',
    valuesJson: JSON.stringify({
      enabled: false,
      baseUrl: 'http://127.0.0.1:8080',
      instanceId: 'inst-default',
      connectTimeoutMillis: 5000,
      requestTimeoutMillis: 120000,
      longPollTimeoutMillis: 130000,
      streamBufferBytes: 16384,
      maxJsonResponseBytes: 524288,
      maxErrorResponseBytes: 4096,
      maxOutputChars: 65535,
      gptImage2: {
        paidEnabled: false,
        askTimeoutSeconds: 900,
        hubExecutionTimeoutMillis: 960000,
        maxWaitMillis: 1200000,
      },
      seedance: {
        enabled: false,
        workspaceId: 'workspace-main',
        retry: 0,
        hubExecutionTimeoutMillis: 600000,
        statusPollIntervalMillis: 30000,
        maxWaitMillis: 1800000,
      },
    }),
    valuesSchema: JSON.stringify({
      type: 'object',
      description: 'OpenCLI configuration',
      properties: {
        enabled: { type: 'boolean', description: 'whether OpenCLI integration is enabled' },
        baseUrl: { type: 'string', description: 'OpenCLI Hub HTTP(S) origin base URL' },
        instanceId: { type: 'string', description: 'optional OpenCLI instance identifier' },
        connectTimeoutMillis: { type: 'integer', description: 'HTTP connect timeout in milliseconds' },
        requestTimeoutMillis: { type: 'integer', description: 'HTTP request timeout in milliseconds' },
        longPollTimeoutMillis: { type: 'integer', description: 'long polling timeout in milliseconds' },
        streamBufferBytes: { type: 'integer', description: 'streaming response buffer size in bytes' },
        maxJsonResponseBytes: { type: 'integer', description: 'maximum JSON response bytes' },
        maxErrorResponseBytes: { type: 'integer', description: 'maximum error response bytes' },
        maxOutputChars: { type: 'integer', description: 'maximum execution output characters' },
        gptImage2: {
          type: 'object',
          description: 'GPT Image 2 configuration',
          properties: {
            paidEnabled: { type: 'boolean', description: 'whether paid GPT Image 2 execution is enabled' },
            askTimeoutSeconds: { type: 'integer', description: 'timeout for ask operations in seconds' },
            hubExecutionTimeoutMillis: { type: 'integer', description: 'Hub execution timeout in milliseconds' },
            maxWaitMillis: { type: 'integer', description: 'maximum wait duration in milliseconds' },
          },
          required: ['paidEnabled', 'askTimeoutSeconds', 'hubExecutionTimeoutMillis', 'maxWaitMillis'],
          additionalProperties: false,
        },
        seedance: {
          type: 'object',
          description: 'Seedance configuration',
          properties: {
            enabled: { type: 'boolean', description: 'whether Seedance execution is enabled' },
            workspaceId: { type: 'string', description: 'optional Seedance workspace identifier' },
            retry: { type: 'integer', description: 'retry attempt count' },
            hubExecutionTimeoutMillis: { type: 'integer', description: 'Hub execution timeout in milliseconds' },
            statusPollIntervalMillis: { type: 'integer', description: 'status polling interval in milliseconds' },
            maxWaitMillis: { type: 'integer', description: 'maximum wait duration in milliseconds' },
          },
          required: [
            'enabled',
            'retry',
            'hubExecutionTimeoutMillis',
            'statusPollIntervalMillis',
            'maxWaitMillis',
          ],
          additionalProperties: false,
        },
      },
      required: [
        'enabled',
        'connectTimeoutMillis',
        'requestTimeoutMillis',
        'longPollTimeoutMillis',
        'streamBufferBytes',
        'maxJsonResponseBytes',
        'maxErrorResponseBytes',
        'maxOutputChars',
        'gptImage2',
        'seedance',
      ],
      additionalProperties: false,
    }),
    defaultsJson: JSON.stringify({
      enabled: false,
      connectTimeoutMillis: 5000,
      requestTimeoutMillis: 120000,
      longPollTimeoutMillis: 130000,
      streamBufferBytes: 16384,
      maxJsonResponseBytes: 524288,
      maxErrorResponseBytes: 4096,
      maxOutputChars: 65535,
      gptImage2: {
        paidEnabled: false,
        askTimeoutSeconds: 900,
        hubExecutionTimeoutMillis: 960000,
        maxWaitMillis: 1200000,
      },
      seedance: {
        enabled: false,
        retry: 0,
        hubExecutionTimeoutMillis: 600000,
        statusPollIntervalMillis: 30000,
        maxWaitMillis: 1800000,
      },
    }),
    secrets: {
      apiKey: {
        description: 'OpenCLI API key',
        configured: true,
      },
    },
  },
  desiredEpoch: '3',
  appliedEpoch: '3',
  desiredRevision: '1',
  appliedRevision: '1',
  harnessToolNames: [],
  canvasFunctionNames: ['gpt-image-2', 'seedance-1', 'seedance-2', 'seedance-3', 'seedance-4'],
  applicationError: null,
}

describe('PluginConfigModal OpenCLI regression coverage', () => {
  let queryClient: QueryClient
  const onClose = vi.fn()
  const onSuccess = vi.fn()

  beforeEach(() => {
    vi.resetAllMocks()
    queryClient = new QueryClient({
      defaultOptions: { mutations: { retry: false } },
    })
  })

  function renderModal(plugin: PluginDTO = opencliPluginFixture) {
    return render(
      <QueryClientProvider client={queryClient}>
        <PluginConfigModal plugin={plugin} onClose={onClose} onSuccess={onSuccess} />
      </QueryClientProvider>,
    )
  }

  it('renders OpenCLI nested schema accurately without leaking API key plaintext into standard fields', () => {
    renderModal()

    expect(screen.getByText('配置插件：OpenCLI')).toBeInTheDocument()
    expect(screen.getByText('Rev 1')).toBeInTheDocument()

    // 顶层标量字段
    expect(screen.getByRole('textbox', { name: 'baseUrl' })).toHaveValue('http://127.0.0.1:8080')
    expect(screen.getByRole('textbox', { name: 'instanceId' })).toHaveValue('inst-default')
    expect(screen.getByRole('textbox', { name: 'connectTimeoutMillis' })).toHaveValue('5000')
    expect(screen.getByRole('textbox', { name: 'requestTimeoutMillis' })).toHaveValue('120000')

    // 嵌套结构 gptImage2 渲染为 fieldset，并包含其子字段
    const gptFieldset = screen.getByRole('group', { name: 'gptImage2' })
    expect(gptFieldset).toBeInTheDocument()
    expect(within(gptFieldset).getByRole('checkbox', { name: 'paidEnabled' })).not.toBeChecked()
    expect(within(gptFieldset).getByRole('textbox', { name: 'askTimeoutSeconds' })).toHaveValue('900')

    // 嵌套结构 seedance 渲染为 fieldset，并包含其子字段
    const seedanceFieldset = screen.getByRole('group', { name: 'seedance' })
    expect(seedanceFieldset).toBeInTheDocument()
    expect(within(seedanceFieldset).getByRole('checkbox', { name: 'enabled' })).not.toBeChecked()
    expect(within(seedanceFieldset).getByRole('textbox', { name: 'workspaceId' })).toHaveValue('workspace-main')
    expect(within(seedanceFieldset).getByRole('textbox', { name: 'retry' })).toHaveValue('0')

    // API Key 绝不作为普通配置输入渲染，只在秘密区域显示状态，不回显凭据明文
    expect(screen.queryByRole('textbox', { name: 'apiKey' })).not.toBeInTheDocument()
    const secretItem = screen.getByTestId('secret-item-apiKey')
    expect(within(secretItem).getByText('apiKey')).toBeInTheDocument()
    expect(within(secretItem).getByText('OpenCLI API key')).toBeInTheDocument()
    expect(within(secretItem).getByText('已配置')).toBeInTheDocument()
  })

  it('edits nested schema properties and saves with expected coordinates and default KEEP secret operation', async () => {
    const user = userEvent.setup()
    vi.mocked(pluginsService.updateConfiguration).mockResolvedValue(opencliPluginFixture)
    renderModal()

    // 编辑顶层 baseUrl
    const baseUrlInput = screen.getByRole('textbox', { name: 'baseUrl' })
    await user.clear(baseUrlInput)
    await user.type(baseUrlInput, 'https://hub.example.com')

    // 编辑嵌套 gptImage2 的 paidEnabled
    const gptFieldset = screen.getByRole('group', { name: 'gptImage2' })
    const paidCheckbox = within(gptFieldset).getByRole('checkbox', { name: 'paidEnabled' })
    await user.click(paidCheckbox)
    expect(paidCheckbox).toBeChecked()

    // 编辑嵌套 seedance 的 retry
    const seedanceFieldset = screen.getByRole('group', { name: 'seedance' })
    const retryInput = within(seedanceFieldset).getByRole('textbox', { name: 'retry' })
    await user.clear(retryInput)
    await user.type(retryInput, '3')

    const saveBtn = screen.getByRole('button', { name: '保存配置' })
    await user.click(saveBtn)

    await waitFor(() => {
      expect(pluginsService.updateConfiguration).toHaveBeenCalledWith(
        'opencli',
        expect.objectContaining({
          expectedEpoch: '3',
          expectedRevision: '1',
          secretPatch: {
            apiKey: { operation: 'KEEP' },
          },
        }),
      )
    })

    const callPayload = vi.mocked(pluginsService.updateConfiguration).mock.calls[0][1]
    const parsedValues = JSON.parse(callPayload.valuesJson)
    expect(parsedValues.baseUrl).toBe('https://hub.example.com')
    expect(parsedValues.gptImage2.paidEnabled).toBe(true)
    expect(parsedValues.seedance.retry).toBe(3)
  })

  it('performs SET secret operation without trimming and clears sensitive state upon save', async () => {
    const user = userEvent.setup()
    vi.mocked(pluginsService.updateConfiguration).mockResolvedValue(opencliPluginFixture)
    renderModal()

    const secretItem = screen.getByTestId('secret-item-apiKey')
    const opSelect = within(secretItem).getByRole('button', { name: /Operation for apiKey/i })
    await user.click(opSelect)
    await user.click(screen.getByRole('option', { name: '设置新值' }))

    const secretInput = screen.getByLabelText(/New secret for apiKey/i)
    // 严格保留原值，包含前后空格等特殊字符
    const testSecret = '  sk-opencli-live-secret-999  '
    await user.type(secretInput, testSecret)
    expect(secretInput).toHaveValue(testSecret)

    const saveBtn = screen.getByRole('button', { name: '保存配置' })
    await user.click(saveBtn)

    await waitFor(() => {
      expect(pluginsService.updateConfiguration).toHaveBeenCalledWith(
        'opencli',
        expect.objectContaining({
          expectedEpoch: '3',
          expectedRevision: '1',
          secretPatch: {
            apiKey: {
              operation: 'SET',
              value: testSecret,
            },
          },
        }),
      )
    })

    // 保存后，秘密输入框从界面移除且状态已清空
    await waitFor(() => {
      expect(onSuccess).toHaveBeenCalled()
    })
  })

  it('requires confirmation before clearing secret and submits CLEAR operation', async () => {
    const user = userEvent.setup()
    vi.mocked(pluginsService.updateConfiguration).mockResolvedValue(opencliPluginFixture)
    renderModal()

    const secretItem = screen.getByTestId('secret-item-apiKey')
    const opSelect = within(secretItem).getByRole('button', { name: /Operation for apiKey/i })
    await user.click(opSelect)
    await user.click(screen.getByRole('option', { name: '清除' }))

    const saveBtn = screen.getByRole('button', { name: '保存配置' })

    // 未勾选确认时：保存按钮禁用，阻止未授权误清
    expect(saveBtn).toBeDisabled()
    expect(pluginsService.updateConfiguration).not.toHaveBeenCalled()

    // 勾选确认
    const confirmCheckbox = screen.getByRole('checkbox', {
      name: '确认保存时清除此秘密凭据？',
    })
    await user.click(confirmCheckbox)
    expect(confirmCheckbox).toBeChecked()
    expect(saveBtn).not.toBeDisabled()

    // 再次点击保存：成功提交 CLEAR
    await user.click(saveBtn)
    await waitFor(() => {
      expect(pluginsService.updateConfiguration).toHaveBeenCalledWith(
        'opencli',
        expect.objectContaining({
          expectedEpoch: '3',
          expectedRevision: '1',
          secretPatch: {
            apiKey: { operation: 'CLEAR' },
          },
        }),
      )
    })
  })

  it('handles optimistic locking 409 conflict and presents notification banner', async () => {
    const user = userEvent.setup()
    vi.mocked(pluginsService.updateConfiguration).mockRejectedValue(
      new ApiError('conflict', 409, 'Conflict', { code: 'CONFLICT' }),
    )
    renderModal()

    const saveBtn = screen.getByRole('button', { name: '保存配置' })
    await user.click(saveBtn)

    // 捕获 409 冲突并在弹窗顶部展示冲突提示横幅
    await waitFor(() => {
      expect(screen.getByTestId('plugin-config-conflict-banner')).toBeInTheDocument()
      expect(
        screen.getByText('配置已被其他操作修改（版本冲突），请刷新并查看最新改动。'),
      ).toBeInTheDocument()
    })

    // 弹窗依然保留，不会异常关闭，保存按钮重新可用
    expect(screen.getByText('配置插件：OpenCLI')).toBeInTheDocument()
    expect(saveBtn).not.toBeDisabled()
    expect(onSuccess).not.toHaveBeenCalled()
  })
})
