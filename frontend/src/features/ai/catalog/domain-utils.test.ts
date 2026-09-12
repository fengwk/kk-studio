import { describe, expect, it } from 'vitest'
import {
  filterAgents,
  filterModels,
  filterProviders,
  resourceTitle,
} from '@/features/ai/catalog/catalog-utils'
import {
  filterChats,
  formatBackendDate,
} from '@/features/ai/chat/chat-utils'
import { filterEnvironments } from '@/features/ai/environment/environment-utils'
import {
  includesSearch,
  naturalNameCompare,
} from '@/shared/lib/search-utils'
import type { AgentModelView } from '@/features/ai/catalog/AgentModelView'
import type { ModelDraft } from '@/features/ai/catalog/ai-console-types'
import type { ChatDTO } from '@/shared/api/contracts/ai-chat'
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
} from '@/features/ai/catalog/ai-resource-draft-codecs'
import type {
  AgentModelDTO,
  AgentModelConfigDTO,
} from '@/shared/api/contracts/ai-catalog'

function modelDraft(overrides: Partial<ModelDraft> = {}): ModelDraft {
  return {
    ...emptyModelDraft(),
    providerName: 'minimax',
    name: 'MiniMax-M2.7',
    modelId: 'minimax-upstream',
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
    providerName: 'minimax',
    name: 'MiniMax-M2.7',
    modelId: 'minimax-upstream',
    description: 'Chat model',
    config: fullConfig(),
    version: '1',
    createTime: '2026-06-20T02:00:00',
    updateTime: '2026-06-20T02:00:00',
    ...overrides,
  }
}

describe('AI domain utilities', () => {
  /** DTO 编解码器必须填充结构化的当前 schema 草稿，并提供稳定的创建默认值。 */
  it('builds editable drafts from DTOs and default factories', () => {
    expect(emptyProviderDraft()).toMatchObject({
      providerType: 'openai',
      modelCallTimeoutMillis: '1800000',
      modelCallIdleTimeoutMillis: '120000',
    })
    expect(
      emptyModelDraft({ name: 'minimax' }),
    ).toMatchObject({
      providerName: 'minimax',
      defaultVariant: 'medium',
      tools: true,
    })
    expect(emptyModelDraft().inputModalities).toEqual(['TEXT'])
    expect(emptyAgentDraft(model())).toMatchObject({
      model: 'minimax/MiniMax-M2.7',
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
                reasoningEffort: 'high',
              },
            ],
          },
        }),
      ),
    ).toMatchObject({
      providerName: 'minimax',
      name: 'MiniMax-M2.7',
      modelId: 'minimax-upstream',
      defaultVariant: 'quality',
      variants: [
        { id: 'quality', reasoningEffort: 'high' },
      ],
    })

    expect(
      toAgentDraft({
        id: 'agent-1',
        name: 'assistant',
        description: 'desc',
        systemPrompt: 'prompt',
        model: 'minimax/MiniMax-M2.7',
        variant: 'default',
        config: {
          toolIds: ['base.search'],
          skills: [],
          subagents: [],
        },
        version: '1',
        createTime: '2026-06-20T02:00:00',
        updateTime: '2026-06-20T02:00:00',
      }),
    ).toMatchObject({
      name: 'assistant',
      model: 'minimax/MiniMax-M2.7',
      toolIds: ['base.search'],
      subagents: [],
    })
  })

  /** 结构化草稿必须按当前的 provider/model/agent API payload 契约序列化。 */
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
        providerName: ' minimax ',
        name: ' MiniMax-M2.7 ',
        description: ' chat model ',
        reasoning: true,
        defaultVariant: 'quality',
        variants: [
          {
            ...baseVariant,
            id: 'quality',
            reasoningEffort: 'high',
          },
        ],
      }),
    )
    expect(editableModel).toMatchObject({
      providerName: 'minimax',
      name: 'MiniMax-M2.7',
      modelId: 'minimax-upstream',
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
    expect(toEditableModelUpdate(modelDraft())).not.toHaveProperty('providerName')

    const agentInput = {
      name: ' assistant ',
      description: ' desc ',
      systemPrompt: ' prompt ',
      model: ' minimax/MiniMax-M2.7 ',
      variant: ' default ',
      environmentId: ' env-uuid-1 ',
      toolIds: [' base.search ', ''],
      skills: [],
      subagents: [' helper '],
    }
    const expectedAgent = {
      name: 'assistant',
      description: 'desc',
      systemPrompt: 'prompt',
      model: 'minimax/MiniMax-M2.7',
      variant: 'default',
      environmentId: 'env-uuid-1',
      config: {
        toolIds: ['base.search'],
        skills: [],
        subagents: ['helper'],
      },
    }
    expect(toEditableAgent(agentInput)).toEqual(expectedAgent)
    expect(toEditableAgentUpdate(agentInput)).toEqual({
      description: 'desc',
      systemPrompt: 'prompt',
      model: 'minimax/MiniMax-M2.7',
      variant: 'default',
      environmentId: 'env-uuid-1',
      config: {
        toolIds: ['base.search'],
        skills: [],
        subagents: ['helper'],
      },
    })
    expect(toEditableAgent({ ...agentInput, variant: ' ' }).variant).toBeNull()
  })

  /** 搜索与展示辅助函数覆盖结构化的 model 默认值。 */
  it('filters resources and formats helper values', () => {
    const agents = [
      {
        id: 'agent-1',
        name: 'assistant',
        description: 'Cloud agent',
        systemPrompt: null,
        model: 'minimax/MiniMax-M2.7',
        variant: 'default',
        config: {
          toolIds: [],
          skills: [],
          subagents: [],
        },
        version: '1',
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
        version: '1',
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
        version: '1',
        createTime: '2026-06-20T02:00:00',
        updateTime: '2026-06-20T02:00:00',
      },
    ]

    expect(filterAgents(agents, 'assistant')).toHaveLength(1)
    expect(filterAgents(agents, 'cloud')).toHaveLength(0)
    expect(filterModels(models, 'minimax/MiniMax-M2.7').map((item) => item.name)).toEqual(['MiniMax-M2.7'])
    expect(filterModels(models, 'default')).toHaveLength(0)
    // 按完整 ref 排序：minimax/... 在 openai/... 之前
    expect(filterModels(models, '').map((item) => item.name)).toEqual(['MiniMax-M2.7', 'gpt-5.4'])
    expect(filterProviders(providers, 'minimax').map((item) => item.name)).toEqual(['minimax'])
    expect(filterProviders(providers, 'endpoint')).toHaveLength(0)
    expect(filterProviders(providers, '').map((item) => item.name)).toEqual(['minimax', 'openai'])
    expect(filterEnvironments([{ name: 'tool-e2e', status: 'READY', lastSeen: null, capabilities: [], skills: [] }, { name: 'platform', status: 'READY', lastSeen: null, capabilities: [], skills: [] }], '').map((item) => item.name)).toEqual([
      'platform',
      'tool-e2e',
    ])
    expect(
      filterChats(
        [
          {
            id: '2',
            title: 'beta',
            agentName: 'missing',
            yoloEnabled: false,
            version: '1',
            createTime: '2026-06-21T00:00:00',
            updateTime: '2026-06-21T00:00:00',
          } satisfies ChatDTO,
          {
            id: '1',
            title: 'alpha',
            agentName: 'missing',
            yoloEnabled: false,
            version: '1',
            createTime: '2026-06-22T00:00:00',
            updateTime: '2026-06-20T00:00:00',
          } satisfies ChatDTO,
        ],
        '',
      ).map((item) => item.title),
    ).toEqual(['alpha', 'beta'])
      expect(filterChats([{
        id: '1',
        title: 'alpha',
        agentName: 'missing',
        yoloEnabled: false,
        version: '1',
        createTime: null,
        updateTime: null,
      } satisfies ChatDTO], 'alp')).toHaveLength(1)
    expect(naturalNameCompare('m2', 'm10')).toBeLessThan(0)
    expect(includesSearch('MiniMax', 'mini')).toBe(true)
    expect(includesSearch('MiniMax', '')).toBe(true)

    expect(resourceTitle({ kind: 'provider', mode: 'create' })).toBe('新建 Provider')
    expect(resourceTitle({ kind: 'model', mode: 'edit' })).toBe('编辑 Model')
    expect(resourceTitle({ kind: 'agent', mode: 'create' })).toBe('新建 Agent')
    expect(formatBackendDate([2026, 6, 20, 2, 1, 0, 0])).toBe('2026-06-20 02:01')
    expect(formatBackendDate('2026-06-20T02:01:00')).toBe('2026-06-20 02:01')
    expect(formatBackendDate(0)).toBe('-')
    expect(formatBackendDate(1_782_000_000)).toBe('2026-06-21 00:00')
    expect(formatBackendDate([Number.NaN])).toBe('-')
  })

  /** 可为空的 optional 资源字段映射为完整的可编辑草稿。 */
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
      providerName: 'minimax',
      modelId: 'minimax-upstream',
      description: '',
      contextWindow: '128000',
      maxOutputTokens: '8192',
      defaultVariant: 'default',
      variants: [{ id: 'default', reasoningEffort: '' }],
    })

    expect(
      toAgentDraft({
        id: 'agent-2',
        name: 'fallback-agent',
        description: null,
        systemPrompt: null,
        model: 'openai/gpt-5.4',
        variant: 'default',
        config: {
          toolIds: [],
          skills: [],
          subagents: [],
        },
        version: '1',
        createTime: '2026-06-20T02:00:00',
        updateTime: '2026-06-20T02:00:00',
      }),
    ).toMatchObject({
      description: '',
      systemPrompt: '',
      variant: 'default',
      toolIds: [],
      subagents: [],
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
