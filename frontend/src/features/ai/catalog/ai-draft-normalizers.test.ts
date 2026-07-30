import { describe, expect, it } from 'vitest'
import { emptyAgentDraft } from '@/features/ai/catalog/ai-agent-draft-codec'
import type { AgentDraft } from '@/features/ai/catalog/ai-console-types'
import {
  applyAgentModelSelection,
  normalizeAgentDraftDefaultVariant,
  normalizeAgentDraftSelection,
  normalizeModelDraftDefaultVariant,
  normalizeModelDraftProvider,
  variantOptionsFromDraft,
  variantOptionsFromModel,
} from '@/features/ai/catalog/ai-draft-normalizers'
import { emptyModelDraft } from '@/features/ai/catalog/ai-model-draft-codec'
import { newVariantDraft } from '@/features/ai/catalog/ai-resource-draft-primitives'
import type {
  AgentModelConfigDTO,
  AgentModelDTO,
  AgentProviderDTO,
} from '@/shared/api/contracts'

function modelConfig(overrides: Partial<AgentModelConfigDTO> = {}): AgentModelConfigDTO {
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
    ...overrides,
  }
}

function model(overrides: Partial<AgentModelDTO>): AgentModelDTO {
  return {
    id: 'model-1',
    providerId: 'provider-1',
    name: 'MiniMax-M2.7',
    description: null,
    config: modelConfig(),
    version: '1',
    createTime: '2026-06-20T02:00:00',
    updateTime: '2026-06-20T02:00:00',
    ...overrides,
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

describe('ai-draft-normalizers', () => {
  /** Variant options must use config.variants[].id and preserve first-seen ordering. */
  it('builds variant options from drafts and models', () => {
    expect(
      variantOptionsFromDraft(
        [
          newVariantDraft({ id: ' default ' }),
          newVariantDraft({ id: 'creative' }),
          newVariantDraft({ id: 'creative' }),
        ],
      ),
    ).toEqual(['default', 'creative'])

    expect(variantOptionsFromDraft([newVariantDraft({ id: '   ' })])).toEqual([])

    expect(
      variantOptionsFromModel(
        model({
          config: modelConfig({
            defaultVariant: 'creative',
            variants: [{ id: 'creative' }, { id: 'precise' }],
          }),
        }),
      ),
    ).toEqual(['creative', 'precise'])
    expect(
      variantOptionsFromModel(
        model({
          config: modelConfig({
            variants: [{ id: 'creative' }, { id: 'precise' }],
          }),
        }),
      ),
    ).toEqual(['creative', 'precise'])
  })

  /** Invalid selected variants fall back to each model config's effective default. */
  it('normalizes model and agent default variants', () => {
    const normalizedModel = normalizeModelDraftDefaultVariant({
      ...emptyModelDraft(),
      defaultVariant: 'legacy',
      variants: [newVariantDraft({ id: 'default' }), newVariantDraft({ id: 'creative' })],
    })
    expect(normalizedModel.defaultVariant).toBe('default')

    const normalizedAgent = normalizeAgentDraftDefaultVariant(
      {
        ...emptyAgentDraft(),
        name: 'assistant',
        modelId: 'model-sonnet',
        variant: 'legacy',
      },
      [
        model({
          id: 'model-sonnet',
          name: 'Claude-Sonnet-4.5',
          config: {
            ...modelConfig(),
            defaultVariant: 'precise',
            variants: [{ id: 'creative' }, { id: 'precise' }],
          },
        }),
      ],
    )
    // Invalid override is cleared so the model default is used.
    expect(normalizedAgent.variant).toBe('')

    expect(
      normalizeAgentDraftDefaultVariant(
        {
          ...emptyAgentDraft(),
          modelId: 'missing',
          variant: '   ',
        },
        [],
      ).variant,
    ).toBe('')
  })

  /** Changing models also changes the Agent variant to the new model's defaultVariant. */
  it('syncs agent variant when model selection changes', () => {
    const draft: AgentDraft = {
      ...emptyAgentDraft(),
      name: 'assistant',
      modelId: 'model-minimax',
      variant: 'default',
    }
    const models = [
      model({
        id: 'model-minimax',
        name: 'MiniMax-M2.7',
        config: modelConfig({
          defaultVariant: 'default',
          variants: [{ id: 'default' }],
        }),
      }),
      model({
        id: 'model-sonnet',
        name: 'Claude-Sonnet-4.5',
        config: modelConfig({
          defaultVariant: 'creative',
          variants: [{ id: 'creative' }, { id: 'precise' }],
        }),
      }),
    ]

    expect(applyAgentModelSelection(draft, 'model-sonnet', models)).toMatchObject({
      modelId: 'model-sonnet',
      variant: '',
    })
    expect(applyAgentModelSelection(draft, 'unknown', models)).toMatchObject({
      modelId: 'unknown',
    })
  })

  /** Provider normalization preserves the existing draft's provider when it matches. */
  it('preserves a valid existing provider on the draft', () => {
    const draft = { ...emptyModelDraft(), providerId: 'provider-1' }
    const providers = [provider('provider-1', 'minimax'), provider('provider-2', 'anthropic')]
    const result = normalizeModelDraftProvider(draft, providers)
    expect(result).toBe(draft)
  })

  /** When draft provider is missing, fall back to preferred id then to providers[0]. */
  it('falls back to preferred provider id, then to first provider', () => {
    const draft = { ...emptyModelDraft(), providerId: '' }
    const providers = [provider('provider-1', 'minimax'), provider('provider-2', 'anthropic')]

    const withPreferred = normalizeModelDraftProvider(draft, providers, 'provider-2')
    expect(withPreferred.providerId).toBe('provider-2')

    const withFallback = normalizeModelDraftProvider(draft, providers, 'missing-id')
    expect(withFallback.providerId).toBe('provider-1')

    const withNoProviders = normalizeModelDraftProvider(draft, [])
    expect(withNoProviders.providerId).toBe('')
  })

  /** normalizeAgentDraftSelection picks preferred, then fallback model[0]. */
  it('picks preferred model id then first available model', () => {
    const draft: AgentDraft = { ...emptyAgentDraft(), modelId: 'gone', variant: '' }
    const models = [
      model({ id: 'model-minimax', config: modelConfig({ defaultVariant: 'default', variants: [{ id: 'default' }] }) }),
      model({ id: 'model-sonnet', config: modelConfig({ defaultVariant: 'creative', variants: [{ id: 'creative' }] }) }),
    ]

    const preferred = normalizeAgentDraftSelection(draft, models, 'model-sonnet')
    expect(preferred.modelId).toBe('model-sonnet')
    expect(preferred.variant).toBe('')

    const fallback = normalizeAgentDraftSelection(draft, models)
    expect(fallback.modelId).toBe('model-minimax')
    expect(fallback.variant).toBe('')

    const empty = normalizeAgentDraftSelection(draft, [])
    expect(empty.modelId).toBe('gone')
  })
})