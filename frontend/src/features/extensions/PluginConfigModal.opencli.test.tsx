import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { PluginConfigModal } from './PluginConfigModal'
import { pluginsService } from '@/shared/api/plugins-service'
import { ApiError } from '@/shared/api/client'
import { decodePluginDTO } from '@/shared/api/ai-plugin-codec'
import type { PluginDTO } from '@/shared/api/contracts/ai-plugin'

vi.mock('@/shared/api/plugins-service', () => ({
  pluginsService: { updateConfiguration: vi.fn() },
}))

const goldenEnvelope: unknown = JSON.parse(readFileSync(resolve(
  dirname(fileURLToPath(import.meta.url)),
  '../../../../web/src/test/resources/fun/fengwk/kkstudio/web/plugin/opencli-http.json',
), 'utf8'))
if (goldenEnvelope === null || typeof goldenEnvelope !== 'object' || !('data' in goldenEnvelope)) {
  throw new Error('OpenCLI HTTP golden must contain a data envelope')
}
const opencliPluginFixture = decodePluginDTO(goldenEnvelope.data)

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

  it('renders OpenCLI schema without leaking apiKey or instanceId into standard fields, protecting instanceId under secrets', () => {
    renderModal()

    expect(screen.getByText('配置插件：OpenCLI')).toBeInTheDocument()
    expect(screen.getByText(`Rev ${opencliPluginFixture.configuration.revision}`)).toBeInTheDocument()

    // 顶层 9 个标量字段渲染（以 baseUrl/connectTimeoutMillis/requestTimeoutMillis 为代表）
    expect(screen.getByRole('textbox', { name: 'baseUrl' })).toHaveValue('http://127.0.0.1:8080')
    expect(screen.getByRole('textbox', { name: 'connectTimeoutMillis' })).toHaveValue('5000')
    expect(screen.getByRole('textbox', { name: 'requestTimeoutMillis' })).toHaveValue('120000')

    // instanceId 与 apiKey 绝对不作为普通表单输入渲染，也不在 valuesJson 暴露
    expect(screen.queryByRole('textbox', { name: 'instanceId' })).not.toBeInTheDocument()
    expect(screen.queryByRole('textbox', { name: 'apiKey' })).not.toBeInTheDocument()

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

    // 秘密区域：apiKey（认证密钥）与 instanceId（受保护的私有路由身份）安全展示，不回显明文
    const secretItemApiKey = screen.getByTestId('secret-item-apiKey')
    expect(within(secretItemApiKey).getByText('apiKey')).toBeInTheDocument()
    expect(within(secretItemApiKey).getByText('OpenCLI API key')).toBeInTheDocument()
    expect(within(secretItemApiKey).getByText('已配置')).toBeInTheDocument()

    const secretItemInstanceId = screen.getByTestId('secret-item-instanceId')
    expect(within(secretItemInstanceId).getByText('instanceId')).toBeInTheDocument()
    expect(within(secretItemInstanceId).getByText(
      opencliPluginFixture.configuration.secrets.instanceId.description,
    )).toBeInTheDocument()
    expect(within(secretItemInstanceId).getByText('已配置')).toBeInTheDocument()
  })

  it('edits nested schema properties and saves with expected coordinates and KEEP secret operations', async () => {
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
          expectedRevision: opencliPluginFixture.configuration.revision,
          secretPatch: {
            apiKey: { operation: 'KEEP' },
            instanceId: { operation: 'KEEP' },
          },
        }),
      )
    })

    const callPayload = vi.mocked(pluginsService.updateConfiguration).mock.calls[0][1]
    const parsedValues = JSON.parse(callPayload.valuesJson)
    expect(parsedValues.baseUrl).toBe('https://hub.example.com')
    expect(parsedValues.gptImage2.paidEnabled).toBe(true)
    expect(parsedValues.seedance.retry).toBe(3)
    // 确保 instanceId 不进入 valuesJson
    expect(parsedValues).not.toHaveProperty('instanceId')
  })

  it('sets the authentication key and private identity without adding either to ordinary values', async () => {
    const user = userEvent.setup()
    vi.mocked(pluginsService.updateConfiguration).mockResolvedValue(opencliPluginFixture)
    renderModal()

    // 设置 apiKey
    const secretItemApiKey = screen.getByTestId('secret-item-apiKey')
    const opSelectApiKey = within(secretItemApiKey).getByRole('button', { name: /Operation for apiKey/i })
    await user.click(opSelectApiKey)
    await user.click(screen.getByRole('option', { name: '设置新值' }))

    const apiKeyInput = screen.getByLabelText(/New secret for apiKey/i)
    const testApiKey = 'synthetic-opencli-replacement'
    await user.type(apiKeyInput, testApiKey)
    expect(apiKeyInput).toHaveValue(testApiKey)

    // 设置受保护的私有 instanceId
    const secretItemInst = screen.getByTestId('secret-item-instanceId')
    const opSelectInst = within(secretItemInst).getByRole('button', { name: /Operation for instanceId/i })
    await user.click(opSelectInst)
    await user.click(screen.getByRole('option', { name: '设置新值' }))

    const instInput = screen.getByLabelText(/New secret for instanceId/i)
    const testInstanceId = 'inst-private-edge-42'
    await user.type(instInput, testInstanceId)
    expect(instInput).toHaveValue(testInstanceId)

    const saveBtn = screen.getByRole('button', { name: '保存配置' })
    await user.click(saveBtn)

    await waitFor(() => {
      expect(pluginsService.updateConfiguration).toHaveBeenCalledWith(
        'opencli',
        expect.objectContaining({
          expectedRevision: opencliPluginFixture.configuration.revision,
          secretPatch: {
            apiKey: {
              operation: 'SET',
              value: testApiKey,
            },
            instanceId: {
              operation: 'SET',
              value: testInstanceId,
            },
          },
        }),
      )
    })

    await waitFor(() => {
      expect(onSuccess).toHaveBeenCalled()
    })
    expect(apiKeyInput).toHaveValue('')
    expect(instInput).toHaveValue('')
    expect(vi.mocked(pluginsService.updateConfiguration).mock.calls[0][1].valuesJson)
      .not.toContain(testInstanceId)
    expect(vi.mocked(pluginsService.updateConfiguration).mock.calls[0][1].valuesJson)
      .not.toContain(testApiKey)
  })

  it('preserves padded secret input for server rejection instead of silently changing the value', async () => {
    const user = userEvent.setup()
    const message = 'SET value must not have surrounding whitespace'
    vi.mocked(pluginsService.updateConfiguration).mockRejectedValue(new ApiError(message, 400))
    renderModal()
    const secretItem = screen.getByTestId('secret-item-apiKey')
    await user.click(within(secretItem).getByRole('button', { name: /Operation for apiKey/i }))
    await user.click(screen.getByRole('option', { name: '设置新值' }))
    const input = screen.getByLabelText(/New secret for apiKey/i)
    const raw = '  synthetic-key-with-padding  '
    await user.type(input, raw)
    await user.click(screen.getByRole('button', { name: '保存配置' }))

    await waitFor(() => expect(screen.getByText(message)).toBeInTheDocument())
    expect(pluginsService.updateConfiguration).toHaveBeenCalledWith(
      'opencli',
      expect.objectContaining({
        secretPatch: {
          apiKey: { operation: 'SET', value: raw },
          instanceId: { operation: 'KEEP' },
        },
      }),
    )
    expect(onSuccess).not.toHaveBeenCalled()
    expect(onClose).not.toHaveBeenCalled()
    expect(input).toHaveValue('')
  })

  it('manages CLEAR operation independently on protected instanceId with confirmation safeguard', async () => {
    const user = userEvent.setup()
    vi.mocked(pluginsService.updateConfiguration).mockResolvedValue(opencliPluginFixture)
    renderModal()

    // 仅清除受保护的 instanceId，apiKey 维持 KEEP
    const secretItemInst = screen.getByTestId('secret-item-instanceId')
    const opSelectInst = within(secretItemInst).getByRole('button', { name: /Operation for instanceId/i })
    await user.click(opSelectInst)
    await user.click(screen.getByRole('option', { name: '清除' }))

    const saveBtn = screen.getByRole('button', { name: '保存配置' })

    // 未确认前：保存按钮禁用
    expect(saveBtn).toBeDisabled()
    expect(pluginsService.updateConfiguration).not.toHaveBeenCalled()

    // 勾选确认复选框
    const confirmCheckbox = within(secretItemInst).getByRole('checkbox', {
      name: '确认保存时清除此秘密凭据？',
    })
    await user.click(confirmCheckbox)
    expect(confirmCheckbox).toBeChecked()
    expect(saveBtn).not.toBeDisabled()

    // 提交保存：instanceId CLEAR，apiKey KEEP
    await user.click(saveBtn)
    await waitFor(() => {
      expect(pluginsService.updateConfiguration).toHaveBeenCalledWith(
        'opencli',
        expect.objectContaining({
          expectedRevision: opencliPluginFixture.configuration.revision,
          secretPatch: {
            apiKey: { operation: 'KEEP' },
            instanceId: { operation: 'CLEAR' },
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
