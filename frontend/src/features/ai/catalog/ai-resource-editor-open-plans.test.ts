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
      base: { input: '0', output: '0', cacheRead: '0', cacheWrite: '0', cacheWriteByTtl: {}, reasoning: null },
      overrides: [],
      multipliers: [],
    },
    defaultVariant: 'default',
    variants: [{ id: 'default' }],
  }
}

function provider(_id: string, name: string): AgentProviderDTO {
  return {
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
    modelHttpRetryStatusCodes: null,
  }
}

function model(id: string, providerName: string): AgentModelDTO {
  return {
    providerName,
    kind: 'MODEL',
    name: id,
    modelId: id.toLowerCase(),
    description: null,
    config: modelConfig(),
    version: '1',
    createTime: null,
    updateTime: null,
  }
}

function agent(id: string, model: string): AgentDefinitionDTO {
  return {
    name: id,
    description: null,
    systemPrompt: null,
    type: 'USER',
    model,
    variant: 'default',
    config: {
      inheritParentEnvironment: true,
      tools: [],
      skills: [],
      subagents: [],
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
    expect(plan.modelDraft.providerName).toBe('minimax')
  })

  it('returns empty providerName when no provider exists', () => {
    const plan = createModelEditorPlan([])
    expect(plan.kind).toBe('model')
    if (plan.kind !== 'model') return
    expect(plan.modelDraft.providerName).toBe('')
  })

  it('returns null when editModelEditorPlan cannot find the model', () => {
    const providers = [provider('p1', 'minimax')]
    const models = [model('MiniMax', 'minimax')]
    expect(editModelEditorPlan(models, 'missing')).toBeNull()
    expect(editProviderEditorPlan(providers, 'missing' as never)).toBeNull()
    expect(editAgentEditorPlan([agent('a1', 'MiniMax')], models, 'missing' as never)).toBeNull()
  })

  it('builds an edit model plan when the model exists', () => {
    const models = [model('MiniMax', 'minimax')]
    const plan = editModelEditorPlan(models, 'MiniMax')
    expect(plan).not.toBeNull()
    if (!plan) return
    expect(plan.kind).toBe('model')
    if (plan.kind !== 'model') return
    expect(plan.modelDraft.name).toBe('MiniMax')
    expect(plan.modal).toMatchObject({ mode: 'edit', resourceKind: 'MODEL', name: 'MiniMax', expectedVersion: '1' })
  })

  it('seeds a new agent plan with the first model', () => {
    const models = [model('MiniMax', 'minimax')]
    const plan = createAgentEditorPlan(models)
    expect(plan.kind).toBe('agent')
    if (plan.kind !== 'agent') return
    expect(plan.agentDraft.model).toBe('MiniMax')
  })

  it('builds an empty agent plan when no model or provider exists', () => {
    const plan = createAgentEditorPlan([])
    expect(plan.kind).toBe('agent')
    if (plan.kind !== 'agent') return
    expect(plan.agentDraft.model).toBe('')
  })

  it('builds edit plan when the agent exists', () => {
    const models = [model('MiniMax', 'minimax')]
    const agents = [agent('a1', 'MiniMax')]
    const plan = editAgentEditorPlan(agents, models, 'a1')
    expect(plan).not.toBeNull()
    if (!plan) return
    expect(plan.kind).toBe('agent')
    expect(plan.modal).toMatchObject({ mode: 'edit', name: 'a1', type: 'USER', expectedVersion: '1' })
  })

  it('carries BUILTIN identity and an unconfigured model into the edit plan without seeding a fallback', () => {
    const models = [model('MiniMax', 'minimax')]
    const builtin: AgentDefinitionDTO = {
      ...agent('summarizer', 'MiniMax'),
      type: 'BUILTIN',
      model: null,
    }
    const plan = editAgentEditorPlan([builtin], models, 'summarizer')
    expect(plan).not.toBeNull()
    if (!plan) return
    expect(plan.kind).toBe('agent')
    expect(plan.modal).toMatchObject({ mode: 'edit', name: 'summarizer', type: 'BUILTIN', model: null })
    // 未配置模型保持空字符串，不被第一个 model 静默填充。
    expect(plan.agentDraft.model).toBe('')
  })

  it('keeps an Agent edit plan identity when its Model is deleted or off-page', () => {
    const agents = [agent('a1', 'Deleted-Model')]
    const plan = editAgentEditorPlan(agents, [model('Unrelated', 'other-provider')], 'a1')

    expect(plan).not.toBeNull()
    if (!plan) return
    expect(plan.agentDraft.model).toBe('Deleted-Model')
    expect(plan.modal).toMatchObject({
      mode: 'edit',
      model: 'Deleted-Model',
    })
  })

  it('builds a provider edit plan', () => {
    const providers = [provider('p1', 'minimax')]
    const plan = editProviderEditorPlan(providers, 'minimax')
    expect(plan).not.toBeNull()
    if (!plan) return
    expect(plan.kind).toBe('provider')
    if (plan.kind !== 'provider') return
    expect(plan.providerDraft.name).toBe('minimax')
    expect(plan.modal).toMatchObject({ mode: 'edit', name: 'minimax', expectedVersion: '1' })
  })

  it('returns a provider create plan', () => {
    const plan = createProviderEditorPlan()
    expect(plan.kind).toBe('provider')
    if (plan.kind !== 'provider') return
    expect(plan.modal.mode).toBe('create')
  })
})