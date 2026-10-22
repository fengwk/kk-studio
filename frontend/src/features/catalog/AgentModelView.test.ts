import { describe, expect, it } from 'vitest'
import {
  formatModelLabel,
  formatModelRef,
  modelRef,
  toAgentModelViews,
} from '@/features/catalog/AgentModelView'
import type { AgentModelConfigDTO, AgentModelDTO } from '@/shared/api/generated/contracts'

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
  return { providerName, name, modelId: name.toLowerCase(), description: null, config, version: '1', createTime: 0, updateTime: 0 }
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

  it('formats model labels with variant or falls back to canonical ref without inventing defaults', () => {
    // 有 variant 时生成统一 provider/model (variant) 格式
    expect(formatModelLabel('commandcode', 'glm-5.3-flash', 'high')).toBe(
      'commandcode/glm-5.3-flash (high)',
    )
    expect(formatModelLabel('xai', 'grok-4.7', 'xhigh')).toBe('xai/grok-4.7 (xhigh)')

    // 模型名已包含 `/` 前缀时不重复拼接 provider
    expect(formatModelLabel('minimax', 'minimax/MiniMax-M2.7', 'fast')).toBe(
      'minimax/MiniMax-M2.7 (fast)',
    )
    expect(formatModelLabel('minimax', 'group/deep-model', 'deep')).toBe(
      'minimax/group/deep-model (deep)',
    )

    // variant 缺失、null、undefined 或纯空白时退回规范引用，绝不伪造 default variant
    expect(formatModelLabel('provider', 'model', null)).toBe('provider/model')
    expect(formatModelLabel('provider', 'model', undefined)).toBe('provider/model')
    expect(formatModelLabel('provider', 'model', '')).toBe('provider/model')
    expect(formatModelLabel('provider', 'model', '   ')).toBe('provider/model')
    expect(formatModelLabel('provider', 'model')).toBe('provider/model')

    // 缺少 provider 时原样保留 model
    expect(formatModelLabel(null, 'orphan-model', 'v1')).toBe('orphan-model (v1)')
    expect(formatModelLabel(undefined, 'custom/ref', 'override')).toBe('custom/ref (override)')
    expect(formatModelLabel(null, null, 'v1')).toBe('unknown-model (v1)')
    expect(formatModelLabel('', '', '')).toBe('unknown-model')
  })
})
