import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { PluginConfigModal } from './PluginConfigModal'
import { ApiError, request } from '@/shared/api/client'
import type {
  PluginConfigurationUpdateRequestDTO,
  PluginDTO,
} from '@/shared/api/generated/contracts'

vi.mock('@/shared/api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/shared/api/client')>()
  return { ...actual, request: vi.fn() }
})

const sampleSchema = {
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
}

/** wire 上的配置原文：大整数、带 scale 的小数与超范围指数都必须按原词法往返。 */
const sampleValuesJson =
  '{"enabled":true,"bigInt":9007199254740993123456789,"decimal":1.230000,"exp":1E+1000,"mode":"FAST","siteName":"MySite","tags":["a","b"],"nested":{"host":"127.0.0.1"}}'

const samplePlugin: PluginDTO = {
  pluginId: 'demo-plugin',
  name: 'Demo Plugin',
  pluginVersion: '1.0.0',
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
    version: '3',
    valuesJson: sampleValuesJson,
    valuesSchema: sampleSchema,
    defaults: { enabled: true },
    secrets: {
      apiKey: {
        description: 'Demo API Token',
        configured: true,
      },
    },
  },
  appliedVersion: '3',
  connectionCheckSupported: false,
  harnessToolNames: ['search'],
  canvasFunctionNames: [],
  applicationError: null,
}

/** 断言一次配置保存提交：路由、路径身份与请求体。 */
function expectSaved(body: Record<string, unknown>) {
  expect(request).toHaveBeenCalledWith(
    'PUT /api/platform/plugins/{pluginId}/configuration',
    expect.objectContaining({
      path: { pluginId: 'demo-plugin' },
      body: expect.objectContaining(body),
    }),
  )
}

/** 读取最近一次配置保存的请求体。 */
function savedPayload(call = 0): PluginConfigurationUpdateRequestDTO {
  const args = vi.mocked(request).mock.calls[call][1] as {
    body: PluginConfigurationUpdateRequestDTO
  }
  return args.body
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
    expect(screen.getByText('v3')).toBeInTheDocument()

    // Checkbox for boolean
    const checkbox = screen.getByRole('checkbox', { name: 'enabled' })
    expect(checkbox).toBeChecked()

    // 数字按原文 token 渲染，绝不经过 JS number
    expect(screen.getByRole('textbox', { name: 'bigInt' })).toHaveValue('9007199254740993123456789')
    expect(screen.getByRole('textbox', { name: 'decimal' })).toHaveValue('1.230000')
    expect(screen.getByRole('textbox', { name: 'exp' })).toHaveValue('1E+1000')

    // Text inputs and nested objects
    expect(screen.getByRole('textbox', { name: 'siteName' })).toHaveValue('MySite')
    expect(screen.getByRole('textbox', { name: 'host' })).toHaveValue('127.0.0.1')

    // Array container rendered from the same raw JSON text
    expect(
      (screen.getByRole('textbox', { name: 'tags' }) as HTMLTextAreaElement).value.replace(
        /\s+/g,
        '',
      ),
    ).toBe('["a","b"]')

    // Secrets section
    const secretItem = screen.getByTestId('secret-item-apiKey')
    expect(within(secretItem).getByText('apiKey')).toBeInTheDocument()
    expect(within(secretItem).getByText('Demo API Token')).toBeInTheDocument()
    expect(within(secretItem).getByText('已配置')).toBeInTheDocument()

    // 全局 mutationCache 保持为空
    expect(queryClient.getMutationCache().getAll()).toHaveLength(0)
  })

  it('round-trips every exact number token through the editor without JSON.parse', async () => {
    const user = userEvent.setup()
    vi.mocked(request).mockResolvedValue(samplePlugin)
    renderModal()

    await user.click(screen.getByRole('button', { name: '保存配置' }))

    await waitFor(() => {
      expect(savedPayload().valuesJson).toBe(sampleValuesJson)
    })
    expect(savedPayload().valuesJson).toContain('9007199254740993123456789')
    expect(savedPayload().valuesJson).toContain('1.230000')
    expect(savedPayload().valuesJson).toContain('1E+1000')
  })

  it('supports progressive user typing for negative numbers, decimals, and exponents without bouncing back', async () => {
    const user = userEvent.setup()
    vi.mocked(request).mockResolvedValue(samplePlugin)
    renderModal()

    const decInput = screen.getByRole('textbox', { name: 'decimal' })
    const saveBtn = screen.getByRole('button', { name: '保存配置' })

    // 清空并逐键输入负小数 "-0.54321"
    await user.clear(decInput)
    // 键入 "-"：中间态，展示格式错误，保存按钮应禁用
    await user.type(decInput, '-')
    expect(decInput).toHaveValue('-')
    expect(saveBtn).toBeDisabled()
    expect(screen.getByText('无效的数字格式。')).toBeInTheDocument()

    // 继续键入 "0."：仍是中间态
    await user.type(decInput, '0.')
    expect(decInput).toHaveValue('-0.')
    expect(saveBtn).toBeDisabled()

    // 键入完整合法数字 "54321"
    await user.type(decInput, '54321')
    expect(decInput).toHaveValue('-0.54321')
    expect(screen.queryByText('无效的数字格式。')).not.toBeInTheDocument()
    expect(saveBtn).not.toBeDisabled()

    await user.click(saveBtn)
    await waitFor(() => {
      expectSaved({ valuesJson: expect.stringContaining('"decimal":-0.54321') })
    })

    // 全局 mutationCache 保持为空
    expect(queryClient.getMutationCache().getAll()).toHaveLength(0)
  })

  it('strictly validates integer fields and rejects decimals', async () => {
    const user = userEvent.setup()
    renderModal()

    const bigIntInput = screen.getByRole('textbox', { name: 'bigInt' })
    const saveBtn = screen.getByRole('button', { name: '保存配置' })

    await user.clear(bigIntInput)
    await user.type(bigIntInput, '123.45') // 尝试输入小数到 integer 字段
    expect(bigIntInput).toHaveValue('123.45')
    expect(screen.getByText('无效的整数格式。')).toBeInTheDocument()
    expect(saveBtn).toBeDisabled()
    fireEvent.change(bigIntInput, { target: { value: '1e3' } })
    expect(bigIntInput).toHaveValue('1e3')
    expect(saveBtn).toBeDisabled()
  })

  it('does not invent the first enum option for an absent optional field', () => {
    renderModal({
      ...samplePlugin,
      configuration: {
        ...samplePlugin.configuration,
        valuesJson: sampleValuesJson.replace('"mode":"FAST",', ''),
      },
    })
    expect(screen.getByRole('button', { name: 'mode' })).not.toHaveTextContent('FAST')
  })

  it('offers the explicit unset entry for an optional enum and omits the key on save', async () => {
    const user = userEvent.setup()
    vi.mocked(request).mockResolvedValue(samplePlugin)
    renderModal()

    // 已选择的 optional enum 必须能显式省略，而不是只能改成另一个合法选项。
    const unset = screen.getByRole('button', { name: '未设置 mode' })
    await user.click(unset)
    expect(screen.getByRole('button', { name: 'mode' })).toHaveAttribute('data-value', '')
    // 省略后字段不存在，省略入口随之消失。
    expect(screen.queryByRole('button', { name: '未设置 mode' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '保存配置' }))
    const values = JSON.parse(savedPayload().valuesJson) as Record<string, unknown>
    expect(Object.hasOwn(values, 'mode')).toBe(false)
  })

  it('keeps a required enum without an unset entry', () => {
    renderModal({
      ...samplePlugin,
      configuration: {
        ...samplePlugin.configuration,
        valuesSchema: { ...sampleSchema, required: ['enabled', 'mode'] },
      },
    })
    expect(screen.getByRole('button', { name: 'mode' })).toHaveTextContent('FAST')
    expect(screen.queryByRole('button', { name: '未设置 mode' })).not.toBeInTheDocument()
    // 必填的其它标量同样不提供省略入口。
    expect(screen.queryByRole('button', { name: '未设置 enabled' })).not.toBeInTheDocument()
  })

  it('edits complex array container with raw JSON preserving big numbers', async () => {
    const user = userEvent.setup()
    vi.mocked(request).mockResolvedValue(samplePlugin)
    renderModal({
      ...samplePlugin,
      configuration: {
        ...samplePlugin.configuration,
        valuesJson: sampleValuesJson.replace('["a","b"]', '[10,20]'),
        valuesSchema: {
          type: 'object',
          properties: { tags: { type: 'array', items: { type: 'number' } } },
          required: [],
          additionalProperties: true,
        },
      },
    })

    const tagsInput = screen.getByRole('textbox', { name: 'tags' })
    await user.clear(tagsInput)
    // 写入包含整数与小数的数组（[ 在 userEvent.type 中需转义为 [[）
    await user.type(tagsInput, '[[10,20.5]')

    const saveBtn = screen.getByRole('button', { name: '保存配置' })
    await user.click(saveBtn)

    await waitFor(() => {
      expectSaved({ valuesJson: expect.stringContaining('[10,20.5]') })
    })
  })

  it('hides SET and CLEAR operations for DEEP_LINK plugin secrets', () => {
    const deepLinkPlugin: PluginDTO = {
      ...samplePlugin,
      authKind: { type: 'DEEP_LINK', regionCandidates: ['CN'] },
    }
    renderModal(deepLinkPlugin)

    const secretItem = screen.getByTestId('secret-item-apiKey')
    expect(within(secretItem).queryByRole('button', { name: /Operation for apiKey/i })).not.toBeInTheDocument()
    expect(
      within(secretItem).getByText('此插件通过官方登录授权管理凭据。'),
    ).toBeInTheDocument()
  })

  it('retains SET secret without trimming or padding and verifies non-empty', async () => {
    const user = userEvent.setup()
    vi.mocked(request).mockResolvedValue(samplePlugin)
    renderModal()

    const secretItem = screen.getByTestId('secret-item-apiKey')
    const opSelect = within(secretItem).getByRole('button', { name: /Operation for apiKey/i })
    await user.click(opSelect)
    await user.click(screen.getByRole('option', { name: '设置新值' }))

    const passwordInput = within(secretItem).getByLabelText('New secret for apiKey')
    // 输入带空格的密码，验证绝不被 trim
    await user.type(passwordInput, '  secret-with-spaces  ')

    const saveBtn = screen.getByRole('button', { name: '保存配置' })
    await user.click(saveBtn)

    await waitFor(() => {
      expectSaved({
        secretPatch: {
          apiKey: { operation: 'SET', value: '  secret-with-spaces  ' },
        },
      })
    })
  })

  it('strictly validates CLEAR confirmation on submit', async () => {
    const user = userEvent.setup()
    vi.mocked(request).mockResolvedValue(samplePlugin)
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
      expectSaved({
        secretPatch: {
          apiKey: { operation: 'CLEAR' },
        },
      })
    })
  })

  it('handles 409 Conflict: retains user draft, displays conflict warning, and forbids auto retry', async () => {
    const user = userEvent.setup()
    vi.mocked(request).mockRejectedValue(
      new ApiError('Conflict', 409, 'version_conflict'),
    )
    renderModal()

    const checkbox = screen.getByRole('checkbox', { name: 'enabled' })
    await user.click(checkbox)

    const saveBtn = screen.getByRole('button', { name: '保存配置' })
    await user.click(saveBtn)

    expect(await screen.findByTestId('plugin-config-conflict-banner')).toBeInTheDocument()
    expect(
      screen.getByText('配置已被其他操作修改，发生版本冲突。请刷新并查看最新改动。'),
    ).toBeInTheDocument()

    expect(checkbox).not.toBeChecked()
    expect(request).toHaveBeenCalledTimes(1)
  })

  it('switches to JSON editor mode, edits raw JSON, and displays syntax error on invalid JSON', async () => {
    const user = userEvent.setup()
    renderModal()

    const switchBtn = screen.getByRole('button', { name: /JSON 编辑/i })
    await user.click(switchBtn)

    const textarea = screen.getByRole('textbox', { name: /JSON 编辑/i })
    expect(textarea).toBeInTheDocument()

    // 键入语法错误的 JSON
    await user.clear(textarea)
    await user.type(textarea, '{{"bad":')
    expect(screen.getByRole('alert')).toHaveTextContent(/JSON 格式无效/i)
    expect(screen.getByRole('button', { name: '保存配置' })).toBeDisabled()

    // 切换回表单模式
    const formSwitchBtn = screen.getByRole('button', { name: /表单编辑/i })
    await user.click(formSwitchBtn)
  })

  it('modifies string input and enum select in form mode', async () => {
    const user = userEvent.setup()
    vi.mocked(request).mockResolvedValue(samplePlugin)
    renderModal()

    // 1. 修改 string 输入框
    const siteInput = screen.getByRole('textbox', { name: 'siteName' })
    await user.clear(siteInput)
    await user.type(siteInput, 'AwesomeSite')

    // 2. 修改 enum 下拉框
    const modeSelect = screen.getByRole('button', { name: 'mode' })
    await user.click(modeSelect)
    await user.click(screen.getByRole('option', { name: 'THOROUGH' }))

    const saveBtn = screen.getByRole('button', { name: '保存配置' })
    await user.click(saveBtn)

    await waitFor(() => {
      expectSaved({ valuesJson: expect.stringContaining('"siteName":"AwesomeSite"') })
    })
  })

  it('edits dynamic object container with raw snippet and catches syntax errors', async () => {
    const user = userEvent.setup()
    const pluginWithOpenObject: PluginDTO = {
      ...samplePlugin,
      configuration: {
        ...samplePlugin.configuration,
        valuesSchema: {
          type: 'object',
          properties: {
            customObj: {
              type: 'object',
              properties: {},
              required: [],
              additionalProperties: true,
              description: 'Open object',
            },
          },
          required: [],
          additionalProperties: false,
        },
        valuesJson: '{"customObj":{"big":9007199254740993123456789}}',
      },
    }
    renderModal(pluginWithOpenObject)

    const objArea = screen.getByRole('textbox', { name: 'customObj' })
    expect((objArea as HTMLTextAreaElement).value.replace(/\s+/g, '')).toBe(
      '{"big":9007199254740993123456789}',
    )

    // 输入非法 JSON
    await user.clear(objArea)
    await user.type(objArea, 'invalid')
    expect(screen.getByRole('alert')).toHaveTextContent(/无效的 JSON 容器格式/i)
    expect(screen.getByRole('button', { name: '保存配置' })).toBeDisabled()

    // 输入合法 JSON
    await user.clear(objArea)
    await user.type(objArea, '{{"newVal":1e1000}')
    expect(screen.queryByText(/无效的 JSON 容器格式/i)).not.toBeInTheDocument()
  })

  it('automatically falls back to JSON editor and warns when Schema has unsupported keywords', () => {
    const pluginWithUnsupportedSchema: PluginDTO = {
      ...samplePlugin,
      configuration: {
        ...samplePlugin.configuration,
        valuesSchema: {
          type: 'object',
          $ref: '#/unsupported/ref',
          properties: {},
        },
      },
    }

    renderModal(pluginWithUnsupportedSchema)
    expect(screen.getByRole('alert')).toHaveTextContent(
      'Schema 包含不支持的关键字：Unsupported schema keyword "$ref"',
    )
    expect(screen.getByRole('textbox', { name: /JSON 编辑/i })).toBeInTheDocument()
  })

  it('requires non-empty value when setting secret', async () => {
    const user = userEvent.setup()
    renderModal()

    const secretItem = screen.getByTestId('secret-item-apiKey')
    const opSelect = within(secretItem).getByRole('button', { name: /Operation for apiKey/i })
    await user.click(opSelect)
    await user.click(screen.getByRole('option', { name: '设置新值' }))

    const passwordInput = within(secretItem).getByLabelText('New secret for apiKey')
    // 输入字符然后清空成空字符串
    await user.type(passwordInput, 'a')
    await user.clear(passwordInput)

    // 点击保存
    const saveBtn = screen.getByRole('button', { name: '保存配置' })
    await user.click(saveBtn)

    expect(screen.getByRole('alert')).toHaveTextContent('秘密凭据 “apiKey” 需要非空值。')
    expect(request).not.toHaveBeenCalled()
  })

  it('handles general API errors and closes via cancel button', async () => {
    const user = userEvent.setup()
    vi.mocked(request).mockRejectedValue(new Error('Network error'))
    renderModal()

    const saveBtn = screen.getByRole('button', { name: '保存配置' })
    await user.click(saveBtn)

    expect(await screen.findByRole('alert')).toHaveTextContent('Network error')

    // 点击取消关闭
    const cancelBtn = screen.getByRole('button', { name: '取消' })
    await user.click(cancelBtn)
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('saves successfully in JSON editor mode', async () => {
    const user = userEvent.setup()
    vi.mocked(request).mockResolvedValue(samplePlugin)
    renderModal()

    // 切换到 JSON 模式
    await user.click(screen.getByRole('button', { name: /JSON 编辑/i }))
    const textarea = screen.getByRole('textbox', { name: /JSON 编辑/i })
    await user.clear(textarea)
    await user.type(textarea, '{{"valid":true}')

    const saveBtn = screen.getByRole('button', { name: '保存配置' })
    await user.click(saveBtn)

    await waitFor(() => {
      expectSaved({ valuesJson: '{"valid":true}' })
    })
    expect(onSuccess).toHaveBeenCalledTimes(1)
  })

  it('clears in-memory secrets on unmount and keeps global mutationCache empty', () => {
    const { unmount } = renderModal()
    unmount()
    expect(queryClient.getMutationCache().getAll()).toHaveLength(0)
  })

  it('rejects wrong array roots and neighboring-field injection without updating the stored JSON', async () => {
    const user = userEvent.setup()
    renderModal()
    const tags = screen.getByRole('textbox', { name: 'tags' })
    for (const value of ['123', '{}', '[] ,"injected":1']) {
      fireEvent.change(tags, { target: { value } })
      expect(screen.getByRole('button', { name: '保存配置' })).toBeDisabled()
      expect(screen.getByRole('alert')).toHaveTextContent('无效的 JSON 容器格式')
    }
    // 草稿错误未解决时不允许离开表单模式：否则用户会以为提交的是刚输入的内容，实际看到的是上次的合法值。
    const jsonMode = screen.getByRole('button', { name: /JSON 编辑/ })
    expect(jsonMode).toBeDisabled()
    await user.click(jsonMode)
    expect(screen.queryByRole('textbox', { name: /JSON 编辑/ })).not.toBeInTheDocument()
    expect(screen.getByText('请先修复或显式丢弃表单中的草稿错误，再切换编辑器')).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('无效的 JSON 容器格式')
    expect(request).not.toHaveBeenCalled()
  })

  it('keeps invalid field drafts unsavable and switches editors only after the user resolves them', async () => {
    const user = userEvent.setup()
    vi.mocked(request).mockResolvedValue(samplePlugin)
    renderModal()
    fireEvent.change(screen.getByRole('textbox', { name: 'decimal' }), { target: { value: '-' } })
    expect(screen.getByRole('button', { name: '保存配置' })).toBeDisabled()

    // 错误草稿只能被用户显式修复或丢弃，切换到全局 JSON 不会把它悄悄换成上次的合法值。
    const jsonMode = screen.getByRole('button', { name: /JSON 编辑/ })
    await user.click(jsonMode)
    expect(screen.queryByRole('textbox', { name: /JSON 编辑/ })).not.toBeInTheDocument()
    expect(request).not.toHaveBeenCalled()

    // 显式修复后错误消失，模式切换与保存同时恢复，提交的正是用户刚输入的值。
    fireEvent.change(screen.getByRole('textbox', { name: 'decimal' }), {
      target: { value: '-2.500000' },
    })
    expect(screen.getByRole('button', { name: '保存配置' })).not.toBeDisabled()
    await user.click(screen.getByRole('button', { name: /JSON 编辑/ }))
    await user.click(screen.getByRole('button', { name: /表单编辑/ }))
    expect(screen.getByRole('textbox', { name: 'decimal' })).toHaveValue('-2.500000')
    await user.click(screen.getByRole('button', { name: '保存配置' }))
    expectSaved({ valuesJson: expect.stringContaining('"decimal":-2.500000') })
  })

  it('separates dotted property paths from nested paths and treats constructor as an own field', () => {
    renderModal({
      ...samplePlugin,
      configuration: {
        ...samplePlugin.configuration,
        valuesJson: '{"a.b":10,"a":{"b":20},"constructor":30}',
        valuesSchema: {
          type: 'object',
          properties: {
            'a.b': { type: 'integer' },
            a: {
              type: 'object',
              properties: { b: { type: 'integer' } },
              required: [],
              additionalProperties: false,
            },
            constructor: { type: 'integer' },
          },
          required: [],
          additionalProperties: false,
        },
      },
    })
    const dotted = screen.getByRole('textbox', { name: 'a.b' })
    const nested = screen.getByRole('textbox', { name: /^b$/ })
    expect(screen.getByRole('textbox', { name: 'constructor' })).toHaveValue('30')
    fireEvent.change(dotted, { target: { value: '-' } })
    fireEvent.change(nested, { target: { value: '-2' } })
    expect(dotted).toHaveValue('-')
    expect(nested).toHaveValue('-2')
    expect(screen.getByRole('button', { name: '保存配置' })).toBeDisabled()
    fireEvent.change(dotted, { target: { value: '-1' } })
    expect(screen.getByRole('button', { name: '保存配置' })).not.toBeDisabled()
  })

  it('does not inherit secret values or CLEAR confirmation for a constructor secret', async () => {
    const user = userEvent.setup()
    renderModal({
      ...samplePlugin,
      authKind: { type: 'STATIC_KEY', secretField: 'constructor' },
      configuration: {
        ...samplePlugin.configuration,
        secrets: Object.fromEntries([['constructor', { description: 'Own secret', configured: true }]]),
      },
    })
    const item = screen.getByTestId('secret-item-constructor')
    await user.click(within(item).getByRole('button', { name: 'Operation for constructor' }))
    await user.click(screen.getByRole('option', { name: '清除' }))
    expect(within(item).getByRole('checkbox')).not.toBeChecked()
    expect(screen.getByRole('button', { name: '保存配置' })).toBeDisabled()
    await user.click(within(item).getByRole('button', { name: 'Operation for constructor' }))
    await user.click(screen.getByRole('option', { name: '设置新值' }))
    expect(within(item).getByLabelText('New secret for constructor')).toHaveValue('')
  })

  it('clears SET text before pending or failed requests and cannot revive it by changing operations', async () => {
    const user = userEvent.setup()
    let rejectRequest: (error: Error) => void = () => { throw new Error('request not pending') }
    vi.mocked(request).mockReturnValue(
      new Promise<PluginDTO>((_, reject) => { rejectRequest = reject }),
    )
    renderModal()
    const item = screen.getByTestId('secret-item-apiKey')
    const operation = within(item).getByRole('button', { name: 'Operation for apiKey' })
    await user.click(operation)
    await user.click(screen.getByRole('option', { name: '设置新值' }))
    const input = within(item).getByLabelText('New secret for apiKey')
    await user.type(input, 'temporary-local-secret')
    await user.click(screen.getByRole('button', { name: '保存配置' }))
    expect(input).toHaveValue('')
    expect(queryClient.getMutationCache().getAll()).toHaveLength(0)
    rejectRequest(new ApiError('Conflict', 409, 'version_conflict'))
    expect(await screen.findByTestId('plugin-config-conflict-banner')).toBeInTheDocument()
    expect(input).toHaveValue('')
    expect(request).toHaveBeenCalledTimes(1)
    await user.type(input, 'second-local-secret')
    await user.click(operation)
    await user.click(screen.getByRole('option', { name: '保持不变' }))
    await user.click(operation)
    await user.click(screen.getByRole('option', { name: '设置新值' }))
    expect(within(item).getByLabelText('New secret for apiKey')).toHaveValue('')
  })
})
