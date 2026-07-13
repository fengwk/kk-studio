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
        name: 'assistant',
        description: '',
        systemPrompt: '',
        defaultProvider: 'anthropic',
        defaultModel: 'Claude-Sonnet-4.5',
        defaultVariant: 'legacy',
        tools: [],
      },
      [
        model({
          providerName: 'anthropic',
          name: 'Claude-Sonnet-4.5',
          defaultVariant: 'precise',
          variantsJson: '[{"name":"creative"},{"name":"precise"}]',
        }),
      ],
    )
    expect(normalizedAgent.defaultVariant).toBe('precise')

    expect(
      normalizeAgentDraftDefaultVariant(
        {
          name: 'assistant',
          description: '',
          systemPrompt: '',
          defaultProvider: 'missing',
          defaultModel: 'missing',
          defaultVariant: '   ',
          tools: [],
        },
        [],
      ).defaultVariant,
    ).toBe('default')
  })

  it('syncs agent default variant when model selection changes', () => {
    const draft: AgentDraft = {
      name: 'assistant',
      description: '',
      systemPrompt: '',
      defaultProvider: 'minimax',
      defaultModel: 'MiniMax-M2.7',
      defaultVariant: 'default',
      tools: [],
    }
    const models = [
      model({
        providerName: 'minimax',
        name: 'MiniMax-M2.7',
        defaultVariant: 'default',
        variantsJson: '[{"name":"default"}]',
      }),
      model({
        providerName: 'anthropic',
        name: 'Claude-Sonnet-4.5',
        defaultVariant: 'creative',
        variantsJson: '[{"name":"creative"},{"name":"precise"}]',
      }),
    ]

    expect(applyAgentModelSelection(draft, 'anthropic/Claude-Sonnet-4.5', models)).toMatchObject({
      defaultProvider: 'anthropic',
      defaultModel: 'Claude-Sonnet-4.5',
      defaultVariant: 'creative',
    })
    expect(applyAgentModelSelection(draft, 'anthropic/Unknown', models)).toMatchObject({
      defaultProvider: 'anthropic',
      defaultModel: 'Unknown',
      defaultVariant: 'default',
    })
    expect(applyAgentModelSelection(draft, 'invalid', models)).toEqual(draft)
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
