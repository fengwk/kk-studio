import { describe, expect, it } from 'vitest'
import { setLocale } from '@/shared/i18n'
import {
  filterAgents,
  filterModels,
  filterProviders,
  formatCatalogSupportIssue,
  resourceTitle,
} from '@/features/catalog/catalog-utils'
import {
  filterChats,
  formatBackendDate,
} from '@/features/chat/chat-utils'
import { filterEnvironments } from '@/features/environment/environment-utils'
import {
  includesSearch,
  naturalNameCompare,
} from '@/shared/lib/search-utils'
import type { AgentModelView } from '@/features/catalog/AgentModelView'
import { type ModelDraft, type ProviderDraft } from '@/features/catalog/ai-console-types'
import { AGENT_PROVIDER_TYPES } from '@/shared/api/contracts/ai-catalog'
import {
  emptyAgentDraft,
  emptyModelDraft,
  emptyProviderDraft,
  toAgentDraft,
  toEditableAgent,
  toEditableAgentUpdate,
  toEditableModel,
  toEditableProvider,
  toEditableProviderUpdate,
  toModelDraft,
  toProviderDraft,
} from '@/features/catalog/ai-resource-draft-codecs'
import type {
  AgentModelConfigDTO,
  AgentModelDTO,
  AgentProviderDTO,
  ChatDTO,
  EnvironmentCardDTO,
} from '@/shared/api/generated/contracts'

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
      base: { input: '0', output: '0', cacheRead: '0', cacheWrite: '0', cacheWriteByTtl: {}, reasoning: null },
      overrides: [],
      multipliers: [],
    },
    defaultVariant: 'default',
    variants: [{ id: 'default' }],
  }
}

function model(overrides: Partial<AgentModelDTO> = {}): AgentModelDTO {
  return {
    providerName: 'minimax',
    kind: 'MODEL',
    name: 'MiniMax-M2.7',
    modelId: 'minimax-upstream',
    description: 'Chat model',
    config: fullConfig(),
    version: '1',
    createTime: 1781920800,
    updateTime: 1781920800,
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
      model: 'MiniMax-M2.7',
      variant: '',
    })

    expect(
      toProviderDraft({
        name: 'minimax',
        description: 'provider desc',
        providerType: 'openai_responses',
        baseUrl: 'https://api.minimax.io/v1',
        configured: true,
        modelCallTimeoutMillis: '120000',
        modelCallIdleTimeoutMillis: '3000',
        version: '1',
        createTime: 1781920800,
        updateTime: 1781920800,
        modelHttpRetryStatusCodes: null,
      }),
    ).toEqual({
      name: 'minimax',
      description: 'provider desc',
      providerType: 'openai_responses',
      baseUrl: 'https://api.minimax.io/v1',
      credential: '',
      modelCallTimeoutMillis: '120000',
      modelCallIdleTimeoutMillis: '3000',
      modelHttpRetryStatusCodes: null,
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
        name: 'assistant',
        description: 'desc',
        systemPrompt: 'prompt',
        type: 'USER',
        model: 'MiniMax-M2.7',
        variant: 'default',
        config: {
          inheritParentEnvironment: true,
          tools: ['search'],
          skills: [],
          subagents: [],
        },
        version: '1',
        createTime: 1781920800,
        updateTime: 1781920800,
      }),
    ).toMatchObject({
      name: 'assistant',
      model: 'MiniMax-M2.7',
      inheritParentEnvironment: true,
      tools: ['search'],
      subagents: [],
    })
  })

  /** providerType 选择项必须与 AgentProviderType wire 契约暴露同一组合法枚举。 */
  it('lists the supported provider type wire values', () => {
    expect(AGENT_PROVIDER_TYPES).toEqual(['openai', 'openai_responses', 'anthropic', 'google'])
  })

  /** 结构化草稿必须按当前的 provider/model/agent API payload 契约序列化。 */
  it('serializes structured drafts into backend payloads', () => {
    const providerInput: ProviderDraft = {
      name: ' minimax ',
      description: ' provider desc ',
      providerType: 'openai_responses',
      baseUrl: ' https://api.minimax.io/v1 ',
      credential: ' secret ',
      modelCallTimeoutMillis: '120000',
      modelCallIdleTimeoutMillis: '3000',
      modelHttpRetryStatusCodes: null,
    }
    // 注入带空白的非法枚举值，验证运行时输入标准化。
    Reflect.set(providerInput, 'providerType', ' openai_responses ')
    expect(toEditableProvider(providerInput)).toEqual({
      name: 'minimax',
      description: 'provider desc',
      providerType: 'openai_responses',
      baseUrl: 'https://api.minimax.io/v1',
      credential: 'secret',
      modelCallTimeoutMillis: 120000,
      modelCallIdleTimeoutMillis: 3000,
      modelHttpRetryStatusCodes: null,
    })
    expect(toEditableProviderUpdate(providerInput)).toEqual({
      description: 'provider desc',
      providerType: 'openai_responses',
      baseUrl: 'https://api.minimax.io/v1',
      credential: 'secret',
      modelCallTimeoutMillis: 120000,
      modelCallIdleTimeoutMillis: 3000,
      modelHttpRetryStatusCodes: null,
    })

    // Custom retry codes serialization
    expect(
      toEditableProviderUpdate({
        ...providerInput,
        modelHttpRetryStatusCodes: [408, '429', 500],
      }),
    ).toMatchObject({
      modelHttpRetryStatusCodes: [408, 429, 500],
    })

    // Disabled retry codes ([]) serialization
    expect(
      toEditableProviderUpdate({
        ...providerInput,
        modelHttpRetryStatusCodes: [],
      }),
    ).toMatchObject({
      modelHttpRetryStatusCodes: [],
    })

    // Inherit (null) serialization
    expect(
      toEditableProviderUpdate({
        ...providerInput,
        modelHttpRetryStatusCodes: null,
      }),
    ).toMatchObject({
      modelHttpRetryStatusCodes: null,
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

    const agentInput = {
      name: ' assistant ',
      description: ' desc ',
      systemPrompt: ' prompt ',
      model: ' MiniMax-M2.7 ',
      variant: ' default ',
      inheritParentEnvironment: true,
      tools: [' search ', ''],
      skills: [],
      subagents: [' helper '],
    }
    const expectedAgent = {
      name: 'assistant',
      description: 'desc',
      systemPrompt: 'prompt',
      model: 'MiniMax-M2.7',
      variant: 'default',
      config: {
        inheritParentEnvironment: true,
        tools: ['search'],
        skills: [],
        subagents: ['helper'],
      },
    }
    expect(toEditableAgent(agentInput)).toEqual(expectedAgent)
    expect(toEditableAgentUpdate(agentInput)).toEqual({
      description: 'desc',
      systemPrompt: 'prompt',
      model: 'MiniMax-M2.7',
      variant: 'default',
      config: {
        inheritParentEnvironment: true,
        tools: ['search'],
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
        type: 'USER' as const,
        model: 'MiniMax-M2.7',
        variant: 'default',
        config: {
          inheritParentEnvironment: true,
          tools: [],
          skills: [],
          subagents: [],
        },
        version: '1',
        createTime: 1781920800,
        updateTime: 1781920800,
      },
    ]
    const models: AgentModelView[] = [
      { ...model({ name: 'MiniMax-M2.7' }), providerName: 'minimax' },
      { ...model({ name: 'gpt-5.4', modelId: 'gpt-5.4' }), providerName: 'openai' },
    ]
    const providers: AgentProviderDTO[] = [
      {
        name: 'minimax',
        description: 'MiniMax endpoint',
        providerType: 'openai',
        baseUrl: 'https://api.minimax.io/v1',
        configured: true,
        modelCallTimeoutMillis: '1800000',
        modelCallIdleTimeoutMillis: '120000',
        version: '1',
        createTime: 1781920800,
        updateTime: 1781920800,
        modelHttpRetryStatusCodes: null,
      },
      {
        name: 'openai',
        description: 'OpenAI',
        providerType: 'openai_responses',
        baseUrl: null,
        configured: true,
        modelCallTimeoutMillis: '1800000',
        modelCallIdleTimeoutMillis: '120000',
        version: '1',
        createTime: 1781920800,
        updateTime: 1781920800,
        modelHttpRetryStatusCodes: null,
      },
    ]

    expect(filterAgents(agents, 'assistant')).toHaveLength(1)
    expect(filterAgents(agents, 'cloud')).toHaveLength(0)
    expect(filterModels(models, 'MiniMax-M2.7').map((item) => item.name)).toEqual(['MiniMax-M2.7'])
    expect(filterModels(models, 'default')).toHaveLength(0)
    // 按全局唯一逻辑名排序。
    expect(filterModels(models, '').map((item) => item.name)).toEqual(['gpt-5.4', 'MiniMax-M2.7'])
    expect(filterProviders(providers, 'minimax').map((item) => item.name)).toEqual(['minimax'])
    expect(filterProviders(providers, 'endpoint')).toHaveLength(0)
    expect(filterProviders(providers, '').map((item) => item.name)).toEqual(['minimax', 'openai'])
    const envA = { name: 'tool-e2e', status: 'READY', lastSeen: null, capabilities: [], skills: [] }
    const envB = { name: 'platform', status: 'READY', lastSeen: null, capabilities: [], skills: [] }
    expect(filterEnvironments([envA, envB] as unknown as EnvironmentCardDTO[], '').map((item) => item.name)).toEqual([
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
            environmentName: null,
            yoloEnabled: false,
            version: '1',
            createTime: 1782000000,
            updateTime: 1782000000,
          } satisfies ChatDTO,
          {
            id: '1',
            title: 'alpha',
            agentName: 'missing',
            environmentName: null,
            yoloEnabled: false,
            version: '1',
            createTime: 1782086400,
            updateTime: 1781913600,
          } satisfies ChatDTO,
        ],
        '',
      ).map((item) => item.title),
    ).toEqual(['alpha', 'beta'])
      expect(filterChats([{
        id: '1',
        title: 'alpha',
        agentName: 'missing',
        environmentName: null,
        yoloEnabled: false,
        version: '1',
        createTime: 0,
        updateTime: 0,
      } satisfies ChatDTO], 'alp')).toHaveLength(1)
    expect(naturalNameCompare('m2', 'm10')).toBeLessThan(0)
    expect(includesSearch('MiniMax', 'mini')).toBe(true)
    expect(includesSearch('MiniMax', '')).toBe(true)

    expect(resourceTitle({ kind: 'provider', mode: 'create' })).toBe('新建 Provider')
    expect(resourceTitle({ kind: 'model', mode: 'edit', resourceKind: 'MODEL', name: 'MiniMax-M2.7', expectedVersion: '1' })).toBe('编辑 Model')
    expect(resourceTitle({ kind: 'agent', mode: 'create', type: 'USER' })).toBe('新建 Agent')
    expect(formatBackendDate(Number.NaN)).toBe('-')
  })

  /** 可为空的 optional 资源字段映射为完整的可编辑草稿。 */
  it('handles nullable resource fields and serialization fields', () => {
    expect(
      Reflect.apply(toProviderDraft, undefined, [{
        name: 'stub',
        description: null,
        providerType: null,
        baseUrl: null,
        configured: false,
        modelCallTimeoutMillis: 1800000,
        modelCallIdleTimeoutMillis: 120000,
        version: '1',
        createTime: 1781920800,
        updateTime: 1781920800,
        modelHttpRetryStatusCodes: null,
      }]),
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
        name: 'fallback-agent',
        description: null,
        systemPrompt: null,
        type: 'USER',
        model: 'gpt-5.4',
        variant: 'default',
        config: {
          inheritParentEnvironment: true,
          tools: [],
          skills: [],
          subagents: [],
        },
        version: '1',
        createTime: 1781920800,
        updateTime: 1781920800,
      }),
    ).toMatchObject({
      description: '',
      systemPrompt: '',
      variant: 'default',
      tools: [],
      subagents: [],
    })

    expect(
      Reflect.apply(toEditableProvider, undefined, [{
        name: ' stub ',
        description: '   ',
        providerType: ' openai ',
        baseUrl: '   ',
        credential: '   ',
        modelCallTimeoutMillis: '   ',
        modelCallIdleTimeoutMillis: '   ',
        modelHttpRetryStatusCodes: null,
      }]),
    ).toEqual({
      name: 'stub',
      description: null,
      providerType: 'openai',
      baseUrl: null,
      credential: null,
      modelCallTimeoutMillis: null,
      modelCallIdleTimeoutMillis: null,
      modelHttpRetryStatusCodes: null,
    })

    expect(includesSearch('MiniMax', 'missing')).toBe(false)
  })

  it('maps known diagnostics to user warnings and keeps unknown issues verbatim', () => {
    setLocale('zh-CN')
    expect(formatCatalogSupportIssue('ignored region multiplier'))
      .toBe('已忽略地区倍率，统计费用可能低于实际账单')
    expect(formatCatalogSupportIssue('non-token cost is not included: web_search'))
      .toBe('网页搜索附加费用未计入Token价格')
    expect(formatCatalogSupportIssue('unsupported condition: tier'))
      .toBe('unsupported condition: tier')

    setLocale('en-US')
    expect(formatCatalogSupportIssue('ignored region multiplier'))
      .toBe('Region multipliers ignored; estimated costs may be lower than your actual bill')
    expect(formatCatalogSupportIssue('non-token cost is not included: web_search'))
      .toBe('Web search surcharges are not included in token prices')
    expect(formatCatalogSupportIssue('unsupported condition: tier'))
      .toBe('unsupported condition: tier')
  })
})
