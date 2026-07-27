import { describe, expect, it } from 'vitest'
import {
  filterAgents,
  filterChats,
  filterEnvironments,
  filterModels,
  filterProviders,
  formatBackendDate,
  includesSearch,
  naturalNameCompare,
  resourceTitle,
} from '@/features/ai/ai-console-utils'
import type { AgentModelView } from '@/features/ai/AgentModelView'
import type { ModelDraft } from '@/features/ai/ai-console-types'
import {
  emptyAgentDraft,
  emptyModelDraft,
  emptyProviderDraft,
  toAgentDraft,
  toEditableAgent,
  toEditableAgentUpdate,
  toEditableModel,
  toEditableModelUpdate,
  toEditableProvider,
  toEditableProviderUpdate,
  toModelDraft,
  toProviderDraft,
} from '@/features/ai/ai-resource-draft-codecs'
import type {
  AgentModelDTO,
  AgentModelConfigDTO,
} from '@/shared/api/contracts'

function modelDraft(overrides: Partial<ModelDraft> = {}): ModelDraft {
  return {
    ...emptyModelDraft(),
    providerId: 'provider-1',
    name: 'MiniMax-M2.7',
    ...overrides,
  }
}

function fullConfig(): AgentModelConfigDTO {
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
  }
}

function model(overrides: Partial<AgentModelDTO> = {}): AgentModelDTO {
  return {
    id: 'model-1',
    providerId: 'provider-1',
    name: 'MiniMax-M2.7',
    description: 'Chat model',
    config: fullConfig(),
    version: 1,
    createTime: '2026-06-20T02:00:00',
    updateTime: '2026-06-20T02:00:00',
    ...overrides,
  }
}

describe('ai-console-utils', () => {
  /** DTO codecs must populate structured current-schema drafts and stable create defaults. */
  it('builds editable drafts from DTOs and default factories', () => {
    expect(emptyProviderDraft()).toMatchObject({
      providerType: 'openai',
      modelCallTimeoutMillis: '1800000',
      modelCallIdleTimeoutMillis: '120000',
    })
    expect(
      emptyModelDraft({ id: 'provider-1' }),
    ).toMatchObject({
      providerId: 'provider-1',
      defaultVariant: 'medium',
      tools: true,
    })
    expect(emptyModelDraft().inputModalities).toEqual(['TEXT'])
    expect(emptyAgentDraft(model())).toMatchObject({
      modelId: 'model-1',
      variant: '',
    })

    expect(
      toProviderDraft({
        id: 'provider-1',
        name: 'minimax',
        description: 'provider desc',
        providerType: 'openai_response',
        baseUrl: 'https://api.minimax.io/v1',
        configured: true,
        modelCallTimeoutMillis: 120000,
        modelCallIdleTimeoutMillis: 3000,
        createTime: '2026-06-20T02:00:00',
        updateTime: '2026-06-20T02:00:00',
      }),
    ).toEqual({
      name: 'minimax',
      description: 'provider desc',
      providerType: 'openai_response',
      baseUrl: 'https://api.minimax.io/v1',
      credential: '',
      modelCallTimeoutMillis: '120000',
      modelCallIdleTimeoutMillis: '3000',
    })

    expect(
      toModelDraft(
        model({
          config: {
            ...fullConfig(),
            defaultVariant: 'quality',
            variants: [
              {
                id: 'quality',
                temperature: 0.2,
                maxOutputTokens: 256,
                topK: 32,
              },
            ],
          },
        }),
      ),
    ).toMatchObject({
      providerId: 'provider-1',
      name: 'MiniMax-M2.7',
      defaultVariant: 'quality',
      variants: [
        { id: 'quality', temperature: '0.2', maxOutputTokens: '256', topK: '32' },
      ],
    })

    expect(
      toAgentDraft({
        id: 'agent-1',
        name: 'assistant',
        description: 'desc',
        systemPrompt: 'prompt',
        modelId: 'model-1',
        variant: 'default',
        config: {
          environmentName: null,
          tools: ['search'],
          skills: [],
        },
        version: 1,
        createTime: '2026-06-20T02:00:00',
        updateTime: '2026-06-20T02:00:00',
      }),
    ).toMatchObject({
      name: 'assistant',
      modelId: 'model-1',
      tools: ['search'],
    })
  })

  /** Structured drafts must serialize to the current provider/model/agent API payload contracts. */
  it('serializes structured drafts into backend payloads', () => {
    const providerInput = {
      name: ' minimax ',
      description: ' provider desc ',
      providerType: ' openai ',
      baseUrl: ' https://api.minimax.io/v1 ',
      credential: ' secret ',
      modelCallTimeoutMillis: '120000',
      modelCallIdleTimeoutMillis: '3000',
    }
    expect(toEditableProvider(providerInput)).toEqual({
      name: 'minimax',
      description: 'provider desc',
      providerType: 'openai',
      baseUrl: 'https://api.minimax.io/v1',
      credential: 'secret',
      modelCallTimeoutMillis: 120000,
      modelCallIdleTimeoutMillis: 3000,
    })
    expect(toEditableProviderUpdate(providerInput)).toEqual({
      name: 'minimax',
      description: 'provider desc',
      providerType: 'openai',
      baseUrl: 'https://api.minimax.io/v1',
      credential: 'secret',
      modelCallTimeoutMillis: 120000,
      modelCallIdleTimeoutMillis: 3000,
    })

    const baseVariant = emptyModelDraft().variants[0]
    const editableModel = toEditableModel(
      modelDraft({
        providerId: ' provider-1 ',
        name: ' MiniMax-M2.7 ',
        description: ' chat model ',
        reasoning: true,
        defaultVariant: 'quality',
        variants: [
          {
            ...baseVariant,
            id: 'quality',
            reasoningEffort: 'high',
            temperature: '0.1',
            maxOutputTokens: '256',
            topK: '32',
          },
        ],
      }),
    )
    expect(editableModel).toMatchObject({
      providerId: 'provider-1',
      name: 'MiniMax-M2.7',
      description: 'chat model',
    })
    expect(editableModel.config).toMatchObject({
      limit: { context: 128000, output: 8192 },
      abilities: {
        tools: true,
        reasoning: true,
        inputModalities: ['TEXT'],
      },
      defaultVariant: 'quality',
    })
    expect(toEditableModelUpdate(modelDraft())).not.toHaveProperty('providerId')

    const agentInput = {
      name: ' assistant ',
      description: ' desc ',
      systemPrompt: ' prompt ',
      modelId: ' model-1 ',
      variant: ' default ',
      environmentName: '',
      tools: [' search ', ''],
      skills: [],
    }
    const expectedAgent = {
      name: 'assistant',
      description: 'desc',
      systemPrompt: 'prompt',
      modelId: 'model-1',
      variant: 'default',
      config: {
        environmentName: null,
        tools: ['search'],
        skills: [],
      },
    }
    expect(toEditableAgent(agentInput)).toEqual(expectedAgent)
    expect(toEditableAgentUpdate(agentInput)).toEqual(expectedAgent)
    expect(toEditableAgent({ ...agentInput, variant: ' ' }).variant).toBeNull()
  })

  /** Search and presentation helpers cover structured model defaults. */
  it('filters resources and formats helper values', () => {
    const agents = [
      {
        id: 'agent-1',
        name: 'assistant',
        description: 'Cloud agent',
        systemPrompt: null,
        modelId: 'model-1',
        variant: 'default',
        config: {
          environmentName: null,
          tools: [],
          skills: [],
        },
        version: 1,
        createTime: '2026-06-20T02:00:00',
        updateTime: '2026-06-20T02:00:00',
      },
    ]
    const models: AgentModelView[] = [
      { ...model({ name: 'MiniMax-M2.7' }), providerName: 'minimax' },
      { ...model({ id: 'model-2', name: 'gpt-5.4' }), providerName: 'openai' },
    ]
    const providers = [
      {
        id: 'provider-1',
        name: 'minimax',
        description: 'MiniMax endpoint',
        providerType: 'openai',
        baseUrl: 'https://api.minimax.io/v1',
        configured: true,
        modelCallTimeoutMillis: 1800000,
        modelCallIdleTimeoutMillis: 120000,
        version: 1,
        createTime: '2026-06-20T02:00:00',
        updateTime: '2026-06-20T02:00:00',
      },
      {
        id: 'provider-2',
        name: 'openai',
        description: 'OpenAI',
        providerType: 'openai_response',
        baseUrl: null,
        configured: true,
        modelCallTimeoutMillis: 1800000,
        modelCallIdleTimeoutMillis: 120000,
        version: 1,
        createTime: '2026-06-20T02:00:00',
        updateTime: '2026-06-20T02:00:00',
      },
    ]

    expect(filterAgents(agents, 'assistant')).toHaveLength(1)
    expect(filterAgents(agents, 'cloud')).toHaveLength(0)
    expect(filterModels(models, 'minimax/MiniMax-M2.7').map((item) => item.name)).toEqual(['MiniMax-M2.7'])
    expect(filterModels(models, 'default')).toHaveLength(0)
    // Sorted by full ref: minimax/... before openai/...
    expect(filterModels(models, '').map((item) => item.name)).toEqual(['MiniMax-M2.7', 'gpt-5.4'])
    expect(filterProviders(providers, 'minimax').map((item) => item.name)).toEqual(['minimax'])
    expect(filterProviders(providers, 'endpoint')).toHaveLength(0)
    expect(filterProviders(providers, '').map((item) => item.name)).toEqual(['minimax', 'openai'])
    expect(filterEnvironments([{ name: 'tool-e2e', status: 'READY', lastSeen: null, tools: [], skills: [] }, { name: 'platform', status: 'READY', lastSeen: null, tools: [], skills: [] }], '').map((item) => item.name)).toEqual([
      'platform',
      'tool-e2e',
    ])
    expect(
      filterChats(
        [
          { id: '2', title: 'beta', defaultAgentId: null, version: 1, createTime: '2026-06-21T00:00:00', updateTime: '2026-06-21T00:00:00' },
          { id: '1', title: 'alpha', defaultAgentId: null, version: 1, createTime: '2026-06-22T00:00:00', updateTime: '2026-06-20T00:00:00' },
        ],
        '',
      ).map((item) => item.title),
    ).toEqual(['alpha', 'beta'])
    expect(filterChats([{ id: '1', title: 'alpha', defaultAgentId: null, version: 1, createTime: null, updateTime: null }], 'alp')).toHaveLength(1)
    expect(naturalNameCompare('m2', 'm10')).toBeLessThan(0)
    expect(includesSearch('MiniMax', 'mini')).toBe(true)
    expect(includesSearch('MiniMax', '')).toBe(true)

    expect(resourceTitle({ kind: 'provider', mode: 'create' })).toBe('新建 Provider')
    expect(resourceTitle({ kind: 'model', mode: 'edit' })).toBe('编辑 Model')
    expect(resourceTitle({ kind: 'agent', mode: 'create' })).toBe('新建 Agent')
    expect(formatBackendDate([2026, 6, 20, 2, 1, 0, 0])).toBe('2026-06-20 02:01')
    expect(formatBackendDate('2026-06-20T02:01:00')).toBe('2026-06-20 02:01')
    expect(formatBackendDate([Number.NaN])).toBe('-')
  })

  /** Nullable optional resource fields map to complete editable drafts. */
  it('handles nullable resource fields and serialization fields', () => {
    expect(
      toProviderDraft({
        id: 'provider-2',
        name: 'stub',
        description: null,
        providerType: null,
        baseUrl: null,
        configured: false,
        modelCallTimeoutMillis: 1800000,
        modelCallIdleTimeoutMillis: 120000,
        createTime: '2026-06-20T02:00:00',
        updateTime: '2026-06-20T02:00:00',
      }),
    ).toMatchObject({
      name: 'stub',
      description: '',
      providerType: 'openai',
      baseUrl: '',
    })

    expect(toModelDraft(model({ description: null }))).toMatchObject({
      providerId: 'provider-1',
      description: '',
      contextWindow: '128000',
      maxOutputTokens: '8192',
      defaultVariant: 'default',
      variants: [{ id: 'default', reasoningEffort: '', temperature: '', topK: '' }],
    })

    expect(
      toAgentDraft({
        id: 'agent-2',
        name: 'fallback-agent',
        description: null,
        systemPrompt: null,
        modelId: 'model-2',
        variant: 'default',
        config: {
          environmentName: null,
          tools: [],
          skills: [],
        },
        version: 1,
        createTime: '2026-06-20T02:00:00',
        updateTime: '2026-06-20T02:00:00',
      }),
    ).toMatchObject({
      description: '',
      systemPrompt: '',
      variant: 'default',
      tools: [],
    })

    expect(
      toEditableProvider({
        name: ' stub ',
        description: '   ',
        providerType: ' openai ',
        baseUrl: '   ',
        credential: '   ',
        modelCallTimeoutMillis: '   ',
        modelCallIdleTimeoutMillis: '   ',
      }),
    ).toEqual({
      name: 'stub',
      description: null,
      providerType: 'openai',
      baseUrl: null,
      credential: null,
      modelCallTimeoutMillis: null,
      modelCallIdleTimeoutMillis: null,
    })

    expect(includesSearch('MiniMax', 'missing')).toBe(false)
    expect(formatBackendDate(null)).toBe('-')
  })
})
