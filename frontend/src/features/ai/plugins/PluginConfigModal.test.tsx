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

const samplePlugin: PluginDTO = {
  pluginId: 'demo-plugin',
  name: 'Demo Plugin',
  version: '1.0.0',
  authKind: null,
  status: null,
  region: null,
  expiresAt: null,
  nextRefreshAt: null,
  lastRefreshedAt: null,
  lastRefreshError: null,
  configuration: {
    revision: '3',
    valuesJson:
      '{"enabled":true,"bigInt":9007199254740993123456789,"decimal":1.230000,"exp":1e1000,"mode":"FAST","siteName":"MySite","tags":["a","b"],"nested":{"host":"127.0.0.1"}}',
    valuesSchema: JSON.stringify({
      type: 'object',
      description: 'Demo config schema',
      properties: {
        enabled: { type: 'boolean', description: 'Enable switch' },
        bigInt: { type: 'integer', description: 'Large integer' },
        decimal: { type: 'number', description: 'Exact decimal' },
        exp: { type: 'number', description: 'Large exponent' },
        mode: {
          type: 'string',
          description: 'Running mode',
          enum: ['FAST', 'THOROUGH'],
        },
        siteName: { type: 'string', description: 'Site identifier' },
        tags: { type: 'array', items: { type: 'string' }, description: 'Tags list' },
        nested: {
          type: 'object',
          description: 'Nested networking',
          properties: {
            host: { type: 'string', description: 'Host address' },
          },
          required: ['host'],
          additionalProperties: false,
        },
      },
      required: ['enabled'],
      additionalProperties: false,
    }),
    defaultsJson: '{"enabled":true}',
    secrets: {
      apiKey: {
        description: 'Demo API Token',
        configured: true,
      },
    },
  },
  desiredEpoch: '5',
  appliedEpoch: '5',
  desiredRevision: '3',
  appliedRevision: '3',
  harnessToolNames: ['search'],
  canvasFunctionNames: [],
  applicationError: null,
}

describe('PluginConfigModal', () => {
  let queryClient: QueryClient
  const onClose = vi.fn()
  const onSuccess = vi.fn()

  beforeEach(() => {
    vi.resetAllMocks()
    queryClient = new QueryClient({
      defaultOptions: { mutations: { retry: false } },
    })
  })

  function renderModal(plugin: PluginDTO = samplePlugin) {
    return render(
      <QueryClientProvider client={queryClient}>
        <PluginConfigModal plugin={plugin} onClose={onClose} onSuccess={onSuccess} />
      </QueryClientProvider>,
    )
  }

  it('renders form schema correctly with preserved number tokens and secrets status', () => {
    renderModal()

    expect(screen.getByText('配置插件：Demo Plugin')).toBeInTheDocument()
    expect(screen.getByText('Rev 3')).toBeInTheDocument()

    // Checkbox for boolean
    const checkbox = screen.getByRole('checkbox', { name: /enabled/i })
    expect(checkbox).toBeChecked()

    // Exact number tokens rendered
    expect(screen.getByDisplayValue('9007199254740993123456789')).toBeInTheDocument()
    expect(screen.getByDisplayValue('1.230000')).toBeInTheDocument()
    expect(screen.getByDisplayValue('1e1000')).toBeInTheDocument()

    // Text inputs and nested objects
    expect(screen.getByDisplayValue('MySite')).toBeInTheDocument()
    expect(screen.getByDisplayValue('127.0.0.1')).toBeInTheDocument()

    // Secrets section
    const secretItem = screen.getByTestId('secret-item-apiKey')
    expect(within(secretItem).getByText('apiKey')).toBeInTheDocument()
    expect(within(secretItem).getByText('Demo API Token')).toBeInTheDocument()
    expect(within(secretItem).getByText('已配置')).toBeInTheDocument()
  })

  it('modifies text inputs and nested objects', async () => {
    const user = userEvent.setup()
    renderModal()

    const siteInput = screen.getByDisplayValue('MySite')
    await user.clear(siteInput)
    await user.type(siteInput, 'NewSite')

    const hostInput = screen.getByDisplayValue('127.0.0.1')
    await user.clear(hostInput)
    await user.type(hostInput, '192.168.1.1')

    const saveBtn = screen.getByRole('button', { name: '保存配置' })
    await user.click(saveBtn)

    await waitFor(() => {
      expect(pluginsService.updateConfiguration).toHaveBeenCalledWith(
        'demo-plugin',
        expect.objectContaining({
          valuesJson: expect.stringContaining('"siteName":"NewSite"'),
        }),
      )
    })
  })

  it('modifies boolean and preserves untouched number tokens verbatim on save', async () => {
    const user = userEvent.setup()
    vi.mocked(pluginsService.updateConfiguration).mockResolvedValue(samplePlugin)
    renderModal()

    const checkbox = screen.getByRole('checkbox', { name: /enabled/i })
    await user.click(checkbox)

    const saveBtn = screen.getByRole('button', { name: '保存配置' })
    await user.click(saveBtn)

    await waitFor(() => {
      expect(pluginsService.updateConfiguration).toHaveBeenCalledWith(
        'demo-plugin',
        expect.objectContaining({
          expectedEpoch: '5',
          expectedRevision: '3',
          secretPatch: {
            apiKey: { operation: 'KEEP' },
          },
        }),
      )
    })

    const callArgs = vi.mocked(pluginsService.updateConfiguration).mock.calls[0][1]
    expect(callArgs.valuesJson).toContain('9007199254740993123456789')
    expect(callArgs.valuesJson).toContain('1.230000')
    expect(callArgs.valuesJson).toContain('1e1000')
    expect(callArgs.valuesJson).toContain('"enabled":false')
    expect(onSuccess).toHaveBeenCalledTimes(1)
  })

  it('manages secret: SET password input and sends operation=SET with non-empty value', async () => {
    const user = userEvent.setup()
    vi.mocked(pluginsService.updateConfiguration).mockResolvedValue(samplePlugin)
    renderModal()

    const secretItem = screen.getByTestId('secret-item-apiKey')
    const opSelect = within(secretItem).getByRole('button', { name: /Operation for apiKey/i })
    await user.click(opSelect)
    await user.click(screen.getByRole('option', { name: '设置新值' }))

    const passwordInput = within(secretItem).getByLabelText('New secret for apiKey')
    expect(passwordInput).toHaveAttribute('type', 'password')
    expect(passwordInput).toHaveAttribute('autoComplete', 'off')

    await user.type(passwordInput, 'my-super-secret-password-123')

    const saveBtn = screen.getByRole('button', { name: '保存配置' })
    await user.click(saveBtn)

    await waitFor(() => {
      expect(pluginsService.updateConfiguration).toHaveBeenCalledWith(
        'demo-plugin',
        expect.objectContaining({
          secretPatch: {
            apiKey: { operation: 'SET', value: 'my-super-secret-password-123' },
          },
        }),
      )
    })
  })

  it('manages secret: CLEAR with explicit confirmation checkbox before save is enabled', async () => {
    const user = userEvent.setup()
    vi.mocked(pluginsService.updateConfiguration).mockResolvedValue(samplePlugin)
    renderModal()

    const secretItem = screen.getByTestId('secret-item-apiKey')
    const opSelect = within(secretItem).getByRole('button', { name: /Operation for apiKey/i })
    await user.click(opSelect)
    await user.click(screen.getByRole('option', { name: '清除' }))

    const saveBtn = screen.getByRole('button', { name: '保存配置' })
    expect(saveBtn).toBeDisabled()

    const confirmBox = within(secretItem).getByRole('checkbox', {
      name: /确认保存时清除此秘密凭据/i,
    })
    await user.click(confirmBox)
    expect(saveBtn).not.toBeDisabled()

    await user.click(saveBtn)

    await waitFor(() => {
      expect(pluginsService.updateConfiguration).toHaveBeenCalledWith(
        'demo-plugin',
        expect.objectContaining({
          secretPatch: {
            apiKey: { operation: 'CLEAR' },
          },
        }),
      )
    })
  })

  it('handles 409 Conflict: retains user draft, displays conflict warning, and forbids auto retry', async () => {
    const user = userEvent.setup()
    vi.mocked(pluginsService.updateConfiguration).mockRejectedValue(
      new ApiError('Conflict', 409, 'version_conflict'),
    )
    renderModal()

    const checkbox = screen.getByRole('checkbox', { name: /enabled/i })
    await user.click(checkbox)

    const saveBtn = screen.getByRole('button', { name: '保存配置' })
    await user.click(saveBtn)

    expect(await screen.findByTestId('plugin-config-conflict-banner')).toBeInTheDocument()
    expect(
      screen.getByText('配置已被其他操作修改（版本冲突），请刷新并查看最新改动。'),
    ).toBeInTheDocument()

    expect(checkbox).not.toBeChecked()
    expect(pluginsService.updateConfiguration).toHaveBeenCalledTimes(1)
  })

  it('supports switching between Form Editor and JSON Editor', async () => {
    const user = userEvent.setup()
    renderModal()

    const switchBtn = screen.getByRole('button', { name: /JSON 编辑/i })
    await user.click(switchBtn)

    const textarea = screen.getByRole('textbox', { name: /JSON 编辑/i })
    expect(textarea).toBeInTheDocument()

    // Switch back to Form Editor
    const formSwitchBtn = screen.getByRole('button', { name: /表单编辑/i })
    await user.click(formSwitchBtn)
    expect(screen.getByRole('checkbox', { name: /enabled/i })).toBeInTheDocument()
  })

  it('automatically falls back to JSON editor and warns when Schema has unsupported keywords', () => {
    const pluginWithUnsupportedSchema: PluginDTO = {
      ...samplePlugin,
      configuration: {
        ...samplePlugin.configuration,
        valuesSchema: JSON.stringify({
          type: 'object',
          $ref: '#/unsupported/ref',
          properties: {},
        }),
      },
    }

    renderModal(pluginWithUnsupportedSchema)
    expect(screen.getByRole('alert')).toHaveTextContent(
      'Schema 包含不支持的关键字：Unsupported schema keyword "$ref"',
    )
    expect(screen.getByRole('textbox', { name: /JSON 编辑/i })).toBeInTheDocument()
  })

  it('modifies enum select and number input in form mode', async () => {
    const user = userEvent.setup()
    vi.mocked(pluginsService.updateConfiguration).mockResolvedValue(samplePlugin)
    renderModal()

    // Modify enum
    const modeTrigger = screen.getByRole('button', { name: 'FAST' })
    await user.click(modeTrigger)
    await user.click(screen.getByRole('option', { name: 'THOROUGH' }))

    // Modify number input
    const bigIntInput = screen.getByDisplayValue('9007199254740993123456789')
    await user.clear(bigIntInput)
    await user.type(bigIntInput, '888888888888888888')

    const saveBtn = screen.getByRole('button', { name: '保存配置' })
    await user.click(saveBtn)

    await waitFor(() => {
      expect(pluginsService.updateConfiguration).toHaveBeenCalledWith(
        'demo-plugin',
        expect.objectContaining({
          valuesJson: expect.stringContaining('"mode":"THOROUGH"'),
        }),
      )
    })
    const callArgs = vi.mocked(pluginsService.updateConfiguration).mock.calls[0][1]
    expect(callArgs.valuesJson).toContain('888888888888888888')
  })

  it('clears in-memory secrets on unmount', () => {
    const { unmount } = renderModal()
    unmount()
  })
})
