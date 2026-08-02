import { describe, expect, it } from 'vitest'
import type { AgentModelConfigDTO, AgentModelDTO } from '@/shared/api/contracts/ai-catalog'
import { formatModelRef, modelRef, toAgentModelViews } from '@/features/ai/catalog/AgentModelView'

function model(providerName = 'minimax', name = 'MiniMax-M2.7'): AgentModelDTO {
  const config: AgentModelConfigDTO = {
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
  return { providerName, name, description: null, config, version: '1', createTime: null, updateTime: null }
}

describe('AgentModelView', () => {
  it('keeps the name-based model identity in the view', () => {
    const source = [model()]
    const views = toAgentModelViews(source)
    expect(views).toEqual(source)
    expect(modelRef(views[0])).toBe('minimax/MiniMax-M2.7')
  })

  it('formats provider/model references without splitting model names', () => {
    expect(formatModelRef('minimax', 'MiniMax/M2.7')).toBe('minimax/MiniMax/M2.7')
    expect(formatModelRef(null, 'orphan')).toBe('orphan')
    expect(formatModelRef('only-provider', null)).toBe('only-provider')
  })
})
