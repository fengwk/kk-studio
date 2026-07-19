import { describe, expect, it } from 'vitest'
import {
  applyAgentModelSelection,
  normalizeAgentDraftDefaultVariant,
  normalizeModelDraftDefaultVariant,
  variantOptionsFromDraft,
  variantOptionsFromModel,
} from '@/features/ai/ai-draft-normalizers'
import type { AgentDraft } from '@/features/ai/ai-console-types'
import type { AgentModelDTO } from '@/shared/api/contracts'
import { emptyAgentDraft } from '@/features/ai/ai-agent-draft-codec'

describe('ai-draft-normalizers', () => {
  it('builds variant options from drafts and models', () => {
    expect(
      variantOptionsFromDraft(
        [
          { id: 'variant-1', name: ' default ', temperature: '', maxOutputTokens: '', extras: [] },
          { id: 'variant-2', name: 'creative', temperature: '', maxOutputTokens: '', extras: [] },
          { id: 'variant-3', name: 'creative', temperature: '', maxOutputTokens: '', extras: [] },
        ],
        'fallback',
      ),
    ).toEqual(['default', 'creative'])

    expect(variantOptionsFromDraft([{ id: 'variant-4', name: '   ', temperature: '', maxOutputTokens: '', extras: [] }], ' fallback ')).toEqual([
      'fallback',
    ])

    expect(variantOptionsFromModel(model({ defaultVariant: 'creative', variantsJson: '[{"name":"creative"},{"name":"precise"}]' }))).toEqual([
      'creative',
      'precise',
    ])
    expect(variantOptionsFromModel(model({ defaultVariant: 'fallback', variantsJson: '{"name":"broken"}' }))).toEqual(['fallback'])
    expect(variantOptionsFromModel(model({ defaultVariant: 'fallback', variantsJson: '[1,{"name":"precise"}]' }))).toEqual(['precise'])
    expect(variantOptionsFromModel(model({ defaultVariant: ' fallback ', variantsJson: '{broken' }))).toEqual(['fallback'])
    expect(variantOptionsFromModel(model({ defaultVariant: null, variantsJson: null }))).toEqual(['default'])
  })

  it('normalizes model and agent default variants', () => {
    const normalizedModel = normalizeModelDraftDefaultVariant({
      provider: 'minimax',
      name: 'MiniMax-M2.7',
      description: '',
      defaultVariant: 'legacy',
      variants: [
        { id: 'variant-1', name: 'default', temperature: '', maxOutputTokens: '', extras: [] },
        { id: 'variant-2', name: 'creative', temperature: '', maxOutputTokens: '', extras: [] },
      ],
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
          providerName: 'anthropic',
          name: 'Claude-Sonnet-4.5',
          defaultVariant: 'precise',
          variantsJson: '[{"name":"creative"},{"name":"precise"}]',
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
        providerName: 'minimax',
        name: 'MiniMax-M2.7',
        defaultVariant: 'default',
        variantsJson: '[{"name":"default"}]',
      }),
      model({
        id: 'model-sonnet',
        providerName: 'anthropic',
        name: 'Claude-Sonnet-4.5',
        defaultVariant: 'creative',
        variantsJson: '[{"name":"creative"},{"name":"precise"}]',
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
    providerName: 'minimax',
    name: 'MiniMax-M2.7',
    description: null,
    defaultVariant: 'default',
    variantsJson: '[{"name":"default"}]',
    createTime: '2026-06-20T02:00:00',
    updateTime: '2026-06-20T02:00:00',
    ...overrides,
  }
}
