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
} from '@/shared/api/contracts/ai-catalog'

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
    providerName: 'minimax',
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
        model: 'anthropic/Claude-Sonnet-4.5',
        variant: 'legacy',
      },
      [
        model({
          providerName: 'anthropic',
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
          model: 'missing',
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
      model: 'minimax/MiniMax-M2.7',
      variant: 'default',
    }
    const models = [
      model({
        providerName: 'minimax',
        name: 'MiniMax-M2.7',
        config: modelConfig({
          defaultVariant: 'default',
          variants: [{ id: 'default' }],
        }),
      }),
      model({
        providerName: 'anthropic',
        name: 'Claude-Sonnet-4.5',
        config: modelConfig({
          defaultVariant: 'creative',
          variants: [{ id: 'creative' }, { id: 'precise' }],
        }),
      }),
    ]

    expect(applyAgentModelSelection(draft, 'anthropic/Claude-Sonnet-4.5', models)).toMatchObject({
      model: 'anthropic/Claude-Sonnet-4.5',
      variant: '',
    })
    expect(applyAgentModelSelection(draft, 'unknown', models)).toMatchObject({
      model: 'unknown',
    })
  })

  /** Provider normalization preserves the existing draft's provider when it matches. */
  it('preserves a valid existing provider on the draft', () => {
    const draft = { ...emptyModelDraft(), providerName: 'minimax' }
    const providers = [provider('provider-1', 'minimax'), provider('provider-2', 'anthropic')]
    const result = normalizeModelDraftProvider(draft, providers)
    expect(result).toBe(draft)
  })

  /** When draft provider is missing, fall back to preferred name then to providers[0]. */
  it('falls back to preferred provider name, then to first provider', () => {
    const draft = { ...emptyModelDraft(), providerName: '' }
    const providers = [provider('provider-1', 'minimax'), provider('provider-2', 'anthropic')]

    const withPreferred = normalizeModelDraftProvider(draft, providers, 'anthropic')
    expect(withPreferred.providerName).toBe('anthropic')

    const withFallback = normalizeModelDraftProvider(draft, providers, 'missing-name')
    expect(withFallback.providerName).toBe('minimax')

    const withNoProviders = normalizeModelDraftProvider(draft, [])
    expect(withNoProviders.providerName).toBe('')
  })

  /** normalizeAgentDraftSelection picks preferred, then fallback model[0]. */
  it('picks preferred model id then first available model', () => {
    const draft: AgentDraft = { ...emptyAgentDraft(), model: 'gone', variant: '' }
    const models = [
      model({ providerName: 'minimax', name: 'MiniMax-M2.7', config: modelConfig({ defaultVariant: 'default', variants: [{ id: 'default' }] }) }),
      model({ providerName: 'anthropic', name: 'Claude-Sonnet-4.5', config: modelConfig({ defaultVariant: 'creative', variants: [{ id: 'creative' }] }) }),
    ]

    const preferred = normalizeAgentDraftSelection(draft, models, 'anthropic/Claude-Sonnet-4.5')
    expect(preferred.model).toBe('anthropic/Claude-Sonnet-4.5')
    expect(preferred.variant).toBe('')

    const fallback = normalizeAgentDraftSelection(draft, models)
    expect(fallback.model).toBe('minimax/MiniMax-M2.7')
    expect(fallback.variant).toBe('')

    const empty = normalizeAgentDraftSelection(draft, [])
    expect(empty.model).toBe('gone')
  })
})