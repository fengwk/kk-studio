import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { AgentResourceCard } from '@/features/ai/AiAgentResourceCard'
import type { AgentModelView } from '@/features/ai/AgentModelView'
import { ModelResourceCard } from '@/features/ai/AiModelResourceCard'
import { ProviderResourceCard } from '@/features/ai/AiProviderResourceCard'
import type { AgentDefinitionDTO, AgentProviderDTO } from '@/shared/api/contracts'

function model(overrides: Partial<AgentModelView> = {}): AgentModelView {
  return {
    id: 'model-1',
    providerId: 'provider-1',
    providerName: 'minimax',
    name: 'MiniMax',
    description: 'model description',
    config: {
      limit: { context: 128000, output: 8192 },
      abilities: { tools: true, reasoning: true, inputModalities: ['TEXT', 'IMAGE', 'AUDIO', 'VIDEO'] },
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
      defaultVariant: 'quality',
      variants: [{ id: 'quality' }, { id: 'fast' }, { id: 'cheap' }, { id: 'debug' }],
    },
    version: 1,
    createTime: null,
    updateTime: null,
    ...overrides,
  }
}

function agent(overrides: Partial<AgentDefinitionDTO> = {}): AgentDefinitionDTO {
  return {
    id: 'agent-1',
    name: 'assistant',
    description: 'agent description',
    systemPrompt: 'prompt',
    modelId: 'model-1',
    variant: 'quality',
    config: {
      environmentName: 'local',
      tools: ['read', 'bash', 'grep'],
      skills: ['dev'],
    },
    version: 1,
    createTime: null,
    updateTime: null,
    ...overrides,
  }
}

function provider(overrides: Partial<AgentProviderDTO> = {}): AgentProviderDTO {
  return {
    id: 'provider-1',
    name: 'minimax',
    description: null,
    providerType: 'openai',
    baseUrl: 'https://api.example.test',
    configured: true,
    modelCallTimeoutMillis: 180000,
    modelCallIdleTimeoutMillis: 1500,
    version: 1,
    createTime: null,
    updateTime: null,
    ...overrides,
  }
}

describe('AI resource cards', () => {
  it('renders complete agent metadata and action states', async () => {
    const user = userEvent.setup()
    const onEdit = vi.fn()
    const onDelete = vi.fn()
    render(
      <AgentResourceCard
        agent={agent()}
        models={[model()]}
        onEdit={onEdit}
        onDelete={onDelete}
        deletePending={false}
      />,
    )

    expect(screen.getByText('minimax/MiniMax')).toBeInTheDocument()
    // Card shows tools and skills truncated; remaining tools (3 of 2) roll up to +1.
    expect(screen.getByText('+1')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '编辑 assistant' }))
    await user.click(screen.getByRole('button', { name: '删除 assistant' }))
    expect(onEdit).toHaveBeenCalledOnce()
    expect(onDelete).toHaveBeenCalledOnce()
  })

  it('renders missing agent references without inventing metadata', () => {
    const { rerender } = render(
      <AgentResourceCard
        agent={agent({
          description: null,
          modelId: 'missing-model',
          config: {
            environmentName: null,
            tools: [],
            skills: [],
          },
        })}
        models={[]}
        onEdit={() => undefined}
        onDelete={() => undefined}
        deletePending
      />,
    )

    expect(screen.getByText('missing-model')).toBeInTheDocument()
    expect(screen.getByText('prompt')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '删除 assistant' })).toBeDisabled()

    rerender(
      <AgentResourceCard
        agent={agent({ description: null, systemPrompt: null })}
        models={[model({ providerName: null })]}
        onEdit={() => undefined}
        onDelete={() => undefined}
        deletePending={false}
      />,
    )
    expect(screen.getByText('provider-1/MiniMax')).toBeInTheDocument()
    expect(screen.getAllByText('assistant')).toHaveLength(2)
  })

  it('renders model limits, abilities, variants, and strict empty values', () => {
    const { rerender } = render(
      <ModelResourceCard
        model={model()}
        onEdit={() => undefined}
        onDelete={() => undefined}
        deletePending={false}
      />,
    )

    expect(screen.getAllByText('minimax/MiniMax').length).toBeGreaterThanOrEqual(1)
    expect(screen.getByText('ctx 128000 · out 8192')).toBeInTheDocument()
    expect(screen.getByText('tools · reasoning · TEXT, IMAGE, AUDIO +1')).toBeInTheDocument()
    expect(screen.getAllByText('quality')).toHaveLength(2)
    expect(screen.getByText('+1')).toBeInTheDocument()

    rerender(
      <ModelResourceCard
        model={model({
          providerName: null,
          description: null,
          config: {
            ...model().config,
            limit: { context: 0, output: 0 },
            abilities: { tools: false, reasoning: false, inputModalities: [] },
            defaultVariant: '',
            variants: [],
          },
        })}
        onEdit={() => undefined}
        onDelete={() => undefined}
        deletePending={false}
      />,
    )
    // provider deleted: still keep unique providerId/model ref
    expect(screen.getAllByText('provider-1/MiniMax').length).toBeGreaterThanOrEqual(1)
    expect(screen.getAllByText('—').length).toBeGreaterThanOrEqual(3)
  })

  it('formats provider timeout variants and subtitle fallbacks', () => {
    const { rerender } = render(
      <ProviderResourceCard
        provider={provider()}
        onEdit={() => undefined}
        onDelete={() => undefined}
        deletePending={false}
      />,
    )
    expect(screen.getByText('180s')).toBeInTheDocument()
    expect(screen.getByText('1500ms')).toBeInTheDocument()
    expect(screen.getByText('已配置')).toBeInTheDocument()

    rerender(
      <ProviderResourceCard
        provider={provider({
          description: 'provider description',
          baseUrl: null,
          configured: false,
          modelCallTimeoutMillis: '',
          modelCallIdleTimeoutMillis: 'not-a-number',
        })}
        onEdit={() => undefined}
        onDelete={() => undefined}
        deletePending={false}
      />,
    )
    expect(screen.getByText('provider description')).toBeInTheDocument()
    expect(screen.getByText('not-a-number')).toBeInTheDocument()
    expect(screen.getByText('无 API Key（无认证请求）')).toBeInTheDocument()
    expect(screen.getAllByText('—').length).toBeGreaterThan(0)
  })
})
