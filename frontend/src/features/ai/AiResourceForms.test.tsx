import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import type { AgentDraft, ModelDraft, ProviderDraft } from '@/features/ai/ai-console-types'
import { emptyModelDraft } from '@/features/ai/ai-model-draft-codec'
import { AgentForm, ModelForm, ProviderForm } from '@/features/ai/AiResourceForms'

async function selectFormOption(
  user: ReturnType<typeof userEvent.setup>,
  label: string,
  option: string,
) {
  await user.selectOptions(screen.getByLabelText(label), option)
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
    expect(screen.getByLabelText('Provider Type')).toHaveValue('anthropic')
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
    expect(Array.from(container.querySelectorAll('label > span')).slice(0, 2).map((label) => label.textContent)).toEqual([
      'Name',
      'API Key（可选）',
    ])
  })

  it('edits the new model abilities, pricing, variants, and collapsed advanced options', async () => {
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

    const firstAdvanced = screen.getAllByText('高级选项')[0].closest('details')
    expect(firstAdvanced).not.toHaveAttribute('open')

    await user.click(screen.getByRole('button', { name: '添加 Variant' }))
    const secondNameInput = screen.getByLabelText('Variant ID 2')
    expect(secondNameInput).toHaveValue('variant-2')
    await user.clear(secondNameInput)
    await user.type(secondNameInput, 'creative')
    await user.clear(screen.getByLabelText('Reasoning Effort 2'))
    await user.type(screen.getByLabelText('Reasoning Effort 2'), 'high')
    await user.type(screen.getByLabelText('Variant Max Output Tokens 2'), '512')

    await user.click(screen.getAllByText('高级选项')[1])
    await user.type(screen.getByLabelText('Temperature 2'), '0.8')
    await user.type(screen.getByLabelText('Top P 2'), '0.9')
    await user.type(screen.getByLabelText('Top K 2'), '32')
    await user.type(screen.getByLabelText('Stop Sequences 2'), 'END,STOP')
    expect(screen.getByDisplayValue('creative')).toBeInTheDocument()

    await selectFormOption(user, 'Default Variant', 'creative')
    expect(screen.getByLabelText('Default Variant')).toHaveValue('creative')
  })

  it('keeps default variant aligned and hides reasoning effort when reasoning is disabled', async () => {
    const user = userEvent.setup()
    render(<ModelFormHarness />)

    await user.click(screen.getByRole('button', { name: '添加 Variant' }))
    const secondNameInput = screen.getByLabelText('Variant ID 2')
    await user.clear(secondNameInput)
    await user.type(secondNameInput, 'creative')
    await selectFormOption(user, 'Default Variant', 'creative')

    fireEvent.change(secondNameInput, { target: { value: 'creative-2' } })
    expect(screen.getByLabelText('Default Variant')).toHaveValue('creative-2')

    await user.click(screen.getAllByRole('button', { name: '删除 Variant' })[1])
    expect(screen.getByLabelText('Default Variant')).toHaveValue('medium')

    await user.click(screen.getByLabelText('Reasoning'))
    expect(screen.queryByLabelText('Reasoning Effort 1')).not.toBeInTheDocument()
    expect(screen.getByText(/请先勾选上方 Reasoning/)).toBeInTheDocument()
  })

  it('edits agent model binding and tools without json editing', async () => {
    const user = userEvent.setup()
    render(<AgentFormHarness />)

    expect(screen.getByRole('button', { name: '使用第一个 Model 填充默认配置' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '使用第一个 Model 填充默认配置' }))
    expect(screen.getByLabelText('Model')).toHaveValue('model-1')
    expect(screen.getByLabelText('Variant')).toHaveValue('default')

    await selectFormOption(user, 'Model', 'Claude-Sonnet-4.5 (anthropic)')
    expect(screen.getByLabelText('Variant')).toHaveValue('creative')
    expect(screen.getByText('暂无候选 Tools')).toBeInTheDocument()
  })

  it('shows the model prerequisite hint when no model is available', () => {
    render(
      <AgentForm
        draft={{
          name: '',
          description: '',
          systemPrompt: '',
          modelId: '',
          variant: 'default',
          environmentName: '',
          tools: [],
          skills: [],
          allowedSubagents: [],
          executionPolicy: {
            maxTurns: '',
            maxDepth: '',
            maxDirectSubagents: '',
            maxTotalSubagents: '',
          },
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
    providerId: 'provider-1',
    reasoning: true,
  })
  return (
    <ModelForm
      draft={draft}
      mode="create"
      providers={[
        {
          id: 'provider-1',
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

function AgentFormHarness() {
  const [draft, setDraft] = useState<AgentDraft>({
    name: '',
    description: '',
    systemPrompt: '',
    modelId: '',
    variant: 'default',
    environmentName: '',
    tools: [],
    skills: [],
    allowedSubagents: [],
    executionPolicy: {
      maxTurns: '',
      maxDepth: '',
      maxDirectSubagents: '',
      maxTotalSubagents: '',
    },
  })
  return (
    <AgentForm
      draft={draft}
      models={[
        {
          id: 'model-1',
          providerId: 'provider-1',
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
          id: 'model-2',
          providerId: 'provider-2',
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
