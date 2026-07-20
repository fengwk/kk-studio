import { describe, expect, it } from 'vitest'
import {
  filterAgents,
  filterModels,
  filterProviders,
  formatBackendDate,
  formatJsonSummary,
  includesSearch,
  resourceTitle,
} from '@/features/ai/ai-console-utils'
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
import type { AgentModelDTO } from '@/shared/api/contracts'

function modelDraft(overrides: Partial<ModelDraft> = {}): ModelDraft {
  return {
    ...emptyModelDraft(),
    providerId: 'provider-1',
    name: 'MiniMax-M2.7',
    ...overrides,
  }
}

function modelConfig(overrides: Record<string, unknown> = {}) {
  return {
    limit: { context: 128000, output: 8192 },
    abilities: {
      tools: true,
      reasoning: false,
      modalities: { input: ['TEXT'], output: ['TEXT'] },
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
    defaultVariant: 'default',
    variants: [{ id: 'default' }],
    ...overrides,
  }
}

function model(overrides: Partial<AgentModelDTO> = {}): AgentModelDTO {
  return {
    id: 'model-1',
    providerId: 'provider-1',
    providerName: 'minimax',
    name: 'MiniMax-M2.7',
    description: 'Chat model',
    capabilitiesJson: '["TEXT","TOOLS"]',
    configJson: JSON.stringify(modelConfig()),
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
      emptyModelDraft(undefined, {
        id: 'provider-1',
        name: 'minimax',
      } as never),
    ).toMatchObject({
      providerId: 'provider-1',
      defaultVariant: 'medium',
      tools: true,
      inputModalities: ['TEXT'],
    })
    expect(emptyAgentDraft(model())).toMatchObject({
      modelId: 'model-1',
      variant: 'default',
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
          configJson: JSON.stringify(
            modelConfig({
              defaultVariant: 'quality',
              variants: [{ id: 'quality', temperature: 0.2, maxOutputTokens: 256, topK: 32 }],
            }),
          ),
        }),
      ),
    ).toMatchObject({
      providerId: 'provider-1',
      name: 'MiniMax-M2.7',
      defaultVariant: 'quality',
      variants: [{ name: 'quality', temperature: '0.2', maxOutputTokens: '256', topK: '32' }],
    })

    expect(
      toAgentDraft({
        id: 'agent-1',
        name: 'assistant',
        description: 'desc',
        systemPrompt: 'prompt',
        modelId: 'model-1',
        variant: 'default',
        config: { tools: ['search'], skills: [], allowedSubagents: [] },
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
            name: 'quality',
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
      capabilitiesJson: '["TEXT","TOOLS","THINKING"]',
    })
    expect(JSON.parse(editableModel.configJson)).toMatchObject({
      limit: { context: 128000, output: 8192 },
      abilities: {
        tools: true,
        reasoning: true,
        modalities: { input: ['TEXT'], output: ['TEXT'] },
      },
      defaultVariant: 'quality',
      variants: [
        {
          id: 'quality',
          reasoningEffort: 'high',
          temperature: 0.1,
          maxOutputTokens: 256,
          topK: 32,
        },
      ],
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
      allowedSubagents: [],
      executionPolicy: {
        maxTurns: '',
        maxDepth: '',
        maxDirectSubagents: '',
        maxTotalSubagents: '',
      },
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
        allowedSubagents: [],
        executionPolicy: null,
      },
    }
    expect(toEditableAgent(agentInput)).toEqual(expectedAgent)
    expect(toEditableAgentUpdate(agentInput)).toEqual(expectedAgent)
  })

  /** Search and presentation helpers cover model defaults now stored inside configJson. */
  it('filters resources and formats helper values', () => {
    const agents = [
      {
        id: 'agent-1',
        name: 'assistant',
        description: 'Cloud agent',
        systemPrompt: null,
        modelId: 'model-1',
        variant: 'default',
        config: { tools: [] },
        createTime: '2026-06-20T02:00:00',
        updateTime: '2026-06-20T02:00:00',
      },
    ]
    const models = [model()]
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
        createTime: '2026-06-20T02:00:00',
        updateTime: '2026-06-20T02:00:00',
      },
    ]

    expect(filterAgents(agents, 'cloud')).toHaveLength(1)
    expect(filterModels(models, 'default')).toHaveLength(1)
    expect(filterProviders(providers, 'endpoint')).toHaveLength(1)
    expect(includesSearch('MiniMax', 'mini')).toBe(true)
    expect(includesSearch('MiniMax', '')).toBe(true)

    expect(resourceTitle({ kind: 'provider', mode: 'create' })).toBe('新建 Provider')
    expect(resourceTitle({ kind: 'model', mode: 'edit' })).toBe('编辑 Model')
    expect(resourceTitle({ kind: 'agent', mode: 'create' })).toBe('新建 Agent')
    expect(formatJsonSummary(null)).toBe('default')
    expect(formatJsonSummary('[{"id":"default"}]')).toBe('1 item')
    expect(formatJsonSummary('{"vision":true,"audio":false}')).toBe('2 keys')
    expect(formatJsonSummary('{broken')).toBe('invalid json')
    expect(formatBackendDate([2026, 6, 20, 2, 1, 0, 0])).toBe('2026-06-20 02:01')
    expect(formatBackendDate('2026-06-20T02:01:00')).toBe('2026-06-20 02:01')
    expect(formatBackendDate([Number.NaN])).toBe('-')
  })

  /** Malformed persisted model JSON falls back to a complete editable draft without reviving legacy fields. */
  it('handles malformed backend drafts and nullable serialization fields', () => {
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

    expect(toModelDraft(model({ configJson: '{broken', description: null }))).toMatchObject({
      providerId: 'provider-1',
      description: '',
      contextWindow: '128000',
      maxOutputTokens: '8192',
      defaultVariant: 'medium',
      variants: [{ name: 'medium', reasoningEffort: '', temperature: '', topK: '' }],
    })

    expect(
      toAgentDraft({
        id: 'agent-2',
        name: 'fallback-agent',
        description: null,
        systemPrompt: null,
        modelId: 'model-2',
        variant: '',
        config: null,
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
    expect(formatJsonSummary('123')).toBe('default')
    expect(formatBackendDate(null)).toBe('-')
  })
})
