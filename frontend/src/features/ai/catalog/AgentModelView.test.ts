import { describe, expect, it } from 'vitest'
import type { AgentModelConfigDTO, AgentModelDTO } from '@/shared/api/contracts/ai-catalog'
import {
  formatModelLabel,
  isGroupModel,
  toAgentModelViews,
} from '@/features/ai/catalog/AgentModelView'

function model(name = 'MiniMax-M2.7'): AgentModelDTO {
  const config: AgentModelConfigDTO = {
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
  return {
    providerName: 'minimax',
    kind: 'MODEL',
    name,
    modelId: name.toLowerCase(),
    description: null,
    config,
    version: '1',
    createTime: null,
    updateTime: null,
  }
}

describe('AgentModelView', () => {
  it('keeps the globally unique logical name in the view', () => {
    const source = [model()]
    const views = toAgentModelViews(source)
    expect(views).toEqual(source)
    expect(views[0]?.name).toBe('MiniMax-M2.7')
  })

  it('formats logical model names with an optional variant without inventing defaults', () => {
    expect(formatModelLabel('MiniMax-M2.7', 'high')).toBe('MiniMax-M2.7 (high)')
    expect(formatModelLabel('grok-4.7', 'xhigh')).toBe('grok-4.7 (xhigh)')
    expect(formatModelLabel('MiniMax-M2.7')).toBe('MiniMax-M2.7')
    expect(formatModelLabel('group/deep-model', 'deep')).toBe('group/deep-model (deep)')
    expect(formatModelLabel('MiniMax-M2.7', null)).toBe('MiniMax-M2.7')
    expect(formatModelLabel('MiniMax-M2.7', undefined)).toBe('MiniMax-M2.7')
    expect(formatModelLabel('MiniMax-M2.7', '')).toBe('MiniMax-M2.7')
    expect(formatModelLabel('MiniMax-M2.7', '   ')).toBe('MiniMax-M2.7')
    expect(formatModelLabel('', 'v1')).toBe('v1')
    expect(formatModelLabel('', '')).toBe('unknown-model')
  })

  it('recognizes groups without requiring a fabricated variant', () => {
    expect(isGroupModel(model())).toBe(false)
    expect(isGroupModel({ ...model('group'), kind: 'GROUP' })).toBe(true)
    expect(isGroupModel(null)).toBe(false)
  })
})
