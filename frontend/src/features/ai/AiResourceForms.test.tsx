import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import { AgentForm, ModelForm, ProviderForm } from '@/features/ai/AiResourceForms'
import type { AgentDraft, ModelDraft, ProviderDraft } from '@/features/ai/ai-console-types'

describe('AiResourceForms', () => {
  it('edits provider fields with structured inputs', async () => {
    const user = userEvent.setup()
    render(<ProviderFormHarness />)

    await user.type(screen.getByPlaceholderText('minimax'), 'provider-a')
    await user.type(screen.getByPlaceholderText('用途说明'), 'provider desc')
    await user.selectOptions(screen.getByLabelText('Provider Type'), 'anthropic')
    await user.type(screen.getByPlaceholderText('https://api.example.com/v1'), 'https://proxy.example/v1')
    await user.type(screen.getByPlaceholderText('sk-...'), 'secret')
    const totalTimeoutInput = screen.getByPlaceholderText('1800000')
    await user.clear(totalTimeoutInput)
    await user.type(totalTimeoutInput, '240000')
    const idleTimeoutInput = screen.getByPlaceholderText('120000')
    await user.clear(idleTimeoutInput)
    await user.type(idleTimeoutInput, '3000')

    expect(screen.getByDisplayValue('provider-a')).toBeInTheDocument()
    expect(screen.getByDisplayValue('provider desc')).toBeInTheDocument()
    expect(screen.getByDisplayValue('anthropic')).toBeInTheDocument()
    expect(screen.getByDisplayValue('https://proxy.example/v1')).toBeInTheDocument()
    expect(screen.getByDisplayValue('secret')).toBeInTheDocument()
    expect(screen.getByDisplayValue('240000')).toBeInTheDocument()
    expect(screen.getByDisplayValue('3000')).toBeInTheDocument()
  })

  it('edits model variants without raw json', async () => {
    const user = userEvent.setup()
    render(<ModelFormHarness />)

    await user.click(screen.getByRole('button', { name: '添加 Variant' }))
    const nameInputs = screen.getAllByDisplayValue('default')
    expect(nameInputs).toHaveLength(2)

    const secondNameInput = screen.getAllByPlaceholderText('default')[1]
    await user.clear(secondNameInput)
    await user.type(secondNameInput, 'creative')
    await user.type(screen.getByDisplayValue('creative'), '{tab}0.8{tab}512')
    expect(screen.getByRole('option', { name: 'creative' })).toBeInTheDocument()

    const extras = screen.getByRole('region', { name: 'Variant Extras 2' })
    await user.click(within(extras).getByRole('button', { name: '添加' }))
    await user.type(screen.getByLabelText('Variant Extras 2 key 1'), 'topK')
    await user.type(screen.getByLabelText('Variant Extras 2 value 1'), '32')
    expect(screen.getByDisplayValue('topK')).toBeInTheDocument()
    expect(screen.getByDisplayValue('32')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '重置默认' }))
    expect(screen.getByLabelText('Default Variant')).toHaveValue('default')
    expect(screen.queryByDisplayValue('creative')).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Variant Extras 2' })).not.toBeInTheDocument()
  })

  it('keeps the default variant selection aligned with variant edits', async () => {
    const user = userEvent.setup()
    render(<ModelFormHarness />)

    await user.click(screen.getByRole('button', { name: '添加 Variant' }))
    const secondNameInput = screen.getAllByPlaceholderText('default')[1]
    await user.clear(secondNameInput)
    await user.type(secondNameInput, 'creative')

    await user.selectOptions(screen.getByLabelText('Default Variant'), 'creative')
    expect(screen.getByLabelText('Default Variant')).toHaveValue('creative')

    fireEvent.change(secondNameInput, { target: { value: 'creative-2' } })
    expect(screen.getByLabelText('Default Variant')).toHaveValue('creative-2')

    await user.click(screen.getAllByRole('button', { name: '删除 Variant' })[1])
    expect(screen.getByLabelText('Default Variant')).toHaveValue('default')
  })

  it('edits agent model binding and tools string list without json editing', async () => {
    const user = userEvent.setup()
    render(<AgentFormHarness />)

    expect(screen.getByRole('button', { name: '使用第一个 Model 填充默认配置' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '使用第一个 Model 填充默认配置' }))
    expect(screen.getByLabelText('Model')).toHaveValue('minimax/MiniMax-M2.7')
    expect(screen.getByLabelText('Default Variant')).toHaveValue('default')

    await user.selectOptions(screen.getByLabelText('Model'), 'anthropic/Claude-Sonnet-4.5')
    expect(screen.getByLabelText('Default Variant')).toHaveValue('creative')

    const tools = screen.getByRole('region', { name: 'Tools' })
    await user.click(within(tools).getByRole('button', { name: '添加' }))
    await user.type(screen.getByLabelText('Tools 1'), 'search')
    expect(screen.getByDisplayValue('search')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '删除 Tools 1' }))
    expect(screen.queryByDisplayValue('search')).not.toBeInTheDocument()
  })

  it('shows the model prerequisite hint when no model is available', () => {
    render(
      <AgentForm
        draft={{
          name: '',
          description: '',
          systemPrompt: '',
          defaultProvider: '',
          defaultModel: '',
          defaultVariant: 'default',
          tools: [],
        }}
        models={[]}
        onChange={() => undefined}
      />,
    )

    expect(screen.getByText('需要先创建 Model 才能配置 Agent。')).toBeInTheDocument()
  })
})

function ProviderFormHarness() {
  const [draft, setDraft] = useState<ProviderDraft>({
    name: '',
    description: '',
    providerType: 'openai',
    baseUrl: '',
    credential: '',
    modelCallTimeoutMillis: '1800000',
    modelCallIdleTimeoutMillis: '120000',
  })
  return <ProviderForm draft={draft} onChange={setDraft} />
}

function ModelFormHarness() {
  const [draft, setDraft] = useState<ModelDraft>({
    provider: 'minimax',
    name: '',
    description: '',
    defaultVariant: 'default',
    variants: [{ id: 'variant-1', name: 'default', temperature: '', maxOutputTokens: '', extras: [] }],
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
    defaultProvider: '',
    defaultModel: '',
    defaultVariant: 'default',
    tools: [],
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
          defaultVariant: 'default',
          variantsJson: '[{"name":"default"}]',
          createTime: '2026-06-20T02:00:00',
          updateTime: '2026-06-20T02:00:00',
        },
        {
          id: 'model-2',
          providerId: 'provider-2',
          providerName: 'anthropic',
          name: 'Claude-Sonnet-4.5',
          description: null,
          defaultVariant: 'creative',
          variantsJson: '[{"name":"creative"},{"name":"precise"}]',
          createTime: '2026-06-20T02:00:00',
          updateTime: '2026-06-20T02:00:00',
        },
      ]}
      onChange={setDraft}
    />
  )
}
