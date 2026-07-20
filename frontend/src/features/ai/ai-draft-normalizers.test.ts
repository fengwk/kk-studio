import { describe, expect, it } from 'vitest'
import { emptyAgentDraft } from '@/features/ai/ai-agent-draft-codec'
import type { AgentDraft } from '@/features/ai/ai-console-types'
import {
  applyAgentModelSelection,
  normalizeAgentDraftDefaultVariant,
  normalizeModelDraftDefaultVariant,
  variantOptionsFromDraft,
  variantOptionsFromModel,
} from '@/features/ai/ai-draft-normalizers'
import { emptyModelDraft } from '@/features/ai/ai-model-draft-codec'
import { newVariantDraft } from '@/features/ai/ai-resource-draft-primitives'
import type { AgentModelDTO } from '@/shared/api/contracts'

function modelConfig(overrides: Record<string, unknown> = {}) {
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

describe('ai-draft-normalizers', () => {
  /** Variant options must use config.variants[].id and preserve first-seen ordering. */
  it('builds variant options from drafts and models', () => {
    expect(
      variantOptionsFromDraft(
        [
          newVariantDraft({ name: ' default ' }),
          newVariantDraft({ name: 'creative' }),
          newVariantDraft({ name: 'creative' }),
        ],
        'fallback',
      ),
    ).toEqual(['default', 'creative'])

    expect(variantOptionsFromDraft([newVariantDraft({ name: '   ' })], ' fallback ')).toEqual([
      'fallback',
    ])

    expect(
      variantOptionsFromModel(
        model({
          config: modelConfig({
            defaultVariant: 'creative',
            variants: [{ id: 'creative' }, { id: 'precise' }],
          }),
        }) as AgentModelDTO,
      ),
    ).toEqual(['creative', 'precise'])
    expect(
      variantOptionsFromModel(
        model({
          config: modelConfig({
            variants: [{ id: 'creative' }, { id: 'precise' }],
          }),
        }) as AgentModelDTO,
      ),
    ).toEqual(['creative', 'precise'])
    expect(variantOptionsFromModel(model({ config: null }) as AgentModelDTO)).toEqual(['medium'])
  })

  /** Invalid selected variants fall back to each model config's effective default. */
  it('normalizes model and agent default variants', () => {
    const normalizedModel = normalizeModelDraftDefaultVariant({
      ...emptyModelDraft(),
      defaultVariant: 'legacy',
      variants: [newVariantDraft({ name: 'default' }), newVariantDraft({ name: 'creative' })],
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
    expect(normalizedAgent.variant).toBe('precise')

    expect(
      normalizeAgentDraftDefaultVariant(
        {
          ...emptyAgentDraft(),
          modelId: 'missing',
          variant: '   ',
        },
        [],
      ).variant,
    ).toBe('default')
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
      variant: 'creative',
    })
    expect(applyAgentModelSelection(draft, 'unknown', models)).toMatchObject({
      modelId: 'unknown',
    })
  })
})

function model(overrides: Partial<AgentModelDTO>): AgentModelDTO {
  return {
    id: 'model-1',
    providerId: 'provider-1',
    name: 'MiniMax-M2.7',
    description: null,
    config: modelConfig() as AgentModelDTO['config'],
    createTime: '2026-06-20T02:00:00',
    updateTime: '2026-06-20T02:00:00',
    ...overrides,
  } as AgentModelDTO
}
