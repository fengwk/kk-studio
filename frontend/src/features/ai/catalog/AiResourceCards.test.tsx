import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { AgentResourceCard } from '@/features/ai/catalog/AiAgentResourceCard'
import type { AgentModelView } from '@/features/ai/catalog/AgentModelView'
import { ModelResourceCard } from '@/features/ai/catalog/AiModelResourceCard'
import { ProviderResourceCard } from '@/features/ai/catalog/AiProviderResourceCard'
import type { AgentDefinitionDTO, AgentProviderDTO } from '@/shared/api/contracts/ai-catalog'

function model(overrides: Partial<AgentModelView> = {}): AgentModelView {
  return {
    providerName: 'minimax',
    name: 'MiniMax',
    modelId: 'minimax-upstream',
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
    version: '1',
    createTime: null,
    updateTime: null,
    ...overrides,
  }
}

function agent(overrides: Partial<AgentDefinitionDTO> = {}): AgentDefinitionDTO {
  return {
    name: 'assistant',
    description: 'agent description',
    systemPrompt: 'prompt',
    model: 'minimax/MiniMax',
    variant: 'quality',
    config: {
      inheritParentEnvironment: true,
      tools: ['read', 'bash', 'grep'],
      skills: ['dev'],
      subagents: ['writer'],
    },
    version: '1',
    createTime: null,
    updateTime: null,
    ...overrides,
  }
}

function provider(overrides: Partial<AgentProviderDTO> = {}): AgentProviderDTO {
  return {
    name: 'minimax',
    description: null,
    providerType: 'openai',
    baseUrl: 'https://api.example.test',
    configured: true,
    modelCallTimeoutMillis: 180000,
    modelCallIdleTimeoutMillis: 1500,
    version: '1',
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

    expect(screen.getByText(/minimax\/MiniMax · quality/)).toBeInTheDocument()
    // 卡片对 tools 和 skills 做截断展示；超出部分（3 个中的 2 个）折叠为 +1。
    expect(screen.getByText('+1')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '编辑 assistant' }))
    await user.click(screen.getByRole('button', { name: '删除 assistant' }))
    expect(onEdit).toHaveBeenCalledOnce()
    expect(onDelete).toHaveBeenCalledOnce()
  })

  it('projects skill names as tags on the agent card', () => {
    render(
      <AgentResourceCard
        agent={agent({
          config: {
            inheritParentEnvironment: true,
            tools: [],
            skills: [
              { packageName: 'pkg-a', name: 'skill-alpha' },
              { packageName: 'pkg-b', name: 'skill-beta' },
              { packageName: 'pkg-c', name: 'skill-gamma' },
            ],
            subagents: [],
          },
        })}
        models={[]}
        onEdit={() => undefined}
        onDelete={() => undefined}
        deletePending={false}
      />,
    )
    expect(screen.getByText('pkg-a / skill-alpha')).toBeInTheDocument()
    expect(screen.getByText('pkg-b / skill-beta')).toBeInTheDocument()
    expect(screen.queryByText('pkg-c / skill-gamma')).not.toBeInTheDocument()
    expect(screen.getByText('+1')).toBeInTheDocument()
  })

  it('renders agent subagents as a tagged row with the same 2-item limit', () => {
    const { rerender } = render(
      <AgentResourceCard
        agent={agent({
          config: {
            inheritParentEnvironment: true,
            tools: [],
            skills: [],
            subagents: ['helper', 'writer', 'architect'],
          },
        })}
        models={[]}
        onEdit={() => undefined}
        onDelete={() => undefined}
        deletePending={false}
      />,
    )

    // 标签行保留 label；tag limit 沿用 2，第 3 个折叠为 +1。
    expect(screen.getByText('Subagents')).toBeInTheDocument()
    expect(screen.getByText('helper')).toBeInTheDocument()
    expect(screen.getByText('writer')).toBeInTheDocument()
    expect(screen.queryByText('architect')).not.toBeInTheDocument()
    expect(screen.getByText('+1')).toBeInTheDocument()

    // 空 subagents 行保留 label，右侧留空。
    rerender(
      <AgentResourceCard
        agent={agent({
          config: { inheritParentEnvironment: true, tools: [], skills: [], subagents: [] },
        })}
        models={[]}
        onEdit={() => undefined}
        onDelete={() => undefined}
        deletePending={false}
      />,
    )
    expect(screen.getByText('Subagents')).toBeInTheDocument()
    expect(screen.queryByText('+1')).not.toBeInTheDocument()
  })

  it('renders missing agent references without inventing metadata', () => {
    const { rerender } = render(
      <AgentResourceCard
        agent={agent({
          description: null,
          model: 'missing-model',
          variant: null,
          config: {
            inheritParentEnvironment: true,
            tools: [],
            skills: [],
            subagents: [],
          },
        })}
        models={[]}
        onEdit={() => undefined}
        onDelete={() => undefined}
        deletePending
      />,
    )

    expect(screen.getByText(/missing-model · (未解析|unresolved)/)).toBeInTheDocument()
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
    expect(screen.getByText(/minimax\/MiniMax · quality/)).toBeInTheDocument()
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
    // provider 已删除：仍保留唯一的 providerName/model ref
    expect(screen.getAllByText('MiniMax').length).toBeGreaterThanOrEqual(1)
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
