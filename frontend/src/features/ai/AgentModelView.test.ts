import { describe, expect, it } from 'vitest'
import type {
  AgentModelConfigDTO,
  AgentModelDTO,
  AgentProviderDTO,
} from '@/shared/api/contracts'
import { formatModelRef, modelRef, toAgentModelViews } from '@/features/ai/AgentModelView'

function baseConfig(): AgentModelConfigDTO {
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

function model(id: string, providerId: string): AgentModelDTO {
  return {
    id,
    providerId,
    name: id,
    description: null,
    config: baseConfig(),
    version: '1',
    createTime: null,
    updateTime: null,
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

describe('toAgentModelViews', () => {
  it('joins provider name into each model view', () => {
    const providers = [provider('p1', 'minimax')]
    const models = [model('m1', 'p1')]
    const views = toAgentModelViews(models, providers)
    expect(views).toHaveLength(1)
    expect(views[0].providerName).toBe('minimax')
    expect(views[0].id).toBe('m1')
  })

  it('keeps providerName null when the provider was deleted', () => {
    const views = toAgentModelViews([model('m1', 'p1')], [])
    expect(views[0].providerName).toBeNull()
  })

  it('does not mutate input providers or models', () => {
    const providers = [provider('p1', 'minimax')]
    const models = [model('m1', 'p1')]
    const beforeModels = JSON.stringify(models)
    const beforeProviders = JSON.stringify(providers)
    toAgentModelViews(models, providers)
    expect(JSON.stringify(models)).toBe(beforeModels)
    expect(JSON.stringify(providers)).toBe(beforeProviders)
  })

  it('matches providerId as string against numeric ids', () => {
    const providers = [provider('42', 'numeric')]
    const models = [model('m1', '42')]
    expect(toAgentModelViews(models, providers)[0].providerName).toBe('numeric')
  })
})

describe('formatModelRef', () => {
  it('composes the canonical provider/model identity', () => {
    expect(formatModelRef('minimax', 'MiniMax-M2.7')).toBe('minimax/MiniMax-M2.7')
    expect(formatModelRef('minimax', 'minimax/MiniMax-M2.7')).toBe('minimax/MiniMax-M2.7')
    expect(formatModelRef(null, 'orphan')).toBe('orphan')
    expect(formatModelRef('only-provider', null)).toBe('only-provider')
  })

  it('builds refs from AgentModelView', () => {
    const view = toAgentModelViews([model('MiniMax-M2.7', 'p1')], [provider('p1', 'minimax')])[0]
    expect(modelRef(view)).toBe('minimax/MiniMax-M2.7')
  })
})