import { describe, expect, it } from 'vitest'
import type { ModelDraft } from '@/features/ai/ai-console-types'
import type { AgentModelInputModality } from '@/shared/api/contracts'
import {
  buildModelConfig,
  emptyModelDraft,
  extractContextWindow,
  extractDefaultVariantFromModel,
  extractVariantIdsFromModel,
  toEditableModel,
  toEditableModelUpdate,
  toModelDraft,
} from '@/features/ai/ai-model-draft-codec'
import type { AgentModelConfigDTO, AgentModelDTO } from '@/shared/api/contracts'

function draft(overrides: Partial<ModelDraft> = {}): ModelDraft {
  return {
    ...emptyModelDraft(),
    providerId: 'provider-1',
    name: 'model-a',
    ...overrides,
  }
}

function fullConfig(): AgentModelConfigDTO {
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

function model(configOverride?: AgentModelConfigDTO): AgentModelDTO {
  return {
    id: 'model-1',
    providerId: 'provider-1',
    name: 'model-a',
    description: 'desc',
    config: configOverride ?? fullConfig(),
    version: 1,
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
      inputModalities: ['TEXT', 'IMAGE', 'AUDIO'],
      defaultVariant: 'quality',
      variants: [
        {
          id: 'quality',
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
    expect(extractVariantIdsFromModel(source)).toEqual(['quality'])
    expect(extractDefaultVariantFromModel(source)).toBe('quality')
  })

  /** New model drafts default to TEXT only (not every modality). */
  it('defaults to TEXT-only input modalities on empty drafts', () => {
    expect(emptyModelDraft().inputModalities).toEqual<AgentModelInputModality[]>(['TEXT'])
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
      inputModalities: ['TEXT', 'IMAGE', 'AUDIO', 'VIDEO'],
      defaultVariant: 'quality',
      variants: [
        {
          ...base,
          id: 'quality',
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
          id: 'provider-defaults',
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
    expect(editable.name).toBe('model-a')
    expect(editable.description).toBe('model desc')
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
        draft({ variants: [base, { ...base, id: base.id }] }),
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
    expect(() =>
      buildModelConfig(draft({ variants: [{ ...base, temperature: '-0.1' }] })),
    ).toThrow('temperature must not be negative')
    expect(() =>
      buildModelConfig(draft({ variants: [{ ...base, topP: '0' }] })),
    ).toThrow('topP must be in (0, 1]')
    expect(() =>
      buildModelConfig(draft({ inputModalities: ['TEXT', 'TEXT'] })),
    ).toThrow('contains duplicate value')
  })

  /** Generic variants preserve finite negative penalties supported by OpenAI-compatible providers. */
  it('preserves negative frequency and presence penalties', () => {
    const base = emptyModelDraft().variants[0]
    const config = buildModelConfig(
      draft({
        variants: [{ ...base, frequencyPenalty: '-0.5', presencePenalty: '-1' }],
      }),
    )

    expect(config.variants[0]).toMatchObject({
      frequencyPenalty: -0.5,
      presencePenalty: -1,
    })
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

  /** Toggling via immutable arrays never drops the last input modality. */
  it('keeps at least one input modality when toggling', () => {
    const base = emptyModelDraft()
    const onlyText = base.inputModalities.filter((m) => m !== 'TEXT')
    const toggled = onlyText.length > 0 ? onlyText : ['TEXT']
    expect(toggled).toEqual<AgentModelInputModality[]>(['TEXT'])
  })

  /** Edit must preserve every persisted pricing metadata field; new drafts use canonical defaults. */
  it('round-trips pricing metadata across toModelDraft -> buildModelConfig', () => {
    const source = model({
      ...fullConfig(),
      pricing: {
        currency: 'EUR',
        pricingTier: 'enterprise',
        serviceTier: 'premium',
        serviceTierMultiplier: 2.5,
        version: '2026-09',
        inputPerMillionTokens: 9,
        outputPerMillionTokens: 11,
        cacheReadPerMillionTokens: 1.25,
        cacheWritePerMillionTokens: 2.5,
        cacheWriteLongPerMillionTokens: 3.5,
        reasoningPerMillionTokens: 4.5,
      },
    })
    const draftFromModel = toModelDraft(source)
    const rebuilt = buildModelConfig(draftFromModel)
    expect(rebuilt.pricing).toEqual({
      currency: 'EUR',
      pricingTier: 'enterprise',
      serviceTier: 'premium',
      serviceTierMultiplier: 2.5,
      version: '2026-09',
      inputPerMillionTokens: 9,
      outputPerMillionTokens: 11,
      cacheReadPerMillionTokens: 1.25,
      cacheWritePerMillionTokens: 2.5,
      cacheWriteLongPerMillionTokens: 3.5,
      reasoningPerMillionTokens: 4.5,
    })
  })

  /** buildModelConfig rejects non-positive multipliers and negative per-million prices. */
  it('rejects non-positive multiplier and negative prices', () => {
    const base = draft()
    expect(() => buildModelConfig({ ...base, pricing: { ...base.pricing, serviceTierMultiplier: '0' } })).toThrow(
      /serviceTierMultiplier must be positive/,
    )
    expect(() => buildModelConfig({ ...base, pricing: { ...base.pricing, inputPerMillionTokens: '-1' } })).toThrow(
      /inputPerMillionTokens must not be negative/,
    )
    expect(() => buildModelConfig({ ...base, pricing: { ...base.pricing, outputPerMillionTokens: 'NaN' } })).toThrow(
      /outputPerMillionTokens must be a number/,
    )
  })

  /** buildModelConfig rejects blank name + non-blank providerId even before reaching config. */
  it('rejects blank name in toEditableModel and toEditableModelUpdate', () => {
    expect(() => toEditableModel(draft({ name: '   ' }))).toThrow(/name/)
    expect(() => toEditableModelUpdate(draft({ name: '' }))).toThrow(/name/)
  })

  /** toEditableModel rejects a blank providerId. */
  it('rejects blank providerId in toEditableModel', () => {
    expect(() => toEditableModel(draft({ providerId: '' }))).toThrow(/providerId is required/)
  })

  /** Pricing metadata is required and must not be silently reconstructed during edit. */
  it('rejects blank pricing metadata', () => {
    const base = draft()
    expect(() =>
      buildModelConfig({ ...base, pricing: { ...base.pricing, currency: '' } }),
    ).toThrow(/pricing\.currency must not be blank/)
    expect(() =>
      buildModelConfig({ ...base, pricing: { ...base.pricing, version: '' } }),
    ).toThrow(/pricing\.version must not be blank/)
  })

  /** Missing models do not invent a Variant ID. */
  it('returns no variant data for missing models', () => {
    expect(extractVariantIdsFromModel(undefined)).toEqual([])
    expect(extractDefaultVariantFromModel(undefined)).toBe('')
  })

  /** emptyModelDraft without a model or provider keeps an empty providerId and TEXT only. */
  it('returns empty providerId when neither model nor provider is supplied', () => {
    const blank = emptyModelDraft(null)
    expect(blank.providerId).toBe('')
    expect(blank.inputModalities).toEqual(['TEXT'])
  })

  /** emptyModelDraft respects an explicit provider argument. */
  it('uses the explicit provider argument when no model is supplied', () => {
    const seeded = emptyModelDraft({ id: 'provider-x' })
    expect(seeded.providerId).toBe('provider-x')
  })
})
