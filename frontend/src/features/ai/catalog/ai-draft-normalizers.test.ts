import { describe, expect, it } from 'vitest'
import { emptyAgentDraft } from '@/features/ai/catalog/ai-agent-draft-codec'
import type { AgentDraft } from '@/features/ai/catalog/ai-console-types'
import {
  applyAgentModelSelection,
  normalizeCreateAgentDraftSelection,
  normalizeCreateModelDraftProvider,
  normalizeAgentDraftDefaultVariant,
  normalizeEditModelDraftProvider,
  normalizeModelDraftDefaultVariant,
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
  /** variant 选项必须使用 config.variants[].id 并保留首次出现的顺序。 */
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

  /** 无效的已选 variant 回退到每个 model config 的有效默认值。 */
  it('normalizes model and agent default variants', () => {
    const normalizedModel = normalizeModelDraftDefaultVariant({
      ...emptyModelDraft(),
      defaultVariant: 'missing',
      variants: [newVariantDraft({ id: 'default' }), newVariantDraft({ id: 'creative' })],
    })
    expect(normalizedModel.defaultVariant).toBe('default')

    const normalizedAgent = normalizeAgentDraftDefaultVariant(
      {
        ...emptyAgentDraft(),
        name: 'assistant',
        model: 'anthropic/Claude-Sonnet-4.5',
        variant: 'missing',
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
    // 清除无效覆盖，以便使用 model 默认值。
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

  /** 切换 model 时，Agent variant 也同步切换为新 model 的 defaultVariant。 */
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

  /** 新建模式下的 provider 标准化在现有草稿的 provider 匹配时予以保留。 */
  it('preserves a valid existing provider on the draft', () => {
    const draft = { ...emptyModelDraft(), providerName: 'minimax' }
    const providers = [provider('provider-1', 'minimax'), provider('provider-2', 'anthropic')]
    const result = normalizeCreateModelDraftProvider(draft, providers)
    expect(result).toBe(draft)
  })

  /** 新建模式可回退到首选名称，再回退到 providers[0]。 */
  it('falls back to preferred provider name, then to first provider', () => {
    const draft = { ...emptyModelDraft(), providerName: '' }
    const providers = [provider('provider-1', 'minimax'), provider('provider-2', 'anthropic')]

    const withPreferred = normalizeCreateModelDraftProvider(draft, providers, 'anthropic')
    expect(withPreferred.providerName).toBe('anthropic')

    const withFallback = normalizeCreateModelDraftProvider(draft, providers, 'missing-name')
    expect(withFallback.providerName).toBe('minimax')

    const withNoProviders = normalizeCreateModelDraftProvider(draft, [])
    expect(withNoProviders.providerName).toBe('')
  })

  it('keeps an edit model provider identity even when it is not loaded', () => {
    const draft = { ...emptyModelDraft(), providerName: 'first-loaded-provider' }
    const result = normalizeEditModelDraftProvider(draft, 'deleted-provider')

    expect(result.providerName).toBe('deleted-provider')
  })

  /** 新建模式优先选择首选 model，再回退到 model[0]。 */
  it('picks preferred model id then first available model', () => {
    const draft: AgentDraft = { ...emptyAgentDraft(), model: 'gone', variant: '' }
    const models = [
      model({ providerName: 'minimax', name: 'MiniMax-M2.7', config: modelConfig({ defaultVariant: 'default', variants: [{ id: 'default' }] }) }),
      model({ providerName: 'anthropic', name: 'Claude-Sonnet-4.5', config: modelConfig({ defaultVariant: 'creative', variants: [{ id: 'creative' }] }) }),
    ]

    const preferred = normalizeCreateAgentDraftSelection(draft, models, 'anthropic/Claude-Sonnet-4.5')
    expect(preferred.model).toBe('anthropic/Claude-Sonnet-4.5')
    expect(preferred.variant).toBe('')

    const fallback = normalizeCreateAgentDraftSelection(draft, models)
    expect(fallback.model).toBe('minimax/MiniMax-M2.7')
    expect(fallback.variant).toBe('')

    const empty = normalizeCreateAgentDraftSelection(draft, [])
    expect(empty.model).toBe('gone')
  })

  it('keeps the current Agent model and variant when the model is off-page', () => {
    const draft: AgentDraft = {
      ...emptyAgentDraft(),
      model: 'loaded/old-model',
      variant: 'persisted-override',
    }
    const unrelatedModel = model({
      providerName: 'unrelated',
      name: 'different-model',
      config: modelConfig({
        variants: [{ id: 'unrelated-variant' }],
        defaultVariant: 'unrelated-variant',
      }),
    })

    const result = normalizeAgentDraftDefaultVariant(draft, [unrelatedModel])

    expect(result.model).toBe('loaded/old-model')
    expect(result.variant).toBe('persisted-override')
  })

  it('clears an invalid variant override when the selected model is loaded', () => {
    const draft: AgentDraft = {
      ...emptyAgentDraft(),
      model: 'original/original-model',
      variant: 'unrelated-variant',
    }
    const originalModel = model({
      providerName: 'original',
      name: 'original-model',
      config: modelConfig({
        variants: [{ id: 'original-variant' }],
        defaultVariant: 'original-variant',
      }),
    })
    const unrelatedModel = model({
      providerName: 'unrelated',
      name: 'different-model',
      config: modelConfig({
        variants: [{ id: 'unrelated-variant' }],
        defaultVariant: 'unrelated-variant',
      }),
    })

    const result = normalizeAgentDraftDefaultVariant(draft, [unrelatedModel, originalModel])

    expect(result.model).toBe('original/original-model')
    expect(result.variant).toBe('')
  })
})