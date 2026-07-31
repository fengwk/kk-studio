import { describe, expect, it } from 'vitest'
import {
  createAgentEditorPlan,
  createModelEditorPlan,
  createProviderEditorPlan,
  editAgentEditorPlan,
  editModelEditorPlan,
  editProviderEditorPlan,
} from '@/features/ai/catalog/ai-resource-editor-open-plans'
import type {
  AgentDefinitionDTO,
  AgentModelConfigDTO,
  AgentModelDTO,
  AgentProviderDTO,
} from '@/shared/api/contracts/ai-catalog'

function modelConfig(): AgentModelConfigDTO {
  return {
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
  }
}

function provider(id: string, name: string): AgentProviderDTO {
  return {
    id,
    name,
    description: null,
    providerType: 'openai',
    baseUrl: null,
    configured: true,
    modelCallTimeoutMillis: 1800000,
    modelCallIdleTimeoutMillis: 120000,
    version: '1',
    createTime: null,
    updateTime: null,
  }
}

function model(id: string, providerId: string): AgentModelDTO {
  return {
    id,
    providerId,
    name: id,
    description: null,
    config: modelConfig(),
    version: '1',
    createTime: null,
    updateTime: null,
  }
}

function agent(id: string, modelId: string): AgentDefinitionDTO {
  return {
    id,
    name: id,
    description: null,
    systemPrompt: null,
    modelId,
    variant: 'default',
    config: {
      environmentName: null,
      tools: [],
      skills: [],
    },
    version: '1',
    createTime: null,
    updateTime: null,
  }
}

describe('ai-resource-editor-open-plans', () => {
  it('seeds a new model plan from the first provider', () => {
    const providers = [provider('p1', 'minimax'), provider('p2', 'anthropic')]
    const plan = createModelEditorPlan(providers)
    expect(plan.kind).toBe('model')
    if (plan.kind !== 'model') return
    expect(plan.modelDraft.providerId).toBe('p1')
  })

  it('returns empty providerId when no provider exists', () => {
    const plan = createModelEditorPlan([])
    expect(plan.kind).toBe('model')
    if (plan.kind !== 'model') return
    expect(plan.modelDraft.providerId).toBe('')
  })

  it('returns null when editModelEditorPlan cannot find the model', () => {
    const providers = [provider('p1', 'minimax')]
    const models = [model('m1', 'p1')]
    expect(editModelEditorPlan(models, 'missing' as never)).toBeNull()
    expect(editProviderEditorPlan(providers, 'missing' as never)).toBeNull()
    expect(editAgentEditorPlan([agent('a1', 'm1')], models, 'missing' as never)).toBeNull()
  })

  it('builds an edit model plan when the model exists', () => {
    const models = [model('m1', 'p1')]
    const plan = editModelEditorPlan(models, 'm1')
    expect(plan).not.toBeNull()
    if (!plan) return
    expect(plan.kind).toBe('model')
    if (plan.kind !== 'model') return
    expect(plan.modelDraft.name).toBe('m1')
    expect(plan.modal).toMatchObject({ mode: 'edit', id: 'm1', expectedVersion: '1' })
  })

  it('seeds a new agent plan with the first model', () => {
    const models = [model('m1', 'p1')]
    const plan = createAgentEditorPlan(models)
    expect(plan.kind).toBe('agent')
    if (plan.kind !== 'agent') return
    expect(plan.agentDraft.modelId).toBe('m1')
  })

  it('builds an empty agent plan when no model or provider exists', () => {
    const plan = createAgentEditorPlan([])
    expect(plan.kind).toBe('agent')
    if (plan.kind !== 'agent') return
    expect(plan.agentDraft.modelId).toBe('')
  })

  it('builds edit plan when the agent exists', () => {
    const models = [model('m1', 'p1')]
    const agents = [agent('a1', 'm1')]
    const plan = editAgentEditorPlan(agents, models, 'a1')
    expect(plan).not.toBeNull()
    if (!plan) return
    expect(plan.kind).toBe('agent')
    expect(plan.modal).toMatchObject({ mode: 'edit', id: 'a1', expectedVersion: '1' })
  })

  it('builds a provider edit plan', () => {
    const providers = [provider('p1', 'minimax')]
    const plan = editProviderEditorPlan(providers, 'p1')
    expect(plan).not.toBeNull()
    if (!plan) return
    expect(plan.kind).toBe('provider')
    if (plan.kind !== 'provider') return
    expect(plan.providerDraft.name).toBe('minimax')
    expect(plan.modal).toMatchObject({ mode: 'edit', id: 'p1', expectedVersion: '1' })
  })

  it('returns a provider create plan', () => {
    const plan = createProviderEditorPlan()
    expect(plan.kind).toBe('provider')
    if (plan.kind !== 'provider') return
    expect(plan.modal.mode).toBe('create')
  })
})