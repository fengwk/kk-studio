import { describe, expect, it } from 'vitest'
import type { ModelDraft } from '@/features/ai/ai-console-types'
import type { AgentModelInputModality } from '@/shared/api/contracts'
import {
  buildModelConfig,
  emptyModelDraft,
  extractContextWindow,
  extractDefaultVariantFromModel,
  extractVariantNamesFromModel,
  toEditableModel,
  toEditableModelUpdate,
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

function fullConfig() {
  return {
    limit: { context: 200000, output: 16000 },
    abilities: {
      tools: false,
      reasoning: true,
      inputModalities: ['TEXT', 'IMAGE', 'AUDIO'],
    },
    pricing: {
      currency: 'USD',
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
  }
}

function model(configOverrides?: Record<string, unknown>): AgentModelDTO {
  return {
    id: 'model-1',
    providerId: 'provider-1',
    name: 'model-a',
    description: 'desc',
    config: (configOverrides ?? fullConfig()) as AgentModelDTO['config'],
    createTime: null,
    updateTime: null,
  }
}

describe('ai-model-draft-codec', () => {
  /** A full persisted config must round-trip through every nested schema field used by the form. */
  it('reads nested limit, abilities, pricing, defaultVariant, and variant fields', () => {
    const source = model()

    expect(toModelDraft(source)).toMatchObject({
      providerId: 'provider-1',
      name: 'model-a',
      contextWindow: '200000',
      maxOutputTokens: '16000',
      tools: false,
      reasoning: true,
      inputModalities: new Set(['TEXT', 'IMAGE', 'AUDIO']),
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
        currency: 'USD',
        serviceTierMultiplier: '1.25',
        reasoningPerMillionTokens: '3.6',
      },
    })
    expect(extractContextWindow(source)).toBe(200000)
    expect(extractVariantNamesFromModel(source)).toEqual(['quality'])
    expect(extractDefaultVariantFromModel(source)).toBe('quality')
  })

  /** Empty or missing config returns a structured default draft. */
  it('falls back to defaults when persisted config is null', () => {
    const draft = toModelDraft({
      ...model({ config: null }),
    })
    expect(draft.contextWindow).toBe('128000')
    expect(draft.maxOutputTokens).toBe('8192')
    expect(draft.tools).toBe(true)
    expect(draft.inputModalities).toEqual(
      new Set<AgentModelInputModality>(['TEXT', 'IMAGE', 'AUDIO', 'VIDEO', 'DOCUMENT']),
    )
    expect(draft.defaultVariant).toBe('medium')
    expect(draft.variants).toHaveLength(1)
  })

  /** Serialization uses the structured config object and omits empty optional variant fields. */
  it('writes the structured config and omits empty optional variant fields', () => {
    const base = emptyModelDraft().variants[0]
    const input = draft({
      description: ' model desc ',
      contextWindow: '200000',
      maxOutputTokens: '16000',
      tools: true,
      reasoning: true,
      inputModalities: new Set(['TEXT', 'IMAGE', 'AUDIO', 'VIDEO']),
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
          name: 'provider-defaults',
          reasoningEffort: 'off',
        },
      ],
    })

    const editable = toEditableModel(input)
    const config = buildModelConfig(input)
    expect(config).toEqual({
      limit: { context: 200000, output: 16000 },
      abilities: {
        tools: true,
        reasoning: true,
        inputModalities: ['TEXT', 'IMAGE', 'AUDIO', 'VIDEO'],
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
    expect(editable.config).toEqual(config)
  })

  /** Disabling reasoning must prevent stale hidden reasoning-effort values from reaching providers. */
  it('omits reasoningEffort when reasoning is disabled', () => {
    const base = emptyModelDraft().variants[0]
    const input = draft({
      reasoning: false,
      variants: [{ ...base, reasoningEffort: 'high' }],
    })

    const config = buildModelConfig(input)
    expect(config.variants).toEqual([{ id: 'medium' }])
  })

  /** Invalid variant/default relationships are rejected before issuing a mutation request. */
  it('rejects empty, duplicate, unmatched, and invalid optional variants', () => {
    const base = emptyModelDraft().variants[0]
    expect(() => buildModelConfig(draft({ variants: [] }))).toThrow(/at least one variant/i)
    expect(() =>
      buildModelConfig(
        draft({ variants: [base, { ...base, name: base.name }] }),
      ),
    ).toThrow('duplicate variant id')
    expect(() => buildModelConfig(draft({ defaultVariant: 'missing' }))).toThrow(
      /defaultVariant must match/i,
    )
    expect(() =>
      buildModelConfig(
        draft({ variants: [{ ...base, temperature: 'not-a-number' }] }),
      ),
    ).toThrow('temperature must be a number')
    expect(() =>
      buildModelConfig(
        draft({ variants: [{ ...base, maxOutputTokens: '9000' }] }),
      ),
    ).toThrow('exceeds model maxOutputTokens')
  })

  /** Create update DTOs are built without string-encoded JSON. */
  it('produces structured create / update payloads', () => {
    const input = draft({ name: 'stub', contextWindow: '4096', maxOutputTokens: '512' })
    const config = buildModelConfig(input)
    const create = toEditableModel(input)
    expect(create.providerId).toBe('provider-1')
    expect(create.name).toBe('stub')
    expect(create.config).toEqual(config)
    expect(toEditableModelUpdate(input)).not.toHaveProperty('providerId')
  })
})
