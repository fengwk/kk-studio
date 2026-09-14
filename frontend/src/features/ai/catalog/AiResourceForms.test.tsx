import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import type { AgentDraft, ModelDraft, ProviderDraft } from '@/features/ai/catalog/ai-console-types'
import { emptyModelDraft } from '@/features/ai/catalog/ai-model-draft-codec'
import { AgentForm, ModelForm, ProviderForm } from '@/features/ai/catalog/AiResourceForms'
import { chooseSelectOption } from '@/test-support/chooseSelectOption'

async function selectFormOption(
  user: ReturnType<typeof userEvent.setup>,
  label: string,
  option: string,
) {
  await chooseSelectOption(user, label, option)
}

describe('AiResourceForms', () => {
  it('edits provider fields with structured inputs', async () => {
    const user = userEvent.setup()
    render(<ProviderFormHarness />)

    await user.type(screen.getByPlaceholderText('minimax'), 'provider-a')
    await user.type(screen.getByPlaceholderText('用途说明'), 'provider desc')
    await selectFormOption(user, 'Provider Type', 'anthropic')
    await user.type(
      screen.getByPlaceholderText('https://api.example.com/v1'),
      'https://proxy.example/v1',
    )
    await user.type(screen.getByPlaceholderText('可留空'), 'secret')
    const totalTimeoutInput = screen.getByPlaceholderText('1800000')
    await user.clear(totalTimeoutInput)
    await user.type(totalTimeoutInput, '240000')
    const idleTimeoutInput = screen.getByPlaceholderText('120000')
    await user.clear(idleTimeoutInput)
    await user.type(idleTimeoutInput, '3000')

    expect(screen.getByDisplayValue('provider-a')).toBeInTheDocument()
    expect(screen.getByDisplayValue('provider desc')).toBeInTheDocument()
    expect(screen.getByLabelText('Provider Type')).toHaveAttribute('data-value', 'anthropic')
    expect(screen.getByDisplayValue('https://proxy.example/v1')).toBeInTheDocument()
    expect(screen.getByDisplayValue('secret')).toBeInTheDocument()
    expect(screen.getByDisplayValue('240000')).toBeInTheDocument()
    expect(screen.getByDisplayValue('3000')).toBeInTheDocument()
    expect(screen.getByText('留空会以无 Authorization 方式请求 OpenAI-compatible 端点。')).toBeInTheDocument()
  })

  it('explains that an empty edit credential preserves the existing secret without echoing it', () => {
    const { container } = render(<ProviderFormHarness mode="edit" />)

    expect(screen.getByText('留空会保留已配置的 API Key；密钥不会回显。')).toBeInTheDocument()
    expect(screen.getByPlaceholderText('留空保留当前密钥')).toHaveValue('')
    const labels = Array.from(container.querySelectorAll('label > span')).map((label) => label.textContent)
    expect(labels[0]).toMatch(/^Name(?: \*)?$/)
    expect(labels[1]).toBe('API Key（可选）')
  })

  it('edits the new model abilities, pricing, variants, and wire modelId', async () => {
    const user = userEvent.setup()
    render(<ModelFormHarness />)

    expect(screen.getByRole('heading', { name: 'Limit' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '功能' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Pricing' })).toBeInTheDocument()
    expect(screen.getByLabelText('Tools')).toBeChecked()
    expect(screen.getByLabelText('Reasoning')).toBeChecked()
    expect(screen.getByLabelText('TEXT')).toBeChecked()
    expect(screen.queryByText('Capabilities')).not.toBeInTheDocument()
    expect(screen.getByText(/币种固定 USD/)).toBeInTheDocument()
    expect(screen.getByLabelText('Reasoning Effort 1')).toBeInTheDocument()
    expect(screen.getByText('思考强度')).toBeInTheDocument()
    // 空值即协议默认：思考强度不强必填，默认空
    expect(screen.getByLabelText('Reasoning Effort 1')).toHaveValue('')
    expect(screen.getByLabelText('Reasoning Effort 1')).not.toBeRequired()

    await user.type(screen.getByLabelText('Model ID'), 'upstream-claude-3')
    expect(screen.getByDisplayValue('upstream-claude-3')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '添加 Variant' }))
    const secondNameInput = screen.getByLabelText('Variant ID 2')
    expect(secondNameInput).toHaveValue('variant-2')
    await user.clear(secondNameInput)
    await user.type(secondNameInput, 'creative')
    await user.selectOptions(screen.getByLabelText('Reasoning Effort 2'), 'high')
    expect(screen.getByLabelText('Reasoning Effort 2')).toHaveValue('high')
    expect(screen.getByDisplayValue('creative')).toBeInTheDocument()

    await selectFormOption(user, 'Default Variant', 'creative')
    expect(screen.getByLabelText('Default Variant')).toHaveAttribute('data-value', 'creative')
  })

  it.each(['deleted-provider', 'off-page-provider'])(
    'shows an edit model provider identity as unavailable for %s',
    (providerName) => {
      const draft: ModelDraft = {
        ...emptyModelDraft({ name: providerName }),
        providerName,
        name: 'orphaned-model',
      }
      render(
        <ModelForm
          draft={draft}
          mode="edit"
          providers={[
            {
              name: 'loaded-provider',
              description: null,
              providerType: 'openai',
              baseUrl: null,
              configured: true,
              modelCallTimeoutMillis: 1800000,
              modelCallIdleTimeoutMillis: 120000,
              version: '1',
              createTime: null,
              updateTime: null,
            },
          ]}
          onChange={() => undefined}
        />,
      )

      const providerSelect = screen.getByLabelText('Provider')
      expect(providerSelect).toHaveAttribute('data-value', providerName)
      expect(providerSelect).toBeDisabled()
      expect(providerSelect).toHaveAttribute('aria-describedby', 'model-provider-identity-status')
      expect(providerSelect).toHaveTextContent(`${providerName} (不可用)`)
      expect(screen.getByRole('status')).toHaveTextContent('不可用；保存其他字段时仍保留原始身份。')
    },
  )

  it('keeps default variant aligned and hides reasoning effort when reasoning is disabled', async () => {
    const user = userEvent.setup()
    render(<ModelFormHarness />)

    await user.click(screen.getByRole('button', { name: '添加 Variant' }))
    const secondNameInput = screen.getByLabelText('Variant ID 2')
    await user.clear(secondNameInput)
    await user.type(secondNameInput, 'creative')
    await selectFormOption(user, 'Default Variant', 'creative')

    fireEvent.change(secondNameInput, { target: { value: 'creative-2' } })
    expect(screen.getByLabelText('Default Variant')).toHaveAttribute('data-value', 'creative-2')

    await user.click(screen.getAllByRole('button', { name: '删除 Variant' })[1])
    expect(screen.getByLabelText('Default Variant')).toHaveAttribute('data-value', 'medium')

    await user.click(screen.getByLabelText('Reasoning'))
    expect(screen.queryByLabelText('Reasoning Effort 1')).not.toBeInTheDocument()
    expect(screen.getByText(/请先勾选上方 Reasoning/)).toBeInTheDocument()
  })

  it('keeps at least one input modality and toggles IMAGE alongside TEXT', async () => {
    const user = userEvent.setup()
    render(<ModelFormHarness />)

    expect(screen.getByLabelText('IMAGE')).not.toBeChecked()
    await user.click(screen.getByLabelText('IMAGE'))
    expect(screen.getByLabelText('IMAGE')).toBeChecked()
    expect(screen.getByLabelText('TEXT')).toBeChecked()

    // 取消 TEXT 后 IMAGE 仍保留，允许非 TEXT 的模态组合
    await user.click(screen.getByLabelText('TEXT'))
    expect(screen.getByLabelText('TEXT')).not.toBeChecked()
    expect(screen.getByLabelText('IMAGE')).toBeChecked()

    // 全部取消时回退到 TEXT，保证至少保留一种模态
    await user.click(screen.getByLabelText('IMAGE'))
    expect(screen.getByLabelText('TEXT')).toBeChecked()
    expect(screen.getByLabelText('IMAGE')).not.toBeChecked()
  })

  /** 空思考强度表示不覆盖协议默认：开启/重新开启 Reasoning 都不得自动补全。 */
  it('keeps an empty reasoning effort as the protocol default and restores effort inputs on re-enable', async () => {
    const user = userEvent.setup()
    render(<ModelFormHarness />)

    // 新增 variant 的思考强度默认为空（协议默认），且提示文案说明空值语义
    await user.click(screen.getByRole('button', { name: '添加 Variant' }))
    expect(screen.getByLabelText('Reasoning Effort 2')).toHaveValue('')
    expect(screen.getByText(/留空表示不覆盖 Provider 协议默认/)).toBeInTheDocument()

    await user.click(screen.getByLabelText('Reasoning'))
    expect(screen.queryByLabelText('Reasoning Effort 1')).not.toBeInTheDocument()
    expect(screen.getByText(/请先勾选上方 Reasoning/)).toBeInTheDocument()

    // 重新开启后仍然是空值，不得被 variant id / medium 补全
    await user.click(screen.getByLabelText('Reasoning'))
    expect(screen.getByLabelText('Reasoning Effort 1')).toHaveValue('')
    expect(screen.getByLabelText('Reasoning Effort 2')).toHaveValue('')
  })

  it('sanitizes integer limits and decimal pricing while editing', async () => {
    const user = userEvent.setup()
    render(<ModelFormHarness />)

    const contextInput = screen.getByPlaceholderText('128000')
    await user.clear(contextInput)
    await user.type(contextInput, '12a3')
    expect(contextInput).toHaveValue(123)

    const maxOutputInput = screen.getByPlaceholderText('8192')
    await user.clear(maxOutputInput)
    await user.type(maxOutputInput, '7.5')
    expect(maxOutputInput).toHaveValue(75)

    const inputPrice = screen.getByLabelText('Input USD per million tokens')
    await user.clear(inputPrice)
    await user.type(inputPrice, '1.2.3')
    expect(inputPrice).toHaveValue(1.23)

    const outputPrice = screen.getByLabelText('Output USD per million tokens')
    await user.clear(outputPrice)
    await user.type(outputPrice, '4.5.6')
    expect(outputPrice).toHaveValue(4.56)
  })

  it('keeps the default variant unchanged when renaming a non-default variant', async () => {
    const user = userEvent.setup()
    render(<ModelFormHarness />)

    await user.click(screen.getByRole('button', { name: '添加 Variant' }))
    const secondNameInput = screen.getByLabelText('Variant ID 2')
    await user.clear(secondNameInput)
    await user.type(secondNameInput, 'creative')
    expect(screen.getByLabelText('Default Variant')).toHaveAttribute('data-value', 'medium')

    await user.clear(secondNameInput)
    await user.type(secondNameInput, 'creative-2')
    expect(screen.getByLabelText('Default Variant')).toHaveAttribute('data-value', 'medium')
  })

  it('renders every model field error kind inline', () => {
    render(
      <ModelForm
        draft={{
          ...emptyModelDraft(),
          providerName: 'minimax',
          // defaultVariant 不在 variants 中时 Select 回退到第一个 option
          defaultVariant: 'stale',
          reasoning: true,
        }}
        mode="create"
        providers={[
          {
            name: 'minimax',
            description: null,
            providerType: 'openai',
            baseUrl: null,
            configured: true,
            modelCallTimeoutMillis: 1800000,
            modelCallIdleTimeoutMillis: 120000,
            version: '1',
            createTime: null,
            updateTime: null,
          },
        ]}
        fieldErrors={{
          providerName: '请选择 Provider',
          name: '请填写 Model 名称',
          contextWindow: '上下文窗口无效',
          maxOutputTokens: '最大输出长度无效',
          inputModalities: '请至少选择一种输入类型',
          pricing: '价格必须为非负数',
          defaultVariant: '请选择默认 Variant',
          variants: 'Variant ID 不能重复',
        }}
        onChange={() => undefined}
      />,
    )

    expect(screen.getByText('请选择 Provider')).toBeInTheDocument()
    expect(screen.getByText('请填写 Model 名称')).toBeInTheDocument()
    expect(screen.getByText('上下文窗口无效')).toBeInTheDocument()
    expect(screen.getByText('最大输出长度无效')).toBeInTheDocument()
    expect(screen.getByText('请至少选择一种输入类型')).toBeInTheDocument()
    expect(screen.getByText('价格必须为非负数')).toBeInTheDocument()
    expect(screen.getByText('请选择默认 Variant')).toBeInTheDocument()
    expect(screen.getByText('Variant ID 不能重复')).toBeInTheDocument()
  })

  it('allows editing model name in edit mode while keeping provider disabled', async () => {
    const user = userEvent.setup()
    render(<EditModelFormHarness />)

    const providerSelect = screen.getByLabelText('Provider')
    expect(providerSelect).toBeDisabled()

    const nameInput = screen.getByPlaceholderText('MiniMax-M2.7')
    expect(nameInput).not.toHaveAttribute('readonly')

    await user.clear(nameInput)
    await user.type(nameInput, 'MiniMax-M2.8')

    expect(nameInput).toHaveValue('MiniMax-M2.8')
  })

  it('edits agent model binding and tools without json editing', async () => {
    const user = userEvent.setup()
    render(<AgentFormHarness />)

    expect(screen.getByRole('button', { name: '使用第一个 Model 填充默认配置' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '使用第一个 Model 填充默认配置' }))
    expect(screen.getByLabelText('Default Model')).toHaveAttribute('data-value', 'minimax/MiniMax-M2.7')
    expect(screen.getByLabelText('Default Variant Override')).toHaveAttribute('data-value', '')

    await selectFormOption(user, 'Default Model', 'anthropic/Claude-Sonnet-4.5')
    expect(screen.getByLabelText('Default Variant Override')).toHaveAttribute('data-value', '')
    expect(screen.getByText('暂无候选 Tools')).toBeInTheDocument()
  })

  it('shows the model prerequisite hint when no model is available', () => {
    render(
      <AgentForm
        draft={{
          name: '',
          description: '',
          systemPrompt: '',
          model: '',
          variant: 'default',
          environmentId: '',
          toolIds: [],
          skills: [],
          subagents: [],
        }}
        models={[]}
        onChange={() => undefined}
      />,
    )

    expect(screen.getByText('需要先创建 Model 才能配置 Agent。')).toBeInTheDocument()
  })
})

function ProviderFormHarness({ mode = 'create' }: { mode?: 'create' | 'edit' }) {
  const [draft, setDraft] = useState<ProviderDraft>({
    name: '',
    description: '',
    providerType: 'openai',
    baseUrl: '',
    credential: '',
    modelCallTimeoutMillis: '1800000',
    modelCallIdleTimeoutMillis: '120000',
  })
  return <ProviderForm draft={draft} mode={mode} onChange={setDraft} />
}

function ModelFormHarness() {
  const [draft, setDraft] = useState<ModelDraft>({
    ...emptyModelDraft(),
    providerName: 'minimax',
    reasoning: true,
  })
  return (
    <ModelForm
      draft={draft}
      mode="create"
      providers={[
        {
          name: 'minimax',
          description: null,
          providerType: 'openai',
          baseUrl: null,
          configured: true,
          modelCallTimeoutMillis: 1800000,
          modelCallIdleTimeoutMillis: 120000,
          createTime: '2026-06-20T02:00:00',
          updateTime: '2026-06-20T02:00:00',
        },
      ]}
      onChange={setDraft}
    />
  )
}

function EditModelFormHarness() {
  const [draft, setDraft] = useState<ModelDraft>({
    ...emptyModelDraft(),
    providerName: 'minimax',
    name: 'MiniMax-M2.7',
    modelId: 'wire-minimax',
  })
  return (
    <ModelForm
      draft={draft}
      mode="edit"
      providers={[
        {
          name: 'minimax',
          description: null,
          providerType: 'openai',
          baseUrl: null,
          configured: true,
          modelCallTimeoutMillis: 1800000,
          modelCallIdleTimeoutMillis: 120000,
          version: '1',
          createTime: null,
          updateTime: null,
        },
      ]}
      onChange={setDraft}
    />
  )
}

function AgentFormHarness() {
  const [draft, setDraft] = useState<AgentDraft>({
    name: '',
    description: '',
    systemPrompt: '',
    model: '',
    variant: 'default',
    environmentId: '',
    toolIds: [],
    skills: [],
    subagents: [],
  })
  return (
    <AgentForm
      draft={draft}
      models={[
        {
          providerName: 'minimax',
          name: 'MiniMax-M2.7',
          description: null,
          config: {
            limit: { context: 128000, output: 8192 },
            abilities: { tools: true, reasoning: false, inputModalities: ['TEXT'] },
            pricing: {
              currency: 'USD',
              pricingTier: 'default',
              serviceTier: 'default',
              serviceTierMultiplier: 1,
              version: 'v1',
              inputPerMillionTokens: 0,
              outputPerMillionTokens: 0,
              cacheReadPerMillionTokens: 0,
              cacheWritePerMillionTokens: 0,
              cacheWriteLongPerMillionTokens: 0,
              reasoningPerMillionTokens: 0,
            },
            defaultVariant: 'default',
            variants: [{ id: 'default' }],
          },
          createTime: '2026-06-20T02:00:00',
          updateTime: '2026-06-20T02:00:00',
        },
        {
          providerName: 'anthropic',
          name: 'Claude-Sonnet-4.5',
          description: null,
          config: {
            limit: { context: 200000, output: 16000 },
            abilities: { tools: false, reasoning: true, inputModalities: ['TEXT'] },
            pricing: {
              currency: 'USD',
              pricingTier: 'default',
              serviceTier: 'default',
              serviceTierMultiplier: 1,
              version: 'v1',
              inputPerMillionTokens: 0,
              outputPerMillionTokens: 0,
              cacheReadPerMillionTokens: 0,
              cacheWritePerMillionTokens: 0,
              cacheWriteLongPerMillionTokens: 0,
              reasoningPerMillionTokens: 0,
            },
            defaultVariant: 'creative',
            variants: [{ id: 'creative' }, { id: 'precise' }],
          },
          createTime: '2026-06-20T02:00:00',
          updateTime: '2026-06-20T02:00:00',
        },
      ]}
      onChange={setDraft}
    />
  )
}
