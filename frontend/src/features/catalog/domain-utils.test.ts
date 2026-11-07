import { describe, expect, it } from 'vitest'
import { setLocale } from '@/shared/i18n'
import {
  filterAgents,
  filterModels,
  filterProviders,
  formatCatalogSupportIssue,
  resourceTitle,
} from '@/features/catalog/catalog-utils'
import { filterEnvironments } from '@/features/environment/environment-utils'
import {
  includesSearch,
  naturalNameCompare,
} from '@/shared/lib/search-utils'
import { type ModelDraft, type ProviderDraft } from '@/features/catalog/ai-console-types'
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
  ModelConfigDTO,
  ModelDTO,
  SingleModelDTO,
  ProviderDTO,
  EnvironmentDTO,
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

function fullConfig(): ModelConfigDTO {
  return {
    limit: { context: 128000, output: 8192 },
    abilities: { tools: true, reasoning: false, inputModalities: ['TEXT'] },
    pricing: {
      base: { input: '0', output: '0', cacheRead: '0', cacheWrite: '0', cacheWriteByTtl: {}, reasoning: null },
      overrides: [],
      multipliers: [],
    },
    defaultVariant: 'default',
    variants: [{ id: 'default', promptCacheRetention: 'NONE' }],
  }
}

function model(overrides: Partial<SingleModelDTO> = {}): SingleModelDTO {
  return {
    providerName: 'minimax',
    type: 'MODEL',
    name: 'MiniMax-M2.7',
    modelId: 'minimax-upstream',
    description: 'Chat model',
    config: fullConfig(),
    version: '1',
    createdAt: 1781920800,
    updatedAt: 1781920800,
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
        promptCacheRetentions: ['NONE', 'SHORT', 'LONG'],
        baseUrl: 'https://api.minimax.io/v1',
        credential: { configured: true },
        modelCallTimeoutMillis: '120000',
        modelCallIdleTimeoutMillis: '3000',
        version: '1',
        createdAt: 1781920800,
        updatedAt: 1781920800,
        modelHttpRetryStatusCodes: null,
      }),
    ).toEqual({
      name: 'minimax',
      description: 'provider desc',
      providerType: 'openai_responses',
      baseUrl: 'https://api.minimax.io/v1',
      credential: '',
      credentialConfigured: true,
      clearCredential: false,
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
                promptCacheRetention: 'NONE',
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
        execution: { driver: 'builtin', settings: { modelName: 'MiniMax-M2.7', variant: 'default' } },
        config: {
          tools: ['search'],
          skills: [],
          subagents: [],
        },
        version: '1',
        createdAt: 1781920800,
        updatedAt: 1781920800,
      }),
    ).toMatchObject({
      name: 'assistant',
      model: 'MiniMax-M2.7',
      tools: ['search'],
      subagents: [],
    })
  })

  /** 结构化草稿必须按当前的 provider/model/agent API payload 契约序列化。 */
  it('serializes structured drafts into backend payloads', () => {
    const providerInput: ProviderDraft = {
      name: ' minimax ',
      description: ' provider desc ',
      providerType: 'openai_responses',
      baseUrl: ' https://api.minimax.io/v1 ',
      credential: ' secret ',
      credentialConfigured: true,
      clearCredential: false,
      modelCallTimeoutMillis: '120000',
      modelCallIdleTimeoutMillis: '3000',
      modelHttpRetryStatusCodes: null,
    }
    // 枚举取自已生成的 ProviderType 契约，序列化时原样发送。
    expect(toEditableProvider(providerInput)).toEqual({
      name: 'minimax',
      description: 'provider desc',
      providerType: 'openai_responses',
      baseUrl: 'https://api.minimax.io/v1',
      credential: { operation: 'SET', value: 'secret' },
      modelCallTimeoutMillis: '120000',
      modelCallIdleTimeoutMillis: '3000',
      modelHttpRetryStatusCodes: null,
    })
    expect(toEditableProviderUpdate(providerInput)).toEqual({
      description: 'provider desc',
      baseUrl: 'https://api.minimax.io/v1',
      credential: { operation: 'SET', value: 'secret' },
      modelCallTimeoutMillis: '120000',
      modelCallIdleTimeoutMillis: '3000',
      modelHttpRetryStatusCodes: null,
    })

    // 更新契约的 credential 是必填 SecretUpdate：显式清除与保留都必须有明确操作。
    expect(
      toEditableProviderUpdate({ ...providerInput, credential: '', clearCredential: true }),
    ).toMatchObject({ credential: { operation: 'CLEAR' } })
    expect(
      toEditableProviderUpdate({ ...providerInput, credential: '' }),
    ).toMatchObject({ credential: { operation: 'KEEP' } })
    // 创建只接受 SET：草稿上的 clear 标记不会变成创建请求里的 CLEAR。
    expect(
      toEditableProvider({ ...providerInput, clearCredential: true }).credential,
    ).toEqual({ operation: 'SET', value: 'secret' })

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
      tools: [' search ', ''],
      skills: [],
      subagents: [' helper '],
    }
    const expectedAgent = {
      name: 'assistant',
      description: 'desc',
      systemPrompt: 'prompt',
      execution: { driver: 'builtin', settings: { modelName: 'MiniMax-M2.7', variant: 'default' } },
      config: {
        tools: ['search'],
        skills: [],
        subagents: ['helper'],
      },
    }
    expect(toEditableAgent(agentInput)).toEqual(expectedAgent)
    expect(toEditableAgentUpdate(agentInput)).toEqual({
      description: 'desc',
      systemPrompt: 'prompt',
      execution: { driver: 'builtin', settings: { modelName: 'MiniMax-M2.7', variant: 'default' } },
      config: {
        tools: ['search'],
        skills: [],
        subagents: ['helper'],
      },
    })
    expect(toEditableAgent({ ...agentInput, variant: ' ' }).execution).toEqual({
      driver: 'builtin', settings: { modelName: 'MiniMax-M2.7', variant: null },
    })
  })

  /** 搜索与展示辅助函数覆盖结构化的 model 默认值。 */
  it('filters resources and formats helper values', () => {
    const agents = [
      {
        name: 'assistant',
        description: 'Cloud agent',
        systemPrompt: null,
        type: 'USER' as const,
        execution: { driver: 'builtin', settings: { modelName: 'MiniMax-M2.7', variant: 'default' } },
        config: {
          tools: [],
          skills: [],
          subagents: [],
        },
        version: '1',
        createdAt: 1781920800,
        updatedAt: 1781920800,
      },
    ]
    const models: ModelDTO[] = [
      { ...model({ name: 'MiniMax-M2.7' }), providerName: 'minimax' },
      { ...model({ name: 'gpt-5.4', modelId: 'gpt-5.4' }), providerName: 'openai' },
    ]
    const providers: ProviderDTO[] = [
      {
        name: 'minimax',
        description: 'MiniMax endpoint',
        providerType: 'openai',
        promptCacheRetentions: ['NONE', 'SHORT', 'LONG'],
        baseUrl: 'https://api.minimax.io/v1',
        credential: { configured: true },
        modelCallTimeoutMillis: '1800000',
        modelCallIdleTimeoutMillis: '120000',
        version: '1',
        createdAt: 1781920800,
        updatedAt: 1781920800,
        modelHttpRetryStatusCodes: null,
      },
      {
        name: 'openai',
        description: 'OpenAI',
        providerType: 'openai_responses',
        promptCacheRetentions: ['NONE', 'SHORT', 'LONG'],
        baseUrl: null,
        credential: { configured: true },
        modelCallTimeoutMillis: '1800000',
        modelCallIdleTimeoutMillis: '120000',
        version: '1',
        createdAt: 1781920800,
        updatedAt: 1781920800,
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
    expect(filterEnvironments([envA, envB] as unknown as EnvironmentDTO[], '').map((item) => item.name)).toEqual([
      'platform',
      'tool-e2e',
    ])
    expect(naturalNameCompare('m2', 'm10')).toBeLessThan(0)
    expect(includesSearch('MiniMax', 'mini')).toBe(true)
    expect(includesSearch('MiniMax', '')).toBe(true)

    expect(resourceTitle({ kind: 'provider', mode: 'create' })).toBe('新建 Provider')
    expect(resourceTitle({ kind: 'model', mode: 'edit', resourceKind: 'MODEL', name: 'MiniMax-M2.7', expectedVersion: '1' })).toBe('编辑 Model')
    expect(resourceTitle({ kind: 'agent', mode: 'create', type: 'USER' })).toBe('新建 Agent')
  })

  /** 可为空的 optional 资源字段映射为完整的可编辑草稿。 */
  it('handles nullable resource fields and serialization fields', () => {
    expect(
      Reflect.apply(toProviderDraft, undefined, [{
        name: 'stub',
        description: null,
        providerType: 'openai',
        baseUrl: null,
        credential: { configured: false },
        modelCallTimeoutMillis: '1800000',
        modelCallIdleTimeoutMillis: '120000',
        version: '1',
        createdAt: 1781920800,
        updatedAt: 1781920800,
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
        execution: { driver: 'builtin', settings: { modelName: 'gpt-5.4', variant: 'default' } },
        config: {
          tools: [],
          skills: [],
          subagents: [],
        },
        version: '1',
        createdAt: 1781920800,
        updatedAt: 1781920800,
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
        providerType: 'openai',
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
