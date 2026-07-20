import { describe, expect, it } from 'vitest'
import type { ModelDraft } from '@/features/ai/ai-console-types'
import {
  buildModelCapabilitiesJson,
  buildModelConfigJson,
  emptyModelDraft,
  extractContextWindow,
  extractDefaultVariantFromModel,
  extractVariantNamesFromModel,
  toEditableModel,
  toModelDraft,
} from '@/features/ai/ai-model-draft-codec'
import type { AgentModelDTO } from '@/shared/api/contracts'

function draft(overrides: Partial<ModelDraft> = {}): ModelDraft {
  return {
    ...emptyModelDraft(),
    providerId: 'provider-1',
    name: 'model-a',
    ...overrides,
  }
}

function model(config: unknown): AgentModelDTO {
  return {
    id: 'model-1',
    providerId: 'provider-1',
    providerName: 'provider-a',
    name: 'model-a',
    description: 'desc',
    capabilitiesJson: '["TEXT","TOOLS","THINKING","VISION","AUDIO"]',
    configJson: JSON.stringify(config),
    createTime: null,
    updateTime: null,
  }
}

describe('ai-model-draft-codec', () => {
  /** A full persisted config must round-trip through every nested schema field used by the form. */
  it('reads nested limit, abilities, pricing, defaultVariant, and variant fields', () => {
    const source = model({
      limit: { context: 200000, output: 16000 },
      abilities: {
        tools: false,
        reasoning: true,
        modalities: { input: ['TEXT', 'IMAGE', 'AUDIO'], output: ['TEXT'] },
      },
      pricing: {
        currency: 'CNY',
        pricingTier: 'batch',
        serviceTier: 'priority',
        serviceTierMultiplier: 1.25,
        version: '2026-07',
        inputPerMillionTokens: 1.1,
        outputPerMillionTokens: 2.2,
        cacheReadPerMillionTokens: 0.3,
        cacheWritePerMillionTokens: 0.4,
        cacheWriteLongPerMillionTokens: 0.5,
        reasoningPerMillionTokens: 3.6,
      },
      defaultVariant: 'quality',
      variants: [
        {
          id: 'quality',
          reasoningEffort: 'high',
          maxOutputTokens: 4096,
          temperature: 0.4,
          topP: 0.8,
          topK: 20,
          frequencyPenalty: 0.1,
          presencePenalty: 0.2,
          stopSequences: ['END', 'STOP'],
        },
      ],
    })

    expect(toModelDraft(source)).toMatchObject({
      providerId: 'provider-1',
      name: 'model-a',
      contextWindow: '200000',
      maxOutputTokens: '16000',
      tools: false,
      reasoning: true,
      inputModalities: ['TEXT', 'IMAGE', 'AUDIO'],
      outputModalities: ['TEXT'],
      defaultVariant: 'quality',
      variants: [
        {
          name: 'quality',
          reasoningEffort: 'high',
          maxOutputTokens: '4096',
          temperature: '0.4',
          topP: '0.8',
          topK: '20',
          frequencyPenalty: '0.1',
          presencePenalty: '0.2',
          stopSequences: 'END, STOP',
        },
      ],
      pricing: {
        currency: 'CNY',
        serviceTierMultiplier: '1.25',
        reasoningPerMillionTokens: '3.6',
      },
    })
    expect(extractContextWindow(source)).toBe(200000)
    expect(extractVariantNamesFromModel(source)).toEqual(['quality'])
    expect(extractDefaultVariantFromModel(source)).toBe('quality')
  })

  /** Serialization proves the API payload uses only the new nested config and derives capabilities. */
  it('writes the new schema, derives capabilities, and omits empty optional variant fields', () => {
    const base = emptyModelDraft().variants[0]
    const input = draft({
      description: ' model desc ',
      contextWindow: '200000',
      maxOutputTokens: '16000',
      tools: true,
      reasoning: true,
      inputModalities: ['TEXT', 'IMAGE', 'AUDIO', 'VIDEO'],
      defaultVariant: 'quality',
      variants: [
        {
          ...base,
          name: 'quality',
          reasoningEffort: 'high',
          maxOutputTokens: '4096',
          temperature: '0.4',
          topP: '0.8',
          topK: '20',
          frequencyPenalty: '0.1',
          presencePenalty: '0.2',
          stopSequences: 'END, STOP',
        },
        {
          ...base,
          id: 'draft-2',
          name: 'provider-defaults',
          reasoningEffort: 'off',
        },
      ],
    })

    const editable = toEditableModel(input)
    expect(editable.capabilitiesJson).toBe('["TEXT","TOOLS","THINKING","VISION","AUDIO"]')
    const config = JSON.parse(editable.configJson)
    expect(config).toEqual({
      limit: { context: 200000, output: 16000 },
      abilities: {
        tools: true,
        reasoning: true,
        modalities: {
          input: ['TEXT', 'IMAGE', 'AUDIO', 'VIDEO'],
          output: ['TEXT'],
        },
      },
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
      variants: [
        {
          id: 'quality',
          reasoningEffort: 'high',
          maxOutputTokens: 4096,
          temperature: 0.4,
          topP: 0.8,
          frequencyPenalty: 0.1,
          presencePenalty: 0.2,
          topK: 20,
          stopSequences: ['END', 'STOP'],
        },
        { id: 'provider-defaults' },
      ],
    })
    expect(config).not.toHaveProperty('thinkingLevel')
    expect(config).not.toHaveProperty('contextWindow')
    expect(config).not.toHaveProperty('maxOutputTokens')
  })

  /** Disabling reasoning must prevent stale hidden reasoning-effort values from reaching providers. */
  it('omits reasoningEffort when reasoning is disabled', () => {
    const base = emptyModelDraft().variants[0]
    const input = draft({
      reasoning: false,
      variants: [{ ...base, reasoningEffort: 'high' }],
    })

    expect(JSON.parse(buildModelConfigJson(input)).variants).toEqual([{ id: 'medium' }])
    expect(buildModelCapabilitiesJson(input)).toBe('["TEXT","TOOLS"]')
  })

  /** Invalid variant/default relationships are rejected before issuing a mutation request. */
  it('rejects empty, duplicate, unmatched, and invalid optional variants', () => {
    const base = emptyModelDraft().variants[0]
    expect(() => buildModelConfigJson(draft({ variants: [] }))).toThrow('at least one variant')
    expect(() =>
      buildModelConfigJson(
        draft({ variants: [base, { ...base, id: 'draft-2' }] }),
      ),
    ).toThrow('duplicate variant id')
    expect(() => buildModelConfigJson(draft({ defaultVariant: 'missing' }))).toThrow(
      'defaultVariant must match',
    )
    expect(() =>
      buildModelConfigJson(
        draft({ variants: [{ ...base, temperature: 'not-a-number' }] }),
      ),
    ).toThrow('temperature must be a number')
    expect(() =>
      buildModelConfigJson(
        draft({ variants: [{ ...base, maxOutputTokens: '9000' }] }),
      ),
    ).toThrow('exceeds model maxOutputTokens')
  })
})
